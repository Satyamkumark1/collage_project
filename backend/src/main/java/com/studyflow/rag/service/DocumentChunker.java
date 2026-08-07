package com.studyflow.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Structure-aware first (split on detected headings), then token-based within a section: target
 * 700 tokens, hard max 1000, overlap 120 (see specs/09-rag.md §Chunking). Token counting is an
 * approximation (chars/4), not a real tokenizer — a documented simplification for this phase,
 * same as the spec itself calls out.
 */
@Component
public class DocumentChunker {

    private static final int TARGET_TOKENS = 700;
    private static final int MAX_TOKENS = 1000;
    private static final int OVERLAP_TOKENS = 120;
    private static final int MIN_CHUNK_TOKENS = 80;
    private static final double CHARS_PER_TOKEN = 4.0;

    public record ChunkDraft(String content, int tokenCount, Integer pageFrom, Integer pageTo, String sectionPath) {
    }

    private record Line(String text, int pageNumber) {
    }

    private record Section(String heading, List<Line> lines) {
    }

    public List<ChunkDraft> chunk(ParsedDocument document) {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (Section section : segmentIntoSections(document)) {
            chunks.addAll(chunkSection(section));
        }
        return chunks;
    }

    private List<Section> segmentIntoSections(ParsedDocument document) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        List<Line> currentLines = new ArrayList<>();
        for (ParsedPage page : document.pages()) {
            for (String rawLine : page.text().split("\n", -1)) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (isHeading(line) && !currentLines.isEmpty()) {
                    sections.add(new Section(currentHeading, new ArrayList<>(currentLines)));
                    currentLines.clear();
                }
                if (isHeading(line)) {
                    currentHeading = stripHeadingMarkers(line);
                }
                currentLines.add(new Line(line, page.pageNumber()));
            }
        }
        if (!currentLines.isEmpty()) {
            sections.add(new Section(currentHeading, currentLines));
        }
        return sections;
    }

    private boolean isHeading(String line) {
        if (line.startsWith("#")) {
            return true;
        }
        // Heuristic for PDF/TXT (no font-size metadata survives text extraction): a short,
        // non-sentence-terminated, mostly-uppercase line reads as a heading.
        return line.length() <= 80 && !line.endsWith(".") && !line.endsWith(",") && !line.endsWith(";")
                && line.matches("^[A-Z0-9][^a-z]*$");
    }

    private String stripHeadingMarkers(String line) {
        return line.replaceFirst("^#+\\s*", "").strip();
    }

    private List<ChunkDraft> chunkSection(Section section) {
        List<ChunkDraft> result = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        int currentTokens = 0;

        for (Line line : section.lines()) {
            int lineTokens = approxTokens(line.text());
            if (currentTokens + lineTokens > MAX_TOKENS && !current.isEmpty()) {
                result.add(buildChunk(current, section.heading()));
                current = overlapTail(current);
                currentTokens = sumTokens(current);
            }
            current.add(line);
            currentTokens += lineTokens;
            if (currentTokens >= TARGET_TOKENS) {
                result.add(buildChunk(current, section.heading()));
                current = overlapTail(current);
                currentTokens = sumTokens(current);
            }
        }

        if (!current.isEmpty()) {
            ChunkDraft last = buildChunk(current, section.heading());
            if (last.tokenCount() < MIN_CHUNK_TOKENS && !result.isEmpty()) {
                // Too small on its own; fold into the previous chunk rather than discard it
                // outright (spec: discard <80 tokens unless heading+definition — the previous
                // chunk already carries the heading, so merging keeps that content reachable).
                ChunkDraft previous = result.remove(result.size() - 1);
                result.add(mergeChunks(previous, last));
            } else {
                result.add(last);
            }
        }
        return result;
    }

    private List<Line> overlapTail(List<Line> lines) {
        List<Line> tail = new ArrayList<>();
        int tokens = 0;
        for (int i = lines.size() - 1; i >= 0 && tokens < OVERLAP_TOKENS; i--) {
            tail.add(0, lines.get(i));
            tokens += approxTokens(lines.get(i).text());
        }
        return tail;
    }

    private int sumTokens(List<Line> lines) {
        return lines.stream().mapToInt(l -> approxTokens(l.text())).sum();
    }

    private ChunkDraft buildChunk(List<Line> lines, String heading) {
        String content = lines.stream().map(Line::text).collect(Collectors.joining("\n"));
        int pageFrom = lines.stream().mapToInt(Line::pageNumber).min().orElse(1);
        int pageTo = lines.stream().mapToInt(Line::pageNumber).max().orElse(1);
        return new ChunkDraft(content, approxTokens(content), pageFrom, pageTo, heading);
    }

    private ChunkDraft mergeChunks(ChunkDraft a, ChunkDraft b) {
        String content = a.content() + "\n" + b.content();
        return new ChunkDraft(content, approxTokens(content), Math.min(a.pageFrom(), b.pageFrom()),
                Math.max(a.pageTo(), b.pageTo()), a.sectionPath());
    }

    private int approxTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }
}
