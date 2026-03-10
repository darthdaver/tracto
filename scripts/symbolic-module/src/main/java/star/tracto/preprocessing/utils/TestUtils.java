package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.*;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import javassist.expr.NewArray;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.*;
import star.tracto.data.exceptions.*;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.data.records.RepositoryTrack;
import star.tracto.preprocessing.builders.*;
import star.tracto.preprocessing.components.*;
import star.tracto.preprocessing.components.Type;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;
import star.tracto.utils.javaParser.visitors.declarations.FieldDeclarationVisitor;
import star.tracto.utils.javaParser.visitors.expressions.MethodCallExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.NameExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.VariableDeclarationExprVisitor;
import star.tracto.utils.javaParser.visitors.statements.AssertStmtVisitor;
import star.tracto.utils.javaParser.visitors.statements.ExpressionStmtVisitor;
import star.tracto.utils.javaParser.visitors.statements.ReturnStmtVisitor;
import star.tracto.utils.javaParser.visitors.statements.helper.StmtVisitorHelper;

import javax.swing.plaf.nimbus.State;
import javax.swing.text.html.Option;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class provides static utility methods to process test files and
 * generate Oracles Datapoints.
 */
public class TestUtils {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);
    /* helper labels to identify fake elements added to avoid JavaParser parsing problems, like in try-catch statements */
    private static final String FAKE_ELEMENT_LABEL = "<FAKE_ELEMENT>";
    private static final String THROW_EXCEPTION_LABEL = "THROW EXCEPTION";
    /* Do not instantiate this class. */
    private TestUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Initialize a new split test case. It clones the original test method and renames it with
     * the index of the split test case. Returns the new cloned split test case.
     *
     * @param originalTestMethod the original test method
     * @param testCaseName the name of the test case
     * @param exceptionsToThrow the list of exceptions that could be thrown by the split test case
     * @param idx the index of the split test case
     * @return the new cloned split test case
     */
    private static MethodDeclaration initializeSplitTestCase(MethodDeclaration originalTestMethod, String testCaseName, NodeList<ReferenceType> exceptionsToThrow, int idx) {
        MethodDeclaration splitTestCase = originalTestMethod.clone();
        String delimiter = "_split_";
        int delimiterPosition = testCaseName.indexOf("_split_");
        if (delimiterPosition != -1) {
            String delimiterPlusIdx = testCaseName.substring(delimiterPosition, testCaseName.length());
            testCaseName = testCaseName.replace(delimiterPlusIdx, "");
        }
        splitTestCase.setName(testCaseName + delimiter + idx);
        // Set the exceptions to be thrown by the split test case
        if (exceptionsToThrow != null && !exceptionsToThrow.isEmpty()) {
            splitTestCase.setThrownExceptions(exceptionsToThrow);
        }
        return splitTestCase;
    }

    /**
     * Initialize a new split test case. It clones the original test method, renames it with
     * the index of the split test case, and initializes the body of the new test case.
     * Returns the new cloned split test case.
     *
     * @param originalTestMethod the original test method
     * @param idx the index of the split test case
     * @param testCaseName the name of the test case
     * @param exceptionsToThrow the list of exceptions that could be thrown by the split test case
     * @param body the body of the split test case
     * @return the new cloned split test case
     */
    private static MethodDeclaration initializeSplitTestCase(MethodDeclaration originalTestMethod, String testCaseName, int idx, NodeList<ReferenceType> exceptionsToThrow, BlockStmt body) {
        MethodDeclaration splitTestCase = TestUtils.initializeSplitTestCase(originalTestMethod, testCaseName, exceptionsToThrow, idx);
        splitTestCase.setBody(body);
        return splitTestCase;
    }

    /**
     * Perform the matching between the focal method(s) and the test prefix.
     * Extracts each method under test from the test prefix and generate triples of (focalMethod, testPrefix, docstring).
     * The focal method is considered the last statement in the test method that belongs to the fullyQualifiedClassName.
     * Saves the output file in output/toga/input.
     *
     * @param testFilePath the path to the test class file
     * @param repoRootPath the path to the source code of the project under test
     * @param outputDirPath the path to the output folder
     * @param m2tMatchingType the type of matching to use (any occurrence or last occurrence)
     *
     */
    public static void matchMethodsToTests(Path testFilePath, Path repoRootPath, Path outputDirPath, M2TMatchingType m2tMatchingType) {}

    /**
     * Returns the JUnit version used within a test class, identifying it from the collection of imports defined in it and
     * passed to the function.
     *
     * @param imports the collection of `imports` statements defined within a test class
     * @return the JUnit version used within the test class
     * @throws IllegalStateException if the JUnit version cannot be identified within the collection of `imports` passed
     * to the function
     */
    public static JUnitVersion getJunitVersion(NodeList<ImportDeclaration> imports) {
        for (ImportDeclaration importDeclaration : imports) {
            if (importDeclaration.getNameAsString().contains("junit")) {
                if (importDeclaration.getNameAsString().contains("jupiter")) {
                    return JUnitVersion.JUNIT5;
                } else {
                    return JUnitVersion.JUNIT4;
                }
            }
        }
        throw new IllegalStateException("No JUnit version found in the imports");
    }

    /**
     * Check if the test case is in the filter list of test cases collected during the tracking of repositories.
     * The list contains the test cases that has been added after the training cutoff-date of a given model. During
     * the generation of a dataset for experimental evaluation, it prevents to consider test cases that the model
     * could have already seen during the pre-training.
     *
     * @param originalTestCase the test case in the test class
     * @param testCaseFilterList the list of test cases collected during the tracking of repositories
     * @return true if the test case is in the filter list and the body coincides with the test case, false otherwise
     */
    private static boolean matchTestCaseWithRepoTrackTestCase(MethodDeclaration originalTestCase, Optional<List<RepositoryTrack.TestCase>> testCaseFilterList) {
        // Check if the test case is in the filter list
        if (testCaseFilterList != null && testCaseFilterList.isPresent() && testCaseFilterList.get() != null && !testCaseFilterList.get().isEmpty()) {
            String testCaseName = originalTestCase.getNameAsString();
            String testCaseSignature = originalTestCase.getSignature().toString();
            boolean found = false;
            for (RepositoryTrack.TestCase testCaseFilter : testCaseFilterList.get()) {
                if (testCaseName.equals(testCaseFilter.name())) {
                    // Check if the test case signature is in the filter list
                    if (testCaseFilter.signature().replaceAll("\\s+", "").contains(testCaseSignature.replaceAll("\\s+", ""))) {
                        // Check if the body coincides with the test case
                        if (testCaseFilter.body() != null) {
                            Optional<BlockStmt> testCaseBody = originalTestCase.getBody();
                            if (testCaseBody.isPresent()) {
                                // Check if the body of the test case is in the filter list
                                String testCaseBodyString = testCaseBody.get().toString();
                                String testCaseBodyNormalized = testCaseBodyString.replaceAll("\\s+", " ").trim();
                                String testCaseFilterBodyNormalized = testCaseFilter.body().replaceAll("\\s+", " ").trim();
                                if (testCaseBodyNormalized.equals(testCaseFilterBodyNormalized)) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            return found;
        }
        return false;
    }

    /**
     * Process the test class and its test cases and normalize them according to the following policies:
     *
     * <ul>
     *     <li>Integrates the body of auxiliary methods within the test. An auxiliary method is a method invoked within
     *     the test case and defined within the test class, that can contain assertions or whose body can improve the
     *     understandability of the semantic of the test case (improved explainability) for the model that receives the
     *     test case in input. Auxiliary methods can also contain parametric assertions shared among different test
     *     cases. When integrated within a test case they contribute to the generation of additional datapoints, when
     *     the dataset is built.</li>
     *     <li>`Try-catch-finally` blocks are flattened. The block must satisfy strict requirements: the last method in
     *     the try block must be a `fail` method. The penultimate method call is considered as the method that must throw
     *     an exception to check. The `try-catch` block can contain a single catch clause. The `try` block is flattened
     *     (the statements within the `try` block are extracted and added to the reference block of statements). The
     *     penultimate statement within the original `try` block is integrated within an `assertThrows` call and the
     *     expected exception to throw is considered as the exception defined in the catch clause of the original test
     *     case. The statements within the catch clause are flattened as well and inserted after the `assertThrows` in
     *     the normalized reference block. If a `finally` block is present, it is flattened after the catch-clause statements.
     *     If the try block contains resources, they are integrated, before the try statements.</li>
     * </ul>
     *
     * @param config the configuration options for the dataset to generate
     * @param repoRootPath the path to the root of the repository
     * @param testFilePath the path to the test class file
     * @param outputPath the path to the normalized test class file
     * @param testCaseFilterList the list of test cases to process in the class
     * @return the path to the normalized test class file
     */
    public static Pair<Path, List<TestStats>> normalizeTest(
            OraclesDatasetConfig config,
            Path repoRootPath,
            Path testFilePath,
            Path outputPath,
            Optional<List<RepositoryTrack.TestCase>> testCaseFilterList
    ) {
        // Instantiate the list of test statistics
        List<TestStats> testStatsList = new ArrayList<>();
        // Get the relative path of the test file
        String testFileRelativePath = testFilePath.toString().replace(repoRootPath.getParent().toString(), "");
        // Regular output path for report analysis
        Path regularOutputPath = Paths.get(outputPath.toString(), "normalized", testFileRelativePath.replace(".java", NamingConvention.NORMALIZED_TEST_FILE.getConventionName()));
        // Error output path for error analysis
        Path errorOutputPath = Paths.get(outputPath.toString(), "error", "normalized", testFileRelativePath.replace(".java", NamingConvention.NORMALIZED_TEST_FILE.getConventionName()));
        // Test repository output path for further processing
        Path testRepoOutputPath = Paths.get(testFilePath.toString().replace(".java", NamingConvention.NORMALIZED_TEST_FILE.getConventionName()));
        // Create a list to store the normalized test cases generated from the original test cases of the given test class
        List<MethodDeclaration> normalizedTestCases = new ArrayList<>();
        try {
            // Parse the test class
            CompilationUnit cu = JavaParserUtils.getCompilationUnit(testFilePath);
            TypeDeclaration testClass = cu.getPrimaryType().get();
            // Get the junit version used in the test class
            JUnitVersion junitVersion = TestUtils.getJunitVersion(cu.getImports());
            // Get the collection of variables defined in the test class
            HashMap<String, VariableDeclarator> classVariables = JavaParserUtils.collectExistingVariables(new HashMap<>(), testClass.getFields(), List.of(), ClassOrInterfaceDeclaration.class, 0);
            // Add import for all the assertion types at the beginning of the test class, depending on the JUnit version
            // used within the project. This is necessary when the LLMs approaches will generate target assertions not
            // known a priori (the generated test cases would not compile otherwise, if the corresponding assertion is
            // not imported. The import all `*` is inefficient in terms of performances, but prevent this error to happen
            if (junitVersion == JUnitVersion.JUNIT4) {
                cu.addImport("static org.junit.Assert.*");
            } else {
                cu.addImport("static org.junit.jupiter.api.Assertions.*");
            }
            // Distribute the methods of the class according to their meaning
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testClassMethodsDistribution = distributeMethods(testClass.getMethods());
            // Get the list of all the original test cases within the given test class
            List<MethodDeclaration> originalTestCases = testClassMethodsDistribution.getValue0();
            // Get the list of all the auxiliary methods within the given test class
            HashMap<String, MethodDeclaration> auxiliaryMethods = testClassMethodsDistribution.getValue2();
            // Define list of auxiliary methods integrated into the normalized test case body
            // (they have to be removed from the test class since integrated and no more called)
            List<MethodDeclaration> integratedAuxiliaryMethods = new ArrayList<>();
            // Rename all the constructors in the test class according to the name given to the normalized version of the class
            List<ConstructorDeclaration> constructors = testClass.getConstructors();
            for (ConstructorDeclaration constructor: constructors) {
                constructor.setName(constructor.getNameAsString() + NamingConvention.NORMALIZED_TEST_CLASS.getConventionName());
            }
            // Iterate over the original test cases
            for (MethodDeclaration originalTestCase : originalTestCases) {
                // If a filter list of test cases to process exists, check if the test case is in the filter list.
                // This is useful when performing experiments to avoid data leakage problems: excluding from the
                // experiments the test cases added before the training cutoff-date reduces the risk of unfair
                // benchmark generation
                if (testCaseFilterList.isPresent()) {
                    // Check if the test case is not in the filter list of test cases to process, collected during the
                    // tracking of repositories
                    if (!matchTestCaseWithRepoTrackTestCase(originalTestCase, testCaseFilterList)) {
                        // Add a label to ignore the test case, if it is not in the list: in JUnit 4 the `@Ignore`
                        // annotation is used, while in Junit 5 it has been substituted by the `@Disabled` annotation
                        if (junitVersion == JUnitVersion.JUNIT4) {
                            boolean hasIgnore = originalTestCase.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Ignore"));
                            if (!hasIgnore) {
                                originalTestCase.addAnnotation("Ignore");
                                cu.addImport("org.junit.Ignore");
                            }
                        } else {
                            boolean hasDisabled = originalTestCase.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Disabled"));
                            if (!hasDisabled) {
                                originalTestCase.addAnnotation("Disabled");
                                cu.addImport("org.junit.jupiter.api.Disabled");
                            }
                        }
                        normalizedTestCases.add(originalTestCase);
                        continue;
                    }
                }
                // Get the list of statements within the original test case
                List<Statement> originalTestCaseStatements = originalTestCase.getBody().orElseThrow().getStatements();
                // Generate a mold of the test case and set the body to an empty block
                MethodDeclaration normalizedTestCase = originalTestCase.clone();
                BlockStmt normalizedTestCaseBody = normalizedTestCase.getBody().orElseThrow();
                // Normalize test case only if the configuration file require the normalization of the test case
                if (!config.integrateAuxiliaryMethods()) {
                    normalizedTestCaseBody = new BlockStmt();
                    normalizedTestCase.setBody(normalizedTestCaseBody);
                    // Collect the list of the existing variables defined within the test case
                    // Initially copy the references to the global variables defined within the test class
                    // (accessible in all the points)
                    HashMap<String, VariableDeclarator> testCaseExistingVariables = new HashMap<>();
                    for (String classVarName : classVariables.keySet()) {
                        testCaseExistingVariables.put(classVarName, classVariables.get(classVarName));
                    }
                    // Parse the statements block and normalize the test case
                    boolean thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            originalTestCaseStatements,
                            // The reference block is the body of the normalized test case (initially empty)
                            normalizedTestCaseBody,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            // Add the local variables defined within the first block "level" in the test case
                            JavaParserUtils.collectExistingVariables(testCaseExistingVariables, originalTestCase.getBody().get(), MethodDeclaration.class,0),
                            junitVersion
                    );
                    // If an exception occurred during the normalization of the method, keep track of it for future
                    // debugging and analysis
                    if (thrownedException) {
                        collectNormalizationErrors(cu, originalTestCase, errorOutputPath);
                    }
                }
                // Instantiate the test stats builder to collect the statistics of the test
                TestStatsBuilder testStatsBuilder = new TestStatsBuilder();
                testStatsBuilder.setIdentifier(originalTestCase.getNameAsString());
                testStatsBuilder.setSignature(originalTestCase.getSignature().toString());
                testStatsBuilder.setClassIdentifier(testClass.getNameAsString());
                testStatsBuilder.setFilePath(testFilePath.toString());
                testStatsBuilder.setNumberOfAssertions(getNumberOfAssertions(normalizedTestCaseBody.getStatements()));
                testStatsBuilder.setTestLength(normalizedTestCaseBody.toString().length());
                testStatsBuilder.setAssertionsDistribution(getAssertionsDistribution(normalizedTestCaseBody.getStatements()));
                // Count the number of methods found in the test
                int methodCalls = 0;
                for (Statement statement : normalizedTestCaseBody.getStatements()) {
                    MethodCallExprVisitor methodCallExprCollector = new MethodCallExprVisitor();
                    List<MethodCallExpr> methodCallExprs = methodCallExprCollector.visit(statement);
                    methodCalls += methodCallExprs.size();
                }
                testStatsBuilder.setNumberOfMethodCalls(methodCalls);
                VariableDeclarationExprVisitor variableDeclarationExprCollector = new VariableDeclarationExprVisitor();
                List<String> declaredVarList = variableDeclarationExprCollector.visit(normalizedTestCaseBody).stream().map(VariableDeclarationExpr::toString).collect(Collectors.toList());
                NameExprVisitor nameExprCollector = new NameExprVisitor();
                List<String> usedVarList = nameExprCollector.visit(normalizedTestCaseBody).stream().map(NameExpr::toString).collect(Collectors.toList());
                List<String> paramList = normalizedTestCase.getParameters().stream().map(p -> p.getNameAsString()).collect(Collectors.toList());
                Set<String> allVarList = new HashSet<>();
                allVarList.addAll(declaredVarList);
                allVarList.addAll(usedVarList);
                allVarList.addAll(paramList);
                testStatsBuilder.setNumberOfVariables(allVarList.size());
                testStatsList.add(testStatsBuilder.build());
                normalizedTestCases.add(normalizedTestCase);
            }
            //testClass.setName(testClass.getNameAsString() + NamingConvention.NORMALIZED_TEST_CLASS.getConventionName());
            for (MethodDeclaration originalTestCase : originalTestCases) {
                testClass.remove(originalTestCase);
            }
            for (MethodDeclaration integratedAuxiliaryMethod : integratedAuxiliaryMethods) {
                List<AnnotationExpr> annotations = integratedAuxiliaryMethod.getAnnotations();
                boolean isOverride = false;
                for (AnnotationExpr annotation : annotations) {
                    if (annotation.getNameAsString().equals("Override")) {
                        isOverride = true;
                        break;
                    }
                }
                if (!isOverride) {
                    //testClass.remove(integratedAuxiliaryMethod);
                }
            }

            boolean hasSuper = false;
            for (MethodDeclaration normalizedTestCase : normalizedTestCases) {
                for (AnnotationExpr annotation : normalizedTestCase.getAnnotations()) {
                    if (annotation.getNameAsString().equals("Override")) {
                        MethodCallExprVisitor methodCallExprVisitor = new MethodCallExprVisitor();
                        List<MethodCallExpr> methodCallExprs = methodCallExprVisitor.visit(normalizedTestCase);
                        for (MethodCallExpr methodCallExpr : methodCallExprs) {
                            if (methodCallExpr.getNameAsString().equals("super")) {
                                hasSuper = true;
                                break;
                            }
                        }
                    }
                }
                testClass.addMember(normalizedTestCase);
            }

            NodeList<TypeDeclaration<?>> cuTypes = cu.getTypes();
            NodeList<TypeDeclaration<?>> typesToPreserve = new NodeList<>();
            typesToPreserve.add(testClass);
            for(TypeDeclaration typeDeclaration : cuTypes) {
                if (typeDeclaration.isPrivate()) {
                    typesToPreserve.add(typeDeclaration);
                }
            }
            cu.setTypes(typesToPreserve);
            CompilationUnit refactoredCu = refactorCompilationUnitModified(cu, testClass, NamingConvention.NORMALIZED_TEST_CLASS);
            // Save the split test class to the output paths (regular and test repository)
            FilesUtils.writeJavaFile(regularOutputPath, refactoredCu);
            FilesUtils.writeJavaFile(testRepoOutputPath, refactoredCu);
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }
        return new Pair<>(testRepoOutputPath, testStatsList);
    }

    /**
     * Saves information about errors occurred during the normalization of a test class for future analysis and debugging.
     *
     * @param cu the compilation unit of the test class
     * @param originalTestCase the method declaration of the test case that threw the exception during the normalization
     * @param errorOutputPath the path where to save the information about the error to collect
     */
    private static void collectNormalizationErrors(
            CompilationUnit cu,
            MethodDeclaration originalTestCase,
            Path errorOutputPath
    ) throws IOException {
        // Define the error test class compilation unit
        CompilationUnit errorCu = cu;
        // Define the error test class
        TypeDeclaration errorTestClass = cu.getPrimaryType().get();
        // Check if the error test class already exists. Otherwise, create a new one from the original test class
        if (!Files.exists(errorOutputPath)) {
            errorTestClass.setMembers(new NodeList<>());
        } else {
            // Get the error test class from the existing file
            errorCu = JavaParserUtils.getCompilationUnit(errorOutputPath);
            try {
                errorTestClass = errorCu.getPrimaryType().get();
            } catch (NoSuchElementException e) {
                errorTestClass = errorCu.getType(0);
            }
        }
        // Get the list of methods already added in the error test class (if any)
        List<MethodDeclaration> errorTestClassMethods = errorTestClass.getMethods();
        // Check if the error test class already contains the original test case
        if (!errorTestClassMethods.stream().anyMatch(m -> m.getNameAsString().equals(originalTestCase.getNameAsString()))) {
            // Add the original test case to the error test class
            errorTestClass.addMember(originalTestCase);
            // Save the error test class to the error output path
            FilesUtils.writeJavaFile(errorOutputPath, errorCu);
        }
    }

    /**
     * Refactorize the compilation unit modified with the correct replacement of any occurrence of the original test class name.
     * It replaces any occurrence of the original test class name with the new test class name in the compilation unit.
     *
     * @param cu the compilation unit modified
     * @param testClass the primary type of the compilation unit modified
     * @param namingConvention the naming convention to apply to the test class
     * @throws IOException if an I/O error occurs
     * @return the compilation unit modified with the correct replacement of any occurrence of the original test class name
     */
    private static CompilationUnit refactorCompilationUnitModified(CompilationUnit cu, TypeDeclaration testClass, NamingConvention namingConvention) throws IOException {
        String cuContent = cu.toString();
        // Regex pattern with capturing groups
        String regex = "(?<![\\dA-Za-z\\.])(" + testClass.getNameAsString() + ")(?=[\\s.,:;{}()]+)";
        // Replacement string
        String replacement = testClass.getNameAsString();
        if (namingConvention == NamingConvention.TEST_SPLIT_CLASS || namingConvention == NamingConvention.TEST_PREFIX_SPLIT_CLASS) {
            replacement = replacement.replace(NamingConvention.NORMALIZED_TEST_CLASS.getConventionName(), "") + namingConvention.getConventionName();
        } else {
            replacement += namingConvention.getConventionName();
        }


        // Replace all occurrences
        String refactoredContent = cuContent.replaceAll(regex, replacement);
        // Create a new CompilationUnit with the normalized content
        CompilationUnit refactoredCu = JavaParserUtils.getCompilationUnit(refactoredContent);
        return refactoredCu;
    }

    /**
     * Split the test cases in the test class, according to the strategy.
     *
     * If strategy is set to `assertion`, the test is split at any occurrence of an assertion.
     * If strategy is set to `statement`, the test is split at any statement.
     *
     * For example, given the following pseudo test class:
     *
     * <pre>
     *     public class TestClass {
     *          @Test
     *          public void testMethod1() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *              var result3 = method3();
     *              assert2();
     *              assert3();
     *          }
     *
     *          @Test
     *          public void testMethod2() { ... }
     *     }
     * </pre>
     *
     * The STATEMENT strategy will return the following list of methods (in the form of
     * {@link com.github.javaparser.ast.body.MethodDeclaration}):
     *
     * <pre>
     *     [
     *          public void testMethod1_split_0() {
     *              var result1 = method1();
     *          },
     *          public void testMethod1_split_1() {
     *              var result1 = method1();
     *              var result2 = method2();
     *          },
     *          public void testMethod1_split_2() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *          },
     *          public void testMethod1_split_3() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *              var result3 = method3();
     *          }
     *          ...
     *     ]
     * </pre>
     *
     * The ASSERTION stretegy will return the following list of methods (in the form of
     * {@link com.github.javaparser.ast.body.MethodDeclaration}):
     *
     * <pre>
     *     [
     *          public void testMethod1_split_0() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *          },
     *          public void testMethod1_split_1() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *              var result3 = method3();
     *              assert2();
     *         },
     *         public void testMethod1_split_2() {
     *              var result1 = method1();
     *              var result2 = method2();
     *              assert1();
     *              var result3 = method3();
     *              assert2();
     *              assert3();
     *         }
     *    ]
     * </pre>
     *
     * @param config the configuration options for the dataset to generate
     * @param repoRootPath the path to the root of the repository
     * @param testFilePath the path to the test class file
     * @param outputPath the path to the split test class file
     * @throws IllegalStateException if no JUnit version is found in the imports and the test class cannot include
     * assertions
     * @return the path to the split test class file
     */
    public static Pair<Path,Path> splitTest(
            OraclesDatasetConfig config,
            Path repoRootPath,
            Path testFilePath,
            Path outputPath
    ) throws IllegalStateException, UnsupportedOperationException {
        String testFileRelativePath = testFilePath.toString().replace(repoRootPath.getParent().toString(), "");
        // Regular output path for report analysis
        Path regularOutputPath = Paths.get(outputPath.toString(), "regular", testFileRelativePath.replace(NamingConvention.NORMALIZED_TEST_FILE.getConventionName(), NamingConvention.TEST_SPLIT_FILE.getConventionName()));
        Path regularPrefixOutputPath = Paths.get(outputPath.toString(), "regular", testFileRelativePath.replace(NamingConvention.NORMALIZED_TEST_FILE.getConventionName(), NamingConvention.TEST_PREFIX_SPLIT_FILE.getConventionName()));
        // Error output path for error analysis
        Path errorOutputPath = Paths.get(outputPath.toString(), "error", "split", testFileRelativePath.replace(NamingConvention.NORMALIZED_TEST_FILE.getConventionName(), NamingConvention.TEST_SPLIT_FILE.getConventionName()));
        // Test repository output path for further processing
        Path testRepoOutputPath = Paths.get(testFilePath.toString().replace(NamingConvention.NORMALIZED_TEST_FILE.getConventionName(), NamingConvention.TEST_SPLIT_FILE.getConventionName()));
        Path testPrefixRepoOutputPath = Paths.get(testFilePath.toString().replace(NamingConvention.NORMALIZED_TEST_FILE.getConventionName(), NamingConvention.TEST_PREFIX_SPLIT_FILE.getConventionName()));
        // Create a list to store the split test cases generated from the original test cases of the given test class
        List<MethodDeclaration> splitTestCases = new ArrayList<>();
        // Create a list to store the split test cases prefixes generated from the original test cases of the given test class
        List<MethodDeclaration> splitTestCasesPrefixes = new ArrayList<>();
        try {
            // Parse the test class
            CompilationUnit cuTestClass = JavaParserUtils.getCompilationUnit(testFilePath);
            CompilationUnit cuTestPrefixClass = JavaParserUtils.getCompilationUnit(testFilePath);
            TypeDeclaration testClass = cuTestClass.getPrimaryType().get();
            TypeDeclaration testPrefixClass = cuTestPrefixClass.getPrimaryType().get();
            // Get Junit version
            JUnitVersion junitVersion = TestUtils.getJunitVersion(cuTestClass.getImports());
            // Get the list of all the methods defined within the test class
            // List<MethodDeclaration> testClassMethods = cu.findAll(MethodDeclaration.class);
            // testClassMethods = testClassMethods.stream()
            //         .filter(m -> {
            //             Node parent = m.getParentNode().orElse(null);
            //             if (parent instanceof TypeDeclaration<?>) {
            //                 return true;
            //             }
            //             return false;
            //        })
            //        .collect(Collectors.toList());
            // Distribute the methods of the class according to their meaning
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testClassMethodsDistribution = distributeMethods(testClass.getMethods());
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testPrefixClassMethodsDistribution = distributeMethods(testPrefixClass.getMethods());
            // Get the list of all the original test cases within the given test class
            List<MethodDeclaration> originalTestCases = testClassMethodsDistribution.getValue0();
            List<MethodDeclaration> originalTestPrefixCases = testPrefixClassMethodsDistribution.getValue0();
            // Get the list of all the setup and tear down methods within the given test class
            HashMap<String, MethodDeclaration> setUpTearDownMethods = testClassMethodsDistribution.getValue1();
            // Get the list of all the auxiliary methods within the given test class
            HashMap<String, MethodDeclaration> auxiliaryMethods = testClassMethodsDistribution.getValue2();
            // Rename all the constructors in the test class
            List<ConstructorDeclaration> constructors = testClass.getConstructors();
            for (ConstructorDeclaration constructor: constructors) {
                constructor.setName(constructor.getNameAsString().replace(NamingConvention.NORMALIZED_TEST_CLASS.getConventionName(), NamingConvention.TEST_SPLIT_CLASS.getConventionName()));
            }
            // Iterate over the original test cases
            for (MethodDeclaration originalTestCase : originalTestCases) {
                List<AnnotationExpr> annotations = originalTestCase.getAnnotations();

                // Check if the test case is annotated with @Ignore
                boolean isIgnore = false;
                boolean isOverride = false;
                for (AnnotationExpr annotation : annotations) {
                    if (annotation.getNameAsString().equals("Ignore") || annotation.getNameAsString().equals("Disabled")) {
                        isIgnore = true;
                    }
                    if (annotation.getNameAsString().equals("Override")) {
                        isOverride = true;
                    }
                }
                // If the test case is annotated with @Ignore, skip it
                if (isIgnore) {
                    splitTestCases.add(originalTestCase);
                    splitTestCasesPrefixes.add(originalTestCase);
                    continue;
                }

                if (isOverride) {
                    if (!originalTestCase.getType().isVoidType() || originalTestCase.getParameters().size() > 0) {
                        logger.warn("Test case annotated with @Override but not a void method or has parameters. Unable to process it: " + originalTestCase.getNameAsString());
                        splitTestCases.add(originalTestCase);
                        splitTestCasesPrefixes.add(originalTestCase);
                        continue;
                    }
                    MethodDeclaration overrideSupportTestCase = originalTestCase.clone();
                    MethodCallExprVisitor methodCallExprVisitor = new MethodCallExprVisitor();
                    List<MethodCallExpr> methodCallExprs = methodCallExprVisitor.visit(originalTestCase);
                    boolean foundSuper = false;
                    for (MethodCallExpr methodCallExpr : methodCallExprs) {
                        if (methodCallExpr.getNameAsString().equals("super")) {
                            foundSuper = true;
                            break;
                        }
                    }
                    overrideSupportTestCase.setBody(new BlockStmt());
                    BlockStmt overrideTestCaseBody = overrideSupportTestCase.getBody().orElseThrow();
                    BlockStmt originalTestCaseBody = originalTestCase.getBody().orElseThrow();
                    BlockStmt originalTestCaseBodyClone = originalTestCase.getBody().orElseThrow();
                    boolean removedSuper = false;
                    if (foundSuper) {
                        for (Statement statement : originalTestCase.getBody().orElseThrow().getStatements()) {
                            if (statement instanceof ExpressionStmt) {
                                ExpressionStmt expressionStmt = (ExpressionStmt) statement;
                                if (expressionStmt.getExpression() instanceof MethodCallExpr) {
                                    MethodCallExpr methodCallExpr = (MethodCallExpr) expressionStmt.getExpression();
                                    if (methodCallExpr.getNameAsString().equals("super")) {
                                        overrideTestCaseBody.addStatement(statement);
                                        originalTestCaseBody.remove(statement);
                                        removedSuper = true;
                                        break;
                                    }
                                } else {
                                    overrideTestCaseBody.addStatement(statement);
                                    originalTestCaseBody.remove(statement);
                                }
                            } else {
                                overrideTestCaseBody.addStatement(statement);
                                originalTestCaseBody.remove(statement);
                            }
                        }
                    }
                    if (foundSuper && !removedSuper) {
                        logger.warn("Super statement found in the original test case, but unable to remove it: " + originalTestCase.getNameAsString());
                        originalTestCase.setBody(originalTestCaseBodyClone);
                        splitTestCases.add(originalTestCase);
                        splitTestCasesPrefixes.add(originalTestCase);
                        continue;
                    }
                    if (overrideTestCaseBody.getStatements().isEmpty()) {
                        MethodCallExpr println = new MethodCallExpr(new NameExpr("System.out"), "println");
                        println.addArgument("\"Method Overriding!\"");
                        ExpressionStmt stmt = new ExpressionStmt(println);
                        overrideTestCaseBody.getStatements().add(stmt);
                    } else {
                        MethodCallExpr call = new MethodCallExpr(new ThisExpr(), originalTestCase.getNameAsString());
                        ExpressionStmt stmt = new ExpressionStmt(call);
                        overrideTestCaseBody.getStatements().add(0, stmt);
                    }
                    splitTestCases.add(overrideSupportTestCase);
                    splitTestCasesPrefixes.add(overrideSupportTestCase);
                }

                // Get the list of statements within the original test case
                List<Statement> originalTestCaseStatements = originalTestCase.getBody().orElseThrow().getStatements();
                // Parse the statements block and split the test case into multiple test cases
                try {
                    // Generate a mold of the test case and set the body to an empty block
                    MethodDeclaration testCaseMold = originalTestCase.clone();
                    testCaseMold.setBody(new BlockStmt());
                    // Parse the statements of the original test case and split it into multiple test cases
                    try {
                        Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                                config,
                                junitVersion,
                                originalTestCaseStatements,
                                testCaseMold,
                                0,
                                auxiliaryMethods,
                                BlockStatementsType.METHOD_BODY,
                                originalTestCase.getThrownExceptions(),
                                0
                        );
                        // Add the split test cases generated from the original test case to the list
                        splitTestCases.addAll(result.getValue1());
                        // Add the split test cases prefixes generated from the original test case to the list
                        splitTestCasesPrefixes.addAll(result.getValue2());
                    } catch (Exception e) {
                        logger.error("Error parsing test case statements: " + originalTestCase.getNameAsString(), e);
                    }
                } catch (IllegalStateException e) {
                    // Log the error occurred while parsing the statements of the test case
                    logger.error(e.getMessage());
                    // Define the error test class compilation unit
                    CompilationUnit errorCu = cuTestClass.clone();
                    // Define the error test class
                    TypeDeclaration errorTestClass = errorCu.getPrimaryType().get();
                    // Check if the error test class already exists. Otherwise, create a new one from the original test class
                    if (!Files.exists(errorOutputPath)) {
                        errorTestClass.setName(errorTestClass.getNameAsString().replace(NamingConvention.NORMALIZED_TEST_CLASS.getConventionName(), NamingConvention.TEST_SPLIT_CLASS.getConventionName()));
                        errorTestClass.setMembers(new NodeList<>());
                    } else {
                        // Get the error test class from the existing file
                        errorCu = JavaParserUtils.getCompilationUnit(errorOutputPath);
                        errorTestClass = errorCu.getPrimaryType().get();
                    }
                    // Get the list of methods already added in the error test class (if any)
                    List<MethodDeclaration> errorTestClassMethods = errorTestClass.getMethods();
                    // Check if the error test class already contains the original test case
                    if (!errorTestClassMethods.stream().anyMatch(m -> m.getNameAsString().equals(originalTestCase.getNameAsString()))) {
                        // Add the original test case to the error test class
                        errorTestClass.addMember(originalTestCase);
                        // Save the error test class to the error output path
                        FilesUtils.writeJavaFile(errorOutputPath, errorCu);
                    }
                }
            }
            // Set the methods of the test case to the split test methods
            //testClass.setName(testClass.getNameAsString().replace(NamingConvention.NORMALIZED_TEST_CLASS.getConventionName(), NamingConvention.TEST_SPLIT_CLASS.getConventionName()));
            for (MethodDeclaration originalTestCase : originalTestCases) {
                testClass.remove(originalTestCase);
            }
            for (MethodDeclaration originalTestPrefixCase : originalTestPrefixCases) {
                testPrefixClass.remove(originalTestPrefixCase);
            }
            for (MethodDeclaration splitTestCase : splitTestCases) {
                testClass.addMember(splitTestCase);
            }
            for (MethodDeclaration splitTestCasePrefix : splitTestCasesPrefixes) {
                testPrefixClass.addMember(splitTestCasePrefix);
            }
            CompilationUnit refactoredTestClassPrefixesCu = refactorCompilationUnitModified(cuTestPrefixClass, testPrefixClass, NamingConvention.TEST_PREFIX_SPLIT_CLASS);
            CompilationUnit refactoredTestClassCu = refactorCompilationUnitModified(cuTestClass, testClass, NamingConvention.TEST_SPLIT_CLASS);
            // Save the split test class to the output paths (regular and test repository)
            FilesUtils.writeJavaFile(regularOutputPath, refactoredTestClassCu);
            FilesUtils.writeJavaFile(testRepoOutputPath, refactoredTestClassCu);
            FilesUtils.writeJavaFile(regularPrefixOutputPath, refactoredTestClassPrefixesCu);
            FilesUtils.writeJavaFile(testPrefixRepoOutputPath, refactoredTestClassPrefixesCu);
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }
        return new Pair(testRepoOutputPath, testPrefixRepoOutputPath);
    }

    /**
     * Parse the statements of the given block and split the test case into multiple test cases at any occurrence of an
     * assertion. The list of statements represent the statements within a block. Each statement is analyzed through the
     * help of JavaParser.
     * If the statement is a simple expression the statement is processed and added to the current test case.
     * If the statement is an assertion, the assertion is added to the current test case and the current test is
     * completed (a new split test is created, starting from the state of the last test prefix, with the last assertion added,
     * the index of the split test cases is incremented accordingly)
     * If the statement is a complex statement (representing for example a try-catch, a for loop, a while loop, etc.) the
     * statement is analyzed progressively, calling the method recursively, incrementing the recursion level, and defining
     * the type of the block statement to analyze in the next recursion step.
     * The method returns the last test prefix generated, and the list of the intermediary test prefixes, split at any
     * occurrence of an assertion.
     *
     * @param config the configuration options for the dataset to generate
     * @param junitVersion the version of JUnit used in the test class
     * @param statements the list of statements to parse
     * @param testCaseMold the mold of the test case
     * @param startIdx the index of the split test cases
     * @param auxiliaryMethods the auxiliary methods of the test class
     * @param blockStatementsType the type of block statements
     * @param exceptionsToThrow the list of exceptions that could be thrown by the split test case
     * @param recursionLevel the level of recursion of the current block of statements analyzed
     * @return the pair of the last split test case generated and the list of split test cases
     * @throws IllegalStateException if an error occurs while parsing the statements of the block
     */
    private static Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> parseSplitTestStatementsBlock(
            OraclesDatasetConfig config,
            JUnitVersion junitVersion,
            List<Statement> statements,
            MethodDeclaration testCaseMold,
            int startIdx,
            HashMap<String, MethodDeclaration> auxiliaryMethods,
            BlockStatementsType blockStatementsType,
            NodeList<ReferenceType> exceptionsToThrow,
            int recursionLevel
    ) throws IllegalStateException {
        // Create a list to store the split test cases generated from the given block of statements of the original test case
        List<MethodDeclaration> splitTestCases = new ArrayList<>();
        // Create a list to store the split test prefixes generated from the given block of statements of the original test case
        List<MethodDeclaration> splitTestPrefixes = new ArrayList<>();
        // Get the number of assertions in the test case
        int numberOfAssertions = getNumberOfAssertions(statements);
        // Initialize the index of the split test cases (incremental index to name the split test cases)
        int idx = startIdx;
        // Get the name prefix of the original test case
        String testCasePrefixName = testCaseMold.getNameAsString();
        // Initialize the first split test case (clone the test and assign the name to the split test case)
        MethodDeclaration splitTestCase = initializeSplitTestCase(testCaseMold, testCasePrefixName, exceptionsToThrow, idx);
        MethodDeclaration splitTestCasePrefix = splitTestCase.clone();
        // Get the body of the initialized split test case
        BlockStmt splitTestCaseBody = splitTestCase.getBody().get();
        // Iterate over the statements of the original test case
        for (Statement statement : statements) {
            // Create flag to check if the statement is an assertion
            boolean isAssertion = false;
            // Check if the statement is a potential assertion
            if (statement.isAssertStmt()) {
                splitTestCasePrefix = splitTestCase.clone();
                // Add the statement to the current split test case body
                addStatement(statement, splitTestCaseBody, blockStatementsType);
                // Flag the statement as an assertion
                isAssertion = true;
            } else if (statement.isBlockStmt()) {
                // Add a test case with the empty body
                //if (!(blockStatementsType == BlockStatementsType.TRY)) {
                //    // Initialize a new split test case, starting from the body of the previous one
                //    MethodDeclaration newSplitTestCase = initializeSplitTestCase(splitTestCase, testCasePrefixName, ++idx);
                //    // Add the split test case to the list
                //    splitTestCases.add(splitTestCase);
                //    // Update the split test case with the new one
                //    splitTestCase = newSplitTestCase;
                //}
                // Parse the statements of the do body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        statement.asBlockStmt().getStatements(),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        blockStatementsType,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the block statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the do statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the do statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else if (statement.isDoStmt()) {
                // Parse the do statement and split the test case into multiple test cases
                Statement doStmtBody = statement.asDoStmt().getBody();
                // Clone the do statement and set the body to an empty block
                DoStmt doStmtClone = statement.asDoStmt().clone();
                doStmtClone.setBody(new BlockStmt());
                splitTestCasePrefix = splitTestCase.clone();
                // Add the cloned do statement to the current split test case body
                addStatement(doStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the do body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        new ArrayList<>(Arrays.asList(doStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.DO,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the do statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the do statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the do statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else if (statement.isExpressionStmt()) {
                // Get the expression from the statement
                Expression currentExpr = statement.asExpressionStmt().getExpression();
                // Check if the expression is a variable declaration expression and extract the initializer, if present
                if (currentExpr.isVariableDeclarationExpr()) {
                    Optional<Expression> initializer = currentExpr.asVariableDeclarationExpr().getVariable(0).getInitializer();
                    if (initializer.isPresent()) {
                        currentExpr = initializer.get();
                    }
                }
                // Check if the expression is an assertion
                if (currentExpr.isMethodCallExpr() && JUnitAssertionType.isJUnitAssertion(currentExpr.asMethodCallExpr().getNameAsString())) {
                    // Add the statement to the flat body
                    if (!(JUnitAssertionType.fromString(((MethodCallExpr) currentExpr).getNameAsString()) == JUnitAssertionType.ASSERT_THROWS)) {
                        splitTestCasePrefix = splitTestCase.clone();
                        addStatement(statement, splitTestCaseBody, blockStatementsType);
                        // Flag the statement as an assertion
                        isAssertion = true;
                    } else {
                        if (config.assertThrowsStrategy() == AssertThrowsStrategyType.STANDARD) {
                            splitTestCasePrefix = splitTestCase.clone();
                            // Add the statement to the flat body
                            addStatement(statement, splitTestCaseBody, blockStatementsType);
                            // Flag the statement as an assertion
                            isAssertion = true;
                        } else {
                            BlockStmt newSplitTestCaseBody = splitTestCaseBody.clone();
                            splitTestCasePrefix = splitTestCase.clone();
                            addStatement(statement, newSplitTestCaseBody, blockStatementsType);
                            MethodCallExpr assertThrowsMethodCallExpr = statement.asExpressionStmt().getExpression().asMethodCallExpr();
                            Expression expr;
                            com.github.javaparser.ast.type.Type exceptionType = null;
                            NodeList<Expression> assertThrowsArguments = assertThrowsMethodCallExpr.getArguments();
                            if (junitVersion == JUnitVersion.JUNIT4) {
                                expr = assertThrowsArguments.getLast().get();
                                exceptionType = ((ClassExpr) assertThrowsArguments.get(assertThrowsArguments.size() - 2)).getType();;
                            } else {
                                exceptionType = ((ClassExpr) assertThrowsArguments.get(assertThrowsArguments.size() - 2)).getType();;
                                expr = assertThrowsMethodCallExpr.getArguments().get(1);
                            }

                            if (exceptionType.isReferenceType()) {
                                splitTestCase.addThrownException(exceptionType.asReferenceType());
                            }

                            if (expr.isMethodCallExpr()) {
                                ExpressionStmt exprStmt = new ExpressionStmt();
                                exprStmt.setExpression(expr.clone());
                                splitTestCasePrefix = splitTestCase.clone();
                                addStatement(exprStmt, splitTestCaseBody, blockStatementsType);
                            } else if (expr.isLambdaExpr()) {
                                LambdaExpr lambdaExpr = expr.asLambdaExpr();
                                if (lambdaExpr.getParameters().size() > 0) {
                                    throw new IllegalStateException("Unexpected assertThrows lambda expression with parameters not supported");
                                }
                                Optional<Expression> lambdaExprBody = lambdaExpr.getExpressionBody();

                                if (lambdaExprBody.isPresent()) {
                                    // The lambda expression is not a block statement but a single expression
                                    Expression lambdaSingleExpr = lambdaExprBody.get();
                                    if (lambdaSingleExpr.isMethodCallExpr()) {
                                        ExpressionStmt exprStmt = new ExpressionStmt();
                                        exprStmt.setExpression(lambdaSingleExpr.clone());
                                        splitTestCasePrefix = splitTestCase.clone();
                                        addStatement(exprStmt, splitTestCaseBody, blockStatementsType);
                                        if (JUnitAssertionType.isJUnitAssertion(lambdaSingleExpr.asMethodCallExpr().getNameAsString()) || config.splitStrategy() == SplitStrategyType.STATEMENT) {
                                            // Initialize a new split test case, starting from the body of the previous one
                                            MethodDeclaration newSplitTestCase = initializeSplitTestCase(splitTestCase, testCasePrefixName, exceptionsToThrow, ++idx);
                                            // Add the split test case to the list
                                            splitTestCases.add(splitTestCase);
                                            // Add the split test prefix to the list
                                            splitTestPrefixes.add(splitTestCasePrefix);
                                            // Update the split test case with the new one
                                            splitTestCase = newSplitTestCase;
                                            // Get the body of the new split test case
                                            splitTestCaseBody = splitTestCase.getBody().orElseThrow();
                                        }
                                    } else {
                                        throw new IllegalStateException("Unexpected assertThrows lambda expression with non-method call expression");
                                    }
                                } else {
                                    // The lambda expression is a block statement
                                    Statement lambdaStmt = lambdaExpr.getBody();
                                    if (lambdaStmt.isBlockStmt()) {
                                        BlockStmt blockStmt = lambdaStmt.asBlockStmt();
                                        Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                                                config,
                                                junitVersion,
                                                blockStmt.getStatements(),
                                                splitTestCase,
                                                idx,
                                                auxiliaryMethods,
                                                blockStatementsType,
                                                exceptionsToThrow,
                                                recursionLevel + 1
                                        );
                                        // Update the split test case with the last generated from the block statement
                                        splitTestCase = result.getValue0();
                                        // Update the split test prefix
                                        splitTestCasePrefix = splitTestCase.clone();
                                        // Update the split test case body
                                        splitTestCaseBody = splitTestCase.getBody().get();
                                        // Add the split test cases generated from the block statement to the list
                                        splitTestCases.addAll(result.getValue1());
                                        // Add the split test prefixes generated from the block statement to the list
                                        splitTestPrefixes.addAll(result.getValue2());
                                        // Update idx of the split tests
                                        idx += result.getValue1().size();
                                    } else {
                                        throw new IllegalStateException("Unexpected assertThrows lambda expression with non-block statement");
                                    }
                                }
                            }
                            //Statement fakeStmt = new AssertStmt(new StringLiteralExpr(FAKE_ELEMENT_LABEL), new StringLiteralExpr(FAKE_ELEMENT_LABEL));
                            Statement fakeStmt = new ExpressionStmt(new MethodCallExpr("fail", new StringLiteralExpr(THROW_EXCEPTION_LABEL)));
                            fakeStmt.addOrphanComment(new LineComment(THROW_EXCEPTION_LABEL));
                            splitTestCasePrefix = splitTestCase.clone();
                            addStatement(fakeStmt, splitTestCaseBody, blockStatementsType);
                            splitTestCases.add(splitTestCase);
                            splitTestPrefixes.add(splitTestCasePrefix);
                            MethodDeclaration newSplitTestCase = initializeSplitTestCase(splitTestCase, testCasePrefixName, exceptionsToThrow, ++idx);
                            newSplitTestCase.setBody(newSplitTestCaseBody);
                            // Initialize a new split test case, starting from the body of the previous one
                            // Update the split test case with the new one
                            splitTestCase = newSplitTestCase;
                            // Get the body of the new split test case
                            splitTestCaseBody = newSplitTestCaseBody;
                        }
                    }
                } else if (currentExpr.isLambdaExpr()) {
                    LambdaExpr lambdaExpr = currentExpr.asLambdaExpr();
                    Optional<Expression> lambdaExprBody = lambdaExpr.getExpressionBody();
                    if (lambdaExprBody.isPresent()) {
                        // The lambda expression is not a block statement but a single expression
                        currentExpr = lambdaExprBody.get();
                        if (currentExpr.isMethodCallExpr()) {
                            if (JUnitAssertionType.isJUnitAssertion(currentExpr.asMethodCallExpr().getNameAsString())) {
                                // Add the statement to the flat body
                                if (!(JUnitAssertionType.fromString(((MethodCallExpr) currentExpr).getNameAsString()) == JUnitAssertionType.ASSERT_THROWS)) {
                                    splitTestCasePrefix = splitTestCase.clone();
                                    addStatement(statement, splitTestCaseBody, blockStatementsType);
                                } else {
                                    throw new IllegalStateException("assertThrows within lambda expression, not supported");
                                }
                                // Flag the statement as an assertion
                                isAssertion = true;
                            } else {
                                splitTestCasePrefix = splitTestCase.clone();
                                // Add the statement to the flat body
                                addStatement(statement, splitTestCaseBody, blockStatementsType);
                            }
                        } else {
                            splitTestCasePrefix = splitTestCase.clone();
                            addStatement(statement, splitTestCaseBody, blockStatementsType);
                        }
                    } else {
                        Statement lambdaBody = lambdaExpr.getBody();
                        LambdaExpr lambdaExprClone = lambdaExpr.clone();
                        lambdaExprClone.setParameters(new NodeList<>(lambdaExpr.getParameters()));
                        ExpressionStmt lambdaExprStmt = new ExpressionStmt(lambdaExpr.clone());
                        splitTestCasePrefix = splitTestCase.clone();
                        addStatement(lambdaExprStmt, splitTestCaseBody, blockStatementsType);
                        Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                                config,
                                junitVersion,
                                new ArrayList<>(Arrays.asList(lambdaBody)),
                                splitTestCase,
                                idx,
                                auxiliaryMethods,
                                BlockStatementsType.LAMBDA,
                                exceptionsToThrow,
                                recursionLevel + 1
                        );
                        // Update the split test case with the last generated from the lambda expression
                        splitTestCase = result.getValue0();
                        // Update the split test prefix
                        splitTestCasePrefix = splitTestCase.clone();
                        // Update the split test case body
                        splitTestCaseBody = splitTestCase.getBody().get();
                        // Add the split test cases generated from the lambda expression to the list
                        splitTestCases.addAll(result.getValue1());
                        // Add the split test prefixes generated from the lambda expression to the list
                        splitTestPrefixes.addAll(result.getValue2());
                        // Update idx of the split tests
                        idx += result.getValue1().size();
                    }
                } else {
                    if (currentExpr.findAll(BlockStmt.class).isEmpty()) {
                        splitTestCasePrefix = splitTestCase.clone();
                        addStatement(statement, splitTestCaseBody, blockStatementsType);
                    } else {
                        if (currentExpr.findAll(BlockStmt.class).size() > 1) {
                            throw new IllegalStateException("Unexpected multiple block statement within expression statement, not supported.");
                        }
                        BlockStmt blockStmt = currentExpr.findAll(BlockStmt.class).get(0);
                        if (!blockStmt.getParentNode().isPresent() || ! (blockStmt.getParentNode().get() instanceof LambdaExpr)) {
                            throw new IllegalStateException("Unexpected block statement parent node of block in expression different from lambda expression, not supported.");
                        }
                        ExpressionStmt stmtClone = null;
                        Node parentNode = currentExpr.getParentNode().get();
                        while (parentNode != null) {
                            if (parentNode instanceof ExpressionStmt) {
                                stmtClone = ((ExpressionStmt) parentNode).clone();
                                break;
                            }
                            parentNode = parentNode.getParentNode().orElse(null);
                        }
                        if (stmtClone == null) {
                            throw new IllegalStateException("Unexpected block statement parent node of block in expression different from statement, not supported.");
                        }
                        stmtClone.findAll(BlockStmt.class).get(0).setStatements(new NodeList<>());
                        splitTestCasePrefix = splitTestCase.clone();
                        addStatement(stmtClone, splitTestCaseBody, blockStatementsType);
                        Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                                config,
                                junitVersion,
                                blockStmt.getStatements(),
                                splitTestCase,
                                idx,
                                auxiliaryMethods,
                                BlockStatementsType.LAMBDA,
                                exceptionsToThrow,
                                recursionLevel + 1
                        );
                        // Update the split test case with the last generated from the block statement
                        splitTestCase = result.getValue0();
                        // Update the split test prefix
                        splitTestCasePrefix = splitTestCase.clone();
                        // Update the split test case body
                        splitTestCaseBody = splitTestCase.getBody().get();
                        // Add the split test cases generated from the block statement to the list
                        splitTestCases.addAll(result.getValue1());
                        // Add the split test prefixes generated from the block statement to the list
                        splitTestPrefixes.addAll(result.getValue2());
                        // Update idx of the split tests
                        idx += result.getValue1().size();
                    }
                }
            } else if (statement.isForEachStmt()) {
                // Parse the for each statement and split the test case into multiple test cases
                Statement forEachStmtBody = statement.asForEachStmt().getBody();
                // Clone the for each statement and set the body to an empty block
                ForEachStmt forEachStmtClone = statement.asForEachStmt().clone();
                forEachStmtClone.setBody(new BlockStmt());
                splitTestCasePrefix = splitTestCase.clone();
                // Add the cloned for each statement to the current split test case body
                addStatement(forEachStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the for each body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        new ArrayList<>(Arrays.asList(forEachStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.FOR_EACH,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the for each statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the for each statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the for each statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else if (statement.isForStmt()) {
                // Parse the for statement and split the test case into multiple test cases
                Statement forStmtBody = statement.asForStmt().getBody();
                // Clone the for statement and set the body to an empty block
                ForStmt forStmtClone = statement.asForStmt().clone();
                forStmtClone.setBody(new BlockStmt());
                splitTestCasePrefix = splitTestCase.clone();
                // Add the cloned for statement to the current split test case body
                addStatement(forStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the for body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        new ArrayList<>(Arrays.asList(forStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.FOR,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the for statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the for statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the for statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else if (statement.isIfStmt()) {
                IfStmt ifStmt = statement.asIfStmt();
                List<Pair<Optional<Expression>,Statement>> ifStmts = new ArrayList<>();
                ifStmts.add(new Pair<>(Optional.of(ifStmt.getCondition()),ifStmt.getThenStmt()));
                if (ifStmt.getElseStmt().isPresent()) {
                    Optional<Statement> elseIfStmt = ifStmt.getElseStmt();
                    while (elseIfStmt.isPresent()) {
                        if (elseIfStmt.get().isIfStmt()) {
                            ifStmts.add(new Pair<>(Optional.of(elseIfStmt.get().asIfStmt().getCondition()), elseIfStmt.get().asIfStmt().getThenStmt()));
                        } else {
                            ifStmts.add(new Pair<>(Optional.empty(), elseIfStmt.get()));
                        }
                        elseIfStmt = elseIfStmt.get().isIfStmt() ? elseIfStmt.get().asIfStmt().getElseStmt() : Optional.empty();
                    }
                }
                // Define if statement clone
                IfStmt ifStmtClone = null;
                for (Pair<Optional<Expression>,Statement> ifStmtPair : ifStmts) {
                    Optional<Expression> ifCondition = ifStmtPair.getValue0();
                    Statement ifStmtBody = ifStmtPair.getValue1();
                    boolean rootIf = false;
                    if (ifCondition.isPresent()) {
                        if (ifStmtClone == null) {
                            rootIf = true;
                            ifStmtClone = new IfStmt(ifCondition.get(), new BlockStmt(), null);
                            splitTestCasePrefix = splitTestCase.clone();
                            // Add the cloned if statement to the current split test case body
                            addStatement(ifStmtClone, splitTestCaseBody, blockStatementsType);
                        } else {
                            IfStmt lastIfStmt = traverseIfElseStatement(ifStmtClone);
                            lastIfStmt.setElseStmt(new IfStmt(ifCondition.get(), new BlockStmt(), null));
                        }
                    } else {
                        IfStmt lastIfStmt = traverseIfElseStatement(ifStmtClone);
                        lastIfStmt.setElseStmt(new BlockStmt());
                    }
                    // Parse the statements of the if body and split it into multiple test cases
                    Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                            config,
                            junitVersion,
                            new ArrayList<>(Arrays.asList(ifStmtBody)),
                            splitTestCase,
                            idx,
                            auxiliaryMethods,
                            rootIf ? BlockStatementsType.IF : BlockStatementsType.ELSE,
                            exceptionsToThrow,
                            recursionLevel + 1
                    );
                    // Update the split test case with the last generated from the if statement
                    splitTestCase = result.getValue0();
                    // Update the split test prefix
                    splitTestCasePrefix = splitTestCase.clone();
                    // Update the split test case body
                    splitTestCaseBody = splitTestCase.getBody().get();
                    // Add the split test cases generated from the if statement to the list
                    splitTestCases.addAll(result.getValue1());
                    // Add the split test prefixes generated from the if statement to the list
                    splitTestPrefixes.addAll(result.getValue2());
                    // Update idx of the split tests
                    idx += result.getValue1().size();
                    // Update the if statement clone
                    ifStmtClone = JavaParserUtils.getLastStatementTypeOccurrence(splitTestCaseBody, IfStmt.class)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No statement of type " + IfStmt.class + " found in the block of statements."
                            ))
                            .asIfStmt();
                }
            } else if (statement.isSynchronizedStmt()) {
                // Parse the synchronized statement and split the test case into multiple test cases
                Statement synchronizedStmtBody = statement.asSynchronizedStmt().getBody();
                // Clone the synchronized statement and set the body to an empty block
                SynchronizedStmt synchronizedStmtClone = statement.asSynchronizedStmt().clone();
                synchronizedStmtClone.setBody(new BlockStmt());
                splitTestCasePrefix = splitTestCase.clone();
                // Add the cloned synchronized statement to the current split test case body
                addStatement(synchronizedStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the synchronized body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        new ArrayList<>(Arrays.asList(synchronizedStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.METHOD_BODY,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the synchronized statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the synchronized statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the synchronized statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else if (statement.isSwitchStmt()) {
                throw new UnsupportedOperationException("Switch statement not supported yet.");
                // TODO: Implement switch statement
                // Parse the switch statement and split the test case into multiple test cases
                // SwitchStmt switchStmt = statement.asSwitchStmt();
                // for (SwitchEntry switchEntry : switchStmt.getEntries()) {
                //     // Clone the switch statement and set the body to an empty block
                //     SwitchStmt switchStmtClone = statement.asSwitchStmt().clone();
                //     switchStmtClone.setEntries(new NodeList<>());
                //     // Clone the current switch entry and set the statements to an empty block
                //     SwitchEntry switchEntryClone = switchEntry.clone();
                //     switchEntryClone.setStatements(new NodeList<>());
                //     // Add the cloned switch entry to the cloned switch statement
                //     switchStmtClone.getEntries().add(switchEntryClone);
                //     // Add the cloned switch statement to the current split test case body
                //     addStatement(switchStmtClone, splitTestCaseBody, blockStatementsType);
                //     // Parse the statements of the switch entry and split it into multiple test cases
                //     Pair<MethodDeclaration, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                //             config,
                //             junitVersion,
                //             switchEntry.getStatements(),
                //             splitTestCase,
                //             idx,
                //             auxiliaryMethods,
                //             BlockStatementsType.SWITCH,
                //             recursionLevel + 1
                //     );
                //     // Update the split test case with the last generated from the switch entry
                //     splitTestCase = result.getValue0();
                //     // Update the split test case body
                //     splitTestCaseBody = splitTestCase.getBody().get();
                //     // Add the split test cases generated from the switch entry to the list
                //     splitTestCases.addAll(result.getValue1());
                //     // Update idx of the split tests
                //     idx += result.getValue1().size();
                // }
            } else if (statement.isTryStmt()) {
                // Try/Catch statements should not be present anymore after normalization
                // from try/catch to assertThrows
                throw new IllegalStateException("Try statement should not appear in a test after the normalization.");
            } else if (statement.isWhileStmt()) {
                // Parse the while statement and split the test case into multiple test cases
                Statement whileStmtBody = statement.asWhileStmt().getBody();
                // Clone the while statement and set the body to an empty block
                WhileStmt whileStmtClone = statement.asWhileStmt().clone();
                whileStmtClone.setBody(new BlockStmt());
                splitTestCasePrefix = splitTestCase.clone();
                // Add the cloned while statement to the current split test case body
                addStatement(whileStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the while body and split it into multiple test cases
                Triplet<MethodDeclaration, List<MethodDeclaration>, List<MethodDeclaration>> result = parseSplitTestStatementsBlock(
                        config,
                        junitVersion,
                        new ArrayList<>(Arrays.asList(whileStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.WHILE,
                        exceptionsToThrow,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the while statement
                splitTestCase = result.getValue0();
                // Update the split test prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the while statement to the list
                splitTestCases.addAll(result.getValue1());
                // Add the split test prefixes generated from the while statement to the list
                splitTestPrefixes.addAll(result.getValue2());
                // Update idx of the split tests
                idx += result.getValue1().size();
            } else {
                splitTestCasePrefix = splitTestCase.clone();
                // Add the statement to the current split test case body
                addStatement(statement, splitTestCaseBody, blockStatementsType);
                // continue;
            }
            if ((isAssertion && config.splitStrategy() == SplitStrategyType.ASSERTION) || config.splitStrategy() == SplitStrategyType.STATEMENT) {
                if (splitTestCases.size() > 0) {
                    MethodDeclaration lastAddedSplitTestCase = splitTestCases.get(splitTestCases.size() - 1);
                    if(lastAddedSplitTestCase.getBody().get().equals(splitTestCaseBody)) {
                        continue;
                    }
                }
                // Initialize a new split test case, starting from the body of the previous one
                MethodDeclaration newSplitTestCase = initializeSplitTestCase(splitTestCase, testCasePrefixName, exceptionsToThrow, ++idx);

                // List<Comment> comments = newSplitTestCase.getAllContainedComments();
                // for (Comment comment : comments) {
                //     if (comment.isLineComment()) {
                //         String commentContent = comment.getContent();
                //         if (commentContent.equals(TestUtils.THROW_EXCEPTION_LABEL)) {
                //             newSplitTestCase.getBody().get().remove(comment.getParentNode().get());
                //         }
                //     }
                // }
                // Add the split test case to the list
                splitTestCases.add(splitTestCase);
                // Add the split test prefix to the list
                splitTestPrefixes.add(splitTestCasePrefix);
                // Update the split test case with the new one
                splitTestCase = newSplitTestCase;
                // Update the split test case prefix
                splitTestCasePrefix = splitTestCase.clone();
                // Get the body of the new split test case
                splitTestCaseBody = splitTestCase.getBody().orElseThrow();
            }
        }
        // Check if the number of split test cases generated is equal to the number of assertions in the original test case
        if ((numberOfAssertions != splitTestCases.size() && config.splitStrategy() == SplitStrategyType.ASSERTION)) {
            String errMsg = "Unexpected number of split test cases generated from test case " + testCasePrefixName + ". Expected: " + numberOfAssertions + ", Found: " + splitTestCases.size();
            if (config.numAssertionsMatchStrategy() == NumAssertionsMatchStrategyType.STRICT) {
                throw new IllegalStateException(errMsg);
            } else if (config.numAssertionsMatchStrategy() == NumAssertionsMatchStrategyType.LOOSE) {
                // Check if the number of assertions is greater than the number of split test cases
                logger.warn(errMsg);
            }
        }
        // Check if the current split test case has more statements than the last split test case added to the list
        if (splitTestCases.size() > 0 && recursionLevel == 0 && config.keepStatementsAfterLastAssertion()) {
            MethodDeclaration lastAddedSplitTestCase = splitTestCases.get(splitTestCases.size() - 1);
            if(!lastAddedSplitTestCase.getBody().get().equals(splitTestCaseBody)) {
                // Add the last split test case to the list
                splitTestCases.add(splitTestCase);
                splitTestPrefixes.add(splitTestCasePrefix);
            }
        }
        // Return the list of split test cases generated
        return new Triplet<>(splitTestCase, splitTestCases, splitTestPrefixes);
    }

    /**
     * Normalizes a list of statements appending them to the reference block of statements {@code currentBlockStmt} passed
     * to the function. The method iterates over each statement and normalizes them according to the
     * following policies:
     *
     * <ul>
     *     <li>If the statement is an assertion in the form of {@link AssertStmt}, includes it in the reference block of
     *     statements {@code currentBlockStmt}.</li>
     *     <li>If the statement refers to a block of statements {@link BlockStmt}, process the statements within the
     *     block calling the function recursively.</li>
     *     <li>If the statement is a `do` statement {@link DoStmt}, the `do` wrapper with the condition is cloned and the
     *     body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `do` wrapper.</li>
     *     <li>If the statement is an expression statement {@link ExpressionStmt}, it is analyzed and added to the reference
     *     block of statements, according to their type: (i) if the expression refers to a method call to an auxiliary method,
     *     the body of the method is integrated in the reference block of statements (if possible); (ii) if the expression
     *     is a lambda expression, the signature and the parameters of the lambda are cloned and the body of the lambda
     *     expression is processed recursively to be normalized as well; (iii) if the expression refers to a variable
     *     declaration, the statement is added to the reference block as it is; (iv) any other type of expression could
     *     contain method calls that cannot be integrated within the reference block of statements. Therefore they are
     *     added to the reference block as they are</li>
     *     <li>If the statement is a `forEach` statement {@link ForEachStmt} the `foreach` wrapper with the condition
     *     is cloned and the body is processed as a block statement calling the function recursively. The result of the
     *     recursion is appended to the body of the cloned `do` wrapper.</li>
     *     <li>If the statement is a `for` statement {@link ForStmt} the `for` wrapper with the condition is cloned and
     *     the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `do` wrapper.</li>
     *     <li>If the statement is an `if-then-else` statement {@link IfStmt} each block of `if`, `if-else`, `else` is
     *     processed: the conditions are cloned and each block processed recursively to be normalized.</li>
     *     <li>If the statement is a synchronized statement {@link SynchronizedStmt} the `synchronized` wrapper is cloned
     *     and the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `synchronized` wrapper.</li>
     *     <li>If the statement is a switch statement {@link SwitchStmt}, an exception is thrown: no switch statements are
     *     expected within a test case. The statement process is therefore no supported yet.</li>
     *     <li>If the statement is a `try-catch-finally` statement {@link TryStmt}, the block is normalized and flattened.
     *     The block must satisfy strict requirements: the last method in the try block must be a `fail` method. The
     *     penultimate method call is considered as the method that must throw an exception to check. The `try-catch` block
     *     can contain a single catch clause. The `try` block is flattened (the statements within the `try` block are
     *     extracted and added to the reference block of statements). The penultimate statement within the original `try`
     *     block is integrated within an `assertThrows` call and the expected exception to throw is considered as the
     *     exception defined in the catch clause of the original test case. The statements within the catch clause are
     *     flattened as well and inserted after the `assertThrows` in the normalized reference block. If a `finally`
     *     block is present, it is flattened after the catch-clause statements. If the try block contains resources, they
     *     are integrated, before the try statements.</li>
     *     <li>If the statement is a `while` statement {@link WhileStmt} the `while` wrapper with the condition is cloned and
     *     the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `while` wrapper.</li>
     *     <li>For any other statement type, they are added to the normalized reference block as they are.</li>
     * </ul>
     *
     * @param testClass the test class where the original test case to normalize has been defined
     * @param originalTestCase the method declaration of the original test case that must be normalized
     * @param statements the list of statements to process
     * @param currentBlockStmt the reference block of statements representing the normalized body of the test case
     * @param auxiliaryMethods the list of methods defined within the test class that are not test cases and can contain
     *                         assertions. The bodies of auxiliary methods containing assertions are integrated within
     *                         the normalized version of the test case
     * @param integratedAuxiliaryMethods a list of auxiliary methods already integrated within the normalized version of
     *                                   the test case
     * @param existingVariables the collection of global variables already defined within the test class and local variables
     *                          already defined within the normalized version of the test case
     * @param junitVersion the version of JUnit imported and used within the test class
     * @return A boolean. True if an exception has been thrown during the normalization of the block of statements, false
     * otherwise.
     * @throws IllegalStateException In case of error when processing an `if` statement
     */
    private static boolean parseNormalizeTestStatementsBlock(
            TypeDeclaration testClass,
            MethodDeclaration originalTestCase,
            List<Statement> statements,
            BlockStmt currentBlockStmt,
            HashMap<String, MethodDeclaration> auxiliaryMethods,
            List<MethodDeclaration> integratedAuxiliaryMethods,
            HashMap<String, VariableDeclarator> existingVariables,
            JUnitVersion junitVersion
    ) throws IllegalStateException {
        boolean thrownedException = false;
        // Iterate over the statements of the original test case
        for (Statement statement : statements) {
            try {
                // Check if the statement is a potential assertion
                if (statement.isAssertStmt()) {
                    currentBlockStmt.addStatement(statement);
                } else if (statement.isBlockStmt()) {
                    // Parse the statements of the block and add them to the current block
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            statement.asBlockStmt().getStatements(),
                            currentBlockStmt,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isDoStmt()) {
                    // Parse the do statement
                    Statement doStmtBody = statement.asDoStmt().getBody();
                    // Clone the do statement and set the body to an empty block
                    DoStmt doStmtClone = statement.asDoStmt().clone();
                    BlockStmt doStmtCloneBody = new BlockStmt();
                    doStmtClone.setBody(doStmtCloneBody);
                    // Add the cloned do statement to the current block
                    currentBlockStmt.addStatement(doStmtClone);
                    // Parse the statements of the do body and add them to the cloned do statement
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(doStmtBody)),
                            // The reference block is now the body of the cloned `do` wrapper
                            doStmtCloneBody,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isExpressionStmt()) {
                    Expression exprStmt = statement.asExpressionStmt().getExpression();
                    // Check if the expression is a method call to an auxiliary method (not an assertion)
                    if (exprStmt.isMethodCallExpr() && !(JUnitAssertionType.isJUnitAssertion(exprStmt.asMethodCallExpr().getNameAsString()))) {
                        try {
                            // Try to integrate the body of the auxiliary method within the current block of statements
                            // Auxiliary methods can contain additional assertions
                            Optional<MethodDeclaration> auxiliaryMethodDeclaration = exprStmt.asMethodCallExpr().getScope().isEmpty() ? integrateAuxiliaryMethodIntoBlockStmt(testClass, currentBlockStmt, exprStmt.asMethodCallExpr(), auxiliaryMethods, existingVariables) : Optional.empty();
                            if (auxiliaryMethodDeclaration.isPresent()) {
                                // Add the method to the list of auxiliary methods integrated within the test case
                                integratedAuxiliaryMethods.add(auxiliaryMethodDeclaration.get());
                            } else {
                                // If the body of the invoked method cannot be integrated within the test case,
                                // simply add the expression statement to the current body of the normalized test
                                // case
                                currentBlockStmt.addStatement(statement);
                            }
                        } catch (Exception e) {
                            // Log the error occurred while parsing the statements of the test case
                            logger.error(e.getMessage());
                            // If any exception is thrown during the integration of the auxiliary method within the
                            // body of the normalized test case, simply skip the integration and add the expression
                            // statement to the body
                            currentBlockStmt.addStatement(statement);
                            thrownedException = true;
                        }
                    } else if (exprStmt.isLambdaExpr()) {
                        // Clone the lambda statement and the lambda parameters
                        // The body of the clone lambda statement is set to an empty body
                        LambdaExpr lambdaExpr = exprStmt.asLambdaExpr();
                        Statement lambdaBody = lambdaExpr.getBody();
                        LambdaExpr lambdaExprClone = lambdaExpr.clone();
                        BlockStmt lambdaBodyClone = new BlockStmt();
                        lambdaExprClone.setParameters(new NodeList<>(lambdaExpr.getParameters()));
                        lambdaExprClone.setBody(lambdaBodyClone);
                        // The cloned lambda statement is added to the reference block of statements
                        // It's body is still empty, but it is referenced and passed recursively to the
                        // function to be fulfilled after processing each statement within the original
                        // lambda body
                        ExpressionStmt lambdaExprStmt = new ExpressionStmt(lambdaExprClone);
                        currentBlockStmt.addStatement(lambdaExprStmt);
                        // Check if the lambda contains a single statement or if it is composed of a block of statements
                        if (lambdaBody.isExpressionStmt()) {
                            try {
                                // If the original lambda only contains a single statement (a method call), try to
                                // integrate the body of the auxiliary method within the cloned lambda body
                                Optional<MethodDeclaration> auxiliaryMethodDeclaration = integrateAuxiliaryMethodIntoBlockStmt(testClass, lambdaBodyClone, lambdaBody.asExpressionStmt().getExpression().asMethodCallExpr(), auxiliaryMethods, existingVariables);
                                if (auxiliaryMethodDeclaration.isPresent()) {
                                    integratedAuxiliaryMethods.add(auxiliaryMethodDeclaration.get());
                                } else {
                                    // If the integration fails, clone the original body and set it as the new body
                                    lambdaExprClone.setBody(lambdaBody.clone());
                                }
                            } catch (Exception e) {
                                // Log the error occurred while parsing the statements of the test case
                                logger.error(e.getMessage());
                                thrownedException = true;
                                // If the integration fails because of an exception, clone the original body and set
                                // it as the new body
                                lambdaExprClone.setBody(lambdaBody.clone());
                            }
                        } else {
                            // Parse the statements of the lambda body and add them to the cloned lambda expression
                            thrownedException = parseNormalizeTestStatementsBlock(
                                    testClass,
                                    originalTestCase,
                                    new ArrayList<>(Arrays.asList(lambdaBody)),
                                    // The reference block is now the body of the cloned lambda
                                    lambdaBodyClone,
                                    auxiliaryMethods,
                                    integratedAuxiliaryMethods,
                                    existingVariables,
                                    junitVersion
                            );
                        }
                    } else if (exprStmt.isVariableDeclarationExpr()) {
                        // Variable declarations are added to the reference block of statements as they were in the
                        // original test case
                        currentBlockStmt.addStatement(statement);
                    } else {
                        // Any other type of expression should not contain method calls that can be integrated within
                        // the reference block of statements, therefore they are added to it unchanged
                        currentBlockStmt.addStatement(statement);
                    }
                } else if (statement.isForEachStmt()) {
                    // Parse the for each statement and add its body to the current block
                    Statement forEachStmtBody = statement.asForEachStmt().getBody();
                    // Clone the for each statement and set the body to an empty block
                    ForEachStmt forEachStmtClone = statement.asForEachStmt().clone();
                    BlockStmt forEachCloneBody = new BlockStmt();
                    forEachStmtClone.setBody(forEachCloneBody);
                    // Add the cloned for each statement to the current block
                    currentBlockStmt.addStatement(forEachStmtClone);
                    // Parse the statements of the for each body and add them to the cloned for each statement
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(forEachStmtBody)),
                            // The reference block is now the body of the cloned foreach
                            forEachCloneBody,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isForStmt()) {
                    // Parse the for statement and split the test case into multiple test cases
                    Statement forStmtBody = statement.asForStmt().getBody();
                    // Clone the for statement and set the body to an empty block
                    ForStmt forStmtClone = statement.asForStmt().clone();
                    BlockStmt forStmtCloneBody = new BlockStmt();
                    forStmtClone.setBody(forStmtCloneBody);
                    // Add the cloned for statement to the current block
                    currentBlockStmt.addStatement(forStmtClone);
                    // Parse the statements of the for current body and add them to the cloned `for` statement
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(forStmtBody)),
                            // The reference block is now the body of the cloned `for`
                            forStmtCloneBody,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isIfStmt()) {
                    IfStmt ifStmt = statement.asIfStmt();
                    List<Pair<Optional<Expression>, Statement>> ifStmts = new ArrayList<>();
                    ifStmts.add(new Pair<>(Optional.of(ifStmt.getCondition()), ifStmt.getThenStmt()));
                    // Check if the `if` statement is single or part of a `if-then-else` block
                    if (ifStmt.getElseStmt().isPresent()) {
                        Optional<Statement> elseIfStmt = ifStmt.getElseStmt();
                        // Iterate over the list of if-else and collect their respective conditions and bodies
                        while (elseIfStmt.isPresent()) {
                            if (elseIfStmt.get().isIfStmt()) {
                                // Extract the condition and the body of the `if-else`
                                ifStmts.add(new Pair<>(Optional.of(elseIfStmt.get().asIfStmt().getCondition()), elseIfStmt.get().asIfStmt().getThenStmt()));
                            } else {
                                // Extract the condition and the body of the `else`
                                ifStmts.add(new Pair<>(Optional.empty(), elseIfStmt.get()));
                            }
                            // Get the next `if-else` or `else`, if present
                            elseIfStmt = elseIfStmt.get().isIfStmt() ? elseIfStmt.get().asIfStmt().getElseStmt() : Optional.empty();
                        }
                    }
                    // Define `if` statement clone
                    IfStmt ifStmtClone = null;
                    // Process the collection of `if-then-else` conditions
                    for (Pair<Optional<Expression>, Statement> ifStmtPair : ifStmts) {
                        // Create a new `if-then` block. Copy the condition and create an empty body
                        Optional<Expression> ifCondition = ifStmtPair.getValue0();
                        Statement ifStmtBody = ifStmtPair.getValue1();
                        BlockStmt ifStmtCloneBody = new BlockStmt();
                        if (ifCondition.isPresent()) {
                            if (ifStmtClone == null) {
                                ifStmtClone = new IfStmt(ifCondition.get(), ifStmtCloneBody, null);
                                // Add the cloned if statement to the current block
                                currentBlockStmt.addStatement(ifStmtClone);
                            } else {
                                IfStmt lastIfStmt = traverseIfElseStatement(ifStmtClone);
                                lastIfStmt.setElseStmt(new IfStmt(ifCondition.get(), ifStmtCloneBody, null));
                            }
                        } else {
                            IfStmt lastIfStmt = traverseIfElseStatement(ifStmtClone);
                            lastIfStmt.setElseStmt(ifStmtCloneBody);
                        }
                        // Parse the statements of the if-body and add it to the corresponding block of statements
                        thrownedException = parseNormalizeTestStatementsBlock(
                                testClass,
                                originalTestCase,
                                new ArrayList<>(Arrays.asList(ifStmtBody)),
                                // The reference block is now the body of the cloned `if-then` or `else` statement
                                ifStmtCloneBody,
                                auxiliaryMethods,
                                integratedAuxiliaryMethods,
                                existingVariables,
                                junitVersion
                        );
                        // Update the `if` statement clone
                        ifStmtClone = JavaParserUtils.getLastStatementTypeOccurrence(currentBlockStmt, IfStmt.class)
                                .orElseThrow(() -> new IllegalStateException(
                                        "No statement of type " + IfStmt.class + " found in the block of statements."
                                ))
                                .asIfStmt();
                    }
                } else if (statement.isSynchronizedStmt()) {
                    // Parse the synchronized statement and split the test case into multiple test cases
                    Statement synchronizedStmtBody = statement.asSynchronizedStmt().getBody();
                    // Clone the synchronized statement and set the body to an empty block
                    SynchronizedStmt synchronizedStmtClone = statement.asSynchronizedStmt().clone();
                    BlockStmt synchronizedStmtBodyClone = new BlockStmt();
                    synchronizedStmtClone.setBody(synchronizedStmtBodyClone);
                    // Add the cloned synchronized statement to the current block
                    currentBlockStmt.addStatement(synchronizedStmtClone);
                    // Parse the statements of the synchronized body and split it into multiple test cases
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(synchronizedStmtBody)),
                            synchronizedStmtBodyClone,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isSwitchStmt()) {
                    throw new UnsupportedOperationException("Switch statement not supported yet.");
                } else if (statement.isTryStmt()) {
                    // Convert the try/catch statement into a plain assertion (assertThrows)
                    // Get the try statement
                    TryStmt tryStmt = statement.asTryStmt();
                    // Check the try statement
                    checkTryStmt(originalTestCase.getNameAsString(), tryStmt);
                    // Flatten the try statement into a block statement
                    BlockStmt flatTryStmtBlock = flatTryStmt(junitVersion, tryStmt, existingVariables);
                    // Parse the statements of the try block and split it into multiple test cases
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(flatTryStmtBlock)),
                            currentBlockStmt,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else if (statement.isWhileStmt()) {
                    // Parse the while statement and split the test case into multiple test cases
                    Statement whileStmtBody = statement.asWhileStmt().getBody();
                    // Clone the while statement and set the body to an empty block
                    WhileStmt whileStmtClone = statement.asWhileStmt().clone();
                    BlockStmt whileStmtBodyClone= new BlockStmt();
                    whileStmtClone.setBody(whileStmtBodyClone);
                    // Add the cloned while statement to the current block
                    currentBlockStmt.addStatement(whileStmtClone);
                    // Parse the statements of the while body and split it into multiple test cases
                    thrownedException = parseNormalizeTestStatementsBlock(
                            testClass,
                            originalTestCase,
                            new ArrayList<>(Arrays.asList(whileStmtBody)),
                            whileStmtBodyClone,
                            auxiliaryMethods,
                            integratedAuxiliaryMethods,
                            existingVariables,
                            junitVersion
                    );
                } else {
                    // Add the statement to the current split test case body
                    currentBlockStmt.addStatement(statement);
                    // continue;
                }
            } catch (Exception e) {
                // Log the error occurred while parsing the statements of the test case
                logger.error(e.getMessage());
                thrownedException = true;
                currentBlockStmt.addStatement(statement);
            }
        }
        // Return if an exception occurred while parsing the statements of the test case
        return thrownedException;
    }

    /**
     * Given a BlockStmt representing the body of a method, and the mapping of the parameters to replace in the block of
     * statements, replace the parameters with the actual arguments in the block of statements.
     * @param blockStmt the body of the method to process
     * @param paramMapping the mapping of the parameters to replace in the block of statements
     */
    private static void replaceParams(BlockStmt blockStmt, Map<String, Expression> paramMapping) {
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
     * Given a BlockStmt representing the body of a method, and the mapping of the variables to replace in the block of
     * statements, replace the variables with the renamed names in the block of statements.
     * @param blockStmt the body of the method to process
     * @param varMapping the mapping of the variables to replace in the block of statements
     */
    private static void replaceVariables(BlockStmt blockStmt, Map<String, String> varMapping) {
        // Rename variable declarations in the block of statements
        VariableDeclarationExprVisitor varDeclarationExprCollector = new VariableDeclarationExprVisitor();
        List<VariableDeclarationExpr> varDeclarationExprs = varDeclarationExprCollector.visit(blockStmt);
        varDeclarationExprs.forEach(varDecl -> {
            varDecl.getVariables().forEach(var -> {
                String varName = var.getNameAsString();
                if (varMapping.containsKey(varName)) {
                    var.setName(varMapping.get(varName));
                }
            });
        });
        // Rename variable usages in the block of statements
        NameExprVisitor nameExprCollector = new NameExprVisitor();
        List<NameExpr> nameExprs = nameExprCollector.visit(blockStmt);
        nameExprs.forEach(nameExpr -> {
            String varName = nameExpr.getNameAsString();
            if (varMapping.containsKey(varName)) {
                nameExpr.setName(varMapping.get(varName));
            }
        });
    }

    /**
     * Integrates the statements of a method called within the original test case within the normalized version of
     * the test case. The method invoked within the original test case is defined as the <strong>auxiliary method</strong>.
     * The normalized version of the test case, where the statements of the {@code auxiliaryMethodInvoked} are integrated
     * into its body is referenced by the {@code currentBlockStmt} parameter.
     *
     * @param testClass The test class where the original test case and the auxiliary method are defined.
     * @param currentBlockStmt The statements block of the normalized version of the original test case
     * @param auxiliaryMethodInvoked The method call expression referring to the auxiliary method to integrate within the
     *                               normalized version.
     * @param auxiliaryMethods The collection of auxiliary methods defined within the test class
     * @param existingVariables The collection of global variables already defined within the test class and local variables
     *                          already defined within the normalized version of the original test case
     * @return The method declaration of the auxiliary method to integrate within the normalized test case
     */
    private static Optional<MethodDeclaration> integrateAuxiliaryMethodIntoBlockStmt(TypeDeclaration testClass, BlockStmt currentBlockStmt, MethodCallExpr auxiliaryMethodInvoked, HashMap<String, MethodDeclaration> auxiliaryMethods, HashMap<String,VariableDeclarator> existingVariables) {
        // Get the signature of the target auxiliary method
        String methodSignature = "";
        try {
            Optional<Node> resolvedMethod = auxiliaryMethodInvoked.resolve().toAst();
            if (resolvedMethod.isPresent()) {
                methodSignature = ((MethodDeclaration) resolvedMethod.get()).getSignature().asString();
            }
        } catch (UnsolvedSymbolException e) {
            CallableDeclaration callableDeclaration = JavaParserUtils.searchCandidateCallableDeclaration(testClass, auxiliaryMethodInvoked, CallableExprType.METHOD);
            if (callableDeclaration != null) {
                methodSignature = callableDeclaration.getSignature().asString();
            }
        }
        // Check if the signature of the auxiliary method matches one in the dictionary
        if (auxiliaryMethods.containsKey(methodSignature)) {
            // Get the method declaration of the auxiliary method
            MethodDeclaration auxiliaryMethodDeclaration = auxiliaryMethods.get(methodSignature);
            // Check that the method does not define generics
            if (auxiliaryMethodDeclaration.getTypeParameters().size() > 0) {
                logger.error("Auxiliary method with type parameters is not supported.");
                return Optional.empty();
            }
            // Get the body of the auxiliary method
            BlockStmt auxBody = auxiliaryMethodDeclaration.getBody().get();
            // Check the presence and the position of the auxiliary method to verify if the body
            // can be added to the main block statement
            ReturnStmtVisitor returnStmtCollector = new ReturnStmtVisitor();
            List<ReturnStmt> returnStmts = returnStmtCollector.visit(auxBody);
            // In the current implementation, auxiliary methods with multiple return statements
            // cannot be integrated to the block statement
            if (returnStmts.size() > 1) {
                logger.error("Multiple return statements in the auxiliary method.");
                return Optional.empty();
            }
            if (returnStmts.size() == 1) {
                ReturnStmt returnStmt = returnStmts.get(0);
                Optional<Expression> returnExpr = returnStmt.getExpression();
                if (returnExpr.isEmpty()) {
                    logger.error("Empty return statement in the auxiliary method. Removed.");
                    auxBody.remove(returnStmt);
                    //return Optional.empty();
                }
                if (!(auxBody.getStatements().getLast().get() == returnStmt)) {
                    logger.error("Return statement not at the end of the auxiliary method.");
                    return Optional.empty();
                }
                auxBody.getStatements().remove(returnStmt);
                ExpressionStmt returnExprStmt = new ExpressionStmt(returnExpr.get());
                auxBody.addStatement(returnExprStmt);
            }
            // Clone and modify the auxiliary method body to replace variables
            BlockStmt clonedAuxBody = auxBody.clone();
            // Map parameters from the method call to actual arguments
            Map<String, Expression> paramMapping = new HashMap<>();
            // Map variables defined both in the auxiliary method and in the main method
            Map<String, String> varMapping = new HashMap<>();
            // Get the list of concrete arguments passed to the auxiliary method from the main method
            List<Expression> arguments = auxiliaryMethodInvoked.getArguments();
            // Get the list of parameters defined in the signature of the auxiliary method, that receives the arguments
            // from the main method
            List<Parameter> parameters = auxiliaryMethodDeclaration.getParameters();
            // Check that the number of arguments passed from the main method and the parameters
            // declared in the signature of the auxiliary method coincide. The analysis also checks
            // if the last parameter is a varargs (...), allowing multiple arguments to be grouped
            // into a single array, as last parameter of the function. In this case the number of
            // arguments and parameters can be different.
            boolean lastParamVarArgs = parameters.get(parameters.size() - 1).isVarArgs();
            // Check if the last parameter of the auxiliary method is a varargs
            if (lastParamVarArgs) {
                // Create and initialize a varargs expression
                ArrayCreationExpr varArgsArray = new ArrayCreationExpr();
                varArgsArray.setElementType(((Parameter) ((NodeList) parameters).getLast().get()).getType());
                ArrayInitializerExpr varArgsInititalizer = new ArrayInitializerExpr();
                NodeList<Expression> varArgs = new NodeList<>();
                // Create a custom varargs
                Optional<VariableDeclarator> varArgsDeclarator = Optional.empty();
                String varArgsName = parameters.get(parameters.size() - 1).getNameAsString() + "STAR";
                if (existingVariables.containsKey(varArgsName)) {
                    int i = 0;
                    while (existingVariables.containsKey(varArgsName + "_" + i)) {
                        i++;
                    }
                    varArgsName = varArgsName + "_" + i;
                }
                // Find the mapping between the varargs and single argument (array) or the set of values (passed to the
                // auxiliary method and grouped into the last varargs parameter as an array
                boolean found = false;
                if (arguments.size() == parameters.size()) {
                    // If the number of arguments is equal to the number of parameters, the last argument must be an array
                    // or a single value that would be passed and inserted in the argvars as a list with a single value
                    // Within this block, only the first case is managed. The second case is managed in the following for
                    // loop (within the `if (!found)` statement)
                    Expression lastArgument = arguments.get(arguments.size() - 1);
                    // TODO: Check all the possible types of the last argument (for example methodCallExpr)
                    if (lastArgument.isNameExpr() && existingVariables.containsKey(lastArgument.toString())) {
                        found = true;
                        // Initialize the custom varargs accordingly
                        varArgsDeclarator = Optional.of(new VariableDeclarator(existingVariables.get(lastArgument.asNameExpr().getNameAsString()).getType(), varArgsName, lastArgument.asNameExpr()));
                    }
                }
                // If the last argument passed to the auxiliary function is not a variable of type array previously defined
                // within the main method, the arguments passed to the auxiliary methods must be a set of primitive values or
                // object instantiated within the auxiliary method call
                if (!found) {
                    // Iterate over the arguments to collect the last ones collected by the varargs
                    for (int i = 0; i < arguments.size(); i++) {
                        // The argument corresponding to the last parameter idx and any additional argument
                        // passed to the method call is considered an element collected by the param varargs
                        if (i >= (parameters.size() - 1)) {
                            varArgs.add(arguments.get(i));
                        }
                    }
                    // Initialize the custom varargs accordingly
                    varArgsInititalizer.setValues(varArgs);
                    varArgsArray.setInitializer(varArgsInititalizer);
                    varArgsDeclarator = Optional.of(new VariableDeclarator(new ArrayType(varArgsArray.getElementType()), varArgsName, varArgsArray));
                }
                // Create an assign expression to define a variable that store the varargs collection, and add it as a
                // statement to the normalized test case where the auxiliary method is integrated
                AssignExpr assignExpr = new AssignExpr();
                assignExpr.setTarget(new VariableDeclarationExpr(varArgsDeclarator.get().getType(),varArgsDeclarator.get().getNameAsString()));
                assignExpr.setValue(varArgsDeclarator.get().getInitializer().get());
                currentBlockStmt.addStatement(assignExpr);
                // Create a reference between the original parameter name representing the varargs in the auxiliary method
                // and the new variable name defined within the normalized version of the original test case
                paramMapping.put(parameters.get(parameters.size() - 1).getNameAsString(), new NameExpr(varArgsName));
            } else {
                if (arguments.size() != parameters.size() && !lastParamVarArgs) {
                    logger.error("Parameter mismatch between method call and auxiliary method.");
                    return Optional.empty();
                }
            }
            // Map all the parameter names defined in the signature of the auxiliary method with the corresponding variable
            // names of the arguments passed to the auxiliary method
            // For example:
            //      method call --> auxiliaryMethodName(arg1Name, arg2Name)
            //      signature   --> public void auxiliaryMethodName(Type1 param1, Type2 param2);
            //
            // Mapping:
            //      {
            //          param1 --> arg1
            //          param2 --> arg2
            //      }
            //
            for (int i = 0; i < parameters.size(); i++) {
                // Check if the argument passed to a function is an executable, like a lambda function

                if (parameters.get(i).getType().toString().equals("Executable")) {
                    String executableVarName = parameters.get(i).getNameAsString() + "_executable_STAR";
                    VariableDeclarationExpr executableVarDeclExpr = new VariableDeclarationExpr(parameters.get(i).getType(), executableVarName);
                    AssignExpr executableVarDeclExprAssign = new AssignExpr();
                    executableVarDeclExprAssign.setTarget(executableVarDeclExpr);
                    executableVarDeclExprAssign.setValue(arguments.get(i));
                    clonedAuxBody.addStatement(0, executableVarDeclExprAssign);
                    paramMapping.put(parameters.get(i).getNameAsString(), new NameExpr(executableVarName));
                } else {
                    if (!parameters.get(i).isVarArgs()) {
                        paramMapping.put(parameters.get(i).getNameAsString(), arguments.get(i));
                    }
                }
            }
            // Collect the local variable names defined wihtin the auxiliary method that must be renamed whe integrated
            // within the test case
            varMapping = collectVariableMapping(auxBody, existingVariables);
            // Replace any occurrence of the old names with the new ones within the auxiliary block of statements
            replaceParams(clonedAuxBody, paramMapping);
            replaceVariables(clonedAuxBody, varMapping);
            // Add the cloned auxiliary method body to the main method body
            currentBlockStmt.getStatements().addAll(clonedAuxBody.getStatements());
            return Optional.of(auxiliaryMethodDeclaration);
        }
        return Optional.empty();
    }

    /**
     * Collects all the local variable definitions within a pending block of statements and checks if exist variables
     * with the same name in the collection of already defined variables. If so, define a new name for the local variable
     * and create a map between the old name and the new one. This method is useful when integrating a block of statements,
     * like a method body, within another main block of statements (for example the block from where the body of the method
     * to integrate is called) or in case of flat operations, like when try/catch/finally blocks are substituted with an
     * {@code assertThrows} and flattened to be part of the main block.
     *
     * @param integratingBody the block of statements containing the definitions of local variables to check
     * @param existingVariables the collection of variables already defined in the reference block
     * @return a {@link HashMap} that maps the list of local variable to be redefined when integrated within the main
     * corpus of statements. The key represents the original name of the local variable. The value represents the updated
     * name of the local variable
     */
    private static Map<String, String> collectVariableMapping(BlockStmt integratingBody, HashMap<String, VariableDeclarator> existingVariables) {
        HashMap<String, String> varMapping = new HashMap<>();
        // Iterate over the list of variable defined and used within the auxiliary method to check if the integration
        // within the normalized test case could create conflicts with variable with the same name already defined
        // in the normalized statements block
        VariableDeclarationExprVisitor varDeclarationExprCollector = new VariableDeclarationExprVisitor();
        List<VariableDeclarationExpr> localVarDeclarationExprs = varDeclarationExprCollector.visit(integratingBody);
        localVarDeclarationExprs.forEach(localVarDecl -> {
            localVarDecl.getVariables().forEach(localVar -> {
                // If the name of a variable defined within an auxiliary method is also defined within the
                // normalized version, rename it
                if (existingVariables.containsKey(localVar.getNameAsString())) {
                    int i = 0;
                    while (existingVariables.containsKey(localVar.getNameAsString() + "_" + i)) {
                        i++;
                    }
                    varMapping.put(localVar.getNameAsString(), localVar.getNameAsString() + "_" + i);
                    existingVariables.put(localVar.getNameAsString() + "_" + i, localVar);
                } else {
                    existingVariables.put(localVar.getNameAsString(), localVar);
                }
            });
        });
        return varMapping;
    }

    /**
     * Collects the local variable definition within a single expression (if present) and checks if exist variables with
     * the same name in the collection of already defined variables. If so, define a new name for the local variable
     * and create a map between the old name and the new one. This method is useful when integrating an expression statement
     * that defines a variable, within another main block of statements.
     *
     * @param expr the expression statement containing the definition of local variable, if any
     * @param existingVariables the collection of variables already defined in the reference block
     * @return a {@link HashMap} that maps the list of local variables to be redefined. The key represents the original name
     * of the local variable. The value represents the updated name of the local variable
     */
    private static Map<String,String> collectVariableMapping(Expression expr, HashMap<String, VariableDeclarator> existingVariables) {
        Map<String,String> varMapping = new HashMap<>();
        if (expr.isVariableDeclarationExpr()) {
            VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
            for (VariableDeclarator var : varDecl.getVariables()) {
                String varName = var.getNameAsString();
                if (!existingVariables.containsKey(varName)) {
                    existingVariables.put(varName, var);
                } else {
                    int i = 0;
                    while (existingVariables.containsKey(varName + "_" + i)) {
                        i++;
                    }
                    var.setName(varName + "_" + i);
                    existingVariables.put(varName + "_" + i, var);
                    varMapping.put(varName, varName + "_" + i);
                }
            }
        }
        return varMapping;
    }

    /**
     * Traverse the if-else chain of an if-statement from the root up to the last if-else condition
     *
     * @param rootIfStmt the root if-statement
     * @return the last if-statement in the if-else chain
     */
    private static IfStmt traverseIfElseStatement(IfStmt rootIfStmt) {
        IfStmt lastIfStmt = rootIfStmt;
        while (lastIfStmt.getElseStmt().isPresent()) {
            lastIfStmt = lastIfStmt.getElseStmt().get().asIfStmt();
        }
        return lastIfStmt;
    }

    /**
     * Add the catch clause to the try statement in the given splitTestCaseBody.
     *
     * @param catchClause the catch clause to add
     * @param splitTestCaseBody the split test case body where is present the try statement
     */
    private static void addCatchClause(
            CatchClause catchClause,
            BlockStmt splitTestCaseBody
    ) {
        Class blockStmtClass = BlockStatementsType.TRY.getJavaParserStmtClass();
        Statement lastStatement = JavaParserUtils.getLastStatementTypeOccurrence(splitTestCaseBody, blockStmtClass)
                .orElseThrow(() -> new IllegalStateException(
                        "No statement of type " + blockStmtClass + " found in the block of statements."
                ));
        lastStatement.asTryStmt().getCatchClauses().add(catchClause);
    }

    /**
     * Add the statement to the split test case body, according to the type of block statement under analysis.
     * If the block statement is a method body, it refers to the main block of code and the statement is added
     * to the split test case body. Otherwise, the statement is added to the last block statement of the split
     * test case body (the one under analysis).
     *
     * @param statement
     * @param splitTestCaseBody
     * @param blockStatementsType
     */
    private static void addStatement(
            Statement statement,
            BlockStmt splitTestCaseBody,
            BlockStatementsType blockStatementsType
    ) {
        // statement.addOrphanComment(TestUtils.MASK_COMMENT);
        if (blockStatementsType == BlockStatementsType.METHOD_BODY) {
            splitTestCaseBody.addStatement(statement);
        } else {
            Class blockStmtClass = blockStatementsType.getJavaParserStmtClass();
            Statement lastStatement = JavaParserUtils.getLastStatementTypeOccurrence(splitTestCaseBody, blockStmtClass)
                    .orElseThrow(() -> new IllegalStateException(
                            "No statement of type " + blockStmtClass + " found in the block of statements."
                    ));
            BlockStmt body;
            switch (blockStatementsType) {
                case DO:
                    body = (BlockStmt) lastStatement.asDoStmt().getBody();
                    body.addStatement(statement);
                    return;
                case FOR_EACH:
                    body = (BlockStmt) lastStatement.asForEachStmt().getBody();
                    body.addStatement(statement);
                    return;
                case FOR:
                    body = (BlockStmt) lastStatement.asForStmt().getBody();
                    body.addStatement(statement);
                    return;
                case LAMBDA:
                    List<LambdaExpr> lambdaExprs = lastStatement.findAll(LambdaExpr.class);
                    if (lambdaExprs.size() == 0 ) {
                        Optional<Node> currentNode = Optional.of(lastStatement);
                        while(currentNode.isPresent()) {
                            currentNode = currentNode.get().getParentNode();
                            if (currentNode.isPresent() && currentNode.get() instanceof LambdaExpr) {
                                ((LambdaExpr) currentNode.get()).asLambdaExpr().getBody().asBlockStmt().addStatement(statement);
                                break;
                            }
                        }
                        if (currentNode.isEmpty()) {
                            throw new IllegalStateException("No lambda expression found in the block of statements.");
                        }
                    } else {
                        lastStatement.findAll(LambdaExpr.class).get(0).asLambdaExpr().getBody().asBlockStmt().addStatement(statement);
                    }
                    return;
                case IF:
                    body = lastStatement.asIfStmt().getThenStmt().asBlockStmt();
                    body.addStatement(statement);
                    return;
                case ELSE:
                    IfStmt ifStmt = lastStatement.asIfStmt();
                    while (ifStmt.getElseStmt().isPresent()) {
                        Statement stmt = ifStmt.getElseStmt().get();
                        if (stmt.isIfStmt()) {
                            ifStmt = stmt.asIfStmt();
                        } else {
                            body = stmt.asBlockStmt();
                            body.addStatement(statement);
                            return;
                        }
                    }
                    body = ifStmt.getThenStmt().asBlockStmt();
                    body.addStatement(statement);
                    return;
                case TRY:
                    body = lastStatement.asTryStmt().getTryBlock();
                    body.addStatement(statement);
                    return;
                case CATCH:
                    body = lastStatement.asTryStmt().getCatchClauses().getLast().get().getBody();
                    body.addStatement(statement);
                    return;
                case FINALLY:
                    if (!lastStatement.asTryStmt().getFinallyBlock().isPresent()) {
                        if (statement.isBlockStmt()) {
                            lastStatement.asTryStmt().setFinallyBlock(statement.asBlockStmt());
                            return;
                        }
                        throw new IllegalStateException("Unexpected finally block statement");
                    }
                    body = lastStatement.asTryStmt().getFinallyBlock().get();
                    body.addStatement(statement);
                    return;
                case SWITCH:
                    SwitchStmt switchStmt = lastStatement.asSwitchStmt();
                    SwitchEntry lastSwitchEntry = switchStmt.getEntry(switchStmt.getEntries().size() - 1);
                    lastSwitchEntry.getStatements().add(statement);
                    return;
                case WHILE:
                    body = (BlockStmt) lastStatement.asWhileStmt().getBody();
                    body.addStatement(statement);
                    return;
                default:
                    throw new IllegalStateException("Unexpected block statement type");
            }
        }
    }

    /**
     * Get the number of assertions in the list of statements.
     *
     * @param statements the list of statements
     * @return the number of assertions
     */
    private static int getNumberOfAssertions(List<Statement> statements) {
        int assertionCounter = 0;
        for (Statement statement : statements) {
            MethodCallExprVisitor methodCallExprCollector = new MethodCallExprVisitor();
            List<MethodCallExpr> methodCallExprs = methodCallExprCollector.visit(statement);
            for(MethodCallExpr methodCallExpr : methodCallExprs) {
                if (JUnitAssertionType.isJUnitAssertion(methodCallExpr.getNameAsString())) {
                    assertionCounter++;
                } else {
                    // TODO: Check if the statement is a method call expression that refers to an auxiliary method
                    //       (defined or inherited) with assertions
                }
            }
            AssertStmtVisitor assertStmtCollector = new AssertStmtVisitor();
            List<AssertStmt> assertStmts = assertStmtCollector.visit(statement);
            assertionCounter += assertStmts.size();
        }
        return assertionCounter;
    }

    private static HashMap<String,Integer> getAssertionsDistribution(List<Statement> statements) {
        HashMap<String, Integer> assertionDistribution = new HashMap<>();
        for (JUnitAssertionType assertionType : JUnitAssertionType.values()) {
            assertionDistribution.put(assertionType.getAssertionMethodName(), 0);
        }

        for (Statement statement : statements) {
            MethodCallExprVisitor methodCallExprCollector = new MethodCallExprVisitor();
            List<MethodCallExpr> methodCallExprs = methodCallExprCollector.visit(statement);
            for(MethodCallExpr methodCallExpr : methodCallExprs) {
                if (JUnitAssertionType.isJUnitAssertion(methodCallExpr.getNameAsString())) {
                    assertionDistribution.put(methodCallExpr.getNameAsString(), assertionDistribution.get(methodCallExpr.getNameAsString()) + 1);
                } else {
                    // TODO: Check if the statement is a method call expression that refers to an auxiliary method
                    //       (defined or inherited) with assertions
                }
            }
            AssertStmtVisitor assertStmtCollector = new AssertStmtVisitor();
            List<AssertStmt> assertStmts = assertStmtCollector.visit(statement);
            assertionDistribution.put("assert", assertStmts.size());
        }
        return assertionDistribution;
    }

    /**
     * Check that the try statement respect the rules for the split test cases.
     * The rules are:
     * <ul>
     *     <li>Only one catch clause is allowed, if the fail() assertion is in the try statement (if you test a method,
     *     you expect only a specific exception to be thrown under a particular condition)</li>
     *     <li>If the {@code TryStmt} contains a catch clause, the exception captured by the catch clause must be unique
     *     (no union type are admitted)</li>
     *     <li>If the {@code TryStmt} contains a catch clause, the last statement in the try block must be a method call
     *     or an object creation invocation</li>
     * </ul>
     *
     *
     * @param testCaseName the name of the test case
     * @param tryStmt the try statement to check
     * @return the list of split test cases
     */
    private static void checkTryStmt(String testCaseName, TryStmt tryStmt) {
        // Get the list of catch clauses from the try block
        NodeList<CatchClause> catchesClauses = tryStmt.getCatchClauses();
        // Get the list of statements from the try block
        List<Statement> tryStmtStatements = tryStmt.getTryBlock().getStatements();
        Optional<BlockStatementsType> failPositionAssertion = findFailMethodCall(tryStmt);
        // Check the number of catch clauses (only one is allowed if the fail assertion is present in the try block)
        if (failPositionAssertion.isPresent()) {
            if (catchesClauses.size() > 1 && failPositionAssertion.get() == BlockStatementsType.TRY) {
                throw new IllegalStateException("Unexpected number of catch clauses in try statement within test case " + testCaseName);
            }
        }
        // Check the last statement in the try block (must be a method call, an object creation invocation or a fail
        // assertion)
        if (catchesClauses.size() == 1) {
            if (catchesClauses.get(0).getParameter().getType().getClass().equals(UnionType.class)) {
                throw new IllegalStateException("Union type in catch clause in try statement within test case " + testCaseName);
            }
            if (tryStmtStatements.size() == 0) {
                throw new IllegalStateException("No statements found in try block of try statement within test case " + testCaseName);
            }
            Statement lastTryStmtStatement = tryStmtStatements.get(tryStmtStatements.size() - 1);
            if (!lastTryStmtStatement.isExpressionStmt()) {
                throw new IllegalStateException("Last statement in try block of try statement within test case " + testCaseName + " is not an expression statement");
            }
            if (!StmtVisitorHelper.getLastExprInStmt(lastTryStmtStatement, MethodCallExpr.class).isPresent() && !StmtVisitorHelper.getLastExprInStmt(lastTryStmtStatement, ObjectCreationExpr.class).isPresent()) {
                throw new IllegalStateException("Last statement in try block of try statement within test case " + testCaseName + " is not a method call or object creation expression");
            }
        }
        // All checks passed
    }

    /**
     * Flatten a try statement into a block statement. If a catch clause is present, it is converted into an assertThrows
     * statement. The statements within the catch block are added to the flat try block. If a finally block is present,
     * the statements within it are added to the flat try block.
     *
     * Preconditions:
     * <ul>
     *     <li>Only one catch clause is allowed</li>
     *     <li>If the {@code TryStmt} contains a catch clause, the last statement in the try block must be a fail() method call</li>
     * </ul>
     *
     * @param junitVersion the version of JUnit used in the test class
     * @param tryStmt the try statement to flatten
     * @param existingVariables the collection of variables already defined within the statements block that represent the
     *                          corpus of the normalized test case where the flat try block will be integrated
     * @return try/catch/finally blocks as a flat block of statements
     */
    private static BlockStmt flatTryStmt(JUnitVersion junitVersion, TryStmt tryStmt, HashMap<String, VariableDeclarator> existingVariables) throws IllegalStateException {
        // Define the flat block of statements (initially empty)
        BlockStmt flatTryStmtBlock = new BlockStmt();
        // Collect all the statements to insert within the final flat block
        List<Statement> statements = new ArrayList<>();
        // Check if the try statement contains a catch clause, in which case collect the statements within it
        if (tryStmt.getCatchClauses().size() == 1) {
            // Convert catch clause into an assertThrows statement
            Optional<MethodCallExpr> assertThrowsCall = generateAssertThrowsFromTryStmt(junitVersion, tryStmt);
            if (assertThrowsCall.isPresent()) {
                // Check if the try block contains resources
                if (!tryStmt.getResources().isEmpty()) {
                    Map<String,String> resourcesVarMapping = new HashMap<>();
                    // Check the variable names of the resources and change their names if already defined within the
                    // main block
                    for (Expression expr: tryStmt.getResources()) {
                        if (expr.isVariableDeclarationExpr()) {
                            VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
                            for (VariableDeclarator var : varDecl.getVariables()) {
                                String varName = var.getNameAsString();
                                if (!existingVariables.containsKey(varName)) {
                                    existingVariables.put(varName, var);
                                } else {
                                    int i = 0;
                                    while (existingVariables.containsKey(varName + "_" + i)) {
                                        i++;
                                    }
                                    var.setName(varName + "_" + i);
                                    existingVariables.put(varName + "_" + i, var);
                                    resourcesVarMapping.put(varName, varName + "_" + i);
                                }
                            }
                            // Replace any occurrence of the old name with the new one within the old try block
                            replaceVariables(tryStmt.getTryBlock(), resourcesVarMapping);
                        }
                        // Add the resource to the flat version of the block
                        statements.add(new ExpressionStmt(expr));
                    }
                }
                // Replace local variables in the catch block, if already defined within the main block
                Map<String,String> tryVarMapping = collectVariableMapping(tryStmt.getTryBlock(), existingVariables);
                replaceVariables(tryStmt.getTryBlock(), tryVarMapping);
                // Add the list of statements from the try block
                statements.addAll(tryStmt.getTryBlock().getStatements());
                // Remove the method now called in the assertThrows assertion
                statements.remove(statements.size() - 2);
                // Remove the fail() method call at the end of the try block
                statements.remove(statements.size() - 1);
                // Get the exception class expression from the assertThrows
                ClassExpr exceptionClassExpr;
                if (junitVersion == JUnitVersion.JUNIT4) {
                    // In JUnit 4 the exception class is passed as second argument
                    exceptionClassExpr = assertThrowsCall.get().getArguments().get(1).asClassExpr();
                } else {
                    // In JUnit 5 the exception class is passed as first argument
                    exceptionClassExpr = assertThrowsCall.get().getArguments().get(0).asClassExpr();
                }
                // Create a variable to save the exception thrown by the assertThrows (the catch statement
                // is always defined with a variable containing the exception, that can be used within the
                // catch block - for example to check the message of the error).
                Parameter catchExceptionParam = tryStmt.getCatchClauses().get(0).getParameter();
                // Check if the original name of the catch parameter is already defined within the main block
                Optional<Pair<String,String>> catchExceptionParamRenaming = renameLocalParam(catchExceptionParam, existingVariables.keySet());
                BlockStmt catchBlockStmt = tryStmt.getCatchClauses().get(0).getBody();
                String newExceptionParamName = catchExceptionParam.getNameAsString();
                // If the original name of the exception defined within the catch clause is already defined within the
                // main block, redefine it
                if (catchExceptionParamRenaming.isPresent()) {
                    Map<String,String>  variableMapping = new HashMap<>();
                    String oldExceptionParamName = catchExceptionParamRenaming.get().getValue0();
                    newExceptionParamName = catchExceptionParamRenaming.get().getValue1();
                    variableMapping.put(oldExceptionParamName, newExceptionParamName);
                    replaceVariables(catchBlockStmt, variableMapping);
                }
                // Define a variable declarator to store the exception returned by the assertThrow
                // In the original catch clause, it could use it for example to check the correctness of the
                // message associated to the thrown exception
                VariableDeclarator exceptionVarDecl = new VariableDeclarator(
                        new ClassOrInterfaceType(null, exceptionClassExpr.getTypeAsString()),
                        newExceptionParamName,
                        assertThrowsCall.get()
                );
                VariableDeclarationExpr exceptionVarDeclExpr = new VariableDeclarationExpr(exceptionVarDecl);
                // Create an expression statement for the assertThrows call
                Statement assertStmt = new ExpressionStmt(exceptionVarDeclExpr);
                statements.add(assertStmt);
                // Add the generated variable to the collection of existing variables
                existingVariables.put(newExceptionParamName, exceptionVarDecl);
                // Replace local variables in the catch block, if already defined within the main block
                Map<String,String> catchVarMapping = collectVariableMapping(catchBlockStmt, existingVariables);
                replaceVariables(catchBlockStmt, catchVarMapping);
                // Add the statements from the catch block to the flat main block
                statements.addAll(catchBlockStmt.getStatements());
                // Get the finally block, if present
                Optional<BlockStmt> finallyBlockStmt = tryStmt.getFinallyBlock();
                if (finallyBlockStmt.isPresent()) {
                    // Replace local variables in the catch block, if already defined within the main block
                    Map<String,String> finallyVarMapping = collectVariableMapping(finallyBlockStmt.get(), existingVariables);
                    replaceVariables(finallyBlockStmt.get(), finallyVarMapping);
                    // Add the statements from the catch block to the main block
                    statements.addAll(finallyBlockStmt.get().getStatements());
                }
            } else {
                throw new IllegalStateException("The test case does not respect the requirements to flat the try/catch/finally blocks: the last method call within the try block must be a `fail` method.");
            }
        }
        // Check if the try statement contains a finally block, in which case collect the statements within it
        if (tryStmt.getFinallyBlock().isPresent()) {
            statements.addAll(tryStmt.getFinallyBlock().get().getStatements());
        }

        // Iterate over the statements in the try block
        for (Statement statement : statements) {
            // Add the statement to the flat try block
            flatTryStmtBlock.addStatement(statement);
        }
        // Return the flat try block
        return flatTryStmtBlock;
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
    private static Optional<Pair<String,String>> renameLocalVar(VariableDeclarator var, HashMap<String, VariableDeclarator> existingVariables) {
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

    /**
     * Checks if the parameter name of the parameter {@link Parameter} passed to the function is already defined
     * in the collection of the existing variable names. In this case the method renames the variable using a simple incremental
     * logic (adding an index at the end of the variable)
     *
     * @param param the parameter to check
     * @param existingVariableNames the collection of existing variable names
     * @return If the parameter name already exists in the collection, an optional {@link Optional} containing a pair of
     * elements where the first element is the old parameter name and the second element is the new parameter name. Otherwise,
     * an empty optional {@link Optional}
     */
    private static Optional<Pair<String,String>> renameLocalParam(Parameter param, Set<String> existingVariableNames) {
        String paramName = param.getNameAsString();
        if (!existingVariableNames.contains(paramName)) {
            return Optional.empty();
        }
        int i = 0;
        while (existingVariableNames.contains(paramName + "_" + i)) {
            i++;
        }
        String newParamName = paramName + "_" + i;
        return Optional.of(new Pair<>(paramName, newParamName));
    }

    /**
     * Generate an assertThrows method call expression from a try statement. The assertThrows is created using the exception
     * class from the catch clause and the last method call in the try block preceding the fail() method. If the fail() method
     * contains a message, it is set as the message of the assertThrows statement.
     *
     * @param junitVersion the version of JUnit used in the test class
     * @param tryStmt the try statement to generate the assertThrows method call expression from
     * @return the assertThrows method call expression, if the fail() method call is found in the try block. An empty optional otherwise.
     */
    private static Optional<MethodCallExpr> generateAssertThrowsFromTryStmt(JUnitVersion junitVersion, TryStmt tryStmt) {
        // Get catch clause
        CatchClause catchClause = tryStmt.getCatchClauses().get(0);
        // Get the exception class from the catch clause
        com.github.javaparser.ast.type.Type exceptionClass = catchClause.getParameter().getType();
        // Find the position of the `fail()` method call in the try-catch block
        Optional<BlockStatementsType> failAssertionPosition = findFailMethodCall(tryStmt);
        // Proceed only if the fail method is present within the try block, as the last statement of the block
        if (failAssertionPosition.isPresent() && failAssertionPosition.get() == BlockStatementsType.TRY) {
            if (tryStmt.getTryBlock().getStatements().size() < 2) {
                throw new IllegalStateException("Unexpected fail method call as unique statement in try block");
            }
            // Get the penultimate method call in the try block (the last before the `fail()`: it is considered the method
            // that must throw the exception. The last call is usually the fail() method (if the expected exception is not
            // thrown by the focal method it makes the test fail)
            int tryStmtSize = tryStmt.getTryBlock().getStatements().size();
            List<Statement> statements = tryStmt.getTryBlock().getStatements();
            // The last call before the `fail()` that must throw the exception
            Statement lastMethodCallStatement = statements.get(tryStmtSize - 2);
            Expression lastMethodCall = lastMethodCallStatement.asExpressionStmt().getExpression();
            // The `fail()` method call
            Statement failMethodCallStatement = statements.get(tryStmtSize - 1);
            MethodCallExpr failMethodCall = failMethodCallStatement.asExpressionStmt().getExpression().asMethodCallExpr();
            String failMessage = "";
            // Check if the fail method has a comment. If so, use it as the fail message in the new `assertThrow` statement
            if (failMethodCall.getArguments().size() > 0) {
                if (failMethodCall.getArguments().get(0).isStringLiteralExpr()) {
                    // The replaceAll method is used to escape double quotes in the fail message (not escaped by the asString() method)
                    failMessage = failMethodCall.getArguments().get(0).asStringLiteralExpr().asString().replaceAll("\"", "\\\\\"");
                }
            }
            // Check if the last method call is an assignment or a variable declaration
            if (lastMethodCall.isAssignExpr()) {
                lastMethodCall = lastMethodCall.asAssignExpr().getValue();
            } else if (lastMethodCall.isVariableDeclarationExpr()) {
                lastMethodCall = lastMethodCall.asVariableDeclarationExpr().getVariables().get(0).getInitializer().get();
            }
            LambdaExpr lambdaExpr = new LambdaExpr(new NodeList<>(), new ExpressionStmt(lastMethodCall), true);
            // Create the assertThrows statement
            MethodCallExpr assertThrowsCall = new MethodCallExpr(JUnitAssertionType.ASSERT_THROWS.getAssertionMethodName());
            // Add the arguments to the assertThrows statement (exception class and last method call)
            NodeList<Expression> arguments = new NodeList<>();
            arguments.add(new ClassExpr(exceptionClass));
            arguments.add(lambdaExpr);
            if (!failMessage.isEmpty()) {
                if (junitVersion == JUnitVersion.JUNIT4) {
                    arguments.addFirst(new StringLiteralExpr(failMessage));
                } else if (junitVersion == JUnitVersion.JUNIT5) {
                    arguments.add(new StringLiteralExpr(failMessage));
                }
            }
            assertThrowsCall.setArguments(arguments);
            return Optional.of(assertThrowsCall);
        }
        return Optional.empty();
    }

    /**
     * Check if the statement is a fail method call.
     *
     * @param statement the statement to check
     * @return {@code true} if the statement is a fail method call, {@code false} otherwise
     */
    private static boolean isFailMethodCall(Statement statement) {
        if (statement.isExpressionStmt()) {
            Expression expr = statement.asExpressionStmt().getExpression();
            if (expr.isMethodCallExpr()) {
                MethodCallExpr methodCallExpr = expr.asMethodCallExpr();
                if (methodCallExpr.getNameAsString().equals("fail")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Find the fail method call in the try statement. The method returns {@code BlockStatementsType.TRY} if the fail
     * method call is found in the try block, {@code BlockStatementsType.CATCH} if the fail method call is found in the
     * catch block. If the fail method is not found an {@code IllegalStateException} is thrown. The method assumes that
     * the try-catching block contains only one catch clause.
     *
     * @param tryStmt the try statement to check
     * @return the block statements type where the fail method call is found, if present. An empty optional otherwise.
     */
    private static Optional<BlockStatementsType> findFailMethodCall(TryStmt tryStmt) {
        List<Statement> tryStatements = tryStmt.getTryBlock().getStatements();
        boolean failFound = tryStatements.stream().anyMatch(s -> isFailMethodCall(s));
        if (failFound) {
            return Optional.of(BlockStatementsType.TRY);
        }
        for (CatchClause catchClause : tryStmt.getCatchClauses()) {
            List<Statement> catchStatements = catchClause.getBody().getStatements();
            failFound = catchStatements.stream().anyMatch(s -> isFailMethodCall(s));
            if (failFound) {
                return Optional.of(BlockStatementsType.CATCH);
            }
        }
        return Optional.empty();
    }

    /**
     * Process the test class of a given repository to generate all the oracles datapoints {@link TestClazzDatapoints}
     * from its test cases.
     *
     * @param config the configuration of the oracles dataset
     * @param testFilePath the path to the test class to process
     * @param sourceFilePath the path to the corresponding source file, if available
     * @param repoRootPath the path to the root of the repository
     * @return the list of oracle datapoints generated from the test cases of the test class and a hashmap containing
     * the logs of the errors encountered when processing each test cases (which invoked methods cannot be processed
     * in each test case of the test class)
     */
    public static Pair<TestClazzDatapoints<OracleDatapoint>, HashMap<String, HashMap<String, List<String>>>> processSplitTestClass(
            OraclesDatasetConfig config,
            Path testFilePath,
            Path testPrefixFilePath,
            Path sourceFilePath,
            Path repoRootPath
    ) {
        // Create a list to store the oracle datapoints
        List<OracleDatapoint> oracleDatapoints = new ArrayList<>();
        // Create a map to store the processing errors of the test cases (which invoked methods cannot be processed in
        // each test case of the test class)
        HashMap<String, HashMap<String, List<String>>> testsProcessingErrors = new HashMap<>();
        // Instantiate a TestClazzDatapoints builder
        TestClazzDatapointsBuilder<OracleDatapoint> testClassOracleDatapointsBuilder = new TestClazzDatapointsBuilder();
        // Process the test class and generate the corresponding oracles datapoints
        try {
            // Parse the test class and focal classes
            CompilationUnit cuTestClass = JavaParserUtils.getCompilationUnit(testFilePath);
            CompilationUnit cuFocalClass = JavaParserUtils.getCompilationUnit(sourceFilePath);
            // Get the primary type of the test and focal classes
            TypeDeclaration testClass = cuTestClass.getPrimaryType().orElseThrow(() -> new IllegalStateException("Primary type not found in test class: " + testFilePath));
            TypeDeclaration focalClass = cuFocalClass.getPrimaryType().orElseThrow(() -> new IllegalStateException("Primary type not found in focal class: " + sourceFilePath));
            // Get junit version used in the test class
            JUnitVersion junitVersion = TestUtils.getJunitVersion(cuTestClass.getImports());
            // Get the path of the source directory of the repository
            Path repoSourcePath = Path.of(sourceFilePath.toString().replace(FilesUtils.getFQNPath((String) focalClass.getFullyQualifiedName().get()).toString(), ""));
            // Distribute the methods of the class according to their meaning (auxiliary, setup & tear down, test cases)
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> distributedMethods = distributeMethods(testClass.getMethods());
            // Set the list of all the test cases defined in the given test class
            List<MethodDeclaration> testCases = distributedMethods.getValue0();
            // Set the list of all the setup & tear down methods defined in the given test class
            HashMap<String, MethodDeclaration> setupTearDownMethods = distributedMethods.getValue1();
            // Set the list of all the auxiliary methods defined in the given test class
            HashMap<String, MethodDeclaration> auxiliaryMethods = distributedMethods.getValue2();
            // Process test class
            TestClazz testClassTracto = processTestClass(testClass, testFilePath, testPrefixFilePath, repoRootPath, distributedMethods);
            // Process focal class
            Clazz focalClazz = processFocalClass(focalClass, sourceFilePath, repoRootPath);
            testClassOracleDatapointsBuilder.setJunitVersion(junitVersion.getVersion());
            testClassOracleDatapointsBuilder.setTestClass(testClassTracto);
            testClassOracleDatapointsBuilder.setFocalClass(focalClazz);
            // Process test cases
            for (MethodDeclaration testCase : testCases) {
                List<AnnotationExpr> annotations = testCase.getAnnotations();
                // Check if the test case is annotated with @Ignore
                boolean isIgnore = false;
                for (AnnotationExpr annotation : annotations) {
                    if (annotation.getNameAsString().equals("Ignore") || annotation.getNameAsString().equals("Disabled")) {
                        isIgnore = true;
                    }
                }
                // If the test case is annotated with @Ignore, skip it
                if (isIgnore) {
                    continue;
                }

                Statement tgtStatement;
                List<Comment> comments = testCase.getAllContainedComments();
                for (Comment comment : comments) {
                    if (comment.isBlockComment()) {
                        String commentContent = comment.getContent();
                        if (commentContent.equals(TestUtils.FAKE_ELEMENT_LABEL)) {
                            comment.getCommentedNode().get().remove();
                        }
                    }
                }

                if (config.splitStrategy() == SplitStrategyType.ASSERTION) {
                    // Get the assertion statement in the test case
                    Optional<Statement> lastAssertion = JavaParserUtils.getLastAssertionInMethodDeclaration(testCase);
                    if (lastAssertion.isEmpty()) {
                        logger.error("No assertion found in test case: " + testCase.getNameAsString());
                        continue;
                    }
                    tgtStatement = lastAssertion.get();
                } else {
                    List<Statement> statements = testCase.findAll(Statement.class);
                    tgtStatement = statements.get(statements.size() - 1);
                }

                // Remove all comments from the target statement
                tgtStatement.getAllContainedComments().forEach(Node::remove);
                tgtStatement.getOrphanComments().forEach(Node::remove);
                tgtStatement.getComment().ifPresent(Node::remove);

                String target = config.noAssertionTarget();

                if (config.targetStrategy() == TargetStrategyType.ASSERTION) {
                    if (config.splitStrategy() == SplitStrategyType.ASSERTION) {
                        target = tgtStatement.toString().replaceAll("(?<![a-zA-Z0-9])Assert\\.", "").replaceAll("(?<![a-zA-Z0-9])Assertions\\.", "");
                        if (tgtStatement.isAssertStmt()) {
                            AssertStmt assertStmt = tgtStatement.asAssertStmt();
                            if (assertStmt.getCheck().isStringLiteralExpr()) {
                                StringLiteralExpr stringLiteralExpr = assertStmt.getCheck().asStringLiteralExpr();
                                if (stringLiteralExpr.getValue().equals(TestUtils.FAKE_ELEMENT_LABEL)) {
                                    target = THROW_EXCEPTION_LABEL;
                                }
                            }
                        } else if (target.startsWith("fail")) {
                            target = THROW_EXCEPTION_LABEL;
                        }
                    } else if (config.splitStrategy() == SplitStrategyType.STATEMENT) {
                        Optional<Statement> lastAssertion = JavaParserUtils.getLastAssertionInMethodDeclaration(testCase);
                        if (lastAssertion.isPresent()) {
                            if (lastAssertion.get().equals(tgtStatement)) {
                                target = tgtStatement.toString().replaceAll("(?<![a-zA-Z0-9])Assert\\.", "").replaceAll("(?<![a-zA-Z0-9])Assertions\\.", "");
                            }
                        }
                    }
                } else if (config.targetStrategy() == TargetStrategyType.STATEMENT) {
                    target = tgtStatement.toString();
                }
                // Get the parent block statement of the target statement
                Node parentNode = tgtStatement.getParentNode().orElseThrow(() -> new IllegalStateException("Parent statement not found"));
                if (config.splitStrategy() == SplitStrategyType.ASSERTION && config.targetStrategy() == TargetStrategyType.ASSERTION) {
                    if (!(config.assertionStrategy() == AssertionStrategyType.KEEP)) {
                        // Remove target statement from the test case
                        tgtStatement.remove();
                    }
                } else if (config.splitStrategy() == SplitStrategyType.STATEMENT && config.targetStrategy() == TargetStrategyType.STATEMENT) {
                    if (!(config.statementStrategy() == StatementStrategyType.KEEP)) {
                        // Remove target statement from the test case
                        tgtStatement.remove();
                    }
                    // TODO: MANAGE CONDITIONS
                } else if (config.splitStrategy() == SplitStrategyType.STATEMENT && config.targetStrategy() == TargetStrategyType.ASSERTION) {
                    if (!(config.assertionStrategy() == AssertionStrategyType.KEEP)) {
                        // Remove target statement from the test case
                        tgtStatement.remove();
                    }
                }
                Pair<Callable,List<Callable>> invokedMethodsPair = processInvokedMethods(repoSourcePath, focalClass, testClass, testCase, testsProcessingErrors, config);
                if (config.splitStrategy() == SplitStrategyType.ASSERTION && config.assertionStrategy() == AssertionStrategyType.MASK) {
                    // Mask the target statement
                    if (parentNode instanceof BlockStmt) {
                        parentNode.addOrphanComment(new BlockComment(config.mask()));
                    } else {
                        if (tgtStatement instanceof BlockStmt) {
                            tgtStatement.addOrphanComment(new BlockComment(config.mask()));
                        }
                    }
                }
                if (config.splitStrategy() == SplitStrategyType.STATEMENT && config.targetStrategy() == TargetStrategyType.STATEMENT && config.statementStrategy() == StatementStrategyType.MASK) {
                    // Mask the target statement
                    if (parentNode instanceof BlockStmt) {
                        parentNode.addOrphanComment(new BlockComment(config.mask()));
                    } else {
                        if (tgtStatement instanceof BlockStmt) {
                            tgtStatement.addOrphanComment(new BlockComment(config.mask()));
                        }
                    }
                } else if (config.splitStrategy() == SplitStrategyType.STATEMENT && config.targetStrategy() == TargetStrategyType.ASSERTION && config.assertionStrategy() == AssertionStrategyType.MASK) {
                    if (parentNode instanceof BlockStmt) {
                        parentNode.addOrphanComment(new BlockComment(config.mask()));
                    } else {
                        if (tgtStatement instanceof BlockStmt) {
                            tgtStatement.addOrphanComment(new BlockComment(config.mask()));
                        }
                    }
                }
                // TODO: Focal method excluded from the analysis for the moment
                Callable focalMethodTracto = invokedMethodsPair.getValue0();
                List<Callable> invokedMethods = invokedMethodsPair.getValue1();
                Callable testPrefix = processCallable(testClass, testCase, invokedMethods);
                // TODO: Check id the last statement in the test case is an assertion or not
                // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
                oracleDatapoints.add(new OracleDatapoint(testPrefix, target));
            }
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            TestClazzDatapoints<OracleDatapoint> testClassOracleDatapoints = testClassOracleDatapointsBuilder.build();
            if (testClassOracleDatapoints.testClass() == null || testClassOracleDatapoints.focalClass() == null || testClassOracleDatapoints.junitVersion() == null) {
                throw new IllegalStateException("Test class or focal class null when building the object to collect the datapoints: " + testFilePath);
            }
        }
        // Set the list of oracle datapoints generated from the test cases of the test class
        testClassOracleDatapointsBuilder.setDatapoints(oracleDatapoints);
        return new Pair<>(testClassOracleDatapointsBuilder.build(), testsProcessingErrors);
    }

    /**
     * Extract the information about the fields defined in the type declaration (class) passed as input.
     * @param typeDeclaration the type declaration (class) to analyze
     * @return the list of {@link Field}, providing information about each field defined in the compilation unit
     */
    private static List<Field> processFieldsFromTypeDeclaration(TypeDeclaration typeDeclaration) {
        return processFieldsFromTypeDeclaration(typeDeclaration, false);
    }

    /**
     * Extract the information about the fields defined in the type declaration (class) passed as input.
     * @param typeDeclaration the type declaration (class) to analyze
     * @param contextualInfo if {@code true}, the method will collect a limited information about the fields, the only
     *                       necessary for the prompt used to train the model or at inference time.
     * @return the list of {@link Field}, providing information about each field defined in the compilation unit
     */
    private static List<Field> processFieldsFromTypeDeclaration(TypeDeclaration typeDeclaration, boolean contextualInfo) {
        List<Field> fields = new ArrayList<>();
        FieldBuilder fieldBuilder = new FieldBuilder();

        FieldDeclarationVisitor fieldDeclarationCollector = new FieldDeclarationVisitor();
        List<FieldDeclaration> fieldDeclarations = fieldDeclarationCollector.visit(typeDeclaration);

        for (FieldDeclaration fieldDeclaration : fieldDeclarations) {
            // Get field modifiers
            List<String> modifiers = JavaParserUtils.getModifiersAsStrings(fieldDeclaration.getModifiers());
            // Analyze the single varibles within the given field declaration
            List<VariableDeclarator> variables = fieldDeclaration.getVariables();
            for (VariableDeclarator variable : variables) {
                // Set identifier
                fieldBuilder.setIdentifier(variable.getNameAsString());
                // Set signature
                String signature = JavaParserUtils.getSignatureFromVariableDeclarator(modifiers, variable);
                fieldBuilder.setSignature(signature);
                // Set declarator
                fieldBuilder.setDeclarator(variable.toString());
                // Set field modifiers
                fieldBuilder.setModifiers(modifiers);
                // Analyze the type of the variable
                com.github.javaparser.ast.type.Type varType = variable.getType();
                Type type = !contextualInfo ? processType(typeDeclaration, varType, 0) : null;
                fieldBuilder.setType(type);
                fields.add(fieldBuilder.build());
                fieldBuilder.reset();
            }
        }
        return fields;
    }

    /**
     * Handle a type (that can be primitive, reference or void) and generate the corresponding {@link Type}.
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param type the type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the type
     */
    private static Type processType(TypeDeclaration typeDeclaration, com.github.javaparser.ast.type.Type type, int arrayLevel) {
        if (type.isPrimitiveType()) {
            return processPrimitiveType(type.asPrimitiveType(), arrayLevel);
        } else if (type.isArrayType()) {
            com.github.javaparser.ast.type.Type arrayType = type.asArrayType().getComponentType();
            return processType(typeDeclaration, arrayType, arrayLevel + 1);
        } else if (type.isTypeParameter()) {
            return processTypeParameter(typeDeclaration, type.asTypeParameter(), arrayLevel);
        } else if ((typeDeclaration.isClassOrInterfaceDeclaration() && typeDeclaration.asClassOrInterfaceDeclaration().getTypeParameters().stream().anyMatch(t -> t.getNameAsString().equals(type.toString())))) {
            TypeParameter typeParameter = typeDeclaration.asClassOrInterfaceDeclaration().getTypeParameters().stream()
                    .filter(t -> t.getNameAsString().equals(type.toString()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Type parameter not found: " + type.toString()));
            return processTypeParameter(typeDeclaration, typeParameter, arrayLevel);
        } else if (type.isReferenceType()) {
            return processReferenceType(typeDeclaration, type.asReferenceType(), arrayLevel);
        } else if (type.isVoidType()) {
            return processVoidType(type.asVoidType());
        }
        // TODO: Handle other types (like ClassOrInterfaceType, etc.)
        throw new RuntimeException("Type not supported: " + type.toString());
    }

    /**
     * Handle a type (that can be primitive, reference or void) and generate the corresponding {@link Type}.
     * The method is invoked when a constructor or a method cannot be resolved with a callable declaration, for example
     * when it refers to a class of an external library, whose source code is not accessible (only the bytecode is available).
     * Therefore, only the {@link ResolvedType} is available.
     *
     * @param type the type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the type
     */
    private static Type processType(ResolvedType type, int arrayLevel) {
        if (type.isPrimitive()) {
            return processPrimitiveType(type.asPrimitive(), arrayLevel);
        } else if (type.isArray()) {
            ResolvedType arrayType = type.asArrayType().getComponentType();
            return processType(arrayType, arrayLevel + 1);
        } else if (type.isReferenceType()) {
            return processReferenceType(type.asReferenceType(), arrayLevel);
        } else if (type.isTypeVariable()) {
            return processTypeVariable(type.asTypeVariable(), arrayLevel);
        } else if (type.isVoid()) {
            return processVoidType(type);
        }
        // TODO: Handle other types (like ResolvedClassDeclaration, etc.)
        throw new RuntimeException("Type not supported: " + type.toString());
    }

    /**
     * Process the primitive type and generate the corresponding {@link Type} object.
     *
     * @param primitiveType the primitive type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the primitive type
     */
    private static Type processPrimitiveType(PrimitiveType primitiveType, int arrayLevel) {
        // Instantiate a type builder to build a Type object from the primitive type
        TypeBuilder typeBuilder = new TypeBuilder();
        // Set the attributes of the type
        typeBuilder.setIdentifier(primitiveType.getType().asString().replace("Ljava", "java"));
        typeBuilder.setFullyQualifiedIdentifiers(new ArrayList<>());
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(true);
        typeBuilder.setIsGeneric(false);
        typeBuilder.setArrayLevel(arrayLevel);
        // A primitive type does not have any reference to classes. An empty list is set
        typeBuilder.setClazzes(new ArrayList<>());
        // Build the type and return it
        return typeBuilder.build();
    }

    /**
     * Process the primitive type and generate the corresponding {@link Type} object. The method
     * is invoked when the constructor or a method cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * only the {@link ResolvedPrimitiveType} is available.
     *
     *
     * @param resolvedPrimitiveType the primitive type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the primitive type
     */
    private static Type processPrimitiveType(ResolvedPrimitiveType resolvedPrimitiveType, int arrayLevel) {
        // Instantiate a type builder to build a Type object from the primitive type
        TypeBuilder typeBuilder = new TypeBuilder();
        // Set the attributes of the type
        typeBuilder.setIdentifier(resolvedPrimitiveType.describe().replace("Ljava", "java"));
        typeBuilder.setFullyQualifiedIdentifiers(new ArrayList<>());
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(true);
        typeBuilder.setIsGeneric(false);
        typeBuilder.setArrayLevel(arrayLevel);
        // A primitive type does not have any reference to classes. An empty list is set
        typeBuilder.setClazzes(new ArrayList<>());
        // Build the type and return it
        return typeBuilder.build();
    }

    /**
     * Check if the reference type is an array type.
     *
     * @param referenceType the reference type to check
     * @return {@code true} if the reference type is an array type, {@code false} otherwise
     */
    private static boolean isArrayType(ReferenceType referenceType) {
        if (referenceType.isArrayType()) {
            return true;
        } else if (referenceType instanceof ClassOrInterfaceType) {
            Optional<Node> parentNode = referenceType.getParentNode();
            if (parentNode.isPresent()) {
                Node parent = parentNode.get();
                if (parent instanceof ArrayType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Process the reference type and generate the corresponding {@link Type} object.
     *
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param referenceType the reference type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the reference type
     */
    private static Type processReferenceType(TypeDeclaration typeDeclaration, ReferenceType referenceType, int arrayLevel) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(referenceType.isTypeParameter());
        typeBuilder.setArrayLevel(arrayLevel);
        typeBuilder.setIdentifier(referenceType.toString());
        // Try to find additional information about the reference type
        List<String> classNames = new ArrayList<>();
        try {
            // Set the identifier of the reference type
            classNames.add(referenceType.toDescriptor().replace("Ljava", "java"));
        } catch (UnsolvedSymbolException | IllegalStateException | UnsupportedOperationException e) {
            // TODO: Handle complex types like generics, List of reference types, List of Pair, etc.
            // Regex to match class names (starting with uppercase letters)
            Pattern pattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b");
            Matcher matcher = pattern.matcher(referenceType.toString());
            while (matcher.find()) {
                String className = matcher.group();
                // If the reference type is not resolved, try to find the identifier in the imports of the compilation unit
                for (ImportDeclaration importDeclaration : typeDeclaration.findCompilationUnit().get().getImports()) {
                    if (importDeclaration.getNameAsString().endsWith(className)) {
                        classNames.add(importDeclaration.getNameAsString());
                        break;
                    }
                }
            }
        }
        typeBuilder.setFullyQualifiedIdentifiers(classNames);
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();

        // TODO: Implement the processing of the reference type (improved version of the dataset)
        /*Optional<ResolvedReferenceTypeDeclaration> resolvedType = referenceType.resolve().asReferenceType().getTypeDeclaration();

        if (resolvedType.isPresent()) {
            // Initialize a clazz builder to build a ClazzTracto object from the resolved type
            ClazzBuilder clazzBuilder = new ClazzBuilder();
            // Get methods defined and inherited by the resolved type
            Set<MethodUsage> methods = resolvedType.get().getAllMethods();
            // Get constructors defined and inherited by the resolved type
            List<ResolvedConstructorDeclaration> constructors = resolvedType.get().getConstructors();
            // Process the methods of the variable type
            for (MethodUsage method : methods) {
                // Try to resolve the method declaration from the method usage
                Optional<Node> resolvedMethodDeclaration = method.getDeclaration().toAst();
                if (resolvedMethodDeclaration.isPresent()) {
                    MethodDeclaration methodDeclaration = (MethodDeclaration) resolvedMethodDeclaration.get();
                    MethodTracto processedMethod = TestUtils.processCallable(methodDeclaration);
                } else {
                    // TODO: Analyze the method usage
                    throw new RuntimeException("Method declaration not found for method usage: " + method.getName());
                }
            }
        }*/
    }

    /**
     * Process the generic type and generate the corresponding {@link Type} object.
     *
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param typeParameter the generic type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the reference type
     */
    private static Type processTypeParameter(TypeDeclaration typeDeclaration, TypeParameter typeParameter, int arrayLevel) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(true);
        typeBuilder.setArrayLevel(arrayLevel);
        typeBuilder.setIdentifier(typeParameter.toString());
        // Try to find additional information about the reference type
        List<String> classNames = new ArrayList<>();
        try {
            // Set the identifier of the reference type
            classNames.add(typeParameter.toDescriptor().replace("Ljava", "java"));
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            // TODO: Handle complex types like generics, List of reference types, List of Pair, etc.
            // Regex to match class names (starting with uppercase letters)
            Pattern pattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b");
            Matcher matcher = pattern.matcher(typeParameter.toString());
            while (matcher.find()) {
                String className = matcher.group();
                // If the reference type is not resolved, try to find the identifier in the imports of the compilation unit
                for (ImportDeclaration importDeclaration : typeDeclaration.findCompilationUnit().get().getImports()) {
                    if (importDeclaration.getNameAsString().endsWith(className)) {
                        classNames.add(importDeclaration.getNameAsString());
                        break;
                    }
                }
            }
        }
        typeBuilder.setFullyQualifiedIdentifiers(classNames);
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the reference type and generate the corresponding {@link Type} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedReferenceType} is available.
     *
     * @param referenceType the reference type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the reference type
     */
    private static Type processReferenceType(ResolvedReferenceType referenceType, int arrayLevel) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(false);
        typeBuilder.setArrayLevel(arrayLevel);
        typeBuilder.setIdentifier(referenceType.getQualifiedName().substring(referenceType.getQualifiedName().lastIndexOf(".") + 1));
        // Try to find additional information about the reference type
        List<String> classNames = new ArrayList<>();
        try {
            // Set the identifier of the reference type
            classNames.add(referenceType.getQualifiedName().replace("Ljava", "java"));
        } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException e) {}
        typeBuilder.setFullyQualifiedIdentifiers(classNames);
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the generic type and generate the corresponding {@link Type} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedTypeVariable} is available.
     *
     * @param typeVariable the generic type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link Type} object generated from the generic type
     */
    private static Type processTypeVariable(ResolvedTypeVariable typeVariable, int arrayLevel) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(false);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(true);
        typeBuilder.setArrayLevel(arrayLevel);
        typeBuilder.setIdentifier(typeVariable.describe());
        // Try to find additional information about the reference type
        List<String> classNames = new ArrayList<>();
        try {
            // Set the identifier of the reference type
            classNames.add(typeVariable.qualifiedName().replace("Ljava", "java"));
        } catch (UnsolvedSymbolException e) {}
        typeBuilder.setFullyQualifiedIdentifiers(classNames);
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the void type and generate the corresponding {@link Type} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedType} is available.
     *
     * @param voidType the void type to process
     * @return the {@link Type} object generated from the resolved void type
     */
    private static Type processVoidType(ResolvedType voidType) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(true);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(false);
        typeBuilder.setArrayLevel(0);
        // Leave the detailed analysis of the reference type empty for the first version of the oracles dataset
        typeBuilder.setIdentifier(voidType.toString());
        typeBuilder.setFullyQualifiedIdentifiers(new ArrayList<>());
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the void type and generate the corresponding {@link Type} object.
     *
     * @param voidType the void type to process
     * @return the {@link Type} object generated from the void type
     */
    private static Type processVoidType(VoidType voidType) {
        TypeBuilder typeBuilder = new TypeBuilder();
        typeBuilder.setIsVoid(true);
        typeBuilder.setIsPrimitive(false);
        typeBuilder.setIsGeneric(false);
        typeBuilder.setArrayLevel(0);
        // Leave the detailed analysis of the reference type empty for the first version of the oracles dataset
        typeBuilder.setIdentifier(voidType.toString());
        typeBuilder.setFullyQualifiedIdentifiers(new ArrayList<>());
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the test class in the form of {@link TypeDeclaration} and generate the corresponding
     * {@link TestClazz} object.
     *
     * @param testClass the test class to process
     * @param testFilePath the path to the test class file
     * @param testPrefixFilePath the path to the test prefix file
     * @param repoRootPath the path to the root of the repository
     * @param distributedMethods the methods of the test class distributed according to their meaning
     *                           (auxiliary, setup & tear down, test cases)
     * @return the {@link TestClazz} object generated from the test class
     */
    private static TestClazz processTestClass(
            TypeDeclaration testClass,
            Path testFilePath,
            Path testPrefixFilePath,
            Path repoRootPath,
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> distributedMethods
    ) {
        TestClazzBuilder testClazzBuilder = new TestClazzBuilder();
        // Set test class identifier (name)
        testClazzBuilder.setIdentifier(testClass.getNameAsString());
        // Set test class package
        testClazzBuilder.setPackageIdentifier(JavaParserUtils.getPackageNameFromTypeDeclaration(testClass).orElse(""));
        // Retrieve the superclasses extended and the interfaces implemented by the test class
        List<String> superClasses = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        try {
            for(ResolvedReferenceType resolvedReferenceType : testClass.resolve().getAllAncestors()) {
                Optional<ResolvedReferenceTypeDeclaration> resolvedReferenceTypeDeclaration = resolvedReferenceType.getTypeDeclaration();
                if (resolvedReferenceTypeDeclaration.isPresent()) {
                    if (resolvedReferenceTypeDeclaration.get().isClass()) {
                        superClasses.add(resolvedReferenceTypeDeclaration.get().getQualifiedName());
                    } else if (resolvedReferenceTypeDeclaration.get().isInterface()) {
                        interfaces.add(resolvedReferenceTypeDeclaration.get().getQualifiedName());
                    }
                }
                // TODO: We miss the reference type without a corresponding declaration in this way.
                //       We can evaluate if join interfaces and extended classes in a single array and
                //       extract all the information directly from the ResolvedReferenceType
            }
        } catch (UnsolvedSymbolException e) {
            logger.error("Error resolving ancestors of type declaration: " + testClass.getNameAsString());
        }
        // Set test class superclasses and interfaces
        testClazzBuilder.setSuperClasses(superClasses);
        testClazzBuilder.setInterfaces(interfaces);
        // Set file path of the test class
        testClazzBuilder.setFilePath(testFilePath.toString().replace(repoRootPath.toString(), ""));
        testClazzBuilder.setPrefixFilePath(testPrefixFilePath.toString().replace(repoRootPath.toString(), ""));
        // Set test class fields
        testClazzBuilder.setFields(processFieldsFromTypeDeclaration(testClass));
        // Set the list of all the test cases defined in the given test class
        List<MethodDeclaration> testCases = distributedMethods.getValue0();
        // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
        //       version the invoked methods are set to null.
        testClazzBuilder.setTestCases(testCases.stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Set the list of all the setup & tear down methods defined in the given test class
        HashMap<String, MethodDeclaration> setupTearDownMethods = distributedMethods.getValue1();
        testClazzBuilder.setSetupTearDownMethods(setupTearDownMethods.values().stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Set the list of all the auxiliary methods defined in the given test class
        HashMap<String, MethodDeclaration> auxiliaryMethods = distributedMethods.getValue2();
        testClazzBuilder.setAuxiliaryMethods(auxiliaryMethods.values().stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Generate and return the test class
        return testClazzBuilder.build();
    }

    /**
     * Process the focal class in the form of {@link TypeDeclaration} and generate the corresponding
     * {@link Clazz} object.
     *
     * @param focalClass the test class to process
     * @param sourceFilePath the path to the source file representing the focal class
     * @param repoRootPath the path to the root of the repository
     * @return the {@link TestClazz} object generated from the test class
     */
    private static Clazz processFocalClass(TypeDeclaration focalClass, Path sourceFilePath, Path repoRootPath) {
        ClazzBuilder focalClazzBuilder = new ClazzBuilder();
        // Set test class identifier (name)
        focalClazzBuilder.setIdentifier(focalClass.getNameAsString());
        // Set test class package
        focalClazzBuilder.setPackageIdentifier(JavaParserUtils.getPackageNameFromTypeDeclaration(focalClass).orElse(""));
        // Retrieve the superclasses extended and the interfaces implemented by the test class
        List<String> superClasses = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        try {
            for (ResolvedReferenceType resolvedReferenceType : focalClass.resolve().getAllAncestors()) {
                Optional<ResolvedReferenceTypeDeclaration> resolvedReferenceTypeDeclaration = resolvedReferenceType.getTypeDeclaration();
                if (resolvedReferenceTypeDeclaration.isPresent()) {
                    if (resolvedReferenceTypeDeclaration.get().isClass()) {
                        superClasses.add(resolvedReferenceTypeDeclaration.get().getQualifiedName());
                    } else if (resolvedReferenceTypeDeclaration.get().isInterface()) {
                        interfaces.add(resolvedReferenceTypeDeclaration.get().getQualifiedName());
                    }
                }
                // TODO: We miss the reference type without a corresponding declaration in this way.
                //       We can evaluate if join interfaces and extended classes in a single array and
                //       extract all the information directly from the ResolvedReferenceType
            }
        } catch (UnsolvedSymbolException e) {
            logger.error("Error resolving ancestors of type declaration: " + focalClass.getNameAsString());
        }
        // Set test class superclasses and interfaces
        focalClazzBuilder.setSuperClasses(superClasses);
        focalClazzBuilder.setInterfaces(interfaces);
        // Set file path of the test class
        focalClazzBuilder.setFilePath(sourceFilePath.toString().replace(repoRootPath.toString(), ""));
        // Set test class fields
        focalClazzBuilder.setFields(processFieldsFromTypeDeclaration(focalClass));
        // Set the list of all the constructors and methods defined in the given focal class
        List<ConstructorDeclaration> constructors = focalClass.getConstructors();
        // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
        //       version the invoked methods are set to null.
        focalClazzBuilder.setConstructors(constructors.stream().map(m -> processCallable(focalClass, m, null)).collect(Collectors.toList()));
        List<MethodDeclaration> methods = focalClass.getMethods();
        focalClazzBuilder.setMethods(methods.stream().map(m -> processCallable(focalClass, m, null)).collect(Collectors.toList()));
        return focalClazzBuilder.build();
    }

    /**
     * Process class for test orales contextual info in the form of {@link TypeDeclaration} and generate the corresponding
     * {@link Clazz} object.
     *
     * @param typeDeclaration the type declaration to process
     * @return the {@link TestClazz} object generated from the type declaration
     */
    public static Clazz processTypeDeclarationForContextualInfo(TypeDeclaration typeDeclaration) {
        ClazzBuilder focalClazzBuilder = new ClazzBuilder();
        // Set test class identifier (name)
        focalClazzBuilder.setIdentifier(typeDeclaration.getNameAsString());
        // Set test class package
        focalClazzBuilder.setPackageIdentifier(JavaParserUtils.getPackageNameFromTypeDeclaration(typeDeclaration).orElse(""));
        // Retrieve the superclasses extended and the interfaces implemented by the test class
        List<String> superClasses = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        // Set test class superclasses and interfaces
        focalClazzBuilder.setSuperClasses(superClasses);
        focalClazzBuilder.setInterfaces(interfaces);
        // Set file path of the test class
        focalClazzBuilder.setFilePath("");
        // Set test class fields
        focalClazzBuilder.setFields(processFieldsFromTypeDeclaration(typeDeclaration, true));
        // Set the list of all the constructors and methods defined in the given focal class
        List<ConstructorDeclaration> constructors = typeDeclaration.getConstructors();
        // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
        //       version the invoked methods are set to null.
        focalClazzBuilder.setConstructors(constructors.stream().map(m -> processCallable(typeDeclaration, m, null, true)).collect(Collectors.toList()));
        List<MethodDeclaration> methods = typeDeclaration.getMethods();
        focalClazzBuilder.setMethods(methods.stream().map(m -> processCallable(typeDeclaration, m, null, true)).collect(Collectors.toList()));
        return focalClazzBuilder.build();
    }

    /**
     * Process a type declaration to retrieve the list of related type declarations (extended and implemented classes), iteratively.
     *
     * @param referenceTypeDeclaration the reference type declaration to analyze
     * @param repoSourcePath the root path of the repository where the type declaration is located
     * @return the list of related type declarations (extended and implemented classes) of the reference type declaration
     */
    private static List<TypeDeclaration> getRelatedTypeDeclarations(TypeDeclaration referenceTypeDeclaration, Path repoSourcePath, Set<String> visited) {
        // Define the list of related type declarations to return. Initially empty.
        List<TypeDeclaration> relatedTypeDeclarations = new ArrayList<>();
        // Define the list of extended and implemented types of the reference type declaration
        List<ClassOrInterfaceType> extendedAndImplementedTypes = new ArrayList<>();
        // Check if the reference type declaration is a class or interface declaration. In this case, retrieve the
        // extended and implemented types.
        if (referenceTypeDeclaration instanceof ClassOrInterfaceDeclaration) {
            extendedAndImplementedTypes.addAll(((ClassOrInterfaceDeclaration) referenceTypeDeclaration).getExtendedTypes());
            extendedAndImplementedTypes.addAll(((ClassOrInterfaceDeclaration) referenceTypeDeclaration).getImplementedTypes());
        } else {
            // Not a class or interface declaration. No extended or implemented types to retrieve. Return an empty list.
            return relatedTypeDeclarations;
        }
        // Retrieve the compilation unit of the reference type declaration
        CompilationUnit referenceTypeCu = (CompilationUnit) referenceTypeDeclaration.getParentNode().orElse(null);
        // Retrieve the list of imports of the reference type compilation unit
        NodeList<ImportDeclaration> referenceTypeImports = referenceTypeCu != null ? referenceTypeCu.getImports() : new NodeList<>();
        // Retrieve the package of the reference type declaration
        PackageDeclaration referenceTypePackage = referenceTypeCu != null ? referenceTypeCu.getPackageDeclaration().orElse(null) : null;
        // Iterate over the extended and implemented types of the reference type declaration
        // to collect the related type declarations (iteratively)
        for (ClassOrInterfaceType extendedOrImplementedType : extendedAndImplementedTypes) {
            // Retrieve the name of the extended or implemented type
            String extendedOrImplementedTypeName = extendedOrImplementedType.getNameAsString();
            // Define the fully qualified name of the extended or implemented type to resolve. Initially null.
            String fqn = null;
            // Check if the extended or implemented type is imported in the reference type compilation unit
            for (ImportDeclaration referenceTypeImport : referenceTypeImports) {
                String importName = referenceTypeImport.getNameAsString();
                // Check if the import name ends with the name of the extended or implemented type
                if (importName.endsWith(extendedOrImplementedTypeName)) {
                    // Set the fully qualified name of the extended or implemented type and break the loop. The reference
                    // type declaration is found in the imports.
                    fqn = importName;
                    break;
                }
            }
            // Check if the fully qualified name of the extended or implemented type is still null. In this case, if
            // the package of the reference type declaration is not null, set the fully qualified name of the extended
            // or implemented type by concatenating the package name and the name of the extended or implemented type.
            // The reference type can lie in the same package of the reference type declaration.
            if (fqn == null && referenceTypePackage != null) {
                fqn = referenceTypePackage.getNameAsString().concat("." + extendedOrImplementedTypeName);
            }

            if (fqn != null && !visited.contains(fqn)) {
                visited.add(fqn);
                try {
                    Optional<TypeDeclaration> relatedTypeDeclaration = JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName(fqn, repoSourcePath);
                    if (relatedTypeDeclaration.isPresent()) {
                        relatedTypeDeclarations.add(relatedTypeDeclaration.get());
                        relatedTypeDeclarations.addAll(getRelatedTypeDeclarations(relatedTypeDeclaration.get(), repoSourcePath, visited));
                    }
                } catch (Exception e) {
                    logger.error("Error retrieving related type declaration: " + fqn);
                }
            }
        }
        return relatedTypeDeclarations;
    }

    private static Pair<Callable,List<Callable>> processInvokedMethods(Path repoSourcePath, TypeDeclaration focalClass, TypeDeclaration testClass, MethodDeclaration testCase, HashMap<String, HashMap<String, List<String>>> testsProcessingErrors, OraclesDatasetConfig config) {
        // Define list of methods invoked in the test case
        List<Callable> invokedMethods = new ArrayList<>();
        // Define the focal method to find. Initially null.
        Callable focalMethod = null;
        // Get the name of the test case
        String testName = testCase.getNameAsString();
        // Get the name of the test class
        String testClassName = testClass.getFullyQualifiedName().orElse(testClass.getNameAsString()).toString();
        // Setup error map for the test class
        testsProcessingErrors.putIfAbsent(testClassName, new HashMap<>());
        // Setup error map for the test case
        testsProcessingErrors.get(testClassName).putIfAbsent(testName, new ArrayList<>());
        try {
            // Get the list of all the statements within the test
            List<ExpressionStmt> statements = StmtVisitorHelper.getAllExpressionStmts(testCase.getBody().get());
            // Throw an exception if no statements are found (empty test)
            if (statements.size() <= 0 && config.targetStrategy() == TargetStrategyType.ASSERTION) {
                throw new IllegalStateException(String.format("No expression statements found in test %s.", testName));
            }
            // Get statement index
            int stmtIndex = statements.size() - 1;
            // Define an HashMap saving information about the variable progressively defined (and used) within the test case
            HashMap<String, TypeDeclaration> projectVariables = new HashMap<>();
            HashMap<String, ResolvedReferenceType> librariesVariables = new HashMap<>();

            for (FieldDeclaration testClassField : testClass.findAll(FieldDeclaration.class)) {
                for (VariableDeclarator variable : testClassField.getVariables()) {
                    com.github.javaparser.ast.type.Type variableType = variable.getType();
                    while (variableType.isArrayType()) {
                        variableType = variableType.asArrayType().getComponentType();
                    }
                    // TODO: Handle the case when the variable type is an array
                    try {
                        if (variableType.isReferenceType() && !variableType.resolve().isTypeVariable()) {
                            ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = variableType.resolve().asReferenceType().getTypeDeclaration().orElse(null);

                            if (resolvedReferenceTypeDeclaration != null) {
                                if (resolvedReferenceTypeDeclaration instanceof JavaParserClassDeclaration) {
                                    projectVariables.put(variable.getNameAsString(), ((JavaParserClassDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode());
                                    continue;
                                } else if (resolvedReferenceTypeDeclaration instanceof JavaParserInterfaceDeclaration) {
                                    projectVariables.put(variable.getNameAsString(), ((JavaParserInterfaceDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode());
                                    continue;
                                }
                            }
                            try {
                                ResolvedReferenceType resolvedReferenceType = variableType.resolve().asReferenceType();
                                Optional<TypeDeclaration> resolvedTypeDeclaration = JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName(resolvedReferenceType.getQualifiedName(), repoSourcePath);
                                if (resolvedTypeDeclaration.isPresent()) {
                                    projectVariables.put(variable.getNameAsString(), resolvedTypeDeclaration.get());
                                } else {
                                    librariesVariables.put(variable.getNameAsString(), resolvedReferenceType);
                                }
                            } catch (Exception e) {
                                String errorMsg = "Unable to resolve variable type " + variableType.toString() + " in test class " + testClass.getNameAsString();
                                // logger.error(errorMsg);
                                testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        String errorMsg = "Unable to resolve variable type " + variableType.toString() + " in test class " + testClass.getNameAsString();
                        // logger.error(errorMsg);
                        testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                    }
                }
            }


            // Iterate over the statements of the test from the top to the bottom
            for (int i = 0; i <= stmtIndex; i++) {
                // Get current statement
                ExpressionStmt exprStmt = statements.get(i);
                Expression expr = null;
                CallableExprType exprType = null;
                // Get the expression from the current statement
                Expression unparsedExpr = exprStmt.getExpression();
                // Parse the expression to understand if it refers to a method call or construction invocation
                List<Pair<Expression,CallableExprType>> expressionAnalysisResult = JavaParserUtils.parseExpression(unparsedExpr, true);
                for (Pair<Expression,CallableExprType> exprPair : expressionAnalysisResult) {
                    try {
                        // Get the expression and its type
                        expr = exprPair.getValue0();
                        exprType = exprPair.getValue1();
                        boolean fromExternalLibrary = false;
                        // Initialize the invoked class to the reference class. The invoked class is the instance of the class
                        // that contains the invoked method.
                        TypeDeclaration invokedClass = focalClass;

                        if (exprType == CallableExprType.METHOD) {
                            if (expr.asMethodCallExpr().getScope().isPresent()) {
                                Expression scope = expr.asMethodCallExpr().getScope().get();
                                while (scope.isArrayAccessExpr()) {
                                    scope = scope.asArrayAccessExpr().getName();
                                }
                                if (scope.isNameExpr()) {
                                    String scopeName = scope.asNameExpr().getNameAsString();
                                    if (projectVariables.containsKey(scopeName)) {
                                        invokedClass = projectVariables.get(scopeName);
                                    } else if (librariesVariables.containsKey(scopeName)) {
                                        fromExternalLibrary = true;
                                        ResolvedReferenceType resolvedReferenceType = librariesVariables.get(scopeName);
                                        ResolvedMethodDeclaration resolvedMethodDeclaration = JavaParserUtils.searchCandidateResolvedMethodDeclaration(resolvedReferenceType, expr.asMethodCallExpr());
                                        if (resolvedMethodDeclaration == null) {
                                            throw new CandidateCallableDeclarationNotFoundException("The method in the expression " + expr.toString() + " cannot be resolved in the given focal class " + focalClass.getNameAsString());
                                        }
                                        invokedMethods.add(processMethodCallExpr(expr.asMethodCallExpr().resolve()));
                                    }
                                }
                            }
                        }
                        if (!fromExternalLibrary) {
                            // Get the invoked method. The invoked method must be a method declaration or a constructor declaration.
                            // Start searching the method or constructor in the test class
                            CallableDeclaration invokedMethod = JavaParserUtils.resolveCallExpression(testClass, expr, exprType);
                            // If the focal method is not found, look at the methods and constructors in the focal class
                            if (invokedMethod == null) {
                                invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                            }
                            // If the focal method is still not found, search it within the inherited methods of the focal class
                            if (invokedMethod == null) {
                                List<TypeDeclaration> relatedTypeDeclarations = getRelatedTypeDeclarations(invokedClass, repoSourcePath, new HashSet<>());
                                relatedTypeDeclarations.add(0, invokedClass);
                                for (TypeDeclaration relatedTypeDeclaration : relatedTypeDeclarations) {
                                    invokedClass = relatedTypeDeclaration;
                                    if (exprType == CallableExprType.CONSTRUCTOR) {
                                        com.github.javaparser.ast.type.Type constructorType = expr.asObjectCreationExpr().getType();
                                        while (constructorType.isArrayType()) {
                                            constructorType = constructorType.asArrayType().getComponentType();
                                        }
                                        // TODO: Handle the case when the variable type is an array
                                        try {
                                            if (constructorType.isReferenceType() && !constructorType.resolve().isTypeVariable()) {
                                                ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = constructorType.resolve().asReferenceType().getTypeDeclaration().orElse(null);
                                                if (resolvedReferenceTypeDeclaration != null) {
                                                    if (resolvedReferenceTypeDeclaration instanceof JavaParserClassDeclaration) {
                                                        invokedClass = ((JavaParserClassDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode();
                                                        invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                                                    } else if (resolvedReferenceTypeDeclaration instanceof JavaParserInterfaceDeclaration) {
                                                        invokedClass = ((JavaParserInterfaceDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode();
                                                        invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                                                    }
                                                }
                                                if (invokedMethod == null) {
                                                    String candidateClassPath = constructorType.resolve().asReferenceType().getQualifiedName();
                                                    Optional<TypeDeclaration> resolvedTypeDeclaration = JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName(candidateClassPath, repoSourcePath);
                                                    if (resolvedTypeDeclaration.isPresent()) {
                                                        invokedClass = resolvedTypeDeclaration.get();
                                                        invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                                                        if (invokedMethod == null) {
                                                            if (expr.toString().equals("new " + invokedClass.getNameAsString() + "()")) {
                                                                invokedMethod = new ConstructorDeclaration();
                                                                invokedMethod.setName(invokedClass.getNameAsString());
                                                            } else {
                                                                throw new NoFocalMethodMatchingException("The constructor in the expression " + expr.toString() + " cannot be resolved in the given focal class " + invokedClass.getNameAsString());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception e) {
                                            String errorMsg = "Unable to resolve constructor type " + constructorType.toString() + " in test class " + testClass.getNameAsString();
                                            // logger.error(errorMsg);
                                            testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                                        }
                                    } else {
                                        MethodUsage invokedMethodUsage = JavaParserUtils.searchCandidateMethodUsage(invokedClass, expr);
                                        // If the focal method is still not found, raise an exception
                                        if (!(invokedMethodUsage == null)) {
                                            Optional<TypeDeclaration> resolvedTypeDeclaration = JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName(invokedMethodUsage.declaringType().getQualifiedName(), repoSourcePath);
                                            if (resolvedTypeDeclaration.isPresent()) {
                                                invokedClass = resolvedTypeDeclaration.get();
                                                List<MethodDeclaration> candidateMethods = invokedClass.getMethods();
                                                invokedMethod = JavaParserUtils.matchMethodUsageWithMethodDeclaration(invokedMethodUsage, candidateMethods);
                                            }
                                        }
                                    }
                                    if(invokedMethod != null) {
                                        break;
                                    }
                                }
                            }
                            // If the invoked method is still not found, process the expression as a call from an external library
                            if (invokedMethod == null) {
                                fromExternalLibrary = true;
                                try {
                                    if (exprType == CallableExprType.METHOD) {
                                        invokedMethods.add(processMethodCallExpr(expr.asMethodCallExpr().resolve()));
                                    } else {
                                        invokedMethods.add(processObjectCreationExpr(expr.asObjectCreationExpr()));
                                    }
                                } catch (UnsolvedSymbolException e) {
                                    throw new NoFocalMethodMatchingException("The method in the expression " + expr.toString() + " cannot be resolved in the given focal class " + focalClass.getNameAsString());
                                }
                            } else {
                                // Create the focal method
                                // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
                                //       version the invoked methods are set to null.
                                invokedMethods.add(processCallable(invokedClass, invokedMethod, null));
                                // Update focal method
                                if (invokedClass.equals(focalClass)) {
                                    focalMethod = invokedMethods.get(invokedMethods.size() - 1);
                                }
                            }
                        }
                    } catch (
                            NoFocalMethodMatchingException | NoPrimaryTypeException | UnrecognizedExprException |
                            CandidateCallableDeclarationNotFoundException |
                            CandidateCallableMethodUsageNotFoundException |
                            MultipleCandidatesException | UnsolvedSymbolException | IllegalStateException e
                    ) {
                        String errorMsg = "Error processing statement " + exprStmt + " of test case " + testName;
                        // logger.error(errorMsg);
                        testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                    }
                }
                if (unparsedExpr.isVariableDeclarationExpr() ) {
                    VariableDeclarationExpr varExpr = unparsedExpr.asVariableDeclarationExpr();
                    for (VariableDeclarator var : varExpr.getVariables()) {
                        com.github.javaparser.ast.type.Type varType = var.getType();
                        while (varType.isArrayType()) {
                            varType = varType.asArrayType().getComponentType();
                        }
                        // TODO: Handle the case when the variable type is an array
                        try {
                            if (varType.isReferenceType() && !varType.resolve().isTypeVariable()) {
                                ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = varType.resolve().asReferenceType().getTypeDeclaration().orElse(null);

                                if (resolvedReferenceTypeDeclaration != null) {
                                    if (resolvedReferenceTypeDeclaration instanceof JavaParserClassDeclaration) {
                                        projectVariables.put(var.getNameAsString(), ((JavaParserClassDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode());
                                        continue;
                                    } else if (resolvedReferenceTypeDeclaration instanceof JavaParserInterfaceDeclaration) {
                                        projectVariables.put(var.getNameAsString(), ((JavaParserInterfaceDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode());
                                        continue;
                                    }
                                }

                                try {
                                    String fqnReturnType = varType.resolve().asReferenceType().getQualifiedName();
                                    Optional<TypeDeclaration> resolvedTypeDeclaration = JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName(fqnReturnType, repoSourcePath);
                                    if (resolvedTypeDeclaration.isPresent()) {
                                        projectVariables.put(var.getNameAsString(), resolvedTypeDeclaration.get());
                                    } else {
                                        librariesVariables.put(var.getNameAsString(), varType.resolve().asReferenceType());
                                    }
                                } catch (Exception e) {
                                    String errorMsg = "Unable to resolve variable type " + varType.toString() + " in test class " + testClass.getNameAsString();
                                    // logger.error(errorMsg);
                                    testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            String errorMsg = "Unable to resolve variable type " + varType.toString() + " in test class " + testClass.getNameAsString();
                            // logger.error(errorMsg);
                            testsProcessingErrors.get(testClassName).get(testName).add(errorMsg + " | " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            String errMsg = String.format(
                    "[ERROR] - Unexpected error while matching focal method of class %s for test %s (%s)",
                    focalClass.getNameAsString(),
                    testCase.getNameAsString(),
                    e.getClass().getName()
            );
            // logger.error(errMsg);
            testsProcessingErrors.get(testClassName).get(testName).add(errMsg + " | " + e.getMessage());
        }
        // Remove duplicates from the list of invoked methods and return the focal method and the set of invoked methods (as list)
        return new Pair<>(focalMethod, new ArrayList<>(new LinkedHashSet<>(invokedMethods)));
    }

    /**
     * Process the class or interface type and generate the corresponding {@link Clazz} object.
     *
     * @param classOrInterfaceType the class or interface type to process
     * @return the {@link Clazz} object generated from the class or interface type
     */
    private static Clazz processClassOrInterface(ClassOrInterfaceType classOrInterfaceType) {
        ClazzBuilder clazzBuilder = new ClazzBuilder();
        Optional<ResolvedReferenceTypeDeclaration> resolvedReferenceTypeDeclaration = classOrInterfaceType.resolve().asReferenceType().getTypeDeclaration();

        clazzBuilder.setIdentifier(classOrInterfaceType.getNameAsString());

        if (resolvedReferenceTypeDeclaration.isPresent()) {
            Optional<Node> classOrInterfaceDeclaration = resolvedReferenceTypeDeclaration.get().toAst();

            if (classOrInterfaceDeclaration.isPresent() && classOrInterfaceDeclaration.get() instanceof ClassOrInterfaceDeclaration) {
                // TODO: Check
                //return processClassOrInterface(((ClassOrInterfaceDeclaration) classOrInterfaceDeclaration.get());
            }
        }
        //TODO: manage the other cases
        return null;
    }

    /**
     * Process the method declaration and generate the corresponding {@link Callable} object.
     *
     * @param typeDeclaration the type declaration (class) to which the method belongs
     * @param callableDeclaration the method or constructor declaration to process
     * @param invokedMethods the list of invoked methods within the method or constructor declaration. It can be null.
     * @return the {@link Callable} object generated from the method declaration
     */
    public static Callable processCallable(TypeDeclaration typeDeclaration, CallableDeclaration callableDeclaration, List<Callable> invokedMethods) {
        return processCallable(typeDeclaration, callableDeclaration, invokedMethods, false);
    }

    /**
     * Process the method declaration and generate the corresponding {@link Callable} object.
     *
     * @param typeDeclaration the type declaration (class) to which the method belongs
     * @param callableDeclaration the method or constructor declaration to process
     * @param invokedMethods the list of invoked methods within the method or constructor declaration. It can be null.
     * @param contextualInfo a boolean flag to indicate if the method is processed as contextual information for the tokens
     *                       dataset prompt (in this case limited information are provided. The only necessary for the
     *                       prompt to the model)
     * @return the {@link Callable} object generated from the method declaration
     */
    public static Callable processCallable(TypeDeclaration typeDeclaration, CallableDeclaration callableDeclaration, List<Callable> invokedMethods, boolean contextualInfo) {
        // Instantiate a method builder to build a MethodTracto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        // Set the attributes of the method
        callableBuilder.setIdentifier(callableDeclaration.getNameAsString());
        callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(callableDeclaration.getAnnotations()));
        callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(callableDeclaration.getModifiers()));
        callableBuilder.setSignature(JavaParserUtils.getCallableSignature(callableDeclaration));
        callableBuilder.setThrownExceptions(JavaParserUtils.getExceptionsAsString(callableDeclaration.getThrownExceptions()));
        callableBuilder.setJavadoc(JavaParserUtils.getCallableJavadoc(callableDeclaration));
        // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
        callableBuilder.setBody(JavaParserUtils.getCallableSourceCode(callableDeclaration).replaceAll("(?<![a-zA-Z0-9])Assert\\.", "").replaceAll("(?<![a-zA-Z0-9])Assertions\\.", ""));
        // TODO: Manage invoked methods recursion in the improved version of the dataset
        callableBuilder.setInvokedMethods(invokedMethods); // avoid recursion in the first version of the dataset
        if (callableDeclaration instanceof MethodDeclaration && !contextualInfo) {
            callableBuilder.setReturnType(processType(typeDeclaration, ((MethodDeclaration) callableDeclaration).getType(),0));
        } else {
            callableBuilder.setReturnType(null);
        }
        // Process the parameters of the method
        List<Callable.Parameter> parameters = !contextualInfo ? new ArrayList<>() : null;

        if (!contextualInfo) {
            for (Object obj : callableDeclaration.getParameters()) {
                Parameter parameter = (Parameter) obj;
                String parameterName = parameter.getNameAsString();
                Type parameterType = processType(typeDeclaration, parameter.getType(), 0);
                parameters.add(new Callable.Parameter(parameterName, parameterType));
            }
        }
        callableBuilder.setParameters(parameters);
        // Build the method and return it
        return callableBuilder.build();
    }

    /**
     * Process the method usage and generate the corresponding {@link Callable} object without information related
     * to type declaration, return type and invoked methods.
     *
     * @param methodUsage the method usage to process
     * @return the {@link Callable} object generated from the method declaration
     */
    public static Callable processCallable(MethodUsage methodUsage) {
        // Instantiate a method builder to build a MethodTracto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        Optional<Node> declarationNode = methodUsage.getDeclaration().toAst();
        if (declarationNode.isPresent() && declarationNode.get() instanceof MethodDeclaration) {
            MethodDeclaration callableDeclaration = (MethodDeclaration) declarationNode.get();
            // Set the attributes of the method
            callableBuilder.setIdentifier(callableDeclaration.getNameAsString());
            callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(callableDeclaration.getAnnotations()));
            callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(callableDeclaration.getModifiers()));
            callableBuilder.setSignature(JavaParserUtils.getCallableSignature(callableDeclaration));
            callableBuilder.setThrownExceptions(JavaParserUtils.getExceptionsAsString(callableDeclaration.getThrownExceptions()));
            callableBuilder.setJavadoc(JavaParserUtils.getCallableJavadoc(callableDeclaration));
            // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
            callableBuilder.setBody(JavaParserUtils.getCallableSourceCode(callableDeclaration).replaceAll("(?<![a-zA-Z0-9])Assert\\.", "").replaceAll("(?<![a-zA-Z0-9])Assertions\\.", ""));
            // TODO: Manage invoked methods recursion in the improved version of the dataset
            callableBuilder.setInvokedMethods(new ArrayList<>()); // avoid recursion in the first version of the dataset
            if (callableDeclaration instanceof MethodDeclaration) {
                callableBuilder.setReturnType(null);
            }
            // Process the parameters of the method
            callableBuilder.setParameters(new ArrayList<>());
            // Build the method and return it
            return callableBuilder.build();
        } else {
            // Set the attributes of the method
            callableBuilder.setIdentifier(methodUsage.getName());
            callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(new ArrayList<>()));
            callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(new ArrayList<>()));
            callableBuilder.setSignature(JavaParserUtils.getMethodSignature(methodUsage));
            callableBuilder.setThrownExceptions(JavaParserUtils.getExceptions(methodUsage));
            callableBuilder.setJavadoc(JavaParserUtils.getJavadocByPattern(methodUsage.toString()));
            Optional<String> body = JavaParserUtils.getBodyFromMethodUsage(methodUsage);
            // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
            callableBuilder.setBody(body.isPresent() ? body.get() : "");
            // TODO: Manage invoked methods recursion in the improved version of the dataset
            callableBuilder.setInvokedMethods(new ArrayList<>());
            // Process the parameters of the method
            callableBuilder.setParameters(new ArrayList<>());
            // Build the method and return it
            return callableBuilder.build();
        }
    }

    /**
     * Process the resolved constructor declaration and generate the corresponding {@link Callable} object without
     * information related to type declaration, return type and invoked methods.
     *
     * @param resolvedConstructorDeclaration the resolved constructor declaration to process
     * @return the {@link Callable} object generated from the resolved constructor declaration
     */
    public static Callable processCallable(ResolvedConstructorDeclaration resolvedConstructorDeclaration) {
        // Instantiate a method builder to build a MethodTracto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        Optional<Node> declarationNode = resolvedConstructorDeclaration.toAst();
        if (declarationNode.isPresent() && declarationNode.get() instanceof ConstructorDeclaration) {
            ConstructorDeclaration callableDeclaration = (ConstructorDeclaration) declarationNode.get();
            // Set the attributes of the method
            callableBuilder.setIdentifier(callableDeclaration.getNameAsString());
            callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(callableDeclaration.getAnnotations()));
            callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(callableDeclaration.getModifiers()));
            callableBuilder.setSignature(JavaParserUtils.getCallableSignature(callableDeclaration));
            callableBuilder.setThrownExceptions(JavaParserUtils.getExceptionsAsString(callableDeclaration.getThrownExceptions()));
            callableBuilder.setJavadoc(JavaParserUtils.getCallableJavadoc(callableDeclaration));
            // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
            callableBuilder.setBody(JavaParserUtils.getCallableSourceCode(callableDeclaration).replaceAll("(?<![a-zA-Z0-9])Assert\\.", "").replaceAll("(?<![a-zA-Z0-9])Assertions\\.", ""));
            // TODO: Manage invoked methods recursion in the improved version of the dataset
            callableBuilder.setInvokedMethods(new ArrayList<>()); // avoid recursion in the first version of the dataset
            callableBuilder.setReturnType(null);
            // Process the parameters of the method
            callableBuilder.setParameters(new ArrayList<>());
            // Build the method and return it
            return callableBuilder.build();
        } else {
            // Set the attributes of the method
            callableBuilder.setIdentifier(resolvedConstructorDeclaration.getName());
            callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(new ArrayList<>()));
            callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(new ArrayList<>()));
            callableBuilder.setSignature(resolvedConstructorDeclaration.getSignature());
            callableBuilder.setThrownExceptions(new ArrayList<>());
            callableBuilder.setJavadoc(JavaParserUtils.getJavadocByPattern(resolvedConstructorDeclaration.toString()));
            Optional<String> body = JavaParserUtils.getBodyFromResolvedConstructorDeclaration(resolvedConstructorDeclaration);
            // Removed any occurrence of `Assert.` in the body of the method (not useful for the oracle generation)
            callableBuilder.setBody(body.isPresent() ? body.get() : "");
            // TODO: Manage invoked methods recursion in the improved version of the dataset
            callableBuilder.setInvokedMethods(new ArrayList<>());
            callableBuilder.setReturnType(null);
            // Process the parameters of the method
            callableBuilder.setParameters(new ArrayList<>());
            // Build the method and return it
            return callableBuilder.build();
        }

    }

    /**
     * Process the object creation expression and generate the corresponding {@link Callable} object. The method
     * is invoked when the constructor cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * some information are missing or less accurate.
     *
     * @param objCreationExpr the object creation expression to process
     * @return the {@link Callable} object generated from the method declaration
     */
    private static Callable processObjectCreationExpr(ObjectCreationExpr objCreationExpr) {
        ResolvedConstructorDeclaration resolvedConstructor = objCreationExpr.resolve();
        // Instantiate a method builder to build a MethodTracto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        // Set the attributes of the method
        callableBuilder.setIdentifier(resolvedConstructor.getName());
        callableBuilder.setAnnotations(null);
        callableBuilder.setModifiers(null);
        callableBuilder.setSignature(resolvedConstructor.getSignature());
        callableBuilder.setThrownExceptions(null);
        callableBuilder.setJavadoc("");
        callableBuilder.setBody(null);
        callableBuilder.setInvokedMethods(null); // avoid recursion in the first version of the dataset
        callableBuilder.setReturnType(null);
        // Process the parameters of the method
        List<Callable.Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < resolvedConstructor.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration resolvedParam = resolvedConstructor.getParam(i);
            Type parameterType = processType(resolvedParam.getType(), 0);
            parameters.add(new Callable.Parameter(null, parameterType));
        }
        callableBuilder.setParameters(parameters);
        // Build the method and return it
        return callableBuilder.build();
    }

    /**
     * Process the resolved method declaration and generate the corresponding {@link Callable} object. The method
     * is invoked when the method call cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * some information are missing or less accurate.
     *
     * @param resolvedMethod the resolved method declaration to process
     * @return the {@link Callable} object generated from the method declaration
     */
    private static Callable processMethodCallExpr(ResolvedMethodDeclaration resolvedMethod) {
        // Instantiate a method builder to build a MethodTracto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        // Set the attributes of the method
        callableBuilder.setIdentifier(resolvedMethod.getName());
        callableBuilder.setAnnotations(null);
        callableBuilder.setModifiers(null);
        callableBuilder.setSignature(resolvedMethod.getSignature());
        callableBuilder.setThrownExceptions(null);
        callableBuilder.setJavadoc("");
        callableBuilder.setBody(null);
        callableBuilder.setInvokedMethods(null); // avoid recursion in the first version of the dataset
        callableBuilder.setReturnType(null);
        // Process the parameters of the method
        List<Callable.Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < resolvedMethod.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration resolvedParam = resolvedMethod.getParam(i);
            Type parameterType = processType(resolvedParam.getType(), 0);
            parameters.add(new Callable.Parameter(null, parameterType));
        }
        callableBuilder.setParameters(parameters);
        // Build the method and return it
        return callableBuilder.build();
    }


    /**
     * Distribute the methods of the class according to their meaning.
     * The methods are distributed into the following categories:
     * <ul>
     *     <li>Test cases: methods annotated with @Test</li>
     *     <li>Setup and tear down methods: methods annotated with @Before, @BeforeEach, @After, @AfterEach, etc.</li>
     *     <li>Auxiliary methods: methods not annotated with any JUnit annotation (can contain assertions or procedures
     *     shared by multiple methods)</li>
     * </ul>
     *
     * @param testClassMethods the list of methods in the test class
     * @return a triplet containing the test cases, the setup and tear down methods, and the auxiliary methods
     */
    private static Triplet<List<MethodDeclaration>,HashMap<String,MethodDeclaration>, HashMap<String,MethodDeclaration>> distributeMethods(List<MethodDeclaration> testClassMethods) {
        // Create a map to store the setup and tear down methods (@Before, @BeforeEach, @After, @AfterEach, etc.)
        // The key is the signature of the method while the value is the method declaration
        HashMap<String, MethodDeclaration> setUpTearDownMethods = new HashMap<>();
        // Create a map to store auxiliary methods defined within the test class.
        // The key is the signature of the method while the value is the method declaration
        HashMap<String, MethodDeclaration> auxiliaryMethods = new HashMap<>();
        // Create a list to store the test cases
        List<MethodDeclaration> testCases = new ArrayList<>();
        // Distribute the methods into the corresponding set of methods
        for (MethodDeclaration method : testClassMethods) {
            NodeList<AnnotationExpr> methodAnnotations = method.getAnnotations();
            if (methodAnnotations.size() > 0) {
                if (methodAnnotations.stream().anyMatch(a -> JUnitAnnotationType.isTestAnnotation(a.getNameAsString()))) {
                    if (method.getBody().isPresent()) {
                        // TODO: Check if the method contains method calls to auxiliary methods with assertions
                        //      instead of discard the test method
                        //if (getNumberOfAssertions(method.getBody().get().getStatements()) > 0) {
                        //    testCases.add(method);
                        //}
                        testCases.add(method);
                    }
                } else if (methodAnnotations.stream().anyMatch(a -> a.getNameAsString().equals("Override"))) {
                    if (method.getBody().isPresent()) {
                        if (method.getNameAsString().contains("test") || getNumberOfAssertions(method.getBody().get().getStatements()) > 0) {
                            testCases.add(method);
                        } else {
                            auxiliaryMethods.put(method.getSignature().asString(), method);
                        }
                    } else {
                        auxiliaryMethods.put(method.getSignature().asString(), method);
                    }
                } else if (methodAnnotations.stream().anyMatch(a -> JUnitAnnotationType.isSetUpTearDownAnnotation(a.getNameAsString()))) {
                    setUpTearDownMethods.put(method.getSignature().asString(), method);
                } else {
                    auxiliaryMethods.put(method.getSignature().asString(), method);
                }
            } else {
                auxiliaryMethods.put(method.getSignature().asString(), method);
            }
        }
        return new Triplet<>(testCases, setUpTearDownMethods, auxiliaryMethods);
    }
}
