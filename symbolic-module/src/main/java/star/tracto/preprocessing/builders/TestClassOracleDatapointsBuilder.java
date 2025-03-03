package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.*;
import java.util.List;

public class TestClassOracleDatapointsBuilder {

    private String junitVersion;
    private TestClassTracto testClass;
    private ClassTracto focalClass;
    private List<OracleDatapoint> datapoints;

    public TestClassOracleDatapoints build() {
        return new TestClassOracleDatapoints(
            this.junitVersion,
            this.testClass,
            this.focalClass,
            this.datapoints
        );
    }

    /**
     * Set the junit version of the TestClassOracleDatapoints.
     *
     * @param junitVersion the junit version used in the test class to generate assertions
     */
    public void setJunitVersion(String junitVersion) {
        this.junitVersion = junitVersion;
    }

    /**
     * Set the test class of the TestClassOracleDatapoints.
     *
     * @param testClass the test class
     */
    public void setTestClass(TestClassTracto testClass) {
        this.testClass = testClass;
    }

    /**
     * Set the focal class of the TestClassOracleDatapoints.
     *
     * @param focalClass the focal class
     */
    public void setFocalClass(ClassTracto focalClass) {
        this.focalClass = focalClass;
    }

    /**
     * Set the list of datapoints of the TestClassOracleDatapoints.
     *
     * @param datapoints the list of datapoints
     */
    public void setDatapoints(List<OracleDatapoint> datapoints) {
        this.datapoints = datapoints;
    }

}
