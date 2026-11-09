package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import star.tracto.data.enums.AssertionLambdaBlockShape;
import star.tracto.data.enums.AssertionLambdaBlocksStrategyType;
import star.tracto.data.enums.JUnitAssertionType;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.Optional;
import java.util.Set;

/**
 * Classifies the assertions whose lambda expression is a block of statements ({@code assertThrows},
 * {@code assertThrowsExactly} and {@code assertDoesNotThrow}) into the shapes defined by
 * {@link AssertionLambdaBlockShape}, and decides what the normalization and the split do with them ({@link Outcome}).
 * The decision depends only on the source code of the statement, so that the normalization and the split (which reads
 * the normalized test class again) agree on it.
 * <p>
 * A target is always a single statement: the model never generates a block of statements. Any other assertion that
 * contains a block of statements (a lambda block, as in {@code assertTimeoutPreemptively(d, () -> { ... })}, or the
 * body of an anonymous class) produces no datapoint ({@link #containsAStatementBlock}, Step 29).
 */
final class AssertionLambdaBlockClassifier {

    /* The names of the assertions whose lambda block is classified */
    private static final Set<String> ASSERTIONS = Set.of(
            JUnitAssertionType.ASSERT_THROWS.getAssertionMethodName(),
            JUnitAssertionType.ASSERT_THROWS_EXACTLY.getAssertionMethodName(),
            JUnitAssertionType.ASSERT_DOES_NOT_THROW.getAssertionMethodName()
    );

    /* Do not instantiate this class. */
    private AssertionLambdaBlockClassifier() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * What the normalization and the split do with an assertion with a lambda block.
     */
    enum Outcome {
        /* The statements before the last one move before the assertion, and only the call of the last statement stays
           within the lambda expression */
        MOVE_TO_PREFIX,
        /* The statements of the block replace the assertDoesNotThrow (its last statement is an assertion, the target) */
        UNWRAP,
        /* The assertion stays as it is and produces no datapoint */
        SKIP
    }

    /**
     * An assertion statement with a lambda block of at least one statement.
     *
     * @param statement the assertion statement ({@code assertThrows(...);} or {@code E e = assertThrows(...);})
     * @param assertion the call to the assertion
     * @param lambda the lambda expression with the block (the first lambda expression among the arguments)
     * @param block the block of the lambda expression
     * @param shape the shape of the block
     */
    record AssertionLambdaBlock(Statement statement, MethodCallExpr assertion, LambdaExpr lambda, BlockStmt block, AssertionLambdaBlockShape shape) {}

    /**
     * Returns the assertion with a lambda block of a statement: a call to {@code assertThrows},
     * {@code assertThrowsExactly} or {@code assertDoesNotThrow}, as an expression statement or as the initializer of the
     * only variable declared by the statement, whose first lambda argument (the executable: a message supplier follows
     * it) is a block of at least one statement. A block of one statement is normalized as a longer one (Step 29): with
     * nothing to move, {@code () -> { a(); }} becomes {@code () -> a()}.
     *
     * @param statement the statement to check
     * @return the assertion with its lambda block, or an empty optional if the statement is not such an assertion
     */
    static Optional<AssertionLambdaBlock> find(Statement statement) {
        if (!statement.isExpressionStmt()) {
            return Optional.empty();
        }
        Expression expr = statement.asExpressionStmt().getExpression();
        if (expr.isVariableDeclarationExpr()) {
            VariableDeclarationExpr declaration = expr.asVariableDeclarationExpr();
            if (declaration.getVariables().size() != 1 || declaration.getVariable(0).getInitializer().isEmpty()) {
                return Optional.empty();
            }
            expr = declaration.getVariable(0).getInitializer().get();
        }
        if (!expr.isMethodCallExpr() || !ASSERTIONS.contains(expr.asMethodCallExpr().getNameAsString())) {
            return Optional.empty();
        }
        MethodCallExpr assertion = expr.asMethodCallExpr();
        Optional<LambdaExpr> lambda = assertion.getArguments().stream()
                .filter(Expression::isLambdaExpr)
                .map(Expression::asLambdaExpr)
                .findFirst();
        if (lambda.isEmpty() || !lambda.get().getBody().isBlockStmt() || lambda.get().getBody().asBlockStmt().getStatements().isEmpty()) {
            return Optional.empty();
        }
        BlockStmt block = lambda.get().getBody().asBlockStmt();
        return Optional.of(new AssertionLambdaBlock(statement, assertion, lambda.get(), block, classify(block)));
    }

    /**
     * Returns what the normalization and the split do with an assertion with a lambda block. With the {@code skip}
     * strategy no assertion produces a datapoint. With the {@code move-to-prefix} strategy the statements before the
     * last call move before the assertion; an {@code assertDoesNotThrow} ending with an assertion is unwrapped (the
     * assertion is the target); any other shape produces no datapoint, since the statement that throws (or must not
     * throw) is unclear.
     *
     * @param lambdaBlock the assertion with its lambda block
     * @param strategy the strategy of the configuration
     * @return the outcome
     */
    static Outcome outcome(AssertionLambdaBlock lambdaBlock, AssertionLambdaBlocksStrategyType strategy) {
        if (strategy == AssertionLambdaBlocksStrategyType.SKIP) {
            return Outcome.SKIP;
        }
        return switch (lambdaBlock.shape()) {
            case LAST_CALL -> Outcome.MOVE_TO_PREFIX;
            // An assertDoesNotThrow ending with an assertion returns no value, so it cannot initialize a variable
            case LAST_ASSERTION -> isAssertDoesNotThrow(lambdaBlock) && lambdaBlock.assertion().getParentNode().orElse(null) == lambdaBlock.statement()
                    ? Outcome.UNWRAP
                    : Outcome.SKIP;
            default -> Outcome.SKIP;
        };
    }

