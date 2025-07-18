package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.*;

import java.util.List;

public class TestClazzDatapointsBuilder<T> {

    private String junitVersion;
    private TestClazz testClass;
    private Clazz focalClass;
    private List<T> datapoints;

    public TestClazzDatapoints<T> build() {
        return new TestClazzDatapoints<> (
            this.junitVersion,
            this.testClass,
            this.focalClass,
            this.datapoints
        );
    }

    /**
     * Set the junit version of the TestClazzOracleDatapoints.
     *
     * @param junitVersion the junit version used in the test class to generate assertions
     */
    public void setJunitVersion(String junitVersion) {
        this.junitVersion = junitVersion;
    }

    /**
     * Set the test class of the TestClazzOracleDatapoints.
     *
     * @param testClass the test class
     */
    public void setTestClass(TestClazz testClass) {
        this.testClass = testClass;
    }

    /**
     * Set the focal class of the TestClazzOracleDatapoints.
     *
     * @param focalClass the focal class
     */
    public void setFocalClass(Clazz focalClass) {
        this.focalClass = focalClass;
    }

    /**
     * Set the list of datapoints of the TestClazzOracleDatapoints.
     *
     * @param datapoints the list of datapoints
     */
    public void setDatapoints(List<T> datapoints) {
        this.datapoints = datapoints;
    }

}
