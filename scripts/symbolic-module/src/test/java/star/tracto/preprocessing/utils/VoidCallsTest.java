package star.tracto.preprocessing.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a call of a void method is valid in an assertion (Step 39): the next identifier is the end of the body of an
 * expression lambda (its receiver chain) or the name of a method reference.
 */
class VoidCallsTest {

    private static boolean allowed(String... previous) {
        return VoidCalls.allowed(List.of(previous));
    }

    @Test
    void theBodyOfAnExpressionLambda() {
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->"));
        assertTrue(allowed("assertThrows", "(", "E", ".", "class", ",", "(", ")", "->", "clock", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "a", ".", "b", "(", "x", ",", "y", ")", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "new", "Clock", "(", ")", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "(", "(", "Clock", ")", "c", ")", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "items", "[", "0", "]", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "\"text\"", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "this", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "Map", ".", "<", "String", ",", "Integer", ">"));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", ")", "->", "list", ".", "forEach", "(", "x", "->", "x", "."));
        assertTrue(allowed("assertDoesNotThrow", "(", "(", "a", ",", "b", ")", "->"));
    }

    @Test
    void aMethodReference() {
        assertTrue(allowed("assertDoesNotThrow", "(", "clock", "::"));
    }

    @Test
    void aValue() {
        assertFalse(allowed("assertEquals", "("));
        assertFalse(allowed("assertEquals", "(", "1", ","));
        assertFalse(allowed("assertEquals", "(", "1", ",", "clock", "."));
        assertFalse(allowed("assertTrue", "(", "!"));
        assertFalse(allowed("assertEquals", "(", "a", "+"));
        // An argument inside the body of a lambda
        assertFalse(allowed("assertDoesNotThrow", "(", "(", ")", "->", "clock", ".", "check", "("));
        assertFalse(allowed("assertDoesNotThrow", "(", "(", ")", "->", "clock", ".", "check", "(", "clock", "."));
        // An operand inside the body of a lambda
        assertFalse(allowed("assertDoesNotThrow", "(", "(", ")", "->", "a", "+"));
        assertFalse(allowed("assertDoesNotThrow", "(", "(", ")", "->", "a", "<"));
        // After the body of a lambda
        assertFalse(allowed("assertThrows", "(", "E", ".", "class", ",", "(", ")", "->", "a", "(", ")", ",", "b", "."));
        assertFalse(allowed("assertDoesNotThrow", "(", "(", ")", "->", "items", "[", "clock", "."));
    }
}
