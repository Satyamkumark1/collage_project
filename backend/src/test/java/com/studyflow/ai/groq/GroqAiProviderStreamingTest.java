package com.studyflow.ai.groq;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider.StreamListener;
import java.io.IOException;
import java.util.Properties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies SSE parsing and the streaming-safe think-block filter against a fake Groq server —
 * no real API call, unlike the integration tests, since this is exercising wire-format parsing
 * and buffering edge cases (a tag split across two chunks) that are awkward to force a real
 * model to reproduce on demand.
 */
class GroqAiProviderStreamingTest {

    private static final String TEST_MODEL = loadTestModel();

    private MockWebServer server;
    private GroqAiProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new GroqAiProvider("test-key", server.url("/").toString(), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamsTokensAndStripsAThinkBlockSplitAcrossChunks() throws InterruptedException {
        // Mirrors the real Groq/OpenAI-compatible wire shape exactly (including "index", which a
        // hand-crafted fixture without it would let slip past — see docs/DECISIONS.md commit
        // history: this shape omission previously passed this test while failing against the
        // real API under strict deserialization).
        String body = String.join("\n",
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\" <th\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ink>\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret reasoning\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"</th\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ink> world\"},\"finish_reason\":null}]}"),
                sseLine("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}"),
                sseLine("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}"),
                "data: [DONE]", "");
        // Each sseLine() call above yields exactly one "data: {...}" line; String.join adds the
        // separating newlines, so the raw SSE frame boundaries ("\n\n" between events) never
        // matter here — the reader below only cares about individual "data:"-prefixed lines.
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(body));

        List<String> tokens = new CopyOnWriteArrayList<>();
        AtomicReference<AiCompletionResult> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AiCompletionRequest request = new AiCompletionRequest("tutor", 1,
                List.of(new AiCompletionRequest.Message("user", "hi")), TEST_MODEL, 500, 0.4, false, UUID.randomUUID(),
                null);
        provider.streamComplete(request, new StreamListener() {
            @Override
            public void onToken(String delta) {
                tokens.add(delta);
            }

            @Override
            public void onComplete(AiCompletionResult completed) {
                result.set(completed);
                done.countDown();
            }

            @Override
            public void onError(RuntimeException e) {
                error.set(e);
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();

        String streamedVisible = String.join("", tokens);
        assertThat(streamedVisible).doesNotContain("secret reasoning", "<think>", "</think>");
        assertThat(streamedVisible).contains("Hello").contains("world").contains("!");

        AiCompletionResult completed = result.get();
        assertThat(completed).isNotNull();
        assertThat(completed.content()).doesNotContain("secret reasoning", "<think>", "</think>");
        assertThat(completed.content()).contains("Hello").contains("world");
        assertThat(completed.finishReason()).isEqualTo("stop");
        assertThat(completed.tokensIn()).isEqualTo(12);
        assertThat(completed.tokensOut()).isEqualTo(5);
    }

    @Test
    void surfacesA5xxAsATransientError() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("upstream unavailable"));

        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AiCompletionRequest request = new AiCompletionRequest("tutor", 1,
                List.of(new AiCompletionRequest.Message("user", "hi")), TEST_MODEL, 500, 0.4, false, UUID.randomUUID(),
                null);

        provider.streamComplete(request, new StreamListener() {
            @Override
            public void onToken(String delta) {
            }

            @Override
            public void onComplete(AiCompletionResult completed) {
                done.countDown();
            }

            @Override
            public void onError(RuntimeException e) {
                error.set(e);
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(com.studyflow.jobs.domain.TransientJobException.class);
    }

    private String sseLine(String json) {
        return "data: " + json;
    }

    private static String loadTestModel() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        return props != null ? props.getProperty("studyflow.ai.groq.models.tutor", "openai/gpt-oss-20b")
                : "openai/gpt-oss-20b";
    }
}
