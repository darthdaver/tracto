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
import star.tracto.utils.javaParser.NameAllocator;
import star.tracto.utils.javaParser.visitors.declarations.FieldDeclarationVisitor;
import star.tracto.utils.javaParser.visitors.expressions.MethodCallExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.NameExprVisitor;
import star.tracto.utils.javaParser.visitors.expressions.VariableDeclarationExprVisitor;
import star.tracto.utils.javaParser.visitors.statements.AssertStmtVisitor;
import star.tracto.utils.javaParser.visitors.statements.ExpressionStmtVisitor;
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
        // Output path of the test cases discarded because they contain patterns the dataset does not support
        Path discardedOutputPath = Paths.get(outputPath.toString(), "discarded", "normalized", testFileRelativePath.replace(".java", NamingConvention.NORMALIZED_TEST_FILE.getConventionName()));
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
            // Get the set of variables defined in the test class. The set is defined from `IdentityHashMap`
            // that performs equality check by reference (`==`) instead of values (`equals()`) and uses as
            // hash function the `System.identityHashCode(obj)` method instead of obj.hashCode()
            Set<VariableDeclarator> classVariables = Collections.newSetFromMap(new IdentityHashMap<>());
            JavaParserUtils.collectExistingVariables(classVariables, testClass.getFields(), List.of(), ClassOrInterfaceDeclaration.class, 0);
            Set<String> fieldNames = JavaParserUtils.variableDeclaratorsToNames(classVariables);
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
                // Give a unique name to each local variable declared within the original test case. The renaming
                // resolves the symbols, so it must run on the original body, still attached to the compilation unit
                JavaParserUtils.normalizeVariableNames(originalTestCase.getBody().orElseThrow());
                // Get the list of statements within the original test case
                List<Statement> originalTestCaseStatements = originalTestCase.getBody().orElseThrow().getStatements();
                // Generate a mold of the test case and set the body to an empty block
                MethodDeclaration normalizedTestCase = originalTestCase.clone();
                BlockStmt normalizedTestCaseBody = new BlockStmt();
                normalizedTestCase.setBody(normalizedTestCaseBody);
                // Collect the list of the existing variables defined within the test case
                // Initially copy the references to the global variables defined within the test class
                // (accessible in all the points)
                Set<VariableDeclarator> testCaseExistingVariables = Collections.newSetFromMap(new IdentityHashMap<>());
                testCaseExistingVariables.addAll(classVariables);
                // Add the local variables defined within the first block "level" in the test case
                JavaParserUtils.collectExistingVariables(testCaseExistingVariables, originalTestCase.getBody().get(), MethodDeclaration.class,0);
                NormalizationContext context = new NormalizationContext(
                        testClass,
                        auxiliaryMethods,
                        integratedAuxiliaryMethods,
                        testCaseExistingVariables,
                        fieldNames,
                        variableNamesOf(originalTestCase, fieldNames),
                        labelNamesOf(originalTestCase),
                        new ArrayDeque<>(),
                        junitVersion,
                        config
                );

                // Parse the statements block and normalize the test case
                boolean thrownedException;
                try {
                    thrownedException = parseNormalizeTestStatementsBlock(
                            originalTestCaseStatements,
                            // The reference block is the body of the normalized test case (initially empty)
                            normalizedTestCaseBody,
                            context
                    );
                } catch (DiscardedTestCaseException e) {
                    // The test case contains a pattern the dataset does not support: it is not added to the normalized
                    // test class (so it produces no datapoints), and it is saved in the report of the discarded test
                    // cases, with the reason recorded in its statistics
                    logger.warn("Test case {} discarded: {}", originalTestCase.getNameAsString(), e.getMessage());
                    saveTestCaseReport(cu, originalTestCase, discardedOutputPath);
                    testStatsList.add(buildTestStats(testClass, testFilePath, originalTestCase, originalTestCase.getBody().orElseThrow(), e.getReason()));
                    continue;
                }
                // If an exception occurred during the normalization of the method, keep track of it for future
                // debugging and analysis
                if (thrownedException) {
                    saveTestCaseReport(cu, originalTestCase, errorOutputPath);
                }
                testStatsList.add(buildTestStats(testClass, testFilePath, normalizedTestCase, normalizedTestCaseBody, null));
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
     * Saves a copy of a test case in a report file (a copy of the test class with only the reported test cases), for
     * future analysis and debugging: the test cases with normalization or splitting errors, and the discarded ones.
     *
     * @param cu the compilation unit of the test class
     * @param originalTestCase the method declaration of the test case to report
     * @param errorOutputPath the path of the report file
     */
    private static void saveTestCaseReport(
            CompilationUnit cu,
            MethodDeclaration originalTestCase,
            Path errorOutputPath
    ) throws IOException {
        // Define the error test class compilation unit as a copy, so that the test class under normalization is not
        // modified
        CompilationUnit errorCu = cu.clone();
        // Define the error test class
        TypeDeclaration errorTestClass = errorCu.getPrimaryType().get();
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
            // Add a copy of the original test case to the error test class (adding the original node would move it
            // out of the test class under normalization)
            errorTestClass.addMember(originalTestCase.clone());
            // Save the error test class to the error output path
            FilesUtils.writeJavaFile(errorOutputPath, errorCu);
        }
    }

    /**
     * Collects the statistics of a test case.
     *
     * @param testClass the test class where the test case is defined
     * @param testFilePath the path to the test class file
     * @param testCase the test case
     * @param body the body of the test case to measure (the normalized body, or the original one for a discarded test case)
     * @param discardReason the reason why the test case has been discarded during the normalization, or {@code null}
     *                      if the test case has been normalized
     * @return the statistics of the test case
     */
    private static TestStats buildTestStats(TypeDeclaration testClass, Path testFilePath, MethodDeclaration testCase, BlockStmt body, String discardReason) {
        TestStatsBuilder testStatsBuilder = new TestStatsBuilder();
        testStatsBuilder.setIdentifier(testCase.getNameAsString());
        testStatsBuilder.setSignature(testCase.getSignature().toString());
        testStatsBuilder.setClassIdentifier(testClass.getNameAsString());
        testStatsBuilder.setFilePath(testFilePath.toString());
        testStatsBuilder.setNumberOfAssertions(getNumberOfAssertions(body.getStatements()));
        testStatsBuilder.setTestLength(body.toString().length());
        testStatsBuilder.setAssertionsDistribution(getAssertionsDistribution(body.getStatements()));
        // Count the number of methods found in the test
        int methodCalls = 0;
        for (Statement statement : body.getStatements()) {
            MethodCallExprVisitor methodCallExprCollector = new MethodCallExprVisitor();
            List<MethodCallExpr> methodCallExprs = methodCallExprCollector.visit(statement);
            methodCalls += methodCallExprs.size();
        }
        testStatsBuilder.setNumberOfMethodCalls(methodCalls);
        VariableDeclarationExprVisitor variableDeclarationExprCollector = new VariableDeclarationExprVisitor();
        List<String> declaredVarList = variableDeclarationExprCollector.visit(body).stream().map(VariableDeclarationExpr::toString).collect(Collectors.toList());
        NameExprVisitor nameExprCollector = new NameExprVisitor();
        List<String> usedVarList = nameExprCollector.visit(body).stream().map(NameExpr::toString).collect(Collectors.toList());
        List<String> paramList = testCase.getParameters().stream().map(p -> p.getNameAsString()).collect(Collectors.toList());
        Set<String> allVarList = new HashSet<>();
        allVarList.addAll(declaredVarList);
        allVarList.addAll(usedVarList);
        allVarList.addAll(paramList);
        testStatsBuilder.setNumberOfVariables(allVarList.size());
        testStatsBuilder.setDiscardReason(discardReason);
        return testStatsBuilder.build();
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
     * The ASSERTION strategy will return the following list of methods (in the form of
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
     * The split test cases are generated by {@link TestCaseSplitter}, and their split test prefixes (the split test
     * cases without their split points) are saved in a second class. A test case that cannot be split is saved in the
     * error report ({@code error/split}).
     *
     * @param config the configuration options for the dataset to generate
     * @param repoRootPath the path to the root of the repository
     * @param testFilePath the path to the test class file
     * @param outputPath the path to the split test class file
     * @return the paths to the split test class file and to the split test prefix class file
     */
    public static Pair<Path,Path> splitTest(
            OraclesDatasetConfig config,
            Path repoRootPath,
            Path testFilePath,
            Path outputPath
    ) {
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
            // Distribute the methods of the class according to their meaning
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testClassMethodsDistribution = distributeMethods(testClass.getMethods());
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testPrefixClassMethodsDistribution = distributeMethods(testPrefixClass.getMethods());
            // Get the list of all the original test cases within the given test class
            List<MethodDeclaration> originalTestCases = testClassMethodsDistribution.getValue0();
            List<MethodDeclaration> originalTestPrefixCases = testPrefixClassMethodsDistribution.getValue0();
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

                // Split the test case: one split test case (and split test prefix) for each split point
                try {
                    for (TestCaseSplitter.SplitTestCase splitTestCase : TestCaseSplitter.split(originalTestCase, config)) {
                        splitTestCases.add(splitTestCase.testCase());
                        splitTestCasesPrefixes.add(splitTestCase.prefix());
                    }
                } catch (Exception e) {
                    // The test case produces no split test cases: log the error and save the test case in the error
                    // report, for future analysis
                    logger.error("Error splitting test case " + originalTestCase.getNameAsString() + ": " + e.getMessage());
                    saveTestCaseReport(cuTestClass, originalTestCase, errorOutputPath);
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
     *     the body of the method is inlined in the reference block of statements, if possible, and normalized as well
     *     ({@link #inlineAuxiliaryMethod}); (ii) if the expression refers to a variable declaration, the statement is
     *     added to the reference block as it is; (iii) any other type of expression could contain method calls that
     *     cannot be integrated within the reference block of statements. Therefore they are added to the reference
     *     block as they are</li>
     *     <li>If the statement is a `forEach` statement {@link ForEachStmt} the `foreach` wrapper with the condition
     *     is cloned and the body is processed as a block statement calling the function recursively. The result of the
     *     recursion is appended to the body of the cloned `do` wrapper.</li>
     *     <li>If the statement is a `for` statement {@link ForStmt} the `for` wrapper with the condition is cloned and
     *     the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `do` wrapper.</li>
     *     <li>If the statement is an `if-then-else` statement {@link IfStmt} each block of `if`, `if-else`, `else` is
     *     processed: the conditions are cloned and each block processed recursively to be normalized.</li>
     *     <li>If the statement is a labeled statement {@link LabeledStmt}, the labeled statement is processed calling
     *     the function recursively, and labeled with the original label: a loop stays a single labeled statement,
     *     any other statement becomes a labeled block.</li>
     *     <li>If the statement is a synchronized statement {@link SynchronizedStmt} the `synchronized` wrapper is cloned
     *     and the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `synchronized` wrapper.</li>
     *     <li>If the statement is a switch statement {@link SwitchStmt}, an exception is thrown: no switch statements are
     *     expected within a test case. The statement process is therefore no supported yet.</li>
     *     <li>If the statement is a `try-catch-finally` statement {@link TryStmt}, its shape is classified by
     *     {@link TryStatementClassifier}. If the shape is not supported, the whole test case is discarded
     *     ({@link DiscardedTestCaseException}). Otherwise, with the `keep-try-catch` strategy the structure of the
     *     statement is kept and the statements of its blocks are normalized; with the `to-assert-throws` strategy the
     *     statement is flattened: the resources, then the statements of the try block (an expected exception, that is a
     *     call followed by `fail()` at the end of the try block, becomes an `assertThrows` with the exception of the
     *     catch clause, followed by the statements of the catch block), then the statements of the finally block.</li>
     *     <li>If the statement is a `while` statement {@link WhileStmt} the `while` wrapper with the condition is cloned and
     *     the body is processed as a block statement calling the function recursively. The result of the recursion is
     *     appended to the body of the cloned `while` wrapper.</li>
     *     <li>For any other statement type, they are added to the normalized reference block as they are.</li>
     * </ul>
     *
     * @param statements the list of statements to process
     * @param currentBlockStmt the reference block of statements representing the normalized body of the test case
     * @param context the state of the normalization of the test case
     * @return A boolean. True if an exception has been thrown during the normalization of the block of statements, false
     * otherwise.
     * @throws IllegalStateException In case of error when processing an `if` statement
     * @throws DiscardedTestCaseException If the test case contains a pattern the dataset does not support
     */
    private static boolean parseNormalizeTestStatementsBlock(
            List<Statement> statements,
            BlockStmt currentBlockStmt,
            NormalizationContext context
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            statement.asBlockStmt().getStatements(),
                            currentBlockStmt,
                            context
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(doStmtBody)),
                            // The reference block is now the body of the cloned `do` wrapper
                            doStmtCloneBody,
                            context
                    );
                } else if (statement.isExpressionStmt()) {
                    Expression exprStmt = statement.asExpressionStmt().getExpression();
                    // Check if the expression is a method call to an auxiliary method (not an assertion)
                    if (exprStmt.isMethodCallExpr() && !(JUnitAssertionType.isJUnitAssertion(exprStmt.asMethodCallExpr().getNameAsString()))) {
                        try {
                            // Try to inline the auxiliary method within the current block of statements (auxiliary
                            // methods can contain additional assertions)
                            InliningResult inlining = context.config().integrateAuxiliaryMethods() && exprStmt.asMethodCallExpr().getScope().isEmpty()
                                    ? inlineAuxiliaryMethod(exprStmt.asMethodCallExpr(), currentBlockStmt, context)
                                    : InliningResult.NOT_INLINED;
                            if (inlining.inlined()) {
                                thrownedException |= inlining.thrownedException();
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(forEachStmtBody)),
                            // The reference block is now the body of the cloned foreach
                            forEachCloneBody,
                            context
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(forStmtBody)),
                            // The reference block is now the body of the cloned `for`
                            forStmtCloneBody,
                            context
                    );
                } else if (statement.isIfStmt()) {
                    // Clone the `if` statement (with its `else if` chain) and add it to the current block. Each
                    // branch of the clone is then replaced with an empty block, filled with the normalized statements
                    // of the corresponding branch of the original statement. The original and the cloned `if` of
                    // each step of the `else if` chain are visited together, so that each branch is filled in its own
                    // `if`
                    IfStmt ifStmt = statement.asIfStmt();
                    IfStmt ifStmtClone = ifStmt.clone();
                    currentBlockStmt.addStatement(ifStmtClone);
                    while (true) {
                        BlockStmt thenStmtClone = new BlockStmt();
                        ifStmtClone.setThenStmt(thenStmtClone);
                        thrownedException |= parseNormalizeTestStatementsBlock(
                                new ArrayList<>(Arrays.asList(ifStmt.getThenStmt())),
                                // The reference block is now the `then` branch of the cloned `if`
                                thenStmtClone,
                                context
                        );
                        if (ifStmt.getElseStmt().isEmpty()) {
                            break;
                        }
                        Statement elseStmt = ifStmt.getElseStmt().get();
                        if (elseStmt.isIfStmt()) {
                            // Move to the next `if` of the `else if` chain
                            ifStmt = elseStmt.asIfStmt();
                            ifStmtClone = ifStmtClone.getElseStmt().get().asIfStmt();
                        } else {
                            BlockStmt elseStmtClone = new BlockStmt();
                            ifStmtClone.setElseStmt(elseStmtClone);
                            thrownedException |= parseNormalizeTestStatementsBlock(
                                    new ArrayList<>(Arrays.asList(elseStmt)),
                                    // The reference block is now the `else` branch of the cloned `if`
                                    elseStmtClone,
                                    context
                            );
                            break;
                        }
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(synchronizedStmtBody)),
                            synchronizedStmtBodyClone,
                            context
                    );
                } else if (statement.isLabeledStmt()) {
                    // Normalize the labeled statement within a separate block: a loop stays a single statement, and
                    // keeps its label (a `continue <label>` requires it), while a block or a flattened try statement
                    // become a labeled block
                    LabeledStmt labeledStmt = statement.asLabeledStmt();
                    LabeledStmt labeledStmtClone = labeledStmt.clone();
                    currentBlockStmt.addStatement(labeledStmtClone);
                    BlockStmt labeledStmtBody = new BlockStmt();
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(labeledStmt.getStatement())),
                            labeledStmtBody,
                            context
                    );
                    labeledStmtClone.setStatement(labeledStmtBody.getStatements().size() == 1 && !labeledStmt.getStatement().isBlockStmt()
                            ? labeledStmtBody.getStatement(0)
                            : labeledStmtBody);
                } else if (statement.isSwitchStmt()) {
                    throw new UnsupportedOperationException("Switch statement not supported yet.");
                } else if (statement.isTryStmt()) {
                    TryStmt tryStmt = statement.asTryStmt();
                    // A test case containing a try statement with a shape the dataset does not support is discarded
                    TryStatementShape tryStatementShape = TryStatementClassifier.classify(tryStmt);
                    if (!tryStatementShape.isSupported()) {
                        throw new DiscardedTestCaseException(tryStatementShape.name(), tryStatementShape.getDescription());
                    }
                    if (context.config().tryCatchFinallyStrategy() == TryCatchFinallyStrategyType.KEEP_TRY_CATCH) {
                        // Keep the structure of the try statement (resources, catch parameters): clone it with empty
                        // blocks, and fill them with the normalized statements of the original blocks
                        TryStmt tryStmtClone = tryStmt.clone();
                        BlockStmt tryBlockClone = new BlockStmt();
                        tryStmtClone.setTryBlock(tryBlockClone);
                        currentBlockStmt.addStatement(tryStmtClone);
                        thrownedException |= parseNormalizeTestStatementsBlock(
                                tryStmt.getTryBlock().getStatements(),
                                tryBlockClone,
                                context
                        );
                        for (int i = 0; i < tryStmt.getCatchClauses().size(); i++) {
                            BlockStmt catchBodyClone = new BlockStmt();
                            tryStmtClone.getCatchClauses().get(i).setBody(catchBodyClone);
                            thrownedException |= parseNormalizeTestStatementsBlock(
                                    tryStmt.getCatchClauses().get(i).getBody().getStatements(),
                                    catchBodyClone,
                                    context
                            );
                        }
                        if (tryStmt.getFinallyBlock().isPresent()) {
                            BlockStmt finallyBlockClone = new BlockStmt();
                            tryStmtClone.setFinallyBlock(finallyBlockClone);
                            thrownedException |= parseNormalizeTestStatementsBlock(
                                    tryStmt.getFinallyBlock().get().getStatements(),
                                    finallyBlockClone,
                                    context
                            );
                        }
                    } else {
                        // Flatten the try statement into a block statement, converting an expected exception into
                        // an assertThrows
                        BlockStmt flatTryStmtBlock = flatTryStmt(tryStmt, context);
                        // Parse the statements of the flat block and add them to the current block
                        thrownedException |= parseNormalizeTestStatementsBlock(
                                new ArrayList<>(Arrays.asList(flatTryStmtBlock)),
                                currentBlockStmt,
                                context
                        );
                    }
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
                    thrownedException |= parseNormalizeTestStatementsBlock(
                            new ArrayList<>(Arrays.asList(whileStmtBody)),
                            whileStmtBodyClone,
                            context
                    );
                } else {
                    // Add the statement to the current split test case body
                    currentBlockStmt.addStatement(statement);
                    // continue;
                }
            } catch (DiscardedTestCaseException e) {
                // The whole test case is discarded: propagate the exception to the normalization of the test class
                throw e;
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
     * The state of the normalization of a test case, shared by the recursive calls of
     * {@link #parseNormalizeTestStatementsBlock}.
     *
     * @param testClass the test class where the test case is defined
     * @param auxiliaryMethods the methods of the test class that can be inlined in the test case, by signature
     * @param integratedAuxiliaryMethods the auxiliary methods inlined in the test case
     * @param existingVariables the variables of the test class and of the test case, compared by reference
     * @param fieldNames the names of the fields of the test class
     * @param variableNames the names of the variables in use within the test case
     * @param labelNames the labels in use within the test case
     * @param inliningStack the signatures of the auxiliary methods being inlined (a recursive call is not inlined)
     * @param junitVersion the version of JUnit used in the test class
     * @param config the configuration options for the dataset to generate
     */
    private record NormalizationContext(
            TypeDeclaration<?> testClass,
            HashMap<String, MethodDeclaration> auxiliaryMethods,
            List<MethodDeclaration> integratedAuxiliaryMethods,
            Set<VariableDeclarator> existingVariables,
            Set<String> fieldNames,
            NameAllocator variableNames,
            NameAllocator labelNames,
            Deque<String> inliningStack,
            JUnitVersion junitVersion,
            OraclesDatasetConfig config
    ) {}

    /**
     * The result of the inlining of a call to an auxiliary method.
     *
     * @param inlined whether the call was inlined
     * @param thrownedException whether an error occurred while normalizing the inlined statements
     */
    private record InliningResult(boolean inlined, boolean thrownedException) {
        static final InliningResult NOT_INLINED = new InliningResult(false, false);
    }

    /**
     * Returns the names in use within a test case: the parameters and the variables declared within it (at any
     * depth) are declared, the fields of the test class and the other names it references are reserved.
     *
     * @param testCase the test case
     * @param fieldNames the names of the fields of the test class
     * @return the names in use within the test case
     */
    private static NameAllocator variableNamesOf(MethodDeclaration testCase, Set<String> fieldNames) {
        NameAllocator variableNames = new NameAllocator();
        fieldNames.forEach(variableNames::reserve);
        testCase.findAll(Parameter.class).forEach(parameter -> variableNames.declare(parameter.getNameAsString()));
        testCase.findAll(VariableDeclarator.class).forEach(variable -> variableNames.declare(variable.getNameAsString()));
        testCase.findAll(NameExpr.class).forEach(nameExpr -> variableNames.reserve(nameExpr.getNameAsString()));
        return variableNames;
    }

    /**
     * Returns the labels in use within a test case.
     *
     * @param testCase the test case
     * @return the labels declared within the test case
     */
    private static NameAllocator labelNamesOf(MethodDeclaration testCase) {
        NameAllocator labelNames = new NameAllocator();
        testCase.findAll(LabeledStmt.class).forEach(labeledStmt -> labelNames.declare(labeledStmt.getLabel().asString()));
        return labelNames;
    }

    /**
     * Inlines a call to an auxiliary method, invoked as a statement of the test case, within the current block of
     * statements ({@link AuxiliaryMethodInliner}). The inlined statements are normalized like the statements of the
     * test case: the calls to other auxiliary methods are inlined as well (a recursive call is kept as it is), and the
     * try statements are converted according to the configuration. If the auxiliary method contains a pattern the
     * dataset does not support (an unsupported try statement), the call is not inlined: the test case is not discarded
     * for a pattern that is not written within it.
     *
     * @param call the call to the auxiliary method
     * @param currentBlockStmt the reference block of statements where the call is normalized
     * @param context the state of the normalization of the test case
     * @return the result of the inlining
     */
    private static InliningResult inlineAuxiliaryMethod(MethodCallExpr call, BlockStmt currentBlockStmt, NormalizationContext context) {
        Optional<MethodDeclaration> auxiliaryMethod = AuxiliaryMethodInliner.findAuxiliaryMethod(call, context.testClass(), context.auxiliaryMethods());
        if (auxiliaryMethod.isEmpty()) {
            return InliningResult.NOT_INLINED;
        }
        String signature = auxiliaryMethod.get().getSignature().asString();
        if (context.inliningStack().contains(signature)) {
            logger.info("Recursive call to the auxiliary method {} not inlined.", signature);
            return InliningResult.NOT_INLINED;
        }
        Optional<AuxiliaryMethodInliner.InlinedCall> inlinedCall = AuxiliaryMethodInliner.inline(
                call, auxiliaryMethod.get(), context.testClass(), context.variableNames(), context.labelNames(), context.existingVariables());
        if (inlinedCall.isEmpty()) {
            return InliningResult.NOT_INLINED;
        }
        // The inlined statements are normalized within a separate block, added to the current block only if the
        // normalization succeeds
        BlockStmt inlinedBlock = new BlockStmt();
        inlinedCall.get().bindings().forEach(inlinedBlock::addStatement);
        BlockStmt inlinedBody = inlinedBlock;
        if (inlinedCall.get().label().isPresent()) {
            inlinedBody = new BlockStmt();
            inlinedBlock.addStatement(new LabeledStmt(inlinedCall.get().label().get(), inlinedBody));
        }
        boolean thrownedException;
        context.inliningStack().push(signature);
        try {
            thrownedException = parseNormalizeTestStatementsBlock(inlinedCall.get().body().getStatements(), inlinedBody, context);
        } catch (DiscardedTestCaseException e) {
            logger.warn("Auxiliary method {} not inlined: {}", signature, e.getMessage());
            return InliningResult.NOT_INLINED;
        } finally {
            context.inliningStack().pop();
        }
        new ArrayList<>(inlinedBlock.getStatements()).forEach(currentBlockStmt::addStatement);
        context.integratedAuxiliaryMethods().add(auxiliaryMethod.get());
        return new InliningResult(true, thrownedException);
    }

    /**
     * Renames the variables declared within a block of a try statement flattened into the enclosing block, if their
     * names are names of fields of the test class: within the enclosing block they would hide the fields to the
     * statements that follow. The variables of the test case have unique names
     * ({@link JavaParserUtils#normalizeVariableNames}), and the variables of the inlined auxiliary methods are named
     * after them ({@link AuxiliaryMethodInliner}), so only the fields can be hidden. The variables are added to the
     * existing variables.
     *
     * @param block the block to flatten
     * @param context the state of the normalization of the test case
     */
    private static void renameVariablesHidingFields(BlockStmt block, NormalizationContext context) {
        // The keys are compared by reference: distinct declarations with the same content are equal for JavaParser
        Map<VariableDeclarator, String> variablesToRename = new IdentityHashMap<>();
        for (VariableDeclarationExpr declaration : new VariableDeclarationExprVisitor().visit(block)) {
            for (VariableDeclarator variable : declaration.getVariables()) {
                if (context.fieldNames().contains(variable.getNameAsString())) {
                    variablesToRename.put(variable, context.variableNames().allocate(variable.getNameAsString()));
                }
                context.existingVariables().add(variable);
            }
        }
        JavaParserUtils.replaceVariables(block, variablesToRename);
    }

    /**
     * Get the number of assertions in the list of statements.
     *
     * @param statements the list of statements
     * @return the number of assertions
     */
    static int getNumberOfAssertions(List<Statement> statements) {
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
     * Flatten a try statement into a block statement. If a catch clause is present, it is converted into an assertThrows
     * statement. The statements within the catch block are added to the flat try block. If a finally block is present,
     * the statements within it are added to the flat try block.
     *
     * Precondition: the shape of the try statement is supported ({@link TryStatementClassifier}): it has no catch
     * clauses, or it is an expected exception (a single catch clause, and a call followed by {@code fail()} at the end
     * of the try block).
     *
     * @param tryStmt the try statement to flatten
     * @param context the state of the normalization of the test case
     * @return try/catch/finally blocks as a flat block of statements
     */
    private static BlockStmt flatTryStmt(TryStmt tryStmt, NormalizationContext context) throws IllegalStateException {
        // Define the flat block of statements (initially empty)
        BlockStmt flatTryStmtBlock = new BlockStmt();
        // Collect all the statements to insert within the final flat block
        List<Statement> statements = new ArrayList<>();
        // Check if the try statement contains a catch clause, in which case collect the statements within it
        if (tryStmt.getCatchClauses().size() == 1) {
            int tryStmtsSize = tryStmt.getTryBlock().getStatements().size();
            // Convert catch clause into an assertThrows statement
            MethodCallExpr assertThrowsCall = generateAssertThrowsFromTryStmt(context.junitVersion(), tryStmt);
            // Add the resources of the try statement (if any) to the flat version of the block
            statements.addAll(flatResources(tryStmt, context));
            // Get the exception class expression and the lambda expression with the failing call from the
            // assertThrows (their position depends on the JUnit version and on the presence of a message)
            NodeList<Expression> assertThrowsArguments = assertThrowsCall.getArguments();
            ClassExpr exceptionClassExpr = assertThrowsArguments.stream()
                    .filter(Expression::isClassExpr)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No exception class found in " + assertThrowsCall))
                    .asClassExpr();
            Expression failingExpr = assertThrowsArguments.stream()
                    .filter(Expression::isLambdaExpr)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No lambda expression found in " + assertThrowsCall));
            // The variables used within the lambda expression must be (effectively) final: for each variable
            // used in the failing call, define a final copy before the call and use it within the lambda
            // expression. The variables are resolved on the original failing call, still attached to the
            // compilation unit, and renamed in its copy within the lambda expression (the two expressions have
            // the same structure, so their name expressions are found in the same order)
            List<NameExpr> originalNameExprs = TryStatementClassifier.getFailingExpression(tryStmt).orElseThrow().findAll(NameExpr.class);
            List<NameExpr> lambdaNameExprs = failingExpr.findAll(NameExpr.class);
            int finalVarDeclIdx = tryStmtsSize - 2;
            // The final copies already defined, for the variables used more than once in the failing call
            Map<VariableDeclarator, SimpleName> finalCopies = new IdentityHashMap<>();
            for (int i = 0; i < originalNameExprs.size(); i++) {
                NameExpr nameExpr = originalNameExprs.get(i);
                Optional<VariableDeclarator> targetVar = JavaParserUtils.getVarFromNameExpr(nameExpr, context.existingVariables());
                if  (targetVar.isEmpty()) {
                    logger.error("Variable from name expression " + nameExpr + " not found among the existing variables.");
                    continue;
                }
                if (finalCopies.containsKey(targetVar.get())) {
                    lambdaNameExprs.get(i).setName(finalCopies.get(targetVar.get()).clone());
                    continue;
                }
                VariableDeclarator exceptionVarDecl = new VariableDeclarator(
                        new ClassOrInterfaceType(null, targetVar.get().getType().asString()),
                        context.variableNames().allocate(nameExpr.getNameAsString() + "_final"),
                        nameExpr.clone()
                );
                VariableDeclarationExpr finalVarDeclExpr = new VariableDeclarationExpr(exceptionVarDecl);
                finalVarDeclExpr.addModifier(Modifier.Keyword.FINAL);
                context.existingVariables().add(exceptionVarDecl);
                Statement finalVarDeclStmt = new ExpressionStmt(finalVarDeclExpr);
                tryStmt.getTryBlock().getStatements().add(finalVarDeclIdx++, finalVarDeclStmt);
                lambdaNameExprs.get(i).setName(exceptionVarDecl.getName().clone());
                finalCopies.put(targetVar.get(), exceptionVarDecl.getName());
            }
            // Rename the local variables of the try block that would hide a field
            renameVariablesHidingFields(tryStmt.getTryBlock(), context);
            // Add the list of statements from the try block
            statements.addAll(tryStmt.getTryBlock().getStatements());
            // Remove the method now called in the assertThrows assertion
            statements.remove(statements.size() - 2);
            // Remove the fail() method call at the end of the try block
            statements.remove(statements.size() - 1);
            // Create a variable to save the exception thrown by the assertThrows (the catch statement
            // is always defined with a variable containing the exception, that can be used within the
            // catch block - for example to check the message of the error).
            Parameter catchExceptionParam = tryStmt.getCatchClauses().get(0).getParameter();
            BlockStmt catchBlockStmt = tryStmt.getCatchClauses().get(0).getBody();
            String newExceptionParamName = catchExceptionParam.getNameAsString();
            // If the variable of the exception would hide a field within the main block, rename it
            if (context.fieldNames().contains(newExceptionParamName)) {
                newExceptionParamName = context.variableNames().allocate(newExceptionParamName);
                Map<Parameter,String>  paramMapping = new IdentityHashMap<>();
                paramMapping.put(catchExceptionParam, newExceptionParamName);
                JavaParserUtils.replaceParameters(catchBlockStmt, paramMapping);
            }
            // Define a variable declarator to store the exception returned by the assertThrow
            // In the original catch clause, it could use it for example to check the correctness of the
            // message associated to the thrown exception
            VariableDeclarator exceptionVarDecl = new VariableDeclarator(
                    new ClassOrInterfaceType(null, exceptionClassExpr.getTypeAsString()),
                    newExceptionParamName,
                    assertThrowsCall
            );
            VariableDeclarationExpr exceptionVarDeclExpr = new VariableDeclarationExpr(exceptionVarDecl);
            // Create an expression statement for the assertThrows call
            Statement assertStmt = new ExpressionStmt(exceptionVarDeclExpr);
            statements.add(assertStmt);
            // Add the generated variable to the collection of existing variables
            context.existingVariables().add(exceptionVarDecl);
            // Rename the local variables of the catch block that would hide a field
            renameVariablesHidingFields(catchBlockStmt, context);
            // Add the statements from the catch block to the flat main block
            statements.addAll(catchBlockStmt.getStatements());
        } else if (tryStmt.getCatchClauses().isEmpty()) {
            // A try statement without catch clauses (try-with-resources or try/finally) is flattened as it is: the
            // resources, then the statements of the try block
            statements.addAll(flatResources(tryStmt, context));
            // Rename the local variables of the try block that would hide a field
            renameVariablesHidingFields(tryStmt.getTryBlock(), context);
            statements.addAll(tryStmt.getTryBlock().getStatements());
        }
        // Check if the try statement contains a finally block, in which case collect the statements within it
        Optional<BlockStmt> finallyBlockStmt = tryStmt.getFinallyBlock();
        if (finallyBlockStmt.isPresent()) {
            // Rename the local variables of the finally block that would hide a field
            renameVariablesHidingFields(finallyBlockStmt.get(), context);
            // Add the statements from the finally block to the main block
            statements.addAll(finallyBlockStmt.get().getStatements());
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
     * Collects the resources of a try statement as statements of the flat version of the block. The variables declared
     * as resources are renamed if they would hide a field within the main block (together with their usages within
     * the try block). A resource that refers to a variable already declared (for example {@code try (in) {...}})
     * does not declare anything and is not collected.
     *
     * @param tryStmt the try statement to flatten
     * @param context the state of the normalization of the test case
     * @return the list of statements declaring the resources
     */
    private static List<Statement> flatResources(TryStmt tryStmt, NormalizationContext context) {
        List<Statement> statements = new ArrayList<>();
        for (Expression expr: tryStmt.getResources()) {
            if (expr.isVariableDeclarationExpr()) {
                Map<VariableDeclarator,String> resourcesVarMapping = new IdentityHashMap<>();
                for (VariableDeclarator var : expr.asVariableDeclarationExpr().getVariables()) {
                    if (context.fieldNames().contains(var.getNameAsString())) {
                        resourcesVarMapping.put(var, context.variableNames().allocate(var.getNameAsString()));
                    }
                    context.existingVariables().add(var);
                }
                // Replace any occurrence of the old name with the new one within the old try block
                JavaParserUtils.replaceVariables(tryStmt.getTryBlock(), resourcesVarMapping);
                // Add the resource to the flat version of the block
                statements.add(new ExpressionStmt(expr));
            }
        }
        return statements;
    }

    /**
     * Generate an assertThrows method call expression from a try statement. The assertThrows is created using the exception
     * class from the catch clause and the last method call in the try block preceding the fail() method. If the fail() method
     * contains a message, it is set as the message of the assertThrows statement.
     *
     * Precondition: the try statement is an expected exception ({@link TryStatementShape#EXPECTED_EXCEPTION}).
     *
     * @param junitVersion the version of JUnit used in the test class
     * @param tryStmt the try statement to generate the assertThrows method call expression from
     * @return the assertThrows method call expression
     */
    private static MethodCallExpr generateAssertThrowsFromTryStmt(JUnitVersion junitVersion, TryStmt tryStmt) {
        // Get catch clause
        CatchClause catchClause = tryStmt.getCatchClauses().get(0);
        // Get the exception class from the catch clause
        com.github.javaparser.ast.type.Type exceptionClass = catchClause.getParameter().getType();
        // The last call before the `fail()`, that must throw the exception (if the expected exception is not thrown,
        // the `fail()` makes the test fail)
        Expression lastMethodCall = TryStatementClassifier.getFailingExpression(tryStmt)
                .orElseThrow(() -> new IllegalStateException("No failing call found before fail() in " + tryStmt.getTryBlock()));
        // The `fail()` method call, the last statement of the try block
        List<Statement> statements = tryStmt.getTryBlock().getStatements();
        MethodCallExpr failMethodCall = statements.get(statements.size() - 1).asExpressionStmt().getExpression().asMethodCallExpr();
        String failMessage = "";
        // Check if the fail method has a comment. If so, use it as the fail message in the new `assertThrow` statement
        if (failMethodCall.getArguments().size() > 0) {
            if (failMethodCall.getArguments().get(0).isStringLiteralExpr()) {
                // The replaceAll method is used to escape double quotes in the fail message (not escaped by the asString() method)
                failMessage = failMethodCall.getArguments().get(0).asStringLiteralExpr().asString().replaceAll("\"", "\\\\\"");
            }
        }
        // The lambda expression contains a copy of the failing call: the original call stays in the try block,
        // attached to the compilation unit, so that its symbols can still be resolved
        LambdaExpr lambdaExpr = new LambdaExpr(new NodeList<>(), new ExpressionStmt(lastMethodCall.clone()), true);
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
        return assertThrowsCall;
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
