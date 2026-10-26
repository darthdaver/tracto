package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.javatuples.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import star.tracto.data.enums.NumAssertionsMatchStrategyType;
import star.tracto.data.enums.SplitStrategyType;
import star.tracto.data.enums.TryCatchFinallyStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the splitting of the normalized test cases into split test cases ({@code TestUtils.splitTest}). Each split
 * test case ends at a split point (an assertion, or any statement), and the corresponding split test prefix is the same
 * code without the split point.
 */
class TestUtilsSplitTest {

    @TempDir
    Path tempDir;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FixtureRepo(tempDir);
    }

    @Test
    void assertionsWithinLabeledBlockAreSplitPoints() throws IOException {
        Path normalizedFile = repo.writeTestClass("com/acme/LabeledTestSTARNormalizedTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class LabeledTestSTARNormalizedTest {
                    @Test
                    public void testLabeled() {
                        int x = 1;
                        check: {
                            if (x < 0) {
                                break check;
                            }
                            assertTrue(x > 0);
                            assertEquals(1, x);
                        }
                        assertNotNull("done");
                    }
                }
                """);

        Pair<Path, Path> result = assertDoesNotThrow(
                () -> TestUtils.splitTest(FixtureRepo.CONFIG, repo.root, normalizedFile, repo.output));

        assertEquals(Map.of(
                "testLabeled_split_0", StaticJavaParser.parseBlock("""
                        {
                            int x = 1;
                            check: {
                                if (x < 0) {
                                    break check;
                                }
                                assertTrue(x > 0);
                            }
                        }
                        """),
                "testLabeled_split_1", StaticJavaParser.parseBlock("""
                        {
                            int x = 1;
                            check: {
                                if (x < 0) {
                                    break check;
                                }
                                assertTrue(x > 0);
                                assertEquals(1, x);
                            }
                        }
                        """),
                "testLabeled_split_2", StaticJavaParser.parseBlock("""
                        {
                            int x = 1;
                            check: {
                                if (x < 0) {
                                    break check;
                                }
                                assertTrue(x > 0);
                                assertEquals(1, x);
                            }
                            assertNotNull("done");
                        }
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    void eachAssertionEndsASplitTestCaseAndTheStatementsAfterTheLastAssertionAreDropped() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testFlat() {
                    int x = 1;
                    assertEquals(1, x);
                    x++;
                    assertEquals(2, x);
                    x++;
                }
                """);

        assertEquals(Map.of(
                "testFlat_split_0", block("int x = 1; assertEquals(1, x);"),
                "testFlat_split_1", block("int x = 1; assertEquals(1, x); x++; assertEquals(2, x);")
        ), methodBodies(result.getValue0()));
    }

    @Test
    void splitTestPrefixIsTheSplitTestCaseWithoutItsSplitPoint() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testFlat() {
                    int x = 1;
                    assertEquals(1, x);
                    x++;
                    assertEquals(2, x);
                }
                """);

        assertEquals(Map.of(
                "testFlat_split_0", block("int x = 1;"),
                "testFlat_split_1", block("int x = 1; assertEquals(1, x); x++;")
        ), methodBodies(result.getValue1()));
    }

    @Test
    void statementsAfterTheLastAssertionFormOneMoreSplitTestCaseWhenKept() throws IOException {
        Pair<Path, Path> result = split(new ConfigBuilder().keepStatementsAfterLastAssertion(true).build(), """
                @Test
                public void testFlat() {
                    int x = 1;
                    assertEquals(1, x);
                    x++;
                }
                """);

        assertEquals(Map.of(
                "testFlat_split_0", block("int x = 1; assertEquals(1, x);"),
                "testFlat_split_1", block("int x = 1; assertEquals(1, x); x++;")
        ), methodBodies(result.getValue0()));
    }

    @Test
    void eachStatementEndsASplitTestCaseWithStatementSplitStrategy() throws IOException {
        Pair<Path, Path> result = split(new ConfigBuilder().split(SplitStrategyType.STATEMENT).build(), """
                @Test
                public void testFlat() {
                    int x = 1;
                    x++;
                    assertEquals(2, x);
                }
                """);

        assertEquals(Map.of(
                "testFlat_split_0", block("int x = 1;"),
                "testFlat_split_1", block("int x = 1; x++;"),
                "testFlat_split_2", block("int x = 1; x++; assertEquals(2, x);")
        ), methodBodies(result.getValue0()));
    }

    /**
     * An assertion within the body of a loop is a split point: the split test case keeps the loop, with the body up to
     * the assertion.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "for (int i = 0; i < 2; i++) {        | }",
            "for (int v : new int[] {1, 2}) {     | }",
            "while (s < 2) {                      | }",
            "do {                                 | } while (s < 2);"
    })
    void assertionWithinTheBodyOfACompoundStatementIsASplitPoint(String header, String footer) throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testCompound() {
                    int s = 0;
                    %s
                        s++;
                        assertTrue(s > 0);
                    %s
                    assertNotNull("done");
                }
                """.formatted(header, footer));

        assertEquals(Map.of(
                "testCompound_split_0", block("int s = 0; %s s++; assertTrue(s > 0); %s".formatted(header, footer)),
                "testCompound_split_1", block("int s = 0; %s s++; assertTrue(s > 0); %s assertNotNull(\"done\");".formatted(header, footer))
        ), methodBodies(result.getValue0()));
    }

    @Test
    void assertionsWithinTheBranchesOfAnIfChainAreSplitPoints() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testIfChain() {
                    int x = 1;
                    if (x > 0) {
                        assertTrue(x > 0);
                    } else if (x < 0) {
                        assertTrue(x < 0);
                    } else {
                        assertEquals(0, x);
                    }
                    assertNotNull("done");
                }
                """);

        assertEquals(Map.of(
                "testIfChain_split_0", block("""
                        int x = 1;
                        if (x > 0) { assertTrue(x > 0); }
                        """),
                "testIfChain_split_1", block("""
                        int x = 1;
                        if (x > 0) { assertTrue(x > 0); } else if (x < 0) { assertTrue(x < 0); }
                        """),
                "testIfChain_split_2", block("""
                        int x = 1;
                        if (x > 0) { assertTrue(x > 0); } else if (x < 0) { assertTrue(x < 0); } else { assertEquals(0, x); }
                        """),
                "testIfChain_split_3", block("""
                        int x = 1;
                        if (x > 0) { assertTrue(x > 0); } else if (x < 0) { assertTrue(x < 0); } else { assertEquals(0, x); }
                        assertNotNull("done");
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    void assertionWithinTheBlockOfALambdaArgumentIsASplitPoint() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testLambda() {
                    int[] values = {1, 2};
                    java.util.Arrays.stream(values).forEach(v -> {
                        assertTrue(v > 0);
                    });
                    assertNotNull("done");
                }
                """);

        assertEquals(Map.of(
                "testLambda_split_0", block("""
                        int[] values = {1, 2};
                        java.util.Arrays.stream(values).forEach(v -> { assertTrue(v > 0); });
                        """),
                "testLambda_split_1", block("""
                        int[] values = {1, 2};
                        java.util.Arrays.stream(values).forEach(v -> { assertTrue(v > 0); });
                        assertNotNull("done");
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    void assertThrowsIsASplitPointWithTheStandardStrategy() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testThrows() {
                    String s = "x";
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));
                    assertNotNull(e.getMessage());
                }
                """);

        assertEquals(Map.of(
                "testThrows_split_0", block("""
                        String s = "x";
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));
                        """),
                "testThrows_split_1", block("""
                        String s = "x";
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));
                        assertNotNull(e.getMessage());
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    void disabledTestCaseIsKeptUnchanged() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                @Disabled
                public void testDisabled() {
                    assertEquals(1, 1);
                    assertEquals(2, 2);
                }
                """);

        assertEquals(Map.of("testDisabled", block("assertEquals(1, 1); assertEquals(2, 2);")), methodBodies(result.getValue0()));
    }

    /**
     * An AssertJ chain contains an assertion ({@code assertThat}), but it is not a split point, so the number of split
     * test cases differs from the number of assertions.
     */
    @ParameterizedTest
    @CsvSource({"STRICT, 0", "LOOSE, 1"})
    void mismatchBetweenAssertionsAndSplitTestCasesDropsTheTestCaseOnlyWithStrictMatch(NumAssertionsMatchStrategyType numAssertionsMatch, int expectedSplitTestCases) throws IOException {
        Pair<Path, Path> result = split(new ConfigBuilder().numAssertionsMatch(numAssertionsMatch).build(), """
                @Test
                public void testChain() {
                    int x = 1;
                    assertThat(x).isEqualTo(1);
                    assertEquals(1, x);
                }

                private static Chain assertThat(int actual) {
                    return new Chain();
                }

                private static class Chain {
                    void isEqualTo(int expected) {}
                }
                """);

        assertEquals(expectedSplitTestCases, methodBodies(result.getValue0()).keySet().stream().filter(name -> name.startsWith("testChain_split_")).count());
    }

    @Test
    @Disabled("D1: the statement after the inner loop is added to the inner loop")
    void statementAfterAnInnerLoopStaysInTheOuterLoop() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testNested() {
                    int s = 0;
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            s += j;
                        }
                        assertTrue(s >= 0);
                    }
                    assertEquals(2, s);
                }
                """);

        assertEquals(Map.of(
                "testNested_split_0", block("""
                        int s = 0;
                        for (int i = 0; i < 2; i++) { for (int j = 0; j < 2; j++) { s += j; } assertTrue(s >= 0); }
                        """),
                "testNested_split_1", block("""
                        int s = 0;
                        for (int i = 0; i < 2; i++) { for (int j = 0; j < 2; j++) { s += j; } assertTrue(s >= 0); }
                        assertEquals(2, s);
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    @Disabled("D24: the body of a synchronized statement is added after the statement instead of within it")
    void assertionWithinASynchronizedStatementIsASplitPoint() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testSynchronized() {
                    int s = 0;
                    synchronized (this) {
                        s++;
                        assertTrue(s > 0);
                    }
                    assertNotNull("done");
                }
                """);

        assertEquals(Map.of(
                "testSynchronized_split_0", block("int s = 0; synchronized (this) { s++; assertTrue(s > 0); }"),
                "testSynchronized_split_1", block("int s = 0; synchronized (this) { s++; assertTrue(s > 0); } assertNotNull(\"done\");")
        ), methodBodies(result.getValue0()));
    }

    @Test
    @Disabled("D25: annotations written with their fully qualified name are not recognized")
    void disabledTestCaseWithFullyQualifiedAnnotationIsKeptUnchanged() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                @org.junit.jupiter.api.Disabled
                public void testDisabled() {
                    assertEquals(1, 1);
                    assertEquals(2, 2);
                }
                """);

        assertEquals(Map.of("testDisabled", block("assertEquals(1, 1); assertEquals(2, 2);")), methodBodies(result.getValue0()));
    }

    @Test
    @Disabled("D2: the else branch is attached to the if nested in the then branch")
    void elseBranchStaysOnItsIfWhenTheThenBranchContainsAnotherIf() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testIfElse() {
                    int x = 1;
                    if (x > 0) {
                        if (x > 5) {
                            assertTrue(x > 5);
                        }
                    } else {
                        assertTrue(x <= 0);
                    }
                }
                """);

        assertEquals(Map.of(
                "testIfElse_split_0", block("int x = 1; if (x > 0) { if (x > 5) { assertTrue(x > 5); } }"),
                "testIfElse_split_1", block("int x = 1; if (x > 0) { if (x > 5) { assertTrue(x > 5); } } else { assertTrue(x <= 0); }")
        ), methodBodies(result.getValue0()));
    }

    @Test
    @Disabled("D9: with the committed configuration the assertThrows declaration generated by the normalization crashes the splitter")
    void generatedAssertThrowsDeclarationIsASplitPointWithTheCommittedConfiguration() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.committedConfig(), """
                @Test
                public void testThrows() {
                    String s = "x";
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(s), "should throw");
                    assertNotNull(e.getMessage());
                }
                """);

        assertEquals(List.of("testThrows_split_0", "testThrows_split_1"), List.copyOf(methodBodies(result.getValue0()).keySet()));
    }

    @Test
    @Disabled("D26: a statement containing an anonymous class with methods makes the splitter drop the test case")
    void statementContainingAnAnonymousClassIsKeptAsItIs() throws IOException {
        Pair<Path, Path> result = split(FixtureRepo.CONFIG, """
                @Test
                public void testAnonymous() {
                    Runnable task = new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("run");
                        }
                    };
                    task.run();
                    assertNotNull(task);
                }
                """);

        assertEquals(Map.of(
                "testAnonymous_split_0", block("""
                        Runnable task = new Runnable() {
                            @Override
                            public void run() {
                                System.out.println("run");
                            }
                        };
                        task.run();
                        assertNotNull(task);
                        """)
        ), methodBodies(result.getValue0()));
    }

    @Test
    @Disabled("Step 10: splitting within try statements, kept by the keep-try-catch strategy")
    void failAndAssertionsWithinATryStatementAreSplitPointsWithKeepTryCatch() throws IOException {
        Pair<Path, Path> result = split(new ConfigBuilder().tryCatchFinally(TryCatchFinallyStrategyType.KEEP_TRY_CATCH).build(), """
                @Test
                public void testKeep() {
                    String s = "x";
                    try {
                        Integer.parseInt(s);
                        fail("should throw");
                    } catch (NumberFormatException e) {
                        assertNotNull(e);
                    }
                }
                """);

        assertEquals(Map.of(
                "testKeep_split_0", block("""
                        String s = "x";
                        try { Integer.parseInt(s); fail("should throw"); } catch (NumberFormatException e) { }
                        """),
                "testKeep_split_1", block("""
                        String s = "x";
                        try { Integer.parseInt(s); fail("should throw"); } catch (NumberFormatException e) { assertNotNull(e); }
                        """)
        ), methodBodies(result.getValue0()));
    }

    /**
     * Splits a normalized JUnit 5 test class {@code com.acme.ShapesTestSTARNormalizedTest} whose members are the given
     * source, and returns the paths to the split test class and to the split test prefix class.
     */
    private Pair<Path, Path> split(OraclesDatasetConfig config, String members) throws IOException {
        Path normalizedFile = repo.writeTestClass("com/acme/ShapesTestSTARNormalizedTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ShapesTestSTARNormalizedTest {
                %s
                }
                """.formatted(members));
        return assertDoesNotThrow(() -> TestUtils.splitTest(config, repo.root, normalizedFile, repo.output));
    }

    private static BlockStmt block(String statements) {
        return StaticJavaParser.parseBlock("{" + statements + "}");
    }

    /** Returns the bodies of the methods of the test class in the given file (auxiliary methods excluded). */
    private static Map<String, BlockStmt> methodBodies(Path javaFile) throws IOException {
        assertTrue(Files.exists(javaFile), "Expected split file " + javaFile);
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        Map<String, BlockStmt> bodies = new LinkedHashMap<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (method.getAnnotationByName("Test").isPresent()) {
                bodies.put(method.getNameAsString(), method.getBody().orElseThrow());
            }
        }
        return bodies;
    }
}
