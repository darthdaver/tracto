import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import com.vmware.antlr4c3.CodeCompletionCore;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;

public class Antlr4C3Tester {

    public static void main(String[] args) {
        String code = "assertAll";//"assertAll(() -> true);";
        CharStream input = CharStreams.fromString(code);
        JavaLexer lexer = new JavaLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        JavaParser parserTokens = new JavaParser(tokens);

        // Parse as much as possible
        ParserRuleContext tree = parserTokens.assertAllMethodCall();

        // Pass null for preferredRules and ignoredTokens
        CodeCompletionCore core = new CodeCompletionCore(parserTokens, null, null);

        // Position: index of last token
        int caretTokenIndex = tokens.size() - 1;

        CodeCompletionCore.CandidatesCollection candidates =
                core.collectCandidates(caretTokenIndex, tree);

        System.out.println("Tokens:");
        for (Integer tokenType : candidates.tokens.keySet()) {
            System.out.println("  " + parserTokens.getVocabulary().getDisplayName(tokenType));
        }

        // Reset stream for second pass
        tokens.seek(0);
        JavaParser parserRules = new JavaParser(tokens);
        tree = parserRules.assertAllMethodCall();

        System.out.println("Parse tree:");
        printContextTree(tree, 0);
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
