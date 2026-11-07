package star.tracto.preprocessing.components;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents an Oracle Datapoint.
 * An Oracle Datapoint is a tuple with the following elements:
 * <ol>
 *    <li>The test prefix, i.e. the corpus of the test with all the preliminary steps to drive the system into a specific state</li>
 *    <li>The target, i.e. the next assertion to generate by the model</li>
 *    <li>The assignment, i.e. the declaration of the variable assigned with the result of the target assertion
 *    ({@code Exception e = assertThrows(...)}), absent when the assertion is a statement by itself</li>
 * </ol>
 * The Oracle Datapoint is part of the information used to train the model to generate the next assertion to be added to the given test prefix
 */
public record OracleDatapoint(
        /* The test prefix */
        Callable testPrefix,
        /* The target, i.e. the next assertion to generate */
        String target,
        /* The declaration of the variable assigned with the target assertion (null if none) */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Assignment assignment
) {

    /**
     * The statement of the test: the target, with the declaration of its variable if it is assigned.
     *
     * @param assertion the target assertion (the target itself, or a generated one)
     * @return the statement as written in the test
     */
    public String statement(String assertion) {
        return assignment == null ? assertion : assignment.declare(assertion);
    }
}
