package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.*;

public class OracleDatapointBuilder {

    private Callable testPrefix;
    private String target;
    private Assignment assignment;

    public OracleDatapoint build() {
        return new OracleDatapoint(
            this.testPrefix,
            this.target,
            this.assignment
        );
    }

    /**
     * Set the test prefix of the OracleDatapoint.
     *
     * @param testPrefix the test prefix
     */
    public void setTestPrefix(Callable testPrefix) {
        this.testPrefix = testPrefix;
    }

    /**
     * Set the target of the OracleDatapoint.
     *
     * @param target the target
     */
    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * Set the declaration of the variable assigned with the target assertion.
     *
     * @param assignment the assignment, or null if the assertion is a statement by itself
     */
    public void setAssignment(Assignment assignment) {
        this.assignment = assignment;
    }
}
