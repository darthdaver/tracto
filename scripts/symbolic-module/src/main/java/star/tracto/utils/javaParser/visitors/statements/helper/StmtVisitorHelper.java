package star.tracto.utils.javaParser.visitors.statements.helper;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.*;
import star.tracto.utils.javaParser.visitors.expressions.MethodCallExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.ObjectCreationExprVisitor;
import star.tracto.utils.javaParser.visitors.statements.*;

import java.util.List;
import java.util.Optional;

public class StmtVisitorHelper {
    public static Optional<Expression> getLastExprInStmt(Statement statement, Class<?> type) {
        List<Expression> exprs;
        if (type.equals(MethodCallExpr.class)) {
            MethodCallExprVisitor methodCallExprVisitor = new MethodCallExprVisitor();
            exprs = methodCallExprVisitor.visit(statement).stream().map(Expression.class::cast).toList();
        } else if (type.equals(ObjectCreationExpr.class)) {
            ObjectCreationExprVisitor objectCreationExprVisitor = new ObjectCreationExprVisitor();
            exprs = objectCreationExprVisitor.visit(statement).stream().map(Expression.class::cast).toList();
        } else {
            throw new IllegalStateException("Unexpected expression type");
        }
        if (exprs.size() > 0) {
            return Optional.of(exprs.get(exprs.size() - 1));
        }
        return Optional.empty();
    }

    public static List<ExpressionStmt> getAllExpressionStmts(Statement statement) {
        ExpressionStmtVisitor expressionStmtCollector = new ExpressionStmtVisitor();
        return expressionStmtCollector.visit(statement);
    }
}
