package com.studyflow.rag.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyflow.jobs.domain.TransientJobException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls Voyage AI's embeddings endpoint, batched (see specs/09-rag.md). Retryable failure
 * classes (5xx, 429, connection issues) surface as {@link TransientJobException} so the job
 * engine's own backoff/retry handles them — no separate retry loop here.
 */
@Component
public class VoyageEmbeddingClient implements EmbeddingClient {

    private static final int BATCH_SIZE = 32;

    private final RestClient restClient;
    private final String model;

    public VoyageEmbeddingClient(@Value("${studyflow.ai.voyage.api-key}") String apiKey,
            @Value("${studyflow.ai.voyage.base-url}") String baseUrl,
            @Value("${studyflow.ai.voyage.model}") String model) {
        this.model = model;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    record EmbeddingRequest(List<String> input, String model) {
    }

    record EmbeddingResponse(List<EmbeddingData> data, String model, Usage usage) {
        record EmbeddingData(List<Float> embedding, int index) {
        }

        record Usage(@JsonProperty("total_tokens") int totalTokens) {
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embed(texts, ignored -> { });
    }

    @Override
    public List<float[]> embed(List<String> texts, java.util.function.IntConsumer onBatchEmbedded) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            results.addAll(embedBatch(batch));
            onBatchEmbedded.accept(results.size());
        }
        return results;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String modelVersion() {
        return "1";
    }

    private List<float[]> embedBatch(List<String> batch) {
        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .body(new EmbeddingRequest(batch, model))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new TransientJobException("Voyage embeddings call failed transiently", e);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                throw new TransientJobException("Voyage rate-limited the request", e);
            }
            throw e;
        }
        if (response == null || response.data() == null) {
            throw new TransientJobException("Voyage returned an empty response");
        }
        List<EmbeddingResponse.EmbeddingData> data = response.data();
        if (data.size() != batch.size()) {
            throw new TransientJobException(
                    "Voyage returned " + data.size() + " embeddings for a batch of " + batch.size());
        }
        Set<Integer> seenIndexes = new HashSet<>();
        for (EmbeddingResponse.EmbeddingData item : data) {
            if (item.index() < 0 || item.index() >= batch.size() || !seenIndexes.add(item.index())) {
                throw new TransientJobException("Voyage returned an out-of-range or duplicate embedding index: "
                        + item.index());
            }
        }
        return data.stream()
                .sorted(Comparator.comparingInt(EmbeddingResponse.EmbeddingData::index))
                .map(this::toFloatArray)
                .toList();
    }

    private float[] toFloatArray(EmbeddingResponse.EmbeddingData data) {
        List<Float> values = data.embedding();
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
