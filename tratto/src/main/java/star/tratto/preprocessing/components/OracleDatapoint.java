package star.tratto.preprocessing.components;

/**
 * A full representation of an Oracle Datapoint.
 * An Oracle Datapoint is a tuple of the following elements:
 * <ol>
 *    <li>The test prefix, i.e. the corpus of the test with all the preliminary steps to drive the system into a specific state</li>
 *    <li>The focal method, i.e. the method under test</li>
 *    <li>The test class, i.e. the class containing the test prefix</li>
 *    <li>The focal class, i.e. the class containing the focal method under test</li>
 *    <li>The target, i.e. the next assertion to generate by the model</li>
 * </ol>
 * The Oracle Datapoint is used to train the model to generate the next assertion to be added to the given test prefix
 */
public record OracleDatapoint(
        /* The test prefix */
        CallableTratto testPrefix,
        /* The focal method of the test prefix */
        CallableTratto focalMethodTratto,
        /* The test class */
        TestClassTratto testClass,
        /* The focal class */
        ClassTratto focalClass,
        /* The target, i.e. the next assertion to generate */
        String target
) {
}
