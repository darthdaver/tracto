package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The members visible within a test class (Step 22): its fields and methods, whatever their access modifier, and the
 * members it inherits from its superclasses and interfaces, following the access rules of Java (the private members of
 * an ancestor are not inherited, its package-private members only within the same package).
 *
 * <p>The members of the ancestors are read from their declarations (the classes of the project and the JDK sources);
 * an ancestor that cannot be resolved (for example a class of a dependency missing from the classpath) is skipped,
 * with the ones above it.</p>
 */
final class TestClassScope {

    private static final Logger logger = LoggerFactory.getLogger(TestClassScope.class);

    /**
     * A method visible within the test class.
     *
     * @param name the name of the method
     * @param usage the method resolved, or empty if its signature cannot be resolved (for example a type of a dependency
     *              missing from the classpath): the method is still callable within the test class
     */
    record Method(String name, Optional<MethodUsage> usage) {}

    private TestClassScope() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * The fields inherited by the test class from its ancestors, the closest ancestors first (the fields declared in
     * the test class itself are not included).
     *
     * @param testClass the test class
     * @return the variables of the fields of the ancestors visible within the test class
     */
    static List<VariableDeclarator> inheritedFields(TypeDeclaration<?> testClass) {
        List<VariableDeclarator> fields = new ArrayList<>();
        String testPackage = packageOf(testClass);
        for (TypeDeclaration<?> ancestor : ancestors(testClass).declared()) {
            boolean samePackage = packageOf(ancestor).equals(testPackage);
            for (FieldDeclaration field : ancestor.getFields()) {
                if (isInherited(field, ancestor, samePackage)) {
                    fields.addAll(field.getVariables());
                }
            }
        }
        return fields;
    }

    /**
     * The methods callable within the test class without a receiver, collected one by one: the methods declared in the
     * test class, then the ones inherited from its ancestors. The test methods (annotated with {@code @Test}) are
     * excluded. Used when the test class cannot be resolved as a whole (the signature of one of its methods or of an
     * inherited method cannot be resolved): one method that cannot be resolved does not hide the others.
     *
     * @param testClass the test class
     * @return the methods callable within the test class
     */
    static List<Method> methodsOneByOne(TypeDeclaration<?> testClass) {
        List<Method> methods = new ArrayList<>();
        addMethods(methods, testClass, true, true);
        String testPackage = packageOf(testClass);
        Ancestors ancestors = ancestors(testClass);
        for (TypeDeclaration<?> ancestor : ancestors.declared()) {
            addMethods(methods, ancestor, false, packageOf(ancestor).equals(testPackage));
        }
        // An ancestor without a declaration (a class of a library on the classpath) gives its methods and the inherited
        // ones (its ancestors are not visited)
        for (ResolvedReferenceTypeDeclaration ancestor : ancestors.withoutDeclaration()) {
            try {
                for (MethodUsage usage : TypeParameterValues.inFixedOrder(ancestor.getAllMethods())) {
                    if (usage.getDeclaration().accessSpecifier() != AccessSpecifier.PRIVATE) {
                        methods.add(new Method(usage.getName(), Optional.of(usage)));
                    }
                }
            } catch (Exception | StackOverflowError e) {
                logger.warn("Methods of {} not resolved - {}", ancestor.getQualifiedName(), e.getMessage());
            }
        }
        return methods;
    }

    /** Adds the methods of a type visible within the test class (all of them if the type is the test class itself). */
    private static void addMethods(List<Method> methods, TypeDeclaration<?> type, boolean testClass, boolean samePackage) {
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getAnnotationByName("Test").isPresent()) {
                continue;
            }
            if (!testClass && !isInherited(method, type, samePackage)) {
                continue;
            }
            Optional<MethodUsage> usage;
            try {
                usage = Optional.of(new MethodUsage(method.resolve()));
            } catch (Exception | StackOverflowError e) {
                logger.warn("Method {} of {} not resolved: offered without its signature - {}", method.getNameAsString(),
                        type.getNameAsString(), e.getMessage());
                usage = Optional.empty();
            }
            methods.add(new Method(method.getNameAsString(), usage));
        }
    }

    /**
     * Whether a member of an ancestor is inherited by the test class: not private, and package-private only within the
     * same package (the members of an interface are public unless declared private).
     */
    private static boolean isInherited(NodeWithModifiers<?> member, TypeDeclaration<?> ancestor, boolean samePackage) {
        if (member.hasModifier(Modifier.Keyword.PRIVATE)) {
            return false;
        }
        boolean isInterface = ancestor instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
        if (isInterface || member.hasModifier(Modifier.Keyword.PUBLIC) || member.hasModifier(Modifier.Keyword.PROTECTED)) {
            return true;
        }
        return samePackage;
    }

    /**
     * The ancestors of a type.
     *
     * @param declared the ancestors with a declaration (the classes of the project and of the JDK sources), the
     *                 closest first, each once (an interface implemented twice, {@code Object})
     * @param withoutDeclaration the ancestors resolved without a declaration (the classes of the libraries): their own
     *                           ancestors are not visited
     */
    private record Ancestors(List<TypeDeclaration<?>> declared, List<ResolvedReferenceTypeDeclaration> withoutDeclaration) {}

    /** The ancestors of a type; the ancestors that cannot be resolved are skipped with their own ancestors. */
    private static Ancestors ancestors(TypeDeclaration<?> type) {
        List<TypeDeclaration<?>> declared = new ArrayList<>();
        List<ResolvedReferenceTypeDeclaration> withoutDeclaration = new ArrayList<>();
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> visitedWithoutDeclaration = new HashSet<>();
        visited.add(type);
        List<TypeDeclaration<?>> toVisit = new ArrayList<>(List.of(type));
        for (int i = 0; i < toVisit.size(); i++) {
            for (ResolvedReferenceTypeDeclaration ancestor : directAncestors(toVisit.get(i))) {
                Optional<TypeDeclaration<?>> ast = ancestor.toAst()
                        .filter(TypeDeclaration.class::isInstance)
                        .map(node -> (TypeDeclaration<?>) node);
                if (ast.isPresent()) {
                    if (visited.add(ast.get())) {
                        declared.add(ast.get());
                        toVisit.add(ast.get());
                    }
                } else if (visitedWithoutDeclaration.add(ancestor.getQualifiedName())) {
                    withoutDeclaration.add(ancestor);
                }
            }
        }
        return new Ancestors(declared, withoutDeclaration);
    }

    private static List<ResolvedReferenceTypeDeclaration> directAncestors(TypeDeclaration<?> type) {
        List<ResolvedReferenceTypeDeclaration> ancestors = new ArrayList<>();
        try {
            // Accept an incomplete list: the ancestors that cannot be resolved are skipped
            for (ResolvedReferenceType ancestor : type.resolve().getAncestors(true)) {
                ancestor.getTypeDeclaration().ifPresent(ancestors::add);
            }
        } catch (Exception | StackOverflowError e) {
            logger.warn("Ancestors of {} not resolved - {}", type.getNameAsString(), e.getMessage());
        }
        return ancestors;
    }

    private static String packageOf(TypeDeclaration<?> type) {
        return type.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");
    }
}
