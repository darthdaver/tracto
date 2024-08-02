package star.tratto.utils.javaParser.visitors;

import com.github.javaparser.ast.stmt.AssertStmt;
import java.util.List;

/**
 * The class visit a node (and all its descendants within the AST) and collects all the
 * {@link com.github.javaparser.ast.stmt.AssertStmt} found.
 *
 */
public class AssertStmtVisitor extends BaseStmtVisitor<AssertStmt> {

    /**
     * Visit a node and collects all the assert statements found within is AST.
     *
     * @param assertStmt the type of node to collect, anytime it is found within the AST of the analyzed node.
     * @param collection the collection to add any occurrence of the searched node found.
     */
    @Override
    public void visit(AssertStmt assertStmt, List<AssertStmt> collection) {
        super.visit(assertStmt, collection);
        addToCollection(assertStmt, collection);
    }
}
