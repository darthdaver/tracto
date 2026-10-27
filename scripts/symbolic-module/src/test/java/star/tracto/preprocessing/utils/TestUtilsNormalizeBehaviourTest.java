package star.tracto.preprocessing.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the normalization preserves the behaviour of the test cases: the original and the normalized test
 * classes are compiled and executed, and the statements they execute (recorded in a trace) are compared.
 */
class TestUtilsNormalizeBehaviourTest {

    @TempDir
    Path tempDir;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FixtureRepo(tempDir);
    }

    @Test
    void earlyReturnOfInlinedAuxiliaryMethodSkipsTheRestOfTheAuxiliaryMethod() throws Exception {
        Path testFile = repo.writeTestClass("com/acme/TraceTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;

                public class TraceTest {
                    public static final List<String> TRACE = new ArrayList<>();

                    @Test
                    public void testScan() {
                        TRACE.add("test:start");
                        scan(new int[] {1, 2, 3, 4}, 3);
                        TRACE.add("test:after-first-call");
                        scan(new int[] {5, 6}, 9);
                        TRACE.add("test:end");
                    }

                    private void scan(int[] values, int stop) {
                        TRACE.add("helper:start");
                        for (int v : values) {
                            for (int w = 0; w < 2; w++) {
                                if (v == stop) {
                                    TRACE.add("helper:early-return");
                                    return;
                                }
                                TRACE.add("helper:visit " + v + "/" + w);
                            }
                            TRACE.add("helper:after-inner-loop " + v);
                        }
                        TRACE.add("helper:end");
                    }
                }
                """);
        List<String> expectedTrace = List.of(
                "test:start",
                "helper:start",
                "helper:visit 1/0", "helper:visit 1/1", "helper:after-inner-loop 1",
                "helper:visit 2/0", "helper:visit 2/1", "helper:after-inner-loop 2",
                "helper:early-return",
                "test:after-first-call",
                "helper:start",
                "helper:visit 5/0", "helper:visit 5/1", "helper:after-inner-loop 5",
                "helper:visit 6/0", "helper:visit 6/1", "helper:after-inner-loop 6",
                "helper:end",
                "test:end"
        );

        Path normalizedFile = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(FixtureRepo.CONFIG, repo.root, testFile, repo.output, Optional.empty())).getValue0();

        assertEquals(expectedTrace, runTrace(testFile, "com.acme.TraceTest", "testScan"), "Original test case");
        assertEquals(expectedTrace, runTrace(normalizedFile, "com.acme.TraceTestSTARNormalizedTest", "testScan"), "Normalized test case");
        // Without inlining the traces would trivially be equal: check the helper was actually inlined
        assertTrue(Files.readString(normalizedFile).contains("break scan;"), "Expected the helper to be inlined as a labeled block");
    }

    @Test
    void inlinedAuxiliaryMethodsSeeTheValuesOfTheOriginalCalls() throws Exception {
        Path testFile = repo.writeTestClass("com/acme/InlineTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;

                public class InlineTest {
                    public static final List<String> TRACE = new ArrayList<>();
                    private int base = 10;

                    @Test
                    public void testInline() {
                        int base = 1;
                        int x = 2;
                        bump(x);
                        TRACE.add("test:x=" + x);
                        readBase();
                        TRACE.add("test:base=" + base);
                        sum(x);
                        sum(x, base);
                    }

                    private void bump(int v) {
                        v = v + 1;
                        TRACE.add("bump:v=" + v);
                    }

                    private void readBase() {
                        TRACE.add("readBase:base=" + base);
                    }

                    private void sum(int... values) {
                        int total = 0;
                        for (int value : values) {
                            total += value;
                        }
                        TRACE.add("sum:" + values.length + ":" + total);
                    }
                }
                """);
        List<String> expectedTrace = List.of(
                "bump:v=3", "test:x=2",
                "readBase:base=10", "test:base=1",
                "sum:1:2", "sum:2:3"
        );

        Path normalizedFile = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(FixtureRepo.CONFIG, repo.root, testFile, repo.output, Optional.empty())).getValue0();

        assertEquals(expectedTrace, runTrace(testFile, "com.acme.InlineTest", "testInline"), "Original test case");
        assertEquals(expectedTrace, runTrace(normalizedFile, "com.acme.InlineTestSTARNormalizedTest", "testInline"), "Normalized test case");
        // Without inlining the traces would trivially be equal: check the auxiliary methods were actually inlined
        assertFalse(Files.readString(normalizedFile).contains("bump(x);"), "Expected the auxiliary methods to be inlined");
    }

    @Test
    void elseBranchesOfNestedIfStatementsRunAsInTheOriginalTestCase() throws Exception {
        Path testFile = repo.writeTestClass("com/acme/BranchesTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;

                public class BranchesTest {
                    public static final List<String> TRACE = new ArrayList<>();

                    @Test
                    public void testBranches() {
                        for (int x : new int[] {-1, 1, 7}) {
                            if (x > 0) {
                                if (x > 5) {
                                    TRACE.add("a:big " + x);
                                }
                            } else {
                                TRACE.add("a:non-positive " + x);
                            }
                            if (x > 0) {
                                if (x > 5) {
                                    TRACE.add("b:big " + x);
                                } else {
                                    TRACE.add("b:small " + x);
                                }
                            } else {
                                TRACE.add("b:non-positive " + x);
                            }
                        }
                    }
                }
                """);
        List<String> expectedTrace = List.of(
                "a:non-positive -1", "b:non-positive -1",
                "b:small 1",
                "a:big 7", "b:big 7"
        );

        Path normalizedFile = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(FixtureRepo.CONFIG, repo.root, testFile, repo.output, Optional.empty())).getValue0();

        assertEquals(expectedTrace, runTrace(testFile, "com.acme.BranchesTest", "testBranches"), "Original test case");
        assertEquals(expectedTrace, runTrace(normalizedFile, "com.acme.BranchesTestSTARNormalizedTest", "testBranches"), "Normalized test case");
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
