package star.tratto.preprocessing.components;


import java.util.List;

/**
 * Represents a Java method, describing all the information in a structured way.
 * The record can describe a test method (test prefix) or a source method (focal method).
 * The method contains:
 * <ol>
 *     <li>the name of the method</li>
 *     <li>the annotations of the method</li>
 *     <li>the modifiers of the method</li>
 *     <li>the signature of the method</li>
 *     <li>the full signature of the method</li>
 *     <li>the class test method signature</li>
 *     <li>the exceptions thrown by the method</li>
 *     <li>the parameters of the method</li>
 *     <li>the return type of the method</li>
 *     <li>the documentation of the method</li>
 *     <li>the body of the method</li>
 *     <li>the list of the other methods invoked within it. It can be null if the information are not provided or
 *     retrieved. It can also contain a subset list of methods retrieved, if some of them cannot be collected (for
 *     example if the method are defined within an external library).</li>
 * </ol>
 */
public record Method(
        /* The name of the test method. Example: `methodName` */
        String identifier,
        /* The annotations of the method. Example: `["@Test"]` */
        List<String> annotations,
        /* The modifiers of the test method. Example: `["public", "static"]` */
        List<String> modifiers,
        /* The signature of the test method. Example: `testMethod(ParamType1 param1, ParamType2 param2)` */
        String signature,
        /* The full signature of the test method. Example: `@Test public void testMethod(ParamType1 param1, ParamType2 param2)` */
        String fullSignature,
        /* The class test method signature. Example: `TestClass.testMethodSignature(ParamType1 param1, ParamType2 param2)`*/
        String classTestMethodSignature,
        /* The exceptions thrown by the test method. Example: `["Exception1", "Exception2"]` */
        List<String> thrownExceptions,
        /* The parameters of the test method. Example: `["ParamType1 param1", "ParamType2 param2"]` */
        List<String> parameters,
        /* The return type of the test method. */
        Type returnType,
        /* The documentation of the test method. */
        String javadoc,
        /* The body of the test method. */
        String body,
        /* The list of methods invoked within the method. It can be null. */
        List<Method> invokedMethods

) {}