    /**
     * Checks if a statement is an assertion that produces no datapoint: an assertion with a lambda block whose outcome
     * is {@link Outcome#SKIP}, or any other assertion containing a block of statements ({@link #containsAStatementBlock}).
     * The split does not make it a split point.
     *
     * @param statement the statement to check
     * @param strategy the strategy of the configuration
     * @return {@code true} if the statement is an assertion with a lambda block that produces no datapoint
     */
    static boolean producesNoDatapoint(Statement statement, AssertionLambdaBlocksStrategyType strategy) {
        Optional<AssertionLambdaBlock> lambdaBlock = find(statement);
        if (lambdaBlock.isPresent()) {
            return outcome(lambdaBlock.get(), strategy) == Outcome.SKIP;
        }
        return JavaParserUtils.isAssertionStatement(statement) && containsAStatementBlock(statement);
    }

    /**
     * Checks if a statement contains a block of statements: a lambda expression whose body is a block, or the body of
     * an anonymous class. An assertion containing one is not a target: the model generates a single statement.
     *
     * @param statement the statement to check
     * @return {@code true} if the statement contains a lambda block or an anonymous class body
     */
    static boolean containsAStatementBlock(Statement statement) {
        return !statement.findAll(LambdaExpr.class, lambdaExpr -> lambdaExpr.getBody().isBlockStmt()).isEmpty()
                || !statement.findAll(ObjectCreationExpr.class, creation -> creation.getAnonymousClassBody().isPresent()).isEmpty();
    }

    /**
     * Returns the expression of the last statement of a lambda block of shape
     * {@link AssertionLambdaBlockShape#LAST_CALL}: the call or the object creation that stays within the lambda
     * expression.
     *
     * @param lambdaBlock the assertion with its lambda block
     * @return the call or the object creation of the last statement
     */
    static Expression lastCall(AssertionLambdaBlock lambdaBlock) {
        return lastCall(lambdaBlock.block().getStatements().getLast().orElseThrow())
                .orElseThrow(() -> new IllegalStateException("No call in the last statement of " + lambdaBlock.block()));
    }

    /**
     * Classifies a block by its last statement. A return statement before the last statement prevents the move, since
     * it would end the test case.
     */
    private static AssertionLambdaBlockShape classify(BlockStmt block) {
        NodeList<Statement> statements = block.getStatements();
        if (statements.stream().limit(statements.size() - 1).anyMatch(AssertionLambdaBlockClassifier::containsAReturnOfTheLambda)) {
            return AssertionLambdaBlockShape.EARLY_RETURN;
        }
        Statement last = statements.getLast().orElseThrow();
        if (JavaParserUtils.isAssertionStatement(last)) {
            return AssertionLambdaBlockShape.LAST_ASSERTION;
        }
        if (last.isThrowStmt()) {
            return AssertionLambdaBlockShape.LAST_THROW;
        }
        if (last.isBlockStmt() || last.isIfStmt() || last.isForStmt() || last.isForEachStmt() || last.isWhileStmt() || last.isDoStmt()
                || last.isTryStmt() || last.isSwitchStmt() || last.isSynchronizedStmt() || last.isLabeledStmt()) {
            return AssertionLambdaBlockShape.LAST_COMPOUND;
        }
        return lastCall(last).isPresent() ? AssertionLambdaBlockShape.LAST_CALL : AssertionLambdaBlockShape.LAST_OTHER;
    }

    /**
     * Returns the call or the object creation of a statement: the expression of an expression statement, the value of
     * an assignment, the initializer of the only variable of a declaration, or the value of a return statement.
     */
    private static Optional<Expression> lastCall(Statement statement) {
        Optional<Expression> expr = Optional.empty();
        if (statement.isExpressionStmt()) {
            expr = Optional.of(statement.asExpressionStmt().getExpression());
            if (expr.get().isAssignExpr()) {
                expr = Optional.of(expr.get().asAssignExpr().getValue());
            } else if (expr.get().isVariableDeclarationExpr()) {
                VariableDeclarationExpr declaration = expr.get().asVariableDeclarationExpr();
                expr = declaration.getVariables().size() == 1 ? declaration.getVariable(0).getInitializer() : Optional.empty();
            }
        } else if (statement.isReturnStmt()) {
            expr = statement.asReturnStmt().getExpression();
        }
        return expr.filter(e -> e.isMethodCallExpr() || e.isObjectCreationExpr());
    }

    /**
     * Checks if a statement contains a return statement of the lambda expression, that is not nested within another
     * lambda expression or within a class.
     */
    private static boolean containsAReturnOfTheLambda(Statement statement) {
        return statement.findAll(ReturnStmt.class).stream().anyMatch(returnStmt -> {
            Node node = returnStmt;
            while (node != statement) {
                node = node.getParentNode().orElseThrow();
                if (node instanceof LambdaExpr || node instanceof BodyDeclaration<?>) {
                    return false;
                }
            }
            return true;
        });
    }

    private static boolean isAssertDoesNotThrow(AssertionLambdaBlock lambdaBlock) {
        return lambdaBlock.assertion().getNameAsString().equals(JUnitAssertionType.ASSERT_DOES_NOT_THROW.getAssertionMethodName());
    }
}
