package star.tracto.preprocessing.utils;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.JUnitAnnotationType;
import star.tracto.data.enums.NumAssertionsMatchStrategyType;
import star.tracto.data.enums.SplitStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Splits a normalized test case into split test cases, one for each split point: an assertion, or any statement,
 * depending on the split strategy. The split test case of a split point is a copy of the test case truncated right
 * after the split point: at every block enclosing the split point, the statements that follow it are removed (an
 * {@code else} branch, the following entries of a {@code switch}, the content of the following catch and finally
 * blocks). The split test prefix is the same copy without the split point.
 *
 * <p>The split points are searched within the blocks of the compound statements ({@code if}, loops,
 * {@code synchronized}, labeled statements, {@code switch}, {@code try}) and within the block of a lambda expression
 * passed to a statement that is not an assertion (for example {@code values.forEach(v -> { ... })}). Any other
 * statement, like an assertion or a statement declaring an anonymous class, is a single unit.</p>
 */
final class TestCaseSplitter {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TestCaseSplitter.class);
    /* The delimiter between the name of the test case and the index of the split test case */
    private static final String SPLIT_DELIMITER = "_split_";

    /* Do not instantiate this class. */
    private TestCaseSplitter() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * A split test case, ending with its split point, and the corresponding split test prefix, without it.
     *
     * @param testCase the split test case
     * @param prefix the split test prefix
     */
    record SplitTestCase(MethodDeclaration testCase, MethodDeclaration prefix) {}

    /**
     * Splits a normalized test case. The split test cases are named after the test case, with the suffix
     * {@code _split_<index>}, in the order of their split points in the source code.
     *
     * @param testCase the normalized test case
     * @param config the configuration options for the dataset to generate
     * @return the split test cases with their split test prefixes
     * @throws IllegalStateException if the number of split test cases differs from the number of assertions, with the
     * strict {@code num-assertions-match} strategy
     */
    static List<SplitTestCase> split(MethodDeclaration testCase, OraclesDatasetConfig config) {
        // Work on a copy, where the bodies of the compound statements are blocks: a split point can then always be
        // removed from its block, to generate the split test prefix. The bodies are wrapped while collecting the split
        // points. The setters used to wrap them append the new block after the other children of the statement (after
        // the `else` branch of an `if`, for example), while the copies of the test case keep the order of the source
        // code: the split points are located in the copies by their index in pre-order, so they are collected again
        // on a copy of the wrapped test case
        MethodDeclaration wrapped = testCase.clone();
        collectSplitPoints(wrapped.getBody().orElseThrow(), config.splitStrategy());
        MethodDeclaration source = wrapped.clone();
        BlockStmt body = source.getBody().orElseThrow();
        List<Statement> splitPoints = collectSplitPoints(body, config.splitStrategy());
        if (config.splitStrategy() == SplitStrategyType.ASSERTION) {
            checkNumberOfAssertions(source, splitPoints.size(), config.numAssertionsMatchStrategy());
        }
        List<Statement> statements = body.findAll(Statement.class);
        List<SplitTestCase> splitTestCases = new ArrayList<>();
        for (Statement splitPoint : splitPoints) {
            splitTestCases.add(splitAt(source, indexOf(statements, splitPoint), splitTestCases.size()));
        }
        // The statements after the last split point form one more split test case, ending with the last statement
        if (config.keepStatementsAfterLastAssertion() && !splitTestCases.isEmpty()) {
            BlockStmt lastSplitTestCaseBody = splitTestCases.get(splitTestCases.size() - 1).testCase().getBody().orElseThrow();
            if (!lastSplitTestCaseBody.equals(body)) {
                splitTestCases.add(splitAt(source, statements.size() - 1, splitTestCases.size()));
            }
        }
        return splitTestCases;
    }

    /**
     * Collects the split points within the body of a test case, in the order of the source code.
     */
    private static List<Statement> collectSplitPoints(BlockStmt body, SplitStrategyType splitStrategy) {
        List<Statement> splitPoints = new ArrayList<>();
        for (Statement statement : body.getStatements()) {
            collectSplitPoints(statement, splitStrategy, splitPoints);
        }
        return splitPoints;
    }

    /**
     * Collects the split points within a statement, in the order of the source code. With the assertion strategy the
     * split points are the assertions; with the statement strategy they are the statements that are not split further,
     * and the compound statements without split points within them (for example an empty loop).
     */
    private static void collectSplitPoints(Statement statement, SplitStrategyType splitStrategy, List<Statement> splitPoints) {
        List<Statement> innerStatements = innerStatements(statement);
        if (innerStatements == null) {
            if (splitStrategy == SplitStrategyType.STATEMENT || JavaParserUtils.isAssertionStatement(statement)) {
                splitPoints.add(statement);
            }
            return;
        }
        int splitPointsBefore = splitPoints.size();
        for (Statement innerStatement : innerStatements) {
            collectSplitPoints(innerStatement, splitStrategy, splitPoints);
        }
        if (splitStrategy == SplitStrategyType.STATEMENT && splitPoints.size() == splitPointsBefore) {
            splitPoints.add(statement);
        }
    }

    /**
     * Returns the statements within which the split points of a statement are searched, or {@code null} if the
     * statement is a single unit. The body of a compound statement that is not a block is wrapped in a block first.
     */
    private static List<Statement> innerStatements(Statement statement) {
        if (statement.isBlockStmt()) {
            return statement.asBlockStmt().getStatements();
        } else if (statement.isIfStmt()) {
            IfStmt ifStmt = statement.asIfStmt();
            List<Statement> branches = new ArrayList<>(List.of(asBlock(ifStmt.getThenStmt(), ifStmt::setThenStmt)));
            // An `else if` stays an if statement, so that the chain is kept
            ifStmt.getElseStmt().ifPresent(elseStmt -> branches.add(elseStmt.isIfStmt() ? elseStmt : asBlock(elseStmt, ifStmt::setElseStmt)));
            return branches;
        } else if (statement.isForStmt()) {
            return List.of(asBlock(statement.asForStmt().getBody(), statement.asForStmt()::setBody));
        } else if (statement.isForEachStmt()) {
            return List.of(asBlock(statement.asForEachStmt().getBody(), statement.asForEachStmt()::setBody));
        } else if (statement.isWhileStmt()) {
            return List.of(asBlock(statement.asWhileStmt().getBody(), statement.asWhileStmt()::setBody));
        } else if (statement.isDoStmt()) {
            return List.of(asBlock(statement.asDoStmt().getBody(), statement.asDoStmt()::setBody));
        } else if (statement.isSynchronizedStmt()) {
            return List.of(statement.asSynchronizedStmt().getBody());
        } else if (statement.isLabeledStmt()) {
            LabeledStmt labeledStmt = statement.asLabeledStmt();
            // The label of a loop stays on the loop (a `continue <label>` requires it)
            if (isLoop(labeledStmt.getStatement())) {
                return List.of(labeledStmt.getStatement());
            }
            return List.of(asBlock(labeledStmt.getStatement(), labeledStmt::setStatement));
        } else if (statement.isSwitchStmt()) {
            List<Statement> entriesStatements = new ArrayList<>();
            statement.asSwitchStmt().getEntries().forEach(entry -> entriesStatements.addAll(entry.getStatements()));
            return entriesStatements;
        } else if (statement.isTryStmt()) {
            TryStmt tryStmt = statement.asTryStmt();
            List<Statement> blocks = new ArrayList<>(List.of(tryStmt.getTryBlock()));
            tryStmt.getCatchClauses().forEach(catchClause -> blocks.add(catchClause.getBody()));
            tryStmt.getFinallyBlock().ifPresent(blocks::add);
            return blocks;
        } else if (statement.isExpressionStmt() && !JavaParserUtils.isAssertionStatement(statement)) {
            // The block of a lambda expression passed to the statement (or assigned by it) is split, if it is the only
            // one at the level of the statement
            List<LambdaExpr> lambdaBlocks = statement.asExpressionStmt().getExpression().findAll(LambdaExpr.class,
                    lambdaExpr -> lambdaExpr.getBody().isBlockStmt() && isAtTheLevelOfTheStatement(lambdaExpr, statement));
            if (lambdaBlocks.size() == 1) {
                return List.of(lambdaBlocks.get(0).getBody());
            }
        }
        return null;
    }

    /**
     * Checks that an expression is not nested within another lambda expression or an anonymous class of the statement.
     */
    private static boolean isAtTheLevelOfTheStatement(Expression expr, Statement statement) {
        Node node = expr.getParentNode().orElse(null);
        while (node != null && node != statement) {
            if (node instanceof LambdaExpr || (node instanceof ObjectCreationExpr objectCreationExpr && objectCreationExpr.getAnonymousClassBody().isPresent())) {
                return false;
            }
            node = node.getParentNode().orElse(null);
        }
        return true;
    }

    private static boolean isLoop(Statement statement) {
        return statement.isForStmt() || statement.isForEachStmt() || statement.isWhileStmt() || statement.isDoStmt();
    }

    /**
     * Returns the body of a compound statement as a block, wrapping it in a new block (set with the given setter) if it
     * is a single statement.
     */
    private static Statement asBlock(Statement body, Consumer<Statement> setBody) {
        if (body.isBlockStmt()) {
            return body;
        }
        BlockStmt block = new BlockStmt();
        // Set the block first: the setter detaches the previous body, which is then added to the block
        setBody.accept(block);
        block.addStatement(body);
        return block;
    }

    /**
     * Generates the split test case and the split test prefix of a split point.
     *
     * @param source the test case to split
     * @param splitPointIndex the index of the split point among the statements of the test case (in pre-order)
     * @param splitIndex the index of the split test case
     * @return the split test case and its split test prefix
     */
    private static SplitTestCase splitAt(MethodDeclaration source, int splitPointIndex, int splitIndex) {
        MethodDeclaration splitTestCase = copy(source, splitIndex);
        truncateAfter(splitTestCase.getBody().orElseThrow().findAll(Statement.class).get(splitPointIndex), splitTestCase.getBody().orElseThrow());
        MethodDeclaration prefix = copy(source, splitIndex);
        Statement prefixSplitPoint = prefix.getBody().orElseThrow().findAll(Statement.class).get(splitPointIndex);
        truncateAfter(prefixSplitPoint, prefix.getBody().orElseThrow());
        removeSplitPoint(prefixSplitPoint);
        return new SplitTestCase(splitTestCase, prefix);
    }

    private static MethodDeclaration copy(MethodDeclaration source, int splitIndex) {
        MethodDeclaration copy = source.clone();
        copy.setName(source.getNameAsString() + SPLIT_DELIMITER + splitIndex);
        // With another name, the copy overrides no method of the superclass. A test case without a test annotation
        // (overriding a test case of the superclass) keeps the annotation: it is what marks it as a test case
        boolean hasTestAnnotation = copy.getAnnotations().stream()
                .anyMatch(annotation -> JUnitAnnotationType.isTestAnnotation(annotation.getName().getIdentifier()));
        if (hasTestAnnotation) {
            copy.getAnnotations().removeIf(annotation -> annotation.getName().getIdentifier().equals("Override"));
        }
        return copy;
    }

    /**
     * Removes the code that follows a split point, at every level from the split point up to the body of the test case.
     * The comments not attached to a statement (orphan comments) are removed as well, if they follow the split point in
     * the source code.
     */
    private static void truncateAfter(Statement splitPoint, BlockStmt body) {
        Optional<Position> splitPointEnd = splitPoint.getRange().map(range -> range.end);
        Node child = splitPoint;
        while (child != body) {
            Node parent = child.getParentNode().orElseThrow(() -> new IllegalStateException("The split point is not within the body of the test case."));
            if (parent instanceof BlockStmt block) {
                removeAfter(block.getStatements(), child);
                splitPointEnd.ifPresent(end -> removeOrphanCommentsAfter(block, end));
            } else if (parent instanceof SwitchEntry switchEntry) {
                removeAfter(switchEntry.getStatements(), child);
                splitPointEnd.ifPresent(end -> removeOrphanCommentsAfter(switchEntry, end));
            } else if (parent instanceof SwitchStmt switchStmt) {
                removeAfter(switchStmt.getEntries(), child);
            } else if (parent instanceof IfStmt ifStmt && ifStmt.getThenStmt() == child) {
                ifStmt.removeElseStmt();
            } else if (parent instanceof TryStmt tryStmt) {
                // The catch and finally blocks that follow are emptied, but kept: a try statement needs them
                boolean following = tryStmt.getTryBlock() == child;
                for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                    if (following) {
                        catchClause.setBody(new BlockStmt());
                    }
                    following |= catchClause == child;
                }
                if (following && tryStmt.getFinallyBlock().isPresent()) {
                    tryStmt.setFinallyBlock(new BlockStmt());
                }
            }
            child = parent;
        }
    }

    /**
     * Removes a split point, already the last statement of its block after the truncation. A split point that cannot
     * be removed (an empty block that is the body of a compound statement) is kept as it is.
     */
    private static void removeSplitPoint(Statement splitPoint) {
        Node parent = splitPoint.getParentNode().orElse(null);
        if (parent instanceof BlockStmt || parent instanceof SwitchEntry) {
            splitPoint.remove();
        }
    }

    private static void removeOrphanCommentsAfter(Node node, Position position) {
        for (Comment comment : new ArrayList<>(node.getOrphanComments())) {
            if (comment.getRange().map(range -> range.begin.isAfter(position)).orElse(false)) {
                node.removeOrphanComment(comment);
            }
        }
    }

    private static <N extends Node> void removeAfter(NodeList<N> nodes, Node child) {
        int index = indexOf(nodes, child);
        while (nodes.size() > index + 1) {
            nodes.remove(index + 1);
        }
    }

    /** Returns the index of a node within a list, comparing the nodes by reference. */
    private static int indexOf(List<? extends Node> nodes, Node node) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) {
                return i;
            }
        }
        throw new IllegalStateException("Node not found: " + node);
    }

    /**
     * Compares the number of assertions of the test case with the number of split points, according to the
     * {@code num-assertions-match} strategy. The numbers differ, for example, with AssertJ chains
     * ({@code assertThat(x).isEqualTo(1)}), counted as assertions but not split points.
     */
    private static void checkNumberOfAssertions(MethodDeclaration testCase, int numberOfSplitPoints, NumAssertionsMatchStrategyType numAssertionsMatch) {
        int numberOfAssertions = TestUtils.getNumberOfAssertions(testCase.getBody().orElseThrow().getStatements());
        if (numberOfAssertions != numberOfSplitPoints) {
            String errMsg = "Unexpected number of split test cases generated from test case " + testCase.getNameAsString() + ". Expected: " + numberOfAssertions + ", Found: " + numberOfSplitPoints;
            if (numAssertionsMatch == NumAssertionsMatchStrategyType.STRICT) {
                throw new IllegalStateException(errMsg);
            } else if (numAssertionsMatch == NumAssertionsMatchStrategyType.LOOSE) {
                logger.warn(errMsg);
            }
        }
    }
}
