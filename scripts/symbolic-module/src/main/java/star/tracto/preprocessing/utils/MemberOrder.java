package star.tracto.preprocessing.utils;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A fixed order for the methods offered as candidates (Steps 21 and 24). The symbol solver returns the methods of a
 * type in hash sets ordered by the identity hash codes of the declarations ({@code getAllMethods()},
 * {@code getDeclaredMethods()}), which change from one run to another and with any change of the allocations; the
 * methods are collected in a {@code HashMap} by name, where names in the same bucket keep the order of insertion, so
 * the order of the candidates changed.
 *
 * <p>The order: the declaring type, then the signature (for the methods of {@code getAllMethods()}, as in Step 21) or
 * the name (for the declared methods, collected when {@code getAllMethods()} fails, often because a signature cannot be
 * resolved), then the position in the source when those are equal or cannot be resolved (overloads whose parameter
 * types are not solved), then the signature.</p>
 */
final class MemberOrder {

    /** Declarations without a source (reflection, Javassist) come after the others; their signatures then decide. */
    private static final Position NO_POSITION = new Position(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private static final Comparator<MethodUsage> METHOD_USAGES = Comparator
            .comparing((MethodUsage method) -> declaringTypeName(method))
            .thenComparing(method -> signature(method))
            .thenComparing(MethodUsage::getName)
            .thenComparing(methodUsage -> position(methodUsage.getDeclaration()));

    private static final Comparator<ResolvedMethodDeclaration> METHOD_DECLARATIONS = Comparator
            .comparing((ResolvedMethodDeclaration method) -> declaringTypeName(method))
            .thenComparing(ResolvedMethodDeclaration::getName)
            .thenComparing(method -> position(method))
            .thenComparing(method -> signature(method));

    private MemberOrder() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * @param methods the methods of a type (as given by the symbol solver, in a hash set)
     * @return the methods in a fixed order: declaring type, signature (or name), position in the source
     */
    static List<MethodUsage> methodUsages(Collection<MethodUsage> methods) {
        List<MethodUsage> ordered = new ArrayList<>(methods);
        ordered.sort(METHOD_USAGES);
        return ordered;
    }

    /**
     * @param methods the declared methods of a type (as given by the symbol solver, in a hash set)
     * @return the methods in a fixed order: declaring type, name, position in the source, signature
     */
    static List<ResolvedMethodDeclaration> methodDeclarations(Collection<ResolvedMethodDeclaration> methods) {
        List<ResolvedMethodDeclaration> ordered = new ArrayList<>(methods);
        ordered.sort(METHOD_DECLARATIONS);
        return ordered;
    }

    private static String declaringTypeName(MethodUsage method) {
        try {
            return method.declaringType().getQualifiedName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String declaringTypeName(ResolvedMethodDeclaration method) {
        try {
            return method.declaringType().getQualifiedName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String signature(MethodUsage method) {
        try {
            return method.getSignature();
        } catch (Exception e) {
            return method.getName();
        }
    }

    private static String signature(ResolvedMethodDeclaration method) {
        try {
            return method.getSignature();
        } catch (Exception e) {
            return method.getName();
        }
    }

    private static Position position(ResolvedMethodDeclaration method) {
        try {
            Optional<Node> node = method.toAst();
            return node.flatMap(Node::getBegin).orElse(NO_POSITION);
        } catch (Exception e) {
            return NO_POSITION;
        }
    }
}
