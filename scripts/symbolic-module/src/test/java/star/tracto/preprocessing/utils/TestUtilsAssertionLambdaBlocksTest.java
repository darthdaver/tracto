package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ReferenceType;
import org.javatuples.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import star.tracto.data.enums.AssertionLambdaBlocksStrategyType;
import star.tracto.data.enums.NumAssertionsMatchStrategyType;
import star.tracto.data.enums.SplitStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.TestStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the assertions whose lambda expression is a block of statements ({@code assertThrows},
 * {@code assertThrowsExactly} and {@code assertDoesNotThrow}): the statements before the last one move before the
 * assertion during the normalization ({@code move-to-prefix}), or the assertion produces no datapoint ({@code skip},
 * and the shapes whose throwing statement is unclear).
 */
class TestUtilsAssertionLambdaBlocksTest {

    private static final OraclesDatasetConfig MOVE_TO_PREFIX = FixtureRepo.CONFIG;
    private static final OraclesDatasetConfig SKIP = new ConfigBuilder().assertionLambdaBlocks(AssertionLambdaBlocksStrategyType.SKIP).build();

    @TempDir
    Path tempDir;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FixtureRepo(tempDir);
    }

    // Normalization

    static Stream<Arguments> movedBlocks() {
        return Stream.of(
                Arguments.of("the last statement is a call",
                        "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });",
                        "values.add(1); assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("the assertion declares a variable and has a message",
                        "IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); }, \"out of range\"); assertNotNull(e);",
                        "values.add(1); IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class, () -> values.get(5), \"out of range\"); assertNotNull(e);"),
                Arguments.of("assertThrowsExactly",
                        "assertThrowsExactly(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });",
                        "values.add(1); assertThrowsExactly(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("the last statement declares a variable",
                        "assertThrows(NumberFormatException.class, () -> { String s = \"x\" + count; int v = Integer.parseInt(s); });",
                        "String s = \"x\" + count; assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));"),
                Arguments.of("the last statement is an assignment",
                        "assertThrows(NumberFormatException.class, () -> { String s = \"x\"; count = Integer.parseInt(s); });",
                        "String s = \"x\"; assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));"),
                Arguments.of("the assertions before the last statement move out too",
                        "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); assertEquals(1, values.size()); values.get(5); });",
                        "values.add(1); assertEquals(1, values.size()); assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("assertDoesNotThrow",
                        "assertDoesNotThrow(() -> { values.add(1); values.get(0); });",
                        "values.add(1); assertDoesNotThrow(() -> values.get(0));"),
                Arguments.of("assertDoesNotThrow returning a value",
                        "int first = assertDoesNotThrow(() -> { values.add(1); return values.get(0); }); assertEquals(1, first);",
                        "values.add(1); int first = assertDoesNotThrow(() -> values.get(0)); assertEquals(1, first);"),
                Arguments.of("assertDoesNotThrow ending with an assertion is unwrapped",
                        "assertDoesNotThrow(() -> { values.add(1); assertEquals(1, values.size()); });",
                        "values.add(1); assertEquals(1, values.size());"),
                Arguments.of("a nested lambda block is moved in turn",
                        "assertDoesNotThrow(() -> { values.add(1); assertThrows(IndexOutOfBoundsException.class, () -> { values.add(2); values.get(5); }); });",
                        "values.add(1); values.add(2); assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("a variable reassigned within the block is captured through a final copy",
                        "assertThrows(IndexOutOfBoundsException.class, () -> { int i = 0; i += 5; values.get(i); });",
                        "int i = 0; i += 5; final int finalI = i; assertThrows(IndexOutOfBoundsException.class, () -> values.get(finalI));"),
                Arguments.of("a variable hiding a field is renamed",
                        "assertThrows(NumberFormatException.class, () -> { String count = \"x\"; Integer.parseInt(count); }); count++;",
                        "String count2 = \"x\"; assertThrows(NumberFormatException.class, () -> Integer.parseInt(count2)); count++;"),
                Arguments.of("a block of one call becomes an expression",
                        "assertThrows(IndexOutOfBoundsException.class, () -> { values.get(5); });",
                        "assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("a block of one declaration keeps its initializer",
                        "assertThrows(NumberFormatException.class, () -> { int v = Integer.parseInt(\"x\"); });",
                        "assertThrows(NumberFormatException.class, () -> Integer.parseInt(\"x\"));"),
                Arguments.of("a block of one return keeps its value",
                        "int first = assertDoesNotThrow(() -> { return values.get(0); }); assertEquals(1, first);",
                        "int first = assertDoesNotThrow(() -> values.get(0)); assertEquals(1, first);"),
                Arguments.of("an assertDoesNotThrow of one assertion is unwrapped",
                        "assertDoesNotThrow(() -> { assertEquals(0, values.size()); });",
                        "assertEquals(0, values.size());"),
                Arguments.of("an auxiliary method called within the block is inlined",
                        "assertThrows(IndexOutOfBoundsException.class, () -> { fill(); values.get(5); });",
                        "values.add(1); values.add(2); assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("movedBlocks")
    void statementsBeforeTheLastOneMoveBeforeTheAssertion(String description, String statements, String expectedStatements) throws IOException {
        Path normalizedFile = normalize(MOVE_TO_PREFIX, testCase("testBlock", "", statements)).getValue0();

        assertEquals(block(expectedStatements), body(normalizedFile, "testBlock"));
    }

    static Stream<Arguments> unchangedBlocks() {
        return Stream.of(
                Arguments.of("a single throw", "assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException(); });"),
                Arguments.of("assertThrows ending with an assertion", "assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); });"),
                Arguments.of("a try statement at the end", "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); try { values.get(5); } finally { values.clear(); } });"),
                Arguments.of("a loop at the end", "assertDoesNotThrow(() -> { values.add(1); for (int v : values) { values.get(0); } });"),
                Arguments.of("a throw at the end", "assertThrows(IllegalStateException.class, () -> { values.add(1); throw new IllegalStateException(); });"),
                Arguments.of("a return before the last statement", "assertThrows(IndexOutOfBoundsException.class, () -> { if (values.isEmpty()) { return; } values.get(5); });")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unchangedBlocks")
    void assertionsWithoutAClearThrowingStatementAreNotChanged(String description, String statements) throws IOException {
        Path normalizedFile = normalize(MOVE_TO_PREFIX, testCase("testBlock", "", statements)).getValue0();

        assertEquals(block(statements), body(normalizedFile, "testBlock"));
    }

    static Stream<Arguments> commentedBlocks() {
        return Stream.of(
                Arguments.of("moved statements",
                        "// the scenario\nassertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });",
                        "// the scenario\nvalues.add(1); assertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("unwrapped statements",
                        "// the scenario\nassertDoesNotThrow(() -> { values.add(1); assertEquals(1, values.size()); });",
                        "// the scenario\nvalues.add(1); assertEquals(1, values.size());"),
                Arguments.of("a first statement with its own comment",
                        "// the scenario\nassertThrows(IndexOutOfBoundsException.class, () -> {\n// one value\nvalues.add(1); values.get(5); });",
                        "// one value\nvalues.add(1);\n// the scenario\nassertThrows(IndexOutOfBoundsException.class, () -> values.get(5));"),
                Arguments.of("a block of one call",
                        "// the scenario\nassertThrows(IndexOutOfBoundsException.class, () -> { values.get(5); });",
                        "// the scenario\nassertThrows(IndexOutOfBoundsException.class, () -> values.get(5));")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commentedBlocks")
    void commentOfTheAssertionPrecedesTheStatementsMovedOutOfItsBlock(String description, String statements, String expectedStatements) throws IOException {
        Path normalizedFile = normalize(MOVE_TO_PREFIX, testCase("testBlock", "", statements)).getValue0();

        assertEquals(block(expectedStatements).toString(), body(normalizedFile, "testBlock").toString());
    }

    @Test
    void skipStrategyLeavesTheLambdaBlocksUnchanged() throws IOException {
        String statements = "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); }); assertDoesNotThrow(() -> { values.add(1); assertEquals(2, values.size()); });";

        Path normalizedFile = normalize(SKIP, testCase("testBlock", "", statements)).getValue0();

        assertEquals(block(statements), body(normalizedFile, "testBlock"));
    }

    static Stream<Arguments> thrownExceptions() {
        return Stream.of(
                Arguments.of("statements moved out of a lambda", "", "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });", List.of("Exception")),
                Arguments.of("an unwrapped assertDoesNotThrow", "", "assertDoesNotThrow(() -> { values.add(1); assertEquals(1, values.size()); });", List.of("Exception")),
                Arguments.of("Exception already declared", "throws Exception", "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });", List.of("Exception")),
                Arguments.of("Throwable already declared", "throws Throwable", "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });", List.of("Throwable")),
                Arguments.of("another exception declared", "throws IOException", "assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });", List.of("IOException", "Exception")),
                Arguments.of("a qualified assertion at the end of a longer test case", "throws IOException, InterruptedException",
                        "values.add(0); Assertions.assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });", List.of("IOException", "InterruptedException", "Exception")),
                Arguments.of("no statement moved", "", "assertThrows(IndexOutOfBoundsException.class, () -> { values.get(5); });", List.of()),
                Arguments.of("a block left unchanged", "", "assertThrows(IllegalStateException.class, () -> { values.add(1); throw new IllegalStateException(); });", List.of())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thrownExceptions")
    void testCaseDeclaresExceptionWhenStatementsLeaveALambda(String description, String throwsClause, String statements, List<String> expectedThrownExceptions) throws IOException {
        Path normalizedFile = normalize(MOVE_TO_PREFIX, testCase("testBlock", throwsClause, statements)).getValue0();

        assertEquals(expectedThrownExceptions, method(normalizedFile, "testBlock").getThrownExceptions().stream().map(ReferenceType::asString).toList());
    }

    @Test
    void statisticsCountTheOutcomeOfEachLambdaBlock() throws IOException {
        String statements = """
                assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });
                assertDoesNotThrow(() -> { values.add(1); assertEquals(2, values.size()); });
                assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(2)); });
                assertThrows(IllegalStateException.class, () -> { values.add(1); throw new IllegalStateException(); });
                assertThrows(IndexOutOfBoundsException.class, () -> { values.get(5); });
                """;

        List<TestStats> moved = normalize(MOVE_TO_PREFIX, testCase("testBlocks", "", statements)).getValue1();
        List<TestStats> skipped = normalize(SKIP, testCase("testBlocks", "", statements)).getValue1();

        assertEquals(Map.of("moved-to-prefix", 1, "unwrapped", 1, "skipped-last-statement-assertion", 1, "skipped-last-statement-throw", 1, "expression-lambda", 1),
                moved.get(0).assertionLambdaBlocks());
        assertEquals(Map.of("skipped-last-statement-call", 2, "skipped-last-statement-assertion", 2, "skipped-last-statement-throw", 1),
                skipped.get(0).assertionLambdaBlocks());
    }

    // Splitting

    @Test
    void assertionWithoutDatapointIsNotASplitPointButStaysInTheFollowingPrefixes() throws IOException {
        Pair<Path, Path> splitFiles = split(MOVE_TO_PREFIX, """
                @Test
                public void testSkipped() {
                    int x = 1;
                    assertEquals(1, x);
                    assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); });
                    assertTrue(x > 0);
                }
                """);

        assertEquals(Map.of(
                "testSkipped_split_0", block("int x = 1; assertEquals(1, x);"),
                "testSkipped_split_1", block("int x = 1; assertEquals(1, x); assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); }); assertTrue(x > 0);")
        ), methodBodies(splitFiles.getValue0()));
    }

    @Test
    void lambdaBlockLeftByTheNormalizationIsASplitPointOnlyWithTheMoveToPrefixStrategy() throws IOException {
        String testCase = """
                @Test
                public void testBlock() {
                    assertThrows(IndexOutOfBoundsException.class, () -> { values.add(1); values.get(5); });
                }
                """;

        assertEquals(List.of("testBlock_split_0"), List.copyOf(methodBodies(split(MOVE_TO_PREFIX, testCase).getValue0()).keySet()));
        assertEquals(List.of(), List.copyOf(methodBodies(split(SKIP, testCase).getValue0()).keySet()));
    }

    @Test
    void assertionWithoutDatapointDoesNotDropTheTestCaseWithStrictAssertionsMatch() throws IOException {
        OraclesDatasetConfig strict = new ConfigBuilder().numAssertionsMatch(NumAssertionsMatchStrategyType.STRICT).build();

        Pair<Path, Path> splitFiles = split(strict, """
                @Test
                public void testSkipped() {
                    assertEquals(0, values.size());
                    assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); });
                }
                """);

        assertEquals(List.of("testSkipped_split_0"), List.copyOf(methodBodies(splitFiles.getValue0()).keySet()));
    }

    @Test
    void assertionWithoutDatapointAtTheEndIsNotKeptAsAnExtraSplitTestCase() throws IOException {
        OraclesDatasetConfig keepAfterLast = new ConfigBuilder().keepStatementsAfterLastAssertion(true).build();

        Pair<Path, Path> splitFiles = split(keepAfterLast, """
                @Test
                public void testSkipped() {
                    assertEquals(0, values.size());
                    assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); });
                }
                """);

        assertEquals(List.of("testSkipped_split_0"), List.copyOf(methodBodies(splitFiles.getValue0()).keySet()));
    }

    @Test
    void assertionWithoutDatapointIsNotASplitPointWithTheStatementStrategy() throws IOException {
        OraclesDatasetConfig statementSplit = new ConfigBuilder().split(SplitStrategyType.STATEMENT).build();

        Pair<Path, Path> splitFiles = split(statementSplit, """
                @Test
                public void testSkipped() {
                    values.add(1);
                    assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); });
                    values.clear();
                }
                """);

        assertEquals(List.of("testSkipped_split_0", "testSkipped_split_1"), List.copyOf(methodBodies(splitFiles.getValue0()).keySet()));
        assertEquals(block("values.add(1); assertThrows(ArithmeticException.class, () -> { values.add(0); assertEquals(0, 1 / values.get(0)); }); values.clear();"),
                methodBodies(splitFiles.getValue0()).get("testSkipped_split_1"));
    }

    // Datapoints

    @Test
    void movedStatementsArePartOfThePrefixOfTheShortenedAssertion() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(MOVE_TO_PREFIX, """
                @Test
                public void testThrows() {
                    assertThrows(ArithmeticException.class, () -> { Calc calc = new Calc(); calc.divide(1, 0); });
                }
                """);

        assertEquals(1, datapoints.size());
        assertEquals("assertThrows(ArithmeticException.class, () -> calc.divide(1, 0));", datapoints.get(0).target());
        assertTrue(datapoints.get(0).testPrefix().body().contains("Calc calc = new Calc();"), datapoints.get(0).testPrefix().body());
    }

    @Test
    void assertionsOfAnUnwrappedAssertDoesNotThrowAreTargets() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(MOVE_TO_PREFIX, """
                @Test
                public void testAccumulate() {
                    Calc calc = new Calc();
                    assertDoesNotThrow(() -> { calc.accumulate(2); assertEquals(2, calc.total()); });
                }
                """);

        assertEquals(List.of("assertEquals(2, calc.total());"), datapoints.stream().map(OracleDatapoint::target).toList());
        assertTrue(datapoints.get(0).testPrefix().body().contains("calc.accumulate(2);"), datapoints.get(0).testPrefix().body());
    }

    @Test
    void assertionWithoutDatapointKeepsTheOtherDatapointsOfTheTestCase() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(MOVE_TO_PREFIX, """
                @Test
                public void testSkipped() {
                    Calc calc = new Calc();
                    assertEquals(3, calc.add(1, 2));
                    assertThrows(ArithmeticException.class, () -> { calc.accumulate(1); assertEquals(0, calc.divide(1, 0)); });
                    assertEquals(1, calc.total());
                }
                """);

        assertEquals(List.of("assertEquals(3, calc.add(1, 2));", "assertEquals(1, calc.total());"), datapoints.stream().map(OracleDatapoint::target).toList());
    }

    @Test
    void blockOfOneCallIsATargetOnOneLine() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(MOVE_TO_PREFIX, """
                @Test
                public void testThrows() {
                    Calc calc = new Calc();
                    assertThrows(ArithmeticException.class, () -> {
                        calc.divide(1, 0);
                    });
                }
                """);

        assertEquals(List.of("assertThrows(ArithmeticException.class, () -> calc.divide(1, 0));"), datapoints.stream().map(OracleDatapoint::target).toList());
    }

    static Stream<Arguments> assertionsWithAStatementBlock() {
        return Stream.of(
                Arguments.of("a lambda block of another assertion",
                        "assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> { calc.accumulate(1); assertEquals(1, calc.total()); });"),
                Arguments.of("a lambda block of one statement of another assertion",
                        "assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> { assertEquals(0, calc.total()); });"),
                Arguments.of("a lambda block nested in an argument",
                        "assertEquals(0, java.util.stream.Stream.of(1).filter(v -> { return v > 1; }).count());"),
                Arguments.of("an anonymous class",
                        "assertEquals(\"x\", new Object() { public String toString() { return \"x\"; } }.toString());"),
                Arguments.of("a lambda block left by the normalization", "assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException(); });")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("assertionsWithAStatementBlock")
    void assertionContainingAStatementBlockIsNotATarget(String description, String assertion) throws IOException {
        List<OracleDatapoint> datapoints = datapoints(MOVE_TO_PREFIX, """
                @Test
                public void testBlock() {
                    Calc calc = new Calc();
                    assertEquals(3, calc.add(1, 2));
                    %s
                    assertTimeout(java.time.Duration.ofSeconds(1), () -> calc.accumulate(2));
                }
                """.formatted(assertion));

        assertEquals(List.of("assertEquals(3, calc.add(1, 2));", "assertTimeout(java.time.Duration.ofSeconds(1), () -> calc.accumulate(2));"),
                datapoints.stream().map(OracleDatapoint::target).toList());
        datapoints.forEach(datapoint -> assertTrue(!datapoint.target().contains("\n"), datapoint.target()));
    }

    // Behaviour of the normalized code

    @Test
    void movedStatementsRunAsInTheOriginalTestCase() throws Exception {
        Path testFile = repo.writeTestClass("com/acme/MovedTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class MovedTest {
                    public static final List<String> TRACE = new ArrayList<>();

                    @Test
                    public void testMoved() {
                        TRACE.add("start");
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> {
                            String s = "x";
                            TRACE.add("before " + s);
                            s = s + "y";
                            Integer.parseInt(s);
                        });
                        TRACE.add("caught " + e.getMessage());
                        int value = assertDoesNotThrow(() -> {
                            TRACE.add("parse");
                            return Integer.parseInt("7");
                        });
                        TRACE.add("value " + value);
                    }
                }
                """);
        List<String> expectedTrace = List.of("start", "before x", "caught For input string: \"xy\"", "parse", "value 7");

        Path normalizedFile = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(MOVE_TO_PREFIX, repo.root, testFile, repo.output, Optional.empty())).getValue0();

        assertEquals(expectedTrace, runTrace(testFile, "com.acme.MovedTest", "testMoved"), "Original test case");
        assertEquals(expectedTrace, runTrace(normalizedFile, "com.acme.MovedTestSTARNormalizedTest", "testMoved"), "Normalized test case");
        assertTrue(Files.readString(normalizedFile).contains("() -> Integer.parseInt(finalS)"), "Expected the statements to be moved");
    }

    @Test
    void unwrappedStatementsThrowingCheckedExceptionsCompile() throws Exception {
        Path testFile = repo.writeTestClass("com/acme/UnwrappedTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class UnwrappedTest {
                    public static final List<String> TRACE = new ArrayList<>();

                    @Test
                    public void testUnwrapped() {
                        assertDoesNotThrow(() -> {
                            Thread.sleep(0);
                            TRACE.add("slept");
                            assertEquals(1, TRACE.size());
                        });
                    }
                }
                """);

        Path normalizedFile = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(MOVE_TO_PREFIX, repo.root, testFile, repo.output, Optional.empty())).getValue0();

        assertEquals(List.of("slept"), runTrace(normalizedFile, "com.acme.UnwrappedTestSTARNormalizedTest", "testUnwrapped"));
        assertTrue(!Files.readString(normalizedFile).contains("assertDoesNotThrow"), "Expected the assertDoesNotThrow to be unwrapped");
    }

    // Helpers

    /** Returns the source of a JUnit 5 test case with the given name, throws clause and statements. */
    private static String testCase(String name, String throwsClause, String statements) {
        return """
                @Test
                public void %s() %s {
                    %s
                }
                """.formatted(name, throwsClause, statements);
    }

    /**
     * Normalizes, with the given configuration, a JUnit 5 test class {@code com.acme.BlocksTest} with the fields
     * {@code values} and {@code count}, the auxiliary method {@code fill()} and the given test cases.
     */
    private Pair<Path, List<TestStats>> normalize(OraclesDatasetConfig config, String testCases) throws IOException {
        Path testFile = repo.writeTestClass("com/acme/BlocksTest.java", """
                package com.acme;

                import java.io.IOException;
                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Assertions;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class BlocksTest {
                    private final List<Integer> values = new ArrayList<>();
                    private int count;

                    private void fill() {
                        values.add(1);
                        values.add(2);
                    }

                %s
                }
                """.formatted(testCases));
        return assertDoesNotThrow(() -> TestUtils.normalizeTest(config, repo.root, testFile, repo.output, Optional.empty()));
    }

    /**
     * Splits, with the given configuration, a normalized JUnit 5 test class {@code com.acme.BlocksTestSTARNormalizedTest}
     * with the field {@code values} and the given test cases, and returns the paths to the split test class and to the
     * split test prefix class.
     */
    private Pair<Path, Path> split(OraclesDatasetConfig config, String testCases) throws IOException {
        Path normalizedFile = repo.writeTestClass("com/acme/BlocksTestSTARNormalizedTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class BlocksTestSTARNormalizedTest {
                    private final List<Integer> values = new ArrayList<>();

                %s
                }
                """.formatted(testCases));
        return assertDoesNotThrow(() -> TestUtils.splitTest(config, repo.root, normalizedFile, repo.output));
    }

    /**
     * Writes the focal class {@code com.acme.Calc} and a JUnit 5 test class {@code com.acme.CalcTest} with the given
     * test cases, then normalizes, splits and processes the test class as the dataset generation does.
     */
    private List<OracleDatapoint> datapoints(OraclesDatasetConfig config, String testCases) throws IOException {
        Path focalFile = repo.writeSourceClass("com/acme/Calc.java", """
                package com.acme;

                public class Calc {
                    private int total;

                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int divide(int a, int b) {
                        return a / b;
                    }

                    public void accumulate(int value) {
                        total += value;
                    }

                    public int total() {
                        return total;
                    }
                }
                """);
        Path testFile = repo.writeTestClass("com/acme/CalcTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest {
                %s
                }
                """.formatted(testCases));
        Path normalizedFile = TestUtils.normalizeTest(config, repo.root, testFile, repo.output, Optional.empty()).getValue0();
        repo.refreshParser();
        Pair<Path, Path> splitFiles = TestUtils.splitTest(config, repo.root, normalizedFile, repo.output);
        repo.refreshParser();
        return TestUtils.processSplitTestClass(config, splitFiles.getValue0(), splitFiles.getValue1(), focalFile, repo.root).getValue0().datapoints();
    }

    private static BlockStmt block(String statements) {
        return StaticJavaParser.parseBlock("{" + statements + "}");
    }

    private static MethodDeclaration method(Path javaFile, String methodName) throws IOException {
        return StaticJavaParser.parse(javaFile).findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals(methodName))
                .orElseThrow(() -> new AssertionError("Method " + methodName + " not found in " + javaFile));
    }

    private static BlockStmt body(Path javaFile, String methodName) throws IOException {
        return method(javaFile, methodName).getBody().orElseThrow();
    }

    /** Returns the bodies of the test cases of the class in the given file, by name, in the order of the source code. */
    private static Map<String, BlockStmt> methodBodies(Path javaFile) throws IOException {
        Map<String, BlockStmt> bodies = new java.util.LinkedHashMap<>();
        StaticJavaParser.parse(javaFile).findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getAnnotationByName("Test").isPresent())
                .forEach(m -> bodies.put(m.getNameAsString(), m.getBody().orElseThrow()));
        return bodies;
    }

    /**
     * Compiles a test class, executes one of its test methods and returns the content of its static {@code TRACE}
     * list.
     */
    private List<String> runTrace(Path sourceFile, String className, String testMethodName) throws Exception {
        Path classesDir = Files.createTempDirectory(tempDir, "classes");
        JavaCompilation.assertCompiles(classesDir, sourceFile);
        return JavaCompilation.runTrace(classesDir, className, testMethodName);
    }
}
