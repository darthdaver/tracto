package star.tracto.utils.javaParser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import org.javatuples.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the helpers of {@link JavaParserUtils} used by the normalization of the test cases. The code under analysis is
 * parsed with the parser configured by {@link JavaParserUtils#setRepoJavaParser}, so that its symbols can be resolved.
 */
class JavaParserUtilsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("repo"));
        Path emptyClasspath = Files.createDirectories(tempDir.resolve("no-libs"));
        JavaParserUtils.setRepoJavaParser(repoRoot, emptyClasspath.toString());
    }

    @Test
    void normalizeVariableNamesRenamesLocalDeclaredAgainInSiblingBlock() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v) {}
                    void m() {
                        {
                            int a = 1;
                            use(a);
                        }
                        {
                            int a = 2;
                            use(a);
                        }
                    }
                }
                """), "m");

        JavaParserUtils.normalizeVariableNames(method.getBody().orElseThrow());

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    {
                        int a = 1;
                        use(a);
                    }
                    {
                        int a2 = 2;
                        use(a2);
                    }
                }
                """), method.getBody().orElseThrow());
    }

    @Test
    void normalizeVariableNamesRenamesCatchParameterDeclaredAgainInSiblingCatch() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v) {}
                    void m() {
                        try {
                            use(1);
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                        try {
                            use(2);
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    }
                }
                """), "m");

        JavaParserUtils.normalizeVariableNames(method.getBody().orElseThrow());

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    try {
                        use(1);
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                    try {
                        use(2);
                    } catch (RuntimeException e2) {
                        e2.printStackTrace();
                    }
                }
                """), method.getBody().orElseThrow());
    }

    @Test
    void normalizeVariableNamesKeepsNamesDeclaredOnce() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v) {}
                    void m() {
                        int a = 1;
                        for (int i = 0; i < a; i++) {
                            int b = i;
                            use(b);
                        }
                    }
                }
                """), "m");
        Statement before = method.getBody().orElseThrow().clone();

        JavaParserUtils.normalizeVariableNames(method.getBody().orElseThrow());

        assertEquals(before, method.getBody().orElseThrow());
    }

    @Test
    void replaceVariablesRenamesTheDeclarationAndItsUsagesButNotAFieldWithTheSameName() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    int x;
                    void use(int v) {}
                    void m() {
                        {
                            int x = 1;
                            use(x);
                        }
                        use(x);
                    }
                }
                """), "m");
        VariableDeclarator localX = localVariable(method, "x");

        JavaParserUtils.replaceVariables(method.getBody().orElseThrow(), identityMap(localX, "y"));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    {
                        int y = 1;
                        use(y);
                    }
                    use(x);
                }
                """), method.getBody().orElseThrow());
    }

    @Test
    void replaceVariablesRenamesOnlyTheTargetOfAMultipleDeclaration() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v, int w) {}
                    void m() {
                        int a = 1, b = a + 1;
                        use(a, b);
                    }
                }
                """), "m");

        JavaParserUtils.replaceVariables(method.getBody().orElseThrow(), identityMap(localVariable(method, "b"), "c"));

        assertEquals(StaticJavaParser.parseBlock("""
                {
                    int a = 1, c = a + 1;
                    use(a, c);
                }
                """), method.getBody().orElseThrow());
    }

    @Test
    void replaceParametersRenamesTheParameterAndItsUsagesButNotTheField() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    int v;
                    void use(int v) {}
                    void m(int v) {
                        use(v);
                        this.v = v;
                    }
                }
                """), "m");
        Map<Parameter, String> mapping = new IdentityHashMap<>();
        mapping.put(method.getParameter(0), "w");

        JavaParserUtils.replaceParameters(method.getBody().orElseThrow(), mapping);

        assertEquals("w", method.getParameter(0).getNameAsString());
        assertEquals(StaticJavaParser.parseBlock("""
                {
                    use(w);
                    this.v = w;
                }
                """), method.getBody().orElseThrow());
    }

    /**
     * The example of the documentation of {@code checkNodeScope}: each inner block, and each variable declared in the
     * header of a {@code for} or a {@code try}, adds a hierarchical level.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0 | var1",
            "1 | var1 var2 var3",
            "2 | var1 var2 var3 var4 var5"
    })
    void collectExistingVariablesCollectsTheVariablesUpToTheHierarchicalLevel(int hierarchyLevel, String expectedNames) throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m() {
                        int var1 = 0;
                        for (int var2 = 0; var2 < 1; var2++) {
                            int var3 = 0;
                            try (AutoCloseable var4 = null) {
                                int var5 = 0;
                            } catch (Exception e) {
                            }
                        }
                    }
                }
                """), "m");
        Set<VariableDeclarator> existingVariables = identitySet();

        JavaParserUtils.collectExistingVariables(existingVariables, method.getBody().orElseThrow(), MethodDeclaration.class, hierarchyLevel);

        assertEquals(Set.of(expectedNames.split(" ")), JavaParserUtils.variableDeclaratorsToNames(existingVariables));
    }

    @Test
    void collectExistingVariablesCollectsTheFieldsOfTheClass() throws IOException {
        ClassOrInterfaceDeclaration clazz = parse("""
                class C {
                    int a, b;
                    static String c;
                    void m() {
                        int local = 0;
                    }
                }
                """).getClassByName("C").orElseThrow();
        Set<VariableDeclarator> existingVariables = identitySet();

        JavaParserUtils.collectExistingVariables(existingVariables, clazz.getFields(), List.of(), ClassOrInterfaceDeclaration.class, 0);

        assertEquals(Set.of("a", "b", "c"), JavaParserUtils.variableDeclaratorsToNames(existingVariables));
    }

    /**
     * A node satisfies the rules if it satisfies at least one of them (a rule lists modifiers required or forbidden);
     * no rules, no constraint.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "public static int a;  | true",
            "private static int b; | false",
            "public int c;         | true",
            "protected int d;      | false"
    })
    void checkModifiersRulesRequiresAtLeastOneRuleToBeSatisfied(String field, boolean expected) {
        List<Modifier> modifiers = StaticJavaParser.parseBodyDeclaration(field).asFieldDeclaration().getModifiers();
        // public and static, or neither private nor protected
        List<List<Pair<Modifier.Keyword, Boolean>>> rules = List.of(
                List.of(new Pair<>(Modifier.Keyword.PUBLIC, true), new Pair<>(Modifier.Keyword.STATIC, true)),
                List.of(new Pair<>(Modifier.Keyword.PRIVATE, false), new Pair<>(Modifier.Keyword.PROTECTED, false))
        );

        assertEquals(expected, JavaParserUtils.checkModifiersRules(rules, modifiers));
        assertTrue(JavaParserUtils.checkModifiersRules(List.of(), modifiers));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "int x = 1; assertEquals(1, x);                                  | assertEquals(1, x);",
            "int x = 1; assert x > 0;                                        | assert x > 0;",
            "int x = 1; IllegalStateException e = assertThrows(IllegalStateException.class, () -> use(x)); | IllegalStateException e = assertThrows(IllegalStateException.class, () -> use(x));",
            "assertThrows(IllegalStateException.class, () -> use(1));         | assertThrows(IllegalStateException.class, () -> use(1));",
            "for (int i = 0; i < 2; i++) { use(i); assertTrue(i >= 0); }      | assertTrue(i >= 0);",
            // D32: the split test cases ending within a try block keep the following catch and finally blocks, empty
            "try { use(1); fail(\"x\"); } catch (RuntimeException e) { }       | fail(\"x\");",
            "try { use(1); assertTrue(true); } finally { }                    | assertTrue(true);"
    })
    void getLastAssertionInMethodDeclarationFindsTheLastAssertion(String body, String expectedAssertion) throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v) {}
                    void m() {
                        %s
                    }
                }
                """.formatted(body)), "m");

        Statement lastAssertion = JavaParserUtils.getLastAssertionInMethodDeclaration(method).orElseThrow();

        assertEquals(StaticJavaParser.parseStatement(expectedAssertion), lastAssertion);
    }

    @Test
    void getLastAssertionInMethodDeclarationReturnsAssertAllRatherThanTheAssertionsInItsLambdas() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m() {
                        assertAll(() -> assertEquals(1, 1), () -> assertTrue(true));
                    }
                }
                """), "m");

        Statement lastAssertion = JavaParserUtils.getLastAssertionInMethodDeclaration(method).orElseThrow();

        assertEquals(StaticJavaParser.parseStatement("assertAll(() -> assertEquals(1, 1), () -> assertTrue(true));"), lastAssertion);
    }

    @Test
    void getLastAssertionInMethodDeclarationIsEmptyForAnAssertionInTheExpressionLambdaOfAnotherCall() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m(java.util.List<Integer> values) {
                        values.forEach(v -> assertTrue(v > 0));
                    }
                }
                """), "m");

        assertTrue(JavaParserUtils.getLastAssertionInMethodDeclaration(method).isEmpty());
    }

    @Test
    void getLastAssertionInMethodDeclarationIsEmptyWhenTheLastStatementIsNotAnAssertion() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int v) {}
                    void m() {
                        assertEquals(1, 1);
                        use(1);
                    }
                }
                """), "m");

        assertTrue(JavaParserUtils.getLastAssertionInMethodDeclaration(method).isEmpty());
    }

    @Test
    void declarationOfAProjectClassReachedByTheSymbolSolverCanBeResolved() throws IOException {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot.resolve("src/main/java/com/acme"));
        Files.writeString(repoRoot.resolve("src/main/java/com/acme/Box.java"), """
                package com.acme;

                public class Box<T> {
                    public void put(T content) {}
                }
                """);
        JavaParserUtils.setRepoJavaParser(repoRoot, tempDir.resolve("no-libs").toString());
        CompilationUnit cu = parse("""
                package com.acme;

                class C {
                    void m(Box<C> box) {
                        box.put(this);
                    }
                }
                """);

        // The declaration is parsed by the type solver of the project classes, not by the parser of the class under analysis
        MethodDeclaration put = (MethodDeclaration) cu.findFirst(MethodCallExpr.class).orElseThrow().resolve().toAst().orElseThrow();

        assertTrue(put.getParameter(0).getType().resolve().isTypeVariable());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Java 8: yield, var, record and sealed as names | class C { int var = 1; int record = 2; void yield(int x) {} void m() { int sealed = 3; yield(sealed); } }",
            "Java 14: switch expression                    | class C { int m(int x) { return switch (x) { case 1 -> 2; default -> { yield 3; } }; } }",
            "Java 16: record                               | record P(int x, int y) {}",
            "Java 17: sealed classes                       | sealed interface S permits A {} final class A implements S {}",
            "Java 21: type pattern with guard in switch    | class C { int m(Object o) { return switch (o) { case String s when s.isEmpty() -> 0; case null, default -> 1; }; } }",
            "Java 21: record pattern                       | class C { record P(int x, int y) {} int m(Object o) { return switch (o) { case P(int x, int y) when x > 0 -> x; default -> 0; }; } }",
            "Java 22: unnamed variables                    | class C { void m(java.util.List<String> l) { for (String _ : l) {} try { } catch (Exception _) { } l.forEach(_ -> {}); } }",
            "Java 22: unnamed pattern                      | class C { record P(int x, int y) {} int m(Object o) { return o instanceof P(int x, _) ? x : 0; } }",
            "Java 23: markdown documentation comment       | class C { /// A **markdown** comment\\n void m() {} }",
            "Java 25: statements before super()            | class C extends Object { C(int x) { if (x < 0) throw new IllegalArgumentException(); super(); } }",
            "Java 25: module import                        | import module java.base; class C {}",
    })
    void parserAcceptsTheSyntaxOfJava8To26(String feature, String code) {
        JavaParser parser = JavaParserUtils.setRepoJavaParser(tempDir.resolve("repo"), tempDir.resolve("no-libs").toString());

        ParseResult<CompilationUnit> result = parser.parse(code.replace("\\n", "\n"));

        assertEquals(List.of(), result.getProblems().stream().map(Problem::getMessage).toList(), feature);
    }

    @Test
    void projectClassWithNewSyntaxIsResolvedThroughTheTypeSolver() throws IOException {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot.resolve("src/main/java/com/acme"));
        // A class parsed by the type solver of the project classes, written with Java 25 syntax
        Files.writeString(repoRoot.resolve("src/main/java/com/acme/Box.java"), """
                package com.acme;

                import module java.base;

                public class Box {
                    private final List<String> items = new ArrayList<>();

                    public Box(String first) {
                        if (first == null) {
                            throw new IllegalArgumentException();
                        }
                        super();
                        items.add(first);
                    }

                    public int size() {
                        return items.size();
                    }
                }
                """);
        JavaParserUtils.setRepoJavaParser(repoRoot, tempDir.resolve("no-libs").toString());
        CompilationUnit cu = parse("""
                package com.acme;

                class C {
                    int m(Box box) {
                        return box.size();
                    }
                }
                """);

        assertEquals("com.acme.Box.size()", cu.findFirst(MethodCallExpr.class).orElseThrow().resolve().getQualifiedSignature());
    }

    @Test
    void resolveMethodCallPicksTheOverloadWhoseParametersAreExactlyTheArgumentTypes() throws IOException {
        // JavaParser 3.28.x finds these calls ambiguous: it widens the components of a primitive array (JavaParser
        // issue 4991, fixed after 3.28.2)
        parseWithProjectClass("""
                package com.acme;

                public class Bytes {
                    public static int count(byte[] values) { return 0; }
                    public static int count(int[] values) { return 0; }
                    public static int count(long[] values) { return 0; }
                }
                """);
        CompilationUnit cu = parse("""
                package com.acme;

                class C {
                    void calls(byte[] bytes, int[] ints) {
                        Bytes.count(bytes);
                        com.acme.Bytes.count(ints);
                    }
                }
                """);
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);

        assertEquals("com.acme.Bytes.count(byte[])", JavaParserUtils.resolveMethodCall(calls.get(0)).getQualifiedSignature());
        assertEquals("com.acme.Bytes.count(int[])", JavaParserUtils.resolveMethodCall(calls.get(1)).getQualifiedSignature());
    }

    @Test
    void resolveMethodCallCorrectsAPrimitiveArrayArgumentResolvedToAnotherArrayType() throws IOException {
        // JavaParser 3.28.x resolved Arrays.copyOf(char[], int) of jsoup to copyOf(double[], int), without error
        parseWithProjectClass("""
                package com.acme;

                public class Bytes {
                    public static native <T> T[] copyOf(T[] original, int newLength);
                    public static native byte[] copyOf(byte[] original, int newLength);
                    public static native short[] copyOf(short[] original, int newLength);
                    public static native int[] copyOf(int[] original, int newLength);
                    public static native long[] copyOf(long[] original, int newLength);
                    public static native char[] copyOf(char[] original, int newLength);
                    public static native float[] copyOf(float[] original, int newLength);
                    public static native double[] copyOf(double[] original, int newLength);
                    public static native boolean[] copyOf(boolean[] original, int newLength);
                }
                """);
        CompilationUnit cu = parse("""
                package com.acme;

                class C {
                    void calls(char[] chars, double[] doubles) {
                        Bytes.copyOf(chars, chars.length);
                        Bytes.copyOf(doubles, 3);
                    }
                }
                """);
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
        // The wrong choice depends on the order in which JavaParser meets the candidates (its caches, filled by the
        // previous resolutions): the resolution of the call is replaced by the overload it returned in jsoup
        ResolvedMethodDeclaration copyOfDoubles = calls.get(1).resolve();
        MethodCallExpr copyOfChars = Mockito.spy(calls.get(0));
        Mockito.doReturn(copyOfDoubles).when(copyOfChars).resolve();

        assertEquals("com.acme.Bytes.copyOf(char[], int)", JavaParserUtils.resolveMethodCall(copyOfChars).getQualifiedSignature());
    }

    @Test
    void resolveMethodCallResolvesAMethodWhoseReturnTypeIsUnknown() throws IOException {
        // JavaParser 3.28.x resolves the return type of the candidate methods while choosing one, and fails when it is
        // a class missing from the classpath (here the Answer of Mockito, as in esapi; fixed after 3.28.2)
        CompilationUnit cu = parse("""
                import org.mockito.stubbing.Answer;

                class C {
                    private class Container {
                        Answer<String> throwException() { return null; }
                    }

                    void calls() {
                        new Container().throwException();
                    }
                }
                """);

        assertEquals("C.Container.throwException()",
                JavaParserUtils.resolveMethodCall(cu.findFirst(MethodCallExpr.class).orElseThrow()).getQualifiedSignature());
    }

    @Test
    void resolveMethodCallResolvesTheOnlyMethodWithTheNameAndArityOfTheCall() throws IOException {
        // As the matchers of cryptofs: the return type is missing from the classpath, and the argument (an Integer) is
        // not exactly the type of the parameter (an int)
        CompilationUnit cu = parse("""
                import org.hamcrest.Matcher;

                class C {
                    private static class Matchers {
                        static Matcher<String> hasCapacity(int capacity) { return null; }
                    }

                    void calls(Integer size) {
                        Matchers.hasCapacity(size);
                    }
                }
                """);

        assertEquals("C.Matchers.hasCapacity(int)",
                JavaParserUtils.resolveMethodCall(cu.findFirst(MethodCallExpr.class).orElseThrow()).getQualifiedSignature());
    }

    @Test
    void resolveMethodCallStillFailsWhenNoOverloadMatchesExactly() throws IOException {
        parseWithProjectClass("""
                package com.acme;

                public class Bytes {
                    public static int count(long[] values) { return 0; }
                    public static int count(double[] values) { return 0; }
                }
                """);
        // javac rejects the call: an int[] is neither a long[] nor a double[]
        CompilationUnit cu = parse("""
                package com.acme;

                class C {
                    void calls(int[] ints) {
                        Bytes.count(ints);
                    }
                }
                """);

        assertThrows(RuntimeException.class, () -> JavaParserUtils.resolveMethodCall(cu.findFirst(MethodCallExpr.class).orElseThrow()));
    }

    /** Writes the given class as {@code com/acme/Bytes.java} of the repository, and configures the parser on it. */
    private void parseWithProjectClass(String source) throws IOException {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot.resolve("src/main/java/com/acme"));
        Files.writeString(repoRoot.resolve("src/main/java/com/acme/Bytes.java"), source);
        JavaParserUtils.setRepoJavaParser(repoRoot, tempDir.resolve("no-libs").toString());
    }

    private static CompilationUnit parse(String code) throws IOException {
        return JavaParserUtils.getCompilationUnit(code);
    }

    private static MethodDeclaration method(CompilationUnit cu, String name) {
        return cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals(name)).orElseThrow();
    }

    /** Returns the declarator of the local variable with the given name declared within the method. */
    private static VariableDeclarator localVariable(MethodDeclaration method, String name) {
        return method.getBody().orElseThrow()
                .findFirst(VariableDeclarator.class, v -> v.getNameAsString().equals(name))
                .orElseThrow();
    }

    /** Returns the last name expression with the given name within the method. */
    private static NameExpr nameExpr(MethodDeclaration method, String name) {
        List<NameExpr> nameExprs = method.findAll(NameExpr.class, n -> n.getNameAsString().equals(name));
        return nameExprs.get(nameExprs.size() - 1);
    }

    private static Set<VariableDeclarator> identitySet(VariableDeclarator... variables) {
        Set<VariableDeclarator> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(Arrays.asList(variables));
        return set;
    }

    private static Map<VariableDeclarator, String> identityMap(VariableDeclarator variable, String newName) {
        Map<VariableDeclarator, String> map = new IdentityHashMap<>();
        map.put(variable, newName);
        return map;
    }
}
