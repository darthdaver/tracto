package star.tratto.data;

public enum TrattoNamingConvention {
    TEST_SPLIT_CLASS("TrattoSplit"),
    TEST_SPLIT_FILE("TrattoSplit.java"),
    LIB_FOLDER("tratto_libs"),
    SOURCE_LIB_FOLDER("tratto_libs/sources"),
    JAR_LIB_FOLDER("tratto_libs/jars"),
    DECOMPILED_LIB_FOLDER("tratto_libs/decompiled"),
    JAVA_CLASSPATHS_JSON("lib/java_classpaths.json"),;

    private final String conventionName;

    TrattoNamingConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    public String getConventionName() {
        return conventionName;
    }
}
