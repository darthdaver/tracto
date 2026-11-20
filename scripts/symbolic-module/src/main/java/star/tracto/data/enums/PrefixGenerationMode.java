package star.tracto.data.enums;

/** Strategy used to generate the body of a test-prefix. */
public enum PrefixGenerationMode {
    TOKEN,
    BLOCK,
    PREFIX_ORACLE_CYCLE,
    PREFIX_ORACLE_CYCLE_LINES;

    public boolean usesOracleCycle() {
        return this == PREFIX_ORACLE_CYCLE || this == PREFIX_ORACLE_CYCLE_LINES;
    }
}
