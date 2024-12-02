package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.ClassTratto;
import star.tratto.preprocessing.components.CallableTratto;
import star.tratto.preprocessing.components.OracleDatapoint;
import star.tratto.preprocessing.components.TestClassTratto;

public class OracleDatapointBuilder {

    private CallableTratto testPrefix;
    private CallableTratto focalMethodTratto;
    private TestClassTratto testClass;
    private ClassTratto focalClass;
    private String target;

    public OracleDatapoint build() {
        return new OracleDatapoint(
            this.testPrefix,
            this.focalMethodTratto,
            this.testClass,
            this.focalClass,
            this.target
        );
    }

    /**
     * Set the test prefix of the OracleDatapoint.
     *
     * @param testPrefix the test prefix
     */
    public void setTestPrefix(CallableTratto testPrefix) {
        this.testPrefix = testPrefix;
    }

    /**
     * Set the focal method of the OracleDatapoint.
     *
     * @param focalMethodTratto the focal method
     */
    public void setFocalMethod(CallableTratto focalMethodTratto) {
        this.focalMethodTratto = focalMethodTratto;
    }

    /**
     * Set the test class of the OracleDatapoint.
     *
     * @param testClass the test class
     */
    public void setTestClass(TestClassTratto testClass) {
        this.testClass = testClass;
    }

    /**
     * Set the focal class of the OracleDatapoint.
     *
     * @param focalClass the focal class
     */
    public void setFocalClass(ClassTratto focalClass) {
        this.focalClass = focalClass;
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
