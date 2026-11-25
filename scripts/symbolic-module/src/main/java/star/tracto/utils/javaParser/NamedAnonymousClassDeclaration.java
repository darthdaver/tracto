package star.tracto.utils.javaParser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnonymousClassDeclaration;

import java.util.Optional;

/**
 * The declaration of an anonymous class with the same name at each run (Steps 52-54). JavaParser names an anonymous class
 * {@code Anonymous-<UUID.randomUUID()>}, a new name for each instance of its declaration; this one is named after its
 * supertype, {@code Anonymous-<the name of the supertype>} (see {@link #nameOf}), and its qualified name is the one of
 * JavaParser built on it (the container, then the name). Used as the type of a variable declared with {@code var} whose
 * initializer creates an anonymous class (Step 54), whose members are then the ones declared in the body and the
 * inherited ones.
 */
public class NamedAnonymousClassDeclaration extends JavaParserAnonymousClassDeclaration {

    private final ObjectCreationExpr creation;
    private String name;

    public NamedAnonymousClassDeclaration(ObjectCreationExpr creation, TypeSolver typeSolver) {
        super(creation, typeSolver);
        this.creation = creation;
    }

    @Override
    public String getName() {
        if (name == null) {
            name = nameOf(creation, getSuperTypeDeclaration());
        }
        return name;
    }

    /**
     * The name of an anonymous class: {@code Anonymous-} and the name of its supertype as Java accepts it in the source:
     * its canonical name (dots, not the {@code $} of the binary name; without type arguments), or, for a local type or
     * one nested in a local class, which have no canonical name, the name written in the creation (JavaParser's
     * qualified name of a local type, {@code com.acme.T.Local}, is not valid Java). Step 53.
     */
    public static String nameOf(ObjectCreationExpr creation, ResolvedTypeDeclaration supertype) {
        Optional<Node> declaration = supertype.toAst();
        if (declaration.isPresent() && declaration.get() instanceof TypeDeclaration<?> typeDeclaration) {
            return "Anonymous-" + typeDeclaration.getFullyQualifiedName().orElseGet(() -> creation.getType().getNameWithScope());
        }
        return "Anonymous-" + supertype.getQualifiedName();
    }
}
