package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The members imported statically by a test class (Step 25): the static methods, the static fields and the enum
 * constants named by its {@code import static} declarations (one member, or all of them with a wildcard), visible from
 * the test class following the access rules of Java (public, or without the {@code private} modifier when declared in
 * the package of the test class; the static methods of an interface are not inherited by the types implementing it).
 *
 * <p>Only the classes with a declaration are read (the classes of the project, the JDK sources and, since Step 30, the
 * sources of Hamcrest, part of the assertion API known by the module): the static imports of the other libraries
 * (JUnit, Mockito) are not offered, as the other classes of the dependencies. The members
 * come in the order of the imports, the methods of an import in the fixed order of {@link MemberOrder}.</p>
 */
final class StaticImports {

    private static final Logger logger = LoggerFactory.getLogger(StaticImports.class);

    /**
     * The members imported statically.
     *
     * @param methods the static methods (all the overloads of a name)
     * @param fields the variables of the static fields
     * @param enumConstants the enum constants
     */
    record Members(List<MethodUsage> methods, List<VariableDeclarator> fields, List<EnumConstantDeclaration> enumConstants) {}

    private StaticImports() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * The members imported statically by a compilation unit.
     *
     * @param cu the compilation unit of the test class
     * @return the members imported statically, visible from the package of the compilation unit
     */
    static Members of(CompilationUnit cu) {
        String testPackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Members members = new Members(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (ImportDeclaration importDeclaration : cu.getImports()) {
            if (!importDeclaration.isStatic()) {
                continue;
            }
            String typeName;
            Predicate<String> named;
            if (importDeclaration.isAsterisk()) {
                typeName = importDeclaration.getNameAsString();
                named = name -> true;
            } else {
                Optional<String> qualifier = importDeclaration.getName().getQualifier().map(q -> q.asString());
                if (qualifier.isEmpty()) {
                    continue;
                }
                typeName = qualifier.get();
                String memberName = importDeclaration.getName().getIdentifier();
                named = memberName::equals;
            }
            Optional<ResolvedReferenceTypeDeclaration> type = JavaParserUtils.solveType(typeName);
            Optional<TypeDeclaration<?>> ast = type.flatMap(t -> t.toAst())
                    .filter(TypeDeclaration.class::isInstance)
                    .map(node -> (TypeDeclaration<?>) node);
            if (ast.isEmpty()) {
                // A class of a library (no declaration), or a type that cannot be resolved
                logger.debug("Static import of {} skipped: no declaration", typeName);
                continue;
            }
            addMethods(members.methods(), type.get(), named, testPackage);
            addFields(members.fields(), type.get(), named, testPackage);
            if (ast.get() instanceof EnumDeclaration enumDeclaration) {
                for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                    if (named.test(constant.getNameAsString())) {
                        members.enumConstants().add(constant);
                    }
                }
            }
        }
        return members;
    }

    /** Adds the static methods of a type (declared or inherited) with an imported name, visible from the test class. */
    private static void addMethods(List<MethodUsage> methods, ResolvedReferenceTypeDeclaration type, Predicate<String> named,
                                   String testPackage) {
        List<MethodUsage> candidates = new ArrayList<>();
        try {
            candidates.addAll(MemberOrder.methodUsages(type.getAllMethods()));
        } catch (Exception | StackOverflowError e) {
            // A signature of an ancestor cannot be resolved: the methods declared in the type, one by one
            logger.warn("Methods of {} not resolved as a whole, reading its declared methods - {}", type.getQualifiedName(), e.getMessage());
            try {
                for (ResolvedMethodDeclaration declaration : MemberOrder.methodDeclarations(type.getDeclaredMethods())) {
                    try {
                        candidates.add(new MethodUsage(declaration));
                    } catch (Exception | StackOverflowError methodError) {
                        logger.warn("Method {} of {} not resolved - {}", declaration.getName(), type.getQualifiedName(), methodError.getMessage());
                    }
                }
            } catch (Exception | StackOverflowError declaredError) {
                logger.warn("Declared methods of {} not resolved - {}", type.getQualifiedName(), declaredError.getMessage());
            }
        }
        for (MethodUsage usage : candidates) {
            ResolvedMethodDeclaration declaration = usage.getDeclaration();
            if (!named.test(usage.getName()) || !declaration.isStatic()) {
                continue;
            }
            ResolvedReferenceTypeDeclaration declaringType = declaration.declaringType();
            boolean declaredInInterface = declaringType.isInterface();
            // The static methods of an interface are not inherited (JLS 8.4.8)
            if (declaredInInterface && !declaringType.getQualifiedName().equals(type.getQualifiedName())) {
                continue;
            }
            boolean visible = switch (declaration.accessSpecifier()) {
                case PUBLIC -> true;
                case PRIVATE -> false;
                case NONE -> declaredInInterface || declaringType.getPackageName().equals(testPackage);
                default -> declaringType.getPackageName().equals(testPackage);
            };
            if (visible && methods.stream().noneMatch(usage::equals)) {
                methods.add(usage);
            }
        }
    }

    /** Adds the static fields of a type (declared or inherited, with a declaration) with an imported name, visible from the test class. */
    private static void addFields(List<VariableDeclarator> fields, ResolvedReferenceTypeDeclaration type, Predicate<String> named,
                                  String testPackage) {
        List<Node> declarations = new ArrayList<>();
        try {
            type.getAllFields().forEach(field -> field.toAst().ifPresent(declarations::add));
        } catch (Exception | StackOverflowError e) {
            logger.warn("Fields of {} not resolved as a whole, reading its declared fields - {}", type.getQualifiedName(), e.getMessage());
            type.toAst().filter(TypeDeclaration.class::isInstance)
                    .ifPresent(ast -> declarations.addAll(((TypeDeclaration<?>) ast).getFields()));
        }
        for (Node node : declarations) {
            if (!(node instanceof FieldDeclaration field)) {
                // The enum constants are collected apart
                continue;
            }
            Optional<TypeDeclaration> declaringType = field.findAncestor(TypeDeclaration.class);
            boolean declaredInInterface = declaringType.filter(ClassOrInterfaceDeclaration.class::isInstance)
                    .map(t -> ((ClassOrInterfaceDeclaration) t).isInterface())
                    .orElse(false);
            // The fields of an interface are public and static
            if (!declaredInInterface && !field.isStatic()) {
                continue;
            }
            boolean visible = declaredInInterface || field.hasModifier(Modifier.Keyword.PUBLIC)
                    || !field.hasModifier(Modifier.Keyword.PRIVATE)
                        && declaringType.map(StaticImports::packageOf).orElse("").equals(testPackage);
            if (!visible) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (named.test(variable.getNameAsString()) && fields.stream().noneMatch(v -> v == variable)) {
                    fields.add(variable);
                }
            }
        }
    }

    private static String packageOf(TypeDeclaration<?> type) {
        return type.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");
    }
}
