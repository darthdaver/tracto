package star.tracto.preprocessing.components;

import java.util.List;

/**
 * Represents a Partial Oracle Datapoint.
 * A Partial Oracle Datapoint is a tuple with the following elements:
 * <ol>
 *    <li>The test prefix, i.e. the corpus of the test with all the preliminary steps to drive the system into a specific state</li>
 *    <li>The target, i.e. the next assertion to generate by the model</li>
 *    <li>The list of steps to generate the whole target assertion, from the empty oracle to the final oracle</li>
 * </ol>
 * The Partial Oracle Datapoint is part of the information used to train the model to generate the next tokens to be added
 * to the oracle under construction
 */
public record PartialOracleDatapoint(
        /* The test prefix */
        Callable testPrefix,
        /* The target, i.e. the next assertion to generate */
        String target,
        /* The list of steps to generate the whole */
        List<TokenStep> tokenSteps
) {}
