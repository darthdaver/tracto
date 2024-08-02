package star.tratto.preprocessing.components;


import com.github.javaparser.ast.Modifier;

import java.util.List;

public record TestMethod(
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
        /* The body of the test method. */
        String body,
        /* The return type of the test method. */
        String returnType,
        /* The parameters of the test method. Example: `["ParamType1 param1", "ParamType2 param2"]` */
        List<String> parameters
) {}
