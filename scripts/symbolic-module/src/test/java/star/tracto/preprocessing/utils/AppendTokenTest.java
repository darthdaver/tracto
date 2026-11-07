package star.tracto.preprocessing.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tokens joined to the partial oracle (Step 28), the same way in the tokens dataset and at inference.
 */
class AppendTokenTest {

    @Test
    void aKeywordAfterAnOperandIsSeparatedFromIt() {
        assertEquals("assertTrue(asm instanceof ", TokensUtils.appendToken("assertTrue(asm", "instanceof"));
        assertEquals("assertTrue(get() instanceof ", TokensUtils.appendToken("assertTrue(get()", "instanceof"));
        assertEquals("assertTrue(a[0] instanceof ", TokensUtils.appendToken("assertTrue(a[0]", "instanceof"));
        assertEquals("assertTrue(asm instanceof SiteMap", TokensUtils.appendToken("assertTrue(asm instanceof ", "SiteMap"));
    }

    @Test
    void aKeywordAfterASeparatorOrAnOperatorIsNotPrecededByASpace() {
        assertEquals("assertEquals(new ", TokensUtils.appendToken("assertEquals(", "new"));
        assertEquals("assertEquals(a,new ", TokensUtils.appendToken("assertEquals(a,", "new"));
        assertEquals("assertEquals(Foo.class ", TokensUtils.appendToken("assertEquals(Foo.", "class"));
    }

    @Test
    void theOtherTokensAreJoinedWithoutSpaces() {
        assertEquals("assertEquals(a,b", TokensUtils.appendToken("assertEquals(a,", "b"));
        assertEquals("assertEquals(a)", TokensUtils.appendToken("assertEquals(a", ")"));
        assertEquals("assertEquals(\"x\"", TokensUtils.appendToken("assertEquals(", "\"x\""));
    }
}
