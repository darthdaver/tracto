package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.*;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The declarations in scope at the position of the target of a split test case, within its split test prefix.
 *
 * <p>The split test prefix ({@code ...STARSplitTestPrefix.java}) is the split test case ({@code ...STARSplitTest.java})
 * without its target, which was the last statement of its block (the splitter removes everything that follows it).
 * The prefix alone does not tell where the target was: a target at the end of a loop body and a target right after the
 * loop leave the same prefix. The target is therefore located in the split test case (as the oracles stage does: the
 * last assertion), and its block is mapped to the prefix by its index among the statements of the method in pre-order:
 * the statements that precede the target are the same in both methods.</p>
 *
 * <p>The declarations in scope at the end of that block are the ones a Java compiler would accept there: the local
 * variables declared before the target in each enclosing block (or switch block), the variables of the enclosing
 * {@code for} initializations, for-each statements and try-with-resources, the parameters of the enclosing catch
 * clauses, lambda expressions and methods (up to the test method). Not supported yet: the pattern variables
 * ({@code x instanceof Foo foo}).</p>
 */
final class TargetScope {

    /**
     * The declarations in scope, the innermost blocks first (within a block, in the order of the source code).
     *
     * @param variables the local variables (including for-each and resource variables)
     * @param parameters the parameters of the enclosing catch clauses, lambda expressions and methods
     */
    record Declarations(List<VariableDeclarator> variables, List<Parameter> parameters) {}

    private TargetScope() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Locates the position of the target in the split test prefix: the block (or the switch entry) of the prefix that
     * contained the target, at its end.
     *
     * @param splitTestCase the split test case, ending with its target
     * @param prefix the split test prefix, the same method without the target
     * @return the block or switch entry of the prefix that contained the target, or an empty optional if the split test
     * case has no assertion or the two methods do not match (the statements before the target differ)
     */
    static Optional<Node> targetPosition(MethodDeclaration splitTestCase, MethodDeclaration prefix) {
        if (splitTestCase.getBody().isEmpty() || prefix.getBody().isEmpty()) {
            return Optional.empty();
        }
        Optional<Statement> target = JavaParserUtils.getLastAssertionInMethodDeclaration(splitTestCase);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        Node container = target.get().getParentNode().orElse(null);
        if (!(container instanceof BlockStmt || container instanceof SwitchEntry)) {
            // The splitter cannot remove such a target (for example the body of a loop that is not a block)
            return Optional.empty();
        }
        List<Statement> splitStatements = splitTestCase.getBody().get().findAll(Statement.class);
        List<Statement> prefixStatements = prefix.getBody().get().findAll(Statement.class);
        int targetIndex = indexOf(splitStatements, target.get());
        int targetSize = target.get().findAll(Statement.class).size();
        // The prefix has the statements of the split test case except the target (and the statements within it)
        if (targetIndex < 0 || prefixStatements.size() != splitStatements.size() - targetSize) {
            return Optional.empty();
        }
        for (int i = 0; i < targetIndex; i++) {
            if (splitStatements.get(i).getClass() != prefixStatements.get(i).getClass()) {
                return Optional.empty();
            }
        }
        if (container instanceof BlockStmt block) {
            return Optional.of(prefixStatements.get(indexOf(splitStatements, block)));
        }
        // A switch entry is not a statement: map its switch statement, then the entry by its index
        SwitchEntry entry = (SwitchEntry) container;
        if (!(entry.getParentNode().orElse(null) instanceof SwitchStmt switchStmt)) {
            return Optional.empty();
        }
        Statement prefixSwitch = prefixStatements.get(indexOf(splitStatements, switchStmt));
        int entryIndex = switchStmt.getEntries().indexOf(entry);
        if (!(prefixSwitch instanceof SwitchStmt prefixSwitchStmt) || entryIndex >= prefixSwitchStmt.getEntries().size()) {
            return Optional.empty();
        }
        return Optional.of(prefixSwitchStmt.getEntry(entryIndex));
    }

    /**
     * Collects the declarations in scope at the end of the given block or switch entry, innermost first, up to the
     * parameters of the enclosing method declared in a class (the methods of anonymous and local classes are crossed:
     * the variables of the enclosing method are visible within them).
     *
     * @param position the block or switch entry at whose end the declarations are collected
     * @return the declarations in scope at the position
     */
    static Declarations declarationsInScope(Node position) {
        List<VariableDeclarator> variables = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        Node child = null;
        Node node = position;
        while (node != null) {
            if (node instanceof BlockStmt block) {
                addDeclarationsBefore(block.getStatements(), child, variables);
            } else if (node instanceof SwitchEntry entry) {
                addDeclarationsBefore(entry.getStatements(), child, variables);
            } else if (node instanceof SwitchStmt switchStmt) {
                // The scope of a declaration in a switch block is the rest of the switch block: the previous entries
                List<SwitchEntry> entries = switchStmt.getEntries();
                for (int i = entries.indexOf(child) - 1; i >= 0; i--) {
                    addDeclarationsBefore(entries.get(i).getStatements(), null, variables);
                }
            } else if (node instanceof ForStmt forStmt && child == forStmt.getBody()) {
                for (Expression initialization : forStmt.getInitialization()) {
                    if (initialization.isVariableDeclarationExpr()) {
                        variables.addAll(initialization.asVariableDeclarationExpr().getVariables());
                    }
                }
            } else if (node instanceof ForEachStmt forEachStmt && child == forEachStmt.getBody()) {
                variables.addAll(forEachStmt.getVariable().getVariables());
            } else if (node instanceof TryStmt tryStmt && child == tryStmt.getTryBlock()) {
                for (Expression resource : tryStmt.getResources()) {
                    if (resource.isVariableDeclarationExpr()) {
                        variables.addAll(resource.asVariableDeclarationExpr().getVariables());
                    }
                }
            } else if (node instanceof CatchClause catchClause && child == catchClause.getBody()) {
                parameters.add(catchClause.getParameter());
            } else if (node instanceof LambdaExpr lambdaExpr) {
                parameters.addAll(lambdaExpr.getParameters());
            } else if (node instanceof MethodDeclaration methodDeclaration) {
                parameters.addAll(methodDeclaration.getParameters());
            } else if (node instanceof TypeDeclaration<?> && !(node.getParentNode().orElse(null) instanceof LocalClassDeclarationStmt
                    || node.getParentNode().orElse(null) instanceof LocalRecordDeclarationStmt)) {
                // A class member (the test class): its fields are collected separately
                break;
            }
            child = node;
            node = node.getParentNode().orElse(null);
        }
        return new Declarations(variables, parameters);
    }

    /**
     * Adds the variables declared by the statements that precede the given child (all of them if the child is
     * {@code null}), in the order of the source code (a block cannot declare the same name twice).
     */
    private static void addDeclarationsBefore(List<Statement> statements, Node child, List<VariableDeclarator> variables) {
        for (Statement statement : statements) {
            if (statement == child) {
                break;
            }
            if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                variables.addAll(statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables());
            }
        }
    }

    /** Returns the index of a node within a list, comparing the nodes by reference, or -1. */
    private static int indexOf(List<? extends Node> nodes, Node node) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) {
                return i;
            }
        }
        return -1;
    }
}
