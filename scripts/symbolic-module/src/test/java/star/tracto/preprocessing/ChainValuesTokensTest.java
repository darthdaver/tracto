package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of two shapes of chains (Step 40): an array that is the value of a type variable
 * ({@code List<String[]>}: {@code rows.get(0).} ▸ {@code length}), and the methods of {@code Object} on a value whose type
 * is an interface (JLS 9.2: an interface has the public methods of {@code Object}: {@code pair.} ▸ {@code toString}).
 */
class ChainValuesTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Pair.java", """
                package com.acme;

                public interface Pair {
                    String key();
                }
                """);
        fixture.write("src/main/java/com/acme/Box.java", """
                package com.acme;

                public class Box<T> {
                    private final T value;

                    public Box(T value) {
                        this.value = value;
                    }

                    public T get() {
                        return value;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Store.java", """
                package com.acme;

                import java.util.List;

                public class Store {
                    public static List<String[]> rows() {
                        return List.of(new String[]{"a", "b"});
                    }

                    public static Box<char[]> chars() {
                        return new Box<>(new char[]{'x'});
                    }

                    public static Pair pair() {
                        return () -> "a";
                    }

                    public static List<Pair> pairs() {
                        return List.of(pair());
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/StoreTest.java", """
                package com.acme;

                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class StoreTest {
                    @Test
                    public void testRows() {
                        List<String[]> rows = Store.rows();
                        assertEquals(2, rows.get(0).length);
                    }

                    @Test
                    public void testChars() {
                        Box<char[]> box = Store.chars();
                        assertEquals(1, box.get().length);
                    }

                    @Test
                    public void testPair() {
                        Pair pair = Store.pair();
                        assertNotNull(pair.toString());
                    }

                    @Test
                    public void testPairs() {
                        List<Pair> pairs = Store.pairs();
                        assertEquals(pairs.get(0).hashCode(), pairs.get(0).hashCode());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheValuesOfTheChainsAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // An array as the value of a type variable: its length, and the members of its component type
        List<String> row = candidatesAfter(datapoints.get("testRows_split_0"), "assertEquals(2,rows.get(0).");
        assertTrue(row.containsAll(List.of("length", "isEmpty")), row.toString());
        List<String> chars = candidatesAfter(datapoints.get("testChars_split_0"), "assertEquals(1,box.get().");
        assertTrue(chars.contains("length"), chars.toString());
        // An interface: its methods and the public methods of Object, not the protected ones
        List<String> pair = candidatesAfter(datapoints.get("testPair_split_0"), "assertNotNull(pair.");
        assertTrue(pair.containsAll(List.of("key", "toString", "equals", "hashCode", "getClass")), pair.toString());
        assertTrue(pair.stream().noneMatch(List.of("clone", "finalize")::contains), pair.toString());
        // An interface as the value of a type variable
        List<String> element = candidatesAfter(datapoints.get("testPairs_split_0"), "assertEquals(pairs.get(0).");
        assertTrue(element.containsAll(List.of("key", "hashCode")), element.toString());
    }
}
