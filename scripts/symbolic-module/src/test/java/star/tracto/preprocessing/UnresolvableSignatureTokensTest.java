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
 * End-to-end test of the members of a class one of whose methods has a type of a dependency in its signature (Step 33):
 * the symbol solver cannot resolve that type ({@code org.dom4j.Element}, the dependencies are not given to it), and the
 * lookup of all the methods of the class failed as a whole. The other methods are offered, with their return types (the
 * chain goes on), the inherited ones too; static (after the class name) and instance members (after a variable). The
 * accessors of a record too, whose modifiers could not be read (they are neither in the source nor in a class file).
 */
class UnresolvableSignatureTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Color.java", """
                package com.acme;

                public class Color {
                    public String getName() {
                        return "red";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Base.java", """
                package com.acme;

                public class Base {
                    public String baseName() {
                        return "base";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Styles.java", """
                package com.acme;

                import org.dom4j.Element;

                public class Styles extends Base {
                    public static Color toColor(String value) {
                        return new Color();
                    }

                    public Color palette() {
                        return new Color();
                    }

                    public static Styles load(Element root) {
                        return new Styles();
                    }

                    public void write(Element root) {
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Entry.java", """
                package com.acme;

                public record Entry(String dirId, Color color) {
                }
                """);
        fixture.write("src/test/java/com/acme/StylesTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class StylesTest {
                    @Test
                    public void testToColor() {
                        assertEquals("red", Styles.toColor("FF0000").getName());
                    }

                    @Test
                    public void testPalette() {
                        Styles styles = new Styles();
                        assertEquals("red", styles.palette().getName());
                    }

                    @Test
                    public void testRecord() {
                        Entry entry = new Entry("a", new Color());
                        assertEquals("red", entry.color().getName());
                    }

                    @Test
                    public void testBaseName() {
                        Styles styles = new Styles();
                        assertEquals("base", styles.baseName());
                    }
                }
                """);
    }

    @Test
    void theMethodsOfAClassWithAnUnresolvableSignatureAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // A static method, then the members of its return type
        assertTrue(candidatesAfter(datapoints.get("testToColor_split_0"), "assertEquals(\"red\",Styles.").contains("toColor"));
        assertTrue(candidatesAfter(datapoints.get("testToColor_split_0"), "assertEquals(\"red\",Styles.toColor(\"FF0000\").").contains("getName"));
        // An instance method, then the members of its return type; an inherited method
        assertTrue(candidatesAfter(datapoints.get("testPalette_split_0"), "assertEquals(\"red\",styles.").contains("palette"));
        assertTrue(candidatesAfter(datapoints.get("testPalette_split_0"), "assertEquals(\"red\",styles.palette().").contains("getName"));
        assertTrue(candidatesAfter(datapoints.get("testBaseName_split_0"), "assertEquals(\"base\",styles.").contains("baseName"));
        // The accessors of a record, then the members of their types
        assertTrue(candidatesAfter(datapoints.get("testRecord_split_0"), "assertEquals(\"red\",entry.").contains("color"));
        assertTrue(candidatesAfter(datapoints.get("testRecord_split_0"), "assertEquals(\"red\",entry.color().").contains("getName"));
    }
}
