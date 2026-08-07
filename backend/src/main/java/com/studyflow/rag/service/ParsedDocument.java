package com.studyflow.rag.service;

import java.util.List;

public record ParsedDocument(List<ParsedPage> pages) {

    public int totalCharCount() {
        return pages.stream().mapToInt(p -> p.text().length()).sum();
    }
}
