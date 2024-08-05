package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.Clazz;
import star.tratto.preprocessing.components.Method;
import star.tratto.preprocessing.components.OracleDatapoint;

public class OracleDatapointBuilder {

    private Method testPrefix;
    private Method focalMethod;
    private Clazz testClass;
    private Clazz focalClass;
    private String target;

    public OracleDatapoint build() {
        return new OracleDatapoint(
            this.testPrefix,
            this.focalMethod,
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
    public void setTestPrefix(Method testPrefix) {
        this.testPrefix = testPrefix;
    }

    /**
     * Set the focal method of the OracleDatapoint.
     *
     * @param focalMethod the focal method
     */
    public void setFocalMethod(Method focalMethod) {
        this.focalMethod = focalMethod;
    }

    /**
     * Set the test class of the OracleDatapoint.
     *
     * @param testClass the test class
     */
    public void setTestClass(Clazz testClass) {
        this.testClass = testClass;
    }

    /**
     * Set the focal class of the OracleDatapoint.
     *
     * @param focalClass the focal class
     */
    public void setFocalClass(Clazz focalClass) {
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
