package star.tracto.preprocessing.utils;

import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The resolution of the type parameters along a chain of calls (Step 21), independent of the order in which the
 * methods are processed.
 *
 * <p>Each step of a chain records the values of the type parameters it meets (for example {@code java.util.Iterator<E>}
 * after {@code items().iterator()}). A type parameter may get several values: {@code iterator()} is declared in
 * {@code Collection} and, inherited, in {@code Iterable}, whose type parameter {@code T} is not resolved by the step,
 * so {@code Iterator<E>} gets the element type and {@code T}. Taking the first value made the type of
 * {@code next()} depend on the order of the methods, which came from a hash set ordered by identity hash codes: it
 * changed from one oracle to another and with any change to the code run before.</p>
 */
final class TypeParameterValues {

    private TypeParameterValues() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * The values of a type parameter that are resolved types, in their order; the type variables among them (left
     * unresolved by an earlier step) are skipped. If no value is resolved, the values are returned unchanged.
     *
     * @param values the values recorded for a type parameter
     * @return the resolved values, or the given values if none is resolved
     */
    static List<ResolvedType> resolvedValues(List<ResolvedType> values) {
        List<ResolvedType> resolved = values.stream().filter(value -> !value.isTypeVariable()).toList();
        // A copy: the callers iterate it while recording values of the type parameters
        return resolved.isEmpty() ? List.copyOf(values) : resolved;
    }

    /**
     * The types a type stands for: if it is a type variable whose type parameter has recorded values, its resolved
     * values ({@link #resolvedValues(List)}); otherwise the type itself.
     *
     * @param type a type met along the chain (a return type, the value of a type parameter)
     * @param typeParametersReferences the values recorded for the type parameters, by key
     *                                 ({@code <qualified name of the declaring type><<name>>})
     * @return the types the given type stands for
     */
    static List<ResolvedType> valuesOf(ResolvedType type, Map<String, List<ResolvedType>> typeParametersReferences) {
        if (type.isTypeVariable()) {
            List<ResolvedType> values = typeParametersReferences.get(key(type));
            if (values != null && !values.isEmpty()) {
                return resolvedValues(values);
            }
        }
        return List.of(type);
    }

    /**
     * The methods in a fixed order (declaring type, then signature) instead of the order of the set, which depends on
     * the identity hash codes of the methods.
     *
     * @param methods the methods of a type (as given by the symbol solver, in a hash set)
     * @return the methods in a fixed order
     */
    static List<MethodUsage> inFixedOrder(Collection<MethodUsage> methods) {
        // The order is shared with the other collections of methods, with the position in the source as the last key
        // (Step 24)
        return MemberOrder.methodUsages(methods);
    }

    private static String key(ResolvedType typeVariable) {
        return typeVariable.asTypeParameter().getContainerQualifiedName() + "<" + typeVariable.asTypeParameter().getName() + ">";
    }
}
