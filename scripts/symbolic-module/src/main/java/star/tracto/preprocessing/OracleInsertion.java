package star.tracto.preprocessing;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.nodeTypes.NodeWithStatements;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import star.tracto.preprocessing.components.Assignment;

import java.util.ArrayList;
import java.util.List;

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

    static BlockStmt withoutOracle(BlockStmt proposed) {
        BlockStmt body = proposed.clone();
        ExpressionStmt placeholder = position(body);
        if (placeholder.getExpression() instanceof VariableDeclarationExpr declaration) {
            var variable = declaration.getVariable(0);
            var type = variable.getType();
            if (type.isVarType()) {
                throw new IllegalArgumentException("Use an explicit type for a local receiving the oracle result");
            }
            String value = !type.isPrimitiveType() ? "null" : type.asString().equals("boolean") ? "false" : "0";
            variable.setInitializer(StaticJavaParser.parseExpression(value));
        } else {
            placeholder.replace(new EmptyStmt());
        }
        return body;
    }

    /** Describes each statement list and the numbered boundaries the model can select. */
    public static String insertionPoints(BlockStmt body) {
        var blocks = statementLists(body);
        var description = new StringBuilder();
        for (int block = 0; block < blocks.size(); block++) {
            var container = blocks.get(block);
            var statements = container.getStatements();
            description.append("Block ").append(block).append(" (").append(blockDescription(container))
                    .append("), slot 0 before its first statement\n");
            for (int index = 0; index < statements.size(); index++) {
                var statement = statements.get(index);
                description.append("  slot ").append(index + 1).append(" after ")
                        .append(statement.toString().lines().findFirst().orElse(""));
                if (statement instanceof IfStmt conditional && conditional.getElseStmt().isEmpty()) {
                    description.append(" (may add ELSE)");
                }
                description.append('\n');
            }
        }
        return description.toString();
    }

    private static String blockDescription(NodeWithStatements<?> container) {
        if (container instanceof SwitchEntry entry) return entry.toString().lines().findFirst().orElse("switch case");
        var block = (BlockStmt) container;
        var parent = block.getParentNode().orElse(null);
        if (parent instanceof IfStmt conditional) {
            return (conditional.getThenStmt() == block ? "if " : "else for if ") + conditional.getCondition();
        }
        return parent == null ? "test body" : parent.toString().lines().findFirst().orElse("");
    }

    /** Applies an insertion to a copy, leaving all accepted statements intact. */
    public static BlockStmt applyProposal(BlockStmt accepted, String proposal) {
        String[] lines = proposal.split("\\R", 2);
        String[] address = lines[0].strip().split("\\s+");
        boolean addElse = address.length == 4 && "ELSE".equals(address[3]);
        if (lines.length != 2 || !(address.length == 3 || addElse) || !"AT".equals(address[0])) {
            throw new IllegalArgumentException("Start the proposal with AT <block> <slot>, then give only new Java");
        }
        int block = Integer.parseInt(address[1]);
        int slot = Integer.parseInt(address[2]);
        BlockStmt merged = accepted.clone();
        var blocks = statementLists(merged);
        if (block < 0 || block >= blocks.size() || slot < 0 || slot > blocks.get(block).getStatements().size()) {
            throw new IllegalArgumentException("Insertion point is out of range: " + lines[0]);
        }
        var statements = blocks.get(block).getStatements();
        var added = parseBody(lines[1]);
        if (addElse) {
            if (slot == 0 || !(statements.get(slot - 1) instanceof IfStmt conditional)
                    || conditional.getElseStmt().isPresent()) {
                throw new IllegalArgumentException("ELSE requires a slot immediately after an if without an else");
            }
            conditional.setElseStmt(added);
        } else {
            for (var statement : added.getStatements()) {
                statements.add(slot++, statement.clone());
            }
        }
        position(merged);
        return merged;
    }

    private static List<NodeWithStatements<?>> statementLists(BlockStmt body) {
        var blocks = new ArrayList<NodeWithStatements<?>>();
        body.stream().forEach(node -> {
            if (node instanceof BlockStmt block) blocks.add(block);
            else if (node instanceof SwitchEntry entry && entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                blocks.add(entry);
            }
        });
        return blocks;
    }

    public static String contents(BlockStmt body) {
        String source = body.toString();
        return source.substring(1, source.length() - 1).strip();
    }
}
