package com.studyflow.rag.service;

import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.rag.domain.DocumentParsingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDFBox-backed. Rejects encrypted PDFs and scanned-image PDFs (avg extracted chars/page below
 * threshold — no OCR this phase) with actionable failure codes (see specs/09-rag.md §Parsing).
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MIN_AVG_CHARS_PER_PAGE = 100;

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.PDF;
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        PDDocument document;
        try {
            document = Loader.loadPDF(content);
        } catch (InvalidPasswordException e) {
            throw new DocumentParsingException("FILE_ENCRYPTED", "PDF is password-protected");
        } catch (IOException e) {
            throw new DocumentParsingException("FILE_CORRUPT", "Could not parse PDF: " + e.getMessage());
        }
        try {
            if (document.isEncrypted()) {
                throw new DocumentParsingException("FILE_ENCRYPTED", "PDF is encrypted");
            }
            return extractPages(document);
        } finally {
            closeQuietly(document);
        }
    }

    private ParsedDocument extractPages(PDDocument document) {
        int pageCount = document.getNumberOfPages();
        List<ParsedPage> pages = new ArrayList<>(pageCount);
        PDFTextStripper stripper = newStripper();
        int totalChars = 0;
        for (int i = 1; i <= pageCount; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text;
            try {
                text = stripper.getText(document);
            } catch (IOException e) {
                throw new DocumentParsingException("FILE_CORRUPT", "Could not extract text: " + e.getMessage());
            }
            totalChars += text.length();
            pages.add(new ParsedPage(i, text));
        }
        double avgCharsPerPage = pageCount == 0 ? 0 : (double) totalChars / pageCount;
        if (avgCharsPerPage < MIN_AVG_CHARS_PER_PAGE) {
            throw new DocumentParsingException("FILE_NO_TEXT_LAYER",
                    "Average extracted characters per page (" + String.format("%.1f", avgCharsPerPage)
                            + ") is below the scanned-image detection threshold (" + MIN_AVG_CHARS_PER_PAGE + ")");
        }
        return new ParsedDocument(pages);
    }

    private PDFTextStripper newStripper() {
        return new PDFTextStripper();
    }

    private void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
