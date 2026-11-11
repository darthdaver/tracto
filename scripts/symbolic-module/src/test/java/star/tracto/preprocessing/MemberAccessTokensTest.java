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
 * End-to-end test of the members offered after a value and after the name of a class (Step 37): the members the test
 * class can access, as in Java. A public member always; a member that is not private when its class is in the package
 * of the test class; any member of a class nested in the test class; the members of an interface are public (and its
 * fields static). After the name of a class, its static members only. Before, the rules were combined with OR: after a
 * value, "not protected" let the private members through ({@code String.value}); after the name of a class, "not
 * private" let the instance methods through ({@code String.length}, {@code Object.clone}).
 */
class MemberAccessTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Box.java", """
                package com.acme;

                public class Box {
                    public String label = "a";
                    private String hidden = "b";

                    public static Box create() {
                        return new Box();
                    }

                    public int size() {
                        return 1;
                    }

                    int count() {
                        return 1;
                    }

                    protected int peek() {
                        return 1;
                    }

                    private int secret() {
                        return 1;
                    }

                    public static class Inner {
                        int depth() {
                            return 1;
                        }
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/other/Remote.java", """
                package com.acme.other;

                public class Remote {
                    public static Remote make() {
                        return new Remote();
                    }

                    static Remote helper() {
                        return new Remote();
                    }

                    public int ping() {
                        return 1;
                    }

                    int local() {
                        return 1;
                    }

                    protected int guarded() {
                        return 1;
                    }

                    private int own() {
                        return 1;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/other/Shape.java", """
                package com.acme.other;

                public interface Shape {
                    int SIDES = 4;

                    int area();
                }
                """);
        fixture.write("src/test/java/com/acme/BoxTest.java", """
                package com.acme;

                import com.acme.other.Remote;
                import com.acme.other.Shape;
                import java.util.stream.Stream;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class BoxTest {

                    private static class Helper {
                        private int x = 1;
                    }

                    private Box box = new Box();
                    private Remote remote = new Remote();
                    private Shape shape;
                    private Helper helper = new Helper();
                    private Box.Inner inner = new Box.Inner();
                    private String text = "a";

                    @Test
                    public void testValueSamePackage() {
                        assertEquals(1, box.count());
                    }

                    @Test
                    public void testValueOtherPackage() {
                        assertEquals(1, remote.ping());
                    }

                    @Test
                    public void testInterface() {
                        assertEquals(4, shape.area());
                    }

                    @Test
                    public void testInterfaceField() {
                        assertEquals(4, Shape.SIDES);
                    }

                    @Test
                    public void testClassNameSamePackage() {
                        assertNotNull(Box.create());
                    }

                    @Test
                    public void testClassNameOtherPackage() {
                        assertNotNull(Remote.make());
                    }

                    @Test
                    public void testJdkValue() {
                        assertEquals(1, text.length());
                    }

                    @Test
                    public void testJdkClassName() {
                        assertEquals("1", String.valueOf(1));
                    }

                    @Test
                    public void testNestedInTestClass() {
                        assertEquals(1, helper.x);
                    }

                    @Test
                    public void testConstructorResult() {
                        assertEquals(1, new Box().size());
                    }

                    @Test
                    public void testCallResult() {
                        assertEquals(1, Box.create().count());
                    }

                    @Test
                    public void testMethodReference() {
                        assertEquals(1, Stream.of(box).map(Box::size).count());
                    }

                    @Test
                    public void testNestedSamePackage() {
                        assertEquals(1, inner.depth());
                    }
                }
                """);
    }

    @Test
    void theMembersOfferedAreTheOnesTheTestClassCanAccess() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // A class of the package of the test class: its members that are not private
        assertOffered(datapoints, "testValueSamePackage_split_0", "assertEquals(1,box.",
                List.of("size", "count", "peek", "label"), List.of("secret", "hidden"));
        // A class of another package: its public members only
        assertOffered(datapoints, "testValueOtherPackage_split_0", "assertEquals(1,remote.",
                List.of("ping"), List.of("local", "guarded", "own"));
        // The members of an interface are public, its fields static
        assertOffered(datapoints, "testInterface_split_0", "assertEquals(4,shape.", List.of("area"), List.of());
        assertOffered(datapoints, "testInterfaceField_split_0", "assertEquals(4,Shape.", List.of("SIDES"), List.of("area"));
        // After the name of a class, its static members only (and only the public ones from another package)
        assertOffered(datapoints, "testClassNameSamePackage_split_0", "assertNotNull(Box.",
                List.of("create"), List.of("size", "count", "peek", "secret", "label", "hidden"));
        assertOffered(datapoints, "testClassNameOtherPackage_split_0", "assertNotNull(Remote.",
                List.of("make"), List.of("helper", "ping", "local", "guarded", "own"));
        // The JDK: no private or package-private member after a value; no instance method after the name of a class
        assertOffered(datapoints, "testJdkValue_split_0", "assertEquals(1,text.",
                List.of("length", "isEmpty", "hashCode"), List.of("value", "coder", "isLatin1", "COMPACT_STRINGS", "clone", "finalize"));
        assertOffered(datapoints, "testJdkClassName_split_0", "assertEquals(\"1\",String.",
                List.of("valueOf", "format", "CASE_INSENSITIVE_ORDER"), List.of("length", "isEmpty", "clone", "finalize", "hashCode", "value", "COMPACT_STRINGS"));
        // A class nested in the test class: all its members, private ones included
        assertOffered(datapoints, "testNestedInTestClass_split_0", "assertEquals(1,helper.", List.of("x"), List.of());
        // The result of a constructor is a value
        assertOffered(datapoints, "testConstructorResult_split_0", "assertEquals(1,new Box().",
                List.of("size", "count", "peek", "label"), List.of("secret", "hidden"));
        // The result of a call is a value
        assertOffered(datapoints, "testCallResult_split_0", "assertEquals(1,Box.create().",
                List.of("size", "count", "peek", "label"), List.of("secret", "hidden"));
        // A method reference on a class: its static methods and its instance methods (not its fields)
        assertOffered(datapoints, "testMethodReference_split_0", "assertEquals(1,Stream.of(box).map(Box::",
                List.of("size", "count", "create"), List.of("secret", "label", "hidden"));
        // A class nested in a class of the package of the test class
        assertOffered(datapoints, "testNestedSamePackage_split_0", "assertEquals(1,inner.", List.of("depth"), List.of());
    }

    private static void assertOffered(Map<String, JsonNode> datapoints, String datapoint, String partialSnippet,
                                      List<String> offered, List<String> notOffered) {
        List<String> candidates = candidatesAfter(datapoints.get(datapoint), partialSnippet);
        for (String member : offered) {
            assertTrue(candidates.contains(member), datapoint + ": " + member + " not offered in " + candidates);
        }
        for (String member : notOffered) {
            assertFalse(candidates.contains(member), datapoint + ": " + member + " offered in " + candidates);
        }
    }
}
