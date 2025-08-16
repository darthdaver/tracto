package star.tracto.preprocessing.components;

import java.util.List;

/**
 * A full representation of a test class with its associated Oracle Datapoints.
 * The record encapsulates the following elements:
 * <ol>
 *    <li>The JUnit version used within the test class to generate assertions</li>
 *    <li>The test class, i.e. the class containing the test cases from which the oracle datapoints are extracted</li>
 *    <li>The focal class, i.e. the corresponding class tested in the test class</li>
 *    <li>The list of datapoints extracted from the test cases at any occurrence of an assertion, in the test class</li>
 * </ol>
 */
public record TestClazzDatapoints<T>(
        /* The junit version used within the test class to generate the assertions */
        String junitVersion,
        /* The test class */
        TestClazz testClass,
        /* The corresponding focal class */
        Clazz focalClass,
        /* The list of datapoints associated to the test cases defined within the test class */
        List<T> datapoints
) {}
