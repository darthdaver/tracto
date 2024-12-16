package star.tratto.data;

public enum TrattoNamingConvention {
    TEST_SPLIT_CLASS("TrattoSplit"),
    TEST_SPLIT_FILE("TrattoSplit.java"),
    SOURCE_LIB_FOLDER("tratto_libs/sources"),
    JAR_LIB_FOLDER("tratto_libs/jars");

    private final String conventionName;

    TrattoNamingConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    public String getConventionName() {
        return conventionName;
    }
}
