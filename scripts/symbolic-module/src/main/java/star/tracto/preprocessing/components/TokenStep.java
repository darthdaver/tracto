package star.tracto.preprocessing.components;

import java.util.List;

/**
 * Represents a token step to build an oracle.
 * A Token Step is a tuple with the following elements:
 * <ol>
 *    <li>The partial oracle generated so far</li>
 *    <li>The list of next possible legal project tokens</li>
 *    <li>The list of next possible legal Java tokens</li>
 *    <li>The list of next possible generic tokens</li>
 *    <li>A boolean stating if the partial oracle is syntactically correct and complete<li/>
 *    <li>The target token to add</li>
 * </ol>
 * The Partial Oracle Datapoint is part of the information used to train the model to generate the next tokens to be added
 * to the oracle under construction
 */
public record TokenStep(
        String partialSnippet,
        List<String> nextPossibleProjectTokens,
        List<String> nextPossibleJavaTokens,
        List<String> nextPossibleGenericTokens,
        boolean isSyntaxCorrect,
        String target
) {}
