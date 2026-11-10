package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of the members offered after a chain (Step 31): a call on a class extending a generic class of the
 * JDK ({@code nodes.get(0)}, {@code Nodes extends ArrayList<Node>}: the values of the type parameters of the ancestors),
 * and the {@code length} of an array (a variable or the result of a call). The casts are not covered yet.
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
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertTrue(candidatesAfter(datapoints.get("testGet_split_0"), "assertEquals(\"a\",nodes.get(0).").contains("text"));
        assertTrue(candidatesAfter(datapoints.get("testFirst_split_0"), "assertEquals(\"a\",nodes.first().").contains("text"));
        assertTrue(candidatesAfter(datapoints.get("testArrayLength_split_0"), "assertEquals(2,values.").contains("length"));
        assertTrue(candidatesAfter(datapoints.get("testCallArrayLength_split_0"), "assertEquals(1,nodes.names().").contains("length"));
    }
}
