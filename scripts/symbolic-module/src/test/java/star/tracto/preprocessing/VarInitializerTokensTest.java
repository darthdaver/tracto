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
 * End-to-end test of the type of a local variable declared with {@code var} whose initializer the symbol solver cannot
 * type as a whole (Step 34): an argument of a type of a dependency ({@code org.dom4j.Element}, the dependencies are not
 * given to the symbol solver), an unqualified call (the solver tries the static imports of the dependencies). The
 * members of the type of the initializer are offered after the variable, in the body of a lambda too; not when the type
 * is ambiguous (overloads with different return types).
 */
class VarInitializerTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Config.java", """
                package com.acme;

                import org.dom4j.Element;

                public class Config {
                    public static Builder createNew() {
                        return new Builder();
                    }

                    public static Config load(String name, Element root) {
                        return new Config();
                    }

                    public static Config pick(Element root) {
                        return new Config();
                    }

                    public static Builder pick(String name) {
                        return new Builder();
                    }

                    public static java.util.List<String> names(Element root) {
                        return java.util.List.of("a");
                    }

                    public String getId() {
                        return "id";
                    }

                    public void check() {
                    }

                    public static class Builder {
                        public Builder root(Element root) {
                            return this;
                        }

                        public Config build() {
                            return new Config();
                        }
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ConfigTest.java", """
                package com.acme;

                import org.dom4j.Element;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.*;
                import static com.acme.Config.createNew;

                public class ConfigTest {

                    private Element element;

                    private Config newConfig(String name) {
                        return new Config();
                    }

                    @Test
                    public void testLoad() {
                        var config = Config.load("a", element);
                        assertNotNull(config.getId());
                    }

                    @Test
                    public void testBuilder() {
                        var config = Config.createNew().root(element).build();
                        assertNotNull(config.getId());
                    }

                    @Test
                    public void testLambda() {
                        var config = Config.createNew().root(element).build();
                        assertDoesNotThrow(() -> config.check());
                    }

                    @Test
                    public void testTestClassMethod() {
                        var config = newConfig("a");
                        assertNotNull(config.getId());
                    }

                    @Test
                    public void testStaticImport() {
                        var builder = createNew().root(element);
                        assertNotNull(builder.build());
                    }

                    @Test
                    public void testInheritedGeneric() {
                        var names = Config.names(element);
                        var first = names.iterator().next();
                        assertNotNull(first.trim());
                    }

                    @Test
                    public void testAmbiguous() {
                        var picked = Config.pick(element);
                        assertNotNull(picked.getId());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheTypeOfTheInitializerAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens().entrySet().stream()
                .filter(e -> !e.getKey().startsWith("testAmbiguous"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // A static call with an argument of a type of a dependency
        assertTrue(candidatesAfter(datapoints.get("testLoad_split_0"), "assertNotNull(config.").contains("getId"));
        // A chain of calls, one with an argument of a type of a dependency
        assertTrue(candidatesAfter(datapoints.get("testBuilder_split_0"), "assertNotNull(config.").contains("getId"));
        // In the body of a lambda
        assertTrue(candidatesAfter(datapoints.get("testLambda_split_0"), "assertDoesNotThrow(()->config.").contains("check"));
        // An unqualified call of a method of the test class (the static imports of the dependencies cannot be resolved)
        assertTrue(candidatesAfter(datapoints.get("testTestClassMethod_split_0"), "assertNotNull(config.").contains("getId"));
        // An unqualified call of a method imported statically
        assertTrue(candidatesAfter(datapoints.get("testStaticImport_split_0"), "assertNotNull(builder.").contains("build"));
        // A variable declared with var as the scope of a call, typed in turn; a method of a generic type of the JDK
        // (List<String>.iterator().next())
        assertTrue(candidatesAfter(datapoints.get("testInheritedGeneric_split_0"), "assertNotNull(first.").contains("trim"));
        // Overloads of the same arity with different return types: the type is not guessed
        assertFalse(candidatesAfter(datapoints.get("testAmbiguous_split_0"), "assertNotNull(picked.").contains("getId"));
    }
}
