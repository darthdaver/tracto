package star.tratto.data;

public enum JUnitAnnotationType {
    AFTER_ALL("AfterAll"),
    AFTER_EACH("AfterEach"),
    BEFORE_ALL("BeforeAll"),
    BEFORE_EACH("BeforeEach"),
    DISABLED("Disabled"),
    DISPLAY_NAME("DisplayName"),
    DISPLAY_NAME_GENERATION("DisplayNameGeneration"),
    INDICATIVE_SENTENCES_GENERATION("IndicativeSentencesGeneration"),
    NESTED("Nested"),
    ORDER("Order"),
    REPEATED_TEST("RepeatedTest"),
    TAG("Tag"),
    TAGS("Tags"),
    TEST("Test"),
    TEST_FACTORY("TestFactory"),
    TEST_INSTANCE("TestInstance"),
    TEST_METHOD_ORDER("TestMethodOrder"),
    TEST_TEMPLATE("TestTemplate"),
    TIMEOUT("Timeout");

    private final String annotationName;

    JUnitAnnotationType(String annotationName) {
        this.annotationName = annotationName;
    }

    public String getAnnotationName() {
        return annotationName;
    }

    public static JUnitAnnotationType fromString(String annotationName) {
        for (JUnitAnnotationType value : JUnitAnnotationType.values()) {
            if (value.annotationName.equals(annotationName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No constant with annotationName " + annotationName + " found");
    }

    public static boolean isJunitAnnotation(String annotationName) {
        for (JUnitAnnotationType value : JUnitAnnotationType.values()) {
            if (value.annotationName.equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTestAnnotation(String annotationName) {
        return annotationName.equals(TEST.getAnnotationName());
    }

    public static boolean isSetUpTearDownAnnotation(String annotationName) {
        return isJunitAnnotation(annotationName) && !isTestAnnotation(annotationName);
    }
}
