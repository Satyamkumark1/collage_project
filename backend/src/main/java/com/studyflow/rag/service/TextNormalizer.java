package com.studyflow.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Collapse whitespace, dehyphenate line-break splits, strip repeated headers/footers (detected
 * across &ge;60% of pages), drop page-number-only lines (see specs/09-rag.md §Normalisation).
 */
@Component
public class TextNormalizer {

    private static final double HEADER_FOOTER_THRESHOLD = 0.6;

    public ParsedDocument normalize(ParsedDocument document) {
        Set<String> repeatedLines = findRepeatedHeaderFooterLines(document.pages());
        List<ParsedPage> normalized = new ArrayList<>();
        for (ParsedPage page : document.pages()) {
            normalized.add(new ParsedPage(page.pageNumber(), normalizePageText(page.text(), repeatedLines)));
        }
        return new ParsedDocument(normalized);
    }

    private Set<String> findRepeatedHeaderFooterLines(List<ParsedPage> pages) {
        if (pages.size() < 3) {
            return Set.of();
        }
        Map<String, Integer> firstLineCounts = new HashMap<>();
        Map<String, Integer> lastLineCounts = new HashMap<>();
        for (ParsedPage page : pages) {
            List<String> lines = page.text().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            firstLineCounts.merge(lines.get(0), 1, Integer::sum);
            lastLineCounts.merge(lines.get(lines.size() - 1), 1, Integer::sum);
        }
        int threshold = (int) Math.ceil(pages.size() * HEADER_FOOTER_THRESHOLD);
        Set<String> repeated = new HashSet<>();
        firstLineCounts.forEach((line, count) -> {
            if (count >= threshold) {
                repeated.add(line);
            }
        });
        lastLineCounts.forEach((line, count) -> {
            if (count >= threshold) {
                repeated.add(line);
            }
        });
        return repeated;
    }

    private String normalizePageText(String text, Set<String> repeatedLines) {
        String dehyphenated = dehyphenate(text);
        List<String> lines = dehyphenated.lines()
                .map(String::strip)
                .filter(line -> !isPageNumberOnly(line))
                .filter(line -> !repeatedLines.contains(line))
                .toList();
        return collapseWhitespace(String.join("\n", lines));
    }

    private String dehyphenate(String text) {
        return text.replaceAll("(\\p{L})-\\r?\\n(\\p{L})", "$1$2");
    }

    private boolean isPageNumberOnly(String line) {
        return line.matches("^[-\\s]*\\d+[-\\s]*$");
    }

    private String collapseWhitespace(String text) {
        String collapsedSpaces = text.replaceAll("[ \\t]+", " ");
        return collapsedSpaces.replaceAll("\\n{3,}", "\n\n").strip();
    }
}
