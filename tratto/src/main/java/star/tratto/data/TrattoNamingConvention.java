package star.tratto.data;

public enum TrattoNamingConvention {
    TEST_SPLIT_CLASS("TrattoSplit"),
    TEST_SPLIT_FILE("TrattoSplit.java"),
    LIB_FOLDER("lib_tratto");

    private final String conventionName;

    TrattoNamingConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    public String getConventionName() {
        return conventionName;
    }
}
