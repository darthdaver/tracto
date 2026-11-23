import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import star.tracto.SymbolicModule;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.utils.JavaUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Measurement (not part of the module): the type expected at each argument of the calls of the target oracles, and how
 * many of the candidate project tokens at the first token of the argument can reach it.
 *
 * <pre>java -cp tracto.jar:out ExpectedTypes &lt;project&gt; &lt;repo&gt; &lt;tokens dataset json&gt; &lt;module base&gt; &lt;out.jsonl&gt;</pre>
 *
 * One JSON line per argument. Depth of a candidate: the least number of member accesses (field or method, of any
 * arity, not private) after which its type is assignable to one of the expected types (0: its own type; for a method of
 * the test class, its return type; for a class, 1 + the depth of its static members), -1 if not within
 * {@link #MAX_DEPTH}, -2 if its type is unknown. Strict depth: without the members of Object (equals, hashCode,
 * toString, getClass) and the type variables (generic methods such as Class.cast).
 */
public class ExpectedTypes {
    static final int MAX_DEPTH = 3;
    static final int MAX_NODES = 4000;
    static final Set<String> SKIP = Set.of("contextualInfos", "focalClass", "invokedMethods", "javadoc", "body", "testCases",
            "auxiliaryMethods", "setupTearDownMethods", "fields", "parameters", "returnType", "nextPossibleJavaTokens");
    static final Map<String, String> BOX = Map.of("int", "java.lang.Integer", "long", "java.lang.Long", "short", "java.lang.Short",
            "byte", "java.lang.Byte", "char", "java.lang.Character", "boolean", "java.lang.Boolean", "float", "java.lang.Float",
            "double", "java.lang.Double");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Map<String, Integer> COUNTS = new TreeMap<>();
    static TypeSolver typeSolver;

