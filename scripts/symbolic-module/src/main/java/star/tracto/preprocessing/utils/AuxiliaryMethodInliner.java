package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.CallableExprType;
import star.tracto.utils.javaParser.JavaParserUtils;
import star.tracto.utils.javaParser.NameAllocator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Inlines a call to an auxiliary method of a test class (a method that is neither a test case nor a set-up or
 * tear-down method), invoked as a statement of a test case. The call is replaced with the body of a copy of the method,
 * adapted to the test case:
 * <ul>
 *     <li>each parameter is replaced with the variable passed as argument when this preserves the semantics of the
 *     call; otherwise it is bound to a new local variable, named after the parameter and initialized with the argument
 *     (the arguments of a varargs parameter are collected in an array);</li>
 *     <li>the variables declared within the method are renamed if their names are in use within the test case
 *     ({@link NameAllocator}), and a field used by the method that a variable of the test case would hide is
 *     qualified ({@code this.base});</li>
 *     <li>a return statement at the end of the method is replaced with the evaluation of the returned value, if it can
 *     have side effects; with early return statements, the body is inlined as a labeled block, and the early returns
 *     leave the block ({@code break <label>;}).</li>
 * </ul>
 * The statements of the copy still have to be normalized like the statements of the test case.
 */
final class AuxiliaryMethodInliner {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(AuxiliaryMethodInliner.class);

    /* Do not instantiate this class. */
    private AuxiliaryMethodInliner() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * The statements replacing a call to an auxiliary method.
     *
     * @param auxiliaryMethod the auxiliary method, as declared in the test class
     * @param bindings the declarations of the local variables bound to the parameters of the method, to add before
     *                 the body
     * @param body the body of a copy of the method, adapted to the test case. The copy is within a copy of the
     *             compilation unit of the test class, so the symbols used within it can still be resolved
     * @param label the label of the block containing the body, if the method has early return statements
     */
    record InlinedCall(MethodDeclaration auxiliaryMethod, List<Statement> bindings, BlockStmt body, Optional<String> label) {}

    /**
     * Finds the auxiliary method invoked by a method call.
     *
     * @param call the method call
     * @param testClass the test class where the auxiliary methods are declared
     * @param auxiliaryMethods the auxiliary methods of the test class, by signature
     * @return the auxiliary method invoked, or an empty optional if the call does not invoke an auxiliary method
     */
    static Optional<MethodDeclaration> findAuxiliaryMethod(MethodCallExpr call, TypeDeclaration<?> testClass, Map<String, MethodDeclaration> auxiliaryMethods) {
        String methodSignature = "";
        try {
            Optional<Node> resolvedMethod = call.resolve().toAst();
            if (resolvedMethod.isPresent() && resolvedMethod.get() instanceof MethodDeclaration methodDeclaration) {
                methodSignature = methodDeclaration.getSignature().asString();
            }
        } catch (UnsolvedSymbolException e) {
            CallableDeclaration<?> callableDeclaration = JavaParserUtils.searchCandidateCallableDeclaration(testClass, call, CallableExprType.METHOD);
            if (callableDeclaration != null) {
                methodSignature = callableDeclaration.getSignature().asString();
            }
        }
        return Optional.ofNullable(auxiliaryMethods.get(methodSignature));
    }

