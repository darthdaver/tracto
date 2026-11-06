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
 * End-to-end test of the members imported statically, offered as candidate tokens (Step 25): the static methods, fields
 * and enum constants of the classes of the project and of the JDK, imported one by one or with a wildcard, following the
 * access rules of Java. The static imports of the libraries (JUnit included) are not offered: their classes have no
 * declaration (the dependencies are a separate step).
 */
class StaticImportsTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/util/Times.java", """
                package com.acme.util;

                public final class Times {
                    public static final int LIMIT = 10;
                    static final int PACKAGE_LIMIT = 20;

                    public static String toTimecode(long nanos) {
                        return Long.toString(nanos);
                    }

                    public static long fromTimecode(String timecode) {
                        return Long.parseLong(timecode);
                    }

                    static int hidden() {
                        return 1;
                    }

                    private static int secret() {
                        return 2;
                    }

                    public int instanceMethod() {
                        return 3;
                    }

                    public static class Grid {
                        public static boolean isPowerOfTwo(int n) {
                            return Integer.bitCount(n) == 1;
                        }
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/util/Strings.java", """
                package com.acme.util;

                public final class Strings {
                    public static String normalise(String text) {
                        return text.trim();
                    }

                    public static String resolve(String base, String relative) {
                        return base + relative;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Mode.java", """
                package com.acme;

                public enum Mode {
                    xhtml, base;

                    public int codepointForName(String name) {
                        return name.length();
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/Helpers.java", """
                package com.acme;

                public final class Helpers {
                    public static boolean listEquals(String a, String b) {
                        return a.equals(b);
                    }
                }
                """);
        // The focal class of the test class (the oracles stage needs one in the package of the test class)
        fixture.write("src/main/java/com/acme/Clock.java", """
                package com.acme;

                public class Clock {
                    public long now() {
                        return 1L;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ClockTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                import static com.acme.util.Times.*;
                import static com.acme.util.Times.Grid.isPowerOfTwo;
                import static com.acme.util.Strings.normalise;
                import static com.acme.Mode.*;
                import static com.acme.Helpers.listEquals;
                import static java.lang.Math.max;

                public class ClockTest {

                    @Test
                    public void testWildcardMethod() {
                        assertEquals("1", toTimecode(1L));
                    }

                    @Test
                    public void testWildcardField() {
                        assertEquals(10, LIMIT);
                    }

                    @Test
                    public void testSingleMethod() {
                        assertEquals("a", normalise(" a "));
                    }

                    @Test
                    public void testNestedClassMethod() {
                        assertTrue(isPowerOfTwo(4));
                    }

                    @Test
                    public void testTestClassMethod() {
                        assertTrue(listEquals("a", "a"));
                    }

                    @Test
                    public void testJdkMethod() {
                        assertEquals(2, max(1, 2));
                    }

                    @Test
                    public void testEnumConstant() {
                        assertEquals(3, xhtml.codepointForName("amp"));
                    }

                    @Test
                    public void testQualifiedEnumConstant() {
                        assertEquals(1, Mode.base.codepointForName("a"));
                    }
                }
                """);
        // A field of the test class shadows the enum constant imported with the same name
        fixture.write("src/main/java/com/acme/Shadow.java", """
                package com.acme;

                public class Shadow {
                    public int size() {
                        return 0;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ShadowTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                import static com.acme.Mode.*;

                public class ShadowTest {
                    String xhtml = "x";

                    @Test
                    public void testShadowedEnumConstant() {
                        assertEquals(1, xhtml.length());
                    }
                }
                """);
    }

    @Test
    void theStaticallyImportedMembersAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        assertEquals(List.of("testEnumConstant_split_0", "testJdkMethod_split_0", "testNestedClassMethod_split_0",
                "testQualifiedEnumConstant_split_0", "testShadowedEnumConstant_split_0", "testSingleMethod_split_0",
                "testTestClassMethod_split_0", "testWildcardField_split_0", "testWildcardMethod_split_0"), fixture.tokensDatapoints().keySet().stream().sorted().toList());
    }

    @Test
    void onlyTheVisibleStaticMembersAreOffered() throws IOException {
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testWildcardMethod_split_0"), "assertEquals(\"1\",");
        // Imported with a wildcard: the public static members, the enum constants
        for (String member : List.of("toTimecode", "fromTimecode", "LIMIT", "xhtml", "base")) {
            assertTrue(candidates.contains(member), member + " " + candidates);
        }
        // Imported one by one: only the member named, from a nested class too, from a class of the tests, from the JDK
        for (String member : List.of("normalise", "isPowerOfTwo", "listEquals", "max")) {
            assertTrue(candidates.contains(member), member + " " + candidates);
        }
        assertFalse(candidates.contains("resolve"), candidates.toString());
        // Not visible from another package (package-private, private), not static
        for (String member : List.of("hidden", "PACKAGE_LIMIT", "secret", "instanceMethod")) {
            assertFalse(candidates.contains(member), member + " " + candidates);
        }
        // A class of a library (JUnit): not offered
        assertFalse(candidates.contains("assertEquals"), candidates.toString());
    }

    @Test
    void theMembersOfAStaticallyImportedEnumConstantAreOffered() throws IOException {
        fixture.generate();

        JsonNode datapoint = fixture.tokensDatapoints().get("testEnumConstant_split_0");
        List<String> candidates = candidatesAfter(datapoint, "assertEquals(3,xhtml.");
        assertTrue(candidates.contains("codepointForName"), candidates.toString());
    }

    @Test
    void theMembersOfAnEnumConstantAfterTheNameOfItsEnumAreOffered() throws IOException {
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testQualifiedEnumConstant_split_0"), "assertEquals(1,Mode.base.");
        assertTrue(candidates.contains("codepointForName"), candidates.toString());
    }

    @Test
    void aFieldOfTheTestClassShadowsAStaticallyImportedMember() throws IOException {
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testShadowedEnumConstant_split_0"), "assertEquals(1,xhtml.");
        // The members of the field (a String), not the ones of the enum constant
        assertTrue(candidates.contains("length"), candidates.toString());
        assertFalse(candidates.contains("codepointForName"), candidates.toString());
    }
}
