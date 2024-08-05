package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.Clazz;
import star.tratto.preprocessing.components.Type;

public class TypeBuilder {

    private String identifier;
    private Clazz clazz;

    public Type build() {
        return new Type(
                this.identifier,
                this.clazz
        );
    }

    /**
     * Set the identifier of the type.
     *
     * @param identifier the type identifier
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the class of the type.
     *
     * @param clazz the class of the type. It can be null
     */
    public void setClazz(Clazz clazz) {
        this.clazz = clazz;
    }
}
