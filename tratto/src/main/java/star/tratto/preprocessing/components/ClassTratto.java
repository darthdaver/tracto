package star.tratto.preprocessing.components;

import java.util.List;

public record ClassTratto(
        /* The name of the class. Example: `ClassName` */
        String identifier,
        /* The package of the class. Example: `star.tratto.preprocessing.components` */
        String packageIdentifier,
        /* The list of inherited classes. Example: `["SuperClass1, SuperClass2"]` */
        List<String> superclasses,
        /* The interfaces implemented by the class. Example: `["Interface1, Interface2"]` */
        List<String> interfaces,
        /* The relative path of the class. Example: `src/main/[java/test]/[path_to_class]/ClassName.java` */
        String filePath,
        /* The list of fields defined in the class (public, protected, private). */
        List<FieldTratto> fields,
        /* The list of constructors defined in the class */
        List<CallableTratto> constructors,
        /* The list of methods defined in the class */
        List<CallableTratto> methods

) {}