    /**
     * Inlines a call to an auxiliary method.
     *
     * @param call the method call, invoked as a statement of the test case
     * @param auxiliaryMethod the auxiliary method invoked
     * @param testClass the test class where the auxiliary method is declared
     * @param variableNames the names of the variables in use within the test case. The names of the variables
     *                      declared by the inlined statements are added
     * @param labelNames the labels in use within the test case. The labels of the inlined statements are added
     * @param existingVariables the variables of the test case: the variables declared within the copy of the method
     *                          are added
     * @return the statements replacing the call, or an empty optional if the call cannot be inlined
     */
    static Optional<InlinedCall> inline(
            MethodCallExpr call,
            MethodDeclaration auxiliaryMethod,
            TypeDeclaration<?> testClass,
            NameAllocator variableNames,
            NameAllocator labelNames,
            Set<VariableDeclarator> existingVariables
    ) {
        String methodName = auxiliaryMethod.getNameAsString();
        if (auxiliaryMethod.getBody().isEmpty()) {
            logger.info("Auxiliary method {} not inlined: it has no body.", methodName);
            return Optional.empty();
        }
        if (auxiliaryMethod.getTypeParameters().isNonEmpty()) {
            logger.info("Auxiliary method {} not inlined: type parameters are not supported.", methodName);
            return Optional.empty();
        }
        NodeList<Expression> arguments = call.getArguments();
        NodeList<Parameter> parameters = auxiliaryMethod.getParameters();
        boolean varArgs = parameters.getLast().map(Parameter::isVarArgs).orElse(false);
        int fixedParameters = varArgs ? parameters.size() - 1 : parameters.size();
        if (varArgs ? arguments.size() < fixedParameters : arguments.size() != parameters.size()) {
            logger.error("Auxiliary method {} not inlined: parameter mismatch between the method call and the method.", methodName);
            return Optional.empty();
        }
        // An array passed to a varargs parameter is the value of the parameter; otherwise, the arguments of the
        // varargs parameter are collected in a new array
        boolean collectVarArgs = varArgs;
        if (varArgs && arguments.size() == parameters.size()) {
            Optional<Boolean> isArray = isArray(arguments.getLast().orElseThrow());
            if (isArray.isEmpty()) {
                logger.info("Auxiliary method {} not inlined: the type of the argument of its varargs parameter cannot be resolved.", methodName);
                return Optional.empty();
            }
            collectVarArgs = !isArray.get();
        }
        Type varArgsType = parameters.getLast().map(Parameter::getType).orElse(null);
        if (collectVarArgs && varArgsType.isClassOrInterfaceType() && varArgsType.asClassOrInterfaceType().getTypeArguments().isPresent()) {
            logger.info("Auxiliary method {} not inlined: an array of the generic type {} cannot be created.", methodName, varArgsType);
            return Optional.empty();
        }
        // Adapt a copy of the method to the test case
        MethodDeclaration copy = copyMethodWithinCompilationUnit(testClass, auxiliaryMethod.getSignature().asString());
        BlockStmt body = copy.getBody().orElseThrow();
        reserveNamesUsedButNotDeclared(copy, variableNames);
        qualifyFieldsHiddenByTheTestCase(copy, variableNames);
        renameVariables(body, variableNames, existingVariables);
        renameLabels(body, labelNames);
        List<Statement> bindings = bindParameters(copy, arguments, collectVarArgs, variableNames);
        Optional<String> label = replaceReturns(copy, variableNames, labelNames);
        return Optional.of(new InlinedCall(auxiliaryMethod, bindings, body, label));
    }

    /**
     * Reserves the names used by a method that it does not declare (fields, constants, ...): the names given to the
     * variables declared within the method must not hide them.
     */
    private static void reserveNamesUsedButNotDeclared(MethodDeclaration method, NameAllocator variableNames) {
        Set<String> declaredNames = new HashSet<>();
        method.findAll(Parameter.class).forEach(parameter -> declaredNames.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).forEach(variable -> declaredNames.add(variable.getNameAsString()));
        method.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .filter(name -> !declaredNames.contains(name))
                .forEach(variableNames::reserve);
    }

    /**
     * Qualifies the fields used by a method ({@code base} becomes {@code this.base}, or {@code Type.base} for a static
     * field) whose names are declared by the test case: once inlined, the variables of the test case would hide them.
     * The fields used within a nested class are not qualified ({@code this} would refer to the nested class).
     */
    private static void qualifyFieldsHiddenByTheTestCase(MethodDeclaration method, NameAllocator variableNames) {
        for (NameExpr nameExpr : method.getBody().orElseThrow().findAll(NameExpr.class)) {
            if (!variableNames.isDeclared(nameExpr.getNameAsString()) || isWithinANestedClass(nameExpr, method)) {
                continue;
            }
            ResolvedValueDeclaration declaration;
            try {
                declaration = nameExpr.resolve();
            } catch (RuntimeException e) {
                logger.warn("Name {} not resolved: it is not qualified.", nameExpr);
                continue;
            }
            if (declaration.isField()) {
                Expression scope = declaration.asField().isStatic()
                        ? new NameExpr(declaration.asField().declaringType().getName())
                        : new ThisExpr();
                nameExpr.replace(new FieldAccessExpr(scope, nameExpr.getNameAsString()));
            }
        }
    }

