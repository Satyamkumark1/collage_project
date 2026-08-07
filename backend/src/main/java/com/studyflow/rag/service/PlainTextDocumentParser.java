package com.studyflow.rag.service;

import com.studyflow.library.domain.DocumentFileType;
import com.studyflow.rag.domain.DocumentParsingException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/** TXT/MD have no real page concept — the whole file is one page (see specs/09-rag.md). */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.TXT || fileType == DocumentFileType.MD;
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new DocumentParsingException("FILE_CORRUPT", "File is not valid UTF-8 text");
        }
        return new ParsedDocument(List.of(new ParsedPage(1, text)));
    }
}
