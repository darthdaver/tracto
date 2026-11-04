package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.junit.jupiter.api.Test;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the variables in scope at the position of the target of a split test case ({@link TargetScope}): the position
 * is located in the split test prefix (parsed from its own file, like in the pipeline), and the declarations visible
 * there are collected.
 */
class TargetScopeTest {

    /**
     * A split test case and its split test prefix, each parsed from its own source, as the tokens stage parses the
     * files {@code ...STARSplitTest.java} and {@code ...STARSplitTestPrefix.java}. The prefix is the split test case
     * without its target (the last assertion), like the splitter generates it.
     */
    private record Split(MethodDeclaration testCase, MethodDeclaration prefix) {}

    private static Split split(String method) {
        String source = "class FooTest {\n" + method + "\n}";
        MethodDeclaration testCase = StaticJavaParser.parse(source).getType(0).getMethods().get(0);
        CompilationUnit prefixUnit = StaticJavaParser.parse(source);
        MethodDeclaration prefix = prefixUnit.getType(0).getMethods().get(0);
        Statement target = JavaParserUtils.getLastAssertionInMethodDeclaration(prefix).orElseThrow();
        target.remove();
        // Parse the prefix again from its source, as the pipeline does with the file of the split test prefix
        return new Split(testCase, StaticJavaParser.parse(prefixUnit.toString()).getType(0).getMethods().get(0));
    }

    private static List<String> namesInScope(String method) {
        Split split = split(method);
        Node position = TargetScope.targetPosition(split.testCase(), split.prefix()).orElseThrow();
        TargetScope.Declarations declarations = TargetScope.declarationsInScope(position);
        return names(declarations);
    }

    /** The names of the variables (innermost first), then of the parameters ({@code param:<name>}, innermost first). */
    private static List<String> names(TargetScope.Declarations declarations) {
        List<String> names = new java.util.ArrayList<>();
        declarations.variables().stream().map(VariableDeclarator::getNameAsString).forEach(names::add);
        declarations.parameters().stream().map(Parameter::getNameAsString).forEach(n -> names.add("param:" + n));
        return names;
    }

    @Test
    void topLevelTargetSeesTheTopLevelDeclarationsAndTheParametersOfTheMethod() {
        assertEquals(List.of("a", "b", "param:input"), namesInScope("""
                void test(int input) {
                    int a = 1;
                    int b = a + input;
                    assertEquals(2, b);
                }
                """));
    }

    @Test
    void targetWithinALoopSeesTheVariablesOfTheLoopBodyAndOfTheEnclosingBlocks() {
        assertEquals(List.of("row", "i", "rows"), namesInScope("""
                void test() {
                    int rows = 2;
                    for (int i = 0; i < rows; i++) {
                        int row = i * 2;
                        assertEquals(i * 2, row);
                    }
                }
                """));
    }

    @Test
    void targetWithinAForEachSeesItsVariable() {
        assertEquals(List.of("value", "values"), namesInScope("""
                void test() {
                    int[] values = {1, 2};
                    for (int value : values) {
                        assertTrue(value > 0);
                    }
                }
                """));
    }

    @Test
    void targetWithinAWhileWithinAnIfSeesTheDeclarationsOfEveryEnclosingBlock() {
        assertEquals(List.of("next", "count", "it"), namesInScope("""
                void test() {
                    java.util.Iterator<String> it = java.util.List.of("a").iterator();
                    if (it.hasNext()) {
                        int count = 0;
                        while (it.hasNext()) {
                            String next = it.next();
                            assertNotNull(next);
                        }
                    }
                }
                """));
    }

    @Test
    void declarationsOfPreviousSiblingBlocksAndLaterStatementsAreNotInScope() {
        // `inner` and `i` belong to a loop that precedes the target, `x` to a lambda of a previous statement, `e` to a
        // catch clause of a previous try statement: none of them is visible at the position of the target
        assertEquals(List.of("total"), namesInScope("""
                void test() {
                    for (int i = 0; i < 1; i++) {
                        int inner = i;
                    }
                    java.util.List.of(1).forEach(x -> System.out.println(x));
                    try {
                        System.out.println();
                    } catch (RuntimeException e) {
                        System.out.println(e);
                    }
                    int total = 1;
                    assertEquals(1, total);
                }
                """));
    }

