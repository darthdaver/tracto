package star.tracto.preprocessing.utils;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.Statement;
import org.javatuples.Triplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.SplitStrategyType;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.preprocessing.components.*;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class TokensUtils {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TokensUtils.class);

    /**
     * Processes the test prefix and the compilation unit of the test class and the focal class,
     * and generates a list of token steps from the target assertion
     *
     * @param repoRootPath the path to the repository root
     */
    public static List<TokenStep> generateTokenSteps(
            OraclesDatasetConfig config,
            Path repoRootPath,
            TestClazzDatapoints<OracleDatapoint> testClazzDatapoints
    ) {
        // Define the set of literal tokens to be replaced with "LITERAL"
        Set<String> literalsSet = new HashSet<>(Arrays.asList(
                "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL",
                "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "BOOL_LITERAL",
                "CHAR_LITERAL", "STRING_LITERAL"
        ));
        // Retrieve the datapoints and the path of the test class and the focal class from the test class object
        List<OracleDatapoint> datapoints = testClazzDatapoints.datapoints();
        Path testFilePath = repoRootPath.resolve(testClazzDatapoints.testClass().filePath().substring(1));
        Path focalFilePath = repoRootPath.resolve(testClazzDatapoints.focalClass().filePath().substring(1));
        String fqnFocalClass = testClazzDatapoints.focalClass().packageIdentifier() + "." + testClazzDatapoints.focalClass().identifier();
        // Get the path of the source directory of the repository
        Path repoSourcePath = Path.of(focalFilePath.toString().replace(FilesUtils.getFQNPath(fqnFocalClass).toString(), ""));

        try {
            // Get the compilation units for the test class and the focal class
            CompilationUnit tcCu = JavaParserUtils.getCompilationUnit(testFilePath);
            CompilationUnit fcCu = JavaParserUtils.getCompilationUnit(focalFilePath);
            // Check that the compilation units are not null
            if (tcCu == null || fcCu == null) {
                logger.error("Compilation unit is null for test class: " + testFilePath + " or focal class: " + focalFilePath);
                return new ArrayList<>();
            }
            TypeDeclaration testClass = tcCu.getPrimaryType().get();
            TypeDeclaration focalClass = fcCu.getPrimaryType().get();
            // Get the methods from the test class (test cases and auxiliary methods defined in the test class)
            List<MethodDeclaration> testCases = testClass.getMethods();
            // Iterate over the oracle datapoints and process each test prefix and associated target assertion
            for (OracleDatapoint datapoint : datapoints) {
                // Get the test prefix from the datapoint
                Callable testPrefix = datapoint.testPrefix();
                // Iterate over the test methods to find the one matching the test prefix identifier
                for (MethodDeclaration testCase : testCases) {
                    if (testCase.getNameAsString().equals(testPrefix.identifier())) {
                        // Get the target assertion from the test prefix
                        String targetAssertion = datapoint.target();
                        List<TokenStep> tokenSteps = new ArrayList<>();
                        Statement tgtStatement;
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

                        targetAssertion = targetAssertion.replace(" ", "");
                        List<String> tokens = Antlr4Utils.tokenizeSnippet(targetAssertion);


                        HashMap<String, VariableDeclarator> testClassVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), testClass.getFields());
                        JavaParserUtils.collectExistingVariables(testClassVariablesMap, testCase.getBody().get());
                        HashMap<String, TypeDeclaration> testClassImportedClassesMap = JavaParserUtils.collectImportedClasses(new HashMap<>(), repoSourcePath, tcCu);
                        JavaParserUtils.collectClassesInSamePackage(testClassImportedClassesMap, repoSourcePath, tcCu);
                        HashMap<String, TypeDeclaration> focalClassImportedClassesMap = JavaParserUtils.collectImportedClasses(new HashMap<>(), repoSourcePath, fcCu);
                        for (String token : tokens) {

                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }


        return null;
    }
}
