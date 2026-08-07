package com.studyflow.rag.service;

import com.studyflow.library.domain.DocumentFileType;

/** One implementation per {@link DocumentFileType} (see specs/09-rag.md §Parsing). */
public interface DocumentParser {

    boolean supports(DocumentFileType fileType);

    ParsedDocument parse(byte[] content);
}
