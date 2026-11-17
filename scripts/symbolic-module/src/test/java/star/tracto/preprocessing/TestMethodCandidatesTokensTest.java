package star.tracto.preprocessing;

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
 * End-to-end test of the test methods, which are not candidates (Step 46): the methods annotated with any annotation of
 * a test of JUnit ({@code @Test}, {@code @ParameterizedTest}, {@code @RepeatedTest}, {@code @TestFactory},
 * {@code @TestTemplate}, {@code @Theory} of JUnit 4, by simple or qualified name), in the test class and in its
 * superclasses, whether the test class can be resolved by the symbol solver or not ({@code TestClassScope}). Of the
 * test methods of the test class itself, the split keeps the prefixes ({@code <name>_split_0}) of the ones with an
 * assertion, with their annotations; the ones of a superclass are inherited as they are.
 */
class TestMethodCandidatesTokensTest {

    private static final String TEST_METHODS = """
                @Test
                public void plainTest() {
                }

                @org.junit.jupiter.api.Test
                public void qualifiedTest() {
                }

                @ParameterizedTest
                @ValueSource(ints = {1, 2})
                public void parameterizedTest(int value) {
                }

                @org.junit.jupiter.params.ParameterizedTest
                @ValueSource(ints = {1, 2})
                public void qualifiedParameterizedTest(int value) {
                }

                @RepeatedTest(2)
                public void repeatedTest() {
                }

                @TestFactory
                public List<Object> testFactory() {
                    return List.of();
                }

                @TestTemplate
                public void testTemplate() {
                }

                @ParameterizedTest
                @ValueSource(ints = {1, 2})
                public void parameterizedCheck(int value) {
                    assertTrue(value > 0);
                }

                private int twice(int x) {
                    return x * 2;
                }
            """;

    private static final String IMPORTS = """
            import java.util.List;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.RepeatedTest;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.TestFactory;
            import org.junit.jupiter.api.TestTemplate;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.Arguments;
            import org.junit.jupiter.params.provider.ValueSource;
            import org.junit.experimental.theories.Theory;
            import static org.junit.jupiter.api.Assertions.*;
            """;

    private static final List<String> TEST_METHOD_NAMES = List.of("plainTest", "qualifiedTest", "parameterizedTest",
            "qualifiedParameterizedTest", "repeatedTest", "testFactory", "testTemplate", "inheritedParameterizedTest",
            "inheritedQualifiedParameterizedTest", "inheritedTest", "inheritedQualifiedTest", "inheritedRepeatedTest",
            "inheritedTestFactory", "inheritedTestTemplate", "inheritedTheory",
            "testResolved", "testUnresolved", "testResolvedLambda", "testUnresolvedLambda",
            // A test method of the test class with an assertion: the split class keeps its prefix, with its annotations
            "parameterizedCheck", "parameterizedCheck_split_0");

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Calc.java", """
                package com.acme;

                public class Calc {
                    public int one() {
                        return 1;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Counter.java", """
                package com.acme;

                public class Counter {
                    public int count() {
                        return 0;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/BaseTest.java", "package com.acme;\n\n" + IMPORTS + """

                public abstract class BaseTest {
                    @ParameterizedTest
                    @ValueSource(ints = {1, 2})
                    public void inheritedParameterizedTest(int value) {
                    }

                    @org.junit.jupiter.params.ParameterizedTest
                    @ValueSource(ints = {1, 2})
                    public void inheritedQualifiedParameterizedTest(int value) {
                    }

                    @Test
                    public void inheritedTest() {
                    }

                    @org.junit.Test
                    public void inheritedQualifiedTest() {
                    }

                    @RepeatedTest(2)
                    public void inheritedRepeatedTest() {
                    }

                    @TestFactory
                    public List<Object> inheritedTestFactory() {
                        return List.of();
                    }

                    @TestTemplate
                    public void inheritedTestTemplate() {
                    }

                    @Theory
                    public void inheritedTheory() {
                    }

                    protected int base() {
                        return 1;
                    }
                }
                """);
        // Resolved by the symbol solver as a whole (no signature with a type of JUnit: the test factories return a List,
        // the annotations are not resolved)
        fixture.write("src/test/java/com/acme/CalcTest.java", "package com.acme;\n\n" + IMPORTS + """

                public class CalcTest extends BaseTest {
                """ + TEST_METHODS + """

                    @Test
                    public void testResolved() {
                        int two = 2;
                        assertEquals(two, twice(1));
                    }

                    @Test
                    public void testResolvedLambda() {
                        assertDoesNotThrow(() -> twice(1));
                    }
                }
                """);
        // provider() returns a type of a library that is not on the classpath: the test class cannot
        // be resolved as a whole, its methods are taken from its declaration (TestClassScope)
        fixture.write("src/test/java/com/acme/CounterTest.java", "package com.acme;\n\n" + IMPORTS + """

                public class CounterTest extends BaseTest {
                    static Stream<Arguments> provider() {
                        return Stream.of();
                    }
                """ + TEST_METHODS + """

                    @Test
                    public void testUnresolved() {
                        int two = 2;
                        assertEquals(two, twice(1));
                    }

                    @Test
                    public void testUnresolvedLambda() {
                        assertDoesNotThrow(() -> twice(1));
                    }
                }
                """);
    }

    @Test
    void theTestMethodsAreNotCandidates() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        assertTrue(fixture.tokensDatapoints().keySet().containsAll(List.of("testResolved_split_0", "testUnresolved_split_0")),
                fixture.tokensDatapoints().keySet().toString());
        List<String> offered = new ArrayList<>();
        // At the root (the void methods are not candidates there, Step 39, but a test factory returns a value) and in the
        // body of a lambda (where the void methods are candidates)
        for (String[] position : new String[][]{
                {"testResolved_split_0", "assertEquals(two,"}, {"testUnresolved_split_0", "assertEquals(two,"},
                {"testResolvedLambda_split_0", "assertDoesNotThrow(()->"}, {"testUnresolvedLambda_split_0", "assertDoesNotThrow(()->"}}) {
            List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get(position[0]), position[1]);
            // The other methods of the test class and of its superclass are candidates
            assertTrue(candidates.containsAll(List.of("twice", "base")), position[0] + " " + candidates);
            for (String testMethod : TEST_METHOD_NAMES) {
                if (candidates.contains(testMethod)) {
                    offered.add(position[0] + ":" + testMethod);
                }
            }
        }
        assertEquals(List.of(), offered);
    }
}
