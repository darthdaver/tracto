package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.Field;
import star.tratto.preprocessing.components.Type;

import java.util.List;

public class FieldBuilder {
    private String identifier;
    private String fullSignature;
    private String declarator;
    private List<String> modifiers;
    private Type type;

    public Field build() {
        return new Field(
            this.identifier,
            this.fullSignature,
            this.declarator,
            this.modifiers,
            this.type
        );
    }

    /**
     * Set the name of the field.
     *
     * @param identifier the field name
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the full signature of the field.
     *
     * @param fullSignature the full signature of the field
     */
    public void setFullSignature(String fullSignature) {
        this.fullSignature = fullSignature;
    }

    /**
     * Set the declarator of the field.
     *
     * @param declarator the declarator of the field
     */
    public void setDeclarator(String declarator) {
        this.declarator = declarator;
    }

    /**
     * Set the modifiers of the field.
     *
     * @param modifiers the list of modifiers of the field
     */
    public void setModifiers(List<String> modifiers) {
        this.modifiers = modifiers;
    }

    /**
     * Set the type of the field.
     *
     * @param type the type of the field
     */
    public void setType(Type type) {
        this.type = type;
    }
}
