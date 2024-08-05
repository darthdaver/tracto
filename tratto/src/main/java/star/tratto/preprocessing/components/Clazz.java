package star.tratto.preprocessing.components;

import java.util.List;

/**
 * Represents a Java class, describing all the information in a structured way.
 * The record can describe be a test class or a source class.
 * The class contains:
 * <ol>
 *     <li>the name of the class</li>
 *     <li>the classes inherited (superclasses)</li>
 *     <li>the interfaces implemented</li>
 *     <li>the file path to retrieve the class file within the repository</li>
 *     <li>the fields defined within the repository</li>
 *     <li>the methods defined within the class (that are not test cases, if the record refers to a test class)</li>
 * </ol>
 */
public record Clazz(
        /* The name of the test class. Example: `TestClass` */
        String identifier,
        /* The list of inherited classes. Example: `["SuperClass1, SuperClass2"]` */
        List<String> superclasses,
        /* The interfaces implemented by the test class. Example: `["Interface1, Interface2"]` */
        List<String> interfaces,
        /* The relative path of the test class. Example: `src/main/[java/test]/[path_to_class]/TestClass.java` */
        String filePath,
        /* The list of fields defined in the test class (public, void, protected). */
        List<Field> fields,
        /* The list of methods defined in the class (that are not test methods in case of test classes). */
        List<Method> methods
) {}
