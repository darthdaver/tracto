package star.tracto.data.exceptions;

/**
 * Thrown during the normalization of a test case that contains a pattern the dataset does not support. The test case
 * is discarded (it produces no datapoints), and the reason is recorded in the statistics of the test case.
 */
public class DiscardedTestCaseException extends RuntimeException {
    /* The code of the reason why the test case is discarded (for example `MULTI_CATCH`) */
    private final String reason;

    public DiscardedTestCaseException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
