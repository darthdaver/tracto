package star.tracto.data.enums;

/**
 * The shapes of the try statements found in the test cases. The normalization supports only some of them: a test
 * case containing a try statement with an unsupported shape is discarded.
 */
public enum TryStatementShape {
    /* `try (R r = ...) { ... }`, possibly with a finally block */
    RESOURCES_WITHOUT_CATCH(true, "try-with-resources without catch clauses"),
    /* `try { ... } finally { ... }` */
    FINALLY_WITHOUT_CATCH(true, "try/finally without catch clauses"),
    /* `try { ...; call(); fail(); } catch (E e) { ... }`, possibly with resources and a finally block */
    EXPECTED_EXCEPTION(true, "expected exception: a single catch clause, and a call followed by fail() at the end of the try block"),
    MULTI_CATCH(false, "more than one catch clause"),
    UNION_CATCH(false, "union type in the catch clause"),
    FAIL_NOT_LAST(false, "fail() is not the last statement of the try block"),
    UNSUPPORTED_FAILING_STATEMENT(false, "the statement before fail() is not a method call or an object creation"),
    MUST_NOT_THROW(false, "fail() in the catch block: the test checks that no exception is thrown"),
    CATCH_WITHOUT_FAIL(false, "catch clause without fail(): the test passes whether or not the exception is thrown"),
    EMPTY_CATCH(false, "empty catch block without fail(): the exception is ignored");

    private final boolean supported;
    private final String description;

    TryStatementShape(boolean supported, String description) {
        this.supported = supported;
        this.description = description;
    }

    public boolean isSupported() {
        return supported;
    }

    public String getDescription() {
        return description;
    }
}
