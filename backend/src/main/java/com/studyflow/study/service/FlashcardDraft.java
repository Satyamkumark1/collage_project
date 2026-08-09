package com.studyflow.study.service;

import java.util.List;

record FlashcardDraft(String frontMd, String backMd, List<String> citedChunkIds) {
}
