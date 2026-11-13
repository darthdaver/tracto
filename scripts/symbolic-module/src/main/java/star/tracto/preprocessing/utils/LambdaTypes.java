package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedWildcard;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import star.tracto.utils.javaParser.JavaParserUtils;

/**
 * The type of a parameter of a lambda without a declared type ({@code it -> ...}), whose body contains the target
 * (Step 42). Its type ({@code UnknownType}) cannot be resolved by itself: the symbol solver infers it from the
 * functional interface the lambda is passed as ({@code Parameter.resolve()}), and gives the type argument of the
 * interface as a constraint ({@code ResolvedLambdaConstraintType}, described {@code ? super X} for
 * {@code Consumer<? super T>}) or a wildcard. Java gives the parameter the bound of the wildcard (JLS 15.27.3, the
 * non-wildcard parameterization of the interface, JLS 9.9): {@code X} for {@code ? super X} and for
 * {@code ? extends X}, {@code Object} for {@code ?}.
 */
final class LambdaTypes {

    private LambdaTypes() {
    }

    /**
     * The type of a parameter: its declared type, or, for a parameter of a lambda without a declared type, the type
     * inferred by the symbol solver, without a wildcard.
     *
     * @throws RuntimeException if it cannot be resolved
     */
    static ResolvedType parameterType(Type type) {
        if (type.isUnknownType()
                && type.getParentNode().orElse(null) instanceof Parameter parameter
                && parameter.getParentNode().orElse(null) instanceof LambdaExpr) {
            return withoutWildcard(parameter.resolve().getType());
        }
        return type.resolve();
    }

    /** The bound of a constraint or of a wildcard ({@code Object} for an unbounded one), or the type itself. */
    static ResolvedType withoutWildcard(ResolvedType type) {
        while (type.isConstraint() || type.isWildcard()) {
            if (type.isConstraint()) {
                type = type.asConstraintType().getBound();
                continue;
            }
            ResolvedWildcard wildcard = type.asWildcard();
            if (!wildcard.isBounded()) {
                return JavaParserUtils.solveType("java.lang.Object")
                        .<ResolvedType>map(ReferenceTypeImpl::new)
                        .orElseThrow(() -> new IllegalStateException("java.lang.Object not resolved"));
            }
            type = wildcard.getBoundedType();
        }
        return type;
    }
}
