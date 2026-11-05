package star.tracto.preprocessing.utils;

import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedTypeVariable;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the resolution of the type parameters along a chain of calls ({@link TypeParameterValues}): a type variable
 * resolves to the resolved types recorded for it, whatever their order and whatever the order in which the methods
 * were collected.
 */
class TypeParameterValuesTest {

    private static final ReflectionTypeSolver TYPE_SOLVER = new ReflectionTypeSolver();

    private static ResolvedReferenceTypeDeclaration declaration(String qualifiedName) {
        return TYPE_SOLVER.solveType(qualifiedName);
    }

    private static ResolvedType type(String qualifiedName) {
        return new ReferenceTypeImpl(declaration(qualifiedName));
    }

    /** The type parameter {@code T} of {@code Iterable}, as left unresolved by a step of the chain. */
    private static ResolvedType iterableT() {
        return new ResolvedTypeVariable(declaration("java.lang.Iterable").getTypeParameters().get(0));
    }

    /** The type parameter {@code E} of {@code Iterator}, the return type of {@code next()}. */
    private static ResolvedType iteratorE() {
        return new ResolvedTypeVariable(declaration("java.util.Iterator").getTypeParameters().get(0));
    }

    private static List<String> describe(List<ResolvedType> types) {
        return types.stream().map(ResolvedType::describe).toList();
    }

    @Test
    void theTypeVariablesAmongTheValuesOfATypeParameterAreSkippedWhateverTheirPosition() {
        assertEquals(List.of("java.lang.String"), describe(TypeParameterValues.resolvedValues(List.of(iterableT(), type("java.lang.String")))));
        assertEquals(List.of("java.lang.String"), describe(TypeParameterValues.resolvedValues(List.of(type("java.lang.String"), iterableT()))));
    }

    @Test
    void allTheResolvedValuesOfATypeParameterAreKept() {
        assertEquals(List.of("java.lang.String", "java.lang.Integer"),
                describe(TypeParameterValues.resolvedValues(List.of(type("java.lang.String"), iterableT(), type("java.lang.Integer")))));
    }

    @Test
    void aTypeParameterWithoutResolvedValuesKeepsItsValues() {
        assertEquals(List.of("T"), describe(TypeParameterValues.resolvedValues(List.of(iterableT()))));
    }

    @Test
    void aTypeVariableResolvesToTheResolvedValuesRecordedForItsTypeParameter() {
        // The values of Iterator<E> after `items().iterator()`: from Iterable.iterator() (T, left unresolved) and from
        // Collection.iterator() (the element type), in the order the two declarations were processed
        Map<String, List<ResolvedType>> references = Map.of("java.util.Iterator<E>", List.of(iterableT(), type("java.lang.String")));

        assertEquals(List.of("java.lang.String"), describe(TypeParameterValues.valuesOf(iteratorE(), references)));
        // Not a type variable, or a type variable without recorded values: unchanged
        assertEquals(List.of("java.lang.Integer"), describe(TypeParameterValues.valuesOf(type("java.lang.Integer"), references)));
        assertEquals(List.of("T"), describe(TypeParameterValues.valuesOf(iterableT(), references)));
    }

    @Test
    void theMethodsAreOrderedByDeclaringTypeThenSignatureWhateverTheOrderOfTheSet() {
        List<MethodUsage> methods = new ArrayList<>(declaration("java.util.Collection").getAllMethods());
        List<MethodUsage> reversed = new ArrayList<>(methods.reversed());

        List<MethodUsage> sorted = TypeParameterValues.inFixedOrder(new LinkedHashSet<>(methods));
        assertEquals(sorted, TypeParameterValues.inFixedOrder(new LinkedHashSet<>(reversed)));
        List<String> iterators = sorted.stream().filter(m -> m.getName().equals("iterator"))
                .map(m -> m.declaringType().getQualifiedName()).toList();
        assertEquals(List.of("java.lang.Iterable", "java.util.Collection"), iterators);
    }
}
