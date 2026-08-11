package com.studyflow.ai.groq;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
        // Non-fatal by design (see class javadoc), but an unbounded RestTemplate default still
        // blocks the ApplicationRunner — and therefore Boot startup — indefinitely if Groq never
        // responds. Bound it the same way GroqAiProvider bounds its own client.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        // api-key can now be a comma-separated pool (see GroqAiProvider/docs/DECISIONS.md) — this
        // is a one-off startup check, so just the first key is enough, never all of them.
        String firstApiKey = apiKey.split(",", 2)[0].strip();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + firstApiKey)
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
