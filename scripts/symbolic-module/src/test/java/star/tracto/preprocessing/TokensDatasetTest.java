package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the generation of the tokens dataset ({@link TokensDataset#generate}) on a small repository: the
 * oracles stage runs first with the configuration committed in the module, then the tokens stage on its output. Checks
 * that the identifiers in scope at the position of each target are offered as candidate tokens, and only them.
 */
class TokensDatasetTest {

    private static final Path COMMITTED_CONFIG = Path.of("src/main/resources/dataset/oracles-dataset_config.json");
    private static final String PROJECT = "fixture";
    private static final String UNEXPECTED_TOKEN = "Unexpected token";

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path outputPath;
    private Path emptyClasspath;

    @BeforeEach
    void setUp() throws IOException {
        repoRoot = tempDir.resolve("repo");
        outputPath = tempDir.resolve("output");
        emptyClasspath = Files.createDirectories(tempDir.resolve("no-libs"));
        write("src/main/java/com/acme/Calc.java", """
                package com.acme;

                public class Calc {
                    private int total;

                    public int add(int a, int b) {
                        return a + b;
                    }

                    public void accumulate(int value) {
                        total += value;
                    }

                    public int total() {
                        return total;
                    }
                }
                """);
        write("src/test/java/com/acme/CalcTest.java", """
                package com.acme;

                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest {
                    @Test
                    public void testLoopBody() {
                        Calc calc = new Calc();
                        for (int i = 0; i < 2; i++) {
                            int doubled = calc.add(i, i);
                            assertEquals(doubled, calc.add(i, i));
                        }
                    }

                    @Test
                    public void testForEach() {
                        Calc calc = new Calc();
                        for (Integer value : List.of(1, 2)) {
                            assertEquals(value, calc.add(value, 0));
                        }
                    }

                    @Test
                    public void testLambdaBlock() {
                        Calc calc = new Calc();
                        List.of(1, 2).forEach(v -> {
                            int sum = calc.add(v, 0);
                            assertEquals(sum, calc.add(v, 0));
                        });
                    }

                    @ParameterizedTest
                    @ValueSource(ints = {1, 2})
                    public void testParameter(int input) {
                        Calc calc = new Calc();
                        assertEquals(input, calc.add(input, 0));
                    }

                    @Test
                    public void testOutOfScope() {
                        Calc calc = new Calc();
                        for (int i = 0; i < 1; i++) {
                            int inner = calc.add(i, 1);
                            calc.accumulate(inner);
                        }
                        List.of(1).forEach(x -> calc.accumulate(x));
                        int total = calc.total();
                        assertEquals(total, calc.total());
                    }
                }
                """);
    }

    @Test
    void theVariablesOfNestedBlocksAndTheParametersOfTheTestAreOfferedAsCandidates() throws IOException {
        generate();

        Map<String, List<String>> missing = unexpectedTokens();
        assertEquals(Map.of(), missing.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        // Every oracle produces its tokens datapoint
        assertEquals(List.of("testForEach_split_0", "testLambdaBlock_split_0", "testLoopBody_split_0", "testOutOfScope_split_0",
                "testParameter_split_0"), tokensDatapoints().keySet().stream().sorted().toList());
    }

    @Test
    void theVariablesOutOfScopeAtThePositionOfTheTargetAreNotOffered() throws IOException {
        generate();

        // At the first token after `assertEquals(` the candidates include the variable declared before the target,
        // not the variables of the previous loop nor the parameter of the previous lambda
        List<String> candidates = candidatesAfter(tokensDatapoints().get("testOutOfScope_split_0"), "assertEquals(");
        assertTrue(candidates.contains("total"), candidates.toString());
        assertTrue(candidates.contains("calc"), candidates.toString());
        assertFalse(candidates.contains("inner"), candidates.toString());
        assertFalse(candidates.contains("i"), candidates.toString());
        assertFalse(candidates.contains("x"), candidates.toString());
    }

    private void generate() throws IOException {
        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());
        TokensDataset.generate(repoRoot, outputPath, "training", PROJECT, emptyClasspath.toString());
    }

    /** The messages of the targets missing from the candidates, by test prefix. */
    private Map<String, List<String>> unexpectedTokens() throws IOException {
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
                    unexpected.put(testCase.getKey(), messages);
                }));
            }
        }
        return unexpected;
    }

    /** The tokens datapoints, by test prefix. */
    private Map<String, JsonNode> tokensDatapoints() throws IOException {
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
    private static List<String> candidatesAfter(JsonNode datapoint, String partialSnippet) {
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

    private void write(String relativePath, String source) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
