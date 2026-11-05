package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import star.tracto.utils.JavaUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A small repository on which the oracles stage (with the configuration committed in the module) and then the tokens
 * stage run, end to end, as the command line runs them on the reference projects in the A/B: the symbol solver gets the
 * sources of a JDK of the {@code lib} folder (Java 21), and no library (the types of the dependencies, JUnit included,
 * are not resolved).
 */
final class TokensFixture {

    private static final Path COMMITTED_CONFIG = Path.of("src/main/resources/dataset/oracles-dataset_config.json");
    private static final String PROJECT = "fixture";
    private static final String UNEXPECTED_TOKEN = "Unexpected token";
    private static final int JAVA_VERSION = 21;

    private final Path repoRoot;
    private final Path outputPath;

    TokensFixture(Path tempDir) throws IOException {
        repoRoot = tempDir.resolve("repo");
        outputPath = tempDir.resolve("output");
    }

    /** Writes a source file of the repository (a path relative to its root). */
    void write(String relativePath, String source) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /**
     * Runs the oracles stage, then the tokens stage (training), with the sources of the JDK as classpath (as
     * {@code SymbolicModule}; the {@code lib} folder is the one of the module, the working directory of the tests).
     */
    void generate() throws IOException {
        JavaUtils.setJavaLibPath(Path.of("").toAbsolutePath(), Optional.of(JAVA_VERSION));
        String classpath = JavaUtils.initializeJavaClassPath();
        assertFalse(classpath.isEmpty(), "no JDK sources in lib/java" + JAVA_VERSION);
        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, classpath);
        TokensDataset.generate(repoRoot, outputPath, "training", PROJECT, classpath);
    }

    /** The messages of the targets missing from the candidates, by test prefix (only the prefixes with messages). */
    Map<String, List<String>> unexpectedTokens() throws IOException {
        Map<String, List<String>> unexpected = new LinkedHashMap<>();
        try (var files = Files.list(outputPath.resolve("statistics-tokens-dataset-training"))) {
            for (Path file : files.toList()) {
                JsonNode statistics = new ObjectMapper().readTree(file.toFile());
                statistics.fields().forEachRemaining(testClass -> testClass.getValue().fields().forEachRemaining(testCase -> {
                    List<String> messages = new ArrayList<>();
                    testCase.getValue().fields().forEachRemaining(target -> target.getValue()
                            .forEach(message -> {
                                if (message.asText().startsWith(UNEXPECTED_TOKEN)) {
                                    messages.add(message.asText());
                                }
                            }));
                    if (!messages.isEmpty()) {
                        unexpected.put(testCase.getKey(), messages);
                    }
                }));
            }
        }
        return unexpected;
    }

    /** The tokens datapoints, by test prefix. */
    Map<String, JsonNode> tokensDatapoints() throws IOException {
        Map<String, JsonNode> datapoints = new LinkedHashMap<>();
        try (var files = Files.list(outputPath.resolve("raw-tokens-dataset-training").resolve(PROJECT))) {
            for (Path file : files.toList()) {
                for (JsonNode testClass : new ObjectMapper().readTree(file.toFile())) {
                    for (JsonNode datapoint : testClass.get("datapoints")) {
                        datapoints.put(datapoint.get("testPrefix").get("identifier").asText(), datapoint);
                    }
                }
            }
        }
        return datapoints;
    }

    /** The candidate project tokens of the token step that follows the given partial oracle. */
    static List<String> candidatesAfter(JsonNode datapoint, String partialSnippet) {
        assertNotNull(datapoint, "no tokens datapoint");
        for (JsonNode step : datapoint.get("tokenSteps")) {
            if (step.get("partialSnippet").asText().equals(partialSnippet)) {
                List<String> candidates = new ArrayList<>();
                step.get("nextPossibleProjectTokens").forEach(token -> candidates.add(token.asText()));
                return candidates;
            }
        }
        throw new AssertionError("no token step after " + partialSnippet);
    }
}
