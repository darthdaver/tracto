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
 * End-to-end test of the {@code assertThat} of Hamcrest (Step 30): a target of the tokens stage like the JUnit
 * assertions, with the matchers among the candidates. Hamcrest is part of the assertion API known by the module (its
 * sources are given to the symbol solver, as those of the JDK), not a dependency of the project.
 */
class HamcrestTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Clock.java", """
                package com.acme;

                import java.util.List;

                public class Clock {
                    public long now() {
                        return 1L;
                    }

                    public List<String> names() {
                        return List.of("a", "b");
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ClockTest.java", """
                package com.acme;

                import org.hamcrest.CoreMatchers;
                import org.hamcrest.MatcherAssert;
                import org.junit.jupiter.api.Test;

                import static org.hamcrest.MatcherAssert.assertThat;
                import static org.hamcrest.Matchers.*;
                import static org.hamcrest.core.Is.is;

                public class ClockTest {

                    @Test
                    public void testNow() {
                        Clock clock = new Clock();
                        assertThat(clock.now(), is(1L));
                    }

                    @Test
                    public void testNames() {
                        Clock clock = new Clock();
                        MatcherAssert.assertThat("names", clock.names(), CoreMatchers.hasItem("a"));
                    }

                    @Test
                    public void testSize() {
                        Clock clock = new Clock();
                        assertThat(clock.names(), hasSize(2));
                    }
                }
                """);
    }

    @Test
    void assertThatIsATargetWithTheMatchersAmongTheCandidates() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.messages("Unsupported assertion"));
        assertEquals(Map.of(), fixture.messages("Assertion do not start"));
        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertEquals(List.of("testNames_split_0", "testNow_split_0", "testSize_split_0"),
                datapoints.keySet().stream().sorted().toList());
        JsonNode now = datapoints.get("testNow_split_0");
        assertEquals("assertThat(clock.now(), is(1L));", now.get("target").asText());
        // A matcher imported statically, one by one or with a wildcard
        assertTrue(candidatesAfter(now, "assertThat(clock.now(),").contains("is"));
        assertTrue(candidatesAfter(datapoints.get("testSize_split_0"), "assertThat(clock.names(),").contains("hasSize"));
        // A matcher of a class imported (the class qualifier of MatcherAssert removed)
        JsonNode names = datapoints.get("testNames_split_0");
        assertEquals("assertThat(\"names\", clock.names(), CoreMatchers.hasItem(\"a\"));", names.get("target").asText());
        assertTrue(candidatesAfter(names, "assertThat(\"names\",clock.names(),").contains("CoreMatchers"));
        assertTrue(candidatesAfter(names, "assertThat(\"names\",clock.names(),CoreMatchers.").contains("hasItem"));
    }
}
