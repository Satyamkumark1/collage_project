package com.studyflow.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Prompts are versioned assets under {@code resources/prompts/{purpose}/v{n}.md} + a manifest,
 * never a string literal in a service class (see specs/08-ai-layer.md §Prompt registry).
 */
@Component
public class PromptRegistry {

    public record Prompt(String content, String purpose, int version) {
    }

    public Prompt load(String purpose, int version) {
        String path = "prompts/" + purpose + "/v" + version + ".md";
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Prompt(content, purpose, version);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt not found on classpath: " + path, e);
        }
    }
}
