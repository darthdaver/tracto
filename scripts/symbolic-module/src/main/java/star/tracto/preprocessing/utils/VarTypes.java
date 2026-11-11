package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The type of a local variable declared with {@code var} whose initializer the symbol solver cannot type as a whole
 * (Step 34). The symbol solver resolves every part of the initializer, and fails when one part needs a type it cannot
 * resolve: an argument of a type of a dependency (the dependencies are not given to it, step 8), an unqualified call it
 * also looks for in the static imports of the dependencies. The variable was recorded as unsolvable, and the class names
 * of the root were offered after it.
 *
 * <p>Only the forms whose type does not depend on what cannot be resolved: {@code new T(...)}, {@code (T) e}, a call
 * {@code m(...)} or {@code e.m(...)} where all the methods named {@code m} with as many parameters as arguments (found in
 * the type of {@code e}; for an unqualified call in the enclosing types, then in the static imports) return the same
 * type, which is not a type variable. Otherwise nothing: the variable stays unsolvable, as before.</p>
 */
final class VarTypes {

    private static final Logger logger = LoggerFactory.getLogger(VarTypes.class);

    /** Bound on the nesting of the initializers followed (chains of calls, variables declared with other variables). */
    private static final int MAX_DEPTH = 16;

    private VarTypes() {
    }

    /**
     * The type of a variable declared with {@code var}, from its initializer, when the symbol solver cannot type it.
     *
     * @param variable a variable declared with {@code var}
     * @return its type, or empty if it cannot be determined without ambiguity
     */
    static Optional<ResolvedType> fromInitializer(VariableDeclarator variable) {
        Optional<ResolvedType> type = variable.getInitializer().flatMap(initializer -> typeOf(initializer, 0));
        type.ifPresent(t -> logger.info("Type of the variable " + variable.getNameAsString() + " taken from its initializer: " + t.describe()));
        return type;
    }

