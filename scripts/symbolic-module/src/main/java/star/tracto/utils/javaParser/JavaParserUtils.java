package star.tracto.utils.javaParser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistMethodDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.CallableExprType;
import star.tracto.data.enums.JUnitAssertionType;
import star.tracto.data.enums.NamingConvention;
import star.tracto.data.exceptions.CandidateCallableDeclarationNotFoundException;
import star.tracto.data.exceptions.CandidateCallableMethodUsageNotFoundException;
import star.tracto.data.exceptions.MultipleCandidatesException;
import star.tracto.data.exceptions.UnrecognizedExprException;
import star.tracto.preprocessing.builders.TestClazzDatapointsBuilder;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.PartialOracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.utils.TokensUtils;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.JavaUtils;
import star.tracto.utils.javaParser.visitors.expressions.NameExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.VariableDeclarationExprVisitor;
import star.tracto.utils.javaParser.visitors.statements.helper.StmtVisitorHelper;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.plumelib.util.CollectionsPlume.mapList;

/**
 * This class provides static methods for JavaParser utilities.
 */
public class JavaParserUtils {

    private static JavaParser javaParser;
    private static JavaSymbolSolver symbolSolver;
    private static final Logger logger = LoggerFactory.getLogger(JavaParserUtils.class);
    /** Regex to match the Javadoc of a class or method. */
    private static final Pattern javadocPattern = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    /** Regex to match the binary name of a class (e.g. "package.submodule.InnerClass$OuterClass") */
    private static final Pattern PACKAGE_CLASS = Pattern.compile("[a-zA-Z_][a-zA-Z\\d_]*(\\.[a-zA-Z_][a-zA-Z\\d_]*)*");
    /** Regex to match {@link ReflectionMethodDeclaration#toString()}. */
    private static final Pattern REFLECTION_METHOD_DECLARATION = Pattern.compile(
            "^ReflectionMethodDeclaration\\{method=((.*) )?\\S+ \\S+\\(.*}$"
    );
    /** Regex to match {@link JavassistMethodDeclaration#toString()}. */
    private static final Pattern JAVASSIST_METHOD_DECLARATION = Pattern.compile(
            "^JavassistMethodDeclaration\\{ctMethod=.*\\[((.*) )?\\S+ \\(.*\\).*]}$"
    );
    /** Artificial class name. */
    private static final String SYNTHETIC_CLASS_NAME = "Tracto__AuxiliaryClass";
    /** Artificial class source code. */
    private static final String SYNTHETIC_CLASS_SOURCE = "public class " + SYNTHETIC_CLASS_NAME + " {}";
    /** Artificial method name. */
    private static final String SYNTHETIC_METHOD_NAME = "__tracto__auxiliaryMethod";



