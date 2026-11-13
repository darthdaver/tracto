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
 * End-to-end test of the types that come from a lambda (Step 42): the parameter of a lambda without a declared type
 * whose body contains the target ({@code found.ifPresent(it -> { assertEquals("a", it.name()); })}: the type of
 * {@code it} is the bound of the wildcard of {@code Consumer<? super T>}), and a variable declared with {@code var}
 * whose initializer calls a method overloaded with a void method and a generic one
 * ({@code var item = Checks.attempt(() -> Store.make())}: the generic one, whose type is the type of the body of the
 * lambda; a variable is never void).
 */
class LambdaTypesTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Item.java", """
                package com.acme;

                public class Item {
                    public String name() {
                        return "a";
                    }

                    public int count() {
                        return 1;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Action.java", """
                package com.acme;

                public interface Action {
                    void run() throws Exception;
                }
                """);
        fixture.write("src/main/java/com/acme/Producer.java", """
                package com.acme;

                public interface Producer<T> {
                    T get() throws Exception;
                }
                """);
        fixture.write("src/main/java/com/acme/Checks.java", """
                package com.acme;

                public class Checks {
                    public static void attempt(Action action) {
                        try {
                            action.run();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }

                    public static <T> T attempt(Producer<T> producer) {
                        try {
                            return producer.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Store.java", """
                package com.acme;

                import java.util.List;

                public class Store {
                    public static Item make() {
                        return new Item();
                    }

                    public static List<Item> items() {
                        return List.of(new Item());
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/StoreTest.java", """
                package com.acme;

                import java.util.Optional;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class StoreTest {
                    @Test
                    public void testAttempt() {
                        var item = Checks.attempt(() -> Store.make());
                        assertEquals("a", item.name());
                    }

                    @Test
                    public void testFound() {
                        var found = Store.items().stream().filter(i -> i.count() > 0).findAny();
                        found.ifPresent(it -> {
                            assertEquals("a", it.name());
                        });
                    }

                    @Test
                    public void testDeclared() {
                        Optional<Item> first = Store.items().stream().findFirst();
                        first.ifPresent(it -> {
                            assertEquals(1, it.count());
                        });
                    }

                    @Test
                    public void testTyped() {
                        Optional<Item> first = Store.items().stream().findFirst();
                        first.ifPresent((Item it) -> {
                            assertEquals(1, it.count());
                        });
                    }
                }
                """);
    }

    @Test
    void theTypesFromTheLambdasAreKnown() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // A variable declared with var, initialized by the generic method of an overload with a void one
        List<String> item = candidatesAfter(datapoints.get("testAttempt_split_0"), "assertEquals(\"a\",item.");
        assertTrue(item.containsAll(List.of("name", "count")), item.toString());
        // The parameter of a lambda without a declared type: the bound of the wildcard (Consumer<? super T>)
        List<String> found = candidatesAfter(datapoints.get("testFound_split_0"), "assertEquals(\"a\",it.");
        assertTrue(found.containsAll(List.of("name", "count")), found.toString());
        List<String> declared = candidatesAfter(datapoints.get("testDeclared_split_0"), "assertEquals(1,it.");
        assertTrue(declared.containsAll(List.of("name", "count")), declared.toString());
        // A declared type, as before
        List<String> typed = candidatesAfter(datapoints.get("testTyped_split_0"), "assertEquals(1,it.");
        assertTrue(typed.containsAll(List.of("name", "count")), typed.toString());
    }
}