    private static Optional<ResolvedType> typeOf(Expression expression, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(expression.calculateResolvedType());
        } catch (Exception | StackOverflowError e) {
            // Typed below, from its parts
        }
        try {
            if (expression.isEnclosedExpr()) {
                EnclosedExpr enclosed = expression.asEnclosedExpr();
                return typeOf(enclosed.getInner(), depth + 1);
            }
            if (expression.isCastExpr()) {
                CastExpr cast = expression.asCastExpr();
                return Optional.of(cast.getType().resolve());
            }
            if (expression.isObjectCreationExpr()) {
                ObjectCreationExpr creation = expression.asObjectCreationExpr();
                return Optional.of(creation.getType().resolve());
            }
            if (expression.isNameExpr()) {
                return typeOfName(expression.asNameExpr(), depth);
            }
            if (expression.isMethodCallExpr()) {
                return typeOfCall(expression.asMethodCallExpr(), depth);
            }
        } catch (Exception | StackOverflowError e) {
            logger.info("Type of " + expression + " not determined from its parts - " + e.getMessage());
        }
        return Optional.empty();
    }

    /** A variable (another {@code var} whose initializer is typed in turn) or the name of a class (a static call). */
    private static Optional<ResolvedType> typeOfName(NameExpr name, int depth) {
        try {
            ResolvedValueDeclaration value = name.resolve();
            // A local variable: its declaration expression (all its declarators), or its declarator
            Optional<VariableDeclarator> variable = value.toAst().flatMap(node -> {
                if (node instanceof VariableDeclarator declarator) {
                    return Optional.of(declarator);
                }
                if (node instanceof VariableDeclarationExpr declarationExpr) {
                    return declarationExpr.getVariables().stream()
                            .filter(declarator -> declarator.getNameAsString().equals(name.getNameAsString()))
                            .findFirst();
                }
                return Optional.empty();
            });
            if (variable.isPresent() && variable.get().getType().isVarType() && variable.get().getInitializer().isPresent()) {
                return typeOf(variable.get().getInitializer().get(), depth + 1);
            }
            return Optional.empty();
        } catch (Exception | StackOverflowError e) {
            // Not a value: the name of a class
        }
        return solveTypeName(name, name.getNameAsString())
                .map(declaration -> new ReferenceTypeImpl(declaration));
    }

    private static Optional<ResolvedType> typeOfCall(MethodCallExpr call, int depth) {
        String name = call.getNameAsString();
        int arguments = call.getArguments().size();
        if (call.getScope().isPresent()) {
            Optional<ResolvedType> scopeType = typeOf(call.getScope().get(), depth + 1);
            if (scopeType.isEmpty() || !scopeType.get().isReferenceType() || scopeType.get().asReferenceType().getTypeDeclaration().isEmpty()) {
                return Optional.empty();
            }
            List<ResolvedMethodDeclaration> candidates = candidates(scopeType.get().asReferenceType().getTypeDeclaration().get(), name, arguments);
            return returnType(candidates, Optional.of(scopeType.get()));
        }
        // An unqualified call: the enclosing types, from the innermost, then the static imports (a single import of the
        // name first, then the imports on demand), the first ones declaring the name deciding
        Optional<Node> parent = call.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> typeDeclaration) {
                List<ResolvedMethodDeclaration> candidates = candidates(typeDeclaration.resolve(), name, arguments);
                if (!candidates.isEmpty()) {
                    return returnType(candidates, Optional.empty());
                }
            }
            parent = parent.get().getParentNode();
        }
        Optional<CompilationUnit> cu = call.findCompilationUnit();
        if (cu.isEmpty()) {
            return Optional.empty();
        }
        for (boolean onDemand : new boolean[]{false, true}) {
            List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
            for (ImportDeclaration importDeclaration : cu.get().getImports()) {
                if (!importDeclaration.isStatic() || importDeclaration.isAsterisk() != onDemand) {
                    continue;
                }
                String importedName = importDeclaration.getNameAsString();
                String typeName = onDemand ? importedName : importedName.substring(0, Math.max(importedName.lastIndexOf('.'), 0));
                if (!onDemand && !importedName.endsWith("." + name)) {
                    continue;
                }
                // A type that cannot be resolved (a dependency) is left out
                JavaParserUtils.solveType(typeName).ifPresent(type -> candidates.addAll(candidates(type, name, arguments).stream()
                        .filter(ResolvedMethodDeclaration::isStatic).toList()));
            }
            if (!candidates.isEmpty()) {
                return returnType(candidates, Optional.empty());
            }
        }
        return Optional.empty();
    }

    /**
     * The methods of a type and of its ancestors with this name, applicable to this number of arguments: the declared
     * methods, read without their parameter types (a method whose parameters cannot be resolved is a candidate too: it
     * must not be taken for absent).
     */
    private static List<ResolvedMethodDeclaration> candidates(ResolvedReferenceTypeDeclaration type, String name, int arguments) {
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        collectCandidates(type, name, arguments, candidates, new HashSet<>());
        return candidates;
    }

    private static void collectCandidates(
            ResolvedReferenceTypeDeclaration type,
            String name,
            int arguments,
            List<ResolvedMethodDeclaration> candidates,
            Set<String> visitedTypes
    ) {
        if (!visitedTypes.add(type.getQualifiedName())) {
            return;
        }
        for (ResolvedMethodDeclaration declaration : type.getDeclaredMethods()) {
            if (!declaration.getName().equals(name)) {
                continue;
            }
            int parameters = declaration.getNumberOfParams();
            boolean variadic = parameters > 0 && declaration.getLastParam().isVariadic();
            if (parameters == arguments || (variadic && arguments >= parameters - 1)) {
                candidates.add(declaration);
            }
        }
        // The ancestors that cannot be resolved are left out: a method declared there is not seen (as by the symbol
        // solver, Step 33)
        for (ResolvedReferenceType ancestor : type.getAncestors(true)) {
            ancestor.getTypeDeclaration().ifPresent(declaration -> collectCandidates(declaration, name, arguments, candidates, visitedTypes));
        }
    }

    /**
     * The return type shared by the candidates, with the type parameters of the type of the scope replaced by their
     * values; empty if one cannot be resolved, if they differ, if it is {@code void} or a type variable (a type parameter
     * of the method, inferred from the arguments).
     */
    private static Optional<ResolvedType> returnType(List<ResolvedMethodDeclaration> candidates, Optional<ResolvedType> scopeType) {
        ResolvedType shared = null;
        for (ResolvedMethodDeclaration candidate : candidates) {
            ResolvedType returnType = candidate.getReturnType();
            if (scopeType.isPresent()) {
                returnType = scopeType.get().asReferenceType().useThisTypeParametersOnTheGivenType(returnType);
            }
            if (returnType.isVoid() || returnType.isTypeVariable() || returnType.isWildcard()) {
                return Optional.empty();
            }
            if (shared == null) {
                shared = returnType;
            } else if (!shared.describe().equals(returnType.describe())) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(shared);
    }

    private static Optional<ResolvedReferenceTypeDeclaration> solveTypeName(Node node, String name) {
        return JavaParserUtils.getRepoTypeSolver().flatMap(typeSolver -> {
            try {
                SymbolReference<ResolvedTypeDeclaration> reference = JavaParserFactory.getContext(node, typeSolver).solveType(name);
                if (reference.isSolved() && reference.getCorrespondingDeclaration().isReferenceType()) {
                    return Optional.of(reference.getCorrespondingDeclaration().asReferenceType());
                }
            } catch (Exception | StackOverflowError e) {
                // Not resolved
            }
            return Optional.empty();
        });
    }
}
