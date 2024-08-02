package star.tratto.utils.javaParser.visitors;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;

/**
 * The class visit a node (and all its descendants within the AST) and collects all the
 * {@link com.github.javaparser.ast.expr.MethodCallExpr} found.
 *
 */
public class MethodCallExprVisitor extends BaseExprVisitor<MethodCallExpr> {

    /**
     * Visit a node and collects all the method call expressions found within is AST.
     *
     * @param MethodCallExprStmt the type of node to collect, anytime it is found within the AST of the analyzed node.
     * @param collection the collection to add any occurrence of the searched node found.
     */
    @Override
    public void visit(MethodCallExpr MethodCallExprStmt, List<MethodCallExpr> collection) {
        super.visit(MethodCallExprStmt, collection);
        addToCollection(MethodCallExprStmt, collection);
    }
}