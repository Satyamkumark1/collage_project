package com.studyflow.eval;

import com.studyflow.eval.EvalCase.RetrievalProbe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads {@code eval/documents/*.md} + {@code eval/answer-keys/*.json} from the repo root — a
 * sibling of the {@code backend/} module, not on the classpath, per the eval harness layout in
 * docs/status/phase-3.md. Maven runs tests with the module directory as the working directory, so
 * {@code eval/} resolves as {@code ../eval} from there.
 */
final class EvalDocumentFixtures {

    private EvalDocumentFixtures() {
    }

    static List<EvalCase> loadAll(ObjectMapper objectMapper) {
        Path evalDir = resolveEvalDir();
        Path answerKeysDir = evalDir.resolve("answer-keys");
        Path documentsDir = evalDir.resolve("documents");

        List<EvalCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(answerKeysDir)) {
            List<Path> keyFiles = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path keyFile : keyFiles) {
                JsonNode node = objectMapper.readTree(Files.readString(keyFile));
                String slug = node.get("documentSlug").asString();
                String sourceFile = node.get("sourceFile").asString();
                String content = Files.readString(documentsDir.resolve(sourceFile));

                List<String> expectedKeyTerms = new ArrayList<>();
                node.get("expectedKeyTerms").forEach(n -> expectedKeyTerms.add(n.asString()));

                List<RetrievalProbe> probes = new ArrayList<>();
                node.get("retrievalProbes")
                        .forEach(p -> probes.add(new RetrievalProbe(p.get("query").asString(),
                                p.get("expectedKeyword").asString())));

                cases.add(new EvalCase(slug, sourceFile, content, expectedKeyTerms, probes));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load eval fixtures from " + evalDir, e);
        }
        return cases;
    }

    private static Path resolveEvalDir() {
        Path candidate = Path.of("..", "eval");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException(
                "eval/ directory not found relative to " + Path.of("").toAbsolutePath() + " — expected ../eval");
    }
}
