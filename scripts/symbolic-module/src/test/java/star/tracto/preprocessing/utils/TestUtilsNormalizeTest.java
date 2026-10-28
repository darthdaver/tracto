package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.javatuples.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import star.tracto.data.enums.TryCatchFinallyStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.preprocessing.components.TestStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TestUtilsNormalizeTest {

    private static final OraclesDatasetConfig CONFIG = FixtureRepo.CONFIG;
    private static final OraclesDatasetConfig KEEP_TRY_CATCH = new ConfigBuilder().tryCatchFinally(TryCatchFinallyStrategyType.KEEP_TRY_CATCH).build();

    @TempDir
    Path tempDir;

    private FixtureRepo repo;
    private Path repoRoot;
    private Path outputPath;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FixtureRepo(tempDir);
        repoRoot = repo.root;
        outputPath = repo.output;
    }

    @Test
    void normalizeTestRenamesLoopVariableReusedInSiblingLoop() throws IOException {
        Path testFile = writeTestClass("com/acme/LoopsTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class LoopsTest {
                    @Test
                    public void testTwoLoops() {
                        int sum = 0;
                        for (int i = 0; i < 2; i++) {
                            sum += i;
                        }
                        for (int i = 0; i < 3; i++) {
                            sum += i;
                        }
                        assertEquals(4, sum);
                    }
                }
                """);

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        MethodDeclaration normalized = findMethod(result.getValue0(), "testTwoLoops");
        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int sum = 0;
                    for (int i = 0; i < 2; i++) {
                        sum += i;
                    }
                    for (int i2 = 0; i2 < 3; i2++) {
                        sum += i2;
                    }
                    assertEquals(4, sum);
                }
                """), normalized.getBody().orElseThrow());
    }

    @Test
    void normalizationErrorInOneTestCaseKeepsTheRestOfTheTestClass() throws IOException {
        Path testFile = writeTestClass("com/acme/ErrorsTest.java", """
                package com.acme;

                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ErrorsTest {
                    private int base;

                    @BeforeEach
                    public void setUp() {
                        base = 1;
                    }

                    @Test
                    public void testSwitch() {
                        int v = base;
                        switch (v) {
                            case 1:
                                assertEquals(1, v);
                                break;
                            default:
                                fail("unexpected");
                        }
                    }

                    @Test
                    public void testTwoLoops() {
                        int sum = base;
                        for (int i = 0; i < 2; i++) {
                            sum += i;
                        }
                        for (int i = 0; i < 3; i++) {
                            sum += i;
                        }
                        assertEquals(5, sum);
                    }
                }
                """);

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        CompilationUnit normalized = StaticJavaParser.parse(result.getValue0());
        assertEquals(List.of("base"), fieldNames(normalized));
        assertEquals(List.of("setUp", "testSwitch", "testTwoLoops"), methodNames(normalized));
        // The normalization does not support switch statements, so only that test case is reported in the error file
        Path errorFile = outputPath.resolve("error/normalized/repo/src/test/java/com/acme/ErrorsTestSTARNormalizedTest.java");
        assertTrue(Files.exists(errorFile), "Expected error file " + errorFile);
        assertEquals(List.of("testSwitch"), methodNames(StaticJavaParser.parse(errorFile)));
    }

    @Test
    void auxiliaryMethodWithParameterIsInlinedWithTheArgument() throws IOException {
        Path testFile = writeTestClass("com/acme/HelperTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class HelperTest {
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        check(x);
                    }

                    private void check(int v) {
                        assertEquals(1, v);
                    }
                }
                """);

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertEquals(1, x);
                }
                """), findMethod(result.getValue0(), "testUsesHelper").getBody().orElseThrow());
        assertEquals(StaticJavaParser.parseMethodDeclaration("""
                private void check(int v) {
                    assertEquals(1, v);
                }
                """), findMethod(result.getValue0(), "check"));
    }

    @Test
    void auxiliaryMethodLocalClashingWithTestVariableIsRenamedOnlyInTheInlinedCopy() throws IOException {
        Path testFile = writeTestClass("com/acme/ClashTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class ClashTest {
                    @Test
                    public void testLocalClash() {
                        int r = 5;
                        checkNext(1);
                        assertEquals(5, r);
                    }

                    private void checkNext(int v) {
                        int r = v + 1;
                        assertEquals(2, r);
                    }
                }
                """);

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int r = 5;
                    int v = 1;
                    int r2 = v + 1;
                    assertEquals(2, r2);
                    assertEquals(5, r);
                }
                """), findMethod(result.getValue0(), "testLocalClash").getBody().orElseThrow());
        assertEquals(StaticJavaParser.parseMethodDeclaration("""
                private void checkNext(int v) {
                    int r = v + 1;
                    assertEquals(2, r);
                }
                """), findMethod(result.getValue0(), "checkNext"));
    }

    @Test
    void auxiliaryMethodEndingWithEmptyReturnIsInlined() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        checkPositive(x);
                    }

                    private void checkPositive(int v) {
                        assertTrue(v > 0);
                        return;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
        assertEquals(StaticJavaParser.parseMethodDeclaration("""
                private void checkPositive(int v) {
                    assertTrue(v > 0);
                    return;
                }
                """), findMethod(normalized, "checkPositive"));
    }

    @Test
    void auxiliaryMethodReturningAStatementExpressionKeepsItAsStatement() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private final List<Integer> seen = new ArrayList<>();

                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        record(x);
                        assertEquals(1, seen.size());
                    }

                    private boolean record(int v) {
                        assertTrue(v > 0);
                        return seen.add(v);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                    seen.add(x);
                    assertEquals(1, seen.size());
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
        assertEquals(StaticJavaParser.parseMethodDeclaration("""
                private boolean record(int v) {
                    assertTrue(v > 0);
                    return seen.add(v);
                }
                """), findMethod(normalized, "record"));
    }

    @Test
    void auxiliaryMethodReturningAValueWithoutSideEffectsDropsIt() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        isPositive(x);
                    }

                    private boolean isPositive(int v) {
                        assertTrue(v > 0);
                        return v > 0;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodReturningAValueWithSideEffectsKeepsItInAResultVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private final List<Integer> seen = new ArrayList<>();

                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        recordPositive(x);
                    }

                    private boolean recordPositive(int v) {
                        assertTrue(v > 0);
                        return seen.add(v) && v > 0;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                    boolean recordPositiveResult = seen.add(x) && x > 0;
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodReturningAnIncrementKeepsItAsStatement() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int counter;

                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        count(x);
                        assertEquals(1, counter);
                    }

                    private int count(int v) {
                        assertTrue(v > 0);
                        return counter++;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                    counter++;
                    assertEquals(1, counter);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodWithEarlyReturnIsInlinedAsLabeledBlock() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        byte[] a1 = {1, 2};
                        byte[] a2 = {1, 3};
                        assertArrayNotEqual(a1, a2, 2);
                        assertEquals(2, a1.length);
                    }

                    private void assertArrayNotEqual(byte[] array1, byte[] array2, int length) {
                        for (int i = 0; i < length; i++) {
                            if (array1[i] != array2[i]) {
                                return;
                            }
                        }
                        fail("Array1 and Array2 matched");
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    byte[] a1 = {1, 2};
                    byte[] a2 = {1, 3};
                    int length = 2;
                    assertArrayNotEqual: {
                        for (int i = 0; i < length; i++) {
                            if (a1[i] != a2[i]) {
                                break assertArrayNotEqual;
                            }
                        }
                        fail("Array1 and Array2 matched");
                    }
                    assertEquals(2, a1.length);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
        assertEquals(StaticJavaParser.parseMethodDeclaration("""
                private void assertArrayNotEqual(byte[] array1, byte[] array2, int length) {
                    for (int i = 0; i < length; i++) {
                        if (array1[i] != array2[i]) {
                            return;
                        }
                    }
                    fail("Array1 and Array2 matched");
                }
                """), findMethod(normalized, "assertArrayNotEqual"));
    }

    @Test
    void earlyReturnWithSideEffectsEvaluatesTheValueBeforeLeavingTheLabeledBlock() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private final List<Integer> seen = new ArrayList<>();

                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        recordOnce(x);
                        assertEquals(1, seen.size());
                    }

                    private boolean recordOnce(int v) {
                        if (seen.contains(v)) {
                            return seen.remove((Integer) v);
                        }
                        assertTrue(v > 0);
                        return seen.add(v);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    recordOnce: {
                        if (seen.contains(x)) {
                            seen.remove((Integer) x);
                            break recordOnce;
                        }
                        assertTrue(x > 0);
                        seen.add(x);
                    }
                    assertEquals(1, seen.size());
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void labelOfInlinedAuxiliaryMethodDoesNotClashWithItsOwnLabels() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int[] xs = {1, 2};
                        search(xs, 1);
                    }

                    private void search(int[] values, int target) {
                        search:
                        for (int v : values) {
                            if (v == target) {
                                break search;
                            }
                            if (v < 0) {
                                return;
                            }
                        }
                        assertTrue(target > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int[] xs = {1, 2};
                    int target = 1;
                    search2: {
                        search:
                        for (int v : xs) {
                            if (v == target) {
                                break search;
                            }
                            if (v < 0) {
                                break search2;
                            }
                        }
                        assertTrue(target > 0);
                    }
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodWhoseReturnedValueIsUsedIsNotInlined() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        int doubled = doubleChecked(x);
                        assertEquals(2, doubled);
                        doubled = doubleChecked(doubled);
                        assertEquals(8, doubleChecked(x) * doubled);
                    }

                    private int doubleChecked(int v) {
                        assertTrue(v > 0);
                        return v * 2;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    int doubled = doubleChecked(x);
                    assertEquals(2, doubled);
                    doubled = doubleChecked(doubled);
                    assertEquals(8, doubleChecked(x) * doubled);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void returnWithinLambdaOfAuxiliaryMethodDoesNotPreventInlining() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        List<Integer> xs = List.of(1, 2);
                        checkAll(xs);
                    }

                    private void checkAll(List<Integer> vs) {
                        vs.forEach(v -> {
                            if (v < 0) {
                                return;
                            }
                            assertTrue(v > 0);
                        });
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    List<Integer> xs = List.of(1, 2);
                    xs.forEach(v -> {
                        if (v < 0) {
                            return;
                        }
                        assertTrue(v > 0);
                    });
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * An auxiliary method can modify a field passed as argument: the parameter keeps the value of the field at the
     * time of the call.
     */
    @Test
    void argumentThatIsAFieldIsBoundToALocalVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int counter = 0;

                    @Test
                    public void testUsesHelper() {
                        check(counter);
                        assertEquals(1, counter);
                    }

                    private void check(int v) {
                        counter++;
                        assertEquals(0, v);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int v = counter;
                    counter++;
                    assertEquals(0, v);
                    assertEquals(1, counter);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * A final field cannot be modified by the auxiliary method: it can replace the parameter, even within a lambda.
     */
    @Test
    void argumentThatIsAFinalFieldReplacesTheParameter() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private static final int LIMIT = 3;

                    @Test
                    public void testUsesHelper() {
                        List<Integer> xs = List.of(1, 2);
                        checkBelow(xs, LIMIT);
                    }

                    private void checkBelow(List<Integer> values, int max) {
                        values.forEach(v -> assertTrue(v < max));
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    List<Integer> xs = List.of(1, 2);
                    xs.forEach(v -> assertTrue(v < LIMIT));
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * A variable captured by a lambda expression must be effectively final: the variable passed by the test case may
     * not be.
     */
    @Test
    void parameterUsedWithinALambdaOfTheAuxiliaryMethodIsBoundToALocalVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        List<Integer> xs = List.of(1, 2);
                        checkAbove(xs, x);
                        x = 2;
                    }

                    private void checkAbove(List<Integer> values, int min) {
                        values.forEach(v -> assertTrue(v >= min));
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    List<Integer> xs = List.of(1, 2);
                    int min = x;
                    xs.forEach(v -> assertTrue(v >= min));
                    x = 2;
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * The arguments are evaluated before the body of the method: a variable modified by another argument is bound to
     * its value at the time of the call.
     */
    @Test
    void variableModifiedByAnotherArgumentIsBoundToALocalVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        checkPair(x, x++);
                    }

                    private void checkPair(int a, int b) {
                        assertEquals(a, b);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    int a = x;
                    int b = x++;
                    assertEquals(a, b);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * A local variable of the auxiliary method named like a field would hide the field to the statements of the test
     * case that follow the call.
     */
    @Test
    void auxiliaryMethodLocalClashingWithAFieldIsRenamed() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int count = 0;

                    @Test
                    public void testUsesHelper() {
                        check();
                        assertEquals(0, count);
                    }

                    private void check() {
                        int count = 5;
                        assertEquals(5, count);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int count2 = 5;
                    assertEquals(5, count2);
                    assertEquals(0, count);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void lambdaParameterOfTheAuxiliaryMethodClashingWithATestVariableIsRenamed() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int v = 1;
                        List<Integer> xs = List.of(v);
                        checkAll(xs);
                    }

                    private void checkAll(List<Integer> values) {
                        values.forEach(v -> assertTrue(v > 0));
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int v = 1;
                    List<Integer> xs = List.of(v);
                    xs.forEach(v2 -> assertTrue(v2 > 0));
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void variablesGeneratedByTwoCallsToTheSameAuxiliaryMethodHaveDifferentNames() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private final List<Integer> seen = new ArrayList<>();

                    @Test
                    public void testUsesHelper() {
                        recordPositive(1);
                        recordPositive(2);
                    }

                    private boolean recordPositive(int v) {
                        assertTrue(v > 0);
                        return seen.add(v) && v > 0;
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int v = 1;
                    assertTrue(v > 0);
                    boolean recordPositiveResult = seen.add(v) && v > 0;
                    int v2 = 2;
                    assertTrue(v2 > 0);
                    boolean recordPositiveResult2 = seen.add(v2) && v2 > 0;
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void statementsWithinALabeledLoopAreNormalized() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        outer:
                        for (int i = 0; i < 2; i++) {
                            try {
                                Integer.parseInt("x");
                                fail();
                            } catch (NumberFormatException e) {
                                assertNotNull(e);
                                continue outer;
                            }
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    outer:
                    for (int i = 0; i < 2; i++) {
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x"));
                        assertNotNull(e);
                        continue outer;
                    }
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void labelOfInlinedAuxiliaryMethodDoesNotClashWithALabelOfTheTestCase() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int[] xs = {1, 2};
                        check:
                        for (int x : xs) {
                            check(x);
                        }
                    }

                    private void check(int v) {
                        if (v < 0) {
                            return;
                        }
                        assertTrue(v > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int[] xs = {1, 2};
                    check:
                    for (int x : xs) {
                        check2: {
                            if (x < 0) {
                                break check2;
                            }
                            assertTrue(x > 0);
                        }
                    }
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void recursiveCallOfAnInlinedAuxiliaryMethodIsKept() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        countDown(2);
                    }

                    private void countDown(int n) {
                        assertTrue(n >= 0);
                        if (n > 0) {
                            countDown(n - 1);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int n = 2;
                    assertTrue(n >= 0);
                    if (n > 0) {
                        countDown(n - 1);
                    }
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * The test case is discarded only for the try statements it contains: an auxiliary method containing a try
     * statement the dataset does not support is not inlined.
     */
    @Test
    void auxiliaryMethodWithAnUnsupportedTryStatementIsNotInlined() throws IOException {
        Path testFile = writeTestClass("com/acme/HelperTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class HelperTest {
                    @Test
                    public void testUsesHelper() {
                        check();
                        assertTrue(true);
                    }

                    private void check() {
                        try {
                            Integer.parseInt("1");
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                """);

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    check();
                    assertTrue(true);
                }
                """), findMethod(result.getValue0(), "testUsesHelper").getBody().orElseThrow());
        assertEquals(Map.of(), discardReasons(result.getValue1()));
        Path errorFile = outputPath.resolve("error/normalized/repo/src/test/java/com/acme/HelperTestSTARNormalizedTest.java");
        assertFalse(Files.exists(errorFile), "Unexpected error file " + errorFile);
    }

    @Test
    void severalArgumentsOfAVarargsParameterAreCollectedInAnArray() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        checkAll(1, 2);
                    }

                    private void checkAll(int... values) {
                        assertTrue(values.length > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int[] values = new int[] {1, 2};
                    assertTrue(values.length > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void arrayPassedToAVarargsParameterIsPassedAsItIs() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int[] xs = {1, 2};
                        checkAll(xs);
                    }

                    private void checkAll(int... values) {
                        assertTrue(values.length > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int[] xs = {1, 2};
                    assertTrue(xs.length > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    /**
     * The declaration of the array is already moved to the normalized test case, out of the compilation unit, when the
     * call is inlined: the type of a reference array cannot be resolved there.
     */
    @Test
    void referenceArrayPassedToAVarargsParameterIsPassedAsItIs() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        String[] names = {"a", "b"};
                        checkAll(names);
                    }

                    private void checkAll(String... values) {
                        assertTrue(values.length > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    String[] names = {"a", "b"};
                    assertTrue(names.length > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    static Stream<Arguments> tryCatchFailVariants() {
        String junit4Imports = """
                import org.junit.Test;
                import static org.junit.Assert.*;
                """;
        String junit5Imports = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                """;
        return Stream.of(
                Arguments.of("JUnit 5 with message", junit5Imports, "fail(\"should throw\");",
                        "assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x), \"should throw\")"),
                Arguments.of("JUnit 5 without message", junit5Imports, "fail();",
                        "assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x))"),
                Arguments.of("JUnit 4 with message", junit4Imports, "fail(\"should throw\");",
                        "assertThrows(\"should throw\", IllegalArgumentException.class, () -> calc.sqrt(x))"),
                Arguments.of("JUnit 4 without message", junit4Imports, "fail();",
                        "assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x))")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tryCatchFailVariants")
    void tryCatchFailIsFlattenedIntoAssertThrows(String variant, String imports, String failCall, String expectedAssertThrows) throws IOException {
        Path testFile = writeTestClass("com/acme/ThrowsTest.java", """
                package com.acme;

                %s
                public class ThrowsTest {
                    @Test
                    public void testThrows() {
                        Calc calc = new Calc();
                        int x = -1;
                        try {
                            calc.sqrt(x);
                            %s
                        } catch (IllegalArgumentException e) {
                            assertEquals("negative", e.getMessage());
                        }
                    }
                }
                """.formatted(imports, failCall));

        Path normalized = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty())).getValue0();

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    Calc calc = new Calc();
                    int x = -1;
                    IllegalArgumentException e = %s;
                    assertEquals("negative", e.getMessage());
                }
                """.formatted(expectedAssertThrows)), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    static Stream<Arguments> catchBlocksNotUsingTheException() {
        return Stream.of(
                Arguments.of("empty catch block", "", ""),
                Arguments.of("catch block with other statements", "assertTrue(true);", "assertTrue(true);")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("catchBlocksNotUsingTheException")
    void assertThrowsIsNotAssignedWhenTheCatchBlockDoesNotUseTheException(String description, String catchBlock, String expectedStatements) throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        try {
                            Integer.parseInt("x");
                            fail();
                        } catch (NumberFormatException e) {
                            %s
                        }
                    }
                """.formatted(catchBlock));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    assertThrows(NumberFormatException.class, () -> Integer.parseInt("x"));
                    %s
                }
                """.formatted(expectedStatements)), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void classNameInFailingCallIsNotCopiedAsVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        String s = "abc";
                        s = s + "d";
                        try {
                            Integer.parseInt(s);
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    String s = "abc";
                    s = s + "d";
                    final String finalS = s;
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt(finalS));
                    assertNotNull(e);
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void variableUsedTwiceInFailingCallGetsASingleFinalCopy() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        Calc calc = new Calc();
                        int x = 0;
                        x--;
                        try {
                            calc.add(x, x);
                            fail();
                        } catch (IllegalArgumentException e) {
                            assertNotNull(e);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    Calc calc = new Calc();
                    int x = 0;
                    x--;
                    final int finalX = x;
                    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> calc.add(finalX, finalX));
                    assertNotNull(e);
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    /**
     * A field can be captured by a lambda expression even if it is reassigned.
     */
    @Test
    void reassignedFieldUsedInTheFailingCallIsCapturedWithoutACopy() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int count = 0;

                    @Test
                    public void testThrows() {
                        count++;
                        try {
                            Integer.parseInt("x" + count);
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    count++;
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x" + count));
                    assertNotNull(e);
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void finalCopyClashingWithAVariableOfTheTestCaseGetsANumericSuffix() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        int finalX = 1;
                        int x = 0;
                        x++;
                        try {
                            Integer.parseInt("x" + x + finalX);
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int finalX = 1;
                    int x = 0;
                    x++;
                    final int finalX2 = x;
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x" + finalX2 + finalX));
                    assertNotNull(e);
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void reassignedVariableOfAnInlinedAuxiliaryMethodUsedInTheFailingCallGetsAFinalCopy() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        checkAll(2);
                    }

                    private void checkAll(int n) {
                        for (int i = 0; i < n; i++) {
                            try {
                                Integer.parseInt("x" + i);
                                fail();
                            } catch (NumberFormatException e) {
                                assertNotNull(e);
                            }
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int n = 2;
                    for (int i = 0; i < n; i++) {
                        final int finalI = i;
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x" + finalI));
                        assertNotNull(e);
                    }
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void catchParameterClashingWithFieldIsRenamed() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private String e = "field";

                    @Test
                    public void testThrows() {
                        Calc calc = new Calc();
                        try {
                            calc.explode();
                            fail();
                        } catch (IllegalStateException e) {
                            assertNotNull(e.getMessage());
                        }
                        assertEquals("field", e);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    Calc calc = new Calc();
                    IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> calc.explode());
                    assertNotNull(e2.getMessage());
                    assertEquals("field", e);
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void tryWithResourcesWithoutCatchIsFlattened() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testRead() throws IOException {
                        try (InputStream in = new ByteArrayInputStream(new byte[] {7})) {
                            assertEquals(7, in.read());
                        }
                        assertTrue(true);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    InputStream in = new ByteArrayInputStream(new byte[] {7});
                    assertEquals(7, in.read());
                    assertTrue(true);
                }
                """), findMethod(normalized, "testRead").getBody().orElseThrow());
    }

    @Test
    void tryWithResourceReferenceIsFlattenedWithoutAddingTheReference() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testRead() throws IOException {
                        InputStream in = new ByteArrayInputStream(new byte[] {7});
                        try (in) {
                            assertEquals(7, in.read());
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    InputStream in = new ByteArrayInputStream(new byte[] {7});
                    assertEquals(7, in.read());
                }
                """), findMethod(normalized, "testRead").getBody().orElseThrow());
    }

    @Test
    void tryFinallyWithoutCatchIsFlattened() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testFinally() {
                        StringBuilder sb = new StringBuilder();
                        try {
                            sb.append("a");
                            assertEquals("a", sb.toString());
                        } finally {
                            sb.setLength(0);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("a");
                    assertEquals("a", sb.toString());
                    sb.setLength(0);
                }
                """), findMethod(normalized, "testFinally").getBody().orElseThrow());
    }

    @Test
    void flattenedTryLocalClashingWithFieldIsRenamed() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int count = 0;

                    @Test
                    public void testCount() {
                        try {
                            int count = 1;
                            assertEquals(1, count);
                        } finally {
                            count = 0;
                        }
                        assertEquals(0, count);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int count2 = 1;
                    assertEquals(1, count2);
                    count = 0;
                    assertEquals(0, count);
                }
                """), findMethod(normalized, "testCount").getBody().orElseThrow());
    }

    @Test
    void finallyOfFlattenedTryCatchFailIsAddedOnce() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        Calc calc = new Calc();
                        try {
                            calc.sqrt(-1);
                            fail();
                        } catch (IllegalArgumentException e) {
                            assertNotNull(e);
                        } finally {
                            calc.reset();
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    Calc calc = new Calc();
                    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> calc.sqrt(-1));
                    assertNotNull(e);
                    calc.reset();
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void normalizationErrorFollowedByABlockStatementIsReported() throws IOException {
        normalizeHelperTest("""
                    @Test
                    public void testSwitchThenLoop() {
                        int v = 1;
                        switch (v) {
                            default:
                                assertEquals(1, v);
                        }
                        for (int i = 0; i < 2; i++) {
                            assertTrue(i >= 0);
                        }
                    }
                """);

        Path errorFile = outputPath.resolve("error/normalized/repo/src/test/java/com/acme/HelperTestSTARNormalizedTest.java");
        assertTrue(Files.exists(errorFile), "Expected error file " + errorFile);
        assertEquals(List.of("testSwitchThenLoop"), methodNames(StaticJavaParser.parse(errorFile)));
    }

    static Stream<Arguments> unsupportedTryStatements() {
        return Stream.of(
                Arguments.of("MULTI_CATCH", """
                        try {
                            Integer.parseInt(s);
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        } catch (IllegalStateException e) {
                            assertNotNull(e);
                        }
                        """),
                // Before Step 9 the try block was silently dropped (D11)
                Arguments.of("MULTI_CATCH", """
                        try {
                            int v = Integer.parseInt(s);
                            assertEquals(1, v);
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        } catch (IllegalStateException e) {
                            assertNotNull(e);
                        }
                        """),
                Arguments.of("UNION_CATCH", """
                        try {
                            Integer.parseInt(s);
                            fail();
                        } catch (NumberFormatException | IllegalStateException e) {
                            assertNotNull(e);
                        }
                        """),
                // Before Step 9 the fail() call was wrapped in the assertThrows (D10)
                Arguments.of("FAIL_NOT_LAST", """
                        try {
                            Integer.parseInt(s);
                            fail("should throw");
                            assertEquals("never", s);
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                        """),
                Arguments.of("UNSUPPORTED_FAILING_STATEMENT", """
                        try {
                            if (s.isEmpty()) {
                                Integer.parseInt(s);
                            }
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                        """),
                Arguments.of("MUST_NOT_THROW", """
                        try {
                            Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            fail("unexpected");
                        }
                        """),
                Arguments.of("CATCH_WITHOUT_FAIL", """
                        try {
                            Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                        """),
                Arguments.of("EMPTY_CATCH", """
                        try {
                            Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                        }
                        """),
                // A try statement nested in another statement discards the test case as well
                Arguments.of("EMPTY_CATCH", """
                        for (int i = 0; i < 2; i++) {
                            try {
                                Integer.parseInt(s + i);
                            } catch (NumberFormatException e) {
                            }
                        }
                        """)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedTryStatements")
    void testCaseWithUnsupportedTryStatementIsDiscarded(String expectedReason, String tryStatement) throws IOException {
        Path testFile = writeTestClass("com/acme/DiscardTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class DiscardTest {
                    @Test
                    public void testDiscarded() {
                        String s = "x";
                        %s
                        assertEquals("x", s);
                    }

                    @Test
                    public void testKept() {
                        assertEquals(1, 1);
                    }
                }
                """.formatted(tryStatement));

        Pair<Path, List<TestStats>> result = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty()));

        assertEquals(List.of("testKept"), methodNames(StaticJavaParser.parse(result.getValue0())));
        assertEquals(Map.of("testDiscarded", expectedReason), discardReasons(result.getValue1()));
        Path discardedFile = outputPath.resolve("discarded/normalized/repo/src/test/java/com/acme/DiscardTestSTARNormalizedTest.java");
        assertTrue(Files.exists(discardedFile), "Expected discarded file " + discardedFile);
        assertEquals(List.of("testDiscarded"), methodNames(StaticJavaParser.parse(discardedFile)));
    }

    @Test
    void tryStatementIsKeptWithItsBlocksNormalizedWithKeepTryCatch() throws IOException {
        Path normalized = normalizeHelperTest(KEEP_TRY_CATCH, """
                    @Test
                    public void testKeep() {
                        int x = 1;
                        try {
                            check(x);
                            Integer.parseInt("x");
                            fail("should throw");
                        } catch (NumberFormatException e) {
                            check(x);
                        } finally {
                            check(x);
                        }
                    }

                    private void check(int v) {
                        assertEquals(1, v);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    try {
                        assertEquals(1, x);
                        Integer.parseInt("x");
                        fail("should throw");
                    } catch (NumberFormatException e) {
                        assertEquals(1, x);
                    } finally {
                        assertEquals(1, x);
                    }
                }
                """), findMethod(normalized, "testKeep").getBody().orElseThrow());
    }

    @Test
    void tryWithResourcesIsKeptWithKeepTryCatch() throws IOException {
        Path normalized = normalizeHelperTest(KEEP_TRY_CATCH, """
                    @Test
                    public void testRead() throws IOException {
                        try (InputStream in = new ByteArrayInputStream(new byte[] {7})) {
                            assertEquals(7, in.read());
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    try (InputStream in = new ByteArrayInputStream(new byte[] {7})) {
                        assertEquals(7, in.read());
                    }
                }
                """), findMethod(normalized, "testRead").getBody().orElseThrow());
    }

    @Test
    void testCaseWithUnsupportedTryStatementIsDiscardedWithKeepTryCatch() throws IOException {
        Path normalized = normalizeHelperTest(KEEP_TRY_CATCH, """
                    @Test
                    public void testMustNotThrow() {
                        try {
                            Integer.parseInt("1");
                        } catch (NumberFormatException e) {
                            fail("unexpected");
                        }
                    }

                    @Test
                    public void testKept() {
                        assertEquals(1, 1);
                    }
                """);

        assertEquals(List.of("testKept"), methodNames(StaticJavaParser.parse(normalized)));
    }

    static Stream<Arguments> ifStatements() {
        return Stream.of(
                Arguments.of("single-statement branches of an else-if chain are wrapped in blocks", """
                        if (x > 0) y = 1; else if (x < 0) y = -1; else y = 0;
                        """, """
                        if (x > 0) {
                            y = 1;
                        } else if (x < 0) {
                            y = -1;
                        } else {
                            y = 0;
                        }
                        """),
                Arguments.of("if within the else block stays within the block", """
                        if (x > 0) {
                            y = 1;
                        } else {
                            if (x < 0) {
                                y = -1;
                            }
                        }
                        """, null),
                Arguments.of("D2: if within the then branch", """
                        if (x > 0) {
                            if (x > 5) {
                                assertTrue(x > 5);
                            }
                        } else {
                            assertTrue(x <= 0);
                        }
                        """, null),
                Arguments.of("D2: if-else within the then branch", """
                        if (x > 0) {
                            if (x > 5) {
                                y = 2;
                            } else {
                                y = 1;
                            }
                        } else {
                            y = -1;
                        }
                        """, null),
                Arguments.of("D2: if within a loop of an else-if branch", """
                        if (x > 5) {
                            y = 2;
                        } else if (x > 0) {
                            for (int i = 0; i < x; i++) {
                                if (i > 0) {
                                    y += i;
                                }
                            }
                        } else {
                            y = -1;
                        }
                        """, null),
                Arguments.of("D29: comment of the if statement", """
                        // positive values
                        if (x > 0) {
                            y = 1;
                        }
                        """, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ifStatements")
    void ifStatementKeepsTheStructureOfItsBranches(String description, String ifStatement, String expectedIfStatement) throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testIf() {
                        int x = 1;
                        int y = 0;
                %s
                        assertEquals(1, x);
                    }
                """.formatted(ifStatement));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    int y = 0;
                %s
                    assertEquals(1, x);
                }
                """.formatted(expectedIfStatement == null ? ifStatement : expectedIfStatement)),
                findMethod(normalized, "testIf").getBody().orElseThrow());
        Path errorFile = outputPath.resolve("error/normalized/repo/src/test/java/com/acme/HelperTestSTARNormalizedTest.java");
        assertFalse(Files.exists(errorFile), "Unexpected error file " + errorFile);
    }

    // Known defects (docs/design-review-2026-10-26.md). The expected results follow the decisions of 2026-10-26:
    // generated variables only when needed, with Java-style names.

    @Test
    void tryCatchFailWithinAuxiliaryMethodIsFlattenedOnceInlined() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        checkThrows();
                        assertTrue(true);
                    }

                    private void checkThrows() {
                        try {
                            Integer.parseInt("x");
                            fail();
                        } catch (NumberFormatException e) {
                            assertNotNull(e);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x"));
                    assertNotNull(e);
                    assertTrue(true);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodCalledByAnInlinedAuxiliaryMethodIsInlined() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        outer(x);
                    }

                    private void outer(int v) {
                        assertTrue(v > 0);
                        inner(v);
                    }

                    private void inner(int w) {
                        assertEquals(1, w);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    assertTrue(x > 0);
                    assertEquals(1, x);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void parameterAssignedByTheAuxiliaryMethodIsBoundToALocalVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        bump(x);
                        assertEquals(1, x);
                    }

                    private void bump(int v) {
                        v = v + 1;
                        assertEquals(2, v);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    int v = x;
                    v = v + 1;
                    assertEquals(2, v);
                    assertEquals(1, x);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void fieldReadByTheAuxiliaryMethodIsNotShadowedByALocalOfTheTestCase() throws IOException {
        Path normalized = normalizeHelperTest("""
                    private int base = 1;

                    @Test
                    public void testUsesHelper() {
                        int base = 5;
                        checkBase();
                        assertEquals(5, base);
                    }

                    private void checkBase() {
                        assertEquals(1, base);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int base = 5;
                    assertEquals(1, this.base);
                    assertEquals(5, base);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void auxiliaryMethodLocalClashingWithALoopVariableOfTheTestCaseIsRenamed() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        for (int i = 0; i < 2; i++) {
                            check();
                        }
                    }

                    private void check() {
                        for (int i = 0; i < 1; i++) {
                            assertTrue(i >= 0);
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    for (int i = 0; i < 2; i++) {
                        for (int i2 = 0; i2 < 1; i2++) {
                            assertTrue(i2 >= 0);
                        }
                    }
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void reassignedVariableUsedInTheFailingCallGetsAFinalCopy() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThrows() {
                        for (int i = 0; i < 2; i++) {
                            try {
                                Integer.parseInt("x" + i);
                                fail();
                            } catch (NumberFormatException e) {
                                assertNotNull(e);
                            }
                        }
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    for (int i = 0; i < 2; i++) {
                        final int finalI = i;
                        NumberFormatException e = assertThrows(NumberFormatException.class, () -> Integer.parseInt("x" + finalI));
                        assertNotNull(e);
                    }
                }
                """), findMethod(normalized, "testThrows").getBody().orElseThrow());
    }

    @Test
    void singleArgumentOfAVarargsParameterIsWrappedInAnArray() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testUsesHelper() {
                        int x = 1;
                        checkAll(x);
                    }

                    private void checkAll(int... values) {
                        assertTrue(values.length > 0);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int x = 1;
                    int[] values = new int[] {x};
                    assertTrue(values.length > 0);
                }
                """), findMethod(normalized, "testUsesHelper").getBody().orElseThrow());
    }

    @Test
    void normalizeTestGivesAUniqueNameToEachLoopVariable() throws IOException {
        Path normalized = normalizeHelperTest("""
                    @Test
                    public void testThreeLoops() {
                        int s = 0;
                        for (int i = 0; i < 1; i++) { s += i; }
                        for (int i = 0; i < 2; i++) { s += i; }
                        for (int i = 0; i < 3; i++) { s += i; }
                        assertEquals(4, s);
                    }
                """);

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int s = 0;
                    for (int i = 0; i < 1; i++) { s += i; }
                    for (int i2 = 0; i2 < 2; i2++) { s += i2; }
                    for (int i3 = 0; i3 < 3; i3++) { s += i3; }
                    assertEquals(4, s);
                }
                """), findMethod(normalized, "testThreeLoops").getBody().orElseThrow());
    }

    @Test
    void junitVersionIsTakenFromTheTestAnnotationImport() throws IOException {
        Path testFile = writeTestClass("com/acme/MixedImportsTest.java", """
                package com.acme;

                import org.junit.Assert;
                import org.junit.jupiter.api.Test;

                public class MixedImportsTest {
                    @Test
                    public void testValue() {
                        Assert.assertEquals(1, 1);
                    }
                }
                """);

        Path normalized = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty())).getValue0();

        assertTrue(Files.readString(normalized).contains("import static org.junit.jupiter.api.Assertions.*;"),
                "Expected the JUnit 5 assertions to be imported in " + normalized);
    }

    @Test
    void statisticsCountTheAssertStatementsOfTheWholeTestCase() throws IOException {
        Path testFile = writeTestClass("com/acme/AssertStmtTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;

                public class AssertStmtTest {
                    @Test
                    public void testAsserts() {
                        int x = 1;
                        assert x > 0;
                        assert x < 2;
                        x++;
                    }
                }
                """);

        List<TestStats> stats = assertDoesNotThrow(
                () -> TestUtils.normalizeTest(CONFIG, repoRoot, testFile, outputPath, Optional.empty())).getValue1();

        assertEquals(2, stats.get(0).assertionsDistribution().get("assert"));
    }

    /**
     * Normalizes a JUnit 5 test class {@code com.acme.HelperTest} whose members are the given source, and returns the
     * path to the normalized file.
     */
    private Path normalizeHelperTest(String members) throws IOException {
        return normalizeHelperTest(CONFIG, members);
    }

    /**
     * Normalizes, with the given configuration, a JUnit 5 test class {@code com.acme.HelperTest} whose members are the
     * given source, and returns the path to the normalized file.
     */
    private Path normalizeHelperTest(OraclesDatasetConfig config, String members) throws IOException {
        Path testFile = writeTestClass("com/acme/HelperTest.java", """
                package com.acme;

                import java.io.*;
                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class HelperTest {
                %s
                }
                """.formatted(members));
        return assertDoesNotThrow(
                () -> TestUtils.normalizeTest(config, repoRoot, testFile, outputPath, Optional.empty())).getValue0();
    }

    private static List<String> fieldNames(CompilationUnit cu) {
        return cu.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getParentNode().orElse(null) instanceof FieldDeclaration)
                .map(VariableDeclarator::getNameAsString)
                .toList();
    }

    /** Returns the discard reason of each discarded test case, by name. */
    private static Map<String, String> discardReasons(List<TestStats> testStats) {
        return testStats.stream()
                .filter(stats -> stats.discardReason() != null)
                .collect(Collectors.toMap(TestStats::identifier, TestStats::discardReason));
    }

    private static List<String> methodNames(CompilationUnit cu) {
        return cu.findAll(MethodDeclaration.class).stream().map(MethodDeclaration::getNameAsString).sorted().toList();
    }

    private Path writeTestClass(String relativePath, String source) throws IOException {
        return repo.writeTestClass(relativePath, source);
    }

    private static MethodDeclaration findMethod(Path javaFile, String methodName) throws IOException {
        assertTrue(Files.exists(javaFile), "Expected normalized file " + javaFile);
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        return cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals(methodName))
                .orElseThrow(() -> new AssertionError("Method " + methodName + " not found in " + javaFile));
    }
}
