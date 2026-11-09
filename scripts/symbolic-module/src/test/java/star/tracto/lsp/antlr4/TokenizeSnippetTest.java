package star.tracto.lsp.antlr4;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tokens of a snippet are the tokens of the language: no whitespace, no comment (Step 29).
 */
class TokenizeSnippetTest {

    @Test
    void theWhitespaceIsSkipped() {
        assertEquals(List.of("assertThrows", "(", "E", ".", "class", ",", "(", ")", "->", "{", "a", "(", ")", ";", "}",
                        ")", ";"),
                Antlr4Utils.tokenizeSnippet("assertThrows(E.class, () -> {\n    a();\n\t});"));
    }

    @Test
    void theCommentsAreSkipped() {
        assertEquals(List.of("assertEquals", "(", "1", ",", "x", ")", ";"),
                Antlr4Utils.tokenizeSnippet("assertEquals(1, /* one */ x); // end"));
    }

    @Test
    void aStringLiteralKeepsItsSpaces() {
        assertEquals(List.of("fail", "(", "\"a  b // c\"", ")", ";"),
                Antlr4Utils.tokenizeSnippet("fail(\"a  b // c\");"));
    }
}
