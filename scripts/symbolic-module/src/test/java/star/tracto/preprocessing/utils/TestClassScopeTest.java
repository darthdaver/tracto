package star.tracto.preprocessing.utils;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the members visible within a test class ({@link TestClassScope}): the fields and methods inherited from its
 * ancestors following the access rules of Java, and the methods collected one by one when the test class cannot be
 * resolved as a whole.
 */
class TestClassScopeTest {

    @TempDir
    Path sources;

    private void write(String relativePath, String source) throws IOException {
        Path file = sources.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /**
     * Parses a class of the sources with a symbol solver on the sources and the JDK (by reflection). As in the pipeline
     * ({@code JavaParserUtils.setRepoJavaParser}), the type solver of the sources parses with the same configuration,
     * on which the symbol solver is set: the ancestors reached through the symbol solver can be resolved in turn.
     */
    private TypeDeclaration<?> parse(String relativePath) throws IOException {
        ParserConfiguration configuration = new ParserConfiguration();
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(), new JavaParserTypeSolver(sources, configuration));
        configuration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(configuration).parse(sources.resolve(relativePath)).getResult().orElseThrow().getType(0);
    }

    private static List<String> names(List<VariableDeclarator> variables) {
        return variables.stream().map(VariableDeclarator::getNameAsString).toList();
    }

    private static List<String> methodNames(List<TestClassScope.Method> methods) {
        return methods.stream().map(TestClassScope.Method::name).toList();
    }

    @Test
    void thePackagePrivateMembersOfAnAncestorAreInheritedOnlyWithinTheSamePackage() throws IOException {
        write("base/Base.java", """
                package base;
                public class Base {
                    public int publicField;
                    protected int protectedField;
                    int packageField;
                    private int privateField;
                    protected void protectedMethod() {}
                    void packageMethod() {}
                    private void privateMethod() {}
                }
                """);
        write("tests/FooTest.java", """
                package tests;
                public class FooTest extends base.Base {
                    Object unresolved() { return new Missing(); }
                }
                """);
        TypeDeclaration<?> testClass = parse("tests/FooTest.java");

        assertEquals(List.of("publicField", "protectedField"), names(TestClassScope.inheritedFields(testClass)));
        List<String> methods = methodNames(TestClassScope.methodsOneByOne(testClass));
        assertTrue(methods.containsAll(List.of("unresolved", "protectedMethod")), methods.toString());
        assertFalse(methods.contains("packageMethod"), methods.toString());
        assertFalse(methods.contains("privateMethod"), methods.toString());
    }

    @Test
    void theFieldsOfTheAncestorsAreCollectedTransitivelyClosestFirstWithTheConstantsOfTheInterfaces() throws IOException {
        write("tests/Constants.java", """
                package tests;
                public interface Constants {
                    int LIMIT = 3;
                }
                """);
        write("tests/Base.java", """
                package tests;
                public class Base {
                    int base;
                }
                """);
        write("tests/Middle.java", """
                package tests;
                public class Middle extends Base implements Constants {
                    protected int middle;
                }
                """);
        write("tests/FooTest.java", """
                package tests;
                public class FooTest extends Middle implements Constants {
                    int own;
                }
                """);

        // The fields declared in the test class are not included; the interface is visited once
        assertEquals(List.of("middle", "LIMIT", "base"), names(TestClassScope.inheritedFields(parse("tests/FooTest.java"))));
    }

    @Test
    void anAncestorThatCannotBeResolvedIsSkipped() throws IOException {
        write("tests/FooTest.java", """
                package tests;
                public class FooTest extends missing.MissingBase {
                    private int helper() { return 1; }
                    @org.junit.jupiter.api.Test
                    void testSomething() {}
                }
                """);
        TypeDeclaration<?> testClass = parse("tests/FooTest.java");

        assertEquals(List.of(), TestClassScope.inheritedFields(testClass));
        // The test methods are excluded; the private methods of the test class itself are callable within it
        List<TestClassScope.Method> methods = TestClassScope.methodsOneByOne(testClass);
        assertTrue(methodNames(methods).contains("helper"), methods.toString());
        assertFalse(methodNames(methods).contains("testSomething"), methods.toString());
    }

    @Test
    void aMethodWhoseSignatureCannotBeResolvedIsOfferedByItsName() throws IOException {
        write("tests/FooTest.java", """
                package tests;
                public class FooTest {
                    static java.util.stream.Stream<missing.Arguments> provider(missing.Info info) { return null; }
                    private int twice(int x) { return 2 * x; }
                }
                """);

        List<TestClassScope.Method> methods = TestClassScope.methodsOneByOne(parse("tests/FooTest.java"));

        TestClassScope.Method provider = methods.stream().filter(m -> m.name().equals("provider")).findFirst().orElseThrow();
        TestClassScope.Method twice = methods.stream().filter(m -> m.name().equals("twice")).findFirst().orElseThrow();
        assertTrue(provider.usage().isEmpty());
        assertTrue(twice.usage().isPresent());
        // The methods of Object come from the JDK (here by reflection, without a declaration)
        assertTrue(methodNames(methods).contains("toString"), methods.toString());
        assertFalse(methodNames(methods).contains("registerNatives"), methods.toString());
    }
}
