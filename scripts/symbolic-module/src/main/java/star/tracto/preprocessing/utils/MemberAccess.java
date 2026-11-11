package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import java.util.Optional;

/**
 * Which members of a type the test class can access, as in Java (Step 37): the members offered after a value
 * ({@code x.}) and after the name of a class ({@code X.}).
 * <ul>
 *     <li>a public member always;</li>
 *     <li>a member that is not private when the class that declares it is in the package of the test class;</li>
 *     <li>any member of a class nested in the test class (the private ones too: same top-level class);</li>
 *     <li>the members of an interface or of an annotation are public, the fields of an interface are static;</li>
 *     <li>after the name of a class, the static members only.</li>
 * </ul>
 * A protected member of a class of another package is not offered: it is accessible from a subclass, through a value of
 * the subclass only (the members of the test class and of its superclasses are collected apart, Step 22).
 *
 * Before, the members were filtered with lists of rules on their modifiers combined with OR: after a value, "public" OR
 * "not private" OR "not protected" (a private member is not protected: {@code String.value}, {@code Object.wait0});
 * after the name of a class, "static and public" OR "not private" (any instance method: {@code String.length},
 * {@code Object.clone}). The package was compared with {@code String.contains} on the qualified name of the type of the
 * value without its simple name (a subpackage was the same package; a nested type was in no package).
 *
 * @param testPackage the package of the test class ({@code ""} for the default package)
 * @param testClass the qualified name of the test class
 * @param staticOnly whether only the static members are accessible (after the name of a class)
 */
public record MemberAccess(String testPackage, String testClass, boolean staticOnly) {

    /** No constraint (the members of the test class itself and of its superclasses, collected apart). */
    public static final MemberAccess ANY = new MemberAccess(null, null, false);

    /** The access to the members of a value from a test class. */
    public static MemberAccess ofValue(TypeDeclaration<?> testClass) {
        return new MemberAccess(packageOf(testClass), qualifiedName(testClass), false);
    }

    /** The access to the members of a class, by its name, from a test class. */
    public static MemberAccess ofClassName(TypeDeclaration<?> testClass) {
        return new MemberAccess(packageOf(testClass), qualifiedName(testClass), true);
    }

    /** Whether a method can be accessed. */
    public boolean allows(ResolvedMethodDeclaration method) {
        if (this == ANY) {
            return true;
        }
        ResolvedReferenceTypeDeclaration declaringType;
        try {
            declaringType = method.declaringType();
        } catch (RuntimeException e) {
            // Not known: only if public
            return (!staticOnly || isStatic(method)) && method.accessSpecifier() == AccessSpecifier.PUBLIC;
        }
        AccessSpecifier access = method.accessSpecifier();
        if (access == AccessSpecifier.NONE && (declaringType.isInterface() || declaringType.isAnnotation())) {
            access = AccessSpecifier.PUBLIC;
        }
        return (!staticOnly || isStatic(method))
                && allows(access, declaringType.getPackageName(), declaringType.getQualifiedName());
    }

    /** Whether the variables of a field declaration can be accessed. */
    public boolean allows(FieldDeclaration field) {
        if (this == ANY) {
            return true;
        }
        Optional<TypeDeclaration> declaringType = field.findAncestor(TypeDeclaration.class);
        boolean interfaceMember = declaringType.isPresent() && isInterfaceOrAnnotation(declaringType.get());
        boolean isStatic = interfaceMember || field.hasModifier(Modifier.Keyword.STATIC);
        AccessSpecifier access = interfaceMember ? AccessSpecifier.PUBLIC : field.getAccessSpecifier();
        return (!staticOnly || isStatic) && allows(
                access,
                declaringType.map(MemberAccess::packageOf).orElse(""),
                declaringType.map(MemberAccess::qualifiedName).orElse(""));
    }

    private boolean allows(AccessSpecifier access, String declaringPackage, String declaringType) {
        if (access == AccessSpecifier.PUBLIC || isNestedInTestClass(declaringType)) {
            return true;
        }
        return access != AccessSpecifier.PRIVATE && declaringPackage.equals(testPackage);
    }

    private boolean isNestedInTestClass(String declaringType) {
        return testClass != null && !testClass.isEmpty()
                && (declaringType.equals(testClass) || declaringType.startsWith(testClass + "."));
    }

    private static boolean isStatic(ResolvedMethodDeclaration method) {
        try {
            return method.isStatic();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isInterfaceOrAnnotation(TypeDeclaration<?> type) {
        return type instanceof AnnotationDeclaration
                || (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface());
    }

    private static String packageOf(TypeDeclaration<?> type) {
        return type.findCompilationUnit()
                .flatMap(cu -> cu.getPackageDeclaration())
                .map(p -> p.getNameAsString())
                .orElse("");
    }

    private static String qualifiedName(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElse(type.getNameAsString());
    }
}
