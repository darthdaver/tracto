package star.tracto.preprocessing;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import star.tracto.preprocessing.components.Assignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Represents the active oracle placeholder as a statement for syntactic scope analysis. */
public final class OracleInsertion {
    public static final String MASK = "/*<MASK_PLACEHOLDER>*/";
    private static final String NAME = "__tracto_oracle__";
    private static final String MARKER = NAME + "()";

    private OracleInsertion() {}

    public static BlockStmt parseBody(String proposal) {
        return StaticJavaParser.parseBlock("{\n" + parseable(proposal) + "\n}");
    }

    public static String parseable(String source) {
        int index = source.indexOf(MASK);
        if (index < 0 || source.indexOf(MASK, index + MASK.length()) >= 0 || source.contains(NAME)) {
            throw new IllegalArgumentException("Expected exactly one oracle placeholder");
        }
        String suffix = source.substring(index + MASK.length());
        return source.replace(MASK, suffix.stripLeading().startsWith(";") ? MARKER : MARKER + ";");
    }

    public static ExpressionStmt position(BlockStmt body) {
        List<MethodCallExpr> markers = body.findAll(MethodCallExpr.class,
                call -> call.getNameAsString().equals(NAME));
        if (markers.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one oracle placeholder in the body");
        }
        MethodCallExpr marker = markers.getFirst();
        ExpressionStmt statement = marker.findAncestor(ExpressionStmt.class).orElseThrow(
                () -> new IllegalArgumentException("Oracle placeholder must be an assertion or a local initializer"));
        var expression = statement.getExpression();
        if (expression != marker && !(expression instanceof VariableDeclarationExpr declaration
                && declaration.getVariables().size() == 1
                && declaration.getVariable(0).getInitializer().orElse(null) == marker)) {
            throw new IllegalArgumentException("Oracle placeholder must be an assertion or a local initializer");
        }
        if (statement.getParentNode().filter(parent -> parent instanceof BlockStmt || parent instanceof SwitchEntry).isEmpty()) {
            throw new IllegalArgumentException("Oracle placeholder must be inside a block or switch entry");
        }
        return statement;
    }

    public static Assignment assignment(ExpressionStmt position) {
        var expression = position.getExpression();
        if (!(expression instanceof VariableDeclarationExpr declaration)) return null;
        var variable = declaration.getVariable(0);
        List<String> modifiers = new ArrayList<>();
        declaration.getAnnotations().forEach(annotation -> modifiers.add(annotation.toString()));
        declaration.getModifiers().forEach(modifier -> modifiers.add(modifier.getKeyword().asString()));
        return new Assignment(modifiers, variable.getType().toString(), variable.getNameAsString());
    }

    public static String masked(String source) {
        return source.replace(MARKER, MASK);
    }

    public static void insert(ExpressionStmt position, String oracle) {
        if (!position.replace(StaticJavaParser.parseStatement(oracle))) {
            throw new IllegalStateException("Could not insert the generated oracle");
        }
    }

    /** Checks that existing statements keep their order and scope while allowing new statements. */
    public static boolean extendsBody(BlockStmt existing, BlockStmt proposed) {
        return preserves(existing, proposed);
    }

    private static boolean preserves(Node existing, Node proposed) {
        if (existing.getClass() != proposed.getClass()) return false;
        for (var property : existing.getMetaModel().getAllPropertyMetaModels()) {
            if (property.is("comment")) continue;
            Object before = property.getValue(existing);
            Object after = property.getValue(proposed);
            if (property.is("elseStmt") && before == null) continue;
            if (property.is("statements")) {
                if (!preservesStatements((NodeList<?>) before, (NodeList<?>) after)) return false;
            } else if (before instanceof Node beforeNode && after instanceof Node afterNode) {
                if (!preserves(beforeNode, afterNode)) return false;
            } else if (before instanceof NodeList<?> beforeList && after instanceof NodeList<?> afterList) {
                if (beforeList.size() != afterList.size()) return false;
                for (int i = 0; i < beforeList.size(); i++) {
                    if (!preserves(beforeList.get(i), afterList.get(i))) return false;
                }
            } else if (!Objects.equals(before, after)) {
                return false;
            }
        }
        return true;
    }

    private static boolean preservesStatements(NodeList<?> existing, NodeList<?> proposed) {
        int matched = 0;
        for (Node candidate : proposed) {
            if (matched < existing.size() && preserves(existing.get(matched), candidate)) matched++;
        }
        return matched == existing.size();
    }

    public static String contents(BlockStmt body) {
        String source = body.toString();
        return source.substring(1, source.length() - 1).strip();
    }
}
