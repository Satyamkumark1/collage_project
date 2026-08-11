package com.studyflow.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.rag.domain.DocumentParsingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void extractsParagraphTextAsASinglePage() throws IOException {
        byte[] bytes = docxWithParagraph(
                "This is a real study notes paragraph with enough characters to clear the "
                        + "minimum text threshold used to detect an empty or image-only document.");

        ParsedDocument parsed = parser.parse(bytes);

        assertThat(parsed.pages()).hasSize(1);
        assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(parsed.pages().get(0).text()).contains("real study notes paragraph");
    }

    @Test
    void corruptBytesFailWithFileCorrupt() {
        byte[] garbage = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x10, 0x11};

        assertThatThrownBy(() -> parser.parse(garbage))
                .isInstanceOf(DocumentParsingException.class)
                .satisfies(e -> assertThat(((DocumentParsingException) e).failureCode()).isEqualTo("FILE_CORRUPT"));
    }

    @Test
    void emptyDocumentFailsWithNoTextLayer() throws IOException {
        byte[] bytes = docxWithParagraph("");

        assertThatThrownBy(() -> parser.parse(bytes))
                .isInstanceOf(DocumentParsingException.class)
                .satisfies(e -> assertThat(((DocumentParsingException) e).failureCode())
                        .isEqualTo("FILE_NO_TEXT_LAYER"));
    }

    private byte[] docxWithParagraph(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
