package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
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
 * End-to-end test of the block lambdas of one statement (Step 29): the normalization writes {@code () -> { a(); }} as
 * {@code () -> a()}, so the target is a single statement on one line, and its token steps are the tokens of the
 * language.
 */
class BlockLambdaTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Clock.java", """
                package com.acme;

                public class Clock {
                    public long now() {
                        return 1L;
                    }

                    public void stop() {
                        throw new IllegalStateException("stopped");
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ClockTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ClockTest {

                    @Test
                    public void testStop() {
                        Clock clock = new Clock();
                        assertThrows(IllegalStateException.class, () -> {
                            clock.stop();
                        });
                    }

                    @Test
                    public void testNow() {
                        Clock clock = new Clock();
                        assertDoesNotThrow(() -> {
                            clock.now();
                        });
                    }
                }
                """);
    }

    @Test
    void aBlockLambdaIsATargetOfTheTokensStage() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.messages("No enum constant"));
        assertEquals(Map.of(), fixture.unexpectedTokens());
        assertEquals(List.of("testNow_split_0", "testStop_split_0"),
                fixture.tokensDatapoints().keySet().stream().sorted().toList());
        JsonNode stop = fixture.tokensDatapoints().get("testStop_split_0");
        assertEquals("assertThrows(IllegalStateException.class, () -> clock.stop());", stop.get("target").asText());
        assertEquals(List.of("assertThrows", "(", "IllegalStateException", ".", "class", ",", "(", ")", "->",
                "clock", ".", "stop", "(", ")", ")", ";"), targetTokens(stop));
        assertEquals(List.of("assertDoesNotThrow", "(", "(", ")", "->", "clock", ".", "now", "(", ")", ")", ";"),
                targetTokens(fixture.tokensDatapoints().get("testNow_split_0")));
        assertTrue(candidatesAfter(stop, "assertThrows(IllegalStateException.class ,()->").contains("clock"));
    }

    /** The target tokens of the token steps of a datapoint (the text after the token type). */
    private static List<String> targetTokens(JsonNode datapoint) {
        List<String> tokens = new ArrayList<>();
        for (JsonNode step : datapoint.get("tokenSteps")) {
            String target = step.get("target").asText();
            tokens.add(target.substring(target.indexOf(':') + 1));
        }
        return tokens;
    }
}