    public static void main(String[] args) throws Exception {
        String project = args[0];
        Path repo = Path.of(args[1]);
        Path tokens = Path.of(args[2]);
        JavaUtils.setJavaLibPath(Path.of(args[3]), JavaUtils.AUTO_JAVA_VERSION, repo);
        StringBuilder classpath = new StringBuilder(JavaUtils.initializeJavaClassPath());
        for (Path library : SymbolicModule.findLibrariesPaths(repo)) {
            classpath.append(File.pathSeparator).append(library);
        }
        JavaParserUtils.setRepoJavaParser(repo, classpath.toString());
        typeSolver = JavaParserUtils.getRepoTypeSolver().orElseThrow();
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[4]));
             JsonParser parser = new JsonFactory().createParser(tokens.toFile())) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("not an array: " + tokens);
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode item = read(parser);
                try {
                    testClass(project, repo, item, out);
                } catch (Exception | StackOverflowError e) {
                    count("test class error " + e.getClass().getSimpleName());
                }
            }
        }
        System.out.println(project + " " + COUNTS);
    }

    /** The JSON value at the current token, without the fields in {@link #SKIP} (streamed: never the whole file). */
    static JsonNode read(JsonParser parser) throws Exception {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (SKIP.contains(name)) {
                    parser.skipChildren();
                } else {
                    node.set(name, read(parser));
                }
            }
            return node;
        }
        if (token == JsonToken.START_ARRAY) {
            ArrayNode node = JsonNodeFactory.instance.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                node.add(read(parser));
            }
            return node;
        }
        return MAPPER.readTree(parser);
    }

    static void count(String key) {
        COUNTS.merge(key, 1, Integer::sum);
    }

    static void testClass(String project, Path repo, JsonNode item, BufferedWriter out) throws Exception {
        String filePath = item.path("testClass").path("filePath").asText();
        CompilationUnit cu = JavaParserUtils.getCompilationUnit(repo.resolve(filePath.substring(1)));
        TypeDeclaration<?> type = cu.getPrimaryType().orElseThrow();
        for (JsonNode datapoint : item.path("datapoints")) {
            count("datapoints");
            String prefix = datapoint.path("testPrefix").path("identifier").asText();
            String target = datapoint.path("target").asText();
            List<MethodDeclaration> methods = type.getMethodsByName(prefix);
            if (methods.size() != 1) {
                count("split test case not found");
                continue;
            }
            Statement parsed;
            try {
                parsed = StaticJavaParser.parseStatement(target);
            } catch (Exception e) {
                count("target not parsed");
                continue;
            }
            String normalized = parsed.toString();
            Optional<ExpressionStmt> real = methods.get(0).findAll(ExpressionStmt.class).stream()
                    .filter(s -> s.toString().equals(normalized) || s.toString().endsWith("." + normalized)).reduce((a, b) -> b);
            if (real.isEmpty()) {
                count("target not found in the split test case");
                if (System.getenv("MEASURE_SHOW_MISSED") != null) {
                    List<ExpressionStmt> all = methods.get(0).findAll(ExpressionStmt.class);
                    System.err.println("MISSED\t" + filePath + "\t" + prefix + "\t" + normalized.replace('\n', ' ') + "\tLAST:\t"
                            + (all.isEmpty() ? "-" : all.get(all.size() - 1).toString().replace('\n', ' ')));
                }
                continue;
            }
            if (System.getenv("MEASURE_SHOW_MISSED") != null) {
                continue;
            }
            List<Expression> parsedCalls = calls(parsed);
            List<Expression> realCalls = calls(real.get());
            if (parsedCalls.size() != realCalls.size()) {
                count("calls differ");
                continue;
            }
            List<String> targetTokens = Antlr4Utils.tokenizeSnippet(target);
            JsonNode steps = datapoint.path("tokenSteps");
            for (int c = 0; c < parsedCalls.size(); c++) {
                NodeWithArguments<?> parsedCall = (NodeWithArguments<?>) parsedCalls.get(c);
                Expression realCall = realCalls.get(c);
                for (int i = 0; i < parsedCall.getArguments().size(); i++) {
                    count("arguments");
                    ObjectNode record = argument(project, filePath, prefix, target, targetTokens, steps, parsedCall, realCall, c, i);
                    if (record != null) {
                        out.write(MAPPER.writeValueAsString(record));
                        out.newLine();
                    }
                }
            }
        }
    }

    static List<Expression> calls(Node node) {
        return node.findAll(Expression.class, e -> e instanceof MethodCallExpr || e instanceof ObjectCreationExpr);
    }

    static int offset(String text, Position position) {
        int line = 1;
        int index = 0;
        while (line < position.line) {
            index = text.indexOf('\n', index) + 1;
            line++;
        }
        return index + position.column - 1;
    }

    static ObjectNode argument(String project, String filePath, String prefix, String target, List<String> targetTokens,
                               JsonNode steps, NodeWithArguments<?> parsedCall, Expression realCall, int callIndex, int argIndex) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("project", project).put("file", filePath).put("prefix", prefix).put("target", target)
                .put("callIndex", callIndex).put("argIndex", argIndex);
        Expression parsedArg = parsedCall.getArgument(argIndex);
        Expression realArg = ((NodeWithArguments<?>) realCall).getArgument(argIndex);
        record.put("call", realCall instanceof MethodCallExpr m ? m.getNameAsString() : "new " + ((ObjectCreationExpr) realCall).getTypeAsString());
        record.put("argument", realArg.toString());
        record.put("argKind", kind(realArg));
        record.put("chainLength", chainLength(realArg));
        // The step of the first token of the argument
        int tokenIndex = Antlr4Utils.tokenizeSnippet(target.substring(0, offset(target, parsedArg.getBegin().orElseThrow()))).size();
        record.put("tokenIndex", tokenIndex);
        JsonNode step = steps.path(tokenIndex);
        String stepTarget = step.path("target").asText(null);
        boolean aligned = stepTarget != null && tokenIndex < targetTokens.size()
                && stepTarget.substring(stepTarget.indexOf(':') + 1).equals(targetTokens.get(tokenIndex));
        record.put("stepTarget", stepTarget);
        record.put("aligned", aligned);
        if (!aligned) {
            count("step not aligned");
        }
        // The expected types: the parameter at this position of the overloads compatible with the arguments before it
        List<ResolvedType> expected;
        try {
            expected = expectedTypes(realCall, argIndex);
        } catch (Exception | StackOverflowError e) {
            count("call not resolved");
            record.put("expected", (String) null);
            return record;
        }
        ArrayNode expectedNode = record.putArray("expected");
        expected.forEach(t -> expectedNode.add(describe(t)));
        record.put("informative", !expected.isEmpty() && expected.stream().noneMatch(ExpectedTypes::uninformative));
        ResolvedType argType = null;
        try {
            argType = realArg.calculateResolvedType();
            record.put("argType", describe(argType));
        } catch (Exception | StackOverflowError e) {
            record.put("argType", (String) null);
        }
        if (argType != null) {
            ResolvedType resolvedArgType = argType;
            record.put("compatible", expected.stream().anyMatch(e -> assignable(e, resolvedArgType)));
        }
        // The candidates of the step: the depth of each, when the first token of the argument is a project token
        if (aligned && stepTarget.startsWith("IDENTIFIER:") || aligned && step.path("nextPossibleProjectTokens").toString().contains("\"" + targetTokens.get(tokenIndex) + "\"")) {
            String targetToken = targetTokens.get(tokenIndex);
            ObjectNode depths = record.putObject("depths");
            ObjectNode strictDepths = record.putObject("strictDepths");
            for (JsonNode candidate : step.path("nextPossibleProjectTokens")) {
                depths.put(candidate.asText(), candidateDepth(candidate.asText(), realArg, expected, false));
                strictDepths.put(candidate.asText(), candidateDepth(candidate.asText(), realArg, expected, true));
            }
            record.put("targetToken", targetToken);
            record.put("targetDepth", depths.has(targetToken) ? depths.get(targetToken).asInt() : candidateDepth(targetToken, realArg, expected, false));
            record.put("targetStrictDepth", strictDepths.has(targetToken) ? strictDepths.get(targetToken).asInt() : candidateDepth(targetToken, realArg, expected, true));
            record.put("targetIsCandidate", depths.has(targetToken));
        }
        return record;
    }

    static String kind(Expression e) {
        if (e instanceof NullLiteralExpr) return "null";
        if (e instanceof LiteralExpr) return "literal";
        if (e instanceof NameExpr) return "name";
        if (e instanceof MethodCallExpr || e instanceof FieldAccessExpr) return "chain";
        if (e instanceof ObjectCreationExpr || e instanceof ArrayCreationExpr) return "creation";
        if (e instanceof BinaryExpr || e instanceof UnaryExpr || e instanceof ConditionalExpr || e instanceof InstanceOfExpr) return "operator";
        if (e instanceof EnclosedExpr enclosed) return kind(enclosed.getInner()).equals("operator") ? "operator" : "enclosed";
        if (e instanceof CastExpr) return "cast";
        if (e instanceof LambdaExpr || e instanceof MethodReferenceExpr) return "lambda";
        if (e instanceof ArrayAccessExpr) return "arrayAccess";
        if (e instanceof ClassExpr) return "class";
        return e.getClass().getSimpleName();
    }

    static int chainLength(Expression e) {
        int length = 0;
        while (true) {
            if (e instanceof MethodCallExpr m && m.getScope().isPresent()) {
                e = m.getScope().get();
            } else if (e instanceof FieldAccessExpr f) {
                e = f.getScope();
            } else if (e instanceof ArrayAccessExpr a) {
                e = a.getName();
            } else {
                return length;
            }
            length++;
        }
    }

    static List<ResolvedType> expectedTypes(Expression call, int argIndex) {
        List<List<ResolvedType>> signatures = new ArrayList<>();
        List<Boolean> variadic = new ArrayList<>();
        if (call instanceof MethodCallExpr m) {
            ResolvedMethodDeclaration declaration = m.resolve();
            Collection<MethodUsage> usages;
            if (m.getScope().isPresent() && !(m.getScope().get() instanceof NameExpr n && isType(n)) && m.getScope().get().calculateResolvedType() instanceof ResolvedReferenceType scope) {
                usages = new ArrayList<>(scope.getDeclaredMethods());
                for (ResolvedReferenceType ancestor : scope.getAllAncestors()) {
                    usages.addAll(ancestor.getDeclaredMethods());
                }
            } else {
                usages = declaration.declaringType().getAllMethods();
            }
            for (MethodUsage usage : usages) {
                if (usage.getName().equals(m.getNameAsString())) {
                    signatures.add(usage.getParamTypes());
                    variadic.add(usage.getDeclaration().hasVariadicParameter());
                }
            }
        } else {
            ObjectCreationExpr creation = (ObjectCreationExpr) call;
            ResolvedConstructorDeclaration declaration = creation.resolve();
            for (ResolvedConstructorDeclaration constructor : declaration.declaringType().getConstructors()) {
                List<ResolvedType> parameters = new ArrayList<>();
                for (int p = 0; p < constructor.getNumberOfParams(); p++) {
                    parameters.add(constructor.getParam(p).getType());
                }
                signatures.add(parameters);
                variadic.add(constructor.hasVariadicParameter());
            }
        }
        List<Expression> arguments = new ArrayList<>(((NodeWithArguments<?>) call).getArguments());
        List<ResolvedType> before = new ArrayList<>();
        for (int a = 0; a < argIndex; a++) {
            try {
                before.add(arguments.get(a).calculateResolvedType());
            } catch (Exception | StackOverflowError e) {
                before.add(null);
            }
        }
        Map<String, ResolvedType> expected = new LinkedHashMap<>();
        for (int s = 0; s < signatures.size(); s++) {
            List<ResolvedType> parameters = signatures.get(s);
            boolean varargs = variadic.get(s);
            ResolvedType at = parameterAt(parameters, varargs, argIndex);
            if (at == null) {
                continue;
            }
            boolean fits = true;
            for (int a = 0; a < argIndex && fits; a++) {
                ResolvedType parameter = parameterAt(parameters, varargs, a);
                fits = parameter != null && (before.get(a) == null || assignable(parameter, before.get(a))
                        || varargs && a == parameters.size() - 1 && parameter.isArray() && assignable(parameter.asArrayType().getComponentType(), before.get(a)));
            }
            if (fits) {
                expected.putIfAbsent(describe(at), at);
                if (varargs && argIndex >= parameters.size() - 1 && at.isArray()) {
                    ResolvedType component = at.asArrayType().getComponentType();
                    expected.putIfAbsent(describe(component), component);
                }
            }
        }
        return new ArrayList<>(expected.values());
    }

    static ResolvedType parameterAt(List<ResolvedType> parameters, boolean varargs, int index) {
        if (index < parameters.size()) {
            return parameters.get(index);
        }
        if (varargs && !parameters.isEmpty()) {
            ResolvedType last = parameters.get(parameters.size() - 1);
            return last.isArray() ? last.asArrayType().getComponentType() : last;
        }
        return null;
    }

    static boolean isType(NameExpr name) {
        try {
            name.calculateResolvedType();
            return false;
        } catch (Exception | StackOverflowError e) {
            return true;
        }
    }

    static boolean uninformative(ResolvedType type) {
        return type.isTypeVariable() || type.isWildcard()
                || type.isReferenceType() && type.asReferenceType().getQualifiedName().equals("java.lang.Object");
    }

    static String describe(ResolvedType type) {
        try {
            return type.describe();
        } catch (Exception | StackOverflowError e) {
            return "?";
        }
    }

    static boolean assignable(ResolvedType to, ResolvedType from) {
        try {
            if (to.isTypeVariable() || to.isWildcard()) {
                return true;
            }
            if (to.isPrimitive() && from.isReferenceType()) {
                for (Map.Entry<String, String> box : BOX.entrySet()) {
                    if (box.getValue().equals(from.asReferenceType().getQualifiedName())) {
                        return to.isAssignableBy(com.github.javaparser.resolution.types.ResolvedPrimitiveType.byName(box.getKey()));
                    }
                }
                return false;
            }
            if (to.isReferenceType() && from.isPrimitive()) {
                String boxed = BOX.get(from.asPrimitive().describe());
                SymbolReference<ResolvedReferenceTypeDeclaration> reference = typeSolver.tryToSolveType(boxed);
                return reference.isSolved() && to.isAssignableBy(new ReferenceTypeImpl(reference.getCorrespondingDeclaration()));
            }
            return to.isAssignableBy(from);
        } catch (Exception | StackOverflowError e) {
            return false;
        }
    }

    /** The depth of a candidate name at the position of the argument (see the class comment). */
    static int candidateDepth(String name, Expression argument, List<ResolvedType> expected, boolean strict) {
        if (expected.isEmpty()) {
            return -2;
        }
        // A variable, a parameter or a field: its type, at the position of the argument
        NameExpr probe = new NameExpr(name);
        argument.replace(probe);
        try {
            try {
                return depth(List.of(probe.calculateResolvedType()), expected, strict);
            } catch (Exception | StackOverflowError e) {
                // not a value
            }
            // A class: its static members
            try {
                SymbolReference<ResolvedTypeDeclaration> reference = JavaParserFactory.getContext(probe, typeSolver).solveType(name);
                if (reference.isSolved() && reference.getCorrespondingDeclaration().isClass() || reference.isSolved() && reference.getCorrespondingDeclaration().isInterface() || reference.isSolved() && reference.getCorrespondingDeclaration().isEnum()) {
                    ResolvedReferenceTypeDeclaration declaration = reference.getCorrespondingDeclaration().asReferenceType();
                    List<ResolvedType> members = new ArrayList<>();
                    for (ResolvedMethodDeclaration method : declaration.getDeclaredMethods()) {
                        if (method.isStatic() && method.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE) members.add(method.getReturnType());
                    }
                    for (ResolvedFieldDeclaration field : declaration.getAllFields()) {
                        if (field.isStatic() && field.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE) members.add(field.getType());
                    }
                    int d = depth(members, expected, strict);
                    return d >= 0 ? (d + 1 > MAX_DEPTH ? -1 : d + 1) : d;
                }
            } catch (Exception | StackOverflowError e) {
                // not a class
            }
            // A method of the test class (or one inherited): its return types
            Optional<TypeDeclaration> enclosing = argument.findAncestor(TypeDeclaration.class);
            if (enclosing.isPresent()) {
                try {
                    List<ResolvedType> returns = new ArrayList<>();
                    for (MethodUsage usage : enclosing.get().resolve().asReferenceType().getAllMethods()) {
                        if (usage.getName().equals(name)) returns.add(usage.returnType());
                    }
                    if (!returns.isEmpty()) {
                        return depth(returns, expected, strict);
                    }
                } catch (Exception | StackOverflowError e) {
                    // unknown
                }
            }
            return -2;
        } finally {
            probe.replace(argument);
        }
    }

    /** The least depth, over the starting types, at which a type assignable to an expected one is reached. */
    static int depth(List<ResolvedType> start, List<ResolvedType> expected, boolean strict) {
        List<ResolvedType> level = new ArrayList<>(start);
        Set<String> seen = new HashSet<>();
        boolean unknown = false;
        for (int d = 0; d <= MAX_DEPTH && !level.isEmpty(); d++) {
            List<ResolvedType> next = new ArrayList<>();
            for (ResolvedType type : level) {
                if (type == null || type.isVoid() || !seen.add(describe(type))) {
                    continue;
                }
                if (type.isTypeVariable() || type.isWildcard()) {
                    // Strict: a type variable (a generic method such as Class.cast) is not a path to the expected type
                    unknown |= !strict;
                    continue;
                }
                for (ResolvedType e : expected) {
                    if (assignable(e, type)) {
                        return d;
                    }
                }
                if (d < MAX_DEPTH && seen.size() < MAX_NODES) {
                    next.addAll(members(type, strict));
                }
            }
            level = next;
        }
        return unknown ? -2 : -1;
    }

    static List<ResolvedType> members(ResolvedType type, boolean strict) {
        List<ResolvedType> members = new ArrayList<>();
        try {
            if (type instanceof ResolvedArrayType array) {
                members.add(array.getComponentType());
                members.add(com.github.javaparser.resolution.types.ResolvedPrimitiveType.INT);
                return members;
            }
            if (!type.isReferenceType()) {
                return members;
            }
            ResolvedReferenceType reference = type.asReferenceType();
            List<ResolvedReferenceType> types = new ArrayList<>();
            types.add(reference);
            types.addAll(reference.getAllAncestors());
            for (ResolvedReferenceType t : types) {
                if (strict && t.getQualifiedName().equals("java.lang.Object")) {
                    // Strict: not equals, hashCode, toString, getClass (every type reaches boolean, int, String with them)
                    continue;
                }
                for (MethodUsage usage : t.getDeclaredMethods()) {
                    if (usage.getDeclaration().accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE) {
                        members.add(usage.returnType());
                    }
                }
                Optional<ResolvedReferenceTypeDeclaration> declaration = t.getTypeDeclaration();
                if (declaration.isPresent()) {
                    for (ResolvedFieldDeclaration field : declaration.get().getDeclaredFields()) {
                        if (field.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE) {
                            members.add(t.useThisTypeParametersOnTheGivenType(field.getType()));
                        }
                    }
                }
            }
        } catch (Exception | StackOverflowError e) {
            // the members that could be resolved
        }
        return members;
    }
}
