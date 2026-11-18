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
 * End-to-end test of the fields offered after a value (Step 50): the fields declared in its type and in its supertypes,
 * not the fields of the classes nested in them (a static or inner class, an anonymous class, a local class), which Java
 * does not let a value reach ({@code doc.validChildren} where {@code validChildren} is a field of
 * {@code Element.NodeList}; {@code e.STACK_TRACE_SENTINEL} of the private {@code Throwable.SentinelHolder}). A value
 * of the nested class itself still has its fields.
 */
class NestedClassFieldsTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Node.java", """
                package com.acme;

                import java.util.Iterator;

                public class Node {
                    static final String EmptyString = "";
                    int depth = 0;

                    public static final class NodeList {
                        public boolean validChildren = true;
                    }

                    private static class Holder {
                        public static final String SENTINEL = "";
                    }

                    public Iterator<Integer> depths() {
                        return new Iterator<Integer>() {
                            int cursor = 0;

                            public boolean hasNext() {
                                return cursor < depth;
                            }

                            public Integer next() {
                                return cursor++;
                            }
                        };
                    }

                    public int localDepth() {
                        class Counter {
                            int counted = depth;
                        }
                        return new Counter().counted;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Element.java", """
                package com.acme;

                public class Element extends Node {
                    String tag = "div";

                    static class Cache {
                        int hits = 0;
                    }

                    public String tag() {
                        return tag;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ElementTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ElementTest {
                    @Test
                    public void testElement() {
                        Element element = new Element();
                        assertEquals("div", element.tag);
                    }

                    @Test
                    public void testNode() {
                        Node node = new Node();
                        assertEquals(0, node.depth);
                    }

                    @Test
                    public void testNodeList() {
                        Node.NodeList list = new Node.NodeList();
                        assertTrue(list.validChildren);
                    }

                    @Test
                    public void testException() {
                        IllegalStateException exception = new IllegalStateException("x");
                        assertEquals("x", exception.getMessage());
                    }
                }
                """);
    }

    @Test
    void theFieldsOfNestedClassesAreNotCandidatesAfterAValue() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        List<String> wrong = new ArrayList<>();
        // The fields of the type and of its superclass; not those of the classes nested in either
        check(wrong, datapoints, "testElement_split_0", "assertEquals(\"div\",element.",
                List.of("tag", "depth", "EmptyString"), List.of("hits", "validChildren", "SENTINEL", "cursor", "counted"));
        check(wrong, datapoints, "testNode_split_0", "assertEquals(0,node.",
                List.of("depth", "EmptyString"), List.of("validChildren", "SENTINEL", "cursor", "counted"));
        // A value of the nested class: its own fields
        check(wrong, datapoints, "testNodeList_split_0", "assertTrue(list.",
                List.of("validChildren"), List.of("depth", "SENTINEL"));
        // The JDK: the fields of the private Throwable.SentinelHolder
        check(wrong, datapoints, "testException_split_0", "assertEquals(\"x\",exception.",
                List.of("getMessage"), List.of("STACK_TRACE_SENTINEL", "STACK_TRACE_ELEMENT_SENTINEL"));
        assertEquals(List.of(), wrong);
    }

    private static void check(List<String> wrong, Map<String, JsonNode> datapoints, String prefix, String position,
                              List<String> offered, List<String> hidden) {
        List<String> candidates = candidatesAfter(datapoints.get(prefix), position);
        for (String name : offered) {
            if (!candidates.contains(name)) {
                wrong.add(prefix + " misses " + name);
            }
        }
        for (String name : hidden) {
            if (candidates.contains(name)) {
                wrong.add(prefix + " offers " + name);
            }
        }
    }
}
