package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.ClassTratto;
import star.tratto.preprocessing.components.FieldTratto;
import star.tratto.preprocessing.components.CallableTratto;

import java.util.List;

public class ClassBuilder {

    private String identifier;
    private String packageIdentifier;
    private List<String> superClasses;
    private List<String> interfaces;
    private String filePath;
    private List<FieldTratto> fields;
    private List<CallableTratto> constructors;
    private List<CallableTratto> methods;

    public ClassTratto build() {
        return new ClassTratto(
                this.identifier,
                this.packageIdentifier,
                this.superClasses,
                this.interfaces,
                this.filePath,
                this.fields,
                this.constructors,
                this.methods
        );
    }

    /**
     * Set the name of the class.
     *
     * @param identifier the class name
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the package of the class.
     *
     * @param packageIdentifier the package of the class
     */
    public void setPackageIdentifier(String packageIdentifier) {
        this.packageIdentifier = packageIdentifier;
    }

    /**
     * Set the superclasses of the class.
     *
     * @param superClasses the list of classes extended by the class
     */
    public void setSuperClasses(List<String> superClasses) {
        this.superClasses = superClasses;
    }

    /**
     * Set the interfaces implemented by the class.
     *
     * @param interfaces the list of interfaces implemented by the class
     */
    public void setInterfaces(List<String> interfaces) {
        this.interfaces = interfaces;
    }

    /**
     * Set the file path of the class.
     *
     * @param filePath the file path of the class within the repository
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Set the fields of the class.
     *
     * @param fields the fields defined in the class
     */
    public void setFields(List<FieldTratto> fields) {
        this.fields = fields;
    }

    /**
     * Set the list of constructors defined in the class.
     *
     * @param constructors the constructors of the class
     */
    public void setConstructors(List<CallableTratto> constructors) {
        this.constructors = constructors;
    }

    /**
     * Set the list of methods defined in the class.
     *
     * @param methods the methods of the class
     */
    public void setMethods(List<CallableTratto> methods) {
        this.methods = methods;
    }
}
