package star.tracto.preprocessing.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The simple name of the class of a key of the class references (Step 49). */
class SimpleClassNameTest {

    @Test
    void theSimpleNameIsTheLastSegmentBeforeTheTypeArguments() {
        assertEquals("Item", TokensUtils.simpleClassName("com.acme.Item"));
        assertEquals("Item", TokensUtils.simpleClassName("Item"));
        assertEquals("Entry", TokensUtils.simpleClassName("java.util.Map.Entry"));
        assertEquals("List", TokensUtils.simpleClassName("java.util.List<T>"));
        // A qualified type argument: its dots come after the `<` (before Step 49, the substring failed)
        assertEquals("List", TokensUtils.simpleClassName("java.util.List<com.acme.Item>"));
        assertEquals("Map", TokensUtils.simpleClassName("java.util.Map<java.lang.String, java.util.List<com.acme.Item>>"));
    }
}
