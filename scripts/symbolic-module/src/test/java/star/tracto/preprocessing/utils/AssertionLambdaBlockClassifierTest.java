package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import star.tracto.data.enums.AssertionLambdaBlockShape;
import star.tracto.data.enums.AssertionLambdaBlocksStrategyType;
import star.tracto.preprocessing.utils.AssertionLambdaBlockClassifier.Outcome;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the classification of the assertions whose lambda expression is a block of statements
 * ({@link AssertionLambdaBlockClassifier}).
 */
class AssertionLambdaBlockClassifierTest {

    static Stream<Arguments> statements() {
        return Stream.of(
                // The assertions in scope: assertThrows, assertThrowsExactly and assertDoesNotThrow, as a statement or
                // as the initializer of a variable, with a lambda block of at least two statements
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "Assertions.assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "IllegalStateException e = assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(\"should throw\", IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrowsExactly(IllegalStateException.class, () -> { calc.reset(); calc.explode(); }, \"should throw\");"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertDoesNotThrow(() -> { calc.reset(); calc.add(1, 2); }, () -> \"should not throw\");"),
                // The last statement keeps a call or an object creation within the lambda
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalArgumentException.class, () -> { int a = -1; new Range(1, a); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalArgumentException.class, () -> { int a = -1; int r = calc.sqrt(a); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalArgumentException.class, () -> { int a = -1; results[0] = calc.sqrt(a); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "int r = assertDoesNotThrow(() -> { int a = 4; return calc.sqrt(a); });"),
                // A return before the last statement belongs to the lambda only if it is not nested in another lambda
                // or in an anonymous class
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalStateException.class, () -> { Runnable r = () -> { return; }; calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_CALL, "assertThrows(IllegalStateException.class, () -> { Runnable r = new Runnable() { public void run() { return; } }; calc.explode(); });"),
                // The statements that produce no datapoint (or that unwrap an assertDoesNotThrow)
                Arguments.of(AssertionLambdaBlockShape.LAST_ASSERTION, "assertThrows(ArithmeticException.class, () -> { calc.reset(); assertEquals(0, calc.divide(1, 0)); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_ASSERTION, "assertThrows(IllegalStateException.class, () -> { calc.explode(); fail(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_ASSERTION, "assertThrows(AssertionError.class, () -> { calc.reset(); assert calc.total() > 0; });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_ASSERTION, "assertDoesNotThrow(() -> { calc.reset(); assertEquals(0, calc.total()); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_COMPOUND, "assertThrows(IllegalStateException.class, () -> { calc.reset(); try { calc.explode(); } finally { calc.reset(); } });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_COMPOUND, "assertThrows(IllegalStateException.class, () -> { calc.reset(); for (int i = 0; i < 2; i++) { calc.explode(); } });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_COMPOUND, "assertThrows(IllegalStateException.class, () -> { calc.reset(); if (strict) calc.explode(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_COMPOUND, "assertDoesNotThrow(() -> { calc.reset(); { calc.add(1, 2); } });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_THROW, "assertThrows(IllegalStateException.class, () -> { calc.reset(); throw new IllegalStateException(); });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_OTHER, "assertDoesNotThrow(() -> { calc.reset(); return; });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_OTHER, "int r = assertDoesNotThrow(() -> { int a = calc.sqrt(4); return a; });"),
                Arguments.of(AssertionLambdaBlockShape.LAST_OTHER, "assertThrows(ArithmeticException.class, () -> { int a = 0; int r = 1 / a; });"),
                Arguments.of(AssertionLambdaBlockShape.EARLY_RETURN, "assertThrows(IllegalStateException.class, () -> { if (strict) { return; } calc.explode(); });"),
                // The statements out of scope
                Arguments.of(null, "assertThrows(IllegalStateException.class, () -> calc.explode());"),
                Arguments.of(null, "assertThrows(IllegalStateException.class, () -> { calc.explode(); });"),
                Arguments.of(null, "assertThrows(IllegalStateException.class, calc::explode);"),
                Arguments.of(null, "assertAll(() -> { calc.reset(); assertEquals(0, calc.total()); });"),
                Arguments.of(null, "assertTimeout(Duration.ofSeconds(1), () -> { calc.reset(); calc.add(1, 2); });"),
                Arguments.of(null, "values.forEach(v -> { calc.accumulate(v); calc.explode(); });"),
                Arguments.of(null, "assertEquals(\"boom\", assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); }).getMessage());")
        );
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("statements")
    void classifiesTheLastStatementOfTheLambdaBlock(AssertionLambdaBlockShape expectedShape, String statement) {
        Statement stmt = StaticJavaParser.parseStatement(statement);

        assertEquals(Optional.ofNullable(expectedShape), AssertionLambdaBlockClassifier.find(stmt).map(AssertionLambdaBlockClassifier.AssertionLambdaBlock::shape));
    }

    static Stream<Arguments> outcomes() {
        return Stream.of(
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.MOVE_TO_PREFIX, "assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.MOVE_TO_PREFIX, "assertDoesNotThrow(() -> { calc.reset(); calc.add(1, 2); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.SKIP, "assertThrows(ArithmeticException.class, () -> { calc.reset(); assertEquals(0, calc.divide(1, 0)); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.SKIP, "assertThrowsExactly(ArithmeticException.class, () -> { calc.reset(); assertEquals(0, calc.divide(1, 0)); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.UNWRAP, "assertDoesNotThrow(() -> { calc.reset(); assertEquals(0, calc.total()); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.SKIP, "assertDoesNotThrow(() -> { calc.reset(); try { calc.add(1, 2); } finally { calc.reset(); } });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.SKIP, "assertThrows(IllegalStateException.class, () -> { calc.reset(); throw new IllegalStateException(); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX, Outcome.SKIP, "assertThrows(IllegalStateException.class, () -> { if (strict) { return; } calc.explode(); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.SKIP, Outcome.SKIP, "assertThrows(IllegalStateException.class, () -> { calc.reset(); calc.explode(); });"),
                Arguments.of(AssertionLambdaBlocksStrategyType.SKIP, Outcome.SKIP, "assertDoesNotThrow(() -> { calc.reset(); assertEquals(0, calc.total()); });")
        );
    }

    @ParameterizedTest(name = "{0}, {1}: {2}")
    @MethodSource("outcomes")
    void outcomeDependsOnTheShapeTheAssertionAndTheStrategy(AssertionLambdaBlocksStrategyType strategy, Outcome expectedOutcome, String statement) {
        AssertionLambdaBlockClassifier.AssertionLambdaBlock block = AssertionLambdaBlockClassifier.find(StaticJavaParser.parseStatement(statement)).orElseThrow();

        assertEquals(expectedOutcome, AssertionLambdaBlockClassifier.outcome(block, strategy));
    }
}
