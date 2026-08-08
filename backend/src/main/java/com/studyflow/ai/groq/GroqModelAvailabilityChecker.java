package com.studyflow.ai.groq;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Groq deprecates model ids over time (see specs/08-ai-layer.md). Non-fatal: logs a warning at
 * boot if a configured model id isn't in the live list, never blocks startup on it.
 */
@Component
public class GroqModelAvailabilityChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroqModelAvailabilityChecker.class);

    private final RestClient restClient;
    private final String summaryModel;

    public GroqModelAvailabilityChecker(@Value("${studyflow.ai.groq.api-key}") String apiKey,
            @Value("${studyflow.ai.groq.base-url}") String baseUrl,
            @Value("${studyflow.ai.groq.models.summary}") String summaryModel) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.summaryModel = summaryModel;
    }

    record ModelEntry(String id) {
    }

    record ModelListResponse(List<ModelEntry> data) {
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ModelListResponse response = restClient.get().uri("/models").retrieve().body(ModelListResponse.class);
            Set<String> liveIds = response == null || response.data() == null ? Set.of()
                    : response.data().stream().map(ModelEntry::id).collect(Collectors.toSet());
            if (!liveIds.contains(summaryModel)) {
                log.warn("Configured Groq model '{}' (purpose=summary) is not in the live /models list. "
                        + "It may have been deprecated — check https://console.groq.com/docs/models.", summaryModel);
            }
        } catch (Exception e) {
            log.warn("Could not verify Groq model availability at boot (non-fatal): {}", e.getMessage());
        }
    }
}
