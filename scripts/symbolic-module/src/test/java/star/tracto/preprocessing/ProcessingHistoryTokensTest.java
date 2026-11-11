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
 * End-to-end test of the independence of the datapoints (Step 36): the token steps of a target do not depend on the
 * targets processed before it. The flag telling that the current value is a {@code Stream} (whose lambdas get the
 * {@code LAMBDA_PARAM} placeholder and the {@code LAMBDA_CALL} contextual information) was kept from one datapoint to the
 * next, and from one test class to the next: after {@code list.stream().count()}, every later target got
 * {@code LAMBDA_PARAM} after a {@code (} whose argument is a literal.
 */
class ProcessingHistoryTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Names.java", """
                package com.acme;

                import java.util.List;

                public class Names {
                    public List<String> all() {
                        return List.of("a");
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/NamesTest.java", """
                package com.acme;

                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class NamesTest {
                    @Test
                    public void testLambda() {
                        List<String> list = new Names().all();
                        assertFalse(list.stream().anyMatch(s -> s.isEmpty()));
                    }

                    @Test
                    public void testStream() {
                        List<String> list = new Names().all();
                        assertEquals(1, list.stream().count());
                    }

                    @Test
                    public void testPlain() {
                        assertEquals(1, Math.abs(1));
                    }
                }
                """);
    }

    @Test
    void aStreamInOneTargetDoesNotChangeTheNextTargets() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // The parameter of a lambda on a stream is offered with its type
        assertTrue(candidatesAfter(datapoints.get("testLambda_split_0"), "assertFalse(list.stream().anyMatch(s->s.").contains("isEmpty"));
        // The literal argument of a call that does not follow a stream is a literal, not a lambda parameter
        JsonNode plain = datapoints.get("testPlain_split_0");
        assertFalse(candidatesAfter(plain, "assertEquals(1,Math.abs(").contains("LAMBDA_PARAM"));
        List<String> targets = new ArrayList<>();
        plain.get("tokenSteps").forEach(step -> targets.add(step.get("target").asText()));
        assertFalse(targets.stream().anyMatch(target -> target.startsWith("LAMBDA_PARAM")), targets.toString());
        List<String> contextualInfoTypes = new ArrayList<>();
        plain.get("tokenSteps").forEach(step -> step.get("contextualInfos").forEach(info -> contextualInfoTypes.add(info.path("contextualInfoType").asText())));
        assertFalse(contextualInfoTypes.contains("LAMBDA_CALL"), contextualInfoTypes.toString());
    }
}
