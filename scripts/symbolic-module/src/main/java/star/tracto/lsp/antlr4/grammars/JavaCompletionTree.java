package star.tracto.lsp.antlr4.grammars;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;

import java.util.*;

public class JavaCompletionTree {

    public static void main(String[] args) throws Exception {
        // 1. Create lexer and token stream
        String code = "assertAll((t";
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(code));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        // 2. Pass 1: parse to inspect the tree
        JavaParser parser = new JavaParser(tokens);
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ParserRuleContext ctx = parser.assertAllMethodCall();
        List<ParserRuleContext> chain = new ArrayList<>();
        chain.add(ctx);
        for (ParseTree child : ctx.children) {
            if (child instanceof ParserRuleContext) {
                chain.add((ParserRuleContext) child);
            }
        } // root to leaf

        // 3. Reset stream for second pass
        tokens.seek(0);

        // 4. Pass 2: fresh parser for code completion
        JavaParser parser2 = new JavaParser(tokens);
        ctx = parser2.assertAllMethodCall();

        Set<Integer> preferredRules = new HashSet<>();
        Set<Integer> ignoredTokens = new HashSet<>();
        CodeCompletionCore core = new CodeCompletionCore(parser2, preferredRules, ignoredTokens);

        System.out.println("Candidate tokens/rules at each context:");
        for (ParserRuleContext c : chain) {
            String rule = JavaParser.ruleNames[c.getRuleIndex()];
            System.out.println("\n=== " + rule + " ===");

            // index where this rule starts
            int index = c.getStart().getTokenIndex();

            CodeCompletionCore.CandidatesCollection cand = core.collectCandidates(index, ctx);

            System.out.println("Tokens:");
            for (Integer t : cand.tokens.keySet()) {
                System.out.println("  " + parser.getVocabulary().getDisplayName(t));
            }
            System.out.println("Rules:");
            for (Integer r : cand.rules.keySet()) {
                System.out.println("  " + parser.getRuleNames()[r]);
            }
        }
    }
}