package star.tratto.preprocessing.builders;

import star.tratto.preprocessing.components.Method;
import star.tratto.preprocessing.components.Type;

import java.util.List;

public class MethodBuilder {

    private String identifier;
    private List<String> annotations;
    private List<String> modifiers;
    private String signature;
    private String fullSignature;
    private String classTestMethodSignature;
    private List<String> thrownExceptions;
    private List<String> parameters;
    private Type returnType;
    private String javadoc;
    private String body;
    private List<Method> invokedMethods;

    public Method build() {
        return new Method(
                this.identifier,
                this.annotations,
                this.modifiers,
                this.signature,
                this.fullSignature,
                this.classTestMethodSignature,
                this.thrownExceptions,
                this.parameters,
                this.returnType,
                this.javadoc,
                this.body,
                this.invokedMethods
        );
    }

    /**
     * Set the name of a test method.
     *
     * @param identifier the test method name
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Set the annotations of a test method.
     * Example:
     *      [`@Test`]
     *
     * @param annotations the test method annotations
     */
    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    /**
     * Set the modifiers of a test method.
     * Example:
     *      `["public", "static"]`
     *
     * @param modifiers the test method modifiers
     */
    void setModifiers(List<String> modifiers){
        this.modifiers = modifiers;
    }

    /**
     * Set the signature of a test method.
     * Example:
     *      `testMethod(ParamType1 param1, ParamType2 param2)`
     *
     * @param signature the test method signature
     */
    void setSignature(String signature) {
        this.signature = signature;
    }

    /**
     * Set the signature of a test method.
     * Example:
     *      `@Test public void testMethod(ParamType1 param1, ParamType2 param2)`
     *
     * @param fullSignature the test method fully signature
     */
    void setFullSignature(String fullSignature) {
        this.fullSignature = fullSignature;
    }

    /**
     * Set the class test method signature.
     * Example:
     *      `TestClass.testMethodSignature(ParamType1 param1, ParamType2 param2)`
     *
     * @param classTestMethodSignature the class test method signature
     */
    void setClassTestMethodSignature(String classTestMethodSignature) {
        this.classTestMethodSignature = classTestMethodSignature;
    }

    /**
     * Set the exceptions thrown by a test method.
     * Example:
     *      `["Exception1", "Exception2"]`
     *
     * @param thrownExceptions the test method thrown exceptions
     */
    void setThrownExceptions(List<String> thrownExceptions) {
        this.thrownExceptions = thrownExceptions;
    }

    /**
     * Set the parameters of a test method.
     * Example:
     *      `["ParamType1 param1", "ParamType2 param2"]`
     *
     * @param parameters the test method parameters
     */
    void setParameters(List<String> parameters) {
        this.parameters = parameters;
    }

    /**
     * Set the documentation of a test method.
     *
     * @param javadoc the test method documentation
     */
    void setJavadoc(String javadoc) {
        this.javadoc = javadoc;
    }

    /**
     * Set the body of a test method.
     *
     * @param body the test method body
     */
    void setBody(String body) {
        this.body = body;
    }

    /**
     * Set the return type of test method.
     *
     * @param returnType the test method return type
     */
    void setReturnType(Type returnType) {
        this.returnType = returnType;
    }

    /**
     * Set the list of methods invoked within the method. it can be null.
     *
     * @param invokedMethods the list of methods invoked within the method. It can be null
     */
    void setInvokedMethods(List<Method> invokedMethods) {
        this.invokedMethods = invokedMethods;
    }
}