    @Test
    void targetWithinALambdaBlockSeesItsParametersAndTheEnclosingDeclarations() {
        assertEquals(List.of("doubled", "values", "param:v"), namesInScope("""
                void test() {
                    java.util.List<Integer> values = java.util.List.of(1, 2);
                    values.forEach(v -> {
                        int doubled = v * 2;
                        assertEquals(v * 2, doubled);
                    });
                }
                """));
    }

    @Test
    void targetWithinATryWithResourcesSeesTheResources() {
        // The splitter empties the catch blocks that follow the target
        assertEquals(List.of("read", "reader"), namesInScope("""
                void test() throws Exception {
                    try (java.io.StringReader reader = new java.io.StringReader("a")) {
                        int read = reader.read();
                        assertEquals('a', read);
                    } catch (java.io.IOException e) {
                    }
                }
                """));
    }

    @Test
    void targetWithinACatchClauseSeesItsParameter() {
        assertEquals(List.of("value", "param:e"), namesInScope("""
                void test() {
                    String value = null;
                    try {
                        value.length();
                    } catch (NullPointerException e) {
                        assertNotNull(e);
                    }
                }
                """));
    }

    @Test
    void targetWithinASwitchEntrySeesTheDeclarationsOfTheSwitchBlockBeforeIt() {
        Split split = split("""
                void test(int kind) {
                    switch (kind) {
                        case 1:
                            int one = 1;
                            break;
                        default:
                            int other = 2;
                            assertEquals(2, other);
                    }
                }
                """);
        Node position = TargetScope.targetPosition(split.testCase(), split.prefix()).orElseThrow();

        assertInstanceOf(SwitchEntry.class, position);
        assertEquals(List.of("other", "one", "param:kind"), names(TargetScope.declarationsInScope(position)));
    }

    @Test
    void innerDeclarationsComeBeforeTheOuterOnesWithTheSameName() {
        // A lambda parameter cannot shadow a local variable, but a variable of a nested class method can: the order
        // (innermost first) lets the consumers prefer the innermost declaration
        assertEquals(List.of("value", "param:value"), namesInScope("""
                void test(int value) {
                    Runnable r = new Runnable() {
                        public void run() {
                            int value = 2;
                            assertEquals(2, value);
                        }
                    };
                }
                """));
    }

    @Test
    void thePositionIsTheBlockOfThePrefixThatContainedTheTarget() {
        Split split = split("""
                void test() {
                    for (int i = 0; i < 2; i++) {
                        assertTrue(i >= 0);
                    }
                }
                """);

        Optional<Node> position = TargetScope.targetPosition(split.testCase(), split.prefix());

        assertTrue(position.isPresent());
        BlockStmt block = assertInstanceOf(BlockStmt.class, position.get());
        assertTrue(block.isEmpty());
        // The position belongs to the prefix, not to the split test case: its declarations resolve in the prefix file
        assertTrue(block.findAncestor(MethodDeclaration.class).orElseThrow() == split.prefix());
    }

    @Test
    void noPositionWhenThePrefixDoesNotMatchTheSplitTestCase() {
        Split split = split("""
                void test() {
                    int a = 1;
                    assertEquals(1, a);
                }
                """);
        MethodDeclaration otherPrefix = StaticJavaParser.parse("""
                class FooTest {
                    void test() {
                        if (true) {
                            int a = 1;
                        }
                    }
                }
                """).getType(0).getMethods().get(0);

        assertTrue(TargetScope.targetPosition(split.testCase(), otherPrefix).isEmpty());
    }

    @Test
    void noPositionWithoutAnAssertion() {
        MethodDeclaration method = StaticJavaParser.parse("class FooTest { void test() { int a = 1; } }").getType(0).getMethods().get(0);

        assertTrue(TargetScope.targetPosition(method, method.clone()).isEmpty());
    }
}
