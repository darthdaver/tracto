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
