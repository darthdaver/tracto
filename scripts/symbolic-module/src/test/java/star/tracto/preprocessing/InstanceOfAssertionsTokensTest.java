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
 * End-to-end test of the assertions {@code assertInstanceOf} and {@code assertThrowsExactly} of JUnit 5 (Step 41): the
 * tokens stage had no grammar rule for them ("Unsupported assertion type") and dropped their oracles.
 */
class InstanceOfAssertionsTokensTest {

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

                    public Object state() {
                        return time;
                    }

                    public void stop() {
                        if (time == 0) {
                            throw new IllegalStateException();
                        }
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ClockTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ClockTest {
                    @Test
                    public void testState() {
                        Clock clock = new Clock();
                        assertInstanceOf(Long.class, clock.state());
                    }

                    @Test
                    public void testStateMessage() {
                        Clock clock = new Clock();
                        assertInstanceOf(Long.class, clock.state(), "a long");
                    }

                    @Test
                    public void testStop() {
                        Clock clock = new Clock();
                        assertThrowsExactly(IllegalStateException.class, () -> clock.stop());
                    }
                }
                """);
    }

    @Test
    void theirOraclesHaveTokenSteps() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        for (String test : List.of("testState_split_0", "testStateMessage_split_0", "testStop_split_0")) {
            assertTrue(datapoints.containsKey(test), test + " dropped: " + datapoints.keySet());
        }
        List<String> actual = candidatesAfter(datapoints.get("testState_split_0"), "assertInstanceOf(Long.class ,clock.");
        assertTrue(actual.contains("state"), actual.toString());
        List<String> executable = candidatesAfter(datapoints.get("testStop_split_0"), "assertThrowsExactly(IllegalStateException.class ,()->clock.");
        assertTrue(executable.contains("stop"), executable.toString());
    }
}
