package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import star.tracto.utils.JavaUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the generation of the oracles dataset ({@link OraclesDataset#generate}) on a small repository,
 * with the configuration committed in the module.
 */
class OraclesDatasetTest {

    private static final Path COMMITTED_CONFIG = Path.of("src/main/resources/dataset/oracles-dataset_config.json");
    private static final String PROJECT = "fixture";

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

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest {
                    @Test
                    public void testAdd() {
                        Calc calc = new Calc();
                        assertEquals(3, calc.add(1, 2));
                        assertEquals(0, calc.add(0, 0));
                    }

                    @Test
                    public void testAccumulate() {
                        Calc calc = new Calc();
                        accumulateTwice(calc, 2);
                        assertEquals(4, calc.total());
                    }

                    @Test
                    public void testNoException() {
                        Calc calc = new Calc();
                        try {
                            calc.accumulate(1);
                        } catch (IllegalStateException e) {
                            fail("unexpected");
                        }
                        assertEquals(1, calc.total());
                    }

                    @Test
                    public void testPositiveSum() {
                        Calc calc = new Calc();
                        if (calc.add(1, 1) < 0) {
                            fail("negative sum");
                        }
                    }

                    private void accumulateTwice(Calc calc, int value) {
                        calc.accumulate(value);
                        calc.accumulate(value);
                        assertTrue(calc.total() > 0);
                    }
                }
                """);
        // A test class without a focal class with the corresponding name produces no datapoints
        write("src/test/java/com/acme/OrphanTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class OrphanTest {
                    @Test
                    public void testNothing() {
                        assertTrue(true);
                    }
                }
                """);
    }

    @Test
    void generatesTheDatapointsOfTheTestClassesMatchingAFocalClass() throws IOException {
        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());

        JsonNode statistics = readJson(outputPath.resolve("statistics-oracles-dataset-training/fixture/statistics-fixture.json"));
        assertEquals(2, statistics.get("testFilePaths").asInt());
        assertEquals(1, statistics.get("perfectMatches").asInt());
        assertEquals(1, statistics.get("testClassesProcessed").asInt());
        assertEquals(5, statistics.get("oracleDatapoints").asInt());
        // The test case checking that no exception is thrown (fail() in the catch block) is discarded
        assertEquals(1, statistics.get("discardedTestCases").asInt());
        JsonNode testStats = readJson(outputPath.resolve("statistics-oracles-dataset-training/fixture/test-stats-fixture.json"));
        List<String> discarded = new ArrayList<>();
        for (JsonNode testCaseStats : testStats) {
            if (!testCaseStats.get("discardReason").isNull()) {
                discarded.add(testCaseStats.get("identifier").asText() + ": " + testCaseStats.get("discardReason").asText());
            }
        }
        assertEquals(List.of("testNoException: MUST_NOT_THROW"), discarded);
        // The assertion of the auxiliary method is inlined in the test case, so it becomes a target too; a fail(...)
        // is a target as it is written
        assertEquals(List.of(
                "assertEquals(3, calc.add(1, 2));",
                "assertEquals(0, calc.add(0, 0));",
                "assertTrue(calc.total() > 0);",
                "assertEquals(4, calc.total());",
                "fail(\"negative sum\");"
        ), targets());
    }

    @Test
    void statisticsRecordTheJavaVersionOfTheRepositoryAndTheJdksOfTheSymbolSolver() throws IOException {
        write("pom.xml", "<project><properties><maven.compiler.release>9</maven.compiler.release></properties></project>");
        Path base = tempDir.resolve("base");
        Files.createDirectories(base.resolve("lib/java8/src"));
        Files.createDirectories(base.resolve("lib/java11/src/java.base"));
        Files.writeString(base.resolve("lib/java_classpaths.json"), "{\"8\": [\"src\"], \"11\": [\"src/java.base\"]}");
        JavaUtils.setJavaLibPath(base, "auto", repoRoot);

        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());

        JsonNode statistics = readJson(outputPath.resolve("statistics-oracles-dataset-training/fixture/statistics-fixture.json"));
        assertEquals(9, statistics.get("javaVersion").asInt());
        assertEquals("detected", statistics.get("javaVersionSource").asText());
        assertEquals("[11]", statistics.get("jdkVersions").toString());
    }

    @Test
    void tokenStepsOfAFailTargetAreTheTokensOfTheCall() throws IOException {
        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());

        TokensDataset.generate(repoRoot, outputPath, "training", PROJECT, emptyClasspath.toString());

        JsonNode testClasses = readJson(outputPath.resolve("raw-tokens-dataset-training/fixture/oracles-datapoints-fixture-0.json"));
        JsonNode failDatapoint = null;
        for (JsonNode testClass : testClasses) {
            for (JsonNode datapoint : testClass.get("datapoints")) {
                if (datapoint.get("target").asText().startsWith("fail(")) {
                    failDatapoint = datapoint;
                }
            }
        }
        assertNotNull(failDatapoint, "Expected a datapoint with a fail(...) target in " + testClasses);
        JsonNode firstStep = failDatapoint.get("tokenSteps").get(0);
        assertEquals("ASSERT:fail", firstStep.get("target").asText());
        assertEquals(0, firstStep.get("nextPossibleGenericTokens").size(), firstStep.toString());
        assertTrue(failDatapoint.get("tokenSteps").size() > 1, "Expected one token step per token of " + failDatapoint.get("target"));
    }

    @Test
    void runningTheGenerationAgainGivesTheSameResult() throws IOException {
        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());
        String firstStatistics = Files.readString(outputPath.resolve("statistics-oracles-dataset-training/fixture/statistics-fixture.json"));
        List<String> firstTargets = targets();

        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());

        assertEquals(firstStatistics, Files.readString(outputPath.resolve("statistics-oracles-dataset-training/fixture/statistics-fixture.json")));
        assertEquals(firstTargets, targets());
    }

    @Test
    void focalClassWhoseNameContainsTestIsMatched() throws IOException {
        write("src/main/java/com/acme/Latest.java", """
                package com.acme;

                public class Latest {
                    public int value() {
                        return 1;
                    }
                }
                """);
        write("src/test/java/com/acme/LatestTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class LatestTest {
                    @Test
                    public void testValue() {
                        assertEquals(1, new Latest().value());
                    }
                }
                """);

        OraclesDataset.generate(repoRoot, Optional.empty(), COMMITTED_CONFIG, outputPath, PROJECT, emptyClasspath.toString());

        JsonNode statistics = readJson(outputPath.resolve("statistics-oracles-dataset-training/fixture/statistics-fixture.json"));
        assertEquals(2, statistics.get("perfectMatches").asInt());
    }

    /** Returns the targets of all the datapoints of the generated dataset, in order. */
    private List<String> targets() throws IOException {
        JsonNode testClasses = readJson(outputPath.resolve("raw-oracles-dataset-training/fixture/oracles-datapoints-fixture-0.json"));
        List<String> targets = new ArrayList<>();
        for (JsonNode testClass : testClasses) {
            for (JsonNode datapoint : testClass.get("datapoints")) {
                targets.add(datapoint.get("target").asText());
            }
        }
        return targets;
    }

    private void write(String relativePath, String source) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static JsonNode readJson(Path file) throws IOException {
        assertTrue(Files.exists(file), "Expected file " + file);
        return new ObjectMapper().readTree(file.toFile());
    }
}
