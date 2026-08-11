package com.studyflow.library.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.common.hash.Sha256;
import com.studyflow.common.id.UuidV7;
import com.studyflow.common.quota.QuotaService;
import com.studyflow.common.quota.UsageMetric;
import com.studyflow.jobs.domain.AiJob;
import com.studyflow.jobs.domain.TaskType;
import com.studyflow.jobs.service.JobEnqueueService;
import com.studyflow.library.domain.Document;
import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.library.repo.DocumentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Direct multipart upload (a documented deviation from the spec's presigned-upload flow — see
 * docs/DECISIONS.md). Magic-byte sniffing never trusts the client's declared MIME type (see
 * specs/05-library-and-storage.md).
 */
@Service
public class DocumentUploadService {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private final DocumentRepository documentRepository;
    private final StorageProvider storageProvider;
    private final QuotaService quotaService;
    private final JobEnqueueService jobEnqueueService;
    private final long maxFileSizeBytes;
    private final long uploadsPerMonth;

    public DocumentUploadService(DocumentRepository documentRepository, StorageProvider storageProvider,
            QuotaService quotaService, JobEnqueueService jobEnqueueService,
            @Value("${studyflow.upload.max-file-size-bytes}") long maxFileSizeBytes,
            @Value("${studyflow.quota.uploads-per-month}") long uploadsPerMonth) {
        this.documentRepository = documentRepository;
        this.storageProvider = storageProvider;
        this.quotaService = quotaService;
        this.jobEnqueueService = jobEnqueueService;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.uploadsPerMonth = uploadsPerMonth;
    }

    public record UploadResult(UUID documentId, UUID jobId) {
    }

    @Transactional
    public UploadResult upload(UUID ownerId, MultipartFile file, String titleOverride, String idempotencyKey) {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "File is empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "File exceeds the configured size limit");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_CORRUPT, "Could not read the uploaded file");
        }

        DocumentFileType fileType = sniff(content, file.getOriginalFilename());
        String sha256 = sha256Hex(content);

        Optional<Document> existing = documentRepository.findByOwnerIdAndContentSha256AndDeletedAtIsNull(ownerId,
                sha256);
        if (existing.isPresent()) {
            // Re-uploading the same bytes reuses the existing document instead of re-billing
            // ingestion — see specs/05-library-and-storage.md.
            return new UploadResult(existing.get().getId(), null);
        }

        quotaService.incrementAndEnforce(ownerId, UsageMetric.UPLOADS, uploadsPerMonth,
                ErrorCode.QUOTA_UPLOADS_EXCEEDED);

        UUID documentId = UuidV7.generate();
        String normalizedFilename = normalizeFilename(file.getOriginalFilename());
        String storageKey = "users/" + ownerId + "/" + documentId + "/" + safeFilename(normalizedFilename);
        String title = (titleOverride == null || titleOverride.isBlank()) ? normalizedFilename : titleOverride;

        Document document = new Document(documentId, ownerId, title, normalizedFilename,
                mimeTypeFor(fileType), fileType, content.length, sha256, storageKey,
                storageProvider.providerName());
        storageProvider.store(content, storageKey);
        registerStorageCleanupOnRollback(storageKey);
        documentRepository.save(document);

        String paramsJson = "{\"documentId\":\"" + documentId + "\"}";
        String fingerprint = JobEnqueueService.fingerprint(documentId.toString(), TaskType.DOCUMENT_INGEST.name());
        AiJob job = jobEnqueueService.enqueue(ownerId, TaskType.DOCUMENT_INGEST, paramsJson, idempotencyKey,
                fingerprint);

        return new UploadResult(documentId, job.getId());
    }

    private DocumentFileType sniff(byte[] content, String originalFilename) {
        if (startsWith(content, PDF_MAGIC)) {
            return DocumentFileType.PDF;
        }
        if (startsWith(content, ZIP_MAGIC)) {
            DocumentFileType ooxmlType = sniffOoxmlEntryNames(content);
            if (ooxmlType != null) {
                return ooxmlType;
            }
        }
        String extension = extensionOf(originalFilename);
        if (("txt".equals(extension) || "md".equals(extension)) && isPlausibleUtf8Text(content)) {
            return "md".equals(extension) ? DocumentFileType.MD : DocumentFileType.TXT;
        }
        throw new ApiException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                "Only PDF, TXT, MD, DOCX, and PPTX are supported");
    }

    /**
     * DOCX/PPTX are both OOXML zip containers, so the shared PK\x03\x04 magic bytes alone can't
     * tell them apart from each other, XLSX, or a plain zip — classify by a distinguishing
     * internal part path instead (same technique Apache Tika's own zip detector uses). Reading
     * entry *names* via ZipInputStream never decompresses entry data, so this carries no
     * zip-bomb risk itself — that's guarded separately where real parsing happens (see
     * PoiZipBombProtectionConfig). Password-protected DOCX/PPTX use OOXML's OLE2/CFB container
     * encryption (different magic bytes entirely) and fall through to FILE_TYPE_UNSUPPORTED
     * rather than the more specific FILE_ENCRYPTED — see docs/DECISIONS.md.
     */
    private DocumentFileType sniffOoxmlEntryNames(byte[] content) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                switch (entry.getName()) {
                    case "word/document.xml":
                        return DocumentFileType.DOCX;
                    case "ppt/presentation.xml":
                        return DocumentFileType.PPTX;
                    default:
                        // keep scanning — e.g. xl/workbook.xml (XLSX) falls through unsupported
                }
            }
        } catch (IOException notAValidZip) {
            return null;
        }
        return null;
    }

    private boolean isPlausibleUtf8Text(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return false; // null bytes never appear in real text files
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
    }

    private String mimeTypeFor(DocumentFileType fileType) {
        return switch (fileType) {
            case PDF -> "application/pdf";
            case MD -> "text/markdown";
            case TXT -> "text/plain";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        };
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeFilename(String original) {
        return (original == null || original.isBlank()) ? "upload" : original;
    }

    private String safeFilename(String normalizedFilename) {
        String base = normalizedFilename.replaceAll("[\\\\/]", "_");
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * storageProvider.store() writes bytes to disk outside the DB transaction that follows it —
     * if that transaction later rolls back (e.g. the enqueue's quota check throws), the file
     * would otherwise be orphaned on disk with no Document row pointing at it. Clean it up only
     * when the transaction doesn't commit.
     */
    private void registerStorageCleanupOnRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    storageProvider.delete(storageKey);
                }
            }
        });
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String sha256Hex(byte[] content) {
        return Sha256.hex(content);
    }
}
