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
 * End-to-end test of the void methods (Step 39): a call of a void method has no value, so it is valid in an assertion
 * only as the body of an expression lambda (or as a method reference); everywhere else the void methods are not
 * offered (the methods of the test class, the assertions of JUnit, {@code Object.wait}, the void methods of the
 * project). A name is left out only when all its methods are void.
 */
class VoidMethodsTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Clock.java", """
                package com.acme;

                public class Clock {
                    private long time;

                    public long now() {
                        return time;
                    }

                    public void stop() {
                        if (time < 0) {
                            throw new IllegalStateException();
                        }
                    }

                    public void reset() {
                        time = 0;
                    }

                    public void log() {
                    }

                    public String log(String prefix) {
                        return prefix + time;
                    }

                    public long check(long value) {
                        return value;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ClockTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ClockTest {
                    private void prepare() {
                    }

                    private int count() {
                        return 3;
                    }

                    @Test
                    public void testNow() {
                        Clock clock = new Clock();
                        assertEquals(count(), clock.now());
                    }

                    @Test
                    public void testLog() {
                        Clock clock = new Clock();
                        assertEquals("t0", clock.log("t"));
                    }

                    @Test
                    public void testStop() {
                        Clock clock = new Clock();
                        assertThrows(IllegalStateException.class, () -> clock.stop());
                    }

                    @Test
                    public void testPrepare() {
                        assertDoesNotThrow(() -> prepare());
                    }

                    @Test
                    public void testCheck() {
                        Clock clock = new Clock();
                        assertDoesNotThrow(() -> clock.check(clock.now()));
                    }

                    @Test
                    public void testChain() {
                        Clock clock = new Clock();
                        assertDoesNotThrow(() -> new Clock().reset());
                    }
                }
                """);
    }

    @Test
    void theVoidMethodsAreOfferedOnlyWhereACallNeedsNoValue() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // An argument: not the void methods of the test class (its test methods included) nor of Object
        List<String> argument = candidatesAfter(datapoints.get("testNow_split_0"), "assertEquals(");
        assertTrue(argument.containsAll(List.of("count", "clock", "hashCode")), argument.toString());
        assertTrue(argument.stream().noneMatch(List.of("prepare", "testNow", "testStop", "wait", "notify", "notifyAll")::contains), argument.toString());
        // A member of a value: not the void methods of its type; a name with a method that is not void is kept
        List<String> member = candidatesAfter(datapoints.get("testNow_split_0"), "assertEquals(count(),clock.");
        assertTrue(member.containsAll(List.of("now", "check", "log")), member.toString());
        assertTrue(member.stream().noneMatch(List.of("stop", "reset", "wait")::contains), member.toString());
        // The body of an expression lambda: the void methods are offered
        JsonNode stop = datapoints.get("testStop_split_0");
        List<String> lambdaMember = candidatesAfter(stop, stepBefore(stop, "stop"));
        assertTrue(lambdaMember.containsAll(List.of("stop", "reset", "now")), lambdaMember.toString());
        JsonNode prepare = datapoints.get("testPrepare_split_0");
        List<String> lambdaRoot = candidatesAfter(prepare, stepBefore(prepare, "prepare"));
        assertTrue(lambdaRoot.containsAll(List.of("prepare", "count")), lambdaRoot.toString());
        // The receiver of the body of a lambda is a chain: after a constructor call too
        JsonNode chain = datapoints.get("testChain_split_0");
        assertTrue(candidatesAfter(chain, stepBefore(chain, "reset")).contains("reset"));
        // An argument inside the body of a lambda is a value
        JsonNode check = datapoints.get("testCheck_split_0");
        List<String> nested = candidatesAfter(check, stepBefore(check, "now"));
        assertTrue(nested.contains("now"), nested.toString());
        assertFalse(nested.contains("stop"), nested.toString());
    }

    /** The partial snippet of the step whose target is the given identifier (the last one). */
    private static String stepBefore(JsonNode datapoint, String identifier) {
        String snippet = null;
        for (JsonNode step : datapoint.get("tokenSteps")) {
            if (step.get("target").asText().endsWith(":" + identifier)) {
                snippet = step.get("partialSnippet").asText();
            }
        }
        assertNotNull(snippet, "no step with target " + identifier);
        return snippet;
    }
}