    /**
     * Renames the variables declared within the body of a method (and the parameters of its lambda expressions and
     * catch clauses) whose names are in use within the test case. The variables are added to the existing variables.
     */
    private static void renameVariables(BlockStmt body, NameAllocator variableNames, Set<VariableDeclarator> existingVariables) {
        // The keys are compared by reference: distinct declarations with the same content are equal for JavaParser
        Map<VariableDeclarator, String> variablesToRename = new IdentityHashMap<>();
        for (VariableDeclarationExpr declaration : body.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator variable : declaration.getVariables()) {
                String name = variableNames.allocate(variable.getNameAsString());
                if (!name.equals(variable.getNameAsString())) {
                    variablesToRename.put(variable, name);
                }
                existingVariables.add(variable);
            }
        }
        Map<Parameter, String> parametersToRename = new IdentityHashMap<>();
        for (Parameter parameter : body.findAll(Parameter.class)) {
            String name = variableNames.allocate(parameter.getNameAsString());
            if (!name.equals(parameter.getNameAsString())) {
                parametersToRename.put(parameter, name);
            }
        }
        JavaParserUtils.replaceVariables(body, variablesToRename);
        JavaParserUtils.replaceParameters(body, parametersToRename);
    }

    /**
     * Renames the labels declared within the body of a method whose names are in use within the test case (a label
     * cannot be declared again within the statement it labels), together with the {@code break} and {@code continue}
     * statements referring to them.
     */
    private static void renameLabels(BlockStmt body, NameAllocator labelNames) {
        for (LabeledStmt labeledStmt : body.findAll(LabeledStmt.class)) {
            String label = labeledStmt.getLabel().asString();
            String newLabel = labelNames.allocate(label);
            if (newLabel.equals(label)) {
                continue;
            }
            labeledStmt.setLabel(new SimpleName(newLabel));
            labeledStmt.getStatement().findAll(BreakStmt.class, breakStmt -> hasLabel(breakStmt.getLabel(), label))
                    .forEach(breakStmt -> breakStmt.setLabel(new SimpleName(newLabel)));
            labeledStmt.getStatement().findAll(ContinueStmt.class, continueStmt -> hasLabel(continueStmt.getLabel(), label))
                    .forEach(continueStmt -> continueStmt.setLabel(new SimpleName(newLabel)));
        }
    }

    private static boolean hasLabel(Optional<SimpleName> statementLabel, String label) {
        return statementLabel.map(name -> name.asString().equals(label)).orElse(false);
    }

    /**
     * Replaces each parameter of a method with the variable passed as argument, or binds it to a new local variable
     * initialized with the argument ({@link #canBeReplacedWithTheArgument}). The local variable is named after the
     * parameter.
     *
     * @param method the copy of the auxiliary method
     * @param arguments the arguments of the call
     * @param collectVarArgs whether the arguments of the varargs parameter (the last one) are collected in a new array
     * @param variableNames the names of the variables in use within the test case
     * @return the declarations of the local variables bound to the parameters, in the order of the parameters
     */
    private static List<Statement> bindParameters(MethodDeclaration method, NodeList<Expression> arguments, boolean collectVarArgs, NameAllocator variableNames) {
        NodeList<Parameter> parameters = method.getParameters();
        int boundParameters = collectVarArgs ? parameters.size() - 1 : parameters.size();
        List<Statement> bindings = new ArrayList<>();
        Map<Parameter, String> parameterNames = new IdentityHashMap<>();
        for (int i = 0; i < boundParameters; i++) {
            Parameter parameter = parameters.get(i);
            Expression argument = arguments.get(i);
            if (canBeReplacedWithTheArgument(parameter, argument, arguments, method)) {
                parameterNames.put(parameter, argument.asNameExpr().getNameAsString());
            } else {
                // An array passed to a varargs parameter is bound to an array variable
                Type type = parameter.isVarArgs() ? new ArrayType(parameter.getType().clone()) : parameter.getType().clone();
                String name = variableNames.allocate(parameter.getNameAsString());
                bindings.add(declaration(type, name, argument.clone()));
                parameterNames.put(parameter, name);
            }
        }
        if (collectVarArgs) {
            Parameter parameter = parameters.getLast().orElseThrow();
            NodeList<Expression> values = new NodeList<>();
            arguments.stream().skip(boundParameters).forEach(argument -> values.add(argument.clone()));
            ArrayCreationExpr array = new ArrayCreationExpr(parameter.getType().clone(), new NodeList<>(new ArrayCreationLevel()), new ArrayInitializerExpr(values));
            String name = variableNames.allocate(parameter.getNameAsString());
            bindings.add(declaration(new ArrayType(parameter.getType().clone()), name, array));
            parameterNames.put(parameter, name);
        }
        JavaParserUtils.replaceParameters(method.getBody().orElseThrow(), parameterNames);
        return bindings;
    }

    private static Statement declaration(Type type, String name, Expression initializer) {
        return new ExpressionStmt(new VariableDeclarationExpr(new VariableDeclarator(type, name, initializer)));
    }

    /**
     * Checks if a parameter can be replaced with the argument within the body of the method, preserving the semantics
     * of the call. The argument must be a variable and the method must not assign the parameter (it would modify the
     * variable of the caller). A final field can always replace the parameter. A local variable of the caller can if
     * the method does not use the parameter within a lambda expression or a nested class (a captured variable must be
     * effectively final, the variable of the caller may not be), and no other argument modifies the variable (the
     * parameter takes its value when the call is evaluated). Any other field could be modified by the method.
     */
    private static boolean canBeReplacedWithTheArgument(Parameter parameter, Expression argument, NodeList<Expression> arguments, MethodDeclaration method) {
        String parameterName = parameter.getNameAsString();
        BlockStmt body = method.getBody().orElseThrow();
        if (!argument.isNameExpr() || isAssigned(parameterName, body)) {
            return false;
        }
        ResolvedValueDeclaration declaration;
        try {
            declaration = argument.asNameExpr().resolve();
        } catch (RuntimeException e) {
            return false;
        }
        if (declaration.isField()) {
            return isFinalField(declaration);
        }
        String argumentName = argument.asNameExpr().getNameAsString();
        return (declaration.isVariable() || declaration.isParameter())
                && body.findAll(NameExpr.class, nameExpr -> nameExpr.getNameAsString().equals(parameterName)).stream()
                        .noneMatch(nameExpr -> isWithinALambdaOrANestedClass(nameExpr, method))
                && arguments.stream().noneMatch(other -> isAssigned(argumentName, other));
    }

    /**
     * Checks if a field is declared final (or is a constant of an interface). A field declared out of the source code
     * of the project is not considered final.
     */
    private static boolean isFinalField(ResolvedValueDeclaration field) {
        Optional<FieldDeclaration> fieldDeclaration = field.toAst().flatMap(node -> node instanceof FieldDeclaration declaration
                ? Optional.of(declaration)
                : node.getParentNode().filter(FieldDeclaration.class::isInstance).map(FieldDeclaration.class::cast));
        return fieldDeclaration.map(declaration -> declaration.isFinal() || declaration.getParentNode()
                        .map(parent -> parent instanceof ClassOrInterfaceDeclaration type && type.isInterface())
                        .orElse(false))
                .orElse(false);
    }

    /**
     * Checks if a variable is assigned (or incremented, or decremented) within a node.
     */
    private static boolean isAssigned(String name, Node node) {
        return node.findFirst(AssignExpr.class, assignExpr -> isNameExpr(assignExpr.getTarget(), name)).isPresent()
                || node.findFirst(UnaryExpr.class, unaryExpr -> isIncrementOrDecrement(unaryExpr) && isNameExpr(unaryExpr.getExpression(), name)).isPresent();
    }

    private static boolean isNameExpr(Expression expr, String name) {
        return expr.isNameExpr() && expr.asNameExpr().getNameAsString().equals(name);
    }

    private static boolean isIncrementOrDecrement(UnaryExpr unaryExpr) {
        UnaryExpr.Operator operator = unaryExpr.getOperator();
        return operator == UnaryExpr.Operator.PREFIX_INCREMENT || operator == UnaryExpr.Operator.PREFIX_DECREMENT
                || operator == UnaryExpr.Operator.POSTFIX_INCREMENT || operator == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    private static boolean isWithinALambdaOrANestedClass(Node node, MethodDeclaration method) {
        for (Optional<Node> parent = node.getParentNode(); parent.isPresent() && parent.get() != method; parent = parent.get().getParentNode()) {
            // The members of a nested class (anonymous or local) are body declarations
            if (parent.get() instanceof LambdaExpr || parent.get() instanceof BodyDeclaration<?>) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithinANestedClass(Node node, MethodDeclaration method) {
        for (Optional<Node> parent = node.getParentNode(); parent.isPresent() && parent.get() != method; parent = parent.get().getParentNode()) {
            if (parent.get() instanceof BodyDeclaration<?>) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the type of an expression is an array.
     *
     * @return an empty optional if the type cannot be resolved
     */
    private static Optional<Boolean> isArray(Expression expr) {
        // A null argument of a varargs parameter is the array itself
        if (expr.isNullLiteralExpr()) {
            return Optional.of(true);
        }
        try {
            return Optional.of(expr.calculateResolvedType().isArray());
        } catch (RuntimeException e) {
            // The type of a variable cannot be resolved if its declaration was already moved to the normalized test
            // case, out of the compilation unit: its declared type tells if it is an array
            return expr.isNameExpr() ? isDeclaredAsArray(expr.asNameExpr()) : Optional.empty();
        }
    }

    private static Optional<Boolean> isDeclaredAsArray(NameExpr nameExpr) {
        Optional<Node> declaration;
        try {
            declaration = nameExpr.resolve().toAst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Optional<Type> declaredType = declaration.flatMap(node -> {
            if (node instanceof Parameter parameter) {
                return Optional.of(parameter.isVarArgs() ? new ArrayType(parameter.getType().clone()) : parameter.getType());
            } else if (node instanceof VariableDeclarator variable) {
                return Optional.of(variable.getType());
            } else if (node instanceof VariableDeclarationExpr declarationExpr) {
                return declarationExpr.getVariables().stream()
                        .filter(variable -> variable.getNameAsString().equals(nameExpr.getNameAsString()))
                        .findFirst()
                        .map(VariableDeclarator::getType);
            }
            return Optional.empty();
        });
        // The type of a variable declared with `var` is not written
        return declaredType.filter(type -> !type.isVarType()).map(Type::isArrayType);
    }

    /**
     * Replaces the return statements of a method. The value returned is discarded (only the auxiliary methods invoked
     * as statements are inlined), so each return statement is replaced with the evaluation of the value, if needed.
     * A return statement that is not the last statement of the method (an early return) would interrupt the test case
     * where the method is inlined: in that case, the body of the method is inlined as a labeled block, and the early
     * return statements leave the block ({@code break <label>;}), continuing with the statements of the test case that
     * follow the call.
     *
     * @return the label of the block, if the method has early return statements
     */
    private static Optional<String> replaceReturns(MethodDeclaration method, NameAllocator variableNames, NameAllocator labelNames) {
        BlockStmt body = method.getBody().orElseThrow();
        // The return statements within lambda expressions or nested classes belong to them
        List<ReturnStmt> returnStmts = body.findAll(ReturnStmt.class).stream()
                .filter(returnStmt -> isReturnOfMethod(returnStmt, method))
                .toList();
        Optional<String> label = Optional.empty();
        for (ReturnStmt returnStmt : returnStmts) {
            Optional<Statement> returnedValueEvaluation = returnStmt.getExpression()
                    .flatMap(returnExpr -> evaluateDiscardedValue(returnExpr, method, variableNames));
            boolean trailingReturn = body.getStatements().getLast().orElse(null) == returnStmt;
            if (trailingReturn) {
                if (returnedValueEvaluation.isPresent()) {
                    returnStmt.replace(returnedValueEvaluation.get());
                } else {
                    body.remove(returnStmt);
                }
            } else {
                if (label.isEmpty()) {
                    label = Optional.of(labelNames.allocate(method.getNameAsString()));
                }
                replaceWithBreak(returnStmt, returnedValueEvaluation, label.get());
            }
        }
        return label;
    }

    /**
     * Replaces an early return statement of an auxiliary method, inlined as a labeled block, with a {@code break}
     * statement that leaves the labeled block. If the returned value must be evaluated, the evaluation is placed before
     * the {@code break} statement.
     *
     * @param returnStmt the return statement to replace
     * @param returnedValueEvaluation the statement evaluating the returned value, if needed
     * @param label the label of the block where the auxiliary method is inlined
     */
    private static void replaceWithBreak(ReturnStmt returnStmt, Optional<Statement> returnedValueEvaluation, String label) {
        BreakStmt breakStmt = new BreakStmt(label);
        if (returnedValueEvaluation.isEmpty()) {
            returnStmt.replace(breakStmt);
        } else if (returnStmt.getParentNode().orElse(null) instanceof BlockStmt parentBlock) {
            // Keep the evaluation and the break statement in the block where the return statement was defined
            NodeList<Statement> statements = parentBlock.getStatements();
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) == returnStmt) {
                    statements.set(i, breakStmt);
                    statements.add(i, returnedValueEvaluation.get());
                    break;
                }
            }
        } else {
            // The return statement is the body of another statement (for example `if (...) return ...;`)
            returnStmt.replace(new BlockStmt(new NodeList<>(returnedValueEvaluation.get(), breakStmt)));
        }
    }

    /**
     * Checks if a return statement belongs to the given method. A return statement defined within a lambda expression,
     * or within a method of a nested or anonymous class, belongs to the lambda expression or to the nested method.
     *
     * @param returnStmt the return statement to check
     * @param method the method declaration
     * @return {@code true} if the return statement terminates the given method, {@code false} otherwise
     */
    private static boolean isReturnOfMethod(ReturnStmt returnStmt, MethodDeclaration method) {
        Optional<Node> node = returnStmt.getParentNode();
        while (node.isPresent() && !(node.get() instanceof LambdaExpr || node.get() instanceof CallableDeclaration || node.get() instanceof InitializerDeclaration)) {
            node = node.get().getParentNode();
        }
        return node.isPresent() && node.get() == method;
    }

    /**
     * Generates the statement that evaluates the value returned by a method, when the value is discarded (the method
     * is invoked as a statement). The value is evaluated only if it can have side effects:
     * <ul>
     *     <li>a statement expression (a method call, an object creation, an assignment, an increment or a decrement)
     *     is kept as an expression statement;</li>
     *     <li>any other expression containing a method call, an object creation, an assignment, an increment or a
     *     decrement is stored in a new local variable (named after the method, with suffix {@code Result}), since it
     *     is not a valid statement by itself;</li>
     *     <li>an expression without side effects (for example a literal, a variable or a comparison between
     *     variables) is not evaluated.</li>
     * </ul>
     *
     * @param returnedValue the expression of the value returned by the method
     * @param method the method returning the value
     * @param variableNames the names of the variables in use within the test case
     * @return the statement evaluating the value, or an empty optional if the value does not need to be evaluated
     */
    private static Optional<Statement> evaluateDiscardedValue(Expression returnedValue, MethodDeclaration method, NameAllocator variableNames) {
        if (isStatementExpression(returnedValue)) {
            return Optional.of(new ExpressionStmt(returnedValue.clone()));
        }
        boolean hasSideEffects = returnedValue.findFirst(Expression.class, AuxiliaryMethodInliner::isStatementExpression).isPresent();
        if (hasSideEffects) {
            String resultName = variableNames.allocate(method.getNameAsString() + "Result");
            return Optional.of(declaration(method.getType().clone(), resultName, returnedValue.clone()));
        }
        return Optional.empty();
    }

    /**
     * Checks if an expression can be used as a statement by itself (a method call, an object creation, an assignment,
     * an increment or a decrement).
     *
     * @param expr the expression to check
     * @return {@code true} if the expression is a statement expression, {@code false} otherwise
     */
    private static boolean isStatementExpression(Expression expr) {
        if (expr.isUnaryExpr()) {
            return isIncrementOrDecrement(expr.asUnaryExpr());
        }
        return expr.isMethodCallExpr() || expr.isObjectCreationExpr() || expr.isAssignExpr();
    }

    /**
     * Returns a copy of a method declared in the test class, located within a copy of the compilation unit of the test
     * class. Unlike a plain clone of the method, the copy is attached to a compilation unit, so the symbols used within
     * it can be resolved. Changes applied to the copy do not affect the original method.
     *
     * @param testClass the test class where the method is declared
     * @param methodSignature the signature of the method to copy
     * @return the copy of the method declaration
     * @throws IllegalStateException if the method is not found within the copy of the compilation unit
     */
    private static MethodDeclaration copyMethodWithinCompilationUnit(TypeDeclaration<?> testClass, String methodSignature) {
        CompilationUnit cuCopy = testClass.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("The test class " + testClass.getNameAsString() + " is not inserted in a compilation unit."))
                .clone();
        String testClassName = testClass.getFullyQualifiedName().orElse(testClass.getNameAsString());
        return cuCopy.findAll(TypeDeclaration.class).stream()
                .filter(t -> t.getFullyQualifiedName().orElse(t.getNameAsString()).equals(testClassName))
                .flatMap(t -> ((TypeDeclaration<?>) t).getMethods().stream())
                .filter(m -> m.getSignature().asString().equals(methodSignature))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Method " + methodSignature + " not found in the copy of the test class " + testClassName));
    }
}
