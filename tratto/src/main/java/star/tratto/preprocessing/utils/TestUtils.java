package star.tratto.preprocessing.utils;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.*;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.data.*;
import star.tratto.preprocessing.builders.*;
import star.tratto.preprocessing.components.*;
import star.tratto.utils.FileUtils;
import star.tratto.utils.javaParser.JavaParserUtils;
import star.tratto.utils.javaParser.visitors.declarations.FieldDeclarationVisitor;
import star.tratto.utils.javaParser.visitors.statements.AssertStmtVisitor;
import star.tratto.utils.javaParser.visitors.expressions.MethodCallExprVisitor;
import star.tratto.utils.javaParser.visitors.statements.helper.StmtVisitorHelper;

import javax.swing.text.html.Option;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
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

    /** The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

    /** Do not instantiate this class. */
    private TestUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Initialize a new split test case. It clones the original test method and renames it with
     * the index of the split test case. Returns the new cloned split test case.
     *
     * @param originalTestMethod the original test method
     * @param idx the index of the split test case
     * @return the new cloned split test case
     */
    private static MethodDeclaration initializeSplitTestCase(MethodDeclaration originalTestMethod, String testCaseName, int idx) {
        MethodDeclaration splitTestCase = originalTestMethod.clone();
        String delimiter = "_split_";
        int delimiterPosition = testCaseName.indexOf("_split_");
        if (delimiterPosition != -1) {
            String delimiterPlusIdx = testCaseName.substring(delimiterPosition, testCaseName.length());
            testCaseName = testCaseName.replace(delimiterPlusIdx, "");
        }
        splitTestCase.setName(testCaseName + delimiter + idx);
        return splitTestCase;
    }

    /**
     * Initialize a new split test case. It clones the original test method, renames it with
     * the index of the split test case, and initializes the body of the new test case.
     * Returns the new cloned split test case.
     *
     * @param originalTestMethod the original test method
     * @param idx the index of the split test case
     * @param body the body of the split test case
     * @return the new cloned split test case
     */
    private static MethodDeclaration initializeSplitTestCase(MethodDeclaration originalTestMethod, String testCaseName, int idx, BlockStmt body) {
        MethodDeclaration splitTestCase = TestUtils.initializeSplitTestCase(originalTestMethod, testCaseName, idx);
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
    public static void matchMethodsToTests(Path testFilePath, Path repoRootPath, Path outputDirPath, M2TMatchingType m2tMatchingType) {

    }

    /**
     * Split the test cases in the test class, at any occurrence of an assertion.
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
     * The method will return the following list of methods (in the form of {@link com.github.javaparser.ast.body.MethodDeclaration}):
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
     * @param repoRootPath the path to the root of the repository
     * @param testFilePath the path to the test class file
     * @param outputPath the path to the split test class file
     * @return the path to the split test class file
     */
    public static Path splitTest(Path repoRootPath, Path testFilePath, Path outputPath) {
        String testFileRelativePath = testFilePath.toString().replace(repoRootPath.getParent().toString(), "");
        // Regular output path for report analysis
        Path regularOutputPath = Paths.get(outputPath.toString(), "regular", testFileRelativePath.replace(".java", TrattoNamingConvention.TEST_SPLIT_FILE.getConventionName()));
        // Error output path for error analysis
        Path errorOutputPath = Paths.get(outputPath.toString(), "error", testFileRelativePath);
        // Test repository output path for further processing
        Path testRepoOutputPath = Paths.get(testFilePath.toString().replace(".java", TrattoNamingConvention.TEST_SPLIT_FILE.getConventionName()));
        // Create a list to store the split test cases generated from the original test cases of the given test class
        List<MethodDeclaration> splitTestCases = new ArrayList<>();
        try {
            // Parse the test class
            CompilationUnit cu = JavaParserUtils.getCompilationUnit(testFilePath);

            // Get the list of all the methods defined within the test class
            List<MethodDeclaration> testClassMethods = cu.findAll(MethodDeclaration.class);
            // Distribute the methods of the class according to their meaning
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> testClassMethodsDistribution = distributeMethods(testClassMethods);
            // Get the list of all the original test cases within the given test class
            List<MethodDeclaration> originalTestCases = testClassMethodsDistribution.getValue0();
            // Get the list of all the setup and tear down methods within the given test class
            HashMap<String, MethodDeclaration> setUpTearDownMethods = testClassMethodsDistribution.getValue1();
            // Get the list of all the auxiliary methods within the given test class
            HashMap<String, MethodDeclaration> auxiliaryMethods = testClassMethodsDistribution.getValue2();

            // Iterate over the original test cases
            for (MethodDeclaration originalTestCase : originalTestCases) {
                // Get the list of statements within the original test case
                List<Statement> originalTestCaseStatements = originalTestCase.getBody().orElseThrow().getStatements();
                // Parse the statements block and split the test case into multiple test cases
                try {
                    // Generate a mold of the test case and set the body to an empty block
                    MethodDeclaration testCaseMold = originalTestCase.clone();
                    testCaseMold.setBody(new BlockStmt());
                    // Parse the statements of the original test case and split it into multiple test cases
                    Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                            originalTestCaseStatements,
                            testCaseMold,
                            0,
                            auxiliaryMethods,
                            BlockStatementsType.METHOD_BODY,
                            0
                    );
                    // Add the split test cases generated from the original test case to the list
                    splitTestCases.addAll(result.getValue1());
                } catch (IllegalStateException e) {
                    // Log the error occurred while parsing the statements of the test case
                    logger.error(e.getMessage());
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
                        errorTestClass = errorCu.getPrimaryType().get();
                    }
                    // Get the list of methods already added in the error test class (if any)
                    List<MethodDeclaration> errorTestClassMethods = errorTestClass.getMethods();
                    // Check if the error test class already contains the original test case
                    if (!errorTestClassMethods.stream().anyMatch(m -> m.getNameAsString().equals(originalTestCase.getNameAsString()))) {
                        // Add the original test case to the error test class
                        errorTestClass.addMember(originalTestCase);
                        // Save the error test class to the error output path
                        FileUtils.writeJavaFile(errorOutputPath, errorCu);
                    }
                }
            }
            // Set the methods of the test case to the split test methods
            TypeDeclaration testClass = cu.getPrimaryType().get();
            testClass.setName(testClass.getNameAsString() + TrattoNamingConvention.TEST_SPLIT_CLASS.getConventionName());
            for (MethodDeclaration originalTestCase : originalTestCases) {
                testClass.remove(originalTestCase);
            }
            for (MethodDeclaration splitTestCase : splitTestCases) {
                testClass.addMember(splitTestCase);
            }
            // Save the split test class to the output paths (regular and test repository)
            FileUtils.writeJavaFile(regularOutputPath, cu);
            FileUtils.writeJavaFile(testRepoOutputPath, cu);
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }
        return testRepoOutputPath;
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
     * @param statements the list of statements to parse
     * @param testCaseMold the mold of the test case
     * @param startIdx the index of the split test cases
     * @param auxiliaryMethods the auxiliary methods of the test class
     * @param blockStatementsType the type of block statements
     * @param recursionLevel the level of recursion of the current block of statements analyzed
     * @return the pair of the last split test case generated and the list of split test cases
     * @throws IllegalStateException if an error occurs while parsing the statements of the block
     */
    private static Pair<MethodDeclaration, List<MethodDeclaration>> parseStatementsBlock(
            List<Statement> statements,
            MethodDeclaration testCaseMold,
            int startIdx,
            HashMap<String, MethodDeclaration> auxiliaryMethods,
            BlockStatementsType blockStatementsType,
            int recursionLevel
    ) throws IllegalStateException {
        // Create a list to store the split test cases generated from the given block of statements of the original test case
        List<MethodDeclaration> splitTestCases = new ArrayList<>();
        // Get the number of assertions in the test case
        int numberOfAssertions = getNumberOfAssertions(statements);
        // Initialize the index of the split test cases (incremental index to name the split test cases)
        int idx = startIdx;
        // Get the name prefix of the original test case
        String testCasePrefixName = testCaseMold.getNameAsString();
        // Initialize the first split test case (clone the test and assign the name to the split test case)
        MethodDeclaration splitTestCase = initializeSplitTestCase(testCaseMold, testCasePrefixName, idx);
        // Get the body of the initialized split test case
        BlockStmt splitTestCaseBody = splitTestCase.getBody().get();
        // Iterate over the statements of the original test case
        for (Statement statement : statements) {
            // Create flag to check if the statement is an assertion
            boolean isAssertion = false;
            // Check if the statement is a potential assertion
            if (statement.isAssertStmt()) {
                // Add the statement to the current split test case body
                addStatement(statement, splitTestCaseBody, blockStatementsType);
                // Flag the statement as an assertion
                isAssertion = true;
            } else if (statement.isBlockStmt()) {
                // Parse the statements of the do body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        statement.asBlockStmt().getStatements(),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        blockStatementsType,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the do statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the do statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else if (statement.isDoStmt()) {
                // Parse the do statement and split the test case into multiple test cases
                Statement doStmtBody = statement.asDoStmt().getBody();
                // Clone the do statement and set the body to an empty block
                DoStmt doStmtClone = statement.asDoStmt().clone();
                doStmtClone.setBody(new BlockStmt());
                // Add the cloned do statement to the current split test case body
                addStatement(doStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the do body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(Arrays.asList(doStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.DO,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the do statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the do statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else if (statement.isExpressionStmt()) {
                // Get the expression from the statement
                Expression currentExpr = statement.asExpressionStmt().getExpression();
                // Check if the expression is an assertion
                if (currentExpr.isMethodCallExpr() && JUnitAssertionType.isJUnitAssertion(currentExpr.asMethodCallExpr().getNameAsString())) {
                    // Add the statement to the flat body
                    addStatement(statement, splitTestCaseBody, blockStatementsType);
                    // Flag the statement as an assertion
                    isAssertion = true;
                } else {
                    if (currentExpr.isMethodCallExpr()) {
                        // Get the method call expression
                        MethodCallExpr methodCallExpr = currentExpr.asMethodCallExpr();
                        // Get the name of the method
                        String methodName = methodCallExpr.getNameAsString();
                        // Check if the method is an auxiliary method
                        if (auxiliaryMethods.containsKey(methodName)) {
                            if (auxiliaryMethods.get(methodName).getBody().isPresent()) {
                                // Parse the statements of the do body and split it into multiple test cases
                                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                                        auxiliaryMethods.get(methodName).getBody().get().getStatements(),
                                        splitTestCase,
                                        idx,
                                        auxiliaryMethods,
                                        blockStatementsType,
                                        recursionLevel + 1
                                );
                                // Update the split test case with the last generated from the do statement
                                splitTestCase = result.getValue0();
                                // Update the split test case body
                                splitTestCaseBody = splitTestCase.getBody().get();
                                // Add the split test cases generated from the do statement to the list
                                splitTestCases.addAll(result.getValue1());
                                // Update idx of the split tests
                                idx = splitTestCases.size();
                            }
                        } else {
                            // Add the statement to the current split test case body
                            addStatement(statement, splitTestCaseBody, blockStatementsType);
                            continue;
                        }
                    } else {
                        // Add the statement to the current split test case body
                        addStatement(statement, splitTestCaseBody, blockStatementsType);
                        continue;
                    }
                }
            } else if (statement.isForEachStmt()) {
                // Parse the for each statement and split the test case into multiple test cases
                Statement forEachStmtBody = statement.asForEachStmt().getBody();
                // Clone the for each statement and set the body to an empty block
                ForEachStmt forEachStmtClone = statement.asForEachStmt().clone();
                forEachStmtClone.setBody(new BlockStmt());
                // Add the cloned for each statement to the current split test case body
                addStatement(forEachStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the for each body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(Arrays.asList(forEachStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.FOR_EACH,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the for each statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the for each statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else if (statement.isForStmt()) {
                // Parse the for statement and split the test case into multiple test cases
                Statement forStmtBody = statement.asForStmt().getBody();
                // Clone the for statement and set the body to an empty block
                ForStmt forStmtClone = statement.asForStmt().clone();
                forStmtClone.setBody(new BlockStmt());
                // Add the cloned for statement to the current split test case body
                addStatement(forStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the for body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(Arrays.asList(forStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.FOR,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the for statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the for statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
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
                    Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                            new ArrayList<>(Arrays.asList(ifStmtBody)),
                            splitTestCase,
                            idx,
                            auxiliaryMethods,
                            rootIf ? BlockStatementsType.IF : BlockStatementsType.ELSE,
                            recursionLevel + 1
                    );
                    // Update the split test case with the last generated from the if statement
                    splitTestCase = result.getValue0();
                    // Update the split test case body
                    splitTestCaseBody = splitTestCase.getBody().get();
                    // Add the split test cases generated from the if statement to the list
                    splitTestCases.addAll(result.getValue1());
                    // Update idx of the split tests
                    idx = splitTestCases.size();
                    // Update the if statement clone
                    ifStmtClone = getLastStatementTypeOccurrence(splitTestCaseBody, IfStmt.class).asIfStmt();
                }
            } else if (statement.isSynchronizedStmt()) {
                // Parse the synchronized statement and split the test case into multiple test cases
                Statement synchronizedStmtBody = statement.asSynchronizedStmt().getBody();
                // Clone the synchronized statement and set the body to an empty block
                SynchronizedStmt synchronizedStmtClone = statement.asSynchronizedStmt().clone();
                synchronizedStmtClone.setBody(new BlockStmt());
                // Add the cloned synchronized statement to the current split test case body
                addStatement(synchronizedStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the synchronized body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(Arrays.asList(synchronizedStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.METHOD_BODY,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the synchronized statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the synchronized statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else if (statement.isSwitchStmt()) {
                // Parse the switch statement and split the test case into multiple test cases
                SwitchStmt switchStmt = statement.asSwitchStmt();
                for (SwitchEntry switchEntry : switchStmt.getEntries()) {
                    // Clone the switch statement and set the body to an empty block
                    SwitchStmt switchStmtClone = statement.asSwitchStmt().clone();
                    switchStmtClone.setEntries(new NodeList<>());
                    // Clone the current switch entry and set the statements to an empty block
                    SwitchEntry switchEntryClone = switchEntry.clone();
                    switchEntryClone.setStatements(new NodeList<>());
                    // Add the cloned switch entry to the cloned switch statement
                    switchStmtClone.getEntries().add(switchEntryClone);
                    // Add the cloned switch statement to the current split test case body
                    addStatement(switchStmtClone, splitTestCaseBody, blockStatementsType);
                    // Parse the statements of the switch entry and split it into multiple test cases
                    Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                            switchEntry.getStatements(),
                            splitTestCase,
                            idx,
                            auxiliaryMethods,
                            BlockStatementsType.SWITCH,
                            recursionLevel + 1
                    );
                    // Update the split test case with the last generated from the switch entry
                    splitTestCase = result.getValue0();
                    // Update the split test case body
                    splitTestCaseBody = splitTestCase.getBody().get();
                    // Add the split test cases generated from the switch entry to the list
                    splitTestCases.addAll(result.getValue1());
                    // Update idx of the split tests
                    idx = splitTestCases.size();
                }
            } else if (statement.isTryStmt()) {
                // Convert the try/catch statement into a plain assertion (assertThrows)
                // Get the try statement
                TryStmt tryStmt = statement.asTryStmt();
                // Check the try statement
                checkTryStmt(testCasePrefixName, tryStmt);
                // Check if the try statement contains a fail method call and, in that case, update
                // the number of assertions (a fail() assertion in the catch block does not split the test,
                // because it is removed in the current approach)
                if (tryStmt.getCatchClauses().size() > 0) {
                    Optional<BlockStatementsType> failAssertionPosition = findFailMethodCall(tryStmt);
                    if (failAssertionPosition.isPresent() && failAssertionPosition.get() == BlockStatementsType.CATCH) {
                        numberOfAssertions--;
                    }
                }
                // Flatten the try statement into a block statement
                BlockStmt flatTryStmt = flatTryStmt(tryStmt);
                // Parse the statements of the synchronized body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(flatTryStmt.getStatements()),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        blockStatementsType,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the synchronized statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the synchronized statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else if (statement.isWhileStmt()) {
                // Parse the while statement and split the test case into multiple test cases
                Statement whileStmtBody = statement.asWhileStmt().getBody();
                // Clone the while statement and set the body to an empty block
                WhileStmt whileStmtClone = statement.asWhileStmt().clone();
                whileStmtClone.setBody(new BlockStmt());
                // Add the cloned while statement to the current split test case body
                addStatement(whileStmtClone, splitTestCaseBody, blockStatementsType);
                // Parse the statements of the while body and split it into multiple test cases
                Pair<MethodDeclaration, List<MethodDeclaration>> result = parseStatementsBlock(
                        new ArrayList<>(Arrays.asList(whileStmtBody)),
                        splitTestCase,
                        idx,
                        auxiliaryMethods,
                        BlockStatementsType.WHILE,
                        recursionLevel + 1
                );
                // Update the split test case with the last generated from the while statement
                splitTestCase = result.getValue0();
                // Update the split test case body
                splitTestCaseBody = splitTestCase.getBody().get();
                // Add the split test cases generated from the while statement to the list
                splitTestCases.addAll(result.getValue1());
                // Update idx of the split tests
                idx = splitTestCases.size();
            } else {
                // Add the statement to the current split test case body
                addStatement(statement, splitTestCaseBody, blockStatementsType);
                continue;
            }
            if (isAssertion) {
                // Add the split test case to the list
                splitTestCases.add(splitTestCase);
                // Initialize a new split test case, starting from the body of the previous one
                splitTestCase = initializeSplitTestCase(splitTestCase, testCasePrefixName, ++idx);
                // Get the body of the new split test case
                splitTestCaseBody = splitTestCase.getBody().orElseThrow();
            }
        }
        // Check if the number of split test cases generated is equal to the number of assertions in the original test case
        if (numberOfAssertions != splitTestCases.size()) {
            throw new IllegalStateException("Unexpected number of split test cases generated from test case " + testCasePrefixName + ". Expected: " + getNumberOfAssertions(statements) + ", Found: " + splitTestCases.size());
        }
        // Check if the current split test case has more statements than the last split test case added to the list
        if (splitTestCases.size() > 0 && recursionLevel == 0) {
            MethodDeclaration lastAddedSplitTestCase = splitTestCases.get(splitTestCases.size() - 1);
            if(lastAddedSplitTestCase.getBody().get().getStatements().size() != splitTestCaseBody.getStatements().size()) {
                // Add the last split test case to the list
                splitTestCases.add(splitTestCase);
            }
        }
        // Return the list of split test cases generated
        return new Pair<>(splitTestCase, splitTestCases);
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
     * Add the statement to the split test case body, according to the type of block statement under analysis.
     * If the block statement is a method body, it refers to the main block of code and the statement is added
     * to the split test case body. Otherwise, the statement is added to the last block statement of the split
     * test case body (the one under analysis).
     *
     * @param statement
     * @param splitTestCaseBody
     * @param blockStatementsType
     */
    private static void addStatement(Statement statement, BlockStmt splitTestCaseBody, BlockStatementsType blockStatementsType) {
        if (blockStatementsType == BlockStatementsType.METHOD_BODY) {
            splitTestCaseBody.addStatement(statement);
        } else {
            Statement lastStatement = getLastStatementTypeOccurrence(splitTestCaseBody, blockStatementsType.getJavaParserStmtClass());
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
     * Visit all the statements within a block of statements to find the last occurrence of a given statement type.
     *
     * @param blockStmt the block of statements to visit
     * @param type the type of statement to find
     * @return the last occurrence of the statement type
     */
    private static Statement getLastStatementTypeOccurrence(BlockStmt blockStmt, Class type) {
        if (blockStmt.getStatements().isEmpty()) {
            throw new IllegalStateException("Block of statements is empty.");
        }
        Optional<Statement> lastOccurrence = StmtVisitorHelper.getLastStatementOccurrence(blockStmt, type);
        if (lastOccurrence.isPresent()) {
            return lastOccurrence.get();
        }
        throw new IllegalStateException("No statement of type " + type.getName() + " found in the block of statements.");
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
     * @param tryStmt the try statement to flatten
     * @return the flat try block
     */
    private static BlockStmt flatTryStmt(TryStmt tryStmt) {
        BlockStmt flatTryStmt = new BlockStmt();
        // Get the list of statements from the try block
        List<Statement> statements = new ArrayList<>(tryStmt.getTryBlock().getStatements());
        // Check if the try statement contains a catch clause, in which case collect the statements within it
        if (tryStmt.getCatchClauses().size() == 1) {
            // Convert catch clause into an assertThrows statement
            Optional<Statement> assertThrowsStmt = generateAssertThrowsFromTryStmt(tryStmt);
            if (assertThrowsStmt.isPresent()) {
                // Remove the fail() method call at the end of the try block
                statements.remove(statements.size() - 1);
                // Add the assertThrows statement to the flat try block
                statements.add(assertThrowsStmt.get());
                // Add the statements from the catch block to the flat try block
                statements.addAll(tryStmt.getCatchClauses().get(0).getBody().getStatements());
            }
        }
        // Check if the try statement contains a finally block, in which case collect the statements within it
        if (tryStmt.getFinallyBlock().isPresent()) {
            statements.addAll(tryStmt.getFinallyBlock().get().getStatements());
        }
        // Iterate over the statements in the try block
        for (Statement statement : statements) {
            // Add the statement to the flat try block
            flatTryStmt.addStatement(statement);
        }
        // Return the flat try block
        return flatTryStmt;
    }

    /**
     * Generate an assertThrows statement from a try statement. The assertThrows statement is created using the exception
     * class from the catch clause and the last method call in the try block. If the last method call is a fail() method
     * call, the message of the fail() method is used as the message of the assertThrows statement.
     *
     * @param tryStmt the try statement to generate the assertThrows statement from
     * @return the assertThrows statement, if the fail() method call is found in the try block. An empty optional otherwise.
     */
    private static Optional<Statement> generateAssertThrowsFromTryStmt(TryStmt tryStmt) {
        // Get catch clause
        CatchClause catchClause = tryStmt.getCatchClauses().get(0);
        // Get the exception class from the catch clause
        Type exceptionClass = catchClause.getParameter().getType();
        // Find the position of the fail method call in the try-catch block
        Optional<BlockStatementsType> failAssertionPosition = findFailMethodCall(tryStmt);

        if (failAssertionPosition.isPresent() && failAssertionPosition.get() == BlockStatementsType.TRY) {
            // Get the last method call in the try block it is considered the method that must throw the
            // exception. The last call is usually the fail() method (if the expected exception
            // is not thrown by the focal method it makes the test fail)
            int tryStmtSize = tryStmt.getTryBlock().getStatements().size();
            List<Statement> statements = tryStmt.getTryBlock().getStatements();
            Statement lastMethodCallStatement = statements.get(tryStmtSize - 2);
            Expression lastMethodCall = lastMethodCallStatement.asExpressionStmt().getExpression();
            Statement failMethodCallStatement = statements.get(tryStmtSize - 1);
            MethodCallExpr failMethodCall = failMethodCallStatement.asExpressionStmt().getExpression().asMethodCallExpr();
            String failMessage = "";
            // Check if the last method call (fail method) has a comment. If so, use it as the fail message
            if (failMethodCall.getArguments().size() > 0) {
                if (failMethodCall.getArguments().get(0).isStringLiteralExpr()) {
                    failMessage = failMethodCall.getArguments().get(0).asStringLiteralExpr().asString();
                }
            }
            // Create the assertThrows statement
            MethodCallExpr assertThrowsCall = new MethodCallExpr(JUnitAssertionType.ASSERT_THROWS.getAssertionMethodName());
            // Add the arguments to the assertThrows statement (exception class and last method call)
            NodeList<Expression> arguments = new NodeList<>();
            arguments.add(new ClassExpr(exceptionClass));
            arguments.add(lastMethodCall);
            if (!failMessage.isEmpty()) {
                arguments.add(new StringLiteralExpr(failMessage));
            }
            assertThrowsCall.setArguments(arguments);
            // Create an expression statement for the assertThrows call
            Statement assertStmt = new ExpressionStmt(assertThrowsCall);
            return Optional.of(assertStmt);
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
     * Process the test class of a given repository to generate all the oracles datapoints {@link OracleDatapoint}
     * from its test cases.
     *
     * @param repoRootPath the path to the repository to process
     * @param testFilePath the path to the test class to process
     * @param sourceFilePath the path to the corresponding source file, if available
     * @return the list of oracle datapoints generated from the test cases of the test class
     */
    public static List<OracleDatapoint> processSplitTestClass(Path repoRootPath, Path testFilePath, Path sourceFilePath) {
        // Create a list to store the oracle datapoints
        List<OracleDatapoint> oracleDatapoints = new ArrayList<>();
        // Process the test class and generate the corresponding oracles datapoints
        try {
            // Parse the test class and focal classes
            CompilationUnit cuTestClass = JavaParserUtils.getCompilationUnit(testFilePath);
            CompilationUnit cuFocalClass = JavaParserUtils.getCompilationUnit(sourceFilePath);
            // Get the primary type of the test and focal classes
            TypeDeclaration testClass = cuTestClass.getPrimaryType().orElseThrow(() -> new IllegalStateException("Primary type not found in test class: " + testFilePath));
            TypeDeclaration focalClass = cuFocalClass.getPrimaryType().orElseThrow(() -> new IllegalStateException("Primary type not found in focal class: " + sourceFilePath));
            //
            Path repoSourcePath = Path.of(sourceFilePath.toString().replace(FileUtils.getFQNPath((String) focalClass.getFullyQualifiedName().get()).toString(), ""));
            // Distribute the methods of the class according to their meaning (auxiliary, setup & tear down, test cases)
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> distributedMethods = distributeMethods(testClass.getMethods());
            // Set the list of all the test cases defined in the given test class
            List<MethodDeclaration> testCases = distributedMethods.getValue0();
            // Set the list of all the setup & tear down methods defined in the given test class
            HashMap<String, MethodDeclaration> setupTearDownMethods = distributedMethods.getValue1();
            // Set the list of all the auxiliary methods defined in the given test class
            HashMap<String, MethodDeclaration> auxiliaryMethods = distributedMethods.getValue2();
            // Process test class
            TestClassTratto testClassTratto = processTestClass(testClass, testFilePath, distributedMethods);
            // Process focal class
            ClassTratto focalClassTratto = processFocalClass(focalClass, sourceFilePath);
            // Process test cases
            for (MethodDeclaration testCase : testCases) {
                Pair<CallableTratto,List<CallableTratto>> invokedMethodsPair = processInvokedMethods(repoSourcePath, focalClass, testClass, testCase);
                CallableTratto focalMethodTratto = invokedMethodsPair.getValue0();
                List<CallableTratto> invokedMethods = invokedMethodsPair.getValue1();
                CallableTratto testCaseTratto = processCallable(testClass, testCase, invokedMethods);
                // TODO: Check id the last statement in the test case is an assertion or not
                String target = JavaParserUtils.getLastStatementInMethodDeclaration(testCase).toString();
                oracleDatapoints.add(new OracleDatapoint(testCaseTratto, focalMethodTratto, testClassTratto, focalClassTratto, target));
            }
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
        }
        return oracleDatapoints;
    }

    /**
     * Extract the information about the fields defined in the type declaration (class) passed as input.
     * @param typeDeclaration the type declaration (class) to analyze
     * @return the list of {@link FieldTratto}, providing information about each field defined in the compilation unit
     */
    private static List<FieldTratto> processFieldsFromTypeDeclaration(TypeDeclaration typeDeclaration) {
        List<FieldTratto> fields = new ArrayList<>();
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
                Type varType = variable.getType();
                TypeTratto type = processType(typeDeclaration, varType, 0);
                fieldBuilder.setType(type);
                fields.add(fieldBuilder.build());
                fieldBuilder.reset();
            }
        }
        return fields;
    }

    /**
     * Handle a type (that can be primitive, reference or void) and generate the corresponding {@link TypeTratto}.
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param type the type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the type
     */
    private static TypeTratto processType(TypeDeclaration typeDeclaration, Type type, int arrayLevel) {
        if (type.isPrimitiveType()) {
            return processPrimitiveType(type.asPrimitiveType(), arrayLevel);
        } else if (type.isArrayType()) {
            Type arrayType = type.asArrayType().getComponentType();
            return processType(typeDeclaration, arrayType, arrayLevel + 1);
        } else if (type.isTypeParameter()) {
            return processTypeParameter(typeDeclaration, type.asTypeParameter(), arrayLevel);
        } else if (type.isReferenceType()) {
            return processReferenceType(typeDeclaration, type.asReferenceType(), arrayLevel);
        } else if (type.isVoidType()) {
            return processVoidType(type.asVoidType());
        }
        throw new RuntimeException("Type not supported: " + type.toString());
    }

    /**
     * Handle a type (that can be primitive, reference or void) and generate the corresponding {@link TypeTratto}.
     * The method is invoked when a constructor or a method cannot be resolved with a callable declaration, for example
     * when it refers to a class of an external library, whose source code is not accessible (only the bytecode is available).
     * Therefore, only the {@link ResolvedType} is available.
     *
     * @param type the type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the type
     */
    private static TypeTratto processType(ResolvedType type, int arrayLevel) {
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
        throw new RuntimeException("Type not supported: " + type.toString());
    }

    /**
     * Process the primitive type and generate the corresponding {@link TypeTratto} object.
     *
     * @param primitiveType the primitive type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the primitive type
     */
    private static TypeTratto processPrimitiveType(PrimitiveType primitiveType, int arrayLevel) {
        // Instantiate a type builder to build a TypeTratto object from the primitive type
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
     * Process the primitive type and generate the corresponding {@link TypeTratto} object. The method
     * is invoked when the constructor or a method cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * only the {@link ResolvedPrimitiveType} is available.
     *
     *
     * @param resolvedPrimitiveType the primitive type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the primitive type
     */
    private static TypeTratto processPrimitiveType(ResolvedPrimitiveType resolvedPrimitiveType, int arrayLevel) {
        // Instantiate a type builder to build a TypeTratto object from the primitive type
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
     * Process the reference type and generate the corresponding {@link TypeTratto} object.
     *
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param referenceType the reference type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the reference type
     */
    private static TypeTratto processReferenceType(TypeDeclaration typeDeclaration, ReferenceType referenceType, int arrayLevel) {
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
        } catch (UnsolvedSymbolException | IllegalStateException e) {
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
            // Initialize a clazz builder to build a ClazzTratto object from the resolved type
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
                    MethodTratto processedMethod = TestUtils.processCallable(methodDeclaration);
                } else {
                    // TODO: Analyze the method usage
                    throw new RuntimeException("Method declaration not found for method usage: " + method.getName());
                }
            }
        }*/
    }

    /**
     * Process the generic type and generate the corresponding {@link TypeTratto} object.
     *
     * @param typeDeclaration the type declaration (class) to support the analysis of a type, (if reference type)
     * @param typeParameter the generic type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the reference type
     */
    private static TypeTratto processTypeParameter(TypeDeclaration typeDeclaration, TypeParameter typeParameter, int arrayLevel) {
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
        } catch (UnsolvedSymbolException e) {
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
     * Process the reference type and generate the corresponding {@link TypeTratto} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedReferenceType} is available.
     *
     * @param referenceType the reference type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the reference type
     */
    private static TypeTratto processReferenceType(ResolvedReferenceType referenceType, int arrayLevel) {
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
        } catch (UnsolvedSymbolException e) {}
        typeBuilder.setFullyQualifiedIdentifiers(classNames);
        typeBuilder.setClazzes(new ArrayList<>());
        return typeBuilder.build();
    }

    /**
     * Process the generic type and generate the corresponding {@link TypeTratto} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedTypeVariable} is available.
     *
     * @param typeVariable the generic type to process
     * @param arrayLevel the array level of the type (0 if the type is not an array)
     * @return the {@link TypeTratto} object generated from the generic type
     */
    private static TypeTratto processTypeVariable(ResolvedTypeVariable typeVariable, int arrayLevel) {
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
     * Process the void type and generate the corresponding {@link TypeTratto} object. The method is invoked when a
     * constructor or a method cannot be resolved with a callable declaration, for example when it refers to a class of
     * an external library, whose source code is not accessible (only the bytecode is available). Therefore, only the
     * {@link ResolvedType} is available.
     *
     * @param voidType the void type to process
     * @return the {@link TypeTratto} object generated from the resolved void type
     */
    private static TypeTratto processVoidType(ResolvedType voidType) {
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
     * Process the void type and generate the corresponding {@link TypeTratto} object.
     *
     * @param voidType the void type to process
     * @return the {@link TypeTratto} object generated from the void type
     */
    private static TypeTratto processVoidType(VoidType voidType) {
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
     * {@link TestClassTratto} object.
     *
     * @param testClass the test class to process
     * @param testFilePath the path to the test class file
     * @param distributedMethods the methods of the test class distributed according to their meaning
     *                           (auxiliary, setup & tear down, test cases)
     * @return the {@link TestClassTratto} object generated from the test class
     */
    private static TestClassTratto processTestClass(
            TypeDeclaration testClass,
            Path testFilePath,
            Triplet<List<MethodDeclaration>, HashMap<String, MethodDeclaration>, HashMap<String, MethodDeclaration>> distributedMethods
    ) {
        TestClassBuilder testClassBuilder = new TestClassBuilder();
        // Set test class identifier (name)
        testClassBuilder.setIdentifier(testClass.getNameAsString());
        // Set test class package
        testClassBuilder.setPackageIdentifier(JavaParserUtils.getPackageNameFromTypeDeclaration(testClass).orElse(""));
        // Retrieve the superclasses extended and the interfaces implemented by the test class
        List<String> superClasses = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
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
        // Set test class superclasses and interfaces
        testClassBuilder.setSuperClasses(superClasses);
        testClassBuilder.setInterfaces(interfaces);
        // Set file path of the test class
        testClassBuilder.setFilePath(testFilePath.toString());
        // Set test class fields
        testClassBuilder.setFields(processFieldsFromTypeDeclaration(testClass));
        // Set the list of all the test cases defined in the given test class
        List<MethodDeclaration> testCases = distributedMethods.getValue0();
        // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
        //       version the invoked methods are set to null.
        testClassBuilder.setTestCases(testCases.stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Set the list of all the setup & tear down methods defined in the given test class
        HashMap<String, MethodDeclaration> setupTearDownMethods = distributedMethods.getValue1();
        testClassBuilder.setSetupTearDownMethods(setupTearDownMethods.values().stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Set the list of all the auxiliary methods defined in the given test class
        HashMap<String, MethodDeclaration> auxiliaryMethods = distributedMethods.getValue2();
        testClassBuilder.setAuxiliaryMethods(auxiliaryMethods.values().stream().map(m -> processCallable(testClass, m, null)).collect(Collectors.toList()));
        // Generate and return the test class
        return testClassBuilder.build();
    }

    /**
     * Process the focal class in the form of {@link TypeDeclaration} and generate the corresponding
     * {@link ClassTratto} object.
     *
     * @param focalClass the test class to process
     * @param sourceFilePath the path to the source file representing the focal class
     * @return the {@link TestClassTratto} object generated from the test class
     */
    private static ClassTratto processFocalClass(TypeDeclaration focalClass, Path sourceFilePath) {
        ClassBuilder focalClassBuilder = new ClassBuilder();
        // Set test class identifier (name)
        focalClassBuilder.setIdentifier(focalClass.getNameAsString());
        // Set test class package
        focalClassBuilder.setPackageIdentifier(JavaParserUtils.getPackageNameFromTypeDeclaration(focalClass).orElse(""));
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
        focalClassBuilder.setSuperClasses(superClasses);
        focalClassBuilder.setInterfaces(interfaces);
        // Set file path of the test class
        focalClassBuilder.setFilePath(sourceFilePath.toString());
        // Set test class fields
        focalClassBuilder.setFields(processFieldsFromTypeDeclaration(focalClass));
        // Set the list of all the constructors and methods defined in the given focal class
        List<ConstructorDeclaration> constructors = focalClass.getConstructors();
        // TODO: Manage invoked methods recursion in the improved version of the dataset. In the current
        //       version the invoked methods are set to null.
        focalClassBuilder.setConstructors(constructors.stream().map(m -> processCallable(focalClass, m, null)).collect(Collectors.toList()));
        List<MethodDeclaration> methods = focalClass.getMethods();
        focalClassBuilder.setMethods(methods.stream().map(m -> processCallable(focalClass, m, null)).collect(Collectors.toList()));
        return focalClassBuilder.build();
    }

    private static Pair<CallableTratto,List<CallableTratto>> processInvokedMethods(Path repoSourcePath, TypeDeclaration focalClass, TypeDeclaration testClass, MethodDeclaration testCase) {
        // Define list of methods invoked in the test case
        List<CallableTratto> invokedMethods = new ArrayList<>();
        // Define the focal method to find. Initially null.
        CallableTratto focalMethod = null;
        // Set identifier of the focal method
        String testName = testCase.getNameAsString();
        try {
            // Get the list of all the statements within the test
            NodeList<Statement> statements = testCase
                    .getBody()
                    .orElseThrow(() -> new IllegalStateException(String.format("Cannot retrieve block statements from test %s.", testName)))
                    .getStatements();
            // Throw an exception if no statements are found (empty test)
            if (statements.size() <= 0) {
                throw new IllegalStateException(String.format("No statements found in test %s.", testName));
            }
            // Get statement index
            int stmtIndex = statements.size() - 1;
            // Define an HashMap saving information about the variable progressively defined (and used) within the test case
            HashMap<String, TypeDeclaration> projectVariables = new HashMap<>();
            HashMap<String, ResolvedReferenceType> librariesVariables = new HashMap<>();

            for (FieldDeclaration testClassField : testClass.findAll(FieldDeclaration.class)) {
                for (VariableDeclarator variable : testClassField.getVariables()) {
                    String variableName = variable.getNameAsString();
                    Type variableType = variable.getType();
                    while (variableType.isArrayType()) {
                        variableType = variableType.asArrayType().getComponentType();
                    }
                    // TODO: Handle the case when the variable type is an array
                    if (variableType.isReferenceType()) {
                        try {
                            ResolvedReferenceType resolvedReferenceType = variableType.resolve().asReferenceType();
                            Path fqnVarType = FileUtils.getFQNPath(resolvedReferenceType.getQualifiedName());
                            Path classFilePath = repoSourcePath.resolve(fqnVarType);
                            CompilationUnit cuVarType = null;
                            if (Files.exists(classFilePath)) {
                                try {
                                    cuVarType = JavaParserUtils.getCompilationUnit(classFilePath);
                                } catch (IOException e) {
                                    logger.error("Error reading file: " + classFilePath);
                                }
                                projectVariables.put(variable.getNameAsString(), cuVarType.getPrimaryType().orElseThrow(() -> new NoPrimaryTypeException("[ERROR] - ClassFile from method usage, with no primary file.")));
                            } else {
                                librariesVariables.put(variable.getNameAsString(), variableType.resolve().asReferenceType());
                            }
                        } catch (UnsolvedSymbolException e) {
                            logger.error("Unable to resolve variable type " + variableType.toString() + " in test class " + testClass.getNameAsString());
                        }
                    }
                }
            }


            // Iterate over the statements of the test from the top to the bottom
            for (int i = 0; i <= stmtIndex; i++) {
                // Get current statement
                Statement stmt = statements.get(i);
                Expression expr = null;
                CallableExprType exprType = null;
                // A method call or constructor invocation must be an instance of ExpressionStmt
                if (!stmt.isExpressionStmt()) {
                    continue;
                }
                // Get the expression from the current statement
                Expression unparsedExpr = stmt.asExpressionStmt().getExpression();
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
                            // CallableDeclaration invokedMethod = JavaParserUtils.resolveCallExpression(testClass, expr, exprType);
                            CallableDeclaration invokedMethod = JavaParserUtils.resolveCallExpression(testClass, expr, exprType);
                            // If the focal method is not found, look at the methods and constructors in the focal class
                            invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                            // If the focal method is still not found, search it within the inherited methods of the focal class
                            if (invokedMethod == null) {
                                if (exprType == CallableExprType.CONSTRUCTOR) {
                                    String candidateClassPath = expr.asObjectCreationExpr().getType().resolve().asReferenceType().getQualifiedName();
                                    Path fullyQualifiedCandidateFilePath = repoSourcePath.resolve(FileUtils.getFQNPath(candidateClassPath));
                                    if (Files.exists(fullyQualifiedCandidateFilePath)) {
                                        invokedClass = JavaParserUtils.getCompilationUnit(repoSourcePath.resolve(fullyQualifiedCandidateFilePath)).getPrimaryType().get();
                                        invokedMethod = JavaParserUtils.resolveCallExpression(invokedClass, expr, exprType);
                                        if (invokedMethod == null) {
                                            throw new NoFocalMethodMatchingException("The constructor in the expression " + expr.toString() + " cannot be resolved in the given focal class " + invokedClass.getNameAsString());
                                        }
                                    }
                                } else {
                                    MethodUsage invokedMethodUsage = JavaParserUtils.searchCandidateMethodUsage(invokedClass, expr);
                                    // If the focal method is still not found, raise an exception
                                    if (!(invokedMethodUsage == null)) {
                                        Path fullyQualifiedClassNameLastExpressionPath = FileUtils.getFQNPath(invokedMethodUsage.declaringType().getQualifiedName());
                                        Path classFileInvokedMethodUsagePath = repoSourcePath.resolve(fullyQualifiedClassNameLastExpressionPath);
                                        if (Files.exists(classFileInvokedMethodUsagePath)) {
                                            try {
                                                CompilationUnit cuInvokedMethodUsage = JavaParserUtils.getCompilationUnit(classFileInvokedMethodUsagePath);
                                                invokedClass = cuInvokedMethodUsage.getPrimaryType().orElseThrow(() -> new NoPrimaryTypeException("[ERROR] - ClassFile from method usage, with no primary file."));
                                                List<MethodDeclaration> candidateMethods = invokedClass.getMethods();
                                                invokedMethod = JavaParserUtils.matchMethodUsageWithMethodDeclaration(invokedMethodUsage, candidateMethods);
                                            } catch (IOException e) {
                                                logger.error("Error reading file: " + classFileInvokedMethodUsagePath);
                                            }
                                        }
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
                            CandidateCallableDeclarationNotFoundException | CandidateCallableMethodUsageNotFoundException |
                            MultipleCandidatesException | UnsolvedSymbolException | IllegalStateException e
                    ) {
                        logger.error(String.format("Error processing expression %s of test case %s", stmt, testName));
                    }
                }
                if (unparsedExpr.isVariableDeclarationExpr() ) {
                    VariableDeclarationExpr varExpr = unparsedExpr.asVariableDeclarationExpr();
                    for (VariableDeclarator var : varExpr.getVariables()) {
                        Type varType = var.getType();
                        while (varType.isArrayType()) {
                            varType = varType.asArrayType().getComponentType();
                        }
                        // TODO: Handle the case when the variable type is an array
                        if (varType.isReferenceType()) {
                            try {
                                Path fqnReturnType = FileUtils.getFQNPath(varType.resolve().asReferenceType().getQualifiedName());
                                Path classFilePath = repoSourcePath.resolve(fqnReturnType);
                                CompilationUnit cuReturnType = null;
                                if (Files.exists(classFilePath)) {
                                    try {
                                        cuReturnType = JavaParserUtils.getCompilationUnit(classFilePath);
                                    } catch (IOException e) {
                                        logger.error("Error reading file: " + classFilePath);
                                    }
                                    projectVariables.put(var.getNameAsString(), cuReturnType.getPrimaryType().orElseThrow(() -> new NoPrimaryTypeException("[ERROR] - ClassFile from method usage, with no primary file.")));
                                } else {
                                    librariesVariables.put(var.getNameAsString(), varType.resolve().asReferenceType());
                                }
                            } catch (UnsolvedSymbolException e) {
                                logger.error("Unable to resolve variable type " + varType.toString() + " in test class " + testClass.getNameAsString());
                            }
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
            logger.error(errMsg);
        }
        return new Pair<>(focalMethod, invokedMethods);
    }

    /**
     * Process the class or interface type and generate the corresponding {@link ClassTratto} object.
     *
     * @param classOrInterfaceType the class or interface type to process
     * @return the {@link ClassTratto} object generated from the class or interface type
     */
    private static ClassTratto processClassOrInterface(ClassOrInterfaceType classOrInterfaceType) {
        ClassBuilder classBuilder = new ClassBuilder();
        Optional<ResolvedReferenceTypeDeclaration> resolvedReferenceTypeDeclaration = classOrInterfaceType.resolve().asReferenceType().getTypeDeclaration();

        classBuilder.setIdentifier(classOrInterfaceType.getNameAsString());

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
     * Process the method declaration and generate the corresponding {@link CallableTratto} object.
     *
     * @param typeDeclaration the type declaration (class) to which the method belongs
     * @param callableDeclaration the method or constructor declaration to process
     * @param invokedMethods the list of invoked methods within the method or constructor declaration. It can be null.
     * @return the {@link CallableTratto} object generated from the method declaration
     */
    private static CallableTratto processCallable(TypeDeclaration typeDeclaration, CallableDeclaration callableDeclaration, List<CallableTratto> invokedMethods) {
        // Instantiate a method builder to build a MethodTratto object from the method declaration
        CallableBuilder callableBuilder = new CallableBuilder();
        // Set the attributes of the method
        callableBuilder.setIdentifier(callableDeclaration.getNameAsString());
        callableBuilder.setAnnotations(JavaParserUtils.getAnnotationsAsStrings(callableDeclaration.getAnnotations()));
        callableBuilder.setModifiers(JavaParserUtils.getModifiersAsStrings(callableDeclaration.getModifiers()));
        callableBuilder.setSignature(JavaParserUtils.getCallableSignature(callableDeclaration));
        callableBuilder.setThrownExceptions(JavaParserUtils.getExceptionsAsString(callableDeclaration.getThrownExceptions()));
        callableBuilder.setJavadoc(JavaParserUtils.getCallableJavadoc(callableDeclaration));
        callableBuilder.setBody(JavaParserUtils.getCallableSourceCode(callableDeclaration));
        // TODO: Manage invoked methods recursion in the improved version of the dataset
        callableBuilder.setInvokedMethods(invokedMethods); // avoid recursion in the first version of the dataset
        if (callableDeclaration instanceof MethodDeclaration) {
            callableBuilder.setReturnType(processType(typeDeclaration, ((MethodDeclaration) callableDeclaration).getType(),0));
        } else {
            callableBuilder.setReturnType(null);
        }
        // Process the parameters of the method
        List<Pair<String,TypeTratto>> parameters = new ArrayList<>();
        for (Object obj : callableDeclaration.getParameters()) {
            Parameter parameter = (Parameter) obj;
            String parameterName = parameter.getNameAsString();
            TypeTratto parameterType = processType(typeDeclaration, parameter.getType(), 0);
            parameters.add(new Pair<>(parameterName, parameterType));
        }
        callableBuilder.setParameters(parameters);
        // Build the method and return it
        return callableBuilder.build();
    }

    /**
     * Process the object creation expression and generate the corresponding {@link CallableTratto} object. The method
     * is invoked when the constructor cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * some information are missing or less accurate.
     *
     * @param objCreationExpr the object creation expression to process
     * @return the {@link CallableTratto} object generated from the method declaration
     */
    private static CallableTratto processObjectCreationExpr(ObjectCreationExpr objCreationExpr) {
        ResolvedConstructorDeclaration resolvedConstructor = objCreationExpr.resolve();
        // Instantiate a method builder to build a MethodTratto object from the method declaration
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
        List<Pair<String,TypeTratto>> parameters = new ArrayList<>();
        for (int i = 0; i < resolvedConstructor.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration resolvedParam = resolvedConstructor.getParam(i);
            TypeTratto parameterType = processType(resolvedParam.getType(), 0);
            parameters.add(new Pair<>(null, parameterType));
        }
        callableBuilder.setParameters(parameters);
        // Build the method and return it
        return callableBuilder.build();
    }

    /**
     * Process the resolved method declaration and generate the corresponding {@link CallableTratto} object. The method
     * is invoked when the method call cannot be resolved with a callable declaration, for example when it refers to
     * a class of an external library, whose source code is not accessible (only the bytecode is available). Therefore,
     * some information are missing or less accurate.
     *
     * @param resolvedMethod the resolved method declaration to process
     * @return the {@link CallableTratto} object generated from the method declaration
     */
    private static CallableTratto processMethodCallExpr(ResolvedMethodDeclaration resolvedMethod) {
        // Instantiate a method builder to build a MethodTratto object from the method declaration
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
        List<Pair<String,TypeTratto>> parameters = new ArrayList<>();
        for (int i = 0; i < resolvedMethod.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration resolvedParam = resolvedMethod.getParam(i);
            TypeTratto parameterType = processType(resolvedParam.getType(), 0);
            parameters.add(new Pair<>(null, parameterType));
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
                        if (getNumberOfAssertions(method.getBody().get().getStatements()) > 0) {
                            testCases.add(method);
                        }
                    }
                } else if (methodAnnotations.stream().anyMatch(a -> a.getNameAsString().equals("Override"))) {
                    if (method.getBody().isPresent()) {
                        if (getNumberOfAssertions(method.getBody().get().getStatements()) > 0) {
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
