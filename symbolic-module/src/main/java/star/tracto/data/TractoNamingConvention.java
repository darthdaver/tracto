package star.tracto.data;

public enum TractoNamingConvention {
    TEST_SPLIT_CLASS("TractoSplit"),
    TEST_SPLIT_FILE("TractoSplit.java"),
    LIB_FOLDER("processed_libs"),
    SOURCE_LIB_FOLDER("processed_libs/sources"),
    JAR_LIB_FOLDER("processed_libs/jars"),
    DECOMPILED_LIB_FOLDER("processed_libs/decompiled"),
    JAVA_CLASSPATHS_JSON("lib/java_classpaths.json"),;

    private final String conventionName;

    TractoNamingConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    public String getConventionName() {
        return conventionName;
    }
}
