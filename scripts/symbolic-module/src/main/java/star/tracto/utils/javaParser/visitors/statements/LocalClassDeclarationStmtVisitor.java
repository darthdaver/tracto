package star.tracto.utils.javaParser.visitors.statements;

import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import star.tracto.utils.javaParser.visitors.base.BaseStmtVisitor;

import java.util.List;

/**
 * The class visit a node (and all its descendants within the AST) and collects all the
 * {@link LocalClassDeclarationStmt} found.
 *
 */
public class LocalClassDeclarationStmtVisitor extends BaseStmtVisitor<LocalClassDeclarationStmt> {

    /**
     * Visit a node and collects all the local class declaration statements found within is AST.
     *
     * @param localClassDeclarationStmt the type of node to collect, anytime it is found within the AST of the analyzed node.
     * @param collection the collection to add any occurrence of the searched node found.
     */
    @Override
    public void visit(LocalClassDeclarationStmt localClassDeclarationStmt, List<LocalClassDeclarationStmt> collection) {
        addToCollection(localClassDeclarationStmt, collection);
        super.visit(localClassDeclarationStmt, collection);
    }
}
