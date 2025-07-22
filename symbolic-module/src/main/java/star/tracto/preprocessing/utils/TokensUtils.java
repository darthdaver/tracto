package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.Statement;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.*;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.preprocessing.builders.PartialOracleDatapointBuilder;
import star.tracto.preprocessing.builders.TestClazzDatapointsBuilder;
import star.tracto.preprocessing.builders.TokenStepBuilder;
import star.tracto.preprocessing.components.*;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class TokensUtils {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TokensUtils.class);

    /**
     * Processes the test prefix and the compilation unit of the test class and the focal class,
     * and generates a list of token steps from the target assertion
     *
     * @param repoRootPath the path to the repository root
     */
    public static TestClazzDatapoints generateTokenSteps (
            OraclesDatasetConfig config,
            Path repoRootPath,
            TestClazzDatapoints<OracleDatapoint> testClazzDatapoints
    ) throws IllegalStateException {
        // Define test class datapoints builder
        TestClazzDatapointsBuilder<PartialOracleDatapoint> testClazzDatapointsBuilder = new TestClazzDatapointsBuilder<>();
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
                testClazzDatapointsBuilder.setDatapoints(new ArrayList<>());
                return testClazzDatapointsBuilder.build();
            }
            TypeDeclaration testClass = tcCu.getPrimaryType().get();
            TypeDeclaration focalClass = fcCu.getPrimaryType().get();
            // Get the methods from the test class (test cases and auxiliary methods defined in the test class)
            List<MethodDeclaration> testCases = testClass.getMethods();

            testClazzDatapointsBuilder.setTestClass(testClazzDatapoints.testClass());
            testClazzDatapointsBuilder.setFocalClass(testClazzDatapoints.focalClass());
            testClazzDatapointsBuilder.setJunitVersion(testClazzDatapoints.junitVersion());
            // Define list of token steps to return
            List<PartialOracleDatapoint> partialOraclesDatapoints = new ArrayList<>();
            // Iterate over the oracle datapoints and process each test prefix and associated target assertion
            for (OracleDatapoint datapoint : datapoints) {
                // Get the test prefix from the datapoint
                Callable testPrefix = datapoint.testPrefix();
                // Define partial oracle datapoints builder
                PartialOracleDatapointBuilder partialOracleDatapointBuilder = new PartialOracleDatapointBuilder();
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

                        // TC_FIELD
                        HashMap<String, VariableDeclarator> tcVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), testClass.getFields());
                        // TP_FIELD
                        HashMap<String, VariableDeclarator> tpVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), testCase.getBody().get());
                        // FC_FIELD
                        HashMap<String, VariableDeclarator> fcVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), focalClass.getFields());
                        // TC_IMPORTED_CLASS_NAME
                        HashMap<String, TypeDeclaration> tcImportedClassesMap = JavaParserUtils.collectImportedClasses(new HashMap<>(), repoSourcePath, tcCu);
                        JavaParserUtils.collectClassesInSamePackage(tcImportedClassesMap, repoSourcePath, tcCu);
                        // FC_IMPORTED_CLASS_NAME
                        HashMap<String, TypeDeclaration> fcImportedClassesMap = JavaParserUtils.collectImportedClasses(new HashMap<>(), repoSourcePath, fcCu);
                        JavaParserUtils.collectClassesInSamePackage(fcImportedClassesMap, repoSourcePath, fcCu);

                        // TODO: Check if it collects also inherited methods
                        HashMap<String, Callable> fcCallableMap = new HashMap<>();
                        //focalClass.getMethods().stream().forEach(m -> fcCallableMap.put());
                        // TC_METHOD
                        HashMap<String, Callable> tcCallableMap = new HashMap<>();
                        // FC_FIELD_METHOD
                        HashMap<String, Callable> fcFieldCallableMap = new HashMap<>();
                        // TC_FIELD_METHOD
                        HashMap<String, Callable> tcFieldCallableMap = new HashMap<>();
                        // TP_FIELD_METHOD
                        HashMap<String, Callable> tpFieldCallableMap = new HashMap<>();
                        // FC_IMPORTED_CLASS_FIELD_METHOD
                        HashMap<String, Callable> fcImportedClassFieldCallableMap = new HashMap<>();
                        // TC_IMPORTED_CLASS_FIELD_METHOD,
                        HashMap<String, Callable> tcImportedClassFieldCallableMap = new HashMap<>();

                        // Extracting the first token (assertion)
                        TokenStepBuilder tokenStepBuilder = new TokenStepBuilder();
                        tokenStepBuilder.setPartialSnippet("");
                        tokenStepBuilder.setNextPossibleGenericTokens(new ArrayList<>());
                        tokenStepBuilder.setNextPossibleProjectTokens(new ArrayList<>());
                        tokenStepBuilder.setNextPossibleJavaTokens(Arrays.stream(JUnitAssertionType.values()).map(a -> a.getAssertionMethodName()).collect(Collectors.toList()));
                        tokenStepBuilder.setSyntaxCorrect(false);
                        tokenStepBuilder.setTarget(tokens.get(0));
                        tokenSteps.add(tokenStepBuilder.build());

                        String partialSnippet = "";
                        for (int i = 0; i < tokens.size(); i++) {
                            String token = tokens.get(i);
                            if (token.equals(";")) {
                                break;
                            }
                            partialSnippet += token;
                            tokenStepBuilder = new TokenStepBuilder();
                            tokenStepBuilder.setPartialSnippet(partialSnippet);
                            List<String> nextANTLRTokens = Antlr4Utils.getNextPossibleTokens(partialSnippet);

                            if (nextANTLRTokens.isEmpty()) {
                                // raise exception
                                IllegalStateException exception = new IllegalStateException("The last token should be ; and not have next possible tokens: ");
                                throw exception;
                            }

                            // Adjust nextTokens by replacing literals and IDENTIFIER tokens
                            List<Pair<String, JavaTokenType>> adjustedNextJavaTokens = new ArrayList<>();
                            List<Pair<String, ConcreteTokenType>> adjustedNextConcreteTokens = new ArrayList<>();
                            List<Pair<String, GenericTokenType>> adjustedNextGenericTokens = new ArrayList<>(); //
                            Type operand1 = null;
                            Type operand2 = null;
                            boolean literalAdded = false;

                            for (String nextANTLRToken : nextANTLRTokens) {
                                // A token can be: a Java token, a literal, an identifier
                                if (literalsSet.contains(nextANTLRToken)) {
                                    // It's a literal: determine its type
                                    String literalType = Antlr4Utils.getLiteralType(nextANTLRToken);
                                    // Only add if compatible
                                    if (!literalAdded) {
                                        adjustedNextGenericTokens.add(new Pair("LITERAL", GenericTokenType.LITERAL));
                                        literalAdded = true;
                                    }

                                } else if (nextANTLRToken.equals("IDENTIFIER")) {
                                    // Replace "IDENTIFIER" with collected identifiers but filter by type
                                    // Check if the token is in testClassVariablesMap
                                    adjustedNextConcreteTokens.addAll(tcVariablesMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TC_FIELD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(fcVariablesMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.FC_FIELD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(tpVariablesMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TP_FIELD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(tcImportedClassesMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TC_IMPORTED_CLASS_NAME)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(fcImportedClassesMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.FC_IMPORTED_CLASS_NAME)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(fcCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.FC_METHOD)).collect(Collectors.toList()));

                                    // TODO: Add logic to collect callable methods from focal class, test class, and imported classes
                                    adjustedNextConcreteTokens.addAll(tcCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TC_METHOD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(fcFieldCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.FC_FIELD_METHOD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(tcFieldCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TC_FIELD_METHOD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(tpFieldCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TP_FIELD_METHOD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(fcImportedClassFieldCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.FC_IMPORTED_CLASS_FIELD_METHOD)).collect(Collectors.toList()));
                                    adjustedNextConcreteTokens.addAll(tcImportedClassFieldCallableMap.keySet().stream().map(k -> new Pair<>(k, ConcreteTokenType.TC_IMPORTED_CLASS_FIELD_METHOD)).collect(Collectors.toList()));

                                } else {
                                    // For non-literal, non-IDENTIFIER tokens (e.g., keywords),
                                    // we can filter them based on expectedType if needed.
                                    // For now, we add them and filter afterward.
                                    adjustedNextJavaTokens.add(new Pair(nextANTLRToken.replace("'", ""), JavaTokenType.JAVA_TOKEN));
                                }
                            }

                            tokenStepBuilder.setNextPossibleGenericTokens(adjustedNextGenericTokens.stream().map(t -> t.getValue0()).collect(Collectors.toList()));
                            tokenStepBuilder.setNextPossibleProjectTokens(adjustedNextConcreteTokens.stream().map(t -> t.getValue0()).collect(Collectors.toList()));
                            tokenStepBuilder.setNextPossibleJavaTokens(adjustedNextJavaTokens.stream().map(t -> t.getValue0()).collect(Collectors.toList()));
                            tokenStepBuilder.setSyntaxCorrect(false);
                            tokenStepBuilder.setTarget(tokens.get(i + 1));
                            tokenSteps.add(tokenStepBuilder.build());
                        }
                        partialOracleDatapointBuilder.setTestPrefix(testPrefix);
                        partialOracleDatapointBuilder.setTarget(targetAssertion);
                        partialOracleDatapointBuilder.setDatapoints(tokenSteps);
                        partialOraclesDatapoints.add(partialOracleDatapointBuilder.build());
                        break;
                    }
                }
            }
            testClazzDatapointsBuilder.setDatapoints(partialOraclesDatapoints);
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }
        return testClazzDatapointsBuilder.build();
    }
}
