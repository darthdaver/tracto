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
 * End-to-end test of the assigned assertions (Step 27): an assertion whose result is assigned to a variable
 * ({@code Exception e = assertThrows(...)}) is a target of the tokens stage, as a plain assertion; the later oracles of
 * the test case see the variable.
 */
class AssignedAssertionsTokensTest {

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
                        final RuntimeException e = assertThrows(IllegalStateException.class, () -> clock.stop());
                        assertEquals("stopped", e.getMessage());
                    }

                    @Test
                    public void testNow() {
                        Clock clock = new Clock();
                        Long now = assertDoesNotThrow(() -> clock.now());
                        assertEquals(1L, now);
                    }
                }
                """);
    }

    @Test
    void anAssignedAssertionIsATargetOfTheTokensStage() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        assertEquals(List.of("testNow_split_0", "testNow_split_1", "testStop_split_0", "testStop_split_1"),
                fixture.tokensDatapoints().keySet().stream().sorted().toList());
        // The target is the assertion only
        assertEquals("assertThrows(IllegalStateException.class, () -> clock.stop());",
                fixture.tokensDatapoints().get("testStop_split_0").get("target").asText());
        assertEquals("assertDoesNotThrow(() -> clock.now());",
                fixture.tokensDatapoints().get("testNow_split_0").get("target").asText());
    }

    @Test
    void theLaterOraclesSeeTheAssignedVariable() throws IOException {
        fixture.generate();

        assertTrue(candidatesAfter(fixture.tokensDatapoints().get("testStop_split_1"), "assertEquals(\"stopped\",").contains("e"));
        assertTrue(candidatesAfter(fixture.tokensDatapoints().get("testNow_split_1"), "assertEquals(1L,").contains("now"));
    }
}
