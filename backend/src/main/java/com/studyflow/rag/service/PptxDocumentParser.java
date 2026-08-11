package com.studyflow.rag.service;

import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.rag.domain.DocumentParsingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/**
 * POI (XSLF)-backed. One ParsedPage per slide (mirrors PdfDocumentParser's per-page loop) —
 * unlike DOCX, PPTX has a natural page unit. Reuses PDF's "avg chars/page below threshold ->
 * scanned image" heuristic, arguably more relevant here since image-heavy decks are common.
 * Deliberately does not extract speaker notes — "page = what's visibly on the slide," matching
 * PDF's contract. See docs/DECISIONS.md for the slide-count cap and its rationale.
 */
@Component
public class PptxDocumentParser implements DocumentParser {

    private static final int MIN_AVG_CHARS_PER_PAGE = 100; // same floor as PdfDocumentParser
    private static final int MAX_SLIDE_COUNT = 300; // see docs/DECISIONS.md

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.PPTX;
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(content))) {
            return extractSlides(slideShow);
        } catch (DocumentParsingException e) {
            throw e; // FILE_TOO_LARGE/FILE_NO_TEXT_LAYER from extractSlides() — not a parse failure
        } catch (IOException e) {
            throw new DocumentParsingException("FILE_CORRUPT", "Could not parse PPTX: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new DocumentParsingException("FILE_CORRUPT", "Could not parse PPTX: " + e.getMessage());
        }
    }

    private ParsedDocument extractSlides(XMLSlideShow slideShow) {
        List<XSLFSlide> slides = slideShow.getSlides();
        if (slides.size() > MAX_SLIDE_COUNT) {
            throw new DocumentParsingException("FILE_TOO_LARGE",
                    "Presentation has " + slides.size() + " slides, exceeding the " + MAX_SLIDE_COUNT
                            + "-slide limit");
        }
        List<ParsedPage> pages = new ArrayList<>(slides.size());
        int totalChars = 0;
        for (int i = 0; i < slides.size(); i++) {
            String text = extractSlideText(slides.get(i));
            totalChars += text.length();
            pages.add(new ParsedPage(i + 1, text));
        }
        double avgCharsPerPage = pages.isEmpty() ? 0 : (double) totalChars / pages.size();
        if (avgCharsPerPage < MIN_AVG_CHARS_PER_PAGE) {
            throw new DocumentParsingException("FILE_NO_TEXT_LAYER",
                    "Average extracted characters per slide (" + String.format("%.1f", avgCharsPerPage)
                            + ") is below the scanned-image detection threshold (" + MIN_AVG_CHARS_PER_PAGE + ")");
        }
        return new ParsedDocument(pages);
    }

    private String extractSlideText(XSLFSlide slide) {
        StringBuilder text = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                text.append(textShape.getText()).append('\n');
            }
        }
        return text.toString();
    }
}
