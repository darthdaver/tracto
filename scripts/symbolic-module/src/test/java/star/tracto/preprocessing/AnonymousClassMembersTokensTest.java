package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of the members of an anonymous class after a variable of that class (Step 54). Only {@code var} gives a
 * variable the type of an anonymous class; JavaParser types {@code new Object() { … }} as {@code Object}, the type
 * written after {@code new}, so the members declared in the body were not offered (a lost target: {@code counter.} ▸
 * {@code twice}). A variable declared with the supertype has the members of the supertype only, as in Java.
 */
class AnonymousClassMembersTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Listener.java", """
                package com.acme;

                public interface Listener {
                    void on(String event);

                    default String name() {
                        return "listener";
                    }
                }
                """);
        // the focal class of CounterTest
        fixture.write("src/main/java/com/acme/Counter.java", """
                package com.acme;

                public class Counter {
                    public int total;

                    public int next(int value) {
                        return value + 1;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/CounterTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import org.missing.Thing;

                import java.util.ArrayList;

                import static org.junit.jupiter.api.Assertions.*;

                public class CounterTest {
                    @Test
                    public void testMethod() {
                        var counter = new Object() {
                            int count = 1;

                            int twice() {
                                return 2 * count;
                            }

                            class Inner {
                                int hidden;
                            }
                        };
                        assertEquals(2, counter.twice());
                    }

                    @Test
                    public void testField() {
                        var counter = new Object() {
                            int count = 1;
                        };
                        assertEquals(1, counter.count);
                    }

                    @Test
                    public void testInterface() {
                        var listener = new Listener() {
                            int calls;

                            public void on(String event) {
                                calls++;
                            }
                        };
                        listener.on("a");
                        assertEquals(1, listener.calls);
                    }

                    @Test
                    public void testPlainVar() {
                        var plain = new Counter();
                        assertEquals(0, plain.total);
                    }

                    @Test
                    public void testGenericVar() {
                        var names = new ArrayList<String>();
                        names.add("a");
                        assertEquals(1, names.get(0).length());
                    }

                    @Test
                    public void testUnresolvable() {
                        var thing = new Thing() {
                            int size = 1;
                        };
                        assertEquals(1, thing.size);
                    }

                    @Test
                    public void testDeclaredType() {
                        Listener listener = new Listener() {
                            int calls;

                            public void on(String event) {
                                calls++;
                            }
                        };
                        assertEquals("listener", listener.name());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheAnonymousClassAreOfferedAfterAVarOfIt() throws IOException {
        fixture.generate();

        // only the anonymous class of a type that cannot be resolved (a missing dependency) loses its target, as before
        assertEquals(Map.of("testUnresolvable_split_0", List.of("Unexpected token: size not found in the collected list of candidate tokens: assertEquals(1,thing.")),
                fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // its method and its field, and the members of its supertype; not the field of a class nested in it (Step 50)
        List<String> afterMethod = candidatesAfter(datapoints.get("testMethod_split_0"), "assertEquals(2,counter.");
        assertTrue(afterMethod.containsAll(List.of("twice", "count", "toString", "hashCode")), afterMethod.toString());
        assertFalse(afterMethod.contains("hidden"), afterMethod.toString());
        List<String> afterField = candidatesAfter(datapoints.get("testField_split_0"), "assertEquals(1,counter.");
        assertTrue(afterField.contains("count"), afterField.toString());
        // a var of a class (no anonymous body): the members of the class, as before
        List<String> afterPlain = candidatesAfter(datapoints.get("testPlainVar_split_0"), "assertEquals(0,plain.");
        assertTrue(afterPlain.containsAll(List.of("total", "next")), afterPlain.toString());
        // a var of a generic class: its type arguments (`E` of `get` is String)
        List<String> afterGeneric = candidatesAfter(datapoints.get("testGenericVar_split_0"), "assertEquals(1,names.get(0).");
        assertTrue(afterGeneric.contains("length"), afterGeneric.toString());
        // an anonymous class of an interface: its field, the methods of the interface (a default one; not the void `on`,
        // not offered as an argument since Step 39)
        List<String> afterInterface = candidatesAfter(datapoints.get("testInterface_split_0"), "assertEquals(1,listener.");
        assertTrue(afterInterface.containsAll(List.of("calls", "name")), afterInterface.toString());
    }

    @Test
    void theContextAfterTheVariableIsTheOneOfTheSupertype() throws IOException {
        // an anonymous class has no file: the context of the step after `counter.` is the one of its supertype (the
        // methods and the constructor of Object), as when the variable had the type Object
        fixture.generate();

        JsonNode step = stepAfter(fixture.tokensDatapoints().get("testMethod_split_0"), "assertEquals(2,counter.");
        List<String> methods = new ArrayList<>();
        List<String> constructors = new ArrayList<>();
        for (JsonNode info : step.get("contextualInfos")) {
            info.get("invokedMethods").forEach(method -> methods.add(method.get("signature").asText()));
            info.get("invokedConstructors").forEach(constructor -> constructors.add(constructor.get("signature").asText()));
        }
        assertTrue(methods.containsAll(List.of("public String toString()", "public native int hashCode()")), methods.toString());
        assertEquals(List.of("public Object()"), constructors);
    }

    private static JsonNode stepAfter(JsonNode datapoint, String partialSnippet) {
        assertNotNull(datapoint, "no tokens datapoint");
        for (JsonNode step : datapoint.get("tokenSteps")) {
            if (step.get("partialSnippet").asText().equals(partialSnippet)) {
                return step;
            }
        }
        throw new AssertionError("no token step after " + partialSnippet);
    }

    @Test
    void aVariableDeclaredWithTheSupertypeHasTheMembersOfTheSupertypeOnly() throws IOException {
        // Java does not compile `listener.calls` when listener is a Listener: as before
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testDeclaredType_split_0"), "assertEquals(\"listener\",listener.");
        assertTrue(candidates.contains("name"), candidates.toString());
        assertFalse(candidates.contains("calls"), candidates.toString());
    }
}
