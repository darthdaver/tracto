package star.tracto.preprocessing.utils;

import org.javatuples.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import star.tracto.data.enums.AssertionStrategyType;
import star.tracto.data.enums.SplitStrategyType;
import star.tracto.data.enums.TargetStrategyType;
import star.tracto.data.enums.TryCatchFinallyStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.preprocessing.components.Callable;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the generation of the oracles datapoints ({@code TestUtils.processSplitTestClass}). Each test runs the three
 * phases of the dataset generation (normalization, splitting, datapoints) on a test class of the focal class
 * {@code com.acme.Calc}.
 */
class TestUtilsDatapointsTest {

    private static final String FOCAL_CLASS = """
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
            """;

    private static final String TEST_ADD = """
            @Test
            public void testAdd() {
                Calc calc = new Calc();
                int sum = calc.add(1, 2);
                assertEquals(3, sum);
                calc.accumulate(sum);
                Assertions.assertEquals(3, calc.total());
            }
            """;

    @TempDir
    Path tempDir;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FixtureRepo(tempDir);
    }

    @Test
    void eachAssertionIsATargetMaskedInItsTestPrefix() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, TEST_ADD);

        assertEquals(List.of("testAdd_split_0", "testAdd_split_1"), datapoints.stream().map(d -> d.testPrefix().identifier()).toList());
        assertEquals("assertEquals(3, sum);", datapoints.get(0).target());
        assertEquals("{ Calc calc = new Calc(); int sum = calc.add(1, 2); /*<MASK_PLACEHOLDER>*/ }", prefixBody(datapoints.get(0)));
        // The qualifier of the assertion is removed from the target
        assertEquals("assertEquals(3, calc.total());", datapoints.get(1).target());
        assertEquals("{ Calc calc = new Calc(); int sum = calc.add(1, 2); assertEquals(3, sum); calc.accumulate(sum); /*<MASK_PLACEHOLDER>*/ }",
                prefixBody(datapoints.get(1)));
    }

    @Test
    void keptAssertionStaysInTheTestPrefix() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(new ConfigBuilder().assertion(AssertionStrategyType.KEEP).build(), TEST_ADD);

        assertEquals("assertEquals(3, sum);", datapoints.get(0).target());
        assertEquals("{ Calc calc = new Calc(); int sum = calc.add(1, 2); assertEquals(3, sum); }", prefixBody(datapoints.get(0)));
    }

    @Test
    void removedAssertionLeavesNoPlaceholderInTheTestPrefix() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(new ConfigBuilder().assertion(AssertionStrategyType.REMOVE).build(), TEST_ADD);

        assertEquals("assertEquals(3, sum);", datapoints.get(0).target());
        assertEquals("{ Calc calc = new Calc(); int sum = calc.add(1, 2); }", prefixBody(datapoints.get(0)));
    }

    @Test
    void assertionWithinALoopIsMaskedWithinTheLoop() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, """
                @Test
                public void testLoop() {
                    Calc calc = new Calc();
                    for (int i = 0; i < 2; i++) {
                        calc.accumulate(i);
                        assertTrue(calc.total() >= 0);
                    }
                }
                """);

        assertEquals(1, datapoints.size());
        assertEquals("assertTrue(calc.total() >= 0);", datapoints.get(0).target());
        assertEquals("{ Calc calc = new Calc(); for (int i = 0; i < 2; i++) { calc.accumulate(i); /*<MASK_PLACEHOLDER>*/ } }", prefixBody(datapoints.get(0)));
    }

    @Test
    void statementsThatAreNotAssertionsGetTheNoAssertionTargetWithStatementSplit() throws IOException {
        OraclesDatasetConfig config = new ConfigBuilder().split(SplitStrategyType.STATEMENT).build();

        List<OracleDatapoint> datapoints = datapoints(config, """
                @Test
                public void testAccumulate() {
                    Calc calc = new Calc();
                    calc.accumulate(1);
                    assertEquals(1, calc.total());
                }
                """);

        assertEquals(List.of("<NO_ASSERTION>", "<NO_ASSERTION>", "assertEquals(1, calc.total());"),
                datapoints.stream().map(OracleDatapoint::target).toList());
    }

    @Test
    void eachStatementIsATargetWithStatementSplitAndStatementTarget() throws IOException {
        OraclesDatasetConfig config = new ConfigBuilder().split(SplitStrategyType.STATEMENT).target(TargetStrategyType.STATEMENT).build();

        List<OracleDatapoint> datapoints = datapoints(config, """
                @Test
                public void testAccumulate() {
                    Calc calc = new Calc();
                    calc.accumulate(1);
                    assertEquals(1, calc.total());
                }
                """);

        assertEquals(List.of("Calc calc = new Calc();", "calc.accumulate(1);", "assertEquals(1, calc.total());"),
                datapoints.stream().map(OracleDatapoint::target).toList());
    }

    /**
     * A compound statement without statements within it is one split point: its target is the statement, not its
     * empty body.
     */
    @Test
    void emptyLoopIsATargetWithStatementSplitAndStatementTarget() throws IOException {
        OraclesDatasetConfig config = new ConfigBuilder().split(SplitStrategyType.STATEMENT).target(TargetStrategyType.STATEMENT).build();

        List<OracleDatapoint> datapoints = datapoints(config, """
                @Test
                public void testLoop() {
                    Calc calc = new Calc();
                    for (int i = 0; i < calc.total(); i++) {
                    }
                }
                """);

        assertEquals(List.of("Calc calc = new Calc();", "for (int i = 0; i < calc.total(); i++) {\n}"),
                datapoints.stream().map(OracleDatapoint::target).toList());
    }

    @Test
    void testPrefixListsTheMethodsOfTheFocalClassInvokedBeforeTheTarget() throws IOException {
        TestClazzDatapoints<OracleDatapoint> testClassDatapoints = testClassDatapoints(FixtureRepo.CONFIG, TEST_ADD);

        assertEquals("Calc", testClassDatapoints.focalClass().identifier());
        assertEquals("CalcTestSTARSplitTest", testClassDatapoints.testClass().identifier());
        List<OracleDatapoint> datapoints = testClassDatapoints.datapoints();
        assertEquals(List.of("Calc", "add"), invokedMethodNames(datapoints.get(0)));
        assertEquals(List.of("Calc", "add", "accumulate"), invokedMethodNames(datapoints.get(1)));
    }

    @Test
    void testPrefixListsTheMethodsOfGenericProjectClassesThatUseTheirTypeParameters() throws IOException {
        repo.writeSourceClass("com/acme/Box.java", """
                package com.acme;

                public class Box<T> {
                    private T content;

                    public void put(T content) {
                        this.content = content;
                    }

                    public T get() {
                        return content;
                    }
                }
                """);

        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, """
                @Test
                public void testBox() {
                    Calc calc = new Calc();
                    Box<Calc> box = new Box<>();
                    box.put(calc);
                    assertEquals(3, box.get().add(1, 2));
                }
                """);

        assertEquals(List.of("Calc", "Box", "put"), invokedMethodNames(datapoints.get(0)));
        assertEquals("T", datapoints.get(0).testPrefix().invokedMethods().get(2).parameters().get(0).type().identifier());
    }

    @Test
    void assertAllIsTheTargetAsAWhole() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, """
                @Test
                public void testAll() {
                    Calc calc = new Calc();
                    assertAll(() -> assertEquals(3, calc.add(1, 2)), () -> assertEquals(0, calc.total()));
                }
                """);

        assertEquals(1, datapoints.size());
        assertEquals("assertAll(() -> assertEquals(3, calc.add(1, 2)), () -> assertEquals(0, calc.total()));", datapoints.get(0).target());
        assertEquals("{ Calc calc = new Calc(); /*<MASK_PLACEHOLDER>*/ }", prefixBody(datapoints.get(0)));
    }

    @Test
    void assertThrowsIsTheTargetWithTheCommittedConfiguration() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.committedConfig(), """
                @Test
                public void testThrows() {
                    Calc calc = new Calc();
                    assertThrows(NumberFormatException.class, () -> Integer.parseInt("x"));
                    assertEquals(0, calc.total());
                }
                """);

        assertEquals(List.of("assertThrows(NumberFormatException.class, () -> Integer.parseInt(\"x\"));", "assertEquals(0, calc.total());"),
                datapoints.stream().map(OracleDatapoint::target).toList());
        assertEquals("{ Calc calc = new Calc(); /*<MASK_PLACEHOLDER>*/ }", prefixBody(datapoints.get(0)));
        assertEquals("{ Calc calc = new Calc(); assertThrows(NumberFormatException.class, () -> Integer.parseInt(\"x\")); /*<MASK_PLACEHOLDER>*/ }",
                prefixBody(datapoints.get(1)));
    }

    @Test
    void assertThrowsGeneratedFromATryStatementIsTheTargetWithTheCommittedConfiguration() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.committedConfig(), """
                @Test
                public void testThrows() {
                    Calc calc = new Calc();
                    try {
                        Integer.parseInt("x");
                        fail("should throw");
                    } catch (NumberFormatException e) {
                        assertNotNull(e.getMessage());
                    }
                }
                """);

        assertEquals(List.of(
                "NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(\"x\"), \"should throw\");",
                "assertNotNull(e.getMessage());"
        ), datapoints.stream().map(OracleDatapoint::target).toList());
    }

    @Test
    void failIsTheTargetAsItIsWritten() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.committedConfig(), """
                @Test
                public void testPositive() {
                    Calc calc = new Calc();
                    if (calc.add(1, 1) < 0) {
                        Assertions.fail("negative sum");
                    }
                    assertEquals(2, calc.add(1, 1));
                }
                """);

        assertEquals(List.of("fail(\"negative sum\");", "assertEquals(2, calc.add(1, 1));"),
                datapoints.stream().map(OracleDatapoint::target).toList());
        assertEquals("{ Calc calc = new Calc(); if (calc.add(1, 1) < 0) { /*<MASK_PLACEHOLDER>*/ } }", prefixBody(datapoints.get(0)));
    }

    @Test
    void failAndAssertionsWithinATryStatementAreTargetsWithKeepTryCatch() throws IOException {
        OraclesDatasetConfig keepTryCatch = new ConfigBuilder().tryCatchFinally(TryCatchFinallyStrategyType.KEEP_TRY_CATCH).build();
        List<OracleDatapoint> datapoints = datapoints(keepTryCatch, """
                @Test
                public void testThrows() {
                    try {
                        Integer.parseInt("x");
                        fail("should throw");
                    } catch (NumberFormatException e) {
                        assertNotNull(e.getMessage());
                    }
                }
                """);

        assertEquals(List.of("fail(\"should throw\");", "assertNotNull(e.getMessage());"),
                datapoints.stream().map(OracleDatapoint::target).toList());
    }

    /**
     * No input is known to make the processing of a split test case fail: the failure is injected, on the first split
     * test case, in the search of its target.
     */
    @Test
    void failureOfASplitTestCaseDoesNotDropTheOtherSplitTestCasesOfTheClass() throws IOException {
        List<OracleDatapoint> datapoints;
        try (MockedStatic<JavaParserUtils> javaParserUtils = Mockito.mockStatic(JavaParserUtils.class, Mockito.CALLS_REAL_METHODS)) {
            javaParserUtils.when(() -> JavaParserUtils.getLastAssertionInMethodDeclaration(Mockito.argThat(method -> method != null && method.getNameAsString().equals("testAdd_split_0"))))
                    .thenThrow(new IllegalStateException("injected failure"));
            datapoints = datapoints(FixtureRepo.CONFIG, TEST_ADD);
        }

        assertEquals(List.of("testAdd_split_1"), datapoints.stream().map(d -> d.testPrefix().identifier()).toList());
    }

    /**
     * A test case overriding a test case of the superclass without its own test annotation is recognized as a test case
     * by {@code @Override} (and its name): its split test cases keep the annotation.
     */
    @Test
    void overridingTestCaseWithoutTestAnnotationProducesDatapoints() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, """
                @Override
                public void testOverride() {
                    Calc calc = new Calc();
                    assertEquals(3, calc.add(1, 2));
                }
                """);

        assertEquals(List.of("testOverride_split_0"), datapoints.stream().map(d -> d.testPrefix().identifier()).toList());
    }

    @Test
    void testCaseDisabledWithAFullyQualifiedAnnotationProducesNoDatapoints() throws IOException {
        List<OracleDatapoint> datapoints = datapoints(FixtureRepo.CONFIG, TEST_ADD + """
                @Test
                @org.junit.jupiter.api.Disabled
                public void testDisabled() {
                    assertEquals(1, 1);
                }
                """);

        assertEquals(List.of("testAdd_split_0", "testAdd_split_1"), datapoints.stream().map(d -> d.testPrefix().identifier()).toList());
    }

    private List<OracleDatapoint> datapoints(OraclesDatasetConfig config, String testMembers) throws IOException {
        return testClassDatapoints(config, testMembers).datapoints();
    }

    /**
     * Writes the focal class {@code com.acme.Calc} and a JUnit 5 test class {@code com.acme.CalcTest} whose members are
     * the given source, then normalizes, splits and processes the test class as the dataset generation does.
     */
    private TestClazzDatapoints<OracleDatapoint> testClassDatapoints(OraclesDatasetConfig config, String testMembers) throws IOException {
        Path focalFile = repo.writeSourceClass("com/acme/Calc.java", FOCAL_CLASS);
        Path testFile = repo.writeTestClass("com/acme/CalcTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Assertions;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest {
                %s
                }
                """.formatted(testMembers));
        Path normalizedFile = TestUtils.normalizeTest(config, repo.root, testFile, repo.output, Optional.empty()).getValue0();
        repo.refreshParser();
        Pair<Path, Path> splitFiles = TestUtils.splitTest(config, repo.root, normalizedFile, repo.output);
        repo.refreshParser();
        return TestUtils.processSplitTestClass(config, splitFiles.getValue0(), splitFiles.getValue1(), focalFile, repo.root).getValue0();
    }

    /** Returns the body of the test prefix of a datapoint, with the whitespace collapsed. */
    private static String prefixBody(OracleDatapoint datapoint) {
        String source = datapoint.testPrefix().body();
        return source.substring(source.indexOf('{')).replaceAll("\\s+", " ").trim();
    }

    private static List<String> invokedMethodNames(OracleDatapoint datapoint) {
        return datapoint.testPrefix().invokedMethods().stream().map(Callable::identifier).toList();
    }
}
