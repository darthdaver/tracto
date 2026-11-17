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
 * End-to-end test of the methods inherited by the test class (Step 48): offered without a receiver as Java allows (public,
 * protected, package-private from the same package), not the private ones (of a superclass of the project, of
 * {@code Object}: {@code wait0} since Java 19, {@code registerNatives} before), whether the test class is resolved as a
 * whole or method by method ({@code TestClassScope}). The methods of the test class itself are all offered.
 */
class InheritedMemberAccessTokensTest {

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
        // A superclass of another package
        fixture.write("src/test/java/com/acme/base/BaseTest.java", """
                package com.acme.base;

                public abstract class BaseTest {
                    protected int inheritedProtected() {
                        return 1;
                    }

                    public int inheritedPublic() {
                        return 1;
                    }

                    int otherPackageHelper() {
                        return 1;
                    }

                    private int superSecret() {
                        return 1;
                    }
                }
                """);
        // Resolved as a whole
        fixture.write("src/test/java/com/acme/CalcTest.java", """
                package com.acme;

                import com.acme.base.BaseTest;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest extends BaseTest {
                    private int twice(int x) {
                        return x * 2;
                    }

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
        // provider() returns a type of a library that is not on the classpath: the methods are taken one by one
        fixture.write("src/test/java/com/acme/CounterTest.java", """
                package com.acme;

                import com.acme.base.BaseTest;
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
    void theInheritedPrivateMethodsAreNotCandidates() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        List<String> wrong = new ArrayList<>();
        for (String[] position : new String[][]{
                {"testResolved_split_0", "assertEquals(two,"}, {"testUnresolved_split_0", "assertEquals(two,"},
                {"testResolvedLambda_split_0", "assertDoesNotThrow(()->"}, {"testUnresolvedLambda_split_0", "assertDoesNotThrow(()->"}}) {
            List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get(position[0]), position[1]);
            // The private method of the test class, the inherited methods Java gives the test class
            for (String offered : List.of("twice", "inheritedProtected", "inheritedPublic", "hashCode", "toString")) {
                if (!candidates.contains(offered)) {
                    wrong.add(position[0] + " misses " + offered);
                }
            }
            // Private in a superclass (of the project, Object since Java 19), package-private of another package
            for (String hidden : List.of("superSecret", "otherPackageHelper", "wait0", "registerNatives")) {
                if (candidates.contains(hidden)) {
                    wrong.add(position[0] + " offers " + hidden);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }
}
