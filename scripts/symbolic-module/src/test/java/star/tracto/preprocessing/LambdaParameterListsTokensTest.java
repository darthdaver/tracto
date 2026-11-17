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
import static star.tracto.preprocessing.TokensFixture.targetAfter;

/**
 * End-to-end test of the lambdas with a parameter list written in the target as the argument of a call (Step 47):
 * several parameters ({@code (a, b) ->}), a parenthesized one ({@code (it) ->}), declared types ({@code (Item a,
 * Special b) ->}, {@code final}, {@code var}). Each name is a new name (the placeholder {@code LAMBDA_PARAM}, the target
 * {@code LAMBDA_PARAM:<name>}); it then gets the type of the parameter of the functional interface at its position, so
 * its members are candidates in the body.
 */
class LambdaParameterListsTokensTest {

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
        fixture.write("src/main/java/com/acme/Special.java", """
                package com.acme;

                public class Special extends Item {
                    public boolean special() {
                        return true;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Items.java", """
                package com.acme;

                import java.util.ArrayList;

                public class Items extends ArrayList<Item> {
                }
                """);
        fixture.write("src/main/java/com/acme/Checks.java", """
                package com.acme;

                import java.util.function.BiPredicate;

                public class Checks {
                    public static boolean both(Item item, Special special, BiPredicate<Item, Special> condition) {
                        return condition.test(item, special);
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ChecksTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ChecksTest {
                    @Test
                    public void testTwoParameters() {
                        Item item = new Item();
                        Special special = new Special();
                        assertTrue(Checks.both(item, special, (a, b) -> a.count() <= b.count() && b.special()));
                    }

                    @Test
                    public void testDeclaredTypes() {
                        Item item = new Item();
                        Special special = new Special();
                        assertTrue(Checks.both(item, special, (Item a, Special b) -> b.special()));
                    }

                    @Test
                    public void testFinalParameters() {
                        Item item = new Item();
                        Special special = new Special();
                        assertTrue(Checks.both(item, special, (final Item a, final Special b) -> a.count() > 0));
                    }

                    @Test
                    public void testVarParameters() {
                        Item item = new Item();
                        Special special = new Special();
                        assertTrue(Checks.both(item, special, (var a, var b) -> b.special()));
                    }

                    @Test
                    public void testParenthesizedParameter() {
                        Items items = new Items();
                        assertFalse(items.removeIf((it) -> it.count() > 5));
                    }

                    @Test
                    public void testMapForEach() {
                        Map<String, Item> byName = new HashMap<>();
                        assertDoesNotThrow(() -> byName.forEach((k, v) -> v.count()));
                    }

                    @Test
                    public void testComparator() {
                        List<Item> list = new ArrayList<>();
                        assertDoesNotThrow(() -> list.sort((x, y) -> x.count() - y.count()));
                    }

                    @Test
                    public void testReduce() {
                        List<Item> list = new ArrayList<>();
                        assertTrue(list.stream().reduce((a, b) -> b).isEmpty());
                    }

                    @Test
                    public void testParenthesizedExpression() {
                        int size = 2;
                        assertEquals(3, (size + 1));
                    }
                }
                """);
    }

    @Test
    void theParametersOfALambdaParameterListAreNewNamesWithTheirTypes() throws IOException {
        fixture.generate();

        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertEquals(Map.of(), fixture.unexpectedTokens(), datapoints.keySet().toString());

        // Two parameters of different types (BiPredicate<Item, Special>)
        JsonNode two = datapoints.get("testTwoParameters_split_0");
        String list = "assertTrue(Checks.both(item,special,(";
        assertTrue(candidatesAfter(two, list).contains("LAMBDA_PARAM"), candidatesAfter(two, list).toString());
        assertEquals("LAMBDA_PARAM:a", targetAfter(two, list));
        // After a name, not another name (`(a` is not a type)
        assertFalse(candidatesAfter(two, list + "a").contains("LAMBDA_PARAM"), candidatesAfter(two, list + "a").toString());
        assertTrue(candidatesAfter(two, list + "a,").contains("LAMBDA_PARAM"));
        assertEquals("LAMBDA_PARAM:b", targetAfter(two, list + "a,"));
        List<String> body = candidatesAfter(two, list + "a,b)->");
        assertTrue(body.containsAll(List.of("a", "b")), body.toString());
        List<String> a = candidatesAfter(two, list + "a,b)->a.");
        assertTrue(a.contains("count"), a.toString());
        assertFalse(a.contains("special"), a.toString());
        List<String> b = candidatesAfter(two, list + "a,b)->a.count()<=b.");
        assertTrue(b.containsAll(List.of("special", "count")), b.toString());
        // Not where the method takes no functional interface (the first argument, an Item)
        assertFalse(candidatesAfter(two, "assertTrue(Checks.both(").contains("LAMBDA_PARAM"));

        // Declared types: the type is a class name (a candidate), then the name
        JsonNode declared = datapoints.get("testDeclaredTypes_split_0");
        assertTrue(candidatesAfter(declared, list).contains("Item"));
        assertEquals("IDENTIFIER:Item", targetAfter(declared, list));
        assertTrue(candidatesAfter(declared, list + "Item").contains("LAMBDA_PARAM"), candidatesAfter(declared, list + "Item").toString());
        assertEquals("LAMBDA_PARAM:a", targetAfter(declared, list + "Item"));
        assertEquals("LAMBDA_PARAM:b", targetAfter(declared, list + "Item a,Special"));
        List<String> declaredB = candidatesAfter(declared, list + "Item a,Special b)->b.");
        assertTrue(declaredB.contains("special"), declaredB.toString());

        JsonNode finals = datapoints.get("testFinalParameters_split_0");
        assertEquals("LAMBDA_PARAM:b", targetAfter(finals, list + "final Item a,final Special"));
        List<String> finalA = candidatesAfter(finals, list + "final Item a,final Special b)->a.");
        assertTrue(finalA.contains("count"), finalA.toString());

        JsonNode vars = datapoints.get("testVarParameters_split_0");
        assertEquals("LAMBDA_PARAM:a", targetAfter(vars, list + "var "));
        List<String> varB = candidatesAfter(vars, list + "var a,var b)->b.");
        assertTrue(varB.contains("special"), varB.toString());

        // A parenthesized parameter (Predicate<? super E> of a subclass of ArrayList<Item>)
        JsonNode parenthesized = datapoints.get("testParenthesizedParameter_split_0");
        assertEquals("LAMBDA_PARAM:it", targetAfter(parenthesized, "assertFalse(items.removeIf(("));
        List<String> it = candidatesAfter(parenthesized, "assertFalse(items.removeIf((it)->it.");
        assertTrue(it.containsAll(List.of("count", "name")), it.toString());

        // The JDK: Map<String, Item>.forEach(BiConsumer<? super K, ? super V>), List<Item>.sort(Comparator<? super E>)
        JsonNode forEach = datapoints.get("testMapForEach_split_0");
        assertEquals("LAMBDA_PARAM:v", targetAfter(forEach, "assertDoesNotThrow(()->byName.forEach((k,"));
        List<String> v = candidatesAfter(forEach, "assertDoesNotThrow(()->byName.forEach((k,v)->v.");
        assertTrue(v.contains("count"), v.toString());
        JsonNode comparator = datapoints.get("testComparator_split_0");
        List<String> y = candidatesAfter(comparator, "assertDoesNotThrow(()->list.sort((x,y)->x.count()-y.");
        assertTrue(y.contains("count"), y.toString());

        // A stream (Stream<Item>.reduce(BinaryOperator<T>))
        JsonNode reduce = datapoints.get("testReduce_split_0");
        assertEquals("LAMBDA_PARAM:b", targetAfter(reduce, "assertTrue(list.stream().reduce((a,"));
        List<String> reduceBody = candidatesAfter(reduce, "assertTrue(list.stream().reduce((a,b)->");
        assertTrue(reduceBody.containsAll(List.of("a", "b")), reduceBody.toString());

        // A parenthesized expression where no lambda can be: no placeholder
        JsonNode expression = datapoints.get("testParenthesizedExpression_split_0");
        assertFalse(candidatesAfter(expression, "assertEquals(3,(").contains("LAMBDA_PARAM"));
        assertEquals("IDENTIFIER:size", targetAfter(expression, "assertEquals(3,("));
    }
}
