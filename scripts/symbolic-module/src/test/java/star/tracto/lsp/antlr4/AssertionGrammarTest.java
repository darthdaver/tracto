package star.tracto.lsp.antlr4;

import org.junit.jupiter.api.Test;
import star.tracto.data.enums.JUnitAssertionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java tokens offered by the grammar of the assertions after a partial oracle (Step 28): the message of an
 * assertion is any expression of type {@code String} (not only a string literal), and the constructor references of
 * the classes and the arrays ({@code Foo::new}, {@code String[]::new}). The {@code assertThat} of Hamcrest (Step 30).
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

    /** {@code assertThat(actual, matcher)}, {@code assertThat(reason, actual, matcher)}, {@code assertThat(reason, boolean)} (Step 30). */
    @Test
    void assertThatOfHamcrestHasARule() throws Exception {
        assertTrue(next(JUnitAssertionType.ASSERT_THAT, "assertThat(").contains("IDENTIFIER"));
        // After the actual value, the matcher
        assertTrue(next(JUnitAssertionType.ASSERT_THAT, "assertThat(clock.now()").contains(","));
        // After two arguments, the end or a third one
        List<String> afterTwo = next(JUnitAssertionType.ASSERT_THAT, "assertThat(clock.now(),is(1L)");
        assertTrue(afterTwo.contains(")"), afterTwo.toString());
        assertTrue(afterTwo.contains(","), afterTwo.toString());
        // After three arguments, the end only
        List<String> afterThree = next(JUnitAssertionType.ASSERT_THAT, "assertThat(\"now\",clock.now(),is(1L)");
        assertTrue(afterThree.contains(")"), afterThree.toString());
        assertFalse(afterThree.contains(","), afterThree.toString());
        assertTrue(next(JUnitAssertionType.ASSERT_THAT, "assertThat(clock.now(),is(1L))").contains(";"));
    }

    /** {@code assertInstanceOf(expectedType, actualValue[, message])} of JUnit 5 (Step 41). */
    @Test
    void assertInstanceOfHasARule() throws Exception {
        assertTrue(next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(").contains("IDENTIFIER"));
        assertTrue(next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.").contains("class"));
        assertTrue(next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.class").contains(","));
        // After two arguments, the end or the message
        List<String> afterTwo = next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.class,result.getCause()");
        assertTrue(afterTwo.contains(")"), afterTwo.toString());
        assertTrue(afterTwo.contains(","), afterTwo.toString());
        // After the message (a string or a supplier), the end only
        List<String> afterMessage = next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.class,result,\"a clock\"");
        assertTrue(afterMessage.contains(")"), afterMessage.toString());
        assertFalse(afterMessage.contains(","), afterMessage.toString());
        assertTrue(next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.class,result,()->\"a clock\"").contains(")"));
        assertTrue(next(JUnitAssertionType.ASSERT_INSTANCE_OF, "assertInstanceOf(Clock.class,result)").contains(";"));
    }

    /** {@code assertThrowsExactly(expectedType, executable[, message])} of JUnit 5, as {@code assertThrows} (Step 41). */
    @Test
    void assertThrowsExactlyHasARule() throws Exception {
        assertTrue(next(JUnitAssertionType.ASSERT_THROWS_EXACTLY, "assertThrowsExactly(IllegalStateException.class").contains(","));
        assertTrue(next(JUnitAssertionType.ASSERT_THROWS_EXACTLY, "assertThrowsExactly(IllegalStateException.class,()->").contains("IDENTIFIER"));
        List<String> afterTwo = next(JUnitAssertionType.ASSERT_THROWS_EXACTLY, "assertThrowsExactly(IllegalStateException.class,()->clock.stop()");
        assertTrue(afterTwo.contains(")"), afterTwo.toString());
        assertTrue(afterTwo.contains(","), afterTwo.toString());
        assertTrue(next(JUnitAssertionType.ASSERT_THROWS_EXACTLY, "assertThrowsExactly(IllegalStateException.class,clock::").contains("IDENTIFIER"));
        assertTrue(next(JUnitAssertionType.ASSERT_THROWS_EXACTLY, "assertThrowsExactly(IllegalStateException.class,clock::stop,\"stops\")").contains(";"));
    }
}
