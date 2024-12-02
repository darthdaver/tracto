package star.tratto.data;

public enum TrattoEntryType {
    FOCAL("Focal"),
    TEST("Test");

    private final String entryType;

    TrattoEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getEntryType() {
        return entryType;
    }
}
