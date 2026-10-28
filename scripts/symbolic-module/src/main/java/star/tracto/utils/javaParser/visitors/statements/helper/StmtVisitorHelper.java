package star.tracto.utils.javaParser.visitors.statements.helper;

import com.github.javaparser.ast.stmt.*;
import star.tracto.utils.javaParser.visitors.statements.*;

import java.util.List;

public class StmtVisitorHelper {
    public static List<ExpressionStmt> getAllExpressionStmts(Statement statement) {
        ExpressionStmtVisitor expressionStmtCollector = new ExpressionStmtVisitor();
        return expressionStmtCollector.visit(statement);
    }
}
