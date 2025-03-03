package star.tracto.data;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;

public enum BlockStatementsType {
    DO(DoStmt.class),
    CATCH(IfStmt.class),
    IF_ELSE(IfStmt.class),
    ELSE(IfStmt.class),
    FINALLY(TryStmt.class),
    FOR_EACH(ForEachStmt.class),
    FOR(ForStmt.class),
    IF(IfStmt.class),
    METHOD_BODY(BlockStmt.class),
    SWITCH(SwitchStmt.class),
    TRY(TryStmt.class),
    WHILE(WhileStmt.class);

    private final Class<?> stmtClass;

    BlockStatementsType(Class<?> stmtClass) {
        this.stmtClass = stmtClass;
    }

    public Class<?> getJavaParserStmtClass() {
        return stmtClass;
    }
}
