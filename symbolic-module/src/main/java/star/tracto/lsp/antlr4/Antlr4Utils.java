package star.tracto.lsp.antlr4;

import org.antlr.v4.runtime.*;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;
import star.tracto.lsp.antlr4.antlr4Class.JavaParserListener;
import star.tracto.lsp.antlr4.antlr4Class.JavaParserBaseListener;


import java.util.ArrayList;
import java.util.List;

public class Antlr4Utils {

    /**
     * Checks the syntax of a snippet and returns syntax errors and next possible tokens.
     *
     * @param snippet The Java snippet to check.
     * @return A list of syntax errors or suggestions for next tokens.
     */
    public static List<String> checkSyntax(String snippet) {
        CharStream charStream = CharStreams.fromString(snippet);

        // Initialize lexer and parser
        JavaLexer lexer = new JavaLexer(charStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokenStream);

        // Add custom error listener
        SyntaxErrorListener errorListener = new SyntaxErrorListener();
        parser.removeErrorListeners(); // Remove default error listeners
        parser.addErrorListener(errorListener);

        try {
            parser.methodCall(); // Call the entry rule
        } catch (Exception e) {
            // Ignore exceptions as they are handled by the listener
        }

        return errorListener.getErrors().isEmpty() ? errorListener.getNextTokens() : errorListener.getErrors();
    }

    /**
     * Returns a list of next possible tokens for a given snippet.
     *
     * @param snippet The Java snippet to analyze.
     * @return A list of next possible tokens.
     */
    public static List<String> getNextPossibleTokens(String snippet) {
        CharStream charStream = CharStreams.fromString(snippet);

        // Initialize lexer and parser
        JavaLexer lexer = new JavaLexer(charStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokenStream);

        // Add custom error listener
        SyntaxErrorListener errorListener = new SyntaxErrorListener();
        parser.removeErrorListeners(); // Remove default error listeners
        parser.addErrorListener(errorListener);

        try {
            parser.methodCall(); // Call the entry rule
        } catch (Exception e) {
            // Ignore exceptions as they are handled by the listener
        }

        return errorListener.getNextTokens();
    }

    /**
     * Tokenizes a Java snippet and returns a list of tokens.
     *
     * @param snippet The Java code snippet to tokenize.
     * @return A list of token strings.
     */
    public static List<String> tokenizeSnippet(String snippet) {
        List<String> tokens = new ArrayList<>();

        // Create a CharStream from the snippet
        CharStream charStream = CharStreams.fromString(snippet);

        // Initialize the Java lexer
        JavaLexer lexer = new JavaLexer(charStream);

        // Tokenize the snippet
        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            tokens.add(token.getText());
        }

        return tokens;
    }

    public static List<String> getNextPossibleTokensWithContext(String snippet, String context) {
        // Combine snippet with context to enhance predictions
        String combinedInput = context + "\n" + snippet;

        // Create CharStream and set up lexer/parser
        CharStream charStream = CharStreams.fromString(combinedInput);
        JavaLexer lexer = new JavaLexer(charStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokenStream);

        // Add error listener for token predictions
        SyntaxErrorListener errorListener = new SyntaxErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        try {
            // Attempt to parse the snippet using the main rule
            parser.methodCall();
        } catch (Exception ignored) {
            // Errors are handled by the error listener
        }

        // Return the next possible tokens
        return errorListener.getNextTokens();
    }

    /**
     * Given a literal token type, return its corresponding Java type.
     */
    public static String getLiteralType(String literalTokenType) {
        switch (literalTokenType) {
            case "DECIMAL_LITERAL":
            case "HEX_LITERAL":
            case "OCT_LITERAL":
            case "BINARY_LITERAL":
                return "int";
            case "FLOAT_LITERAL":
            case "HEX_FLOAT_LITERAL":
                return "float";
            case "BOOL_LITERAL":
                return "boolean";
            case "CHAR_LITERAL":
                return "char";
            case "STRING_LITERAL":
                return "String";
            default:
                return "Object";
        }
    }

}
