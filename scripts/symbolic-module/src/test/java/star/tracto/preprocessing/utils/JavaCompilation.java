package star.tracto.preprocessing.utils;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compiles generated test classes with the test classpath (JUnit included) and executes them, to check that the
 * generated code is valid Java and behaves like the original code.
 */
final class JavaCompilation {

    private JavaCompilation() {
    }

    /**
     * Compiles the given source files into {@code classesDir}, failing the calling test with the compiler output if the
     * compilation fails.
     */
    static void assertCompiles(Path classesDir, Path... sourceFiles) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        List<String> arguments = new ArrayList<>(List.of(
                "-proc:none",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString()));
        for (Path sourceFile : sourceFiles) {
            arguments.add(sourceFile.toString());
        }
        int exitCode = compiler.run(null, null, errors, arguments.toArray(String[]::new));
        assertEquals(0, exitCode, "Compilation of " + List.of(sourceFiles) + " failed:\n" + errors);
    }

    /**
     * Executes a test method (an instance method without parameters) of a compiled class and returns the content of
     * the static {@code TRACE} list of the class.
     */
    @SuppressWarnings("unchecked")
    static List<String> runTrace(Path classesDir, String className, String testMethodName) throws Exception {
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, JavaCompilation.class.getClassLoader())) {
            Class<?> testClass = classLoader.loadClass(className);
            testClass.getMethod(testMethodName).invoke(testClass.getDeclaredConstructor().newInstance());
            return List.copyOf((List<String>) testClass.getField("TRACE").get(null));
        }
    }
}
