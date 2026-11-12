package star.tracto.preprocessing.utils;

import com.github.javaparser.resolution.MethodUsage;
import star.tracto.data.enums.AntlrToken;
import star.tracto.lsp.antlr4.Antlr4Utils;

import java.util.List;
import java.util.Set;

/**
 * The void methods in the candidate tokens (Step 39). A call of a void method has no value: in an assertion it is valid
 * only as the body of an expression lambda ({@code () -> clock.stop()}, the end of the receiver chain of the body) or
 * as the name of a method reference ({@code clock::stop}). Everywhere else (an argument, an operand, the receiver of a
 * member) the void methods are not offered.
 */
public final class VoidCalls {

    /** The keywords of a receiver chain: {@code this.}, {@code super.}, {@code new X().}, {@code X.class.}. */
    private static final Set<String> CHAIN_KEYWORDS = Set.of("this", "super", "new", "class");

    private VoidCalls() {
    }

    /**
     * Whether a call of a void method is valid after the given tokens of the partial oracle. Walking back from the end:
     * the groups between parentheses, brackets and angle brackets (type arguments) are skipped; the identifiers, the
     * literals, the dots and the keywords of a chain continue the chain; an arrow ends it (the body of a lambda); any
     * other token (an open parenthesis or bracket, a comma, an operator) means that the call is a value. When in doubt
     * the void methods are kept: a greater-than sign is read as the end of type arguments.
     */
    public static boolean allowed(List<String> previous) {
        if (!previous.isEmpty() && previous.get(previous.size() - 1).equals("::")) {
            return true;
        }
        int depth = 0;
        int angles = 0;
        for (int j = previous.size() - 1; j >= 0; j--) {
            String token = previous.get(j);
            if (token.equals(")") || token.equals("]")) {
                depth++;
                continue;
            }
            if (token.equals("(") || token.equals("[")) {
                if (depth == 0) {
                    return false;
                }
                depth--;
                continue;
            }
            if (depth > 0) {
                continue;
            }
            if (!token.isEmpty() && token.chars().allMatch(c -> c == '>')) {
                angles += token.length();
                continue;
            }
            if (angles > 0) {
                if (token.equals("<")) {
                    angles--;
                }
                continue;
            }
            if (token.equals("->")) {
                return true;
            }
            if (token.equals(".") || CHAIN_KEYWORDS.contains(token) || isIdentifierOrLiteral(token)) {
                continue;
            }
            return false;
        }
        return false;
    }

    /** Whether all the methods of a name are void (not when one of them is not, nor when a return type is unresolved). */
    public static boolean allVoid(List<MethodUsage> methods) {
        if (methods == null || methods.isEmpty()) {
            return false;
        }
        for (MethodUsage method : methods) {
            try {
                if (!method.returnType().isVoid()) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierOrLiteral(String token) {
        try {
            AntlrToken type = Antlr4Utils.getAntlrToken(token);
            return type == AntlrToken.IDENTIFIER || type.name().endsWith("_LITERAL");
        } catch (RuntimeException e) {
            return false;
        }
    }
}
