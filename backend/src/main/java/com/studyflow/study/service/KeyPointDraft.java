package com.studyflow.study.service;

import com.studyflow.study.domain.KeyPointCategory;
import java.util.List;

public record KeyPointDraft(KeyPointCategory category, String label, String contentMd, List<String> citedChunkIds) {
}
