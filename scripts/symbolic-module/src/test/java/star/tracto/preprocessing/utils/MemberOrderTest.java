package star.tracto.preprocessing.utils;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The methods offered as candidates are collected in a fixed order (Step 24), whatever the order of the sets the symbol
 * solver returns them in (hash sets ordered by identity hash codes): by declaring type and name or signature, then by
 * position in the source when those are equal or cannot be resolved.
 */
class MemberOrderTest {

    private static final String SOURCE = """
            class Fixture {
                void zeta() {}
                void alpha(int a) {}
                void mid() {}
                void alpha() {}
                void beta(Unknown u) {}
                void beta(AlsoUnknown u) {}
            }
            """;

    @Test
    void theDeclaredMethodsHaveTheSameOrderWhateverTheOrderOfTheSet() {
        List<ResolvedMethodDeclaration> methods = new ArrayList<>(parseClass().resolve().getDeclaredMethods());
        List<String> expected = List.of("alpha(int)", "alpha()", "beta(Unknown)", "beta(AlsoUnknown)", "mid()", "zeta()");
        Random random = new Random(24);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(methods, random);
            List<String> ordered = MemberOrder.methodDeclarations(new LinkedHashSet<>(methods)).stream()
                    .map(MemberOrderTest::sourceSignature).collect(Collectors.toList());
            assertEquals(expected, ordered);
        }
    }

    // A MethodUsage resolves the types of its parameters when created: one with an unresolvable signature cannot exist
    // (getAllMethods() fails, and the declared methods are collected instead), so the position is a last key only
    @Test
    void methodUsagesAreOrderedByDeclaringTypeThenSignature() {
        List<MethodUsage> methods = parseClass().resolve().getDeclaredMethods().stream()
                .filter(m -> !m.getName().equals("beta")).map(MethodUsage::new).collect(Collectors.toList());
        Random random = new Random(24);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(methods, random);
            List<String> ordered = MemberOrder.methodUsages(new LinkedHashSet<>(methods)).stream()
                    .map(m -> sourceSignature(m.getDeclaration())).collect(Collectors.toList());
            assertEquals(List.of("alpha()", "alpha(int)", "mid()", "zeta()"), ordered);
        }
    }

    private static String sourceSignature(ResolvedMethodDeclaration method) {
        return method.toAst().map(node -> ((com.github.javaparser.ast.body.MethodDeclaration) node).getSignature().asString()
                .replace(" ", "")).orElseThrow();
    }

    private static ClassOrInterfaceDeclaration parseClass() {
        ParserConfiguration configuration = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver()));
        CompilationUnit compilationUnit = new JavaParser(configuration).parse(SOURCE).getResult().orElseThrow();
        return compilationUnit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
