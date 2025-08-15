package star.tracto.data.enums;

public enum TokenIdentifierCategory {
    ROOT_IDENTIFIER("rootIdentifier"),
    JAVA_IDENTIFIER("javaIdentifier"),
    CLASS_IDENTIFIER("classIdentifier"),
    METHOD_IDENTIFIER("methodIdentifier"),
    CONSTRUCTOR_IDENTIFIER("constructorIdentifier"),
    FIELD_IDENTIFIER("fieldIdentifier");

    private final String category;

    TokenIdentifierCategory(String category) {
        this.category = category;
    }

    public String getCategory() {
        return category;
    }

    public static TokenIdentifierCategory fromString(String category) {
        for (TokenIdentifierCategory value : TokenIdentifierCategory.values()) {
            if (value.category.equals(category)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No constant with category " + category + " found");
    }
}
