package star.tracto.preprocessing.builders;

import star.tracto.preprocessing.components.*;

import java.util.List;

public class TokenStepBuilder {

    private String partialSnippet;
    private List<String> nextPossibleProjectTokens;
    private List<String> nextPossibleJavaTokens;
    private List<String> nextPossibleGenericTokens;
    private boolean isSyntaxCorrect;
    private String target;

    public TokenStep build() {
        return new TokenStep(
                this.partialSnippet,
                this.nextPossibleProjectTokens,
                this.nextPossibleJavaTokens,
                this.nextPossibleGenericTokens,
                this.isSyntaxCorrect,
                this.target
        );
    }

    /**
     * Set the partial oracle
     *
     * @param partialSnippet the partial oracle
     */
    public void setTestPrefix(String partialSnippet) {
        this.partialSnippet = partialSnippet;
    }

    /**
     * Set the next possible project tokens.
     *
     * @param nextPossibleProjectTokens the next possible project tokens
     */
    public void setNextPossibleProjectTokens(List<String> nextPossibleProjectTokens) {
        this.nextPossibleProjectTokens = nextPossibleProjectTokens;
    }

    /**
     * Set the next possible Java tokens.
     *
     * @param nextPossibleJavaTokens the next possible Java tokens
     */
    public void setNextPossibleJavaTokens(List<String> nextPossibleJavaTokens) {
        this.nextPossibleJavaTokens = nextPossibleJavaTokens;
    }

    /**
     * Set the next possible generic tokens.
     *
     * @param nextPossibleGenericTokens the next possible generic tokens
     */
    public void setNextPossibleGenericTokens(List<String> nextPossibleGenericTokens) {
        this.nextPossibleGenericTokens = nextPossibleGenericTokens;
    }

    /**
     * Set whether the partial oracle is syntactically correct and complete.
     *
     * @param isSyntaxCorrect true if the partial oracle is syntactically correct and complete, false otherwise
     */
    public void setSyntaxCorrect(boolean isSyntaxCorrect) {
        this.isSyntaxCorrect = isSyntaxCorrect;
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
