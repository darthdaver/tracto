package star.tracto.lsp.antlr4;

import org.junit.jupiter.api.Test;
import star.tracto.data.enums.JUnitAssertionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java tokens offered by the grammar of the assertions after a partial oracle (Step 28): the message of an
 * assertion is any expression of type {@code String} (not only a string literal), and the constructor references of
 * the classes and the arrays ({@code Foo::new}, {@code String[]::new}).
 */
class AssertionGrammarTest {

    private static List<String> next(JUnitAssertionType type, String partial) throws Exception {
        return Antlr4Utils.parseSnippet(type, partial, 60);
    }

    @Test
    void theMessageOfAnAssertionIsAnyExpression() throws Exception {
        // A string concatenation, a call (the message of JUnit 5, last)
        assertTrue(next(JUnitAssertionType.ASSERT_NULL, "assertNull(normalizer.filter(url),\"normalizing: \"+url").contains(")"));
        assertTrue(next(JUnitAssertionType.ASSERT_TRUE, "assertTrue(Tag.isKnownTag(name),String.format(\"Unknown: %s\",name)").contains(")"));
        assertTrue(next(JUnitAssertionType.ASSERT_EQUALS, "assertEquals(1,size,message+\" items\"").contains(")"));
        // A string literal and a supplier still are messages
        assertTrue(next(JUnitAssertionType.ASSERT_NULL, "assertNull(value,\"message\"").contains(")"));
        assertTrue(next(JUnitAssertionType.ASSERT_NULL, "assertNull(value,()->\"message\"").contains(")"));
    }

    @Test
    void theConstructorReferencesOfClassesAndArraysAreExpressions() throws Exception {
        assertTrue(next(JUnitAssertionType.ASSERT_ARRAY_EQUALS, "assertArrayEquals(expected,list.toArray(String[]").contains("::"));
        assertTrue(next(JUnitAssertionType.ASSERT_ARRAY_EQUALS, "assertArrayEquals(expected,list.toArray(String[]::").contains("new"));
        assertTrue(next(JUnitAssertionType.ASSERT_EQUALS, "assertEquals(expected,stream.map(Foo::").contains("new"));
        // A method reference on an expression still is
        assertTrue(next(JUnitAssertionType.ASSERT_EQUALS, "assertEquals(expected,stream.map(Foo::").contains("IDENTIFIER"));
    }

    @Test
    void instanceofIsFollowedByATypeAndTheRestOfTheAssertion() throws Exception {
        assertTrue(next(JUnitAssertionType.ASSERT_TRUE, "assertTrue(node instanceof Comment").contains(")"));
        assertTrue(next(JUnitAssertionType.ASSERT_TRUE, "assertTrue(node instanceof Comment").contains(","));
    }
}