    /** Do not instantiate this class. */
    private JavaParserUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Creates a JavaParser object capable of resolving symbols from a given
     * source directory (like a java repository).
     *
     * @param repoRootPath the path to a Java repository (containing a single or multiple Java projects)
     * @param classpath a string containing additional paths to libraries and jars (each reference in the
     *                  classpath is separated by a ":")
     * @return the corresponding JavaParser
     */
    public static JavaParser setRepoJavaParser(Path repoRootPath, String classpath) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        // TODO: Verify code without ReflectionTypeSolver and using java source code libraries
        // typeSolver.add(new ReflectionTypeSolver());
        List<Path> repoProjectsPaths = findPotentialProjectsRoots(repoRootPath);
        for(Path projectPath : repoProjectsPaths) {
            typeSolver.add(new JavaParserTypeSolver(projectPath.toFile()));
        }
        String[] classpathElements = classpath.split(":");
        for (String classPathElement : classpathElements) {
            if (classPathElement.endsWith(".jar")) {
                try {
                    typeSolver.add(new JarTypeSolver(classPathElement));
                } catch (IOException e) {
                    throw new Error("Unable to resolve jar " + classPathElement, e);
                }
            } else {
                Path startPath = Paths.get(classPathElement);
                try {
                    List<Path> libraryRootPaths = findPotentialProjectsRoots(Path.of(classPathElement));

                    if (!libraryRootPaths.isEmpty()) {
                        for(Path libraryRootPath : libraryRootPaths) {
                            typeSolver.add(new JavaParserTypeSolver(libraryRootPath.toFile()));
                        }
                    } else {
                        boolean jarFilesFolder = false;
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(startPath, "*.jar")) {
                            for (Path entry : stream) {
                                if (Files.isRegularFile(entry)) {
                                    jarFilesFolder = true;
                                }
                            }
                        }
                        if (jarFilesFolder) {
                            Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    // Check if the file is a .jar file
                                    if (file.toString().endsWith(".jar")) {
                                        typeSolver.add(new JarTypeSolver(file));
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17); // or JAVA_17
        javaParser = new JavaParser(parserConfiguration);
        StaticJavaParser.setConfiguration(parserConfiguration);
        return javaParser;
    }

    /**
     * Gets a compilation unit from a Java file path.
     *
     * @param path a Java file
     * @return the corresponding JavaParser compilation unit. Returns
     * {@code Optional.empty()} if an error occurs while attempting to parse
     * the file.
     */
    public static CompilationUnit getCompilationUnit(Path path) throws IOException {
        if (javaParser == null) {
            throw new IllegalStateException("JavaParser must be set with a repository root path, before calling this method.");
        }
        return javaParser.parse(path).getResult().orElseThrow();
    }

    /**
     * Gets a compilation unit from a code string.
     *
     * @param code a string containing Java code
     * @return the corresponding JavaParser compilation unit. Returns
     * {@code Optional.empty()} if an error occurs while attempting to parse
     * the file.
     */
    public static CompilationUnit getCompilationUnit(String code) throws IOException {
        if (javaParser == null) {
            throw new IllegalStateException("JavaParser must be set with a repository root path, before calling this method.");
        }
        return javaParser.parse(code).getResult().orElseThrow();
    }

    /**
     * Generate a full signature of a variable declaration, given the list of its modifiers.
     * A signature follows the format:
     *     "[modifiers] [type] [variableName] [=] [variableValue];"
     *
     * @param modifiers the list of java modifiers of the variable declarator.
     * @param variable the variable from which to generate the full signature
     * @return the full signature as string
     */
    public static String getSignatureFromVariableDeclarator(List<String> modifiers, VariableDeclarator variable) {
        return modifiers.stream().collect(Collectors.joining(" ")) + " " + variable.getType().asString() + " " + variable + ";";
    }

    /**
     * Returns the signature of a JavaParser method declaration.
     *
     * @param methodDeclaration a JavaParser method declaration
     * @return a string representation of the signature. Signature follows the
     * format:
     *  "[modifiers] [typeParameters] [type] [methodName]([parameters]) throws [exceptions]"
     */
    public static String getMethodSignature(MethodDeclaration methodDeclaration) {
        String method = methodDeclaration.toString();
        if (methodDeclaration.getBody().isPresent()) {
            // Remove body
            method = method.replace(methodDeclaration.getBody().get().toString(), "");
        }
        for (Node comment: methodDeclaration.getAllContainedComments()) {
            // Remove comments within method signature
            method = method.replace(comment.toString(), "");
        }
        // Last line is method signature, remove everything before that
        method = method.replaceAll("[\\s\\S]*\n", "");
        return method.trim().replaceAll(";$", "");
    }

    /**
     * Returns the package name of a class from a {@link TypeDeclaration} (wrapped in an {@link Optional} or an empty
     * optional if the fully qualified name of the type declaration cannot be retrieved.
     * @param typeDeclaration a JavaParser type declaration representing a Java class
     * @return the package name of the class as a string (wrapped in  an {@link Optional}), or an empty optional if the
     * fully qualified name of the type declaration cannot be retrieved
     * @throws IllegalStateException if the fully qualified name of the type declaration cannot be retrieved
     */
    public static Optional<String> getPackageNameFromTypeDeclaration(TypeDeclaration typeDeclaration) {
        Optional<String> fullyQualifiedName = typeDeclaration.getFullyQualifiedName();
        if (fullyQualifiedName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fullyQualifiedName.get().substring(0, fullyQualifiedName.get().lastIndexOf('.')));
    }

    public static List<String> getExceptionsAsString(List<ReferenceType> exceptions) {
        return exceptions.stream().map(ReferenceType::asString).collect(Collectors.toList());
    }

    /**
     * Gets the modifiers of a JavaParser MethodUsage. Unfortunately, the
     * implementations of ResolvedMethodDeclaration have different approaches
     * for storing modifier information. This method uses regexes to handle
     * retrieving this information from {@link ReflectionMethodDeclaration} or
     * {@link JavassistMethodDeclaration} implementations of
     * ResolvedMethodDeclaration.
     *
     * @param methodDeclaration a resolved method declaration. Must be either
     *                          {@link ReflectionMethodDeclaration} or
     *                          {@link JavassistMethodDeclaration}.
     * @return the modifiers of the method
     */
    public static List<String> getMethodModifiers(ResolvedMethodDeclaration methodDeclaration) {
        Matcher reflectionMatcher = REFLECTION_METHOD_DECLARATION.matcher(methodDeclaration.toString());
        Matcher javassistMatcher = JAVASSIST_METHOD_DECLARATION.matcher(methodDeclaration.toString());
        if (!reflectionMatcher.find() && !javassistMatcher.find()) {
            throw new IllegalStateException("Could not parse method signature: " + methodDeclaration);
        }
        String methodModifiers;
        if (methodDeclaration instanceof ReflectionMethodDeclaration) {
            methodModifiers = reflectionMatcher.group(1);
        } else {
            methodModifiers = javassistMatcher.group(1);
        }
        if (methodModifiers == null) {
            methodModifiers = "";
        }
        return Stream.of(methodModifiers.split(" ")).map(m -> m.trim()).collect(Collectors.toList());
    }

    /**
     * Returns the signature of a JavaParser callable declaration. Uses the
     * method source code and removes method body, contained comments, the
     * Javadoc comment, and other special characters (e.g. "\n").
     *
     * @param jpCallable a JavaParser callable declaration
     * @return a string representation of the signature. A signature follows
     * the format:
     *     "[modifiers] [type] [methodName]([parameters]) throws [exceptions]"
     */
    public static String getCallableSignature(CallableDeclaration<?> jpCallable) {
        StringBuilder sb = new StringBuilder();
        // add modifiers
        List<String> modifiers = jpCallable.getModifiers()
                .stream()
                .map(Modifier::toString)
                .toList();
        sb.append(String.join("", modifiers));
        // add type parameters
        List<String> typeParameters = jpCallable.getTypeParameters()
                .stream()
                .map(TypeParameter::toString)
                .toList();
        if (!typeParameters.isEmpty()) {
            sb.append("<")
                    .append(String.join(", ", typeParameters))
                    .append(">")
                    .append(" ");
        }
        // add return type (if not a constructor)
        if (jpCallable.isMethodDeclaration()) {
            sb.append(jpCallable.asMethodDeclaration().getType())
                    .append(" ");
        }
        // add method name
        sb.append(jpCallable.getNameAsString());
        // add formal parameters
        List<String> parameters = jpCallable.getParameters()
                .stream()
                .map(com.github.javaparser.ast.body.Parameter::toString)
                .toList();
        sb.append("(")
                .append(String.join(", ", parameters))
                .append(")");
        // add exceptions
        List<String> exceptions = jpCallable.getThrownExceptions()
                .stream()
                .map(ReferenceType::asString)
                .toList();
        if (!exceptions.isEmpty()) {
            sb.append(" throws ")
                    .append(String.join(", ", exceptions));
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    /**
     * Gets the method signature from a JavaParser MethodUsage. Unfortunately,
     * depending on the implementation of the ResolvedMethodDeclaration, it is
     * not possible to recover specific features, such as:
     * <ul>
     *     <li>Modifiers</li>
     *     <li>Annotations</li>
     *     <li>Parameter names</li>
     * </ul>
     * This method considers three implementations of
     * ResolvedMethodDeclaration: JavaParserMethodDeclaration,
     * ReflectionMethodDeclaration, and JavassistMethodDeclaration. A
     * signature follows the format:
     *     "[modifiers] [typeParameters] [type] [methodName]([parameters]) throws [exceptions]"
     * All type names do not use package names for compatibility with the
     * XText grammar.
     */
    public static String getMethodSignature(MethodUsage methodUsage) {
        ResolvedMethodDeclaration methodDeclaration = methodUsage.getDeclaration();
        // Consider JavaParserMethodDeclaration.
        if (methodDeclaration instanceof JavaParserMethodDeclaration jpMethodDeclaration) {
            MethodDeclaration jpMethod = jpMethodDeclaration.getWrappedNode();
            return getMethodSignature(jpMethod);
        }
        List<String> methodModifiers = getMethodModifiers(methodDeclaration);
        List<String> typeParameterList = getTypeParameters(methodUsage);
        List<String> formalParameterList = getParameters(methodUsage);
        List<String> exceptionList = getExceptions(methodUsage);
        return (methodModifiers.stream().collect(Collectors.joining(" ")) +
                " " + (typeParameterList.isEmpty() ? "" : "<" + String.join(", ", typeParameterList) + ">") +
                " " + getTypeWithoutPackages(methodDeclaration.getReturnType()) +
                " " + methodDeclaration.getName() +
                "(" + String.join(", ", formalParameterList) + ")" +
                (exceptionList.isEmpty() ? "" : " throws " + String.join(", ", exceptionList)))
                .replaceAll(" +", " ").trim();
    }

    /**
     * Gets the simple names of all exceptions that can be thrown by a given
     * method.
     *
     * @param methodUsage the method in the form of {@link MethodUsage}
     * @return a list of string representing the simple names of all the exceptions that can be thrown by the method
     */
    public static List<String> getExceptions(MethodUsage methodUsage) {
        List<ResolvedType> exceptions = methodUsage.getDeclaration().getSpecifiedExceptions();
        return mapList(JavaParserUtils::getTypeWithoutPackages, exceptions);
    }

    /**
     * Gets the Javadoc comment of a given class.
     *
     * @param jpClass a JavaParser class
     * @return the class Javadoc comment, in Javadoc format, surrounded by
     * "&#47;&#42;&#42; ... &#42;&#42;&#47;" for compatibility with the XText
     * grammar (empty string if not found)
     */
    public static String getClassJavadoc(TypeDeclaration<?> jpClass) {
        Optional<JavadocComment> optionalJavadocComment = jpClass.getJavadocComment();
        return optionalJavadocComment
                .map(javadocComment -> "/**" + javadocComment.getContent() + "*/")
                .orElseGet(() -> getJavadocByPattern(jpClass));
    }

    /**
     * Gets the Javadoc comment of a given method.
     *
     * @param jpCallable a JavaParser method
     * @return the method/constructor Javadoc comment, in Javadoc format,
     * surrounded by "&#47;&#42; ... &#42;&#47;" (empty string if not found)
     */
    public static String getCallableJavadoc(CallableDeclaration<?> jpCallable) {
        Optional<JavadocComment> optionalJavadocComment = jpCallable.getJavadocComment();
        return optionalJavadocComment
                .map(javadocComment -> "    /**" + javadocComment.getContent().replaceAll("@exception ", "@throws ") + "*/")
                .orElseGet(() -> getJavadocByPattern(jpCallable));
    }

    /**
     * Gets the source code of a given method or constructor.
     *
     * @param jpCallable a method or constructor
     * @return a string representation of the source code
     */
    public static String getCallableSourceCode(CallableDeclaration<?> jpCallable) {
        String jpSignature = JavaParserUtils.getCallableSignature(jpCallable);
        Optional<BlockStmt> jpBody = jpCallable instanceof MethodDeclaration
                ? ((MethodDeclaration) jpCallable).getBody()
                : Optional.ofNullable(((ConstructorDeclaration) jpCallable).getBody());
        return jpSignature + (jpBody.isEmpty() ? ";" : jpBody.get().toString());
    }

    /**
     * Gets the Javadoc comment of a class or method using a regex. This
     * method should ONLY be called by
     * {@link JavaParserUtils#getCallableJavadoc(CallableDeclaration)} or
     * {@link JavaParserUtils#getClassJavadoc(TypeDeclaration)} (e.g. after
     * attempting to recover the Javadoc using the JavaParser API).
     *
     * @param jpBody a Java class or method
     * @return the matched Javadoc comment surrounded by
     * "&#47;&#42;&#42; ... &#42;&#42;&#47;" for compatibility with the XText
     * grammar (empty string if not found)
     */
    private static String getJavadocByPattern(BodyDeclaration<?> jpBody) {
        String input = jpBody.toString();
        Matcher matcher = javadocPattern.matcher(input);
        if (matcher.find()) {
            String content = matcher.group(1);
            if (jpBody instanceof TypeDeclaration<?>) {
                // class javadoc format
                return "/**" + content + "*/";
            } else {
                // method javadoc format
                return "    /**" + content + "*/";
            }
        }
        return "";
    }

    /**
     * Gets the Javadoc comment of a method from it's string representation, using a regex.
     *
     * @return the matched Javadoc comment surrounded by
     * "&#47;&#42;&#42; ... &#42;&#42;&#47;" for compatibility with the XText
     * grammar (empty string if not found)
     */
    public static String getJavadocByPattern(String input) {
        Matcher matcher = javadocPattern.matcher(input);
        if (matcher.find()) {
            String content = matcher.group(1);
            return "/**" + content + "*/";
        }
        return "";
    }

    /**
     * Gets all formal parameters in the method definition. This method
     * returns the type of each parameter, followed by an artificial name. For
     * example,
     *     "MethodUsage[get(int i)]"    &rarr;    "List.of("int arg1")"
     */
    private static List<String> getParameters(MethodUsage methodUsage) {
        ResolvedMethodDeclaration methodDeclaration = methodUsage.getDeclaration();
        // iterate through each parameter in the method declaration.
        List<String> methodParameters = new ArrayList<>();
        for (int i = 0; i < methodDeclaration.getNumberOfParams(); i++) {
            methodParameters.add(getTypeWithoutPackages(methodDeclaration.getParam(i).getType()) + " arg" + i);
        }
        return methodParameters;
    }

    /**
     * A resolved type may be void, primitive, an array, a reference type, etc.
     * (including arrays of reference types). If the type involves a reference
     * type, this method returns the fully qualified name without packages.
     *
     * @param resolvedType JavaParser resolved type
     * @return the name of a type without packages. If the resolved type is an
     * array of reference types, then this method removes the packages from
     * the fully qualified name of the element types.
     */
    public static String getTypeWithoutPackages(ResolvedType resolvedType) {
        String typeName = resolvedType.describe();
        ResolvedType elementType = getElementType(resolvedType);
        if (elementType.isReferenceType()) {
            // use the original type name to avoid removing array brackets
            return getTypeWithoutPackages(typeName);
        } else {
            return typeName;
        }
    }

    /**
     * Removes the package name from a fully qualified name of a type for
     * compatibility with the XText grammar. Also removes package from type
     * parameters.
     *
     * @param fullyQualifiedName a fully qualified name of a type
     * @return the type name without packages. Includes outer classes, e.g.,
     *     package.Outer.Inner    =>    Outer.Inner
     */
    public static String getTypeWithoutPackages(String fullyQualifiedName) {
        // regex is used instead of String.lastIndexOf to avoid removing outer classes
        Matcher matcher = PACKAGE_CLASS.matcher(fullyQualifiedName);
        // continuously remove packages until none remain
        while (matcher.find()) {
            if (matcher.group().contains(".")) {
                // gets the class name using a JavaParser ResolvedReferenceTypeDeclaration
                String classNameWithoutPackages = getResolvedReferenceTypeDeclaration(matcher.group())
                        .getClassName();
                // replaces all instances of the fully qualified name with the JavaParser type name
                fullyQualifiedName = fullyQualifiedName.replaceAll(
                        matcher.group(),
                        classNameWithoutPackages
                );
            }
        }
        return fullyQualifiedName;
    }

    /**
     * Returns the {@link ResolvedReferenceTypeDeclaration} of a given fully
     * qualified type name.
     *
     * @param fqName fully qualified type name, e.g., {@code java.util.List}
     * @return the corresponding JavaParser ResolvedReferenceTypeDeclaration
     * @throws UnsolvedSymbolException if the type cannot be resolved
     * @throws UnsupportedOperationException if the type is an array or
     * primitive type
     */
    public static ResolvedReferenceTypeDeclaration getResolvedReferenceTypeDeclaration(String fqName) throws UnsolvedSymbolException, UnsupportedOperationException {
        return getResolvedType(fqName).asReferenceType().getTypeDeclaration().get();
    }

    // TODO: Check that this method is not called with binary names as argument
    /**
     * @param fqName fully qualified name, e.g., {@code java.util.List}
     */
    public static ResolvedType getResolvedType(String fqName) throws UnsupportedOperationException {
        CompilationUnit cu = javaParser.parse(SYNTHETIC_CLASS_SOURCE).getResult().get();
        BlockStmt syntheticMethodBody = getClassOrInterface(cu, SYNTHETIC_CLASS_NAME).addMethod(SYNTHETIC_METHOD_NAME).getBody().get();
        syntheticMethodBody.addStatement(fqName + " type1Var;");
        return getClassOrInterface(cu, SYNTHETIC_CLASS_NAME)
                .getMethodsByName(SYNTHETIC_METHOD_NAME).get(0)
                .getBody().get()
                .getStatements().getLast().get()
                .asExpressionStmt().getExpression()
                .asVariableDeclarationExpr().getVariables().get(0)
                .resolve().getType();
    }

    /**
     * Returns a list of type declarations defined within a compilation unit and with a given type name.
     * This method searches for the type declaration(s) with the given name in the compilation unit, including inner classes.
     *
     * @param cu the compilation unit
     * @param typeName the name of the type declaration(s) to find within the compilation unit
     * @return a list containing the found type declarations, or an empty list if no type declarations with the given name
     * are found.
     */
    public static List<TypeDeclaration> getTypeDeclarationsFromCompilationUnitAndName(CompilationUnit cu, String typeName) {
        List<TypeDeclaration> typeDeclarations = new ArrayList<>();
        for (TypeDeclaration typeDeclaration: cu.getTypes()) {
            if (typeDeclaration.getNameAsString().equals(typeName)) {
                 typeDeclarations.add(typeDeclaration);
                 continue;
            }

            Optional<TypeDeclaration> targetTypeDeclaration =  Optional.empty();

            List<TypeDeclaration> innerTypeDeclarations = (List<TypeDeclaration>) typeDeclaration.getMembers().stream()
                    .filter(m -> ((BodyDeclaration) m).isTypeDeclaration())
                    .collect(Collectors.toList());

            while (!targetTypeDeclaration.isEmpty() && innerTypeDeclarations.size() > 0) {
                List<TypeDeclaration> newInnerTypeDeclarations = new ArrayList<>();
                for (TypeDeclaration innerTypeDeclaration: innerTypeDeclarations) {
                    if (innerTypeDeclaration.getNameAsString().equals(typeName)) {
                        targetTypeDeclaration = Optional.of(innerTypeDeclaration);
                        break;
                    } else {
                        newInnerTypeDeclarations.addAll((
                                List<TypeDeclaration>) innerTypeDeclaration.getMembers().stream()
                                .filter(m -> ((BodyDeclaration) m).isTypeDeclaration())
                                .collect(Collectors.toList())
                        );
                    }
                }
                if (targetTypeDeclaration.isEmpty()) {
                    innerTypeDeclarations = newInnerTypeDeclarations;
                } else {
                    innerTypeDeclarations = new ArrayList<>();
                }
            }
            if (targetTypeDeclaration.isPresent()) {;
                typeDeclarations.add(targetTypeDeclaration.get());
            }
        }
        return typeDeclarations;
    }

    /**
     * Retrieve a class declaration from a compilation unit, given its name.
     * @param cu the compilation unit
     * @param name the name of the type declaration to find within the compilation unit
     * @return the type declaration found
     * @throws RuntimeException if the type declaration with name {@code name} is not found within the compilation
     * unit {@code cu}
     */
    public static TypeDeclaration<?> getClassOrInterface(CompilationUnit cu, String name) {
        try {
            List<ClassOrInterfaceDeclaration> classOrInterfaceList = cu.getLocalDeclarationFromClassname(name);
            if (!classOrInterfaceList.isEmpty()) {
                return classOrInterfaceList.get(0);
            }
        } catch (NoSuchElementException ignored) {
            // There are other alternatives to retrieve class, interface, etc., as below
        }
        Optional<ClassOrInterfaceDeclaration> classOrInterface = cu.getClassByName(name);
        if (classOrInterface.isPresent()) {
            return classOrInterface.get();
        }
        Optional<ClassOrInterfaceDeclaration> interface0 = cu.getInterfaceByName(name);
        if (interface0.isPresent()) {
            return interface0.get();
        }
        Optional<EnumDeclaration> enum0 = cu.getEnumByName(name);
        if (enum0.isPresent()) {
            return enum0.get();
        }
        Optional<AnnotationDeclaration> annotation = cu.getAnnotationDeclarationByName(name);
        if (annotation.isPresent()) {
            return annotation.get();
        }
        throw new RuntimeException("Could not find class, interface, enum or annotation '" + name + "' in compilation unit.");
    }

    /**
     * Returns the element of a resolved type. Recursively strips all array
     * variables. For example:
     *     Object[][] => Object
     *
     * @param resolvedType a type
     * @return the element type
     */
    public static ResolvedType getElementType(ResolvedType resolvedType) {
        while (resolvedType.isArray()) {
            resolvedType = resolvedType.asArrayType().getComponentType();
        }
        return resolvedType;
    }

    /**
     * Gets all type parameters of a given method as declared in source code.
     * @param methodUsage the method in the form of {@link MethodUsage}
     * @return a list of string representing the type of all the parameters of the method
     */
    private static List<String> getTypeParameters(MethodUsage methodUsage) {
        return methodUsage.getDeclaration().getTypeParameters()
                .stream()
                .map(ResolvedTypeParameterDeclaration::getName)
                .toList();
    }

    /**
     * Navigate the directories from the root of a given repository and find all the paths that matches
     * the common patterns identifying Java projects (both to source and test files) within it.
     * @param rootPath the path to a Java repository (containing a single or multiple Java projects) or to a container of
     *                 Java libraries (jars or source code)
     * @return a list of paths to the source code and the test code of the Java projects found within
     * the given repository.
     */
    private static List<Path> findPotentialProjectsRoots(Path rootPath) {
        // Define the list of potential projects roots and initialize it with an empty list
        List<Path> potentialProjectsRoots = new ArrayList<>();

        if (rootPath.endsWith(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())) {
            return FilesUtils.listDirectories(rootPath);
        }

        // Walk the repository root path and find potential projects roots
        // Filter the paths contain the common patterns identifying Java projects
        // Collect the paths to the source code and the test code of the Java projects found
        try {
            potentialProjectsRoots = Files.walk(rootPath)
                    .filter(Files::isDirectory)
                    .map(path -> {
                        if(Files.exists(path.resolve("src/main/java"))) {
                            return path.resolve("src/main/java");
                        } else if (Files.exists(path.resolve("src/java/main"))) {
                            return path.resolve("src/java/main");
                        } else if (Files.exists(path.resolve("src/java"))) {
                            return path.resolve("src/java");
                        } else if (Files.exists(path.resolve("src/source"))) {
                            return path.resolve("src/source");
                        } else if (Files.exists(path.resolve("src"))) {
                            return path.resolve("src");
                        } else if (Files.exists(path.resolve("code"))) {
                            return path.resolve("code");
                        } else if (Files.exists(path.resolve("source"))) {
                            return path.resolve("source");
                        } else if (Files.exists(path.resolve("java"))) {
                            return path.resolve("java");
                        } else if (Files.exists(path.resolve("src/test/java"))) {
                            return path.resolve("src/test/java");
                        } else if (Files.exists(path.resolve("src/tests/java"))) {
                            return path.resolve("src/tests/java");
                        } else if (Files.exists(path.resolve("src/java/test"))) {
                            return path.resolve("src/java/test");
                        } else if (Files.exists(path.resolve("src/test"))) {
                            return path.resolve("src/test");
                        } else if (Files.exists(path.resolve("test/java"))) {
                            return path.resolve("test/java");
                        } else if (Files.exists(path.resolve("test"))) {
                            return path.resolve("test");
                        } else if (Files.exists(path.resolve("unittest"))) {
                            return path.resolve("unittest");
                        }
                        return null;
                    })
                    .filter(path -> path != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error while trying to find potential projects roots in repository path {}: ", rootPath, e);
        }
        potentialProjectsRoots.add(rootPath);
        // Return the list of potential projects roots found within the repository
        return potentialProjectsRoots;
    }

    /**
     * Get modifiers as a list of strings
     *
     * @param modifiers The original list of modifiers
     * @return A list of strings representing the modifiers
     * */
    public static List<String> getModifiersAsStrings(List<Modifier> modifiers) {
        return modifiers.stream().map(m -> m.toString().trim().toLowerCase()).collect(Collectors.toList());
    }

    /**
     * Get annotations as a list of strings
     *
     * @param annotations The original list of annotations
     * @return A list of strings representing the annotations
     * */
    public static List<String> getAnnotationsAsStrings(List<AnnotationExpr> annotations) {
        return annotations.stream().map(a -> a.toString().trim()).collect(Collectors.toList());
    }

    /**
     * Process the callable expression return the corresponding {@link CallableDeclaration}
     * @param typeDeclaration the type declaration corresponding to the class where to search the definition of the method
     *                        or constructor (i.e. the callable expression) defined
     * @param callExpr the callable expression to resolve
     * @param exprType the type of the callable expression (i.e. method or constructor)
     * @return the corresponding {@link CallableDeclaration} of the callable expression, if found. Otherwise, return null.
     * @throws MultipleCandidatesException if multiple candidates are found for the callable expression and the method
     *                                     or constructor cannot be resolved uniquely
     * @throws CandidateCallableDeclarationNotFoundException if the method or constructor corresponding to the callable
     *                                                       expression cannot be found and no candidates are available
     */
    public static CallableDeclaration resolveCallExpression(TypeDeclaration typeDeclaration, Expression callExpr, CallableExprType exprType) throws MultipleCandidatesException, CandidateCallableDeclarationNotFoundException {
        // Define the focal method. Initially null.
        CallableDeclaration focalMethod = null;
        try {
            if (exprType == CallableExprType.METHOD) {
                // Try to resolve the method call expression to a method declaration
                MethodCallExpr methodCallExpr = callExpr.asMethodCallExpr();
                Node resolvedCallExpr = methodCallExpr.resolve().toAst().orElseThrow(() -> new UnsolvedSymbolException(methodCallExpr.getNameAsString()));
                if (resolvedCallExpr instanceof MethodDeclaration) {
                    focalMethod = (MethodDeclaration) resolvedCallExpr;
                    // Retrieve the method within the type declaration to avoid to lose the symbol solver functionality
                    for (MethodDeclaration m : (List<MethodDeclaration>) typeDeclaration.getMethods()) {
                        if (m.getDeclarationAsString(true, true, true).equals(focalMethod.getDeclarationAsString(true, true, true))) {
                            return m;
                        }
                    }
                    //logger.error("Resolved method {} not found in type declaration {}", focalMethod.getNameAsString(), typeDeclaration.getNameAsString());
                    return focalMethod;
                }
            } else if (exprType == CallableExprType.CONSTRUCTOR) {
                // Try to resolve the constructor call expression to a constructor declaration
                ObjectCreationExpr constructorCallExpr = callExpr.asObjectCreationExpr();
                focalMethod = (ConstructorDeclaration) constructorCallExpr.resolve().toAst().orElseThrow(() -> new UnsolvedSymbolException(constructorCallExpr.getTypeAsString()));
                // Retrieve the method within the type declaration to avoid to lose the symbol solver functionality
                for (ConstructorDeclaration c : (List<ConstructorDeclaration>) typeDeclaration.getConstructors()) {
                    if (c.getDeclarationAsString(true, true, true).equals(focalMethod.getDeclarationAsString(true, true, true))) {
                        return c;
                    }
                }
                //logger.error("Resolved constructor {} not found in type declaration {}", focalMethod.getNameAsString(), typeDeclaration.getNameAsString());
                return focalMethod;
            }
        } catch (UnsolvedSymbolException e) {
            // The method call expression could not be resolved to a method declaration by
            // Java Symbol Solver
            focalMethod = null;
        }
        // If the method call expression could not be resolved to a method declaration by Java
        // Symbol Solver, try to find the method declaration by comparing the method call expression
        // with the list of methods in the class file (analyzing the signature and the number and types
        // of the parameters).
        if (focalMethod == null) {
            return searchCandidateCallableDeclaration(typeDeclaration, callExpr, exprType);
        }
        return focalMethod;
    }

    /**
     * Search the method declaration corresponding to a given callable expression, by comparing the method call expression
     * with the list of methods in the class file where the callable declaration is supposed to be defined (analyzing the
     * signature and the number and types of the parameters).
     * The method is useful when is not possible to resolve the method call expression to a method declaration by Java Symbol
     * Solver and it is necessary to apply heuristics to find the callable declaration.
     * @param typeDeclaration the type declaration corresponding to the class where to search the definition of the method
     *                        or constructor (i.e. the callable expression) defined
     * @param lastCallExpr the callable expression to resolve
     * @param exprType the type of the callable expression (i.e. method or constructor)
     * @return the corresponding {@link CallableDeclaration} of the callable expression, if found. Otherwise, return null.
     * @throws MultipleCandidatesException if multiple candidates are found for the callable expression and the method
     *                                    or constructor cannot be resolved uniquely
     * @throws CandidateCallableDeclarationNotFoundException if the method or constructor corresponding to the callable
     *                                                       expression cannot be found and no candidates are available
     */
    public static CallableDeclaration searchCandidateCallableDeclaration(TypeDeclaration typeDeclaration, Expression lastCallExpr, CallableExprType exprType) throws MultipleCandidatesException, CandidateCallableDeclarationNotFoundException {
        // Define the list of all the callable methods/constructors in the class file. Initially empty.
        List<? extends CallableDeclaration<?>> classCallableList = new ArrayList<>();
        // Define the name of the focal method. Initially null.
        String focalMethodName = null;
        // Define the list of arguments of the focal method. Initially empty.
        NodeList<Expression> focalMethodArgs = new NodeList<>();
        // Define the focal method. Initially null.
        CallableDeclaration focalMethod = null;
        // Define a list of candidate methods/constructors (methods/constructors with the same name and number of parameters).
        // Initially empty.
        List<CallableDeclaration> candidatesCallables = new ArrayList<>();

        if (exprType == CallableExprType.METHOD) {
            classCallableList = typeDeclaration.getMethods();
            focalMethodName = ((MethodCallExpr) lastCallExpr).getNameAsString();
            focalMethodArgs = ((MethodCallExpr) lastCallExpr).getArguments();
        } else if (exprType == CallableExprType.CONSTRUCTOR) {
            classCallableList = typeDeclaration.getConstructors();
            focalMethodName = ((ObjectCreationExpr) lastCallExpr).getTypeAsString();
            focalMethodArgs = ((ObjectCreationExpr) lastCallExpr).getArguments();
        }
        // Iterate over the list of methods/constructors in the class file
        for (CallableDeclaration callable : classCallableList) {
            // Check if the method/constructor name is the same
            if (callable.getNameAsString().equals(focalMethodName)) {
                // Check if the number of parameters is the same
                if (callable.getParameters().size() == focalMethodArgs.size()) {
                    // Add method/constructor to candidates if the previous conditions are satisfied
                    candidatesCallables.add(callable);
                } else if (callable.getParameters().size() > 0) {
                    // Check if the number of parameters is the same, but the method/constructor is a varargs
                    Parameter lastCandidateParam = (Parameter) callable.getParameters().getLast().get();
                    if (lastCandidateParam.isVarArgs() && callable.getParameters().size() <= focalMethodArgs.size()) {
                        candidatesCallables.add(callable);
                    }
                }
            }
        }
        // If no candidate methods are found, throw an exception (focal method not found)
        if (candidatesCallables.size() == 0) {
            return focalMethod;
        } else if (candidatesCallables.size() == 1) {
            // If only one candidate method/constructor is found, set the focal method to the candidate method/constructor
            focalMethod = candidatesCallables.get(0);
        } else {
            List<ResolvedType> focalMethodResolvedTypes = focalMethodArgs.stream().map(a -> a.calculateResolvedType()).collect(Collectors.toList());
            focalMethod = matchCallableDeclaration(focalMethodResolvedTypes, candidatesCallables);
        }
        return focalMethod;
    }


    /**
     * Search the resolved method declaration corresponding to a given callable expression, by comparing the method call expression
     * with the list of methods in the resolved class file where the callable declaration is supposed to be defined (analyzing the
     * signature and the number and types of the parameters).
     * The method is useful when is not possible to resolve the method call expression to a method declaration by Java Symbol
     * Solver, and it is necessary to apply heuristics to find the callable declaration.
     * @param resolvedReferenceType the resolved reference declaration corresponding to the class where to search the
     *                              definition of the method or constructor (i.e. the callable expression) defined
     * @param methodCallExpr the callable expression to resolve
     * @return the corresponding {@link ResolvedMethodDeclaration} of the callable expression, if found. Otherwise, return null.
     * @throws MultipleCandidatesException if multiple candidates are found for the callable expression and the method
     *                                    or constructor cannot be resolved uniquely
     * @throws CandidateCallableDeclarationNotFoundException if the method or constructor corresponding to the callable
     *                                                       expression cannot be found and no candidates are available
     */
    public static ResolvedMethodDeclaration searchCandidateResolvedMethodDeclaration(ResolvedReferenceType resolvedReferenceType, MethodCallExpr methodCallExpr) throws MultipleCandidatesException, CandidateCallableDeclarationNotFoundException {
        // Define the list of arguments of the focal method. Initially empty.
        NodeList<Expression> focalMethodArgs = new NodeList<>();
        // Define the focal method. Initially null.
        ResolvedMethodDeclaration focalMethod = null;
        // Define a list of candidate methods/constructors (methods/constructors with the same name and number of parameters).
        // Initially empty.
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();

        List<ResolvedMethodDeclaration> classMethodsList = resolvedReferenceType.getAllMethods();
        String focalMethodName = methodCallExpr.getNameAsString();
        focalMethodArgs = methodCallExpr.getArguments();
        // Iterate over the list of methods/constructors in the class file
        for (ResolvedMethodDeclaration m : classMethodsList) {
            // Check if the method/constructor name is the same
            if (m.getName().equals(focalMethodName)) {
                // Check if the number of parameters is the same
                if (m.getNumberOfParams() == focalMethodArgs.size()) {
                    // Add method/constructor to candidates if the previous conditions are satisfied
                    candidates.add(m);
                } else if (m.getNumberOfParams() > 0) {
                    // Check if the number of parameters is the same, but the method/constructor is a varargs
                    ResolvedParameterDeclaration lastCandidateParam = m.getParam(m.getNumberOfParams() - 1);
                    if (lastCandidateParam.isVariadic() && m.getNumberOfParams() <= focalMethodArgs.size()) {
                        candidates.add(m);
                    }
                }
            }
        }
        // If no candidate methods are found, throw an exception (focal method not found)
        if (candidates.size() == 0) {
            return focalMethod;
        } else if (candidates.size() == 1) {
            // If only one candidate method/constructor is found, set the focal method to the candidate method/constructor
            focalMethod = candidates.get(0);
        } else {
            List<ResolvedType> focalMethodResolvedTypes = focalMethodArgs.stream().map(a -> a.calculateResolvedType()).collect(Collectors.toList());
            focalMethod = matchResolvedMethodDeclaration(focalMethodResolvedTypes, candidates);
        }
        return focalMethod;
    }


    /**
     * Match the resolved method declaration with the most similar signature to the given method call expression. The method supports
     * the searchCandidateResolvedMethodDeclaration method in finding the most similar candidate method/constructor resolving the
     * method call expression, comparing the signature and the number and types of the parameters with the list of candidate
     * methods/constructors in the resolved class.
     *
     * @param focalMethodArgs the list of types of the arguments of the callable expression
     * @param candidates the list of candidate methods/constructors in the class file
     * @return the most similar candidate method/constructor to the method call expression in the class file
     * @throws CandidateCallableDeclarationNotFoundException if no candidate method/constructor fully match the signature
     *                                                       of the callable expression
     * @throws MultipleCandidatesException if multiple candidate methods/constructors are found, and it is impossible to
     *                                     discern the focal method
     */
    private static ResolvedMethodDeclaration matchResolvedMethodDeclaration(List<ResolvedType> focalMethodArgs, List<ResolvedMethodDeclaration> candidates) throws CandidateCallableDeclarationNotFoundException, MultipleCandidatesException {
        // Define the focal method.
        ResolvedMethodDeclaration focalMethod;
        // Initialize the number of parameters in common to -1 (no parameters in common)
        int bestParamsTypesInCommon = -1;
        // Initialize the boolean flag multipleCandidates to false (only one candidate method is
        // the most similar to the method call expression)
        boolean multipleCandidates = false;
        // Initialize the most similar candidate method to null
        ResolvedMethodDeclaration mostSimilar = null;
        // Iterate over the list of candidate methods/constructors
        for (ResolvedMethodDeclaration candidate : candidates) {
            // Initialize the number of parameters in common with the current candidate to 0
            int paramsTypesInCommon = 0;
            // Iterate over the list of parameters of the current candidate method/constructor
            for (int i = 0; i < focalMethodArgs.size(); i++) {
                // Get the i-th parameter of the current candidate method/constructor (or the last parameter if the
                // number of parameters of the candidate method/constructor is less than the number of parameters of
                // the method call expression because the last param of the candidate method/constructor is a varargs)
                ResolvedParameterDeclaration param = i < candidate.getNumberOfParams() ? candidate.getParam(i) : candidate.getLastParam();
                // Get the type of the i-th parameter of the focal method call in the test
                ResolvedType focalMethodArg = focalMethodArgs.get(i);
                String focalMethodTypeName = focalMethodArgs.get(i).describe();
                // Perform preliminary checks to avoid comparing incompatible types
                if (!param.getType().describe().equals("java.lang.Object") && !param.getType().isTypeVariable()) {
                    if (focalMethodArg.isArray() && !param.getType().isArray()) {
                        continue;
                    }
                    if (!focalMethodArg.isArray() && param.getType().isArray()) {
                        continue;
                    }
                    if (focalMethodArg.describe().startsWith("java.util.List") && !param.getType().describe().startsWith("List")) {
                        continue;
                    }
                    if (!focalMethodArg.describe().startsWith("java.util.List") && param.getType().describe().startsWith("List")) {
                        continue;
                    }
                    if (focalMethodArg.isArray() && (param.getType().isArray())) {
                        if (focalMethodArg.asArrayType().arrayLevel() != param.getType().asArrayType().arrayLevel()) {
                            continue;
                        }
                    }
                    // Get the type of the i-th parameter of the current candidate method, as a string
                    String paramTypeName = param.getType().describe();
                    boolean matchFound = false;
                    // Compare the parameters type names
                    if (focalMethodTypeName.contains(paramTypeName.replaceAll("<.*?>|List|\\[\\]|<|>", ""))) {
                        matchFound = true;
                    } else {
                        if (param.getType().isReferenceType() && focalMethodArg.isReferenceType()) {
                            List<String> paramAncestors = param.getType().asReferenceType().getAllAncestors().stream().map(ResolvedReferenceType::describe).collect(Collectors.toList());
                            for (String ancestor : paramAncestors) {
                                if (focalMethodTypeName.contains(ancestor.replaceAll("<.*?>|List|\\[\\]|<|>", ""))) {
                                    paramTypeName = ancestor;
                                    matchFound = true;
                                    break;
                                }
                            }
                            if (!matchFound) {
                                List<String> focalAncestors = focalMethodArg.asReferenceType().getAllAncestors().stream().map(ResolvedReferenceType::describe).collect(Collectors.toList());
                                for (String ancestor : focalAncestors) {
                                    if (paramTypeName.contains(ancestor.replaceAll("<.*?>|List|\\[\\]|<|>", ""))) {
                                        focalMethodTypeName = ancestor;
                                        matchFound = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (matchFound) {
                        // Pattern to capture content within < and >
                        Pattern pattern = Pattern.compile("<(.*?)>");
                        Matcher matcherFocalMethod = pattern.matcher(focalMethodTypeName);
                        if (matcherFocalMethod.find()) {
                            String genericTypeFocalMethod = matcherFocalMethod.group(1); // Save the captured content
                            Matcher matcherParam = pattern.matcher(paramTypeName);

                            if (genericTypeFocalMethod.equals("?") || genericTypeFocalMethod.length() == 1) {
                                paramsTypesInCommon += 1;
                            } else {
                                if (matcherParam.find()) {
                                    String genericTypeParam = matcherParam.group(1);
                                    if (genericTypeParam.equals("?") || genericTypeParam.length() == 1 || genericTypeFocalMethod.equals(genericTypeParam)) {
                                        paramsTypesInCommon += 1;
                                    }
                                } else {
                                    paramsTypesInCommon += 1;
                                }
                            }
                        } else {
                            // If the type names corresponds, increment the number of parameters in common
                            paramsTypesInCommon += 1;
                        }
                    }
                } else {
                    // Any type inherits from Object. Increment the number of parameters in common
                    paramsTypesInCommon += 1;
                }
            }
            // If the number of parameters in common with the current candidate method/constructor is
            // greater or equal to the best number of parameters in common found so far, update the best
            // number of parameters in common and assign the current candidate method/constructor to the
            // most similar candidate method/constructor
            if (paramsTypesInCommon >= bestParamsTypesInCommon) {
                if (paramsTypesInCommon == bestParamsTypesInCommon) {
                    if (mostSimilar.isAbstract() && !candidate.isAbstract()) {
                        mostSimilar = candidate;
                    } else if (candidate.getQualifiedName().startsWith("java.util.List") && mostSimilar.getQualifiedName().startsWith("java.util.Collection")) {
                        mostSimilar = candidate;
                    } else if (mostSimilar.getQualifiedName().startsWith("java.util.List") && candidate.getQualifiedName().startsWith("java.util.Collection")) {
                        continue;
                    } else if (mostSimilar.isAbstract() && candidate.isAbstract()) {
                        multipleCandidates = true;
                    }
                } else {
                    bestParamsTypesInCommon = paramsTypesInCommon;
                    multipleCandidates = false;
                    mostSimilar = candidate;
                }
            }
        }
        // If only one candidate method/constructor is the most similar to the focal method call expression, set
        // the focal method to the most similar candidate method
        if (!(multipleCandidates || mostSimilar == null)) {
            focalMethod = mostSimilar;
            if (bestParamsTypesInCommon < focalMethodArgs.size()) {
                // If the number of parameters in common is less than the number of parameters of
                // the method call expression, log a warning
                throw new CandidateCallableDeclarationNotFoundException("[WARNING] - No candidate method fully match the signature of the callable expression. No focal method found.");
            }
        } else {
            if (multipleCandidates) {
                // Multiple candidates, impossible to discern
                throw new MultipleCandidatesException(String.format("[WARNING] - Multiple candidate methods/constructors found. Impossible to discern the focal method."));
            } else {
                // No candidate available
                throw new CandidateCallableDeclarationNotFoundException("[WARNING] - No candidate method fully match the signature of the callable expression. No focal method found.");
            }
        }
        return focalMethod;
    }


    /**
     * Match the callable declaration with the most similar signature to the given method call expression. The method supports
     * the searchCandidateCallableDeclaration method in finding the most similar candidate method/constructor resolving the
     * method call expression, comparing the signature and the number and types of the parameters with the list of candidate
     * methods/constructors in the class file.
     *
     * @param focalMethodArgs the list of types of the arguments of the callable expression
     * @param candidatesCallables the list of candidate methods/constructors in the class file
     * @return the most similar candidate method/constructor to the method call expression in the class file
     * @throws CandidateCallableDeclarationNotFoundException if no candidate method/constructor fully match the signature
     *                                                       of the callable expression
     * @throws MultipleCandidatesException if multiple candidate methods/constructors are found, and it is impossible to
     *                                     discern the focal method
     */
    private static CallableDeclaration matchCallableDeclaration(List<ResolvedType> focalMethodArgs, List<CallableDeclaration> candidatesCallables) throws CandidateCallableDeclarationNotFoundException, MultipleCandidatesException {
        // Define the focal method.
        CallableDeclaration focalMethod;
        // Initialize the number of parameters in common to -1 (no parameters in common)
        int bestParamsTypesInCommon = -1;
        // Initialize the boolean flag multipleCandidates to false (only one candidate method is
        // the most similar to the method call expression)
        boolean multipleCandidates = false;
        // Initialize the most similar candidate method to null
        CallableDeclaration mostSimilar = null;
        // Iterate over the list of candidate methods/constructors
        for (CallableDeclaration callable : candidatesCallables) {
            // Get the list of parameters of the candidate method/constructor
            NodeList<com.github.javaparser.ast.body.Parameter> paramsList = callable.getParameters();
            // Initialize the number of parameters in common with the current candidate to 0
            int paramsTypesInCommon = 0;
            // Iterate over the list of parameters of the current candidate method/constructor
            for (int i = 0; i < focalMethodArgs.size(); i++) {
                // Get the i-th parameter of the current candidate method/constructor or the last parameter if the
                // number of parameters of the current candidate method/constructor is less than the number of parameters
                // of the focal method call expression (this happens when the last parameter is a varargs)
                com.github.javaparser.ast.body.Parameter param = i < paramsList.size() ? paramsList.get(i) : paramsList.getLast().get();
                // Get the type of the i-th parameter of the focal method call in the test
                ResolvedType focalMethodArg = focalMethodArgs.get(i);
                String focalMethodTypeName = focalMethodArgs.get(i).describe();
                if (!param.getType().resolve().describe().equals("java.lang.Object") || !param.getType().resolve().isTypeVariable()) {
                    // Perform preliminary checks to avoid comparing incompatible types
                    if (focalMethodArg.isArray() && !(param.getType().isArrayType() || param.isVarArgs())) {
                        continue;
                    }
                    if (!focalMethodArg.isArray() && (param.getType().isArrayType())) {
                        continue;
                    }
                    if (focalMethodArg.describe().startsWith("java.util.List") && !param.getTypeAsString().startsWith("List")) {
                        continue;
                    }
                    if (!focalMethodArg.describe().startsWith("java.util.List") && param.getTypeAsString().startsWith("List")) {
                        continue;
                    }
                    if (focalMethodArg.isArray() && (param.getType().isArrayType())) {
                        //if (param.isVarArgs() && (focalMethodArg.asArrayType().arrayLevel() > 1)) {
                        //    continue;
                        //} else
                        if (!param.isVarArgs() && (focalMethodArg.asArrayType().arrayLevel() != param.getType().asArrayType().getArrayLevel())) {
                            continue;
                        }
                    }
                    // Get the type of the i-th parameter of the current candidate method, as a string
                    String paramTypeName = param.getTypeAsString();
                    // Compare the parameters type names
                    if (focalMethodTypeName.contains(paramTypeName.replaceAll("<.*?>|List|\\[\\]|<|>", ""))) {
                        // Pattern to capture content within < and >
                        Pattern pattern = Pattern.compile("<(.*?)>");
                        Matcher matcherFocalMethod = pattern.matcher(focalMethodTypeName);
                        if (matcherFocalMethod.find()) {
                            String genericTypeFocalMethod = matcherFocalMethod.group(1); // Save the captured content
                            Matcher matcherParam = pattern.matcher(paramTypeName);

                            if (genericTypeFocalMethod.equals("?") || genericTypeFocalMethod.length() == 1) {
                                paramsTypesInCommon += 1;
                            } else {
                                if (matcherParam.find()) {
                                    String genericTypeParam = matcherParam.group(1);
                                    if (genericTypeParam.equals("?") || genericTypeParam.length() == 1 || genericTypeFocalMethod.equals(genericTypeParam)) {
                                        paramsTypesInCommon += 1;
                                    }
                                } else {
                                    paramsTypesInCommon += 1;
                                }
                            }
                        } else {
                            // If the type names corresponds, increment the number of parameters in common
                            paramsTypesInCommon += 1;
                        }
                    }
                } else {
                    // Any type inherits from Object. Increment the number of parameters in common
                    paramsTypesInCommon += 1;
                }
            }
            // If the number of parameters in common with the current candidate method/constructor is
            // greater or equal to the best number of parameters in common found so far, update the best
            // number of parameters in common and assign the current candidate method/constructor to the
            // most similar candidate method/constructor
            if (paramsTypesInCommon >= bestParamsTypesInCommon) {
                if (paramsTypesInCommon == bestParamsTypesInCommon) {
                    if ((mostSimilar.isAbstract() && !callable.isAbstract()) || (mostSimilar.isPrivate() && callable.isPublic())) {
                        mostSimilar = callable;
                    } else if (mostSimilar.isAbstract() && callable.isAbstract()) {
                        multipleCandidates = true;
                    }
                } else {
                    bestParamsTypesInCommon = paramsTypesInCommon;
                    multipleCandidates = false;
                    mostSimilar = callable;
                }
            }
        }
        // If only one candidate method/constructor is the most similar to the focal method call expression, set
        // the focal method to the most similar candidate method
        if (!(multipleCandidates || mostSimilar == null)) {
            focalMethod = mostSimilar;
            if (bestParamsTypesInCommon < focalMethodArgs.size()) {
                // If the number of parameters in common is less than the number of parameters of
                // the method call expression, log a warning
                throw new CandidateCallableDeclarationNotFoundException("[WARNING] - No candidate method fully match the signature of the callable expression. No focal method found.");
            }
        } else {
            if (multipleCandidates) {
                // Multiple candidates, impossible to discern
                throw new MultipleCandidatesException(String.format("[WARNING] - Multiple candidate methods/constructors found. Impossible to discern the focal method."));
            } else {
                // No candidate available
                throw new CandidateCallableDeclarationNotFoundException("[WARNING] - No candidate method fully match the signature of the callable expression. No focal method found.");
            }
        }
        return focalMethod;
    }

    /**
     * Extract the body of a method from a {@link MethodUsage} object.
     * The body is extracted from the string representation of the method usage, starting from the signature of the method.
     * @param methodUsage the method usage from which to extract the body
     * @return an Optional containing the body of the method, if found. Otherwise, an empty Optional.
     */
    public static Optional<String> getBodyFromMethodUsage(MethodUsage methodUsage) {
        Optional<String> body = Optional.empty();
        int signatureIdx = methodUsage.toString().indexOf(methodUsage.getSignature());
        int bodyIdx = methodUsage.toString().substring(signatureIdx + methodUsage.getSignature().length()).indexOf("{");
        if (bodyIdx != -1) {
            // Extract the body of the method from the signature index
            String bodyString = methodUsage.toString().substring(signatureIdx + methodUsage.getSignature().length() + bodyIdx);
            return Optional.of(bodyString);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Extract the body of a method from a {@link ResolvedConstructorDeclaration} object.
     * The body is extracted from the string representation of the resolved constructor declaration, starting from the
     * signature of the constructor.
     * @param resolvedConstructorDeclaration the resolved constructor declaration from which to extract the body
     * @return an Optional containing the body of the constructor, if found. Otherwise, an empty Optional.
     */
    public static Optional<String> getBodyFromResolvedConstructorDeclaration(ResolvedConstructorDeclaration resolvedConstructorDeclaration) {
        Optional<String> body = Optional.empty();
        int signatureIdx = resolvedConstructorDeclaration.toString().indexOf(resolvedConstructorDeclaration.getSignature());
        int bodyIdx = resolvedConstructorDeclaration.toString().substring(signatureIdx + resolvedConstructorDeclaration.getSignature().length()).indexOf("{");
        if (bodyIdx != -1) {
            // Extract the body of the method from the signature index
            String bodyString = resolvedConstructorDeclaration.toString().substring(signatureIdx + resolvedConstructorDeclaration.getSignature().length() + bodyIdx);
            return Optional.of(bodyString);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Search the {@link MethodUsage} corresponding to a given method call expression, by comparing the method call expression
     * with the list of methods in the class file where the method call expression is supposed to be defined (analyzing the
     * signature and the number and types of the parameters).
     * The method is useful when is not possible to resolve the method call expression to a method declaration by Java Symbol
     * Solver, and it is necessary to apply heuristics to find the method in the form of {@link MethodUsage}.
     * @param typeDeclaration the type declaration corresponding to the class where to search the definition of the method
     *                        defined in the method call expression
     * @param expr the method call expression to resolve
     * @return the corresponding {@link MethodUsage} of the method call expression, if found. Otherwise, return null.
     * @throws CandidateCallableMethodUsageNotFoundException if the method corresponding to the method call expression
     *                                                       cannot be found and no candidates are available
     * @throws MultipleCandidatesException if multiple candidates are found for the method call expression and the method
     *                                     cannot be resolved uniquely
     */
    public static MethodUsage searchCandidateMethodUsage(TypeDeclaration typeDeclaration, Expression expr) throws CandidateCallableMethodUsageNotFoundException, MultipleCandidatesException {
        try {
            return iterateOverCandidatesMethodUsage(typeDeclaration.resolve().getAllMethods(), expr.asMethodCallExpr());
        } catch (CandidateCallableMethodUsageNotFoundException | UnsolvedSymbolException | IllegalStateException e) {
            return null;
        }
    }

    private static MethodUsage iterateOverCandidatesMethodUsage(Set<MethodUsage> methods, MethodCallExpr methodCallExpr) throws CandidateCallableMethodUsageNotFoundException, MultipleCandidatesException {
        List<MethodUsage> methodNotConverted = new ArrayList<>();
        List<MethodDeclaration> methodConverted = new ArrayList<>();
        // Define the focal method. Initially null.
        MethodUsage focalMethod = null;
        // Define a list of candidate methods/constructors (methods/constructors with the same name and number of parameters).
        // Initially empty.
        List<MethodUsage> candidatesMethodUsage = new ArrayList<>();
        // Get the name of the focal method
        String focalMethodName = methodCallExpr.getNameAsString();
        // Get the list of arguments of the focal method
        NodeList<Expression> focalMethodArgs = methodCallExpr.getArguments();

        for(MethodUsage m : methods) {
            try {
                methodConverted.add((MethodDeclaration) m.getDeclaration().toAst().get());
            } catch(Exception e) {
                methodNotConverted.add(m);
            }
        }

        // Iterate over the list of methods/constructors in the class file
        for (MethodUsage methodUsage : methodNotConverted) {
            // Check if the method/constructor name is the same
            if (methodUsage.getName().equals(focalMethodName)) {
                // Check if the number of parameters is the same
                if (methodUsage.getParamTypes().size() == focalMethodArgs.size()) {
                    // Add method/constructor to candidates if the previous conditions are satisfied
                    candidatesMethodUsage.add(methodUsage);
                } else if (methodUsage.getParamTypes().size() > 0) {
                    // Check if the number of parameters is the same, but the method/constructor is a varargs
                    ResolvedType lastCandidateParam = methodUsage.getParamTypes().get(methodUsage.getParamTypes().size() - 1);
                    if (lastCandidateParam.isArray() && methodUsage.getParamTypes().size() <= focalMethodArgs.size()) {
                        candidatesMethodUsage.add(methodUsage);
                    }
                }
            }
        }

        if (candidatesMethodUsage.size() == 0) {
            throw new CandidateCallableMethodUsageNotFoundException("[WARNING] - No candidate method usage found in the original class, analyzing both the name and the number of parameters. Focal method not found. ");
        } else if (candidatesMethodUsage.size() == 1) {
            // If only one candidate method/constructor is found, set the focal method to the candidate method/constructor
            focalMethod = candidatesMethodUsage.get(0);
        } else {
            // If multiple candidate methods are found, try to discern the focal method by analyzing
            // the types of the parameters of the focal method call expression and the parameters of the
            // candidate methods/constructors.
            // Initialize the number of parameters in common to -1 (no parameters in common)
            int bestParamsTypesInCommon = -1;
            // Initialize the boolean flag multipleCandidates to false (only one candidate method/constructor is
            // the most similar to the method call expression)
            boolean multipleCandidates = false;
            // Initialize the most similar candidate method/constructor to null
            MethodUsage mostSimilar = null;
            // Iterate over the list of candidate methods/constructors
            for (MethodUsage candidate : candidatesMethodUsage) {
                // Get the list of parameters of the candidate method/constructor
                List<ResolvedType> paramsTypeList = candidate.getParamTypes();
                // Initialize the number of parameters in common with the current candidate to 0
                int paramsTypesInCommon = 0;
                // Iterate over the list of parameters of the current candidate method/constructor
                for (int i = 0; i < focalMethodArgs.size(); i++) {
                    // Get the i-th parameter of the current candidate method/constructor or the last parameter if the
                    // number of parameters of the current candidate method/constructor is less than the number of parameters
                    // of the focal method call expression (this happens when the last parameter is a varargs)
                    ResolvedType paramType = i < paramsTypeList.size() ? paramsTypeList.get(i) : paramsTypeList.get(paramsTypeList.size() - 1);
                    // Get the type of the i-th parameter of the focal method call in the test
                    // prefix, as a string
                    String focalMethodTypeName = focalMethodArgs.get(i).calculateResolvedType().describe();
                    // Get the type of the i-th parameter of the current candidate method, as a string
                    String paramTypeName = paramType.describe();
                    // Compare the parameters type names
                    if (focalMethodTypeName.endsWith(paramTypeName) || paramTypeName.endsWith("Object") || paramType.isTypeVariable()) {
                        // If the type names corresponds or the candidate param type is Object,
                        // increment the number of parameters in common (any type inherits from Object)
                        paramsTypesInCommon += 1;
                    }
                }
                // If the number of parameters in common with the current candidate method/constructor is
                // greater or equal to the best number of parameters in common found so far, update the best
                // number of parameters in common and assign the current candidate method/constructor to the
                // most similar candidate method/constructor
                if (paramsTypesInCommon >= bestParamsTypesInCommon) {
                    if (paramsTypesInCommon == bestParamsTypesInCommon) {
                        if (mostSimilar.getDeclaration().isAbstract() && !candidate.getDeclaration().isAbstract()) {
                            mostSimilar = candidate;
                        } else if (mostSimilar.getDeclaration().isAbstract() && candidate.getDeclaration().isAbstract()) {
                            multipleCandidates = true;
                        }
                    } else {
                        bestParamsTypesInCommon = paramsTypesInCommon;
                        multipleCandidates = false;
                        mostSimilar = candidate;
                    }
                }
            }
            // If only one candidate method/constructor is the most similar to the focal method call expression, set
            // the focal method to the most similar candidate method
            if (!(multipleCandidates || mostSimilar == null)) {
                focalMethod = mostSimilar;
                if (bestParamsTypesInCommon < focalMethodArgs.size()) {
                    // If the number of parameters in common is less than the number of parameters of
                    // the method call expression, log a warning
                    throw new CandidateCallableMethodUsageNotFoundException("[WARNING] - No candidate method fully match the signature of the callable expression. No focal method found.");
                }
            } else {
                // Multiple candidates, impossible to discern
                throw new MultipleCandidatesException("[WARNING] - Multiple candidate methods/constructors found. Impossible to discern the focal method.");
            }
        }
        return focalMethod;
    }


    /**
     * Search the {@link MethodUsage} corresponding to a given method call expression, by comparing the method call expression
     * with the list of methods in the {@link ResolvedReferenceType} where the method call expression is supposed to be defined (analyzing the
     * signature and the number and types of the parameters).
     * The method is useful when is not possible to resolve the method call expression to a method declaration by Java Symbol
     * Solver, and it is necessary to apply heuristics to find the method in the form of {@link MethodUsage}.
     *
     * @param resolvedReferenceType the resolved reference type corresponding to the class of the external library where
     *                              to search the definition of the method defined in the method call expression
     * @param expr the method call expression to resolve
     * @return the corresponding {@link MethodUsage} of the method call expression, if found. Otherwise, return null.
     * @throws CandidateCallableMethodUsageNotFoundException if the method corresponding to the method call expression
     *                                                       cannot be found and no candidates are available
     * @throws MultipleCandidatesException if multiple candidates are found for the method call expression and the method
     *                                     cannot be resolved uniquely
     */
    public static MethodUsage searchCandidateMethodUsage(ResolvedReferenceType resolvedReferenceType, Expression expr) throws CandidateCallableMethodUsageNotFoundException, MultipleCandidatesException {
        return iterateOverCandidatesMethodUsage(resolvedReferenceType.getDeclaredMethods(), expr.asMethodCallExpr());
    }

    /**
     * Match the method declaration with the most similar signature to the given method in the form of {@link MethodUsage}.
     * @param referenceMethodUsage the method usage to resolve
     * @param methodDeclarationList the list of methods in the reference class where to search the corresponding method declaration
     * @return the corresponding {@link MethodDeclaration} of the method usage, if found.
     * @throws CandidateCallableDeclarationNotFoundException if the method corresponding to the method usage
     *                                                       cannot be found and no candidates are available
     * @throws MultipleCandidatesException if multiple candidates are found for the method usage and the method
     *                                     cannot be resolved uniquely
     */
    public static CallableDeclaration matchMethodUsageWithMethodDeclaration(MethodUsage referenceMethodUsage, List<MethodDeclaration> methodDeclarationList) throws  CandidateCallableDeclarationNotFoundException, MultipleCandidatesException {
        String referenceMethodUsageName = referenceMethodUsage.getName();
        List<ResolvedType> referenceMehodUsageArgs = referenceMethodUsage.getParamTypes();
        // Define the focal method. Initially null.
        CallableDeclaration focalMethod = null;
        // Define a list of candidate methods (methods with the same name and number of parameters).
        // Initially empty.
        List<CallableDeclaration> candidatesMethods = new ArrayList<>();
        // Iterate over the list of methods/constructors in the class file
        for (MethodDeclaration method : methodDeclarationList) {
            // Check if the method/constructor name is the same
            if (method.getNameAsString().equals(referenceMethodUsageName)) {
                // Check if the number of parameters is the same
                if (method.getParameters().size() == referenceMehodUsageArgs.size()) {
                    // Add method/constructor to candidates if the previous conditions are satisfied
                    candidatesMethods.add(method);
                }
            }
        }
        // If no candidate methods are found, throw an exception (focal method not found)
        if (candidatesMethods.size() == 0) {
            return focalMethod;
        } else if (candidatesMethods.size() == 1) {
            // If only one candidate method/constructor is found, set the focal method to the candidate method/constructor
            focalMethod = candidatesMethods.get(0);
        } else {
            focalMethod = matchCallableDeclaration(referenceMethodUsage.getParamTypes(), candidatesMethods);
        }
        return focalMethod;
    }

    /**
     * Parse the expression and return a list of all the occurrences of the method calls and object creations defined in the expression.
     * @param expr the expression to parse
     * @param removeAssertion filter out potential expressions referring to assertions
     * @return a list of pairs of expressions and the type of the expression (method or constructor)
     * @throws UnrecognizedExprException if the expression cannot be parsed
     */
    public static List<Pair<Expression,CallableExprType>> parseExpression(Expression expr, boolean removeAssertion) throws UnrecognizedExprException {
        List<Pair<Expression,CallableExprType>> methodCallsAndObjectCreationsList = new ArrayList<>();

        methodCallsAndObjectCreationsList.addAll(expr.findAll(MethodCallExpr.class).stream().map(e -> new Pair<>((Expression) e, CallableExprType.METHOD)).toList());
        if (removeAssertion) {
            methodCallsAndObjectCreationsList = methodCallsAndObjectCreationsList.stream().filter(p -> !JUnitAssertionType.isJUnitAssertion(p.getValue0().asMethodCallExpr().getNameAsString())).collect(Collectors.toList());
        }
        methodCallsAndObjectCreationsList.addAll(expr.findAll(ObjectCreationExpr.class).stream().map(e -> new Pair<>((Expression) e, CallableExprType.CONSTRUCTOR)).toList());
        return methodCallsAndObjectCreationsList;
    }

    /**
     * Retrieve all the classes extended by the compilation unit passed to the method.
     *
     * @param cu The compilation unit to process
     * @return the list of classes extended by the class represented by the compilation unit.
     */
    private static List<ClassOrInterfaceDeclaration> getExtendedClassesFromCompilationUnit(CompilationUnit cu) {
        List<ClassOrInterfaceDeclaration> extendedClasses = new ArrayList<>();

        // Get the primary type (the main class)
        Optional<ClassOrInterfaceDeclaration> primaryType = cu.getPrimaryType()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast);

        // Check if primary type is present and it extends any other classes
        if (primaryType.isPresent()) {
            ClassOrInterfaceDeclaration classDeclaration = primaryType.get();
            if (classDeclaration.getExtendedTypes().isNonEmpty()) {
                for (ClassOrInterfaceType extendedType : classDeclaration.getExtendedTypes()) {
                    // TODO: Check the extended classes from the imports
                }
            }
        }
        return extendedClasses;
    }

    /**
     * Retrieve the last statement in a method declaration.
     *
     * @param callableDeclaration the method declaration to process
     * @return the last statement in the method declaration
     */
    public static Statement getLastStatementInMethodDeclaration(MethodDeclaration callableDeclaration) {
        List<Statement> statements = callableDeclaration.getBody().get().findAll(Statement.class);
        return statements.get(statements.size() - 1);
    }

    /**
     * Visit all the statements within a statement (it could be a block of statements or an expression statement, for example)
     * to find the last occurrence of a given statement type. The method returns the statement itself, if it is the last
     * occurrence of the given type
     *
     * @param stmt the statement to visit
     * @param type the type of statement to find
     * @return the last occurrence of the statement type. If the statement is the last occurrence of that type, returns
     * itself. If the statement is not found, return an empty optional.
     */
    public static Optional<Statement> getLastStatementTypeOccurrence(Statement stmt, Class type) {
        Optional<Statement> lastOccurrence = StmtVisitorHelper.getLastStatementOccurrence(stmt, type);
        return lastOccurrence;
    }

    /**
     * Collects the names of a set of parameters {@link Parameter} and returns the list as a set.
     * @param existingParameters The set of parameters to process
     * @return The set of corresponding parameter names
     */
    public static Set<String> parametersToNames(Set<Parameter> existingParameters) {
        return new HashSet<>(existingParameters.stream().map(Parameter::getNameAsString).toList());
    }

    /**
     * Collects the names of a set of variables {@link VariableDeclarator} and returns the list as a set.
     * @param variableDeclarators The set of variables to process
     * @return The set of corresponding variable names
     */
    public static Set<String> variableDeclaratorsToNames(Set<VariableDeclarator> variableDeclarators) {
        return new HashSet<>(variableDeclarators.stream().map(VariableDeclarator::getNameAsString).toList());
    }

    /**
     * Checks if the field declaration is within the scope to be collected and satisfies the modifiers rules (like `private`,
     * `public`, `static`) and adds all the corresponding variable declarations to the set of existing variables, if not
     * present.
     *
     * Warning: The variable declarations are added to the set, according to the type of set passed to the function. For
     * example if the set is an instance of `HashSet` the equality check is internally performed as `equals()`, while
     * for an instance created from an `IdentityHashMap` the equality check is performed by reference (`==`).
     *
     * @param existingVariables The set of existing variables already defined (that will be filled with the new variables
     *                          defined in the {@code fieldDeclarations} passed to the function
     * @param blockStmt The block of statement within which the variables to check and add are defined (at any level)
     * @param scope the visibility scope (the type to which the block of statements passed to the function refers to,
     *              like a method declaration or a for statement, for example)
     * @param hierarchyLevel the hierarchy level up to which to accept variable definitions (for example if hierarchy level
     *                       is 0 and the scope is a method declaration, the variables defined within an inner block defined
     *                       within the main block of the method are not considered). Check {@link #checkNodeScope}. Any
     *                       inner block of statement adds a hierarchical level
     */
    public static void collectExistingVariables(
            Set<VariableDeclarator> existingVariables,
            BlockStmt blockStmt,
            Class scope,
            int hierarchyLevel
    ) {
        VariableDeclarationExprVisitor varDeclarationExprCollector = new VariableDeclarationExprVisitor();
        List<VariableDeclarationExpr> newVarDeclarationExprs = varDeclarationExprCollector.visit(blockStmt);
        newVarDeclarationExprs.forEach(varDecl -> {
            varDecl.getVariables().forEach(var -> {
                if (checkNodeScope(var, scope, hierarchyLevel)) {
                    existingVariables.add(var);
                }
            });
        });
    }

    /**
     * Checks if the field declaration is within the scope to be collected and satisfies the modifiers rules (like `private`,
     * `public`, `static`) and adds all the corresponding variable declarations to the set of existing variables, if not
     * present.
     *
     * Warning: The variable declarations are added to the set, according to the type of set passed to the function. For
     * example if the set is an instance of `HashSet` the equality check is internally performed as `equals()`, while
     * for an instance created from an `IdentityHashMap` the equality check is performed by reference (`==`).
     *
     * @param existingVariables The set of existing variables already defined (that will be filled with the new variables
     *                          defined in the {@code fieldDeclarations} passed to the function
     * @param fieldDeclarations The list of field declarations to process
     * @param modifiersRules The list of rules related to the modifiers that the field declarations must satisfy
     * @param scope the visibility scope (like a class or a method declaration or a for statement, etc.)
     * @param hierarchyLevel the hierarchy level up to which to accept variable definitions (for example if hierarchy level
     *                       is 0 and the scope is a method declaration, the variables defined within an inner block defined
     *                       within the main block of the method are not considered). Check {@link #checkNodeScope}. Any
     *                       inner block of statement adds a hierarchical level
     */
    public static void collectExistingVariables(
            Set<VariableDeclarator> existingVariables,
            List<FieldDeclaration> fieldDeclarations,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            Class<?> scope,
            int hierarchyLevel
    ) {
        for (FieldDeclaration fieldDecl : fieldDeclarations) {
            if (!checkNodeScope(fieldDecl, scope, hierarchyLevel)) {
                continue;
            }
            if (!checkModifiersRules(modifiersRules, fieldDecl.getModifiers())) {
                // Skip the field declaration if it does not satisfy the modifiers rules
                continue;
            }
            existingVariables.addAll(fieldDecl.getVariables());
        }
    }

    /**
     * Checks if the node is in the visibility scope or not, given it's hierarchical level of definition.
     * For example, given a node instance of {@link VariableDeclarator}, a method declaration as a scope {@link MethodDeclaration}
     * and a hierarchical level equal to 0, returns {@code true} only if the variable is directly defined in the block of
     * the method declaration, containing the statements of the method. If the variable is defined within another block
     * of statements declared within the block of the method, the hierarchical level is 1. The variable declared within a
     * condition of a statement with a block (like the variable defined within a `for` initialization or the resources
     * defined within a `try` statement) inherit the hierarchy of the block of the corresponding statement.
     *
     * Example:
     *      // Hierarchical level 0
     *      {
     *          // BlockStmt of the method declaration
     *          [Type] var1 = ...;
     *          // Creation of new hierarchical level 1 --> `for` statement
     *          for ([Type] var2 = ...; ...; ...) {
     *              // BlockStmt of the `for` statement
     *              [Type] var3 = ...;
     *              // Creation of new hierarchical level 2 --> `try` statement
     *              try ([Type] var4 = ...) {
     *                  // BlockStmt of the `try` statement
     *                  [Type] var5 = ...;
     *              }
     *          }
     *      }
     *
     *      var1 --> hierarchical level 0 with respect to the method declaration
     *      var2 --> hierarchical level 1 with respect to the method declaration
     *      var3 --> hierarchical level 1 with respect to the method declaration
     *      var4 --> hierarchical level 2 with respect to the method declaration
     *      var5 --> hierarchical level 2 with respect to the method declaration
     *
     * @param node the node to check
     * @param scope the visibility scope (like a class or a block of statements of a method or a for loop, etc.)
     * @param hierarchyLevel the hierarchy level up to which to accept the node.
     * @return
     */
    private static boolean checkNodeScope(
            Node node,
            Class scope,
            int hierarchyLevel
    ) {
        if (!((node instanceof VariableDeclarator) || (node instanceof FieldDeclaration))) {
            throw new IllegalStateException("The node passed to the function is not a variable or a field declaration.");
        }

        boolean valid = false;
        boolean varInStmtCondition = true;
        Optional<Node> parentNode = node.getParentNode();
        while(parentNode.isPresent() && (hierarchyLevel + 1) >= 0) {
            if (scope.isInstance(parentNode.get())) {
                valid = true;
                break;
            }
            if (parentNode.get() instanceof BlockStmt) {
                hierarchyLevel--;
                varInStmtCondition = false;
            } else if (
                    parentNode.get() instanceof DoStmt ||
                    parentNode.get() instanceof ForEachStmt ||
                    parentNode.get() instanceof ForStmt ||
                    parentNode.get() instanceof TryStmt ||
                    parentNode.get() instanceof WhileStmt
            ) {
                if (varInStmtCondition) {
                    hierarchyLevel--;
                }
            }
            parentNode = parentNode.get().getParentNode();
        }
        return valid;
    }

    public static HashMap<String, List<Path>> collectPathsOfClassesInSamePackage(
            HashMap<String, List<Path>> existingClasses,
            Path repoSourcePath,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<String> errorMessages
    ) {
        try {
            String packageIdentifier = cu.getPackageDeclaration().orElseThrow(IllegalStateException::new).getNameAsString();
            Path pathToPackage = repoSourcePath.resolve(packageIdentifier.replace(".","/"));
            Files.walk(pathToPackage)
                    .filter(Files::isRegularFile) // Only process files, not directories
                    .filter(path -> path.toString().endsWith(".java")) // Match pattern
                    .forEach(filePath -> {
                        try {
                            CompilationUnit javaFileCu = JavaParserUtils.getCompilationUnit(filePath);
                            for (TypeDeclaration typeDeclaration : javaFileCu.getTypes()) {
                                // Skip the type declaration if it does not satisfy the modifiers rules or in
                                if(!checkModifiersRules(modifiersRules, typeDeclaration.getModifiers())) {
                                    continue;
                                }
                                // If the type declaration is a class or an interface, add it to the map of existing classes
                                if (typeDeclaration.isClassOrInterfaceDeclaration()) {
                                    if (typeDeclaration.asClassOrInterfaceDeclaration().isInterface()) {
                                        // Skip interfaces that cannot be instantiated or used in test assertions
                                        continue;
                                    }
                                }
                                if (!existingClasses.containsKey(typeDeclaration.getNameAsString())) {
                                    existingClasses.put(typeDeclaration.getNameAsString(), new ArrayList<>());
                                }
                                boolean isAlreadyPresent = existingClasses.get(typeDeclaration.getNameAsString()).stream()
                                        .anyMatch(t -> t.equals(filePath));
                                if (!isAlreadyPresent) {
                                    // Add the type declaration to the list of existing classes
                                    existingClasses.get(typeDeclaration.getNameAsString()).add(filePath);
                                }
                            }
                        } catch (Exception e) {
                            String message = "Failed to process compilation unit from file " + filePath + " in the same package of class " + cu.getPrimaryType().get().getNameAsString();
                            logger.error(message);
                            if (errorMessages != null) {
                                errorMessages.add(message);
                            }
                        }
                    });
        } catch (IOException e) {
            String message = "Failed to walk through java classes in the same package of class " + cu.getPrimaryType().get().getNameAsString();
            logger.error(message);
            if (errorMessages != null) {
                errorMessages.add(message);
            }
        } catch (IllegalStateException e) {
            String message = "Package declaration not found in compilation unit from file " + cu.getPrimaryType().get().getNameAsString();
            logger.error(message);
            if (errorMessages != null) {
                errorMessages.add(message);
            }
        }
        return existingClasses;
    }

    public static HashMap<String, List<TypeDeclaration>> collectClassesInSamePackage(
            HashMap<String, List<TypeDeclaration>> existingClasses,
            Path repoSourcePath,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        try {
            String packageIdentifier = cu.getPackageDeclaration().orElseThrow(IllegalStateException::new).getNameAsString();
            Path pathToPackage = repoSourcePath.resolve(packageIdentifier.replace(".","/"));
            Files.walk(pathToPackage)
                    .filter(Files::isRegularFile) // Only process files, not directories
                    .filter(path -> path.toString().endsWith(".java")) // Match pattern
                    .forEach(filePath -> {
                        try {
                            CompilationUnit javaFileCu = JavaParserUtils.getCompilationUnit(filePath);
                            for (TypeDeclaration typeDeclaration : javaFileCu.getTypes()) {
                                // Skip the type declaration if it does not satisfy the modifiers rules or in
                                if(!checkModifiersRules(modifiersRules, typeDeclaration.getModifiers())) {
                                    continue;
                                }
                                // If the type declaration is a class or an interface, add it to the map of existing classes
                                if (typeDeclaration.isClassOrInterfaceDeclaration()) {
                                    ClassOrInterfaceDeclaration classOrInterfaceDecl = typeDeclaration.asClassOrInterfaceDeclaration();
                                    if (classOrInterfaceDecl.isInterface()) {
                                        // Skip interfaces that cannot be instantiated or used in test assertions
                                        continue;
                                    }
                                    ClassOrInterfaceDeclaration classOrInterfaceDeclaration = typeDeclaration.asClassOrInterfaceDeclaration();
                                    if (!existingClasses.containsKey(classOrInterfaceDeclaration.getNameAsString())) {
                                        existingClasses.put(classOrInterfaceDeclaration.getNameAsString(), new ArrayList<>());
                                    }
                                    boolean isAlreadyPresent = existingClasses.get(classOrInterfaceDeclaration.getNameAsString()).stream()
                                            .anyMatch(t -> t.equals(typeDeclaration));
                                    if (!isAlreadyPresent) {
                                        // Add the type declaration to the list of existing classes
                                        existingClasses.get(classOrInterfaceDeclaration.getNameAsString()).add(typeDeclaration);
                                    }
                                } else if (typeDeclaration.isEnumDeclaration()) {
                                    EnumDeclaration enumDecl = typeDeclaration.asEnumDeclaration();
                                    if (!existingClasses.containsKey(enumDecl.getNameAsString())) {
                                        existingClasses.put(enumDecl.getNameAsString(), new ArrayList<>());
                                    }
                                    boolean isAlreadyPresent = existingClasses.get(enumDecl.getNameAsString()).stream().anyMatch(t -> t.equals(typeDeclaration));
                                    if (!isAlreadyPresent) {
                                        // Add the type declaration to the list of existing classes
                                        existingClasses.get(enumDecl.getNameAsString()).add(typeDeclaration);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Failed to process compilation unit from file " + filePath + " in the same package of class " + cu.getPrimaryType().get().getNameAsString());
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to walk through java classes in the same package of class " + cu.getPrimaryType().get().getNameAsString());
        } catch (IllegalStateException e) {
            logger.error("Package declaration not found in compilation unit from file " + cu.getPrimaryType().get().getNameAsString());
        }
        return existingClasses;
    }

    /**
     * Collect all the imported classes in a compilation unit.
     *
     * @param existingClasses the map of existing classes to update
     * @param cu the compilation unit to process
     * @return the map of existing classes updated with the imported classes file paths
     */
    public static HashMap<String, List<Path>> collectPathsOfImportedClasses(
            HashMap<String, List<Path>> existingClasses,
            List<Path> repoSourcePaths,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        // Iterate over all the imports in the compilation unit
        for (ImportDeclaration importDecl : cu.getImports()) {
            Set<Path> relatedTypeDeclarationPaths = new HashSet<>();

            // Get the fully qualified name of the imported class
            String fqn = importDecl.getNameAsString();
            // Retrieve the type declaration from the fully qualified name
            Optional<Path> pathOpt = Optional.empty();

            for (Path repoSourcePath : repoSourcePaths) {
                pathOpt = retrieveFilePathFromFullyQualifiedName(fqn, repoSourcePath);
                if (pathOpt.isPresent()) {
                    relatedTypeDeclarationPaths.add(pathOpt.get());
                    break; // Exit the loop if the type declaration is found
                }
            }
            // Check if the fqn corresponds to a whole package (e.g., "java.util.*")
            // Retrieve all type declarations in the package if the type declaration is not found
            if (pathOpt.isEmpty()) {
                for (Path repoSourcePath : repoSourcePaths) {
                    relatedTypeDeclarationPaths.addAll(retrieveFilePathsFromPackageName(fqn, repoSourcePath));
                }
            }

            for (Path relatedTypeDeclPath : relatedTypeDeclarationPaths) {
                try {
                    CompilationUnit importedCu = getCompilationUnit(relatedTypeDeclPath);
                    for (TypeDeclaration typeDecl : importedCu.getTypes()) {
                        // Skip the type declaration if it does not satisfy the modifiers rules or in
                        if (!checkModifiersRules(modifiersRules, typeDecl.getModifiers())) {
                            continue;
                        }
                        // If the type declaration is a class or an interface, add it to the map of existing classes
                        if (typeDecl.isClassOrInterfaceDeclaration()) {
                            ClassOrInterfaceDeclaration classOrInterfaceDecl = typeDecl.asClassOrInterfaceDeclaration();
                            // if (classOrInterfaceDecl.isInterface()) {
                                // Skip interfaces that cannot be instantiated or used in test assertions
                            //    continue;
                            // }
                            if (!existingClasses.containsKey(classOrInterfaceDecl.getNameAsString())) {
                                existingClasses.put(classOrInterfaceDecl.getNameAsString(), new ArrayList<>());
                            }
                            boolean isAlreadyPresent = existingClasses.get(classOrInterfaceDecl.getNameAsString()).stream().anyMatch(t -> t.equals(relatedTypeDeclPath));
                            if (!isAlreadyPresent) {
                                // Add the type declaration to the list of existing classes
                                existingClasses.get(classOrInterfaceDecl.getNameAsString()).add(relatedTypeDeclPath);
                            }
                        } else if (typeDecl.isEnumDeclaration()) {
                            EnumDeclaration enumDecl = typeDecl.asEnumDeclaration();
                            if (!existingClasses.containsKey(enumDecl.getNameAsString())) {
                                existingClasses.put(enumDecl.getNameAsString(), new ArrayList<>());
                            }
                            boolean isAlreadyPresent = existingClasses.get(enumDecl.getNameAsString()).stream().anyMatch(t -> t.equals(relatedTypeDeclPath));
                            if (!isAlreadyPresent) {
                                // Add the type declaration to the list of existing classes
                                existingClasses.get(enumDecl.getNameAsString()).add(relatedTypeDeclPath);
                            }
                        }
                    }
                } catch (IOException e) {
                    logger.error("Failed to process compilation unit from file " + relatedTypeDeclPath + " in the imports of class " + cu.getPrimaryType().get().getNameAsString());
                }
            }
        }
        return existingClasses;
    }

    /**
     * Collect all the imported classes in a compilation unit.
     *
     * @param existingClasses the map of existing classes to update
     * @param cu the compilation unit to process
     * @return the map of existing classes updated with the imported classes
     */
    public static HashMap<String, List<TypeDeclaration>> collectImportedClasses(
            HashMap<String, List<TypeDeclaration>> existingClasses,
            List<Path> repoSourcePaths,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        // Iterate over all the imports in the compilation unit
        for (ImportDeclaration importDecl : cu.getImports()) {
            Set<TypeDeclaration> relatedTypeDeclarations = new HashSet<>();

            // Get the fully qualified name of the imported class
            String fqn = importDecl.getNameAsString();
            // Retrieve the type declaration from the fully qualified name
            Optional<TypeDeclaration> typeDeclOpt = Optional.empty();

            for (Path repoSourcePath : repoSourcePaths) {
                typeDeclOpt = retrieveTypeDeclarationFromFullyQualifiedName(fqn, repoSourcePath);
                if (typeDeclOpt.isPresent()) {
                    relatedTypeDeclarations.add(typeDeclOpt.get());
                    break; // Exit the loop if the type declaration is found
                }
            }
            // Check if the fqn corresponds to a whole package (e.g., "java.util.*")
            // Retrieve all type declarations in the package if the type declaration is not found
            if (typeDeclOpt.isEmpty()) {
                for (Path repoSourcePath : repoSourcePaths) {
                    relatedTypeDeclarations.addAll(retrieveTypeDeclarationsFromPackageName(fqn, repoSourcePath));
                }
            }

            for (TypeDeclaration relatedTypeDecl : relatedTypeDeclarations) {
                CompilationUnit importedCu = (CompilationUnit) relatedTypeDecl.getParentNode().get();
                for (TypeDeclaration typeDecl : importedCu.getTypes()) {
                    // Skip the type declaration if it does not satisfy the modifiers rules or in
                    if(!checkModifiersRules(modifiersRules, typeDecl.getModifiers())) {
                        continue;
                    }
                    // If the type declaration is a class or an interface, add it to the map of existing classes
                    if (typeDecl.isClassOrInterfaceDeclaration()) {
                        ClassOrInterfaceDeclaration classOrInterfaceDecl = typeDecl.asClassOrInterfaceDeclaration();
                        if (classOrInterfaceDecl.isInterface()) {
                            // Skip interfaces that cannot be instantiated or used in test assertions
                            continue;
                        }
                        if (!existingClasses.containsKey(classOrInterfaceDecl.getNameAsString())) {
                            existingClasses.put(classOrInterfaceDecl.getNameAsString(), new ArrayList<>());
                        }
                        boolean isAlreadyPresent = existingClasses.get(classOrInterfaceDecl.getNameAsString()).stream().anyMatch(t -> t.equals(typeDecl));
                        if (!isAlreadyPresent) {
                            // Add the type declaration to the list of existing classes
                            existingClasses.get(classOrInterfaceDecl.getNameAsString()).add(typeDecl);
                        }
                    }
                }
            }
        }
        return existingClasses;
    }

    /**
     * Check if the modifiers of a method declaration satisfy at least one of the set of rules defined in the modifiersRules.
     * The rules are defined as a list of lists of pairs, where each pair contains a modifier keyword and a boolean value
     * indicating if the modifier is required (true) or not allowed (false).
     *
     * @param modifiersRules the list of lists of pairs defining the rules for the modifiers
     * @param nodeModifiers the list of modifiers of the method declaration to check
     * @return true if the modifiers satisfy at least one rule, false otherwise
     */
    public static boolean checkModifiersRules(
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<Modifier> nodeModifiers
    ) {
        for (List<Pair<Modifier.Keyword, Boolean>> modifier : modifiersRules) {
            boolean checker = true;
            for (Pair<Modifier.Keyword, Boolean> mod : modifier) {
                // If the modifier is required, check if it is present in the method modifiers
                if (mod.getValue1()) {
                    if (!nodeModifiers.stream().anyMatch(m -> m.getKeyword().equals(mod.getValue0()))) {
                        checker = false;
                    }
                } else {
                    // If the modifier is not allowed, check if it is present in the method modifiers
                    if (nodeModifiers.stream().anyMatch(m -> m.getKeyword().equals(mod.getValue0()))) {
                        checker = false;
                    }
                }
            }
            if (checker) {
                // If all the modifiers in the rule are satisfied, return true
                return true;
            }
        }
        return true;
    }

    /**
     * Checks if the variable name of the variable {@link VariableDeclarator} passed to the function is already defined
     * in the collection of the existing variables. In this case the method renames the variable using a simple incremental
     * logic (adding an index at the end of the variable)
     *
     * @param var the variable to check
     * @param existingVariables the collection of existing variables
     * @return If the variable name already exists in the collection, an optional {@link Optional} containing a pair of
     * elements where the first element is the old variable name and the second element is the new variable name. Otherwise,
     * an empty optional {@link Optional}
     */
    public static Optional<Pair<String,String>> renameLocalVar(VariableDeclarator var, HashMap<String, VariableDeclarator> existingVariables) {
        String varName = var.getNameAsString();
        if (!existingVariables.containsKey(varName)) {
            existingVariables.put(varName, var);
            return Optional.empty();
        }
        int i = 0;
        while (existingVariables.containsKey(varName + "_" + i)) {
            i++;
        }
        String newVarName = varName + "_" + i;
        var.setName(newVarName);
        existingVariables.put(newVarName, var);
        return Optional.of(new Pair<>(varName, newVarName));
    }

    public static Pair<VariableDeclarator, VariableDeclarationExpr> generateNewVariable(String originalName, Set<VariableDeclarator> existingVariables, com.github.javaparser.ast.type.Type varType, Expression expr, Optional<String> suffix) {
        String newVarName = originalName + suffix.orElse("");
        int idx = 0;
        Set<String> existingVariableNames = variableDeclaratorsToNames(existingVariables);
        while (existingVariableNames.contains(newVarName)) {
            newVarName = newVarName + ++idx;
        }
        VariableDeclarator newVarDecl = new VariableDeclarator(
                new ClassOrInterfaceType(null, varType.asString()),
                newVarName,
                expr
        );
        VariableDeclarationExpr executableVarDeclExpr = new VariableDeclarationExpr(newVarDecl);
        return new Pair<>(newVarDecl, executableVarDeclExpr);
    }

    /**
     * Checks if the parameter name of the parameter {@link Parameter} passed to the function is already defined
     * in the collection of the existing variable names. In this case the method renames the variable using a simple incremental
     * logic (adding an index at the end of the variable)
     *
     * @param param the parameter to check
     * @param existingVariables the collection of existing variables
     * @return If the parameter name already exists in the collection, an optional {@link Optional} the new parameter name.
     * Otherwise, an empty optional {@link Optional}
     */
    public static Optional<String> renameLocalParam(Parameter param, Set<VariableDeclarator> existingVariables) {
        Set<String> existingVariableNames = JavaParserUtils.variableDeclaratorsToNames(existingVariables);
        String paramName = param.getNameAsString();
        if (!existingVariableNames.contains(paramName)) {
            return Optional.empty();
        }
        int i = 0;
        while (existingVariableNames.contains(paramName + "_" + i)) {
            i++;
        }
        String newParamName = paramName + "_" + i;
        return Optional.of(newParamName);
    }

    /**
     * Given a BlockStmt representing the body of a method, and the mapping of the parameters to replace in the block of
     * statements, replace the parameters with the actual arguments in the block of statements.
     * @param blockStmt the body of the method to process
     * @param paramMapping the mapping of the parameters to replace in the block of statements
     */
    public static void replaceParams(BlockStmt blockStmt, Map<String, Expression> paramMapping) {
        NameExprVisitor nameExprCollector = new NameExprVisitor();
        List<NameExpr> nameExprs = nameExprCollector.visit(blockStmt);
        nameExprs.forEach(nameExpr -> {
            String varName = nameExpr.getNameAsString();
            if (paramMapping.containsKey(varName)) {
                nameExpr.replace(paramMapping.get(varName).clone());
            }
        });
    }

    /**
     * Given a MethodDeclaration, renames all usages of the specified parameters in the method body,
     * along with the parameter declarations themselves.
     *
     * Uses JavaSymbolSolver to resolve each NameExpr to its exact Parameter declaration, safely handling:
     * <ul>
     *   <li>Local variables shadowing a parameter name inside the body</li>
     *   <li>Field name collisions with parameter names</li>
     * </ul>
     *
     * A parameter is renamed (both its declaration and all usages in the body) only if ALL its usages
     * were successfully resolved. If any usage fails to resolve, the parameter is left entirely
     * unchanged to avoid producing inconsistent code.
     *
     * Prerequisite: {@link com.github.javaparser.symbolsolver.JavaSymbolSolver} must be configured
     * on {@link com.github.javaparser.StaticJavaParser} before calling this method.
     *
     * @param blockStmt the body of the method to process
     * @param paramMapping the mapping of the parameters (paramter → new name) to replace
     */
    public static void replaceParameters(BlockStmt blockStmt, Map<Parameter, String> paramMapping) {
        // PASS 1: Collect pending NameExpr renames grouped by their resolved Parameter.
        // Track parameters for which at least one NameExpr usage failed to resolve.
        // No mutations are applied in this pass.
        Map<Parameter, List<NameExpr>> pendingRenames = new IdentityHashMap<>();
        Set<Parameter> failedParams = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> existingParamNames = parametersToNames(paramMapping.keySet());
        blockStmt.findAll(NameExpr.class).forEach(nameExpr -> {
            if (!existingParamNames.contains(nameExpr.getNameAsString())) return;
            try {
                nameExpr.resolve().toAst()
                        // Unlike local variables, toAst() returns Parameter directly —
                        // no VariableDeclarationExpr unwrapping needed.
                        .filter(node -> node instanceof Parameter)
                        .map(node -> (Parameter) node)
                        .filter(paramMapping::containsKey) // identity check — only OUR targets
                        .ifPresent(param -> pendingRenames
                                .computeIfAbsent(param, k -> new ArrayList<>())
                                .add(nameExpr));
            } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
                // Resolution failed: conservatively mark all target parameters sharing this
                // name as failed, since we cannot determine which one this NameExpr binds to.
                paramMapping.keySet().stream()
                        .filter(p -> p.getNameAsString().equals(nameExpr.getNameAsString()))
                        .forEach(failedParams::add);
            }
        });
        // PASS 2: Apply renames atomically — only for parameters with no resolution failures.
        // Both NameExpr usages in the body and the parameter declaration itself are renamed
        // together, or not at all.
        paramMapping.forEach((param, newName) -> {
            if (failedParams.contains(param)) return;
            pendingRenames.getOrDefault(param, Collections.emptyList())
                    .forEach(nameExpr -> nameExpr.setName(newName));
            param.setName(newName);
        });
    }



    public static void normalizeVariableNames(
            BlockStmt blockStmt
    ) {
        Set<VariableDeclarator> existingVariables = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> existingIdentifiers = new HashSet<>();
        HashMap<VariableDeclarator, String> newVariablesToChange = new HashMap<>();
        VariableDeclarationExprVisitor varDeclarationExprCollector = new VariableDeclarationExprVisitor();
        List<VariableDeclarationExpr> varDeclarationExprs = varDeclarationExprCollector.visit(blockStmt);
        Set<Parameter> existingParams = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Parameter> parameters = blockStmt.findAll(Parameter.class);
        HashMap<Parameter, String> newParametersToChange = new HashMap<>();

        varDeclarationExprs.forEach(varDecl -> {
            varDecl.getVariables().forEach(var -> {
                String varName = var.getNameAsString();
                if (!existingIdentifiers.contains(varName)) {
                    existingVariables.add(var);
                    existingIdentifiers.add(varName);
                } else {
                    String newVarName = varName;
                    int idx = 0;
                    while (existingIdentifiers.contains(newVarName)) {
                        newVarName = varName + ++idx;
                    }
                    existingIdentifiers.add(newVarName);
                    newVariablesToChange.put(var, newVarName);
                }
            });
        });

        parameters.forEach(parameter -> {
            String paramName = parameter.getNameAsString();
            if (!existingIdentifiers.contains(paramName)) {
                existingParams.add(parameter);
                existingIdentifiers.add(paramName);
            } else {
                String newParamName = paramName;
                int idx = 0;
                while (existingIdentifiers.contains(newParamName)) {
                    newParamName = paramName + ++idx;
                }
                existingIdentifiers.add(newParamName);
                newParametersToChange.put(parameter, newParamName);
            }
        });
        JavaParserUtils.replaceVariables(blockStmt, newVariablesToChange);
        JavaParserUtils.replaceParameters(blockStmt, newParametersToChange);
    }

    /**
     * Retrieves the variable declarator {@link VariableDeclarator} of a variable, from a name expression {@link NameExpr}.
     * If the variable declarator is found returns it, otherwise returns an empty optional {@link Optional}.
     *
     * @param nameExpr the name expression to process
     * @param existingVariables the list of variables already defined, among to which look for the name expression
     * @return An optional containing the retrieved variable declarator or an empty optional, otherwise.
     */
    public static Optional<VariableDeclarator> getVarFromNameExpr(NameExpr nameExpr, Set<VariableDeclarator> existingVariables) {
        return nameExpr.resolve().toAst()
                .flatMap(node -> {
                    // toAst() may return either the VariableDeclarator directly,
                    // or the parent VariableDeclarationExpr — handle both.
                    if (node instanceof VariableDeclarator vd) {
                        return Optional.of(vd);
                    } else if (node instanceof VariableDeclarationExpr vde) {
                        // A VariableDeclarationExpr can declare multiple variables (e.g. int x=1, y=2),
                        // so we find the one matching this specific NameExpr's name.
                        return vde.getVariables().stream()
                                .filter(vd -> vd.getNameAsString().equals(nameExpr.getNameAsString()))
                                .findFirst();
                    }
                    return Optional.empty();
                })
                .filter(existingVariables::contains);
    }

    /**
     * Given a BlockStmt representing the body of a method, and the mapping of the variables to replace
     * in the block of statements, replace the variables with the renamed names in the block of statements.
     *
     * Uses JavaSymbolSolver to resolve each NameExpr to its exact declaration, safely handling:
     * <ul>
     *   <li>Variable shadowing in nested scopes</li>
     *   <li>Field name collisions with local variables</li>
     * </ul>
     *
     * A variable is renamed (both declaration and all usages) only if ALL its usages were successfully
     * resolved. If any usage fails to resolve, the variable is left entirely unchanged to avoid
     * producing inconsistent code.
     *
     * Prerequisite: {@link com.github.javaparser.symbolsolver.JavaSymbolSolver} must be configured
     * on {@link com.github.javaparser.StaticJavaParser} before calling this method.
     *
     * @param blockStmt  the body of the method to process
     * @param varMapping the mapping of the variables (old name → new name) to replace
     */
    public static void replaceVariables(BlockStmt blockStmt, Map<VariableDeclarator, String> varMapping) {
        // PASS 1: Collect pending NameExpr renames grouped by their resolved VariableDeclarator.
        // Track declarators for which at least one NameExpr usage failed to resolve.
        // No mutations are applied in this pass.
        Map<VariableDeclarator, List<NameExpr>> pendingRenames = new IdentityHashMap<>();
        Set<VariableDeclarator> failedDeclarators = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> existingVariableNames = variableDeclaratorsToNames(varMapping.keySet());
        blockStmt.findAll(NameExpr.class).forEach(nameExpr -> {
            if (!existingVariableNames.contains(nameExpr.getNameAsString())) return;
            try {
                Optional<VariableDeclarator> resolved = nameExpr.resolve().toAst()
                        .flatMap(node -> {
                            // toAst() may return either the VariableDeclarator directly,
                            // or the parent VariableDeclarationExpr — handle both.
                            if (node instanceof VariableDeclarator vd) {
                                return Optional.of(vd);
                            } else if (node instanceof VariableDeclarationExpr vde) {
                                // A VariableDeclarationExpr can declare multiple variables (e.g. int x=1, y=2),
                                // so we find the one matching this specific NameExpr's name.
                                return vde.getVariables().stream()
                                        .filter(vd -> vd.getNameAsString().equals(nameExpr.getNameAsString()))
                                        .findFirst();
                            }
                            return Optional.empty();
                        })
                        .filter(varMapping::containsKey); // identity check — must be a target declarator

                resolved.ifPresent(vd -> pendingRenames
                        .computeIfAbsent(vd, k -> new ArrayList<>())
                        .add(nameExpr));

            } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
                varMapping.keySet().stream()
                        .filter(vd -> vd.getNameAsString().equals(nameExpr.getNameAsString()))
                        .forEach(failedDeclarators::add);
            }
        });
        // PASS 2: Apply renames atomically — only for declarators with no resolution failures.
        // Both NameExpr usages and the declaration itself are renamed together, or not at all.
        varMapping.forEach((vd, newName) -> {
            if (failedDeclarators.contains(vd)) {
                return;
            }
            pendingRenames.getOrDefault(vd, Collections.emptyList())
                    .forEach(nameExpr -> nameExpr.setName(newName));
            vd.setName(newName);
        });
    }

    /**
     * Retrieve the last assertion, assuming it is in the last statement of a method declaration.
     *
     * @param callableDeclaration the method declaration to process
     * @return the last assertion in the last statement of the method declaration (if any), otherwise return an empty optional
     */
    public static Optional<Statement> getLastAssertionInMethodDeclaration(MethodDeclaration callableDeclaration) throws IllegalStateException {
        // TODO: Check if assert stmt and expression stmt inteleave in the same nested block
        Statement lastStmt = getLastStatementInMethodDeclaration(callableDeclaration);
        if (lastStmt.isAssertStmt()) {
            return Optional.of(lastStmt);
        }
        Optional<Statement> exprStmt = getLastStatementTypeOccurrence(lastStmt,ExpressionStmt.class);
        if (exprStmt.isPresent()) {
            // Get the expression from the statement
            Expression currentExpr = exprStmt.get().asExpressionStmt().getExpression();
            // Check if the expression is a variable declaration expression and extract the initializer, if present
            if (currentExpr.isVariableDeclarationExpr()) {
                Optional<Expression> initializer = currentExpr.asVariableDeclarationExpr().getVariable(0).getInitializer();
                if (initializer.isPresent()) {
                    currentExpr = initializer.get();
                }
            }
            // Check if the expression is an assertion
            if (currentExpr.isMethodCallExpr() && JUnitAssertionType.isJUnitAssertion(currentExpr.asMethodCallExpr().getNameAsString())) {
                return exprStmt;
            } else {
                Optional<Node> parentNode = exprStmt.get().getParentNode();
                while (parentNode.isPresent() && !parentNode.get().equals(callableDeclaration)) {
                    if (parentNode.get() instanceof MethodCallExpr) {
                        MethodCallExpr methodCallExpr = (MethodCallExpr) parentNode.get();
                        if (JUnitAssertionType.isJUnitAssertion(methodCallExpr.getNameAsString())) {
                            while (parentNode.isPresent() && !(parentNode.get() instanceof ExpressionStmt)) {
                                parentNode = parentNode.get().getParentNode();
                            }
                            if (parentNode.isPresent()) {
                                return Optional.of((Statement) parentNode.get());
                            }
                            return Optional.empty();
                        }
                    }
                    parentNode = parentNode.get().getParentNode();
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieve the type declaration from the fully qualified name of the class and the root path of the repository where
     * the class should be located (source code or decompiled library), or an empty optional if the class cannot be found.
     *
     * @param fqn the fully qualified name of the class to retrieve
     * @param repoSourcePath the root path of the repository where the class should be located
     * @return the type declaration of the class corresponding to the fully qualified name or an empty optional if the class
     *        cannot be found
     */
    public static Optional<TypeDeclaration> retrieveTypeDeclarationFromFullyQualifiedName(String fqn, Path repoSourcePath) {
        Path fqnPath = FilesUtils.getFQNPath(fqn);
        Path referencePath = repoSourcePath;
        List<Path> candidatePrefixPaths = new ArrayList<>();
        candidatePrefixPaths.add(repoSourcePath);
        candidatePrefixPaths.addAll(JavaUtils.getPathToJavaLibClassFromFqn(fqnPath, true));
        boolean hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        while(!hasLibFolder && !(referencePath.getParent() == null)) {
            referencePath = referencePath.getParent();
            hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        }
        if (hasLibFolder) {
            candidatePrefixPaths.addAll(FilesUtils.listDirectories(referencePath.resolve(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())));
        }
        candidatePrefixPaths.add(repoSourcePath);
        for(Path candidatePrefixPath : candidatePrefixPaths) {
            Path classFilePath = candidatePrefixPath.resolve(fqnPath);
            if (Files.exists(classFilePath)) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classFilePath);
                    if (cu.getPrimaryType().isPresent()) {
                        return Optional.of(cu.getPrimaryType().get());
                    } else {
                        return Optional.empty();
                    }
                } catch (IOException e) {
                    logger.error("Error reading file: " + classFilePath);
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieve the file path of a corresponding type declaration from the fully qualified name of the class and
     * the root path of the repository where the class should be located (source code or decompiled library),
     * or an empty optional if the class cannot be found.
     *
     * @param fqn the fully qualified name of the class to retrieve
     * @param repoSourcePath the root path of the repository where the class should be located
     * @return the file path of the type declaration of the class corresponding to the fully qualified name,
     *         or an empty optional if the class cannot be found
     */
    public static Optional<Path> retrieveFilePathFromFullyQualifiedName(String fqn, Path repoSourcePath) {
        Path fqnPath = FilesUtils.getFQNPath(fqn);
        Path referencePath = repoSourcePath;
        List<Path> candidatePrefixPaths = new ArrayList<>();
        candidatePrefixPaths.add(repoSourcePath);
        candidatePrefixPaths.addAll(JavaUtils.getPathToJavaLibClassFromFqn(fqnPath, true));
        boolean hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        while(!hasLibFolder && !(referencePath.getParent() == null)) {
            referencePath = referencePath.getParent();
            hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        }
        if (hasLibFolder) {
            candidatePrefixPaths.addAll(FilesUtils.listDirectories(referencePath.resolve(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())));
        }
        candidatePrefixPaths.add(repoSourcePath);
        for(Path candidatePrefixPath : candidatePrefixPaths) {
            Path classFilePath = candidatePrefixPath.resolve(fqnPath);
            if (Files.exists(classFilePath)) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classFilePath);
                    if (cu.getPrimaryType().isPresent()) {
                        return Optional.of(classFilePath);
                    } else {
                        return Optional.empty();
                    }
                } catch (IOException e) {
                    logger.error("Error reading file: " + classFilePath);
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieve the list of type declarations defined within the package name of the repository where the package should
     * be located (source code or decompiled library), or an empty optional if the class cannot be found.
     *
     * @param packageName the package name of the classes to retrieve
     * @param repoSourcePath the root path of the repository where the class should be located
     * @return the type declaration of the class corresponding to the fully qualified name or an empty optional if the
     *         class cannot be found
     */
    public static List<TypeDeclaration> retrieveTypeDeclarationsFromPackageName(String packageName, Path repoSourcePath) {
        List<TypeDeclaration> typeDeclarations = new ArrayList<>();
        Path packageNamePath = FilesUtils.getPackageNamePath(packageName);
        Path referencePath = repoSourcePath;
        List<Path> candidatePrefixPaths = new ArrayList<>();
        candidatePrefixPaths.add(repoSourcePath);
        candidatePrefixPaths.addAll(JavaUtils.getPathsToJavaLibClassesFromPackage(packageNamePath, true));
        boolean hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        while(!hasLibFolder) {
            referencePath = referencePath.getParent();
            if (referencePath == null) {
                break;
            }
            hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        }
        if (hasLibFolder) {
            candidatePrefixPaths.addAll(FilesUtils.listDirectories(referencePath.resolve(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())));
        }
        candidatePrefixPaths.add(repoSourcePath);
        for(Path candidatePrefixPath : candidatePrefixPaths) {
            Path packagePath = candidatePrefixPath.resolve(packageNamePath);
            if (Files.exists(packagePath) && Files.isDirectory(packagePath)) {
                try {
                    Files.walk(packagePath)
                            .filter(Files::isRegularFile) // Only process files, not directories
                            .filter(path -> path.toString().endsWith(".java")) // Match pattern
                            .forEach(filePath -> {
                                try {
                                    CompilationUnit javaFileCu = JavaParserUtils.getCompilationUnit(filePath);
                                    for (TypeDeclaration typeDeclaration : javaFileCu.getTypes()) {
                                        // If the type declaration is a class or an interface, add it to the map of existing classes
                                        if (typeDeclaration.isClassOrInterfaceDeclaration()) {
                                            typeDeclarations.add(typeDeclaration);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to process compilation unit from file " + filePath + " when processing the package " + packageName);
                                }
                            });
                } catch (IOException e) {
                    logger.error("Failed to walk through java classes in the package " + packageName + " in the repository " + repoSourcePath);
                } catch (IllegalStateException e) {
                    logger.error("Package declaration not found in compilation unit from file " + packageName);
                }
                break;
            }
        }
        return typeDeclarations;
    }

    /**
     * Retrieve the list of file paths corresponding to the classes defined within the package name of the repository
     * where the package should be located.
     *
     * @param packageName the package name of the classes to retrieve
     * @param repoSourcePath the root path of the reference repository
     * @return the file paths of the classes defined within the package name of the repository
     */
    public static List<Path> retrieveFilePathsFromPackageName(String packageName, Path repoSourcePath) {
        List<Path> filePaths = new ArrayList<>();
        Path packageNamePath = FilesUtils.getPackageNamePath(packageName);
        Path referencePath = repoSourcePath;
        List<Path> candidatePrefixPaths = new ArrayList<>();
        candidatePrefixPaths.add(repoSourcePath);
        candidatePrefixPaths.addAll(JavaUtils.getPathsToJavaLibClassesFromPackage(packageNamePath, true));
        boolean hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        while(!hasLibFolder) {
            referencePath = referencePath.getParent();
            if (referencePath == null) {
                break;
            }
            hasLibFolder = FilesUtils.hasChildDirectory(referencePath, NamingConvention.LIB_FOLDER.getConventionName());
        }
        if (hasLibFolder) {
            candidatePrefixPaths.addAll(FilesUtils.listDirectories(referencePath.resolve(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())));
        }
        candidatePrefixPaths.add(repoSourcePath);
        for(Path candidatePrefixPath : candidatePrefixPaths) {
            Path packagePath = candidatePrefixPath.resolve(packageNamePath);
            if (Files.exists(packagePath) && Files.isDirectory(packagePath)) {
                try {
                    Files.walk(packagePath)
                            .filter(Files::isRegularFile) // Only process files, not directories
                            .filter(path -> path.toString().endsWith(".java")) // Match pattern
                            .forEach(filePath -> {
                                try {
                                    CompilationUnit javaFileCu = JavaParserUtils.getCompilationUnit(filePath);
                                    for (TypeDeclaration typeDeclaration : javaFileCu.getTypes()) {
                                        // If the type declaration is a class or an interface, add it to the map of existing classes
                                        if (typeDeclaration.isClassOrInterfaceDeclaration()) {
                                            filePaths.add(filePath);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to process compilation unit from file " + filePath + " when processing the package " + packageName);
                                }
                            });
                } catch (IOException e) {
                    logger.error("Failed to walk through java classes in the package " + packageName + " in the repository " + repoSourcePath);
                } catch (IllegalStateException e) {
                    logger.error("Package declaration not found in compilation unit from file " + packageName);
                }
                break;
            }
        }
        return filePaths;
    }
}
