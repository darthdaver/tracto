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
 * End-to-end test of the generation of the tokens dataset ({@link TokensDataset#generate}) on a small repository: the
 * oracles stage runs first with the configuration committed in the module, then the tokens stage on its output. Checks
 * that the identifiers in scope at the position of each target are offered as candidate tokens, and only them.
 */
class TokensDatasetTest {

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

                    public int add(int a, int b) {
                        return a + b;
                    }

                    public void accumulate(int value) {
                        total += value;
                    }

                    public int total() {
                        return total;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/CalcTest.java", """
                package com.acme;

                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;
                import static org.junit.jupiter.api.Assertions.*;

                public class CalcTest {
                    @Test
                    public void testLoopBody() {
                        Calc calc = new Calc();
                        for (int i = 0; i < 2; i++) {
                            int doubled = calc.add(i, i);
                            assertEquals(doubled, calc.add(i, i));
                        }
                    }

                    @Test
                    public void testForEach() {
                        Calc calc = new Calc();
                        for (Integer value : List.of(1, 2)) {
                            assertEquals(value, calc.add(value, 0));
                        }
                    }

                    @Test
                    public void testLambdaBlock() {
                        Calc calc = new Calc();
                        List.of(1, 2).forEach(v -> {
                            int sum = calc.add(v, 0);
                            assertEquals(sum, calc.add(v, 0));
                        });
                    }

                    @ParameterizedTest
                    @ValueSource(ints = {1, 2})
                    public void testParameter(int input) {
                        Calc calc = new Calc();
                        assertEquals(input, calc.add(input, 0));
                    }

                    @Test
                    public void testOutOfScope() {
                        Calc calc = new Calc();
                        for (int i = 0; i < 1; i++) {
                            int inner = calc.add(i, 1);
                            calc.accumulate(inner);
                        }
                        List.of(1).forEach(x -> calc.accumulate(x));
                        int total = calc.total();
                        assertEquals(total, calc.total());
                    }
                }
                """);
    }

    @Test
    void theVariablesOfNestedBlocksAndTheParametersOfTheTestAreOfferedAsCandidates() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        // Every oracle produces its tokens datapoint
        assertEquals(List.of("testForEach_split_0", "testLambdaBlock_split_0", "testLoopBody_split_0", "testOutOfScope_split_0",
                "testParameter_split_0"), fixture.tokensDatapoints().keySet().stream().sorted().toList());
    }

    @Test
    void theVariablesOutOfScopeAtThePositionOfTheTargetAreNotOffered() throws IOException {
        fixture.generate();

        // At the first token after `assertEquals(` the candidates include the variable declared before the target,
        // not the variables of the previous loop nor the parameter of the previous lambda
        List<String> candidates = candidatesAfter(fixture.tokensDatapoints().get("testOutOfScope_split_0"), "assertEquals(");
        assertTrue(candidates.contains("total"), candidates.toString());
        assertTrue(candidates.contains("calc"), candidates.toString());
        assertFalse(candidates.contains("inner"), candidates.toString());
        assertFalse(candidates.contains("i"), candidates.toString());
        assertFalse(candidates.contains("x"), candidates.toString());
    }
}
