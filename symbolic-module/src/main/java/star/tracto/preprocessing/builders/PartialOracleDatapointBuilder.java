package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.Callable;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.PartialOracleDatapoint;
import star.tracto.preprocessing.components.TokenStep;

import java.util.List;

public class PartialOracleDatapointBuilder {

    private Callable testPrefix;
    private String target;
    private List<TokenStep> tokenSteps;

    public PartialOracleDatapoint build() {
        return new PartialOracleDatapoint(
                this.testPrefix,
                this.target,
                this.tokenSteps
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
     * Set the list of token steps to build the whole oracle.
     *
     * @param tokenSteps the list of datapoints
     */
    public void setDatapoints(List<TokenStep> tokenSteps) {
        this.tokenSteps = tokenSteps;
    }
}
