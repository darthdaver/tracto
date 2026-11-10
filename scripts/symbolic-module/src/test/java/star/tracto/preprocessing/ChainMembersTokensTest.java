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
 * End-to-end test of the members offered after a chain (Step 31): a call on a class extending a generic class of the
 * JDK ({@code nodes.get(0)}, {@code Nodes extends ArrayList<Node>}: the values of the type parameters of the ancestors),
 * the {@code length} of an array (a variable or the result of a call), and the members of the type of a cast (Step 32:
 * {@code ((TextNode) node).}, not after a call whose argument is a cast).
 */
class ChainMembersTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Node.java", """
                package com.acme;

                public class Node {
                    public String text() {
                        return "a";
                    }

                    public static String describe(Node node) {
                        return "a";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/TextNode.java", """
                package com.acme;

                public class TextNode extends Node {
                    public String wholeText() {
                        return "a";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Nodes.java", """
                package com.acme;

                import java.util.ArrayList;

                public class Nodes extends ArrayList<Node> {
                    public Node first() {
                        return get(0);
                    }

                    public String[] names() {
                        return new String[] {"a"};
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/NodesTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class NodesTest {
                    @Test
                    public void testGet() {
                        Nodes nodes = new Nodes();
                        nodes.add(new Node());
                        assertEquals("a", nodes.get(0).text());
                    }

                    @Test
                    public void testFirst() {
                        Nodes nodes = new Nodes();
                        assertEquals("a", nodes.first().text());
                    }

                    @Test
                    public void testArrayLength() {
                        int[] values = {1, 2};
                        assertEquals(2, values.length);
                    }

                    @Test
                    public void testCast() {
                        Node node = new TextNode();
                        assertEquals("a", ((TextNode) node).wholeText().trim());
                    }

                    @Test
                    public void testCastOfACall() {
                        Nodes nodes = new Nodes();
                        assertEquals("a", ((TextNode) nodes.get(0)).wholeText());
                    }

                    @Test
                    public void testCastArgument() {
                        Node node = new TextNode();
                        assertEquals("a", Node.describe((TextNode) node).trim());
                    }

                    @Test
                    public void testCallArrayLength() {
                        Nodes nodes = new Nodes();
                        assertEquals(1, nodes.names().length);
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheTypeOfAChainAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        // The field `length` of an array is resolved (Step 32: its declarator, with no parent, could not be resolved)
        assertEquals(Map.of(), fixture.messages("Unable to process field"));
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertTrue(candidatesAfter(datapoints.get("testGet_split_0"), "assertEquals(\"a\",nodes.get(0).").contains("text"));
        assertTrue(candidatesAfter(datapoints.get("testFirst_split_0"), "assertEquals(\"a\",nodes.first().").contains("text"));
        assertTrue(candidatesAfter(datapoints.get("testArrayLength_split_0"), "assertEquals(2,values.").contains("length"));
        assertTrue(candidatesAfter(datapoints.get("testCallArrayLength_split_0"), "assertEquals(1,nodes.names().").contains("length"));
        // A cast: the members of its type, then the chain goes on
        assertTrue(candidatesAfter(datapoints.get("testCast_split_0"), "assertEquals(\"a\",((TextNode)node).").contains("wholeText"));
        assertTrue(candidatesAfter(datapoints.get("testCast_split_0"), "assertEquals(\"a\",((TextNode)node).wholeText().").contains("trim"));
        assertTrue(candidatesAfter(datapoints.get("testCastOfACall_split_0"), "assertEquals(\"a\",((TextNode)nodes.get(0)).").contains("wholeText"));
        // A cast as the argument of a call: the members of the type returned by the call, not of the cast
        List<String> afterCall = candidatesAfter(datapoints.get("testCastArgument_split_0"), "assertEquals(\"a\",Node.describe((TextNode)node).");
        assertTrue(afterCall.contains("trim"));
        assertFalse(afterCall.contains("wholeText"));
    }
}
