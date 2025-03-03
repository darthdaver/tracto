package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.*;
import java.util.List;

public class OracleDatapointBuilder {

    private CallableTracto testPrefix;
    private String target;

    public OracleDatapoint build() {
        return new OracleDatapoint(
            this.testPrefix,
            this.target
        );
    }

    /**
     * Set the test prefix of the OracleDatapoint.
     *
     * @param testPrefix the test prefix
     */
    public void setTestClass(CallableTracto testPrefix) {
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
}
