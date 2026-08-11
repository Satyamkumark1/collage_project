package com.studyflow.ai.groq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider;
import com.studyflow.jobs.domain.TransientJobException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

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
    // Covers a response truncated (e.g. by max_tokens) mid-reasoning: a <think> with no
    // matching close tag left behind by the pattern above.
    private static final Pattern UNTERMINATED_THINK_BLOCK = Pattern.compile("<think>.*",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<String> apiKeys;
    private final AtomicInteger nextKeyIndex = new AtomicInteger();

    public GroqAiProvider(@Value("${studyflow.ai.groq.api-keys}") String apiKeysConfig,
            @Value("${studyflow.ai.groq.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.apiKeys = Arrays.stream(apiKeysConfig.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList();
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("studyflow.ai.groq.api-keys resolved to no usable keys");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(150));
        // No defaultHeader here — Authorization varies per call, see #nextApiKey.
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * Round-robins across every configured Groq account — each has its own independent rate-limit
     * budget, so N keys multiplies the effective request budget by N (see docs/DECISIONS.md).
     * Plain counter, not "retry a different key on 429": the job engine's own backoff/retry
     * already re-invokes {@link #complete}/{@link #streamComplete} on transient failure, and each
     * fresh invocation naturally advances to the next key — no separate retry loop needed here.
     */
    private String nextApiKey() {
        int index = Math.floorMod(nextKeyIndex.getAndIncrement(), apiKeys.size());
        return apiKeys.get(index);
    }

    record ChatMessage(String role, String content) {
    }

    record ResponseFormat(String type) {
    }

    record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }

    record ChatCompletionRequestBody(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions) {
    }

    record Choice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    // ignoreUnknown: parsed with the app's strict, Spring-managed ObjectMapper (see
    // #consumeStream), unlike the other records above which go through RestClient's own lenient
    // default converter — that strictness is meant for validating request DTOs from untrusted
    // clients, not for an external API's response shape, which can add fields (Groq has sent
    // "logprobs" on stream chunks that weren't modelled here) without notice.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
    }

    record ChatCompletionResponseBody(List<Choice> choices, Usage usage, String model) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamChoice(Delta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamChunk(List<StreamChoice> choices, Usage usage, String model) {
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
        ResponseFormat responseFormat = request.jsonMode() ? new ResponseFormat("json_object") : null;
        ChatCompletionRequestBody body = new ChatCompletionRequestBody(request.model(), messages,
                request.maxTokens(), request.temperature(), responseFormat, false, null);

        long start = System.currentTimeMillis();
        ChatCompletionResponseBody response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + nextApiKey())
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

    /**
     * Consumes Groq's OpenAI-compatible SSE stream directly off {@link RestClient#exchange} so
     * tokens can be forwarded as they arrive, instead of {@code .retrieve()} buffering the whole
     * body — the whole point of a browser-waited streamed reply (see specs/01-architecture.md).
     * Never throws: every exit path (happy or not) reaches exactly one {@code listener} callback.
     */
    @Override
    public void streamComplete(AiCompletionRequest request, StreamListener listener) {
        List<ChatMessage> messages = request.messages().stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
        ChatCompletionRequestBody body = new ChatCompletionRequestBody(request.model(), messages,
                request.maxTokens(), request.temperature(), null, true, new StreamOptions(true));

        long start = System.currentTimeMillis();
        try {
            restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + nextApiKey())
                    .body(body)
                    .exchange((req, resp) -> {
                        consumeStream(resp, start, request.model(), listener);
                        return null;
                    });
        } catch (ResourceAccessException e) {
            listener.onError(new TransientJobException("Groq streaming call failed transiently", e));
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    private void consumeStream(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, long start,
            String requestedModel, StreamListener listener) throws IOException {
        if (response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()) {
            String errorBody = readAll(response.getBody());
            boolean transientFailure = response.getStatusCode().value() == 429
                    || response.getStatusCode().is5xxServerError();
            RuntimeException error = transientFailure
                    ? new TransientJobException("Groq streaming call failed transiently: " + errorBody)
                    : new IllegalStateException("Groq streaming call failed (" + response.getStatusCode() + "): "
                            + errorBody);
            listener.onError(error);
            return;
        }

        ThinkBlockFilter filter = new ThinkBlockFilter();
        StringBuilder fullContent = new StringBuilder();
        String finishReason = null;
        Usage usage = null;
        String streamedModel = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                StreamChunk chunk = objectMapper.readValue(data, StreamChunk.class);
                if (chunk.model() != null && !chunk.model().isBlank()) {
                    streamedModel = chunk.model();
                }
                if (chunk.usage() != null) {
                    usage = chunk.usage();
                }
                if (chunk.choices() == null || chunk.choices().isEmpty()) {
                    continue;
                }
                StreamChoice choice = chunk.choices().get(0);
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
                String delta = choice.delta() != null ? choice.delta().content() : null;
                if (delta == null || delta.isEmpty()) {
                    continue;
                }
                fullContent.append(delta);
                String visible = filter.accept(delta);
                if (!visible.isEmpty()) {
                    listener.onToken(visible);
                }
            }
        }
        String trailing = filter.finish();
        if (!trailing.isEmpty()) {
            listener.onToken(trailing);
        }

        long latencyMs = System.currentTimeMillis() - start;
        String finalContent = stripReasoning(fullContent.toString());
        String finalModel = streamedModel != null ? streamedModel : requestedModel;
        listener.onComplete(new AiCompletionResult(finalContent, finishReason,
                usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens(), latencyMs,
                finalModel));
    }

    private String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String stripReasoning(String content) {
        if (content == null) {
            return "";
        }
        String withoutCompleteBlocks = THINK_BLOCK.matcher(content).replaceAll("");
        String withoutTrailingOpenBlock = UNTERMINATED_THINK_BLOCK.matcher(withoutCompleteBlocks).replaceFirst("");
        return withoutTrailingOpenBlock.strip();
    }
}
