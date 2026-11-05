package star.tracto.preprocessing.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.Type;
import org.javatuples.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fields and the local variables offered as candidates are collected in the order of their declarations (Step 24),
 * not in the order of their identity hash codes, which changes from one run to another and with any change of the
 * allocations.
 *
 * <p>The names collide in the {@code HashMap} of the identifiers: {@code "Aa"} and {@code "BB"} have the same hash
 * code, and so have all the names made of them ({@code AaAa}, {@code AaBB}, ...); names in the same bucket of a
 * {@code HashMap} are iterated in the order of insertion. With eight of them, an order of the identity hash codes
 * matches the order of the declarations once in 40,320.</p>
 */
class IdentifiersOrderTest {

    /** Eight names with the same {@code String.hashCode()}, in an order that is not the alphabetical one. */
    private static final List<String> COLLIDING_NAMES = List.of(
            "BBBBBB", "AaAaAa", "BBAaBB", "AaBBAa", "BBBBAa", "AaAaBB", "BBAaAa", "AaBBBB");

    @Test
    void theNamesCollideInTheHashMap() {
        assertEquals(1, COLLIDING_NAMES.stream().map(String::hashCode).distinct().count());
    }

    @Test
    void theFieldsAreInTheOrderOfTheirDeclarations() {
        String fields = COLLIDING_NAMES.stream().map(name -> "    int " + name + ";\n").collect(Collectors.joining());
        ClassOrInterfaceDeclaration clazz = parseClass("class Fixture {\n" + fields + "}\n");
        HashMap<String, List<Pair<VariableDeclarator, Type>>> identifiers = new HashMap<>();
        TokensUtils.updateHashMapOfFieldTypeIdentifiers(identifiers, clazz.getFields(), new ArrayList<>(), ClassOrInterfaceDeclaration.class, 0);
        assertEquals(COLLIDING_NAMES, new ArrayList<>(identifiers.keySet()));
    }

    @Test
    void theFieldsOfOneDeclarationAreInTheirOrder() {
        ClassOrInterfaceDeclaration clazz = parseClass("class Fixture {\n    int " + String.join(", ", COLLIDING_NAMES) + ";\n}\n");
        HashMap<String, List<Pair<VariableDeclarator, Type>>> identifiers = new HashMap<>();
        TokensUtils.updateHashMapOfFieldTypeIdentifiers(identifiers, clazz.getFields(), new ArrayList<>(), ClassOrInterfaceDeclaration.class, 0);
        assertEquals(COLLIDING_NAMES, new ArrayList<>(identifiers.keySet()));
    }

    @Test
    void theLocalVariablesAreInTheOrderOfTheirDeclarations() {
        String variables = COLLIDING_NAMES.stream().map(name -> "        int " + name + " = 0;\n").collect(Collectors.joining());
        ClassOrInterfaceDeclaration clazz = parseClass("class Fixture {\n    void test() {\n" + variables + "    }\n}\n");
        MethodDeclaration method = clazz.getMethodsByName("test").get(0);
        HashMap<String, List<Pair<VariableDeclarator, Type>>> identifiers = new HashMap<>();
        TokensUtils.updateHashMapOfFieldTypeIdentifiers(identifiers, method, 0);
        assertEquals(COLLIDING_NAMES, new ArrayList<>(identifiers.keySet()));
    }

    @Test
    void equalDeclarationsOfDifferentClassesAreBothKept() {
        // Two declarations equal as nodes (same text) but distinct: the collection is by identity, as before
        ClassOrInterfaceDeclaration first = parseClass("class First {\n    int value;\n}\n");
        ClassOrInterfaceDeclaration second = parseClass("class Second {\n    int value;\n}\n");
        HashMap<String, List<Pair<VariableDeclarator, Type>>> identifiers = new HashMap<>();
        TokensUtils.updateHashMapOfFieldTypeIdentifiers(identifiers, first.getFields(), new ArrayList<>(), ClassOrInterfaceDeclaration.class, 0);
        TokensUtils.updateHashMapOfFieldTypeIdentifiers(identifiers, second.getFields(), new ArrayList<>(), ClassOrInterfaceDeclaration.class, 0);
        assertEquals(List.of("value"), new ArrayList<>(identifiers.keySet()));
        // The list of a name keeps one entry per equal declarator (the check of TokensUtils is by equals), as before
        assertEquals(1, identifiers.get("value").size());
    }

    private static ClassOrInterfaceDeclaration parseClass(String source) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(source);
        return compilationUnit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
