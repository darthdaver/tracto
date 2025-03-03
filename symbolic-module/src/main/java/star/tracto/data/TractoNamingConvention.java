package star.tracto.data;

public enum TractoNamingConvention {
    TEST_SPLIT_CLASS("TractoSplit"),
    TEST_SPLIT_FILE("TractoSplit.java"),
    LIB_FOLDER("tracto_libs"),
    SOURCE_LIB_FOLDER("tracto_libs/sources"),
    JAR_LIB_FOLDER("tracto_libs/jars"),
    DECOMPILED_LIB_FOLDER("tracto_libs/decompiled"),
    JAVA_CLASSPATHS_JSON("lib/java_classpaths.json"),;

    private final String conventionName;

    TractoNamingConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    public String getConventionName() {
        return conventionName;
    }
}
