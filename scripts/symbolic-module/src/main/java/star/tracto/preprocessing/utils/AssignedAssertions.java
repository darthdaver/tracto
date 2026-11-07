package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.JUnitAssertionType;
import star.tracto.preprocessing.components.Assignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The assertions whose result is assigned to a variable ({@code Exception e = assertThrows(...)},
 * {@code String v = assertDoesNotThrow(...)}) (Step 27): the target is the assertion only, so that the model always
 * generates an assertion; the declaration of the variable is kept apart, as written in the test, and added back to the
 * generated assertion at the end of the generation.
 */
public final class AssignedAssertions {

    private static final Logger logger = LoggerFactory.getLogger(AssignedAssertions.class);

    /**
     * An assigned assertion split in its two parts.
     *
     * @param assertion the assertion, as a statement by itself
     * @param assignment the declaration of the variable
     */
    public record Split(Statement assertion, Assignment assignment) {}

    private AssignedAssertions() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Splits a statement that declares one variable initialized with a JUnit assertion.
     *
     * @param statement the target statement
     * @return the assertion and the declaration, or an empty optional if the statement is not an assigned assertion (or
     *         the declaration joined with the assertion would not give back the statement as printed)
     */
    public static Optional<Split> split(Statement statement) {
        if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
            return Optional.empty();
        }
        VariableDeclarationExpr declaration = statement.asExpressionStmt().getExpression().asVariableDeclarationExpr();
        if (declaration.getVariables().size() != 1) {
            return Optional.empty();
        }
        VariableDeclarator variable = declaration.getVariable(0);
        Optional<Expression> initializer = variable.getInitializer();
        if (initializer.isEmpty() || !initializer.get().isMethodCallExpr()
                || !JUnitAssertionType.isJUnitAssertion(initializer.get().asMethodCallExpr().getNameAsString())) {
            return Optional.empty();
        }
        List<String> modifiers = new ArrayList<>();
        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            modifiers.add(annotation.toString());
        }
        for (Modifier modifier : declaration.getModifiers()) {
            modifiers.add(modifier.getKeyword().asString());
        }
        // The type copied from the declaration, never inferred (Exception e = assertThrows(SomeException.class, ...))
        Assignment assignment = new Assignment(modifiers, variable.getType().toString(), variable.getNameAsString());
        Statement assertion = new ExpressionStmt(initializer.get().clone());
        String rebuilt = assignment.declare(assertion.toString());
        if (!rebuilt.equals(statement.toString())) {
            logger.warn("Assigned assertion not split: the declaration and the assertion give {} instead of {}", rebuilt, statement);
            return Optional.empty();
        }
        return Optional.of(new Split(assertion, assignment));
    }
}
