package star.tracto.utils.javaParser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                        int a1 = 2;
                        use(a1);
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
                    } catch (RuntimeException e1) {
                        e1.printStackTrace();
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

    @Test
    void getVarFromNameExprReturnsTheDeclaratorOfTheVariable() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int x, int y) {}
                    void m() {
                        int a = 1, b = 2;
                        use(a, b);
                    }
                }
                """), "m");
        Set<VariableDeclarator> existingVariables = identitySet(localVariable(method, "a"), localVariable(method, "b"));

        Optional<VariableDeclarator> resolved = JavaParserUtils.getVarFromNameExpr(nameExpr(method, "b"), existingVariables);

        assertSame(localVariable(method, "b"), resolved.orElseThrow());
    }

    @Test
    void getVarFromNameExprIsEmptyForAVariableOutsideTheExistingVariables() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void use(int x, int y) {}
                    void m() {
                        int a = 1, b = 2;
                        use(a, b);
                    }
                }
                """), "m");

        Optional<VariableDeclarator> resolved = JavaParserUtils.getVarFromNameExpr(nameExpr(method, "b"), identitySet(localVariable(method, "a")));

        assertTrue(resolved.isEmpty());
    }

    @Test
    void getVarFromNameExprIsEmptyForAClassNameOrAParameter() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m(String s) {
                        Integer.parseInt(s);
                    }
                }
                """), "m");
        Set<VariableDeclarator> existingVariables = identitySet();

        assertTrue(JavaParserUtils.getVarFromNameExpr(nameExpr(method, "Integer"), existingVariables).isEmpty());
        assertTrue(JavaParserUtils.getVarFromNameExpr(nameExpr(method, "s"), existingVariables).isEmpty());
    }

    @Test
    void renameLocalParamIsEmptyWithoutClash() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m() {
                        int a = 1;
                        try {
                            a++;
                        } catch (RuntimeException e) {
                            a--;
                        }
                    }
                }
                """), "m");
        Parameter catchParameter = method.findFirst(Parameter.class).orElseThrow();

        assertTrue(JavaParserUtils.renameLocalParam(catchParameter, identitySet(localVariable(method, "a"))).isEmpty());
    }

    @Test
    void renameLocalParamReturnsAnUnusedNameOnClash() throws IOException {
        MethodDeclaration method = method(parse("""
                class C {
                    void m() {
                        int e = 1;
                        try {
                            e++;
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    }
                }
                """), "m");
        Parameter catchParameter = method.findFirst(Parameter.class).orElseThrow();
        Set<VariableDeclarator> existingVariables = identitySet(localVariable(method, "e"));

        String newName = JavaParserUtils.renameLocalParam(catchParameter, existingVariables).orElseThrow();

        assertNotEquals("e", newName);
        assertFalse(JavaParserUtils.variableDeclaratorsToNames(existingVariables).contains(newName));
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "int x = 1; assertEquals(1, x);                                  | assertEquals(1, x);",
            "int x = 1; assert x > 0;                                        | assert x > 0;",
            "int x = 1; IllegalStateException e = assertThrows(IllegalStateException.class, () -> use(x)); | IllegalStateException e = assertThrows(IllegalStateException.class, () -> use(x));",
            "assertThrows(IllegalStateException.class, () -> use(1));         | assertThrows(IllegalStateException.class, () -> use(1));",
            "for (int i = 0; i < 2; i++) { use(i); assertTrue(i >= 0); }      | assertTrue(i >= 0);"
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
    @Disabled("D23: the assertion within the last lambda of assertAll is returned instead of the assertAll statement")
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
