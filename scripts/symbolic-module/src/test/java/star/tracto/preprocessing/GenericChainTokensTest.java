package star.tracto.preprocessing;

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
 * End-to-end test of the candidate tokens after a chain of calls on generic types (Step 21): the type of
 * {@code box.items().iterator().next()} is {@code Item}, whatever the order in which the declarations of
 * {@code iterator()} (in {@code Collection} and, inherited, in {@code Iterable}) are processed.
 */
class GenericChainTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Item.java", """
                package com.acme;

                public class Item {
                    public boolean isValid() {
                        return true;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Box.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.Collection;
                import java.util.List;

                public class Box {
                    private final List<Item> items = new ArrayList<>();

                    public void add(Item item) {
                        items.add(item);
                    }

                    public Collection<Item> items() {
                        return items;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/BoxTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class BoxTest {
                    @Test
                    public void testFirstItem() {
                        Box box = new Box();
                        box.add(new Item());
                        assertTrue(box.items().iterator().next().isValid());
                    }
                }
                """);
    }

    @Test
    void theMethodsOfTheElementTypeAreOfferedAfterTheNextElementOfAnIterator() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testFirstItem_split_0"),
                "assertTrue(box.items().iterator().next().");
        assertTrue(candidates.contains("isValid"), candidates.toString());
        assertTrue(candidates.contains("toString"), candidates.toString());
    }
}
