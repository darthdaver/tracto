package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import star.tracto.data.enums.TryStatementShape;

import java.util.List;
import java.util.Optional;

/**
 * Classifies the try statements of the test cases into the shapes defined by {@link TryStatementShape}, so that the
 * normalization transforms only the supported shapes and discards the test cases containing the others.
 */
final class TryStatementClassifier {

    /* Do not instantiate this class. */
    private TryStatementClassifier() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Returns the shape of a try statement. The checks are applied in order: a try statement without catch clauses is
     * supported; with catch clauses, it is supported only as an expected exception, that is a single catch clause
     * (without union types) and a try block ending with a call followed by {@code fail()}.
     *
     * @param tryStmt the try statement to classify
     * @return the shape of the try statement
     */
    static TryStatementShape classify(TryStmt tryStmt) {
        NodeList<CatchClause> catchClauses = tryStmt.getCatchClauses();
        if (catchClauses.isEmpty()) {
            return tryStmt.getResources().isEmpty() ? TryStatementShape.FINALLY_WITHOUT_CATCH : TryStatementShape.RESOURCES_WITHOUT_CATCH;
        }
        if (catchClauses.size() > 1) {
            return TryStatementShape.MULTI_CATCH;
        }
        CatchClause catchClause = catchClauses.get(0);
        if (catchClause.getParameter().getType().isUnionType()) {
            return TryStatementShape.UNION_CATCH;
        }
        List<Statement> tryStatements = tryStmt.getTryBlock().getStatements();
        int failCallsInTryBlock = countFailCalls(tryStmt.getTryBlock());
        if (failCallsInTryBlock > 0) {
            // The fail() call must be the last statement of the try block (and the only one), otherwise the statements
            // after it would be lost when the try statement is converted into an assertThrows
            boolean failIsLast = isFailCall(tryStatements.get(tryStatements.size() - 1));
            if (!failIsLast || failCallsInTryBlock > 1) {
                return TryStatementShape.FAIL_NOT_LAST;
            }
            return getFailingExpression(tryStmt).isPresent() ? TryStatementShape.EXPECTED_EXCEPTION : TryStatementShape.UNSUPPORTED_FAILING_STATEMENT;
        }
        if (countFailCalls(catchClause.getBody()) > 0) {
            return TryStatementShape.MUST_NOT_THROW;
        }
        return catchClause.getBody().isEmpty() ? TryStatementShape.EMPTY_CATCH : TryStatementShape.CATCH_WITHOUT_FAIL;
    }

    /**
     * Returns the expression expected to throw the exception in a try block terminated by a {@code fail()} call: the
     * expression of the penultimate statement of the try block. If the statement assigns the value of the expression to
     * a variable (an assignment, or the declaration of a single variable), the assigned value is returned. The
     * expression must be a method call or an object creation, so that it can be the body of the lambda expression of an
     * {@code assertThrows}.
     *
     * @param tryStmt the try statement, whose try block ends with the failing statement and the {@code fail()} call
     * @return the expression expected to throw the exception, or an empty optional if the try block does not contain
     * such an expression before the {@code fail()} call
     */
    static Optional<Expression> getFailingExpression(TryStmt tryStmt) {
        List<Statement> statements = tryStmt.getTryBlock().getStatements();
        if (statements.size() < 2 || !statements.get(statements.size() - 2).isExpressionStmt()) {
            return Optional.empty();
        }
        Expression failingExpr = statements.get(statements.size() - 2).asExpressionStmt().getExpression();
        if (failingExpr.isAssignExpr()) {
            failingExpr = failingExpr.asAssignExpr().getValue();
        } else if (failingExpr.isVariableDeclarationExpr()) {
            VariableDeclarationExpr varDeclExpr = failingExpr.asVariableDeclarationExpr();
            if (varDeclExpr.getVariables().size() != 1 || varDeclExpr.getVariable(0).getInitializer().isEmpty()) {
                return Optional.empty();
            }
            failingExpr = varDeclExpr.getVariable(0).getInitializer().get();
        }
        if (failingExpr.isMethodCallExpr() || failingExpr.isObjectCreationExpr()) {
            return Optional.of(failingExpr);
        }
        return Optional.empty();
    }

    /**
     * Checks if a statement is a call to {@code fail()} (with or without a qualifier, like {@code Assert.fail()}).
     *
     * @param statement the statement to check
     * @return {@code true} if the statement is a call to {@code fail()}, {@code false} otherwise
     */
    static boolean isFailCall(Statement statement) {
        return statement.isExpressionStmt()
                && statement.asExpressionStmt().getExpression().isMethodCallExpr()
                && statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("fail");
    }

    /**
     * Counts the calls to {@code fail()} within a node, at any depth.
     */
    private static int countFailCalls(Node node) {
        return node.findAll(MethodCallExpr.class, methodCallExpr -> methodCallExpr.getNameAsString().equals("fail")).size();
    }
}
