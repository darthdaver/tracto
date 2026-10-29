package star.tracto.utils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the choice of the JDK sources given to the symbol solver ({@link JavaUtils}), on a fake {@code lib} folder
 * laid out as the one of the module: {@code lib/java<version>/src} and {@code lib/java_classpaths.json}.
 */
class JavaUtilsTest {

    @TempDir
    Path base;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "6  | 8",
            "8  | 8",
            "9  | 11",
            "11 | 11",
            "18 | 21",
            "22 | 25",
            "30 | 25",
            "   | 25 8",
    })
    void theJdkOfTheDeclaredVersionIsTheSmallestNotOlderOrTheNewestOne(Integer declared, String expectedJdks) throws IOException {
        lib("8", "11", "17", "21", "25");

        JavaUtils.setJavaLibPath(base, Optional.ofNullable(declared));

        assertEquals(Arrays.stream(expectedJdks.split(" ")).map(Integer::valueOf).toList(), JavaUtils.getJdkVersions());
    }

    @Test
    void jdksListedWithoutTheirFolderAreIgnored() throws IOException {
        // As after downloading an archive of lib older than lib/java_classpaths.json
        lib("8", "11", "17");
        write("lib/java_classpaths.json", """
                {"8": ["src"], "11": ["src/java.base", "src/java.sql"], "17": ["src/java.base", "src/java.sql"], "25": ["src/java.base"]}
                """);

        JavaUtils.setJavaLibPath(base, Optional.of(22), 21);
        List<Integer> declaredJava22 = JavaUtils.getJdkVersions();
        JavaUtils.setJavaLibPath(base, Optional.empty(), 21);
        List<Integer> unknownVersion = JavaUtils.getJdkVersions();

        assertEquals(List.of(17), declaredJava22);
        assertEquals(List.of(17, 8), unknownVersion);
    }

    @Test
    void classpathHasOnlyTheSourcesOfTheSelectedJdk() throws IOException {
        lib("8", "11", "17");

        JavaUtils.setJavaLibPath(base, Optional.of(11), 11);

        assertEquals(List.of(source("11", "java.base"), source("11", "java.sql")), classpath());
    }

    @Test
    void classpathEndsWithTheJdkOfTheRunningJvmWhenTheSelectedOneIsOlder() throws IOException {
        lib("8", "11", "17", "21", "25");

        JavaUtils.setJavaLibPath(base, Optional.of(8), 21);
        List<String> olderJdk = classpath();
        List<Integer> olderJdkVersions = JavaUtils.getJdkVersions();
        JavaUtils.setJavaLibPath(base, Optional.of(25), 21);
        List<String> newerJdk = classpath();

        assertEquals(List.of(base.resolve("lib/java8/src").toString(), source("21", "java.base"), source("21", "java.sql")), olderJdk);
        assertEquals(List.of(8), olderJdkVersions);
        assertEquals(List.of(source("25", "java.base"), source("25", "java.sql")), newerJdk);
    }

    @Test
    void classesMissingFromTheJdkOfTheRepositoryAreResolvedFromTheJdkOfTheRunningJvm() throws IOException {
        // JavaParser describes java.lang.Enum by reflection on the running JVM: on Java 21 it implements Constable,
        // which references classes added in Java 12 (here ConstantDesc), absent from the JDK of a Java 8 repository
        write("lib/java8/src/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java21/src/java.base/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java21/src/java.base/java/lang/constant/ConstantDesc.java", "package java.lang.constant; public interface ConstantDesc {}");
        write("lib/java_classpaths.json", """
                {"8": ["src"], "21": ["src/java.base"]}
                """);
        Path repo = Files.createDirectories(base.resolve("repo"));

        JavaUtils.setJavaLibPath(base, Optional.of(8), 21);
        JavaParserUtils.setRepoJavaParser(repo, JavaUtils.initializeJavaClassPath());
        CompilationUnit cu = JavaParserUtils.getCompilationUnit("class C { java.lang.constant.ConstantDesc d; }");

        assertEquals("java.lang.constant.ConstantDesc",
                cu.findFirst(com.github.javaparser.ast.type.ClassOrInterfaceType.class).orElseThrow().resolve().describe());
    }

    @Test
    void classpathHasTheSourcesOfTheNewestThenTheOldestJdkWhenTheVersionIsUnknown() throws IOException {
        lib("8", "11", "17");

        JavaUtils.setJavaLibPath(base, Optional.empty());

        assertEquals(List.of(source("17", "java.base"), source("17", "java.sql"), base.resolve("lib/java8/src").toString()), classpath());
    }

    @Test
    void classesRemovedAfterJava8AreResolvedFromTheOldestJdkWhenTheVersionIsUnknown() throws IOException {
        // JAXB is in JDK 8 and was removed in Java 11
        write("lib/java8/src/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java8/src/javax/xml/bind/JAXB.java", "package javax.xml.bind; public final class JAXB {}");
        write("lib/java17/src/java.base/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java_classpaths.json", """
                {"8": ["src"], "17": ["src/java.base"]}
                """);
        Path repo = Files.createDirectories(base.resolve("repo"));

        JavaUtils.setJavaLibPath(base, Optional.empty());
        JavaParserUtils.setRepoJavaParser(repo, JavaUtils.initializeJavaClassPath());
        CompilationUnit cu = JavaParserUtils.getCompilationUnit("class C { javax.xml.bind.JAXB jaxb; }");

        assertEquals("javax.xml.bind.JAXB",
                cu.findFirst(com.github.javaparser.ast.type.ClassOrInterfaceType.class).orElseThrow().resolve().describe());
    }

    @Test
    void lookupOfAJdkClassFollowsTheSelectedJdks() throws IOException {
        lib("8", "11");
        Path list = Path.of("java/util/List.java");
        write("lib/java8/src/java/util/List.java", "package java.util; public interface List<E> {}");
        write("lib/java11/src/java.base/java/util/List.java", "package java.util; public interface List<E> {}");

        JavaUtils.setJavaLibPath(base, Optional.empty());
        List<Path> allJdks = JavaUtils.getPathToJavaLibClassFromFqn(list, false);
        JavaUtils.setJavaLibPath(base, Optional.of(8));
        List<Path> jdk8 = JavaUtils.getPathToJavaLibClassFromFqn(list, false);

        assertEquals(List.of(base.resolve("lib/java11/src/java.base").resolve(list), base.resolve("lib/java8/src").resolve(list)), allJdks);
        assertEquals(List.of(base.resolve("lib/java8/src").resolve(list)), jdk8);
    }

    @Test
    void autoArgumentTakesTheVersionDeclaredByTheRepository() throws IOException {
        lib("8", "11", "17");
        Path repo = Files.createDirectories(base.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project><properties><maven.compiler.release>9</maven.compiler.release></properties></project>
                """);

        JavaUtils.setJavaLibPath(base, "auto", repo);

        assertEquals(Optional.of(9), JavaUtils.getJavaVersion());
        assertEquals("detected", JavaUtils.getJavaVersionSource());
        assertEquals(List.of(11), JavaUtils.getJdkVersions());
    }

    @Test
    void autoArgumentUsesTheNewestThenTheOldestJdkWhenTheRepositoryDeclaresNoVersion() throws IOException {
        lib("8", "11", "17");
        Path repo = Files.createDirectories(base.resolve("repo"));

        JavaUtils.setJavaLibPath(base, "auto", repo);

        assertEquals(Optional.empty(), JavaUtils.getJavaVersion());
        assertEquals("not detected", JavaUtils.getJavaVersionSource());
        assertEquals(List.of(17, 8), JavaUtils.getJdkVersions());
    }

    @Test
    void numericArgumentForcesTheVersion() throws IOException {
        lib("8", "11", "17");
        Path repo = Files.createDirectories(base.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project><properties><maven.compiler.release>8</maven.compiler.release></properties></project>
                """);

        JavaUtils.setJavaLibPath(base, "17", repo);

        assertEquals(Optional.of(17), JavaUtils.getJavaVersion());
        assertEquals("argument", JavaUtils.getJavaVersionSource());
        assertEquals(List.of(17), JavaUtils.getJdkVersions());
    }

    @Test
    void methodsAddedAfterJava8AreResolvedUnlessTheRepositoryDeclaresJava8() throws IOException {
        // A fake JDK 8 whose List has no `of`, and a fake JDK 11 whose List has it
        write("lib/java8/src/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java8/src/java/util/List.java", "package java.util; public interface List<E> { boolean add(E e); }");
        write("lib/java11/src/java.base/java/lang/Object.java", "package java.lang; public class Object {}");
        write("lib/java11/src/java.base/java/util/List.java", """
                package java.util;
                public interface List<E> {
                    boolean add(E e);
                    static <E> List<E> of() { return null; }
                }
                """);
        write("lib/java_classpaths.json", """
                {"8": ["src"], "11": ["src/java.base"]}
                """);
        Path repo = Files.createDirectories(base.resolve("repo"));

        assertEquals("java11/src/java.base/java/util/List.java", declaringFileOfListOf(repo, Optional.empty()));
        assertEquals("java11/src/java.base/java/util/List.java", declaringFileOfListOf(repo, Optional.of(11)));
        assertEquals("unresolved", declaringFileOfListOf(repo, Optional.of(8)));
    }

    /**
     * Configures the JDKs for the given version and the parser on the repository, as the pipeline does, and returns the
     * file (relative to {@code lib}) declaring the method resolved for {@code java.util.List.of()}.
     */
    private String declaringFileOfListOf(Path repo, Optional<Integer> javaVersion) throws IOException {
        JavaUtils.setJavaLibPath(base, javaVersion);
        JavaParserUtils.setRepoJavaParser(repo, JavaUtils.initializeJavaClassPath());
        CompilationUnit cu = JavaParserUtils.getCompilationUnit("class C { void m() { java.util.List.of(); } }");
        try {
            return cu.findFirst(MethodCallExpr.class).orElseThrow().resolve().toAst()
                    .flatMap(declaration -> declaration.findCompilationUnit())
                    .flatMap(CompilationUnit::getStorage)
                    .map(storage -> base.resolve("lib").relativize(storage.getPath()).toString())
                    .orElse("no source");
        } catch (UnsolvedSymbolException e) {
            return "unresolved";
        }
    }

    /** Creates a fake {@code lib} with the given JDK versions: Java 8 with a single source folder, the others with two modules. */
    private void lib(String... versions) throws IOException {
        StringBuilder json = new StringBuilder("{");
        for (String version : versions) {
            if (version.equals("8")) {
                Files.createDirectories(base.resolve("lib/java8/src/java/lang"));
                json.append("\"8\": [\"src\"],");
            } else {
                Files.createDirectories(base.resolve("lib/java" + version + "/src/java.base/java/lang"));
                Files.createDirectories(base.resolve("lib/java" + version + "/src/java.sql/java/sql"));
                json.append("\"%s\": [\"src/java.base\", \"src/java.sql\"],".formatted(version));
            }
        }
        write("lib/java_classpaths.json", json.substring(0, json.length() - 1) + "}");
    }

    private String source(String version, String module) {
        return base.resolve("lib/java" + version + "/src/" + module).toString();
    }

    private List<String> classpath() {
        return Arrays.asList(JavaUtils.initializeJavaClassPath().split(":"));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = base.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
