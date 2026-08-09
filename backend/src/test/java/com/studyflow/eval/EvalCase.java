package com.studyflow.eval;

import java.util.List;

record EvalCase(String documentSlug, String sourceFile, String content, List<String> expectedKeyTerms,
        List<RetrievalProbe> retrievalProbes) {

    record RetrievalProbe(String query, String expectedKeyword) {
    }
}
