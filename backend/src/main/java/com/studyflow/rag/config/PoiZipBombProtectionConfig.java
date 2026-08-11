package com.studyflow.rag.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.context.annotation.Configuration;

/**
 * ZipSecureFile's limits are static/global JVM settings, applied automatically by POI to every
 * OOXML load (XWPF/XSLF both go through OPCPackage.open) — set once at boot, not per-parse-call.
 * See docs/DECISIONS.md for the numbers.
 */
@Configuration
public class PoiZipBombProtectionConfig {

    // Generous headroom above anything a legitimate DOCX/PPTX under this app's 25 MiB upload cap
    // should ever need per zip entry, but a hard, deterministic backstop dramatically tighter
    // than POI's own 4 GiB default — bounds worst-case per-entry memory blow-up.
    private static final long MAX_OOXML_ENTRY_SIZE_BYTES = 200L * 1024 * 1024; // 200 MiB

    @PostConstruct
    void configureZipBombProtection() {
        ZipSecureFile.setMaxEntrySize(MAX_OOXML_ENTRY_SIZE_BYTES);
        // minInflateRatio left at POI's own default (1%) — already the conservative, widely used
        // threshold; no app-specific reason to loosen or tighten it.
    }
}
