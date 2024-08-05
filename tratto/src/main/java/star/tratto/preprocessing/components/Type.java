package star.tratto.preprocessing.components;

/**
 * Represents the type of a field or the return type of a method in a Java class.
 * The type can be a primitive type, a reference type, or a generic type.
 * The signature of the type is represented as a string, denoted as `identifier`.
 * The class of the type is represented as a {@link Clazz} record object. It can be null if
 * the type is a primitive type or a generic type, or if the class is not available (for example if
 * it is a class of an external library of the repository or if the information is not required).
 */
public record Type(
        /* The full identifier of the type. Example: `Type` or `List<Type>`, etc. */
        String identifier,
        /* The information about the class of the type (it can be null) */
        Clazz clazz
) {}
