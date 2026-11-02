package star.tracto.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The strategy to handle the assertions whose lambda expression is a block of at least two statements
 * ({@code assertThrows}, {@code assertThrowsExactly} and {@code assertDoesNotThrow}), applied during the normalization
 * and the split.
 */
public enum AssertionLambdaBlocksStrategyType {
    /* Move the statements before the last one before the assertion, keeping only the last call within the lambda
       expression (`a(); assertThrows(E.class, () -> b());`). The shapes whose throwing statement is unclear produce no
       datapoint */
    MOVE_TO_PREFIX("move-to-prefix"),
    /* The assertions produce no datapoint: the statement that throws (or must not throw) is unknown */
    SKIP("skip");

    private final String type;

    AssertionLambdaBlocksStrategyType(String type) {
        this.type = type;
    }

    @JsonValue
    public String getType() {
        return type;
    }

    @JsonCreator
    public static AssertionLambdaBlocksStrategyType fromValue(String value) {
        for (AssertionLambdaBlocksStrategyType type : AssertionLambdaBlocksStrategyType.values()) {
            if (type.getType().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
