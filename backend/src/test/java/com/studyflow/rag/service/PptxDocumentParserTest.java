package com.studyflow.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.rag.domain.DocumentParsingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

class PptxDocumentParserTest {

    private final PptxDocumentParser parser = new PptxDocumentParser();

    @Test
    void extractsOneParsedPagePerSlide() throws IOException {
        byte[] bytes = pptxWithSlideTexts(
                "First slide has enough real lecture text on it to clear the scanned-image detection "
                        + "threshold used to flag empty or image-only decks.",
                "Second slide also carries enough real text content to clear the same average "
                        + "characters-per-slide threshold checked across the whole deck.");

        ParsedDocument parsed = parser.parse(bytes);

        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(parsed.pages().get(0).text()).contains("First slide");
        assertThat(parsed.pages().get(1).pageNumber()).isEqualTo(2);
        assertThat(parsed.pages().get(1).text()).contains("Second slide");
    }

    @Test
    void corruptBytesFailWithFileCorrupt() {
        byte[] garbage = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x10, 0x11};

        assertThatThrownBy(() -> parser.parse(garbage))
                .isInstanceOf(DocumentParsingException.class)
                .satisfies(e -> assertThat(((DocumentParsingException) e).failureCode()).isEqualTo("FILE_CORRUPT"));
    }

    @Test
    void emptySlidesFailWithNoTextLayer() throws IOException {
        byte[] bytes = pptxWithSlideTexts("", "");

        assertThatThrownBy(() -> parser.parse(bytes))
                .isInstanceOf(DocumentParsingException.class)
                .satisfies(e -> assertThat(((DocumentParsingException) e).failureCode())
                        .isEqualTo("FILE_NO_TEXT_LAYER"));
    }

    @Test
    void tooManySlidesFailsWithFileTooLarge() throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            for (int i = 0; i < 301; i++) {
                slideShow.createSlide();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            slideShow.write(out);
            byte[] bytes = out.toByteArray();

            assertThatThrownBy(() -> parser.parse(bytes))
                    .isInstanceOf(DocumentParsingException.class)
                    .satisfies(e -> assertThat(((DocumentParsingException) e).failureCode())
                            .isEqualTo("FILE_TOO_LARGE"));
        }
    }

    private byte[] pptxWithSlideTexts(String... slideTexts) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            for (String text : slideTexts) {
                XSLFSlide slide = slideShow.createSlide();
                XSLFTextBox textBox = slide.createTextBox();
                textBox.setText(text);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            slideShow.write(out);
            return out.toByteArray();
        }
    }
}
