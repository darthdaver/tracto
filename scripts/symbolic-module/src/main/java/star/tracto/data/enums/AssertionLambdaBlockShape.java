package star.tracto.data.enums;

/**
 * The shapes of the lambda blocks of the assertions {@code assertThrows}, {@code assertThrowsExactly} and
 * {@code assertDoesNotThrow}, defined by their last statement: the statement expected to throw (or not to throw) the
 * exception.
 */
public enum AssertionLambdaBlockShape {
    /* The last statement is a call or an object creation, possibly assigned to a variable or returned */
    LAST_CALL("last-statement-call", "the last statement is a call or an object creation (possibly assigned, declared or returned)"),
    /* The last statement is an assertion (`assert`, a JUnit assertion or `fail()`) */
    LAST_ASSERTION("last-statement-assertion", "the last statement is an assertion"),
    /* The last statement contains other statements: try, loop, `if`, `switch`, `synchronized`, labeled statement, block */
    LAST_COMPOUND("last-statement-compound", "the last statement is a compound statement (try, loop, if, switch, block)"),
    /* The last statement throws the exception itself */
    LAST_THROW("last-statement-throw", "the last statement is a throw statement"),
    /* Any other last statement, like a bare `return` or a declaration without a call */
    LAST_OTHER("last-statement-other", "the last statement is not a call or an object creation"),
    /* A return statement before the last statement would end the test case once moved out of the lambda expression */
    EARLY_RETURN("early-return", "a return statement precedes the last statement");

    private final String key;
    private final String description;

    AssertionLambdaBlockShape(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }
}
