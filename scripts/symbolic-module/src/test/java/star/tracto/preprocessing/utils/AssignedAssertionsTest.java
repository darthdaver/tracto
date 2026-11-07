package star.tracto.preprocessing.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import star.tracto.preprocessing.components.Assignment;
import star.tracto.preprocessing.components.OracleDatapoint;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assigned assertions (Step 27): the split of the statement, the oracle datapoint written and read back with its
 * assignment, the declaration added back to the generated assertion at the end of the inference.
 */
class AssignedAssertionsTest {

    private static final Assignment ASSIGNMENT = new Assignment(List.of("final"), "Exception", "e");

    @Test
    void onlyAVariableInitializedWithAJUnitAssertionIsSplit() {
        assertTrue(AssignedAssertions.split(StaticJavaParser.parseStatement("int a = assertDoesNotThrow(() -> 1);")).isPresent());
        assertTrue(AssignedAssertions.split(StaticJavaParser.parseStatement("int a = compute();")).isEmpty());
        assertTrue(AssignedAssertions.split(StaticJavaParser.parseStatement("int a;")).isEmpty());
        assertTrue(AssignedAssertions.split(StaticJavaParser.parseStatement("int a = 1, b = assertDoesNotThrow(() -> 1);")).isEmpty());
        assertTrue(AssignedAssertions.split(StaticJavaParser.parseStatement("assertEquals(1, 1);")).isEmpty());
    }

    @Test
    void theAssignmentIsWrittenAndReadBackOnlyWhenPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
        OracleDatapoint assigned = new OracleDatapoint(null, "assertThrows(Exception.class, () -> run());", ASSIGNMENT);
        OracleDatapoint plain = new OracleDatapoint(null, "assertTrue(ok);", null);

        JsonNode assignedJson = mapper.readTree(mapper.writeValueAsString(assigned));
        assertEquals("Exception", assignedJson.get("assignment").get("type").asText());
        assertEquals(assigned, mapper.readValue(mapper.writeValueAsString(assigned), OracleDatapoint.class));
        // No field for a plain assertion: the oracles datasets of the plain assertions do not change
        assertFalse(mapper.readTree(mapper.writeValueAsString(plain)).has("assignment"));
        assertEquals(plain, mapper.readValue("{\"testPrefix\":null,\"target\":\"assertTrue(ok);\"}", OracleDatapoint.class));
    }

    @Test
    void theDeclarationIsAddedBackToTheGeneratedAssertion() {
        OracleDatapoint datapoint = new OracleDatapoint(null, "assertThrows(Exception.class, () -> run());", ASSIGNMENT);

        // The generated text is kept as it is (not reprinted)
        assertEquals(List.of("final Exception e = assertThrows(IllegalStateException.class,()->run());", "OK", ""),
                TokensUtils.completedInference(datapoint, "assertThrows(IllegalStateException.class,()->run());"));
        assertEquals("final Exception e = assertThrows(Exception.class, () -> run());", datapoint.statement(datapoint.target()));
    }

    @Test
    void aGeneratedAssignedAssertionThatIsNotAStatementIsAnError() {
        OracleDatapoint datapoint = new OracleDatapoint(null, "assertThrows(Exception.class, () -> run());", ASSIGNMENT);

        List<String> completed = TokensUtils.completedInference(datapoint, "assertThrows(Exception.class,);");
        assertEquals("final Exception e = assertThrows(Exception.class,);", completed.get(0));
        assertEquals("ERROR", completed.get(1));
        assertTrue(completed.get(2).startsWith("The generated assertion with the declaration of its variable is not a statement"), completed.get(2));
    }

    @Test
    void aPlainGeneratedAssertionIsKeptAsItIs() {
        OracleDatapoint datapoint = new OracleDatapoint(null, "assertTrue(ok);", null);

        assertEquals(List.of("assertTrue(ok);", "OK", ""), TokensUtils.completedInference(datapoint, "assertTrue(ok);"));
    }
}
