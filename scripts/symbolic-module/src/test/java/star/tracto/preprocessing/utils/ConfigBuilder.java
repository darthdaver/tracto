package star.tracto.preprocessing.utils;

import star.tracto.data.enums.*;
import star.tracto.data.records.OraclesDatasetConfig;

/**
 * Builds the configuration of the oracles dataset for the tests. The defaults are the configuration used by most tests
 * ({@link FixtureRepo#CONFIG}); each setter changes one key.
 */
final class ConfigBuilder {

    private SplitStrategyType split = SplitStrategyType.ASSERTION;
    private TryCatchFinallyStrategyType tryCatchFinally = TryCatchFinallyStrategyType.TO_ASSERT_THROWS;
    private AssertionLambdaBlocksStrategyType assertionLambdaBlocks = AssertionLambdaBlocksStrategyType.MOVE_TO_PREFIX;
    private StatementStrategyType statement = StatementStrategyType.KEEP;
    private AssertionStrategyType condition = AssertionStrategyType.KEEP;
    private AssertionStrategyType assertion = AssertionStrategyType.MASK;
    private TargetStrategyType target = TargetStrategyType.ASSERTION;
    private String mask = "<MASK_PLACEHOLDER>";
    private String noAssertionTarget = "<NO_ASSERTION>";
    private boolean integrateAuxiliaryMethods = true;
    private boolean keepStatementsAfterLastAssertion = false;
    private NumAssertionsMatchStrategyType numAssertionsMatch = NumAssertionsMatchStrategyType.LOOSE;

    ConfigBuilder split(SplitStrategyType split) {
        this.split = split;
        return this;
    }

    ConfigBuilder tryCatchFinally(TryCatchFinallyStrategyType tryCatchFinally) {
        this.tryCatchFinally = tryCatchFinally;
        return this;
    }

    ConfigBuilder assertionLambdaBlocks(AssertionLambdaBlocksStrategyType assertionLambdaBlocks) {
        this.assertionLambdaBlocks = assertionLambdaBlocks;
        return this;
    }

    ConfigBuilder statement(StatementStrategyType statement) {
        this.statement = statement;
        return this;
    }

    ConfigBuilder assertion(AssertionStrategyType assertion) {
        this.assertion = assertion;
        return this;
    }

    ConfigBuilder target(TargetStrategyType target) {
        this.target = target;
        return this;
    }

    ConfigBuilder integrateAuxiliaryMethods(boolean integrateAuxiliaryMethods) {
        this.integrateAuxiliaryMethods = integrateAuxiliaryMethods;
        return this;
    }

    ConfigBuilder keepStatementsAfterLastAssertion(boolean keepStatementsAfterLastAssertion) {
        this.keepStatementsAfterLastAssertion = keepStatementsAfterLastAssertion;
        return this;
    }

    ConfigBuilder numAssertionsMatch(NumAssertionsMatchStrategyType numAssertionsMatch) {
        this.numAssertionsMatch = numAssertionsMatch;
        return this;
    }

    OraclesDatasetConfig build() {
        return new OraclesDatasetConfig(
                split,
                tryCatchFinally,
                assertionLambdaBlocks,
                statement,
                condition,
                assertion,
                target,
                mask,
                noAssertionTarget,
                integrateAuxiliaryMethods,
                keepStatementsAfterLastAssertion,
                numAssertionsMatch
        );
    }
}
