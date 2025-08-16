package star.tracto.lsp.antlr4.grammars;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.*;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;

import java.util.*;

public class JavaCompletionSimple {

    public static void main(String[] args) throws Exception {
        // 1. Create lexer and token stream
        CharStream input = CharStreams.fromString("assertAll((t");
        JavaLexer lexer = new JavaLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill(); // fill buffer

        // 2. Pass 1: parse to inspect the tree
        JavaParser parser1 = new JavaParser(tokens);
        ParserRuleContext tree = parser1.assertAllMethodCall();
        System.out.println("Parse tree:");
        printContextTree(tree, 0);

        // 3. Reset stream for second pass
        tokens.seek(0);

        // 4. Pass 2: fresh parser for code completion
        JavaParser parser2 = new JavaParser(tokens);
        tree = parser2.assertAllMethodCall();

        Set<Integer> preferredRules = new HashSet<>();
        Set<Integer> ignoredTokens = new HashSet<>();
        CodeCompletionCore core = new CodeCompletionCore(parser2, preferredRules, ignoredTokens);

        // IMPORTANT: caret is at tokens.size() - 1 (end of last token)
        CodeCompletionCore.CandidatesCollection candidates =
                core.collectCandidates(tokens.size() - 1, tree);

        // 5. Print results
        System.out.println("Candidates:");
        for (int t : candidates.tokens.keySet()) {
            String display = parser2.getVocabulary().getDisplayName(t);
            System.out.println("  " + display);
        }
    }

    private static void printContextTree(ParserRuleContext ctx, int indent) {
        String rule = JavaParser.ruleNames[ctx.getRuleIndex()];
        System.out.println("  ".repeat(indent) + rule);
        for (ParseTree child : ctx.children) {
            if (child instanceof ParserRuleContext) {
                printContextTree((ParserRuleContext) child, indent + 1);
            }
        }
    }
}
