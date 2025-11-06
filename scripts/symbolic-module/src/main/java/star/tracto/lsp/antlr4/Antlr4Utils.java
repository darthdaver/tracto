package star.tracto.lsp.antlr4;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;
import org.javatuples.Pair;
import star.tracto.data.enums.*;
import star.tracto.lsp.antlr4.antlr4Class.JavaLexer;
import star.tracto.lsp.antlr4.antlr4Class.JavaParser;
import star.tracto.lsp.antlr4.antlr4Class.JavaParserListener;
import star.tracto.lsp.antlr4.antlr4Class.JavaParserBaseListener;
import star.tracto.preprocessing.utils.TokensUtils;


import javax.swing.text.html.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Antlr4Utils {

    public static AntlrToken getAntlrToken(String token) {
        CharStream input = CharStreams.fromString(token);

        JavaLexer lexer = new JavaLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokenStream);
        String tokenSymbolName = parser.getVocabulary().getSymbolicName(parser.getCurrentToken().getType());

        try {
            return AntlrToken.valueOf(tokenSymbolName);
        } catch (IllegalArgumentException e) {
            // Not a JUnit assertion
            if (Arrays.stream(JUnitAssertionType.values()).anyMatch(assertionType -> assertionType.name().equals(tokenSymbolName))) {
                return AntlrToken.IDENTIFIER;
            } else {
                throw new IllegalArgumentException("No enum constant for symbol: " + tokenSymbolName);
            }
        }
    }

    public static List<String> parseSnippet(JUnitAssertionType assertionType, String snippet, long timeoutSecs) throws Exception {
        // Initialize lexer and parser
        CharStream input = CharStreams.fromString(snippet);
        JavaLexer lexer = new JavaLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokenStream);
        Optional<ParserRuleContext> ctx = Optional.empty();
        switch (assertionType) {
            case ASSERT_ALL:
                ctx = Optional.of(parser.assertAllMethodCall());
                break;
            case ASSERT_ARRAY_EQUALS:
                ctx = Optional.of(parser.assertArrayEqualsMethodCall());
                break;
            case ASSERT_EQUALS:
                ctx = Optional.of(parser.assertEqualsMethodCall());
                break;
            case ASSERT_FALSE:
                ctx = Optional.of(parser.assertFalseMethodCall());
                break;
            case ASSERT_ITERABLE_EQUALS:
                ctx = Optional.of(parser.assertIterableEqualsMethodCall());
                break;
            case ASSERT_LINES_MATCH:
                ctx = Optional.of(parser.assertLinesMatchMethodCall());
                break;
            case ASSERT_NOT_EQUALS:
                ctx = Optional.of(parser.assertNotEqualsMethodCall());
                break;
            case ASSERT_NOT_NULL:
                ctx = Optional.of(parser.assertNotNullMethodCall());
                break;
            case ASSERT_NOT_SAME:
                ctx = Optional.of(parser.assertNotSameMethodCall());
                break;
            case ASSERT_NULL:
                ctx = Optional.of(parser.assertNullMethodCall());
                break;
            case ASSERT_SAME:
                ctx = Optional.of(parser.assertSameMethodCall());
                break;
            case ASSERT_THROWS:
                ctx = Optional.of(parser.assertThrowsMethodCall());
                break;
            case ASSERT_TIMEOUT:
                ctx = Optional.of(parser.assertTimeoutMethodCall());
                break;
            case ASSERT_TIMEOUT_PREEMPTIVELY:
                ctx = Optional.of(parser.assertTimeoutPreemptivelyMethodCall());
                break;
            case ASSERT_TRUE:
                ctx = Optional.of(parser.assertTrueMethodCall());
                break;
            case FAIL:
                ctx = Optional.of(parser.failMethodCall());
                break;
        }
        if (ctx.isPresent()) {
            final ParserRuleContext ctxValue = ctx.get();
            CodeCompletionCore core = new CodeCompletionCore(parser, null, null);
            int caretTokenIndex = tokenStream.size() - 1;
            // Use ExecutorService to enforce timeout
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<CodeCompletionCore.CandidatesCollection> future = executor.submit(() -> core.collectCandidates(caretTokenIndex, ctxValue));
            try {
                // Wait for the result with a timeout
                CodeCompletionCore.CandidatesCollection candidates = future.get(timeoutSecs, TimeUnit.SECONDS);
                if (candidates.tokens == null || candidates.tokens.isEmpty()) {
                    return new ArrayList<>();
                }
                // Process and return candidates
                return candidates.tokens.keySet().stream()
                        .filter(tokenType -> tokenType != Token.EOF)
                        .map(tokenType -> parser.getVocabulary().getDisplayName(tokenType).replace("'", ""))
                        .filter(name -> !name.isEmpty() && !name.equals("<EOF>"))
                        .flatMap(t -> AntlrTokenMapper.getValuesByName(t).stream())
                        .collect(Collectors.toCollection(ArrayList::new));
            } catch (TimeoutException e) {
                future.cancel(true); // Cancel the task
                throw new TimeoutException("Candidate collection timed out");
            } finally {
                executor.shutdownNow(); // Clean up the executor
            }
        }
        throw new IllegalArgumentException("Unsupported assertion type: " + assertionType);
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

        return errorListener.getNextTokens().stream()
                .flatMap(t -> AntlrTokenMapper.getValuesByName(t).stream())
                .collect(Collectors.toCollection(ArrayList::new));
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
