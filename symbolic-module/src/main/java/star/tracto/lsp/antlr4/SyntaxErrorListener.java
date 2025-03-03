package star.tracto.lsp.antlr4;

import org.antlr.v4.runtime.*;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.misc.IntervalSet;

public class SyntaxErrorListener extends BaseErrorListener {
    private final List<String> errors = new ArrayList<>();
    private final List<String> nextTokens = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        if (recognizer instanceof Parser) {
            Parser parser = (Parser) recognizer;

            // Capture expected tokens at the error state
            IntervalSet expected = parser.getExpectedTokens();
            nextTokens.clear();
            for (int tokenType : expected.toArray()) {
                String tokenName = parser.getVocabulary().getDisplayName(tokenType);
                nextTokens.add(tokenName);
            }

            errors.add("Line " + line + ":" + charPositionInLine + " " + msg);
        }
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getNextTokens() {
        return nextTokens;
    }
}
