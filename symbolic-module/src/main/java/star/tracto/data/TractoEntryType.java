package star.tracto.data;

public enum TractoEntryType {
    FOCAL("Focal"),
    TEST("Test");

    private final String entryType;

    TractoEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getEntryType() {
        return entryType;
    }
}
