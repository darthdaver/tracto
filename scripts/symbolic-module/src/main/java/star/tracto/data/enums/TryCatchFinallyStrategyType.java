package star.tracto.data.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The strategy to handle the try statements of the test cases, applied during the normalization.
 */
public enum TryCatchFinallyStrategyType {
    /* Keep the try/catch/finally structure, normalizing the statements within its blocks */
    KEEP_TRY_CATCH("keep-try-catch"),
    /* Flatten the try statements, converting an expected exception (`try { ...; call(); fail(); } catch (E e) {...}`)
       into an `assertThrows` */
    TO_ASSERT_THROWS("to-assert-throws");

    private final String type;

    TryCatchFinallyStrategyType(String type) {
        this.type = type;
    }

    @JsonValue
    public String getType() {
        return type;
    }

    @JsonCreator
    public static TryCatchFinallyStrategyType fromValue(String value) {
        for (TryCatchFinallyStrategyType type : TryCatchFinallyStrategyType.values()) {
            if (type.getType().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
