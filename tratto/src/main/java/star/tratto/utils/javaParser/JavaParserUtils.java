package star.tratto.utils.javaParser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.utils.javaParser.visitors.MethodCallExprVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public class JavaParserUtils {

    private static JavaParser javaParser;
    private static final Logger logger = LoggerFactory.getLogger(JavaParserUtils.class);

    /** Do not instantiate this class. */
    private JavaParserUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Creates a JavaParser object capable of resolving symbols from a given
     * source directory (like a java repository).
     *
     * @param repoRootPath a project source directory
     * @return the corresponding JavaParser
     */
    public static JavaParser setRepoJavaParser(Path repoRootPath) {
        SymbolSolverCollectionStrategy strategy = new SymbolSolverCollectionStrategy();
        strategy.collect(repoRootPath);
        SymbolResolver symbolResolver = strategy.getParserConfiguration().getSymbolResolver().orElseThrow();
        javaParser = new JavaParser();
        javaParser.getParserConfiguration().setSymbolResolver(symbolResolver);
        return javaParser;
    }

    /**
     * Gets a compilation unit from a Java file path.
     *
     * @param path a Java file
     * @return the corresponding JavaParser compilation unit. Returns
     * {@code Optional.empty()} if an error occurs while attempting to parse
     * the file.
     */
    public static CompilationUnit getCompilationUnit(Path path) throws IOException {
        if (javaParser == null) {
            throw new IllegalStateException("JavaParser must be set with a repository root path, before calling this method.");
        }
        return javaParser.parse(path).getResult().orElseThrow();
    }
}
