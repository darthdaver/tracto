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
 * End-to-end test of the shapes of the last oracles lost by dataset v3 (Step 44): the members of a {@code new} in
 * parentheses ({@code (new Spec()).size()}), a package-private field inherited from a package-private class
 * ({@code not.memo}), a variable named {@code to} (a keyword of the lexer: module declarations), the members of a
 * string literal ({@code "a".equals(x)}) and a fully qualified class name after {@code new}
 * ({@code new java.lang.StringBuilder("ab")}).
 */
class ValueShapesTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Spec.java", """
                package com.acme;

                public class Spec {
                    private int size = 8;

                    public Spec() {
                    }

                    public Spec(int size) {
                        this.size = size;
                    }

                    public int size() {
                        return size;
                    }

                    public boolean needsIv() {
                        return size > 4;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Evaluator.java", """
                package com.acme;

                import java.util.HashMap;
                import java.util.Map;

                abstract class Evaluator {
                    final ThreadLocal<Map<String, Boolean>> memo = ThreadLocal.withInitial(HashMap::new);

                    void reset() {
                        memo.remove();
                    }

                    boolean matches() {
                        return true;
                    }

                    static class Not extends Evaluator {
                        Not() {
                        }
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/SpecTest.java", """
                package com.acme;

                import java.io.File;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class SpecTest {
                    @Test
                    public void testParenthesizedNew() {
                        assertEquals(8, (new Spec()).size());
                    }

                    @Test
                    public void testParenthesizedNewWithArgument() {
                        int size = 2;
                        assertTrue((new Spec(size)).needsIv() == false);
                    }

                    @Test
                    public void testPrimitiveCast() {
                        Spec spec = new Spec();
                        assertEquals(8L, (long) spec.size());
                    }

                    @Test
                    public void testInheritedField() {
                        Evaluator.Not not = new Evaluator.Not();
                        assertTrue(not.memo.get().isEmpty());
                    }

                    @Test
                    public void testVariableNamedTo() {
                        File to = new File("target/x");
                        assertTrue(to.mkdirs());
                    }

                    @Test
                    public void testStringLiteral() {
                        String protocol = "mysql";
                        assertTrue("mysql".equals(protocol));
                    }

                    @Test
                    public void testQualifiedNew() {
                        assertEquals(2, new java.lang.StringBuilder("ab").length());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheseValuesAreCandidates() throws IOException {
        fixture.generate();

        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertEquals(Map.of(), fixture.unexpectedTokens(), datapoints.keySet().toString());
        // The members of a new in parentheses
        List<String> parenthesized = candidatesAfter(datapoints.get("testParenthesizedNew_split_0"), "assertEquals(8,(new Spec()).");
        assertTrue(parenthesized.containsAll(List.of("size", "needsIv")), parenthesized.toString());
        List<String> withArgument = candidatesAfter(datapoints.get("testParenthesizedNewWithArgument_split_0"), "assertTrue((new Spec(size)).");
        assertTrue(withArgument.containsAll(List.of("size", "needsIv")), withArgument.toString());
        // A parenthesized type is not a value: the names after a primitive cast are the ones of the root
        List<String> afterCast = candidatesAfter(datapoints.get("testPrimitiveCast_split_0"), "assertEquals(8L,(long )");
        assertTrue(afterCast.contains("spec"), afterCast.toString());
        // A package-private field inherited from a package-private class of the same package
        List<String> inherited = candidatesAfter(datapoints.get("testInheritedField_split_0"), "assertTrue(not.");
        assertTrue(inherited.containsAll(List.of("memo", "matches")), inherited.toString());
        // A variable named to
        List<String> to = candidatesAfter(datapoints.get("testVariableNamedTo_split_0"), "assertTrue(to.");
        assertTrue(to.containsAll(List.of("mkdirs", "exists")), to.toString());
        // The members of a string literal
        List<String> literal = candidatesAfter(datapoints.get("testStringLiteral_split_0"), "assertTrue(\"mysql\".");
        assertTrue(literal.containsAll(List.of("equals", "length", "formatted")), literal.toString());
        // A fully qualified class name after new: the first names of the packages, the subpackages, the public
        // classes of a package, the members of the value created
        JsonNode qualifiedNew = datapoints.get("testQualifiedNew_split_0");
        List<String> first = candidatesAfter(qualifiedNew, "assertEquals(2,new ");
        assertTrue(first.containsAll(List.of("java", "com", "Spec")), first.toString());
        assertFalse(first.contains("lang") || first.contains("util"), first.toString());
        List<String> java = candidatesAfter(qualifiedNew, "assertEquals(2,new java.");
        assertTrue(java.containsAll(List.of("lang", "util")), java.toString());
        List<String> lang = candidatesAfter(qualifiedNew, "assertEquals(2,new java.lang.");
        assertTrue(lang.containsAll(List.of("StringBuilder", "String", "reflect")), lang.toString());
        assertFalse(lang.contains("AbstractStringBuilder"), lang.toString());
        List<String> created = candidatesAfter(qualifiedNew, "assertEquals(2,new java.lang.StringBuilder(\"ab\").");
        assertTrue(created.containsAll(List.of("length", "append")), created.toString());
        // A package is not offered after a value
        assertFalse(candidatesAfter(qualifiedNew, "assertEquals(2,new java.lang.StringBuilder(\"ab\").").contains("java"));
    }
}
