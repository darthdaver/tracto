package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.TryStmt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import star.tracto.data.enums.TryStatementShape;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the classification of the try statements of the test cases ({@link TryStatementClassifier}).
 */
class TryStatementClassifierTest {

    static Stream<Arguments> tryStatements() {
        return Stream.of(
                Arguments.of(TryStatementShape.RESOURCES_WITHOUT_CATCH, "try (InputStream in = open()) { assertEquals(7, in.read()); }"),
                Arguments.of(TryStatementShape.RESOURCES_WITHOUT_CATCH, "try (InputStream in = open()) { use(in); } finally { reset(); }"),
                Arguments.of(TryStatementShape.FINALLY_WITHOUT_CATCH, "try { use(1); } finally { reset(); }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try { calc.sqrt(-1); fail(); } catch (IllegalArgumentException e) { assertNotNull(e); }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try { calc.sqrt(-1); Assert.fail(\"should throw\"); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try { int v = Integer.parseInt(s); fail(); } catch (NumberFormatException e) { }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try { v = Integer.parseInt(s); fail(); } catch (NumberFormatException e) { }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try { new Range(2, 1); fail(); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.EXPECTED_EXCEPTION, "try (InputStream in = open()) { in.read(); fail(); } catch (IOException e) { } finally { reset(); }"),
                Arguments.of(TryStatementShape.MULTI_CATCH, "try { calc.sqrt(-1); fail(); } catch (IllegalArgumentException e) { } catch (IllegalStateException e) { }"),
                Arguments.of(TryStatementShape.MULTI_CATCH, "try { use(1); } catch (IllegalArgumentException e) { } catch (IllegalStateException e) { }"),
                Arguments.of(TryStatementShape.UNION_CATCH, "try { calc.sqrt(-1); fail(); } catch (IllegalArgumentException | IllegalStateException e) { }"),
                Arguments.of(TryStatementShape.FAIL_NOT_LAST, "try { calc.sqrt(-1); fail(); assertEquals(0, calc.total()); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.FAIL_NOT_LAST, "try { if (calc.sqrt(-1) < 0) { fail(); } } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.UNSUPPORTED_FAILING_STATEMENT, "try { fail(); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.UNSUPPORTED_FAILING_STATEMENT, "try { for (int i = 0; i < 2; i++) { calc.sqrt(-i); } fail(); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.UNSUPPORTED_FAILING_STATEMENT, "try { int r = a / b; fail(); } catch (ArithmeticException e) { }"),
                Arguments.of(TryStatementShape.UNSUPPORTED_FAILING_STATEMENT, "try { int a = 1, b = calc.sqrt(-1); fail(); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.MUST_NOT_THROW, "try { calc.sqrt(1); } catch (IllegalArgumentException e) { fail(\"unexpected\"); }"),
                Arguments.of(TryStatementShape.MUST_NOT_THROW, "try { calc.sqrt(1); } catch (IllegalArgumentException e) { if (strict) { fail(); } }"),
                Arguments.of(TryStatementShape.CATCH_WITHOUT_FAIL, "try { calc.sqrt(-1); } catch (IllegalArgumentException e) { assertNotNull(e); }"),
                Arguments.of(TryStatementShape.EMPTY_CATCH, "try { calc.sqrt(-1); } catch (IllegalArgumentException e) { }"),
                Arguments.of(TryStatementShape.EMPTY_CATCH, "try { calc.sqrt(-1); } catch (IllegalArgumentException e) { /* expected */ }")
        );
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("tryStatements")
    void classifiesTheShapeOfTheTryStatement(TryStatementShape expectedShape, String tryStatement) {
        TryStmt tryStmt = StaticJavaParser.parseStatement(tryStatement).asTryStmt();

        assertEquals(expectedShape, TryStatementClassifier.classify(tryStmt));
    }
}
