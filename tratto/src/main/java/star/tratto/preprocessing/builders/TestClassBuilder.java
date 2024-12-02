package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.*;
import star.tratto.preprocessing.components.FieldTratto;
import star.tratto.preprocessing.components.CallableTratto;

import java.util.List;

public class TestClassBuilder {

    private String identifier;
    private String packageIdentifier;
    private List<String> superClasses;
    private List<String> interfaces;
    private String filePath;
    private List<FieldTratto> fields;
    private List<CallableTratto> auxiliaryMethods;
    private List<CallableTratto> setupTearDownMethods;
    private List<CallableTratto> testCases;

    public TestClassTratto build() {
        return new TestClassTratto(
                this.identifier,
                this.packageIdentifier,
                this.superClasses,
                this.interfaces,
                this.filePath,
                this.fields,
                this.auxiliaryMethods,
                this.setupTearDownMethods,
                this.testCases
        );
    }

    /**
     * Set the name of the test class.
     *
     * @param identifier the test class name
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the package of the test class.
     *
     * @param packageIdentifier the package of the test class
     */
    public void setPackageIdentifier(String packageIdentifier) {
        this.packageIdentifier = packageIdentifier;
    }

    /**
     * Set the superclasses of the test class.
     *
     * @param superClasses the list of classes extended by the test class
     */
    public void setSuperClasses(List<String> superClasses) {
        this.superClasses = superClasses;
    }

    /**
     * Set the interfaces implemented by the test class.
     *
     * @param interfaces the list of interfaces implemented by the test class
     */
    public void setInterfaces(List<String> interfaces) {
        this.interfaces = interfaces;
    }

    /**
     * Set the file path of a class.
     *
     * @param filePath the file path of the test class within the repository
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Set the fields of a class.
     *
     * @param fields the fields defined in the test class
     */
    public void setFields(List<FieldTratto> fields) {
        this.fields = fields;
    }

    /**
     * Set the methods of a class.
     *
     * @param methods the class methods
     */
    public void setAuxiliaryMethods(List<CallableTratto> methods) {
        this.auxiliaryMethods = methods;
    }

    /**
     * Set the setup and tear down methods of the test class.
     *
     * @param methods the setup and tear down methods
     */
    public void setSetupTearDownMethods(List<CallableTratto> methods) {
        this.setupTearDownMethods = methods;
    }

    /**
     * Set the test cases of the test class.
     *
     * @param testCases the test cases defined in the test class
     */
    public void setTestCases(List<CallableTratto> testCases) {
        this.testCases = testCases;
    }
}
