package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.Clazz;
import star.tratto.preprocessing.components.Field;
import star.tratto.preprocessing.components.Method;

import java.util.List;

public class ClazzBuilder {

    private String identifier;
    private List<String> superClasses;
    private List<String> interfaces;
    private String filePath;
    private List<Field> fields;
    private List<Method> methods;

    public Clazz build() {
        return new Clazz(
                this.identifier,
                this.superClasses,
                this.interfaces,
                this.filePath,
                this.fields,
                this.methods
        );
    }

    /**
    * Set the name of a class.
    *
    * @param identifier the class name
    */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the superclasses of a class.
     *
     * @param superClasses the list of classes extended by the class
     */
    public void setSuperClasses(List<String> superClasses) {
        this.superClasses = superClasses;
    }

    /**
     * Set the interfaces implemented by a class.
     *
     * @param interfaces the list of interfaces implemented by the class
     */
    public void setInterfaces(List<String> interfaces) {
        this.interfaces = interfaces;
    }

    /**
     * Set the file path of a class.
     *
     * @param filePath the file path of the class within the repository
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
        * Set the fields of a class.
        *
        * @param fields the class fields
        */
        public void setFields(List<Field> fields) {
            this.fields = fields;
        }

        /**
        * Set the methods of a class.
        *
        * @param methods the class methods
        */
        public void setMethods(List<Method> methods) {
            this.methods = methods;
        }
}
