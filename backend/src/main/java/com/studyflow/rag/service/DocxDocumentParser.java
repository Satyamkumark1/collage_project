package com.studyflow.rag.service;

import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.rag.domain.DocumentParsingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/**
 * POI (XWPF)-backed. DOCX has no reliable structural page-boundary concept (page breaks are a
 * layout-engine computation, not a stored fact) — same reasoning PlainTextDocumentParser already
 * applies to TXT/MD — so the whole document is treated as a single page. Encrypted DOCX (OLE2/CFB
 * container encryption) never reaches this class: DocumentUploadService's sniff only recognizes
 * plain OOXML zip containers, so an encrypted DOCX is rejected upstream as FILE_TYPE_UNSUPPORTED
 * — see docs/DECISIONS.md.
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final int MIN_TEXT_CHARS = 100; // same floor as PdfDocumentParser's per-page average

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.DOCX;
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String text;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            // Paragraphs + tables + headers/footers, uniformly — matches PdfDocumentParser's
            // intent of extracting everything a student would actually read.
            text = extractor.getText();
        } catch (IOException e) {
            throw new DocumentParsingException("FILE_CORRUPT", "Could not parse DOCX: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new DocumentParsingException("FILE_CORRUPT", "Could not parse DOCX: " + e.getMessage());
        }
        if (text.length() < MIN_TEXT_CHARS) {
            throw new DocumentParsingException("FILE_NO_TEXT_LAYER",
                    "Extracted text (" + text.length() + " chars) is below the minimum threshold ("
                            + MIN_TEXT_CHARS + ")");
        }
        return new ParsedDocument(List.of(new ParsedPage(1, text)));
    }
}
