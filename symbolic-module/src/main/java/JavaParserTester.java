
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.IntervalSet;
import star.tracto.data.enums.AntlrTokenMapper;
import star.tracto.lsp.antlr4.SyntaxErrorListener;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JavaParserTester {

    public static void main(String[] args) {
            // Incomplete input: missing "(" and the rest
            String s = "assertAll(t";

            CharStream charStream = CharStreams.fromString(s);

            // Initialize lexer and parser
            JavaLexer lexer = new JavaLexer(charStream);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            JavaParser parser = new JavaParser(tokenStream);

            // Custom error listener that prints expected tokens
            parser.removeErrorListeners();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer,
                                        Object offendingSymbol,
                                        int line, int charPositionInLine,
                                        String msg,
                                        RecognitionException e) {
                    // Safely retrieve expected tokens at the point of error
                    IntervalSet expected = ((Parser) recognizer).getExpectedTokens();
                    Vocabulary vocab = ((Parser) recognizer).getVocabulary();

                    System.out.println("Syntax error at " + line + ":" + charPositionInLine);
                    System.out.println("Message: " + msg);
                    System.out.println("Expected tokens: " + expected.toString(vocab));
                }
            });

            // Optionally, fail fast without recovery:
            // parser.setErrorHandler(new BailErrorStrategy());

            try {
                JavaParser.AssertAllMethodCallContext ctx = parser.assertAllMethodCall();
                System.out.println("Parse finished. Context = " + ctx.toStringTree(parser));
            } catch (Exception e) {
                System.out.println("Exception during parse: " + e);
            }
        }
}

