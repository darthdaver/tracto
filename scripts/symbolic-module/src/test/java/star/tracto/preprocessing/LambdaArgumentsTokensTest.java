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
 * End-to-end test of the lambdas written in the target as the argument of a method that is not a method of a stream
 * ({@code items.removeIf(it -> it.count() > 5)}: the parameter of the lambda and its members, Step 45), and of the
 * members of a value created with type arguments ({@code new ArrayList<String>().}, {@code new Box<>(x).}).
 */
class LambdaArgumentsTokensTest {

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
        fixture.write("src/main/java/com/acme/Items.java", """
                package com.acme;

                import java.util.ArrayList;

                public class Items extends ArrayList<Item> {
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
        fixture.write("src/main/java/com/acme/Group.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.function.Predicate;

                public class Group<T extends Item> extends ArrayList<T> {
                    @Override
                    public boolean removeIf(Predicate<? super T> filter) {
                        return super.removeIf(filter);
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Specials.java", """
                package com.acme;

                public class Specials extends Group<Special> {
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
        fixture.write("src/main/java/com/acme/Checks.java", """
                package com.acme;

                import java.util.function.Predicate;

                public class Checks {
                    public static boolean matches(Item item, Predicate<Item> condition) {
                        return condition.test(item);
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ItemTest.java", """
                package com.acme;

                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ItemTest {
                    @Test
                    public void testRemoveIfOnSubclass() {
                        Items items = new Items();
                        assertFalse(items.removeIf(it -> it.count() > 5));
                    }

                    @Test
                    public void testRemoveIfOverridden() {
                        Specials specials = new Specials();
                        assertFalse(specials.removeIf(s -> s.special()));
                    }

                    @Test
                    public void testRemoveIfOnList() {
                        List<Item> list = new ArrayList<>();
                        assertFalse(list.removeIf(x -> x.name().isEmpty()));
                    }

                    @Test
                    public void testSecondArgument() {
                        Item item = new Item();
                        assertTrue(Checks.matches(item, i -> i.count() > 0));
                    }

                    @Test
                    public void testLambdaScope() {
                        Items items = new Items();
                        Item item = new Item();
                        assertFalse(items.removeIf(it -> it.count() > 5 && Checks.matches(it, j -> j.count() < it.count())));
                    }

                    @Test
                    public void testComparison() {
                        int size = 2;
                        assertTrue(size < 3 && size > 1);
                    }

                    @Test
                    public void testTypeArguments() {
                        assertEquals(0, new ArrayList<String>().size());
                    }

                    @Test
                    public void testDiamond() {
                        Item item = new Item();
                        assertEquals(item, new Box<>(item).get());
                    }

                    @Test
                    public void testNestedTypeArguments() {
                        assertTrue(new HashMap<String, List<Item>>().isEmpty());
                    }
                }
                """);
    }

    @Test
    void theLambdaParametersAndTheCreatedValuesHaveTheirMembers() throws IOException {
        fixture.generate();

        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertEquals(Map.of(), fixture.unexpectedTokens(), datapoints.keySet().toString());
        // The parameter of a lambda argument of Collection.removeIf (Predicate<? super E>), on a subclass of
        // ArrayList<Item> and on a List<Item>
        JsonNode subclass = datapoints.get("testRemoveIfOnSubclass_split_0");
        assertTrue(candidatesAfter(subclass, "assertFalse(items.removeIf(").contains("LAMBDA_PARAM"));
        List<String> it = candidatesAfter(subclass, "assertFalse(items.removeIf(it->it.");
        assertTrue(it.containsAll(List.of("count", "name")), it.toString());
        // Overridden by a generic superclass (jsoup: Elements extends Nodes<Element>, Nodes<T> overrides removeIf)
        List<String> overridden = candidatesAfter(datapoints.get("testRemoveIfOverridden_split_0"), "assertFalse(specials.removeIf(s->s.");
        assertTrue(overridden.containsAll(List.of("special", "count")), overridden.toString());
        List<String> x = candidatesAfter(datapoints.get("testRemoveIfOnList_split_0"), "assertFalse(list.removeIf(x->x.");
        assertTrue(x.containsAll(List.of("count", "name")), x.toString());
        // The second argument of a method of the project (Predicate<Item>)
        JsonNode second = datapoints.get("testSecondArgument_split_0");
        assertTrue(candidatesAfter(second, "assertTrue(Checks.matches(item,").contains("LAMBDA_PARAM"));
        assertFalse(candidatesAfter(second, "assertTrue(Checks.matches(").contains("LAMBDA_PARAM"));
        List<String> i = candidatesAfter(second, "assertTrue(Checks.matches(item,i->i.");
        assertTrue(i.containsAll(List.of("count", "name")), i.toString());
        // The parameter of a lambda is a name of its whole body: after an operator, in the arguments of a call
        JsonNode scope = datapoints.get("testLambdaScope_split_0");
        assertTrue(candidatesAfter(scope, "assertFalse(items.removeIf(it->it.count()>5&&").contains("it"));
        assertTrue(candidatesAfter(scope, "assertFalse(items.removeIf(it->it.count()>5&&Checks.matches(").contains("it"));
        List<String> j = candidatesAfter(scope, "assertFalse(items.removeIf(it->it.count()>5&&Checks.matches(it,j->j.");
        assertTrue(j.contains("count"), j.toString());
        List<String> outer = candidatesAfter(scope, "assertFalse(items.removeIf(it->it.count()>5&&Checks.matches(it,j->j.count()<");
        assertTrue(outer.containsAll(List.of("it", "j")), outer.toString());
        // Not after a parenthesis that is not the one of a call with a functional parameter
        assertFalse(candidatesAfter(datapoints.get("testComparison_split_0"), "assertTrue(").contains("LAMBDA_PARAM"));
        // The members of a value created with type arguments
        List<String> created = candidatesAfter(datapoints.get("testTypeArguments_split_0"), "assertEquals(0,new ArrayList<String>().");
        assertTrue(created.containsAll(List.of("size", "isEmpty")), created.toString());
        List<String> diamond = candidatesAfter(datapoints.get("testDiamond_split_0"), "assertEquals(item,new Box<>(item).");
        assertTrue(diamond.contains("get"), diamond.toString());
        List<String> nested = candidatesAfter(datapoints.get("testNestedTypeArguments_split_0"), "assertTrue(new HashMap<String,List<Item>>().");
        assertTrue(nested.containsAll(List.of("isEmpty", "keySet")), nested.toString());
        // The names of the type arguments are the classes
        List<String> typeArgument = candidatesAfter(datapoints.get("testNestedTypeArguments_split_0"), "assertTrue(new HashMap<String,");
        assertTrue(typeArgument.containsAll(List.of("List", "Item")), typeArgument.toString());
        // The comparisons keep working
        List<String> comparison = candidatesAfter(datapoints.get("testComparison_split_0"), "assertTrue(size<3&&size>");
        assertFalse(comparison.contains("LAMBDA_PARAM"), comparison.toString());
    }
}
