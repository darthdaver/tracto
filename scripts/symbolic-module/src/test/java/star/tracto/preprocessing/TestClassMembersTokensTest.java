package star.tracto.preprocessing;

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
 * End-to-end test of the members of the test class offered as candidate tokens (Step 22): the fields of the test class
 * whatever their access modifier, the fields inherited from its superclasses that are visible in it, and the methods of
 * the test class and of its superclasses even when the signature of one of them cannot be resolved.
 */
class TestClassMembersTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Calc.java", """
                package com.acme;

                public class Calc {
                    private int total;

                    public void accumulate(int value) {
                        total += value;
                    }

                    public int total() {
                        return total;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Counter.java", """
                package com.acme;

                public class Counter {
                    public int next(int value) {
                        return value + 1;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/BaseTest.java", """
                package com.acme;

                public abstract class BaseTest {
                    protected Calc shared = new Calc();
                    Calc samePackage = new Calc();
                    private Calc hidden = new Calc();

                    protected int base() {
                        return 1;
                    }

                    private int secret() {
                        return 2;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/CalcTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest extends BaseTest {
                    Calc calc = new Calc();

                    @Test
                    public void testPackagePrivateField() {
                        calc.accumulate(1);
                        assertEquals(1, calc.total());
                    }

                    @Test
                    public void testInheritedField() {
                        shared.accumulate(1);
                        assertEquals(1, shared.total());
                    }
                }
                """);
        // provider() returns a type of a library that is not on the classpath: the test class cannot be resolved as a
        // whole (as the test classes of the reference projects using JUnit types in their signatures)
        fixture.write("src/test/java/com/acme/CounterTest.java", """
                package com.acme;

                import java.util.stream.Stream;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.provider.Arguments;
                import static org.junit.jupiter.api.Assertions.*;

                public class CounterTest extends BaseTest {
                    static Stream<Arguments> provider() {
                        return Stream.of();
                    }

                    private int twice(int x) {
                        return x * 2;
                    }

                    @Test
                    public void testHelperMethod() {
                        int four = 4;
                        assertEquals(four, twice(2));
                    }

                    @Test
                    public void testInheritedMethod() {
                        int one = 1;
                        assertEquals(one, base());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheTestClassAndOfItsSuperclassesAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        assertEquals(List.of("testHelperMethod_split_0", "testInheritedField_split_0", "testInheritedMethod_split_0",
                "testPackagePrivateField_split_0"), fixture.tokensDatapoints().keySet().stream().sorted().toList());
    }

    @Test
    void theFieldsVisibleInTheTestClassAreOfferedWhateverTheirAccessModifier() throws IOException {
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testPackagePrivateField_split_0"), "assertEquals(1,");
        // Declared in the test class without an access modifier
        assertTrue(candidates.contains("calc"), candidates.toString());
        // Inherited: protected, and without an access modifier from the same package
        assertTrue(candidates.contains("shared"), candidates.toString());
        assertTrue(candidates.contains("samePackage"), candidates.toString());
        // A private field of the superclass is not visible in the test class
        assertFalse(candidates.contains("hidden"), candidates.toString());
    }

    @Test
    void theMethodsAreOfferedWhenASignatureOfTheTestClassCannotBeResolved() throws IOException {
        fixture.generate();

        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testHelperMethod_split_0"), "assertEquals(four,");
        assertTrue(candidates.contains("twice"), candidates.toString());
        assertTrue(candidates.contains("provider"), candidates.toString());
        assertTrue(candidates.contains("base"), candidates.toString());
        // Not visible (private in the superclass), or not callable from a test (the test methods)
        assertFalse(candidates.contains("secret"), candidates.toString());
        assertFalse(candidates.contains("testHelperMethod"), candidates.toString());
        assertFalse(candidates.contains("testInheritedMethod"), candidates.toString());
    }
}
