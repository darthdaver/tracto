package star.tracto.preprocessing.builders;

import org.javatuples.Pair;
import star.tracto.preprocessing.components.CallableTracto;
import star.tracto.preprocessing.components.TypeTracto;

import java.util.List;

public class CallableBuilder {

    private String identifier;
    private List<String> annotations;
    private List<String> modifiers;
    private String signature;
    private String fullSignature;
    private String classTestMethodSignature;
    private List<String> thrownExceptions;
    private List<Pair<String, TypeTracto>> parameters;
    private TypeTracto returnType;
    private String javadoc;
    private String body;
    private List<CallableTracto> invokedMethods;

    public CallableTracto build() {
        return new CallableTracto(
                this.identifier,
                this.annotations,
                this.modifiers,
                this.signature,
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
    public void setModifiers(List<String> modifiers){
        this.modifiers = modifiers;
    }

    /**
     * Set the signature of a test method.
     * Example:
     *      `testMethod(ParamType1 param1, ParamType2 param2)`
     *
     * @param signature the test method signature
     */
    public void setSignature(String signature) {
        this.signature = signature;
    }

    /**
     * Set the exceptions thrown by a test method.
     * Example:
     *      `["Exception1", "Exception2"]`
     *
     * @param thrownExceptions the test method thrown exceptions
     */
    public void setThrownExceptions(List<String> thrownExceptions) {
        this.thrownExceptions = thrownExceptions;
    }

    /**
     * Set the parameters of a test method.
     * Example:
     *      `["ParamType1 param1", "ParamType2 param2"]`
     *
     * @param parameters the test method parameters
     */
    public void setParameters(List<Pair<String, TypeTracto>> parameters) {
        this.parameters = parameters;
    }

    /**
     * Set the documentation of a test method.
     *
     * @param javadoc the test method documentation
     */
    public void setJavadoc(String javadoc) {
        this.javadoc = javadoc;
    }

    /**
     * Set the body of a test method.
     *
     * @param body the test method body
     */
    public void setBody(String body) {
        this.body = body;
    }

    /**
     * Set the return type of test method.
     *
     * @param returnType the test method return type
     */
    public void setReturnType(TypeTracto returnType) {
        this.returnType = returnType;
    }

    /**
     * Set the list of methods invoked within the method. it can be null.
     *
     * @param invokedMethods the list of methods invoked within the method. It can be null
     */
    public void setInvokedMethods(List<CallableTracto> invokedMethods) {
        this.invokedMethods = invokedMethods;
    }
}
