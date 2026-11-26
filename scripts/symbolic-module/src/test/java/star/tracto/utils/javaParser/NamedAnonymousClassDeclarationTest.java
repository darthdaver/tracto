package star.tracto.utils.javaParser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The declaration of an anonymous class with the same name at each run (Step 54): JavaParser names each instance of its
 * declaration {@code Anonymous-<UUID.randomUUID()>}.
 */
class NamedAnonymousClassDeclarationTest {

    private static final ReflectionTypeSolver TYPE_SOLVER = new ReflectionTypeSolver();

    @Test
    void anAnonymousClassIsNamedAfterItsSupertypeAtEachInstance() {
        List<ObjectCreationExpr> creations = creations("""
                package com.acme;

                class T {
                    void m() {
                        interface Local {
                        }
                        var counter = new Object() {
                            int count;
                        };
                        var runnable = new Runnable() {
                            public void run() {
                            }
                        };
                        var local = new Local() {
                        };
                    }
                }
                """);

        NamedAnonymousClassDeclaration counter = new NamedAnonymousClassDeclaration(creations.get(0), TYPE_SOLVER);
        assertEquals("Anonymous-java.lang.Object", counter.getName());
        assertEquals("com.acme.T.Anonymous-java.lang.Object", counter.getQualifiedName());
        assertEquals(counter.getName(), new NamedAnonymousClassDeclaration(creations.get(0), TYPE_SOLVER).getName());
        assertEquals("Anonymous-java.lang.Runnable", new NamedAnonymousClassDeclaration(creations.get(1), TYPE_SOLVER).getName());
        assertEquals("Anonymous-Local", new NamedAnonymousClassDeclaration(creations.get(2), TYPE_SOLVER).getName());
    }

    private static List<ObjectCreationExpr> creations(String source) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(TYPE_SOLVER));
        CompilationUnit unit = new JavaParser(configuration).parse(source).getResult().orElseThrow();
        return unit.findAll(ObjectCreationExpr.class);
    }
}
