package com.studyflow.ai.groq;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider;
import com.studyflow.jobs.domain.TransientJobException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls Groq's OpenAI-compatible chat completions endpoint (see specs/08-ai-layer.md). Nothing
 * outside this package knows Groq exists. Strips {@code <think>...</think>} reasoning segments
 * some models emit — constraint #9 in specs/00-product-and-constraints.md — so a reasoning
 * model's scratchpad never reaches a student, in this adapter, not downstream.
 */
@Component
public class GroqAiProvider implements AiProvider {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;

    public GroqAiProvider(@Value("${studyflow.ai.groq.api-key}") String apiKey,
            @Value("${studyflow.ai.groq.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(150));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    record ChatMessage(String role, String content) {
    }

    record ResponseFormat(String type) {
    }

    record ChatCompletionRequestBody(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature,
            @JsonProperty("response_format") ResponseFormat responseFormat) {
    }

    record Choice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    record Usage(@JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
    }

    record ChatCompletionResponseBody(List<Choice> choices, Usage usage, String model) {
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
        ResponseFormat responseFormat = request.jsonMode() ? new ResponseFormat("json_object") : null;
        ChatCompletionRequestBody body = new ChatCompletionRequestBody(request.model(), messages,
                request.maxTokens(), request.temperature(), responseFormat);

        long start = System.currentTimeMillis();
        ChatCompletionResponseBody response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponseBody.class);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new TransientJobException("Groq call failed transiently", e);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                throw new TransientJobException("Groq rate-limited the request", e);
            }
            throw e;
        }
        long latencyMs = System.currentTimeMillis() - start;

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new TransientJobException("Groq returned an empty response");
        }
        Choice choice = response.choices().get(0);
        String content = stripReasoning(choice.message().content());
        Usage usage = response.usage();

        return new AiCompletionResult(content, choice.finishReason(),
                usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens(), latencyMs,
                response.model());
    }

    private String stripReasoning(String content) {
        if (content == null) {
            return "";
        }
        return THINK_BLOCK.matcher(content).replaceAll("").strip();
    }
}
