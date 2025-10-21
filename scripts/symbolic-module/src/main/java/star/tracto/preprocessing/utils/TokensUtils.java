package star.tracto.preprocessing.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.*;
import star.tracto.data.exceptions.ServerErrorException;
import star.tracto.data.exceptions.ServerUnexpectedResponseCodeException;
import star.tracto.data.exceptions.TokenNotFoundException;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.preprocessing.builders.PartialOracleDatapointBuilder;
import star.tracto.preprocessing.builders.TestClazzDatapointsBuilder;
import star.tracto.preprocessing.builders.TokenStepBuilder;
import star.tracto.preprocessing.components.*;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class TokensUtils {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TokensUtils.class);
    private static TokenIdentifiersCollector rootTokenIdentifiersCollector;
    private static TokenReferencesCollector rootTokenReferencesCollector;
    private static Path repoSourcePath;
    private static Path repoTestPath;
    private static Path testPrefixClassPath;
    private static Path focalClassPath;
    private static HashMap<String, HashMap<String, List<String>>> clazzDatapointsErrors;
    private static final String streamClassReference = "java.util.stream.Stream";
    private static final String streamTypeParameter = "T";
    private static boolean streamFlag = false;
    private static final String THROW_EXCEPTION_LABEL = "THROW EXCEPTION";

    /**
     * A support class to collect various types of candidate token identifiers categorized by their type when processing
     * an assertion.
     *
     */
    private static class TokenIdentifiersCollector {
        HashMap<String, List<Path>> classPathIdentifiers;
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers;
        HashMap<String, List<Type>> parameterIdentifiers;
        HashMap<String, List<ResolvedType>> lambdaIdentifiers;
        HashMap<String, List<MethodUsage>> methodIdentifiers;
        HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers;
        HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers;

        /*
         * Constructor to initialize the token identifiers' collector.
         * @param classPathIdentifiers A map of class identifiers, where the key is the identifier name and the value
         *                         is a list of TypeDeclarations representing the homonyms classes
         * @param fieldIdentifiers A map of field identifiers, where the key is the identifier name and the value
         *                         is a list of VariableDeclarator representing the homonyms fields
         * @param parameterIdentifiers A map of parameter identifiers, where the key is the identifier name and the value
         * @param lambdaIdentifiers A map of lambda identifiers, where the key is the identifier name and the value
         * @param methodIdentifiers A map of method identifiers, where the key is the identifier name and the value
         *                          is a list of MethodUsage representing the homonyms methods
         * @param constructorIdentifiers A map of constructor identifiers, where the key is the identifier name and
         *                               the value is a list of ResolvedConstructorDeclaration representing the
         *                               homonyms constructors
         * @param enumIdentifiers A map of enum constant identifiers, where the key is the identifier name and
         *                        the value is a list of EnumConstantDeclaration representing the
         *                        homonyms enum constants
         */
        public TokenIdentifiersCollector(
                HashMap<String, List<Path>> classPathIdentifiers,
                HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers,
                HashMap<String, List<Type>> parameterIdentifiers,
                HashMap<String, List<ResolvedType>> lambdaIdentifiers,
                HashMap<String, List<MethodUsage>> methodIdentifiers,
                HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers,
                HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers
        ) {
            this.classPathIdentifiers = classPathIdentifiers;
            this.fieldIdentifiers = fieldIdentifiers;
            this.parameterIdentifiers = parameterIdentifiers;
            this.lambdaIdentifiers = lambdaIdentifiers;
            this.methodIdentifiers = methodIdentifiers;
            this.constructorIdentifiers = constructorIdentifiers;
            this.enumIdentifiers = enumIdentifiers;
        }

        public TokenIdentifiersCollector() {
            this.classPathIdentifiers = new HashMap<>();
            this.fieldIdentifiers = new HashMap<>();
            this.parameterIdentifiers = new HashMap<>();
            this.lambdaIdentifiers = new HashMap<>();
            this.methodIdentifiers = new HashMap<>();
            this.constructorIdentifiers = new HashMap<>();
            this.enumIdentifiers = new HashMap<>();
        }

        public void setClassIdentifiersMap(HashMap<String, List<Path>> classIdentifiers) {
            this.classPathIdentifiers = classIdentifiers;
        }

        public void setFieldIdentifiersMap(HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers) {
            this.fieldIdentifiers = fieldIdentifiers;
        }

        public void setParameterIdentifiersMap(HashMap<String, List<Type>> parameterIdentifiers) {
            this.parameterIdentifiers = parameterIdentifiers;
        }

        public void setLambdaIdentifiersMap(HashMap<String, List<ResolvedType>> lambdaIdentifiers) {
            this.lambdaIdentifiers = lambdaIdentifiers;
        }

        public void setMethodIdentifiersMap(HashMap<String, List<MethodUsage>> methodIdentifiers) {
            this.methodIdentifiers = methodIdentifiers;
        }

        public void setConstructorIdentifiersMap(HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers) {
            this.constructorIdentifiers = constructorIdentifiers;
        }

        public void setEnumIdentifiersMap(HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers) {
            this.enumIdentifiers = enumIdentifiers;
        }
    }

    /**
     * A support class to collect the concrete element (CompilationUnit for class, MethodUsage for method or
     * constructor, and Type for field) references for the current token.
     *
     */
    private static class TokenReferencesCollector {
        ContextualInfoType contextualInfoType;
        HashMap<String, List<ResolvedType>> typeParametersReferences;
        HashMap<String, List<ResolvedType>> parametersReferences;
        HashMap<String, List<ResolvedType>> lambdaReferences;
        HashMap<String, List<Path>> currentClasses;
        HashMap<String, List<Path>> returnClasses;
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fields;
        HashMap<String, List<MethodUsage>> methods;
        HashMap<String, List<ResolvedConstructorDeclaration>> constructors;
        HashMap<String, List<EnumConstantDeclaration>> enums;


        public TokenReferencesCollector(
                ContextualInfoType contextualInfoType,
                HashMap<String, List<ResolvedType>> typeParametersReferences,
                HashMap<String, List<ResolvedType>> parametersReferences,
                HashMap<String, List<ResolvedType>> lambdaReferences,
                HashMap<String, List<Path>> currentClasses,
                HashMap<String, List<Path>> returnClasses,
                HashMap<String, List<Pair<VariableDeclarator,Type>>> fields,
                HashMap<String, List<MethodUsage>> methods,
                HashMap<String, List<ResolvedConstructorDeclaration>> constructors,
                HashMap<String, List<EnumConstantDeclaration>> enums
        ) {
            this.contextualInfoType = contextualInfoType;
            this.parametersReferences = parametersReferences;
            this.typeParametersReferences = typeParametersReferences;
            this.lambdaReferences = lambdaReferences;
            this.currentClasses = currentClasses;
            this.returnClasses = returnClasses;
            this.fields = fields;
            this.methods = methods;
            this.constructors = constructors;
            this.enums = enums;
        }

        public TokenReferencesCollector() {
            this.contextualInfoType = ContextualInfoType.INIT;
            this.typeParametersReferences = new HashMap<>();
            this.parametersReferences = new HashMap<>();
            this.lambdaReferences = new HashMap<>();
            this.currentClasses = new HashMap<>();
            this.returnClasses = new HashMap<>();
            this.fields = new HashMap<>();
            this.methods = new HashMap<>();
            this.constructors = new HashMap<>();
            this.enums = new HashMap<>();
        }

        public void setContextualInfoType(ContextualInfoType contextualInfoType) {
            this.contextualInfoType = contextualInfoType;
        }

        public void setTypeParameterReferences(HashMap<String,List<ResolvedType>> typeParametersReferences) {
            this.typeParametersReferences = typeParametersReferences;
        }

        public void setParameterReferences(HashMap<String, List<ResolvedType>> parametersReferences) {
            this.parametersReferences = parametersReferences;
        }

        public void setLambdaReferences(HashMap<String,List<ResolvedType>> lambdaReferences) {
            this.lambdaReferences = lambdaReferences;
        }

        public void setCurrentClasses(HashMap<String,List<Path>> currentClasses) {
            this.currentClasses = currentClasses;
        }

        public void setReturnClasses(HashMap<String,List<Path>> returnClasses) {
            this.returnClasses = returnClasses;
        }

        public void setFields(HashMap<String,List<Pair<VariableDeclarator,Type>>> fieldTypes) {
            this.fields = fieldTypes;
        }

        public void setMethods(HashMap<String,List<MethodUsage>> methods) {
            this.methods = methods;
        }

        public void setConstructors(HashMap<String,List<ResolvedConstructorDeclaration>> constructors) {
            this.constructors = constructors;
        }

        public void setEnums(HashMap<String,List<EnumConstantDeclaration>> enums) {
            this.enums = enums;
        }
    }

    /**
     * Processes the test prefix and the compilation unit of the test class and the focal class,
     * and generates a list of token steps from the target assertion
     *
     * @param repoRootPath the path to the repository root
     * @param testClazzDatapoints the test class datapoints to process
     * @return a pair containing the test class datapoints with the generated token steps and a map of processing errors
     */
    public static Pair<TestClazzDatapoints,HashMap<String, HashMap<String, List<String>>>> generateTokenSteps (
            Path repoRootPath,
            TestClazzDatapoints<OracleDatapoint> testClazzDatapoints
    ) throws IllegalStateException {
        // Create a map to store the processing errors when processing the datapoints
        clazzDatapointsErrors = new HashMap<>();
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
        Path testClassPath = repoRootPath.resolve(testClazzDatapoints.testClass().filePath().substring(1));
        testPrefixClassPath = repoRootPath.resolve(testClazzDatapoints.testClass().prefixFilePath().substring(1));
        focalClassPath = repoRootPath.resolve(testClazzDatapoints.focalClass().filePath().substring(1));
        String fqnTestClass = testClazzDatapoints.testClass().packageIdentifier() + "." + testClazzDatapoints.testClass().identifier();
        String fqnFocalClass = testClazzDatapoints.focalClass().packageIdentifier() + "." + testClazzDatapoints.focalClass().identifier();
        // Get the path of the test directory of the repository
        repoTestPath = Path.of(testClassPath.toString().replace(FilesUtils.getFQNPath(fqnTestClass).toString(), ""));
        // Get the path of the source directory of the repository
        repoSourcePath = Path.of(focalClassPath.toString().replace(FilesUtils.getFQNPath(fqnFocalClass).toString(), ""));

        try {
            // Get the compilation units for the test class and the focal class
            CompilationUnit tcCu = JavaParserUtils.getCompilationUnit(testPrefixClassPath);
            CompilationUnit fcCu = JavaParserUtils.getCompilationUnit(focalClassPath);
            // Check that the compilation units are not null
            if (tcCu == null || fcCu == null) {
                logger.error("Compilation unit is null for test class: " + testPrefixClassPath + " or focal class: " + focalClassPath);
                testClazzDatapointsBuilder.setDatapoints(new ArrayList<>());
                return new Pair<>(testClazzDatapointsBuilder.build(), clazzDatapointsErrors);
            }
            TypeDeclaration testPrefixClass = tcCu.getPrimaryType().get();
            TypeDeclaration focalClass = fcCu.getPrimaryType().get();
            // Get the test prefixes from the test class (test cases and auxiliary methods defined in the test class)
            List<MethodDeclaration> testPrefixes = testPrefixClass.getMethods();

            testClazzDatapointsBuilder.setTestClass(testClazzDatapoints.testClass());
            testClazzDatapointsBuilder.setFocalClass(testClazzDatapoints.focalClass());
            testClazzDatapointsBuilder.setJunitVersion(testClazzDatapoints.junitVersion());
            // Define list of token steps to return
            List<PartialOracleDatapoint> partialOraclesDatapoints = new ArrayList<>();
            // Commented if statement for debug
            //if (testPrefixClass.getNameAsString().contains("Control008TestSTARSplitTestPrefix")) {
            // Iterate over the oracle datapoints and process each test prefix and associated target assertion
            for (OracleDatapoint datapoint : datapoints) {
                // Get the test prefix from the datapoint
                Callable targetTestPrefix = datapoint.testPrefix();
                // Define partial oracle datapoints builder
                PartialOracleDatapointBuilder partialOracleDatapointBuilder = new PartialOracleDatapointBuilder();
                // Iterate over the test methods to find the one matching the test prefix identifier
                for (MethodDeclaration testPrefix : testPrefixes) {
                    if (testPrefix.getNameAsString().equals(targetTestPrefix.identifier())) {
                        List<Pair<TokenIdentifiersCollector, TokenReferencesCollector>> tokenIdentifiersStack = new ArrayList<>();
                        rootTokenIdentifiersCollector = new TokenIdentifiersCollector();
                        rootTokenReferencesCollector = new TokenReferencesCollector();
                        // Get the target assertion from the test prefix
                        String targetAssertion = datapoint.target();

                        logger.info("Processing test class " + (testPrefixClass.getFullyQualifiedName().isPresent() ? testPrefixClass.getFullyQualifiedName().get() : testPrefixClass.getNameAsString()) + " - target assertion: " + targetAssertion);
                        // Commented if statement for debug
                        //if (targetAssertion.contains("assertEquals(Arrays.asList(\"008all00\", \"008all06\", \"008all07\", \"008all11\", \"008all15\", \"008all35\", \"008all38\", \"008all39\", \"008book18\", \"008book22\", \"008book23\", \"008book24\", \"008book28\", \"008book29\", \"008book30\", \"008book31\", \"008book33\", \"008book34\"), field.getMap().keySet().stream().map(x -> x.getId()).collect(Collectors.toList()));")) {
                        try {
                            // Setup error map for the test class
                            clazzDatapointsErrors.putIfAbsent(testPrefix.getNameAsString(), new HashMap<>());
                            // Setup error map for the test case
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).putIfAbsent(targetAssertion, new ArrayList<>());
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).putIfAbsent("targetFound", new ArrayList<>());
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).putIfAbsent("targetMissing", new ArrayList<>());
                            // Setup the root token identifiers amd references collector
                            setUpRootTokenIdentifiers(tcCu, testPrefix, targetAssertion);
                            setupRootTokenReferences(rootTokenReferencesCollector, rootTokenIdentifiersCollector);
                            TokenIdentifiersCollector currentTokenIdentifiersCollector = cloneRootTokenIdentifiers();
                            TokenReferencesCollector currentTokenReferences = new TokenReferencesCollector(); //cloneRootTokenReferences();
                            tokenIdentifiersStack.add(new Pair<>(currentTokenIdentifiersCollector, currentTokenReferences));
                            // Initialize the list of token steps
                            List<TokenStep> tokenSteps = new ArrayList<>();
                            // Split the target assertion into tokens
                            List<String> tokens = !targetAssertion.equals(THROW_EXCEPTION_LABEL) ? Antlr4Utils.tokenizeSnippet(targetAssertion) : List.of(THROW_EXCEPTION_LABEL);
                            if (!tokens.get(0).equals(THROW_EXCEPTION_LABEL)) {
                                // Remove spurious tokens and check that the first token is a valid JUnit assertion method
                                try {
                                    tokens.removeIf(t -> t.equals(" "));
                                    JUnitAssertionType.fromString(tokens.get(0));
                                } catch (IllegalArgumentException e) {
                                    String message = "Assertion do not start with JUnit api (do not implemented yet): " + targetAssertion + " - " + e.getMessage();
                                    logger.error(message);
                                    clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(message);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                    continue;
                                }
                            }
                            // Log target assertion
                            logger.info("Target assertion: " + targetAssertion);
                            logger.info("Target assertion tokens: " + tokens.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
                            // Start with an empty partial snippet
                            String partialSnippet = "";
                            // Iterate over the tokens to build the token steps
                            for (int i = 0; i < tokens.size(); i++) {
                                // Get the current token to process
                                String token = tokens.get(i);
                                logger.info("Partial Oracle: " + partialSnippet);
                                logger.info("Processing token: " + token);
                                // Define a new token step builder for the current token step
                                TokenStepBuilder tokenStepBuilder = new TokenStepBuilder();
                                tokenStepBuilder.setPartialSnippet(partialSnippet);
                                // In the first step, we know that the next possible tokens are only JUnit assertion methods
                                if (partialSnippet.isEmpty()) {
                                    tokenStepBuilder.setNextPossibleJavaTokens(Arrays.stream(JUnitAssertionType.values()).map(a -> a.getAssertionMethodName()).collect(Collectors.toList()));
                                    tokenStepBuilder.setNextPossibleProjectTokens(new ArrayList<>());
                                    tokenStepBuilder.setNextPossibleGenericTokens(List.of(THROW_EXCEPTION_LABEL));
                                    tokenStepBuilder.setSyntaxCorrect(false);
                                    tokenStepBuilder.setTarget("ASSERT:" + tokens.get(0));
                                    tokenStepBuilder.setContextualInfos(new ArrayList<>());
                                    tokenSteps.add(tokenStepBuilder.build());
                                    partialSnippet += token;
                                    continue;
                                }
                                // Define the list of next possible tokens, divided by type
                                List<String> adjustedNextJavaTokens = new ArrayList<>();
                                List<String> adjustedNextConcreteTokens = new ArrayList<>();
                                List<String> adjustedNextGenericTokens = new ArrayList<>();
                                // Collect the list of possible raw next tokens using ANTLR4 (they need to be processed afterward)
                                List<String> nextANTLRTokens = Antlr4Utils.parseSnippet(JUnitAssertionType.fromString(tokens.get(0)), partialSnippet, 600);
                                // If ANTLR4 returns an empty list of candidates and the token is not a semicolon
                                // (terminal symbol in Java statements), raise an exception because the expression
                                // do not follow the Java syntax imposed by the grammar
                                if (nextANTLRTokens.isEmpty() && !token.equals(";")) {
                                    IllegalStateException exception = new IllegalStateException("Unexpected empty token candidates after partial snippet: " + partialSnippet + " and token: " + token);
                                    clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(exception.getMessage());
                                    throw exception;
                                }
                                // Process the list of raw ANTLR next tokens
                                for (String nextANTLRToken : nextANTLRTokens) {
                                    if (literalsSet.contains(nextANTLRToken)) {
                                        adjustedNextGenericTokens.add(nextANTLRToken);
                                    } else if (nextANTLRToken.equals("IDENTIFIER")) {
                                        if (!Antlr4Utils.getAntlrToken(tokens.get(i - 1)).equals(AntlrToken.IDENTIFIER)) {
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.classPathIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.methodIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.constructorIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.fieldIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.parameterIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.lambdaIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.enumIdentifiers.keySet());
                                        }
                                        if (streamFlag && Antlr4Utils.getAntlrToken(tokens.get(i - 1)).equals(AntlrToken.LPAREN) && !adjustedNextGenericTokens.contains(token) && !adjustedNextConcreteTokens.contains(token) && !adjustedNextJavaTokens.contains(token) && ((i-2) > 0) && !tokens.get(i-2).equals("collect")) {
                                            adjustedNextConcreteTokens.add("LAMBDA_PARAM");
                                        }
                                    } else {
                                        adjustedNextJavaTokens.add(nextANTLRToken);
                                    }
                                }
                                logger.info("Next java tokens: " + adjustedNextJavaTokens.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
                                logger.info("Next concrete tokens: " + adjustedNextConcreteTokens.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
                                logger.info("Next generic tokens: " + adjustedNextGenericTokens.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
                                // If the target token is not in the list of next possible tokens, warn about the
                                // Impossibility to provide the target token among the candidates (it can happen for
                                // example when the token is an identifier from an external library impossible to
                                // process by JavaParser)
                                if (!adjustedNextConcreteTokens.contains(token) && !adjustedNextJavaTokens.contains(token) && !adjustedNextGenericTokens.contains(token)) {
                                    // If the token type is not a literal warn about the impossibility to provide the
                                    // token among the candidates
                                    if (!literalsSet.contains(Antlr4Utils.getAntlrToken(token).toString()) && !adjustedNextConcreteTokens.contains("LAMBDA_PARAM")) {
                                        String message = "Unexpected token: " + token + " not found in the collected list of candidate tokens: " + partialSnippet;
                                        logger.error(message);
                                        clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(message);
                                        clazzDatapointsErrors.get(testPrefix.getNameAsString()).get("targetMissing").add(token);
                                    } else {
                                        // If the token is a literal, is the model responsible for generating the specific token
                                        clazzDatapointsErrors.get(testPrefix.getNameAsString()).get("targetFound").add(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                    }
                                } else {
                                    // Token found among the candidates
                                    clazzDatapointsErrors.get(testPrefix.getNameAsString()).get("targetFound").add(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                }
                                // Build the token step
                                tokenStepBuilder.setNextPossibleGenericTokens(adjustedNextGenericTokens);
                                tokenStepBuilder.setNextPossibleProjectTokens(adjustedNextConcreteTokens);
                                tokenStepBuilder.setNextPossibleJavaTokens(adjustedNextJavaTokens);
                                tokenStepBuilder.setSyntaxCorrect(false);
                                if (streamFlag && Antlr4Utils.getAntlrToken(tokens.get(i - 1)).equals(AntlrToken.LPAREN) && !adjustedNextGenericTokens.contains(token) && !adjustedNextConcreteTokens.contains(token) && !adjustedNextJavaTokens.contains(token)  && ((i-2) > 0) && !tokens.get(i-2).equals("collect")) {
                                    tokenStepBuilder.setTarget("LAMBDA_PARAM" + ":" + token);
                                    logger.info("Target: LAMBDA_PARAM" + ":" + token + "\n");
                                } else {
                                    tokenStepBuilder.setTarget(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                    logger.info("Target: " + Antlr4Utils.getAntlrToken(token) + ":" + token + "\n");
                                }
                                //tokenStepBuilder.setContextualInfos(tokenIdentifiersStack.stream().map(t -> generateTokenStepContextualInfo(t.getValue1())).collect(Collectors.toList()));
                                tokenStepBuilder.setContextualInfos(List.of(generateTokenStepContextualInfo(tokenIdentifiersStack.get(tokenIdentifiersStack.size()-1).getValue1(), true)));
                                tokenSteps.add(tokenStepBuilder.build());
                                // Update the partial snippet
                                partialSnippet += AntlrToken.isAtomicKeywordToken(Antlr4Utils.getAntlrToken(token)) ? token + " " : token;
                                // Update the token identifiers and references collectors
                                Pair<TokenIdentifiersCollector, TokenReferencesCollector> updatedCollectors = updateTokenIdentifiersAndReferences(tokenIdentifiersStack, token, tokens.get(i - 1), testPrefixClass, testPrefix.getNameAsString(), targetAssertion);
                                currentTokenIdentifiersCollector = updatedCollectors.getValue0();
                                currentTokenReferences = updatedCollectors.getValue1();
                            }
                            // Build the partial oracle datapoint
                            partialOracleDatapointBuilder.setTestPrefix(targetTestPrefix);
                            partialOracleDatapointBuilder.setTarget(targetAssertion);
                            partialOracleDatapointBuilder.setDatapoints(tokenSteps);
                            partialOraclesDatapoints.add(partialOracleDatapointBuilder.build());
                        } catch (Exception e) {
                            logger.error(e.getMessage());
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(e.getMessage());
                            logger.error("********************************************************************");
                            logger.error("Stack trace:");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                logger.error("\tat " + ste.toString());
                            }
                            logger.error("********************************************************************");
                        }
                        //}
                        break;
                    }
                }
            }
            //}
            testClazzDatapointsBuilder.setDatapoints(partialOraclesDatapoints);
        } catch (IOException e) {
            logger.error("Error reading file: " + testPrefixClassPath);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        return new Pair<>(testClazzDatapointsBuilder.build(), clazzDatapointsErrors);
    }

    private static String getNextToken(
            TestClazzDatapoints<PartialOracleDatapoint> tokenStep,
            Path nextTokenPath,
            String apiURL
    ) {
        // ObjectMapper for JSON processing
        ObjectMapper objectMapper = new ObjectMapper();
        Path nextTokenFilePath = nextTokenPath.resolve("token-step.json");

        try {
            objectMapper.writeValue(nextTokenFilePath.toFile(), List.of(tokenStep));
            HttpURLConnection mlModelApiConn = (HttpURLConnection) new URL(apiURL).openConnection();
            mlModelApiConn.setRequestMethod("GET");
            mlModelApiConn.connect();
            int responseCode = mlModelApiConn.getResponseCode();
            String nextToken;
            if (responseCode == HttpURLConnection.HTTP_OK) {
                nextToken = new String(mlModelApiConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                // Handle 404 response
                String errorResponse = new String(mlModelApiConn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new TokenNotFoundException(errorResponse);
            } else if (responseCode == HttpURLConnection.HTTP_INTERNAL_ERROR) {
                throw new ServerErrorException("Unexpected internal error: " + responseCode);
            } else {
                throw new ServerUnexpectedResponseCodeException("Unexpected response code: " + responseCode);
            }
            mlModelApiConn.disconnect();
            return nextToken;
        } catch (IOException e) {
            throw new RuntimeException("Error writing JSON to file", e);
        }
    }

    public static Pair<List<List<String>>,HashMap<String, HashMap<String, List<String>>>> generateInference(
            Path repoRootPath,
            Path nextTokenPath,
            String apiURL,
            TestClazzDatapoints<OracleDatapoint> testClazzDatapoints
    ) throws IllegalStateException {
        // Define the set of literal tokens to be replaced with "LITERAL"
        Set<String> literalsSet = new HashSet<>(Arrays.asList(
                "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL",
                "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "BOOL_LITERAL",
                "CHAR_LITERAL", "STRING_LITERAL"
        ));
        List<List<String>> inferenceList = new ArrayList<>();
        // Create a map to store the processing errors when processing the datapoints
        clazzDatapointsErrors = new HashMap<>();
        // Retrieve the datapoints and the path of the test class and the focal class from the test class object
        List<OracleDatapoint> datapoints = testClazzDatapoints.datapoints();
        Path testClassPath = repoRootPath.resolve(testClazzDatapoints.testClass().filePath().substring(1));
        testPrefixClassPath = repoRootPath.resolve(testClazzDatapoints.testClass().prefixFilePath().substring(1));
        focalClassPath = repoRootPath.resolve(testClazzDatapoints.focalClass().filePath().substring(1));
        String fqnTestClass = testClazzDatapoints.testClass().packageIdentifier() + "." + testClazzDatapoints.testClass().identifier();
        String fqnFocalClass = testClazzDatapoints.focalClass().packageIdentifier() + "." + testClazzDatapoints.focalClass().identifier();
        // Get the path of the test directory of the repository
        repoTestPath = Path.of(testClassPath.toString().replace(FilesUtils.getFQNPath(fqnTestClass).toString(), ""));
        // Get the path of the source directory of the repository
        repoSourcePath = Path.of(focalClassPath.toString().replace(FilesUtils.getFQNPath(fqnFocalClass).toString(), ""));
        try {
            // Get the compilation units for the test class and the focal class
            CompilationUnit tcCu = JavaParserUtils.getCompilationUnit(testPrefixClassPath);
            CompilationUnit fcCu = JavaParserUtils.getCompilationUnit(focalClassPath);
            // Check that the compilation units are not null
            if (tcCu == null || fcCu == null) {
                logger.error("Compilation unit is null for test class: " + testPrefixClassPath + " or focal class: " + focalClassPath);
                return new Pair<>(new ArrayList<>(), clazzDatapointsErrors);
            }
            TypeDeclaration testPrefixClass = tcCu.getPrimaryType().get();
            TypeDeclaration focalClass = fcCu.getPrimaryType().get();
            // Get the test prefixes from the test class (test cases and auxiliary methods defined in the test class)
            List<MethodDeclaration> testPrefixes = testPrefixClass.getMethods();
            // Commented if statement for debug
            //if (testPrefixClass.getNameAsString().contains("MarcFactoryTestSTARSplitTestPrefix")) {
            int datapointCounter = 0;
            // Iterate over the oracle datapoints and process each test prefix and associated target assertion
            for (OracleDatapoint datapoint : datapoints) {
                List<String> oracleInference = new ArrayList<>();
                String datapointId = testClazzDatapoints.testClass().identifier() + "-" + datapoint.testPrefix().identifier() + "-" + datapointCounter++;
                oracleInference.add(datapointId);
                oracleInference.add(testClassPath.toString());
                oracleInference.add(datapoint.testPrefix().identifier());
                oracleInference.add(datapoint.testPrefix().signature());
                oracleInference.add(datapoint.testPrefix().body());
                // Get the test prefix from the datapoint
                Callable targetTestPrefix = datapoint.testPrefix();
                // Set flag to check if the prefix has been found
                boolean prefixFound = false;
                // Iterate over the test methods to find the one matching the test prefix identifier
                for (MethodDeclaration testPrefix : testPrefixes) {
                    if (testPrefix.getNameAsString().equals(targetTestPrefix.identifier())) {
                        prefixFound = true;
                        List<Pair<TokenIdentifiersCollector, TokenReferencesCollector>> tokenIdentifiersStack = new ArrayList<>();
                        rootTokenIdentifiersCollector = new TokenIdentifiersCollector();
                        rootTokenReferencesCollector = new TokenReferencesCollector();
                        // Get the target assertion from the test prefix
                        String targetAssertion = datapointId + "::" + "inference";
                        logger.info("Processing test class " + (testPrefixClass.getFullyQualifiedName().isPresent() ? testPrefixClass.getFullyQualifiedName().get() : testPrefixClass.getNameAsString()));
                        // Commented if statement for debug
                        //if (targetAssertion.contains("assertEquals(\"type, Leader, Leader_recordLength, Leader_recordStatus, Leader_typeOfRecord, \" + \"Leader_bibliographicLevel, Leader_typeOfControl, Leader_characterCodingScheme, \" + \"Leader_indicatorCount, Leader_subfieldCodeCount, Leader_baseAddressOfData, \" + \"Leader_encodingLevel, Leader_descriptiveCatalogingForm, Leader_multipartResourceRecordLevel, \" + \"Leader_lengthOfTheLengthOfFieldPortion, Leader_lengthOfTheStartingCharacterPositionPortion, \" + \"Leader_lengthOfTheImplementationDefinedPortion, Leader_undefined, ControlNumber, ControlNumberIdentifier, \" + \"LatestTransactionTime, PhysicalDescription, PhysicalDescription_categoryOfMaterial, \" + \"PhysicalDescription_specificMaterialDesignation, GeneralInformation, \" + \"GeneralInformation_dateEnteredOnFile, GeneralInformation_typeOfDateOrPublicationStatus, \" + \"GeneralInformation_date1, GeneralInformation_date2, \" + \"GeneralInformation_placeOfPublicationProductionOrExecution, GeneralInformation_language, \" + \"GeneralInformation_modifiedRecord, GeneralInformation_catalogingSource, \" + \"GeneralInformation_frequency, GeneralInformation_regularity, \" + \"GeneralInformation_typeOfContinuingResource, GeneralInformation_formOfOriginalItem, \" + \"GeneralInformation_formOfItem, GeneralInformation_natureOfEntireWork, \" + \"GeneralInformation_natureOfContents, GeneralInformation_governmentPublication, \" + \"GeneralInformation_conferencePublication, \" + \"GeneralInformation_originalAlphabetOrScriptOfTitle, GeneralInformation_entryConvention, \" + \"IdIntifiedByLocal_source, IdIntifiedByLocal, IdIntifiedByLocal_agency, \" + \"Issn_levelOfInternationalInterest, Issn, \" + \"SystemControlNumber_organizationCode, \" + \"SystemControlNumber, SystemControlNumber_recordNumber, SystemControlNumber_organization, \" + \"AdminMetadata_languageOfCataloging, \" + \"AdminMetadata_descriptionConventions, AdminMetadata_transcribingAgency, AdminMetadata_catalogingAgency, \" + \"Language_translationIndication, Language, Language_sourceOfCode, Place_country, ClassificationDdc_full, \" + \"ClassificationDdc_editionType, ClassificationDdc_classificationSource, ClassificationDdc, \" + \"Classification_classificationPortion, Classification_classificationPortion_zdbs, \" + \"Classification_source, Classification_full, Title_subtitle, \" + \"Title_responsibilityStatement, Title_mainTitle, Title_titleAddedEntry, \" + \"Title_nonfilingCharacters, Title_partName, ParallelTitle_mainTitle, ParallelTitle_type, \" + \"ParallelTitle_displayText, ParallelTitle_noteAndAddedEntry, \" + \"Publication_sequenceOfPublishingStatements, Publication_agent, Publication_place, \" + \"DatesOfPublication, DatesOfPublication_format, NumberingPeculiarities, \" + \"BemerkungenZurTitelaufnahme, \" + \"RSWKKette_nummerDesKettengliedes, RSWKKette_0, RSWKKette_a, \" + \"RSWKKette_nummerDerRSWKKette, RSWKKette_D, RSWKKette_5, AddedCorporateName, AddedCorporateName_full, \" + \"AddedCorporateName_authorityRecordControlNumber, \" + \"AddedCorporateName_authorityRecordControlNumber_recordNumber, \" + \"AddedCorporateName_authorityRecordControlNumber_organization, \" + \"AddedCorporateName_authorityRecordControlNumber_organizationCode, \" + \"AddedCorporateName_entryType, AddedCorporateName_nameType, PartOf_relation, PartOf_displayConstant, \" + \"PartOf_recordControlNumber, PartOf_noteController, PartOf_recordControlNumber_recordNumber, \" + \"PartOf_recordControlNumber_organization, PartOf_recordControlNumber_organizationCode, PartOf, \" + \"PrecededBy, PrecededBy_relation, PrecededBy_recordControlNumber_organization, \" + \"PrecededBy_recordControlNumber_recordNumber, PrecededBy_typeOfRelationship, \" + \"PrecededBy_recordControlNumber, PrecededBy_recordControlNumber_organizationCode, \" + \"PrecededBy_noteController, Bestandsinformationen, Bestandsinformationen_9, \" + \"Bestandsinformationen_d, \" + \"Bestandsinformationen_artDerRessource, Bestandsinformationen_b, Bestandsinformationen_c, \" + \"Bestandsinformationen_h, Bestandsinformationen_g\", StringUtils.join(keys, \", \"))")) {
                        // Start with an empty partial snippet
                        String partialSnippet = "";
                        try {
                            // Setup error map for the test class
                            clazzDatapointsErrors.putIfAbsent(testPrefix.getNameAsString(), new HashMap<>());
                            // Setup error map for the test case
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).putIfAbsent(targetAssertion, new ArrayList<>());
                            // Setup the root token identifiers amd references collector
                            setUpRootTokenIdentifiers(tcCu, testPrefix, targetAssertion);
                            setupRootTokenReferences(rootTokenReferencesCollector, rootTokenIdentifiersCollector);
                            TokenIdentifiersCollector currentTokenIdentifiersCollector = cloneRootTokenIdentifiers();
                            TokenReferencesCollector currentTokenReferences = new TokenReferencesCollector(); //cloneRootTokenReferences();
                            tokenIdentifiersStack.add(new Pair<>(currentTokenIdentifiersCollector, currentTokenReferences));
                            // Iterate over the tokens to build the token steps
                            while (!partialSnippet.endsWith(";")) {
                                // Define a new token step builder for the current token step
                                TokenStepBuilder tokenStepBuilder = new TokenStepBuilder();
                                // Define partial oracle datapoints builder
                                PartialOracleDatapointBuilder partialOracleDatapointBuilder = new PartialOracleDatapointBuilder();
                                // Define test class datapoints builder
                                TestClazzDatapointsBuilder<PartialOracleDatapoint> testClazzDatapointsBuilder = new TestClazzDatapointsBuilder<>();
                                tokenStepBuilder.setPartialSnippet(partialSnippet);
                                // In the first step, we know that the next possible tokens are only JUnit assertion methods
                                if (partialSnippet.isEmpty()) {
                                    tokenStepBuilder.setNextPossibleJavaTokens(Arrays.stream(JUnitAssertionType.values()).map(a -> a.getAssertionMethodName()).collect(Collectors.toList()));
                                    tokenStepBuilder.setNextPossibleProjectTokens(new ArrayList<>());
                                    tokenStepBuilder.setNextPossibleGenericTokens(List.of(THROW_EXCEPTION_LABEL));
                                    tokenStepBuilder.setSyntaxCorrect(false);
                                    tokenStepBuilder.setTarget("");
                                    tokenStepBuilder.setContextualInfos(new ArrayList<>());
                                    partialOracleDatapointBuilder.setTestPrefix(targetTestPrefix);
                                    partialOracleDatapointBuilder.setTarget("");
                                    partialOracleDatapointBuilder.setDatapoints(List.of(tokenStepBuilder.build()));
                                    testClazzDatapointsBuilder.setTestClass(testClazzDatapoints.testClass());
                                    testClazzDatapointsBuilder.setFocalClass(testClazzDatapoints.focalClass());
                                    testClazzDatapointsBuilder.setJunitVersion(testClazzDatapoints.junitVersion());
                                    testClazzDatapointsBuilder.setDatapoints(List.of(partialOracleDatapointBuilder.build()));
                                    // Get the next token from the model
                                    String nextToken = getNextToken(
                                            testClazzDatapointsBuilder.build(),
                                            nextTokenPath,
                                            apiURL
                                    );
                                    partialSnippet += nextToken;

                                    if (nextToken.equals(THROW_EXCEPTION_LABEL)) {
                                        oracleInference.add(nextToken);
                                        break;
                                    }
                                    continue;
                                }
                                List<String> tokens = Antlr4Utils.tokenizeSnippet(partialSnippet);
                                // Remove spurious tokens and check that the first token is a valid JUnit assertion method
                                try {
                                    tokens.removeIf(t -> t.equals(" "));
                                    JUnitAssertionType.fromString(tokens.get(0));
                                } catch (IllegalArgumentException e) {
                                    String message = "Assertion do not start with JUnit api (do not implemented yet): " + targetAssertion + " - " + e.getMessage();
                                    logger.error(message);
                                    clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(message);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                    oracleInference.add(partialSnippet);
                                    oracleInference.add("ERROR");
                                    oracleInference.add(message);
                                    break;
                                }
                                // Define the list of next possible tokens, divided by type
                                List<String> adjustedNextJavaTokens = new ArrayList<>();
                                List<String> adjustedNextConcreteTokens = new ArrayList<>();
                                List<String> adjustedNextGenericTokens = new ArrayList<>();
                                // Collect the list of possible raw next tokens using ANTLR4 (they need to be processed afterward)
                                List<String> nextANTLRTokens = Antlr4Utils.parseSnippet(JUnitAssertionType.fromString(tokens.get(0)), partialSnippet, 600);
                                // If ANTLR4 returns an empty list of candidates and the token is not a semicolon
                                // (terminal symbol in Java statements), raise an exception because the expression
                                // do not follow the Java syntax imposed by the grammar
                                if (nextANTLRTokens.isEmpty()) {
                                    IllegalStateException exception = new IllegalStateException("Unexpected empty token candidates after partial snippet: " + partialSnippet);
                                    clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(exception.getMessage());
                                    oracleInference.add(partialSnippet);
                                    oracleInference.add("ERROR");
                                    oracleInference.add(exception.getMessage());
                                    break;
                                }
                                // Process the list of raw ANTLR next tokens
                                for (int i = 0; i < nextANTLRTokens.size(); i++) {
                                    String nextANTLRToken = nextANTLRTokens.get(i);
                                    if (literalsSet.contains(nextANTLRToken)) {
                                        adjustedNextGenericTokens.add(nextANTLRToken);
                                    } else if (nextANTLRToken.equals("IDENTIFIER")) {
                                        if (!Antlr4Utils.getAntlrToken(tokens.get(tokens.size() - 1)).equals(AntlrToken.IDENTIFIER)) {
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.classPathIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.methodIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.constructorIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.fieldIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.parameterIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.lambdaIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.enumIdentifiers.keySet());
                                        }
                                        if (streamFlag && Antlr4Utils.getAntlrToken(tokens.get(i - 1)).equals(AntlrToken.LPAREN) && ((i-2) > 0) && !tokens.get(i-2).equals("collect")) {
                                            adjustedNextConcreteTokens.add("LAMBDA_PARAM");
                                        }
                                    } else {
                                        adjustedNextJavaTokens.add(nextANTLRToken);
                                    }
                                }
                                // Build the token step
                                tokenStepBuilder.setNextPossibleGenericTokens(adjustedNextGenericTokens);
                                tokenStepBuilder.setNextPossibleProjectTokens(adjustedNextConcreteTokens);
                                tokenStepBuilder.setNextPossibleJavaTokens(adjustedNextJavaTokens);
                                tokenStepBuilder.setSyntaxCorrect(false);
                                //tokenStepBuilder.setContextualInfos(tokenIdentifiersStack.stream().map(t -> generateTokenStepContextualInfo(t.getValue1())).collect(Collectors.toList()));
                                tokenStepBuilder.setContextualInfos(List.of(generateTokenStepContextualInfo(tokenIdentifiersStack.get(tokenIdentifiersStack.size()-1).getValue1(), true)));
                                partialOracleDatapointBuilder.setTestPrefix(targetTestPrefix);
                                partialOracleDatapointBuilder.setTarget("");
                                partialOracleDatapointBuilder.setDatapoints(List.of(tokenStepBuilder.build()));
                                testClazzDatapointsBuilder.setTestClass(testClazzDatapoints.testClass());
                                testClazzDatapointsBuilder.setFocalClass(testClazzDatapoints.focalClass());
                                testClazzDatapointsBuilder.setJunitVersion(testClazzDatapoints.junitVersion());
                                testClazzDatapointsBuilder.setDatapoints(List.of(partialOracleDatapointBuilder.build()));
                                // Get the next token from the model
                                String nextToken = getNextToken(
                                        testClazzDatapointsBuilder.build(),
                                        nextTokenPath,
                                        apiURL
                                );
                                // Update the partial snippet
                                partialSnippet += AntlrToken.isAtomicKeywordToken(Antlr4Utils.getAntlrToken(nextToken)) ? nextToken + " " : nextToken;
                                // Update the token identifiers and references collectors
                                Pair<TokenIdentifiersCollector, TokenReferencesCollector> updatedCollectors = updateTokenIdentifiersAndReferences(tokenIdentifiersStack, nextToken, tokens.get(tokens.size() - 1), testPrefixClass, testPrefix.getNameAsString(), targetAssertion);
                                currentTokenIdentifiersCollector = updatedCollectors.getValue0();
                                currentTokenReferences = updatedCollectors.getValue1();
                            }
                            if (partialSnippet.endsWith(";")) {
                                oracleInference.add(partialSnippet);
                                oracleInference.add("OK");
                                oracleInference.add("");
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage());
                            clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(e.getMessage());
                            oracleInference.add(partialSnippet);
                            oracleInference.add("ERROR");
                            oracleInference.add(e.getMessage());
                            logger.error("********************************************************************");
                            logger.error("Stack trace:");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                logger.error("\tat " + ste.toString());
                            }
                            logger.error("********************************************************************");
                        }
                        //}
                        break;
                    }
                }
                if (!prefixFound) {
                    oracleInference.add("");
                    oracleInference.add("ERROR");
                    oracleInference.add("Test prefix not found");
                }
                inferenceList.add(oracleInference);
            }
            //}
        } catch (IOException e) {
            logger.error("Error reading file: " + testPrefixClassPath);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        return new Pair<>(inferenceList, clazzDatapointsErrors);
    }



    public static TokenIdentifiersCollector cloneRootTokenIdentifiers () {
        TokenIdentifiersCollector clonedTokenIdentifierCollector = new TokenIdentifiersCollector(
                new HashMap<>(rootTokenIdentifiersCollector.classPathIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.fieldIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.parameterIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.lambdaIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.methodIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.constructorIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.enumIdentifiers)
        );
        return clonedTokenIdentifierCollector;
    }

    public static TokenReferencesCollector cloneRootTokenReferences () {
        TokenReferencesCollector clonedTokenReferencesCollector = new TokenReferencesCollector(
                rootTokenReferencesCollector.contextualInfoType,
                new HashMap<>(rootTokenReferencesCollector.typeParametersReferences),
                new HashMap<>(rootTokenReferencesCollector.parametersReferences),
                new HashMap<>(rootTokenReferencesCollector.lambdaReferences),
                new HashMap<>(rootTokenReferencesCollector.currentClasses),
                new HashMap<>(rootTokenReferencesCollector.returnClasses),
                new HashMap<>(rootTokenReferencesCollector.fields),
                new HashMap<>(rootTokenReferencesCollector.methods),
                new HashMap<>(rootTokenReferencesCollector.constructors),
                new HashMap<>(rootTokenReferencesCollector.enums)
        );
        return clonedTokenReferencesCollector;
    }

    /**
     * Resets the token identifiers map for the given compilation units (test class and focal class) and the test prefix
     * method declaration.
     * This is useful to clear any previously collected token identifiers before processing a new test case or a new
     * expression within the same test case.
     *
     * @param tcCu the compilation unit of the test class
     * @param tp the method declaration of the test prefix
     * @param targetAssertion the target assertion to process
     */
    public static void setUpRootTokenIdentifiers (
            CompilationUnit tcCu,
            MethodDeclaration tp,
            String targetAssertion
    ) {
        // Get the primary type of the test class to collect fields and methods defined within it
        TypeDeclaration tc = tcCu.getPrimaryType().get();
        // Collect the fields defined within the test class and the test prefix (immediately accessible)
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldTypeIdentifiers = new HashMap<>();
        try {
            updateHashMapOfFieldTypeIdentifiers(fieldTypeIdentifiers, tc.getFields(), List.of(
                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))),
                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(true)))
            ));
            updateHashMapOfFieldTypeIdentifiers(fieldTypeIdentifiers, tp);
        } catch (Exception e) {
            String message = "Error collecting root variables type identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).add(e.getMessage());
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        // Collect the list of classes imported within the test class and the focal class, and the ones defined within
        // the same package of the test class or the focal class (immediately accessible)
        HashMap<String, List<Path>> typeDeclarationPathIdentifiers = new HashMap<>();
        List<List<Pair<Modifier.Keyword, Boolean>>> typeDeclModifiersRules = List.of(List.of(
                new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(false)),
                new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))
        ));
        try {
            List<String> errorMessages = new ArrayList<>();
            updateHashMapOfTypeDeclarationPathIdentifiers(typeDeclarationPathIdentifiers, List.of(repoTestPath, repoSourcePath), testPrefixClassPath, typeDeclModifiersRules, errorMessages);
            updateHashMapOfTypeDeclarationPathIdentifiers(typeDeclarationPathIdentifiers, List.of(repoSourcePath), focalClassPath, typeDeclModifiersRules, errorMessages);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).addAll(errorMessages);
        } catch (Exception e) {
            String message = "Error collecting root type declaration identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).add(e.getMessage());
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        // Collect the parameters defined within try/catch statements within the test prefix
        HashMap<String, List<Type>> parameterIdentifiers = new HashMap<>();
        try {
            updateHashMapOfParameterIdentifiers(parameterIdentifiers, tp);
        } catch (Exception e) {
            String message = "Error collecting parameter identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).add(e.getMessage());
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        // Collect the list of methods defined within the test class (immediately accessible)
        HashMap<String, List<MethodUsage>> methodUsageIdentifiers = new HashMap<>();
        try {
            updateHashMapOfMethodUsageIdentifiers(methodUsageIdentifiers, tc, new ArrayList<>(), List.of(new Pair("Test", Boolean.valueOf(false))));
        } catch (Exception e) {
            String message = "Error collecting root method usage identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).add(e.getMessage());
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
        // Setup the token identifiers map
        rootTokenIdentifiersCollector.setClassIdentifiersMap(typeDeclarationPathIdentifiers);
        rootTokenIdentifiersCollector.setFieldIdentifiersMap(fieldTypeIdentifiers);
        rootTokenIdentifiersCollector.setMethodIdentifiersMap(methodUsageIdentifiers);
        rootTokenIdentifiersCollector.setParameterIdentifiersMap(parameterIdentifiers);
    }

    public static void setupRootTokenReferences (
            TokenReferencesCollector tokenReferencesCollector,
            TokenIdentifiersCollector tokenIdentifiersCollector
    ) {
        tokenReferencesCollector.setCurrentClasses(tokenIdentifiersCollector.classPathIdentifiers);
        HashMap<String,List<MethodUsage>> methods = new HashMap<>();
        updateHashMapOfMethodUsageReferencesFromIdentifiers(methods,tokenIdentifiersCollector.methodIdentifiers);
        tokenReferencesCollector.setMethods(methods);
        HashMap<String,List<ResolvedType>> parameters = new HashMap<>();
        updateHashMapOfParameterReferencesFromIdentifiers(parameters,tokenIdentifiersCollector.parameterIdentifiers);
        tokenReferencesCollector.setParameterReferences(parameters);
        HashMap<String,List<Pair<VariableDeclarator,Type>>> fields = new HashMap<>();
        updateHashMapOfFieldTypeReferencesFromIdentifiers(fields,tokenIdentifiersCollector.fieldIdentifiers);
        tokenReferencesCollector.setFields(fields);
        for (Map.Entry<String,List<Pair<VariableDeclarator,Type>>> entry: fields.entrySet()) {
            collectTypeParametersReferencesFromFieldIdentifiers(entry.getValue(), tokenReferencesCollector);
        }
    }

    private static void updateHashMapOfFieldTypeReferencesFromIdentifiers(
            HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldTypeReferences,
            HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldTypeIdentifiers
    ) {
        for (Map.Entry<String,List<Pair<VariableDeclarator,Type>>> entry: fieldTypeIdentifiers.entrySet()) {
            String fieldName = entry.getKey();
            for (Pair<VariableDeclarator,Type> fieldEntry: entry.getValue()) {
                VariableDeclarator variable = fieldEntry.getValue0();
                Type fieldType = fieldEntry.getValue1();
                String fieldKey;
                if (fieldType.isReferenceType()) {
                    try {
                        ResolvedType fieldResolvedType = fieldType.asReferenceType().resolve();
                        while (fieldResolvedType.isArray()) {
                            fieldResolvedType = fieldResolvedType.asArrayType().getComponentType();
                        }
                        if (fieldResolvedType.isReferenceType()) {
                            fieldKey = fieldResolvedType.asReferenceType().getQualifiedName() + "." + fieldName;
                        } else if (fieldResolvedType.isTypeVariable()) {
                            fieldKey = fieldResolvedType.asTypeParameter().getContainerQualifiedName() + "<" + fieldResolvedType.asTypeParameter().getName() + ">";
                        } else {
                            fieldKey = fieldName;
                        }
                    } catch (Exception e) {
                        fieldKey = fieldName;
                    }
                } else {
                    fieldKey = fieldName;
                }
                if (!fieldTypeReferences.containsKey(fieldKey)) {
                    fieldTypeReferences.put(fieldKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = fieldTypeReferences.get(fieldKey).stream().anyMatch(t -> t.getValue0().equals(variable) && t.getValue1().equals(fieldType));
                if (!isAlreadyPresent) {
                    fieldTypeReferences.get(fieldKey).add(new Pair<>(variable,fieldType));
                }
            }
        }
    }

    private static void updateHashMapOfLambdaReferencesFromIdentifiers(
            HashMap<String, List<ResolvedType>> lambdaReferences,
            HashMap<String, List<ResolvedType>> lambdaIdentifiers
    ) {
        for (Map.Entry<String,List<ResolvedType>> entry: lambdaIdentifiers.entrySet()) {
            String fieldKey = entry.getKey();
            for (ResolvedType fieldEntry: entry.getValue()) {
                if (!lambdaReferences.containsKey(fieldKey)) {
                    lambdaReferences.put(fieldKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = lambdaReferences.get(fieldKey).stream().anyMatch(t -> t.equals(fieldEntry));
                if (!isAlreadyPresent) {
                    lambdaReferences.get(fieldKey).add(fieldEntry);
                }
            }
        }
    }

    private static void updateHashMapOfMethodUsageReferencesFromIdentifiers(
            HashMap<String, List<MethodUsage>> methodUsageReferences,
            HashMap<String, List<MethodUsage>> methodUsageIdentifiers
    ) {
        for (Map.Entry<String,List<MethodUsage>> entry: methodUsageIdentifiers.entrySet()) {
            String methodName = entry.getKey();
            for (MethodUsage methodUsage: entry.getValue()) {
                String declaringTypeQualifiedName = methodUsage.declaringType().getQualifiedName();
                String methodKey = declaringTypeQualifiedName + "." + methodName;
                if (!methodUsageReferences.containsKey(methodKey)) {
                    methodUsageReferences.put(methodKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = methodUsageReferences.get(methodKey).stream().anyMatch(t -> t.equals(methodUsage));
                if (!isAlreadyPresent) {
                    methodUsageReferences.get(methodKey).add(methodUsage);
                }
            }
        }
    }

    private static void updateHashMapOfParameterReferencesFromIdentifiers(
            HashMap<String, List<ResolvedType>> parameterReferences,
            HashMap<String, List<Type>> parameterIdentifiers
    ) {
        for (Map.Entry<String,List<Type>> entry: parameterIdentifiers.entrySet()) {
            String parameterKey = entry.getKey();
            for (Type type: entry.getValue()) {
                try {
                    ResolvedType resolvedType = type.resolve();
                    while (resolvedType.isArray()) {
                        resolvedType = resolvedType.asArrayType().getComponentType();
                    }
                    if (resolvedType.isReferenceType()) {
                        String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                        parameterKey = qualifiedName + "." + parameterKey;
                    }
                    if (!parameterReferences.containsKey(parameterKey)) {
                        parameterReferences.put(parameterKey, new ArrayList<>());
                    }
                    final ResolvedType resolvedTypeToCompare = resolvedType;
                    boolean isAlreadyPresent = parameterReferences.get(parameterKey).stream().anyMatch(t -> t.equals(resolvedTypeToCompare));
                    if (!isAlreadyPresent) {
                        parameterReferences.get(parameterKey).add(resolvedType);
                    }
                } catch (Exception e) {
                    logger.error("Error resolving reference type " + type + " (from parameter " + parameterKey + "): " + e.getMessage() /*+ " - " + e.getStackTrace()*/);
                    logger.error("********************************************************************");
                    logger.error("Stack trace:");
                    for (StackTraceElement ste : e.getStackTrace()) {
                        logger.error("\tat " + ste.toString());
                    }
                    logger.error("********************************************************************");
                }
            }
        }
    }

    private static void updateHashMapOfEnumConstantReferencesFromIdentifiers(
            HashMap<String, List<EnumConstantDeclaration>> enumConstantReferences,
            HashMap<String, List<EnumConstantDeclaration>> enumConstantIdentifiers
    ) {
        for (Map.Entry<String,List<EnumConstantDeclaration>> entry: enumConstantIdentifiers.entrySet()) {
            for (EnumConstantDeclaration enumConstant: entry.getValue()) {
                String enumKey = entry.getKey();
                Optional<Node> parentNode = enumConstant.getParentNode();
                Optional<EnumDeclaration> enumDeclaration = Optional.empty();
                while(parentNode.isPresent() && !enumDeclaration.isPresent()) {
                    if (parentNode.get() instanceof EnumDeclaration) {
                        enumDeclaration = Optional.of((EnumDeclaration) parentNode.get());
                        break;
                    }
                    parentNode = parentNode.get().getParentNode();
                }
                if (enumDeclaration.isPresent()) {
                    enumKey = enumDeclaration.get().getNameAsString() + "." + enumKey;
                    if (enumDeclaration.get().asTypeDeclaration().getFullyQualifiedName().isPresent()) {
                        enumKey = enumDeclaration.get().asTypeDeclaration().getFullyQualifiedName().get() + "." + entry.getKey();
                    }
                }
                if (!enumConstantReferences.containsKey(enumKey)) {
                    enumConstantReferences.put(enumKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = enumConstantReferences.get(enumKey).stream().anyMatch(t -> t.equals(enumConstant));
                if (!isAlreadyPresent) {
                    enumConstantReferences.get(enumKey).add(enumConstant);
                }
            }
        }
    }

    private static void updateHashMapOfResolvedConstructorReferencesFromIdentifiers(
            HashMap<String, List<ResolvedConstructorDeclaration>> constructorReferences,
            HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers
    ) {
        for (Map.Entry<String,List<ResolvedConstructorDeclaration>> entry: constructorIdentifiers.entrySet()) {
            String constructorName = entry.getKey();
            for (ResolvedConstructorDeclaration constructorDeclaration: entry.getValue()) {
                String declaringTypeQualifiedName = constructorDeclaration.getQualifiedName();
                String constructorKey = declaringTypeQualifiedName;
                if (!constructorReferences.containsKey(constructorKey)) {
                    constructorReferences.put(constructorKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = constructorReferences.get(constructorKey).stream().anyMatch(t -> t.equals(constructorDeclaration));
                if (!isAlreadyPresent) {
                    constructorReferences.get(constructorKey).add(constructorDeclaration);
                }
            }
        }
    }

    public static TokenStep.ContextualInfo generateTokenStepContextualInfo(TokenReferencesCollector tokenReferences, boolean light) {
        List<Clazz> currentClasses = new ArrayList<>();
        List<Clazz> returnClasses = new ArrayList<>();
        List<Callable> methods = new ArrayList<>();
        List<Callable> constructors = new ArrayList<>();
        List<TokenStep.FieldContextualInfo> fields = new ArrayList<>();
        List<String> enumConstants = new ArrayList<>();

        if (!light) {
            for (Map.Entry<String, List<Path>> entry : tokenReferences.currentClasses.entrySet()) {
                String classNameKey = entry.getKey();
                int lastDotIndex = classNameKey.lastIndexOf('.');
                int lastDiamondIndex = classNameKey.lastIndexOf('<');
                String className = lastDiamondIndex == -1 ? classNameKey.substring(lastDotIndex + 1) : classNameKey.substring(lastDotIndex + 1, lastDiamondIndex);
                List<Path> classPaths = entry.getValue();
                for (final Path classPath : classPaths) {
                    try {
                        CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                        List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, className);
                        for (TypeDeclaration typeDeclaration : matchedTypeDeclarations) {
                            currentClasses.add(TestUtils.processTypeDeclarationForContextualInfo(typeDeclaration));
                        }
                    } catch (IOException e) {
                        logger.error("Error reading compilation unit from file: " + classPath);
                        logger.error("********************************************************************");
                        logger.error("Stack trace:");
                        for (StackTraceElement ste : e.getStackTrace()) {
                            logger.error("\tat " + ste.toString());
                        }
                        logger.error("********************************************************************");
                    }
                }
            }
        }

        for (Map.Entry<String,List<Path>> entry: tokenReferences.returnClasses.entrySet()) {
            String classNameKey = entry.getKey();
            int lastDotIndex = classNameKey.lastIndexOf('.');
            int lastDiamondIndex = classNameKey.lastIndexOf('<');
            String className = lastDiamondIndex == -1 ? classNameKey.substring(lastDotIndex + 1) : classNameKey.substring(lastDotIndex + 1, lastDiamondIndex);
            List<Path> classPaths = entry.getValue();
            for (final Path classPath: classPaths) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, className);
                    for (TypeDeclaration typeDeclaration: matchedTypeDeclarations) {
                        returnClasses.add(TestUtils.processTypeDeclarationForContextualInfo(typeDeclaration));
                    }
                } catch (IOException e) {
                    logger.error("Error reading compilation unit from file: " + classPath);
                    logger.error("********************************************************************");
                    logger.error("Stack trace:");
                    for (StackTraceElement ste : e.getStackTrace()) {
                        logger.error("\tat " + ste.toString());
                    }
                    logger.error("********************************************************************");
                }
            }
        }

        for (List<MethodUsage> methodsUsage : tokenReferences.methods.values()) {
            for (MethodUsage method : methodsUsage) {
                methods.add(TestUtils.processCallable(method));
            }
        }

        for (List<ResolvedConstructorDeclaration> constructorDeclarations : tokenReferences.constructors.values()) {
            for (ResolvedConstructorDeclaration constructor : constructorDeclarations) {
                constructors.add(TestUtils.processCallable(constructor));
            }
        }

        for (List<Pair<VariableDeclarator,Type>> fieldPairs : tokenReferences.fields.values()) {
            for (Pair<VariableDeclarator,Type> field : fieldPairs) {
                VariableDeclarator variableDeclarator = field.getValue0();
                Type fieldType = field.getValue1();
                while (fieldType.isArrayType()) {
                    fieldType = fieldType.asArrayType().getComponentType();
                }
                if (fieldType.isReferenceType()) {
                    try {
                        Optional<Node> fieldTypeDeclaration = fieldType.asReferenceType().resolve().asReferenceType().getTypeDeclaration().get().toAst();
                        if (fieldTypeDeclaration.isPresent() && fieldTypeDeclaration.get() instanceof TypeDeclaration) {
                            TypeDeclaration typeDeclaration = (TypeDeclaration) fieldTypeDeclaration.get();

                            Optional<Node> fieldNode = variableDeclarator.getParentNode();
                            Optional<FieldDeclaration> fieldDeclaration = Optional.empty();

                            while (fieldNode.isPresent() && !(fieldNode.get() instanceof  FieldDeclaration)) {
                                fieldNode = fieldNode.get().getParentNode();
                            }

                            if (fieldNode.isPresent() && fieldNode.get() instanceof FieldDeclaration) {
                                fieldDeclaration = Optional.of((FieldDeclaration) fieldNode.get());
                            }
                            List<String> fieldModifiers = fieldDeclaration.isPresent() ? JavaParserUtils.getModifiersAsStrings(fieldDeclaration.get().getModifiers()) : new ArrayList<>();
                            //Path classFilePath = typeDeclaration.getFullyQualifiedName().isPresent() ? repoSourcePath.resolve(FilesUtils.getFQNPath((String) typeDeclaration.getFullyQualifiedName().get())) : Path.of("");
                            TokenStep.FieldContextualInfo fieldContextualInfo = new TokenStep.FieldContextualInfo(
                                    variableDeclarator.getNameAsString(),
                                    JavaParserUtils.getSignatureFromVariableDeclarator(fieldModifiers, variableDeclarator),
                                    variableDeclarator.toString(),
                                    fieldModifiers,
                                    TestUtils.processTypeDeclarationForContextualInfo(typeDeclaration)
                            );
                            fields.add(fieldContextualInfo);
                        }
                    } catch (Exception e) {
                        String message = "Error processing field type declaration for field: " + variableDeclarator.getNameAsString() + " - " + e.getMessage();
                        logger.error(message);
                        logger.error("********************************************************************");
                        logger.error("Stack trace:");
                        for (StackTraceElement ste : e.getStackTrace()) {
                            logger.error("\tat " + ste.toString());
                        }
                        logger.error("********************************************************************");
                    }
                }
            }
        }

        for (List<EnumConstantDeclaration> enumConstantDeclarations : tokenReferences.enums.values()) {
            for (EnumConstantDeclaration enumConstant : enumConstantDeclarations) {
                enumConstants.add(enumConstant.getNameAsString());
            }
        }

        return new TokenStep.ContextualInfo(
                tokenReferences.contextualInfoType,
                currentClasses,
                returnClasses,
                methods,
                constructors,
                fields,
                enumConstants
        );
    }

    public static Pair<TokenIdentifiersCollector, TokenReferencesCollector> updateTokenIdentifiersAndReferences(
            List<Pair<TokenIdentifiersCollector, TokenReferencesCollector>> tokenIdentifiersStack,
            final String token,
            final String previousToken,
            TypeDeclaration tc,
            String testPrefixName,
            String targetAssertion
    ) {
        // Get the action associated with the current token
        AntlrTokenAction tokenAction = AntlrTokenMapper.getTokenAction(token);
        // Get the type of the current token and the previous token
        AntlrToken tokenType = Antlr4Utils.getAntlrToken(token);
        AntlrToken previousTokenType = Antlr4Utils.getAntlrToken(previousToken);
        // Retrieve the current token identifiers and references collectors from the top of the stack
        Pair<TokenIdentifiersCollector, TokenReferencesCollector> currentTokenIdentifiersAndReferences = tokenIdentifiersStack.get(tokenIdentifiersStack.size() - 1);
        TokenIdentifiersCollector tokenIdentifiersCollector = currentTokenIdentifiersAndReferences.getValue0();
        TokenReferencesCollector tokenReferences = currentTokenIdentifiersAndReferences.getValue1();
        // Define the hash maps to collect the updated identifiers and references
        HashMap<String, List<Path>> classIdentifiers = new HashMap<>();
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers = new HashMap<>();
        HashMap<String, List<Type>> parameterIdentifiers = new HashMap<>();
        HashMap<String, List<ResolvedType>> lambdaIdentifiers = new HashMap<>();
        HashMap<String, List<MethodUsage>> methodIdentifiers = new HashMap<>();
        HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers = new HashMap<>();
        HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers = new HashMap<>();
        HashMap<String, List<Path>> currentClassReferences = new HashMap<>();
        HashMap<String, List<Path>> returnClassReferences = new HashMap<>();
        HashMap<String, List<ResolvedType>> typeParameterReferences = new HashMap<>();
        HashMap<String, List<ResolvedType>> parameterReferences = new HashMap<>();
        HashMap<String, List<ResolvedType>> lambdaReferences = new HashMap<>();
        HashMap<String,List<Pair<VariableDeclarator,Type>>> fieldReferences = new HashMap<>();
        HashMap<String, List<MethodUsage>> methodReferences = new HashMap<>();
        HashMap<String, List<ResolvedConstructorDeclaration>> constructorReferences = new HashMap<>();
        HashMap<String, List<EnumConstantDeclaration>> enumReferences = new HashMap<>();
        TokenIdentifiersCollector newTokenIdentifiersCollector = cloneRootTokenIdentifiers();
        TokenReferencesCollector newTokenReferencesCollector = cloneRootTokenReferences();
        // Update the token identifiers and references collectors based on the action associated with the current token
        switch (tokenAction) {
            case ADD_ROOT:
                if (tokenType == AntlrToken.LPAREN && previousTokenType == AntlrToken.IDENTIFIER) {
                    boolean methodsMatch = tokenReferences.methods.values().stream().anyMatch(l -> l.stream().anyMatch(m -> m.getName().equals(previousToken)));
                    boolean fieldsMatch = tokenReferences.fields.values().stream().anyMatch(l -> l.stream().anyMatch(f -> f.getValue0().getNameAsString().equals(previousToken)));
                    boolean constructorsMatch = tokenReferences.constructors.values().stream().anyMatch(l -> l.stream().anyMatch(c -> c.getName().equals(previousToken)));
                    if (methodsMatch || fieldsMatch) {
                        if (streamFlag) {
                            tokenReferences.setContextualInfoType(ContextualInfoType.LAMBDA_CALL);
                        } else {
                            tokenReferences.setContextualInfoType(ContextualInfoType.METHOD_CALL);
                        }
                    } else if (constructorsMatch) {
                        tokenReferences.setContextualInfoType(ContextualInfoType.CONSTRUCTOR_CALL);
                    }
                }
                if (streamFlag) {
                    newTokenIdentifiersCollector.setLambdaIdentifiersMap(tokenIdentifiersCollector.lambdaIdentifiers);
                    newTokenReferencesCollector.setLambdaReferences(tokenReferences.lambdaReferences);
                    newTokenReferencesCollector.setTypeParameterReferences(tokenReferences.typeParametersReferences);
                }
                tokenIdentifiersStack.add(new Pair<>(newTokenIdentifiersCollector, newTokenReferencesCollector));
                break;
            case SET_TO_ROOT:
                // Reset the current token identifiers and references collectors to the root state
                tokenIdentifiersStack.remove(currentTokenIdentifiersAndReferences);
                tokenIdentifiersStack.add(new Pair<>(newTokenIdentifiersCollector, newTokenReferencesCollector));
                if (tokenType == AntlrToken.ARROW) {
                    streamFlag = false;
                    tokenReferences.setContextualInfoType(ContextualInfoType.LAMBDA_BODY);
                    newTokenIdentifiersCollector.setLambdaIdentifiersMap(tokenReferences.lambdaReferences);
                }
                break;
            case KEEP_CURRENT:
                // Do nothing, keep the current token identifiers and references collectors unless the token is a dot that
                // access the type of a field
                if (tokenType == AntlrToken.DOT && (tokenReferences.contextualInfoType == ContextualInfoType.FIELD_CALL || tokenReferences.contextualInfoType == ContextualInfoType.PARAMETER_CALL)) {
                    processReturnClass(
                            tc,
                            tokenReferences,
                            fieldIdentifiers,
                            methodIdentifiers,
                            constructorIdentifiers,
                            enumIdentifiers,
                            fieldReferences,
                            methodReferences,
                            constructorReferences,
                            enumReferences
                    );
                    currentClassReferences = tokenReferences.returnClasses;
                    tokenReferences.setCurrentClasses(currentClassReferences);
                    tokenReferences.setReturnClasses(new HashMap<>());
                    tokenReferences.setMethods(methodReferences);
                    tokenReferences.setFields(fieldReferences);
                    tokenReferences.setConstructors(constructorReferences);
                    tokenReferences.setEnums(enumReferences);
                    if (tokenReferences.contextualInfoType == ContextualInfoType.FIELD_CALL) {
                        tokenReferences.setContextualInfoType(ContextualInfoType.FIELD_RETURN);
                    } else {
                        tokenReferences.setContextualInfoType(ContextualInfoType.PARAMETER_RETURN);
                    }
                }
                break;
            case REMOVE_CURRENT:
                // Pop the current token identifiers and references collectors from the stack
                tokenIdentifiersStack.remove(currentTokenIdentifiersAndReferences);
                if (tokenType == AntlrToken.RPAREN) {
                    tokenReferences = tokenIdentifiersStack.get(tokenIdentifiersStack.size() - 1).getValue1();
                    if (tokenReferences.contextualInfoType == ContextualInfoType.CONSTRUCTOR_CALL) {
                        tokenReferences.setContextualInfoType(ContextualInfoType.CONSTRUCTOR_RETURN);
                        tokenReferences.setConstructors(new HashMap<>());
                    } else if (tokenReferences.contextualInfoType == ContextualInfoType.METHOD_CALL) {
                        tokenReferences.setContextualInfoType(ContextualInfoType.METHOD_RETURN);
                        tokenReferences.setMethods(new HashMap<>());
                    }
                }
                if (
                        tokenReferences.contextualInfoType == ContextualInfoType.METHOD_RETURN ||
                        tokenReferences.contextualInfoType == ContextualInfoType.CONSTRUCTOR_RETURN
                ) {
                    processReturnClass(
                            tc,
                            tokenReferences,
                            fieldIdentifiers,
                            methodIdentifiers,
                            constructorIdentifiers,
                            enumIdentifiers,
                            fieldReferences,
                            methodReferences,
                            constructorReferences,
                            enumReferences
                    );
                    currentClassReferences = tokenReferences.returnClasses;
                    tokenReferences.setCurrentClasses(currentClassReferences);
                    tokenReferences.setReturnClasses(new HashMap<>());
                    tokenReferences.setMethods(methodReferences);
                    tokenReferences.setFields(fieldReferences);
                    tokenReferences.setConstructors(constructorReferences);
                    tokenReferences.setEnums(enumReferences);
                    if (currentClassReferences.containsKey(streamClassReference)) {
                        streamFlag = true;
                        if (tokenReferences.contextualInfoType == ContextualInfoType.LAMBDA_BODY) {
                            tokenReferences.setContextualInfoType(ContextualInfoType.LAMBDA_RETURN);
                            tokenReferences.setLambdaReferences(new HashMap<>());
                        }
                    }
                }
                break;
            case UPDATE_CURRENT:
                switch (tokenType){
                    case ABSTRACT:
                    case BOOLEAN:
                    case BREAK:
                    case BYTE:
                    case CATCH:
                    case CHAR:
                    case CLASS:
                    case CONST:
                    case CONTINUE:
                    case DEFAULT:
                    case DO:
                    case DOUBLE:
                    case ELSE:
                    case ENUM:
                    case FINAL:
                    case FINALLY:
                    case FLOAT:
                    case FOR:
                    case IF:
                    case GOTO:
                    case INT:
                    case LONG:
                    case PACKAGE:
                    case SHORT:
                    case TRY:
                    case VOID:
                    case WHILE:
                    case MODULE:
                    case OPEN:
                    case REQUIRES:
                    case EXPORTS:
                    case INTERFACE:
                    case IMPORT:
                    case OPENS:
                    case TO:
                    case USES:
                    case PROVIDES:
                    case WITH:
                    case TRANSITIVE:
                    case VAR:
                    case RECORD:
                    case SEALED:
                    case NON_SEALED:
                    case DECIMAL_LITERAL:
                    case HEX_LITERAL:
                    case OCT_LITERAL:
                    case BINARY_LITERAL:
                    case FLOAT_LITERAL:
                    case HEX_FLOAT_LITERAL:
                    case BOOL_LITERAL:
                    case CHAR_LITERAL:
                    case STRING_LITERAL: //TODO: consider to add candidate next tokens for string (equals methods, etc.)
                    case TEXT_BLOCK:
                    case NULL_LITERAL:
                        // In case of unusual unexpected tokens within the oracle expression, remove all the references
                        // cause no classes, methods, fields or constructors can be suggested in this state
                        tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(new HashMap<>());
                        tokenReferences.setContextualInfoType(ContextualInfoType.DEFINITION);
                        tokenReferences.setCurrentClasses(new HashMap<>());
                        tokenReferences.setReturnClasses(new HashMap<>());
                        tokenReferences.setMethods(new HashMap<>());
                        tokenReferences.setFields(new HashMap<>());
                        tokenReferences.setConstructors(new HashMap<>());
                        tokenReferences.setEnums(new HashMap<>());
                        break;
                    case IMPLEMENTS:
                    case INSTANCEOF:
                    case NATIVE:
                    case NEW:
                    case EXTENDS:
                    case PRIVATE:
                    case PROTECTED:
                    case PUBLIC:
                    case RETURN:
                    case STATIC:
                    case STRICTFP:
                    case SYNCHRONIZED:
                    case TRANSIENT:
                    case VOLATILE:
                        // These tokens can only be associated with declarations, so no classes, methods, fields or constructors
                        // should be suggested in this state
                        tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(new HashMap<>());
                        tokenReferences.setContextualInfoType(ContextualInfoType.DEFINITION);
                        tokenReferences.setReturnClasses(new HashMap<>());
                        tokenReferences.setMethods(new HashMap<>());
                        tokenReferences.setConstructors(new HashMap<>());
                        tokenReferences.setFields(new HashMap<>());
                        tokenReferences.setEnums(new HashMap<>());
                        break;
                    case THIS:
                        try {
                            // Retrieve the private, protected and public fields defined within the test class
                            updateHashMapOfFieldTypeIdentifiers(fieldIdentifiers, tc.getFields(), new ArrayList<>());
                            // Retrieve the private, protected and public methods defined within the test class (excluding test methods)
                            updateHashMapOfMethodUsageIdentifiers(methodIdentifiers, tc, new ArrayList<>(), List.of(new Pair("Test", Boolean.valueOf(false))));
                            // This keyword excludes constructors
                            constructorIdentifiers = new HashMap<>();
                            // Retrieve the enum constants defined within the test class (in case)
                            updateHashMapOfEnumConstantDeclarationIdentifiers(enumIdentifiers, tc);
                        } catch (Exception e) {
                            String message = "Error collecting variable type identifiers for test case: " + testPrefixName + " - " + e.getMessage();
                            logger.error(message);
                            clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(e.getMessage());
                            logger.error("********************************************************************");
                            logger.error("Stack trace:");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                logger.error("\tat " + ste.toString());
                            }
                            logger.error("********************************************************************");
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setMethodIdentifiersMap(methodIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(constructorIdentifiers);
                        tokenIdentifiersCollector.setFieldIdentifiersMap(fieldIdentifiers);
                        tokenIdentifiersCollector.setEnumIdentifiersMap(enumIdentifiers);
                        tokenReferences.setContextualInfoType(ContextualInfoType.THIS);
                        currentClassReferences.put(tc.getFullyQualifiedName().isPresent() ? (String) tc.getFullyQualifiedName().get() : tc.getNameAsString(), List.of(testPrefixClassPath));
                        tokenReferences.setCurrentClasses(currentClassReferences);
                        updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                        tokenReferences.setFields(fieldReferences);
                        updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                        tokenReferences.setMethods(methodReferences);
                        updateHashMapOfResolvedConstructorReferencesFromIdentifiers(constructorReferences, constructorIdentifiers);
                        tokenReferences.setConstructors(constructorIdentifiers);
                        updateHashMapOfEnumConstantReferencesFromIdentifiers(enumReferences, enumIdentifiers);
                        tokenReferences.setEnums(enumReferences);
                        break;
                    case THROW:
                    case THROWS:
                        // In case of "throw" or "throws", we are likely dealing with exceptions, so we need to filter the
                        // collected class identifiers to keep only the ones that extend Exception or one of its subclasses
                        // No methods, fields, constructors or enums should be suggested in this state
                        HashMap<String, List<Path>> typeDeclarationPathIdentifiers = new HashMap<>();
                        for (String key: tokenIdentifiersCollector.classPathIdentifiers.keySet()) {
                            List<Path> keyValues = tokenIdentifiersCollector.classPathIdentifiers.get(key);
                            // Iterate over the list of paths to check if the class extends Exception or one of its subclasses
                            for (Path path: keyValues) {
                                try {
                                    // Get the compilation unit of the matched class
                                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(path);
                                    // Get the type declarations from the compilation unit that match the token
                                    for (TypeDeclaration typeDeclaration: cu.getTypes()) {
                                        String fullyQualifiedName = (String) typeDeclaration.getFullyQualifiedName().orElse(typeDeclaration.getNameAsString());
                                        if (fullyQualifiedName.equals(key)) {
                                            if (typeDeclaration instanceof ClassOrInterfaceDeclaration) {
                                                // Check if the class extends Exception or one of its subclasses
                                                List<ClassOrInterfaceType> extendedTypes = ((ClassOrInterfaceDeclaration) typeDeclaration).getExtendedTypes();
                                                if (extendedTypes.stream().anyMatch(t -> t.getNameAsString().endsWith("Exception"))) {
                                                    // If the class extends Exception or one of its subclasses, add it to the filtered map
                                                    if (!typeDeclarationPathIdentifiers.containsKey(key)) {
                                                        typeDeclarationPathIdentifiers.put(key, new ArrayList<>());
                                                    }
                                                    boolean isAlreadyPresent = typeDeclarationPathIdentifiers.get(key).stream().anyMatch(p -> p.toString().equals(path.toString()));
                                                    if (!isAlreadyPresent) {
                                                        typeDeclarationPathIdentifiers.get(key).add(path);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (IOException e) {
                                    logger.error("Error reading compilation unit from file: " + path);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                    if (!typeDeclarationPathIdentifiers.containsKey(key)) {
                                        typeDeclarationPathIdentifiers.put(key, new ArrayList<>());
                                    }
                                    boolean isAlreadyPresent = typeDeclarationPathIdentifiers.get(key).stream().anyMatch(p -> p.toString().equals(path.toString()));
                                    if (!isAlreadyPresent) {
                                        typeDeclarationPathIdentifiers.get(key).add(path);
                                    }
                                }
                            }
                        }
                        // Set the filtered class identifiers map and clear all other maps
                        tokenIdentifiersCollector.setClassIdentifiersMap(typeDeclarationPathIdentifiers);
                        tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(new HashMap<>());
                        tokenReferences.setContextualInfoType(ContextualInfoType.THROW);
                        // Set the filtered class references and clear all other references
                        tokenReferences.setCurrentClasses(typeDeclarationPathIdentifiers);
                        tokenReferences.setReturnClasses(new HashMap<>());
                        tokenReferences.setFields(new HashMap<>());
                        tokenReferences.setMethods(new HashMap<>());
                        tokenReferences.setConstructors(new HashMap<>());
                        tokenReferences.setEnums(new HashMap<>());
                        break;
                    case IDENTIFIER:
                        // Check if the token matches any of the collected identifiers
                        boolean classesMatch = tokenIdentifiersCollector.classPathIdentifiers.containsKey(token);
                        boolean methodsMatch = tokenIdentifiersCollector.methodIdentifiers.containsKey(token);
                        boolean fieldsMatch = tokenIdentifiersCollector.fieldIdentifiers.containsKey(token);
                        boolean parametersMatch = tokenIdentifiersCollector.parameterIdentifiers.containsKey(token);
                        boolean lambdasMatch = tokenIdentifiersCollector.lambdaIdentifiers.containsKey(token);
                        ContextualInfoType contextualInfoType = ContextualInfoType.IDENTIFIER;
                        List<ResolvedType> resolvedTypes = new ArrayList<>();

                        int matchCount = 0;
                        if (classesMatch) matchCount++;
                        if (methodsMatch) matchCount++;
                        if (fieldsMatch) matchCount++;
                        if (lambdasMatch) matchCount++;
                        if (parametersMatch) matchCount++;
                        // If the token does not match any identifier, clear all maps to avoid suggesting irrelevant tokens
                        // TODO: Manage tokens that could refer to lambda expressions or generics
                        if (!classesMatch && !methodsMatch && !fieldsMatch && !lambdasMatch && !parametersMatch) {
                            tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setLambdaIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setEnumIdentifiersMap(new HashMap<>());
                            tokenReferences.setCurrentClasses(new HashMap<>());
                            tokenReferences.setReturnClasses(new HashMap<>());
                            tokenReferences.setMethods(new HashMap<>());
                            tokenReferences.setConstructors(new HashMap<>());
                            tokenReferences.setEnums(new HashMap<>());
                            tokenReferences.setFields(new HashMap<>());
                            tokenReferences.setLambdaReferences(new HashMap<>());
                            if (streamFlag) {
                                String streamTypeParameterKey = streamClassReference + "<" + streamTypeParameter + ">";
                                for (String key: tokenReferences.typeParametersReferences.keySet()) {
                                    if (key.startsWith(streamClassReference)) {
                                        streamTypeParameterKey = key;
                                    }
                                }
                                if (tokenReferences.typeParametersReferences.containsKey(streamTypeParameterKey)) {
                                    if (!lambdaReferences.containsKey(token)) {
                                        lambdaReferences.put(token, new ArrayList<>());
                                    }
                                    List<ResolvedType> lambdaParametersTypes = tokenReferences.typeParametersReferences.get(streamTypeParameterKey);
                                    for(ResolvedType lambdaParameterType : lambdaParametersTypes) {
                                        boolean isAlreadyPresent = lambdaReferences.get(token).stream().anyMatch(p -> p.equals(lambdaParameterType));
                                        if (!isAlreadyPresent) {
                                            lambdaReferences.get(token).add(lambdaParameterType);
                                        }
                                    }
                                    tokenReferences.setLambdaReferences(lambdaReferences);
                                    resolvedTypes.addAll(lambdaParametersTypes);
                                    for (ResolvedType lambdaParameterType :lambdaParametersTypes) {
                                        if (lambdaParameterType.isReferenceType()) {
                                            String qualifiedName = lambdaParameterType.asReferenceType().getQualifiedName();
                                            Optional<Path> lambdaParameterTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoSourcePath);
                                            if (!lambdaParameterTypePath.isPresent()) {
                                                lambdaParameterTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoTestPath);
                                            }
                                            if (lambdaParameterTypePath.isPresent()) {
                                                if (!currentClassReferences.containsKey(qualifiedName)) {
                                                    currentClassReferences.put(qualifiedName, new ArrayList<>());
                                                }
                                                final Path lambdaParameterTypePathToCompare = lambdaParameterTypePath.get();
                                                boolean isAlreadyPresent = currentClassReferences.get(qualifiedName).stream().anyMatch(p -> p.toString().equals(lambdaParameterTypePathToCompare.toString()));
                                                if (!isAlreadyPresent) {
                                                    currentClassReferences.get(qualifiedName).add(lambdaParameterTypePath.get());
                                                }
                                            } else {
                                                logger.error("Declaring type path not found for while processing a lambda parameter (" + token + "): " + qualifiedName);

                                            }
                                        }
                                    }
                                    tokenReferences.setCurrentClasses(currentClassReferences);
                                } else {
                                    tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                                }
                            } else {
                                tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                            }
                            break;
                        }

                        if (matchCount > 1) {
                            String message = "Multiple identifier matches for token: " + token + " (classes (or constructors): " + classesMatch + ", methods: " + methodsMatch + ", fields: " + fieldsMatch + ", lambdas: " + lambdasMatch + ")";
                            logger.warn(message);
                            clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                        }

                        // If the token matches a class name, collect its members (fields and methods or constructors)
                        if (classesMatch) {
                            // Get the list of class paths for the matched class name
                            List<Path> classPaths = tokenIdentifiersCollector.classPathIdentifiers.get(token);
                            // Get the compilation unit of the test class to get its package declaration
                            // It's important to collect protected members from classes in the same package
                            CompilationUnit testCu = (CompilationUnit) tc.getParentNode().get();

                            for (final Path classPath: classPaths) {
                                try {
                                    // Get the compilation unit of the matched class
                                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                                    // Get the type declarations from the compilation unit that match the token
                                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, token);
                                    // Get the package declaration of the matched class and the test class
                                    Optional<PackageDeclaration> matchedPackageDeclaration = cu.getPackageDeclaration();
                                    Optional<PackageDeclaration> testPackageDeclaration = testCu.getPackageDeclaration();
                                    // For each matched type declaration, collect its inner types as possible next class identifiers
                                    for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                                        // Set up an initial list of modifiers rules for searching inner class members
                                        List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>();
                                        modifiers.add(List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))));
                                        modifiers.add(List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))));
                                        // Check if the matched class is in the same package as the test class
                                        if (matchedPackageDeclaration.isPresent() && testPackageDeclaration.isPresent()) {
                                            String matchedPackageName = matchedPackageDeclaration.get().getNameAsString();
                                            String testPackageName = testPackageDeclaration.get().getNameAsString();
                                            // If the matched class is in the same package as the test class, add protected members to the search
                                            if (testPackageName.equals(matchedPackageName)) {
                                                modifiers.add(List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))));
                                            }
                                        }
                                        // Add the inner types as possible next class identifiers
                                        targetTypeDeclaration.getMembers().stream()
                                                .filter(m -> ((BodyDeclaration) m).isTypeDeclaration())
                                                .forEach(t -> classIdentifiers.put(((TypeDeclaration) t).getNameAsString(), List.of(classPath)));
                                        // Collect the references to the matched class
                                        String fullyQualifiedName = (String) targetTypeDeclaration.getFullyQualifiedName().orElse(targetTypeDeclaration.getNameAsString());
                                        if (!currentClassReferences.containsKey(fullyQualifiedName)) {
                                            currentClassReferences.put(fullyQualifiedName, new ArrayList<>());
                                        }
                                        boolean isAlreadyPresent = currentClassReferences.get(fullyQualifiedName).stream().anyMatch(p -> p.toString().equals(classPath.toString()));
                                        if (!isAlreadyPresent) {
                                            currentClassReferences.get(fullyQualifiedName).add(classPath);
                                        }
                                    }
                                    // If the previous token is "new", we are likely dealing with a constructor call
                                    if (previousTokenType == AntlrToken.NEW) {
                                        for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                                            // Collect the constructors from the matched type declaration according to the modifiers rules
                                            updateHashMapOfResolvedConstructorDeclarationIdentifiers(constructorIdentifiers, targetTypeDeclaration);
                                            updateHashMapOfResolvedConstructorReferencesFromIdentifiers(constructorReferences, constructorIdentifiers);
                                        }
                                    }
                                    // If the previous token is not "new", we are likely dealing with a class reference
                                    // Iterate over the matched type declarations and collect their members,
                                    // according to the modifiers rules
                                    for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                                        // Set up an initial list of modifiers rules for searching field and method members
                                        List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>();
                                        modifiers.add(List.of(
                                                new Pair(Modifier.Keyword.STATIC, Boolean.valueOf(true)),
                                                new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))
                                        ));
                                        modifiers.add(List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))));
                                        // Check if the matched class is in the same package as the test class
                                        if (matchedPackageDeclaration.isPresent() && testPackageDeclaration.isPresent()) {
                                            String matchedPackageName = matchedPackageDeclaration.get().getNameAsString();
                                            String testPackageName = testPackageDeclaration.get().getNameAsString();
                                            // If the matched class is in the same package as the test class, add protected members to the search
                                            if (testPackageName.equals(matchedPackageName)) {
                                                modifiers.add(List.of(
                                                        new Pair(Modifier.Keyword.STATIC, Boolean.valueOf(true)),
                                                        new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))
                                                ));
                                            }
                                        }
                                        // Add all possible enum constant declarations if the type is an enum
                                        updateHashMapOfEnumConstantDeclarationIdentifiers(enumIdentifiers, targetTypeDeclaration);
                                        updateHashMapOfEnumConstantReferencesFromIdentifiers(enumReferences, enumIdentifiers);
                                        // Collect method usages and field type identifiers from the matched type declaration
                                        // Methods and fields must be static and public or protected (if in the same package)
                                        updateHashMapOfMethodUsageIdentifiers(methodIdentifiers, targetTypeDeclaration, modifiers, List.of(new Pair("Test", Boolean.valueOf(false))));
                                        updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                                        updateHashMapOfFieldTypeIdentifiers(fieldIdentifiers, targetTypeDeclaration.getFields(), modifiers);
                                        updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                                    }
                                } catch (IOException e) {
                                    logger.error("Error reading compilation unit from file: " + classPath);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                }
                            }
                            tokenReferences.setReturnClasses(new HashMap<>());
                            contextualInfoType = previousTokenType == AntlrToken.NEW ? ContextualInfoType.CONSTRUCTOR_CALL : ContextualInfoType.CLASS_CALL;
                        }

                        if (lambdasMatch) {
                            List<ResolvedType> lambdaFields = tokenIdentifiersCollector.lambdaIdentifiers.get(token);
                            for (ResolvedType lambdaField: lambdaFields) {
                                try {
                                    resolvedTypes.add(lambdaField);

                                    if (lambdaField.isReferenceType()) {
                                        String qualifiedName = lambdaField.asReferenceType().getQualifiedName();

                                        if (!currentClassReferences.containsKey(qualifiedName)) {
                                            Optional<Path> declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoSourcePath);
                                            if (!declaringTypePath.isPresent()) {
                                                declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoTestPath);
                                            }
                                            if (declaringTypePath.isPresent()) {
                                                currentClassReferences.put(qualifiedName, List.of(declaringTypePath.get()));
                                            }
                                        }

                                        List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  lambdaField.asReferenceType().getTypeParametersMap();

                                        for(com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameterPair: typeParameters) {
                                            ResolvedTypeParameterDeclaration typeParameterDeclaration = typeParameterPair.a;
                                            ResolvedType typeParameterValue = typeParameterPair.b;
                                            String typeParameterKey = typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">";
                                            if(typeParameterValue.isTypeVariable()) {
                                                String typeParameterValueKey = typeParameterValue.asTypeParameter().getContainerQualifiedName() + "<" + typeParameterValue.asTypeParameter().getName() + ">";
                                                if (tokenReferences.typeParametersReferences.containsKey(typeParameterValueKey)) {
                                                    typeParameterValue = tokenReferences.typeParametersReferences.get(typeParameterValueKey).get(0);
                                                }
                                            }
                                            if (!typeParameterReferences.containsKey(typeParameterKey)) {
                                                typeParameterReferences.put(typeParameterKey, new ArrayList<>());
                                            }
                                            final ResolvedType typeParameterValueToCompare = typeParameterValue;
                                            boolean isAlreadyPresent = typeParameterReferences.get(typeParameterKey).stream().anyMatch(t -> t.toString().equals(typeParameterValueToCompare.toString()));
                                            if (!isAlreadyPresent) {
                                                typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Error resolving reference type from lambda field: " + lambdaField);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                }
                            }
                            updateHashMapOfLambdaReferencesFromIdentifiers(lambdaReferences, lambdaIdentifiers);
                        }

                        if (methodsMatch) {
                            // Get the list of method usages for the matched method name
                            List<MethodUsage> methodUsages = tokenIdentifiersCollector.methodIdentifiers.get(token);
                            methodReferences.put(token, methodUsages);
                            // Collect the return types of the matched methods as possible next class identifiers
                            for (MethodUsage methodUsage: methodUsages) {
                                // Get the return type of the method usage
                                ResolvedType resolvedType = methodUsage.getDeclaration().getReturnType();
                                boolean isAlreadyPresent = resolvedTypes.stream().anyMatch(r -> r.equals(resolvedType));
                                if (!isAlreadyPresent) {
                                    resolvedTypes.add(resolvedType);
                                    // Get the declaring type of the method usage
                                    ResolvedReferenceTypeDeclaration declaringType = methodUsage.getDeclaration().declaringType();
                                    String declaringTypeQualifiedName = declaringType.getQualifiedName();
                                    List<ResolvedReferenceType> ancestors = declaringType.asReferenceType().getAllAncestors();
                                    processDeclaringType(tokenReferences, declaringTypeQualifiedName, currentClassReferences, typeParameterReferences, resolvedType, ancestors);
                                }
                            }
                            // Collect the references to the matched methods
                            updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                            contextualInfoType = ContextualInfoType.METHOD_CALL;
                        }

                        if (fieldsMatch || parametersMatch) {
                            if (fieldsMatch) {
                                List<Pair<VariableDeclarator,Type>> fields = tokenIdentifiersCollector.fieldIdentifiers.get(token);
                                HashMap<String, List<Pair<VariableDeclarator, Type>>> tempFieldIdentifiers = new HashMap<>();
                                tempFieldIdentifiers.put(token, fields);
                                for (Pair<VariableDeclarator,Type> field: fields) {
                                    try {
                                        VariableDeclarator variableDeclarator = field.getValue0();
                                        Type varType = field.getValue1();
                                        ResolvedType resolvedType = varType.resolve();
                                        resolvedTypes.add(resolvedType);
                                        Optional<Node> parentNode = variableDeclarator.getParentNode();
                                        Optional<TypeDeclaration> declaringTypeDeclaration = Optional.empty();

                                        while (parentNode.isPresent() && declaringTypeDeclaration.isEmpty()) {
                                            if (parentNode.get() instanceof TypeDeclaration) {
                                                declaringTypeDeclaration = Optional.of((TypeDeclaration) parentNode.get());
                                                break;
                                            }
                                            parentNode = parentNode.get().getParentNode();
                                        }

                                        if (declaringTypeDeclaration.isPresent()) {
                                            TypeDeclaration typeDeclaration = declaringTypeDeclaration.get();
                                            String declaringTypeQualifiedName = typeDeclaration.getNameAsString();
                                            if (typeDeclaration.getFullyQualifiedName().isPresent()) {
                                                declaringTypeQualifiedName = (String) typeDeclaration.getFullyQualifiedName().get();
                                            }
                                            List<ResolvedReferenceType> ancestors = new ArrayList<>();
                                            try {
                                                ancestors = typeDeclaration.resolve().getAllAncestors();
                                            } catch (Exception e) {
                                                String message = "Unable to resolve test class in the form of type declaration: " + testPrefixClassPath;
                                                logger.error(message);
                                                clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                                logger.error("********************************************************************");
                                                logger.error("Stack trace:");
                                                for (StackTraceElement ste : e.getStackTrace()) {
                                                    logger.error("\tat " + ste.toString());
                                                }
                                                logger.error("********************************************************************");
                                            }
                                            processDeclaringType(tokenReferences, declaringTypeQualifiedName, currentClassReferences, typeParameterReferences, resolvedType, ancestors);
                                        }
                                    }  catch (Exception e) {
                                        String message = "Unable to process field " + token;
                                        logger.error(message);
                                        clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                        logger.error("********************************************************************");
                                        logger.error("Stack trace:");
                                        for (StackTraceElement ste : e.getStackTrace()) {
                                            logger.error("\tat " + ste.toString());
                                        }
                                        logger.error("********************************************************************");
                                    }
                                    contextualInfoType = ContextualInfoType.FIELD_CALL;
                                    updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, tempFieldIdentifiers);
                                }
                            } else {
                                if (tokenIdentifiersCollector.parameterIdentifiers.containsKey(token)) {
                                    String parameterKey = token;
                                    List<Type> parameterTypes = tokenIdentifiersCollector.parameterIdentifiers.get(token);
                                    for (Type type: parameterTypes) {
                                        try {
                                            ResolvedType resolvedType = type.resolve();
                                            while (resolvedType.isArray()) {
                                                resolvedType = resolvedType.asArrayType().getComponentType();
                                            }
                                            if (resolvedType.isReferenceType()) {
                                                String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                                                parameterKey = qualifiedName + "." + parameterKey;
                                            }
                                            resolvedTypes.addAll(tokenReferences.parametersReferences.get(parameterKey));
                                        } catch (Exception e) {
                                            String message = "Unable to retrieve parameter reference from qualified name, for parameter " + token;
                                            logger.error(message);
                                            clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                            logger.error("********************************************************************");
                                            logger.error("Stack trace:");
                                            for (StackTraceElement ste : e.getStackTrace()) {
                                                logger.error("\tat " + ste.toString());
                                            }
                                            logger.error("********************************************************************");
                                        }
                                    }
                                } else {
                                    String message = "Unexpected parameter " + token + " not found in parameter reference hashmap.";
                                    logger.error(message);
                                    clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                }
                                currentClassReferences.put(tc.getFullyQualifiedName().isPresent() ? (String) tc.getFullyQualifiedName().get() : tc.getNameAsString(), List.of(testPrefixClassPath));
                                HashMap<String, List<Type>> tempParamtersIdentifiers = new HashMap<>();
                                tempParamtersIdentifiers.put(token, tokenIdentifiersCollector.parameterIdentifiers.containsKey(token) ? tokenIdentifiersCollector.parameterIdentifiers.get(token) : new ArrayList<>());
                                contextualInfoType = ContextualInfoType.PARAMETER_CALL;
                                updateHashMapOfParameterReferencesFromIdentifiers(parameterReferences,tempParamtersIdentifiers);
                            }
                        }


                        for (ResolvedType resolvedType: resolvedTypes) {
                            List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>(List.of(
                                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))),
                                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(false)))
                            ));
                            while (resolvedType.isArray()) {
                                resolvedType = resolvedType.asArrayType().getComponentType();
                            }
                            if (resolvedType.isTypeVariable()) {
                                String resolvedTypeKey = resolvedType.asTypeParameter().getContainerQualifiedName() + "<" + resolvedType.asTypeParameter().getName() + ">";
                                if (tokenReferences.typeParametersReferences.containsKey(resolvedTypeKey)) {
                                    resolvedType = tokenReferences.typeParametersReferences.get(resolvedTypeKey).get(0);
                                }
                            }

                            if (resolvedType.isReferenceType()) {
                                int typeNameIdx = resolvedType.asReferenceType().getQualifiedName().lastIndexOf(".");
                                String typeName = resolvedType.asReferenceType().getQualifiedName().substring(typeNameIdx + 1);
                                String packageName = resolvedType.asReferenceType().getQualifiedName().replace( "." + typeName, "");
                                // Get the compilation unit of the test class to get its package declaration
                                // It's important to collect protected members from a type in the same package
                                CompilationUnit testCu = (CompilationUnit) tc.getParentNode().get();
                                Optional<PackageDeclaration> testClassPackageDeclaration = testCu.getPackageDeclaration();
                                if (testClassPackageDeclaration.isPresent() && testClassPackageDeclaration.get().getNameAsString().contains(packageName)) {
                                    modifiers.add(List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))));
                                }

                                // Get the declaring type of the method usage
                                String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                                Optional<Path> resolvedTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoSourcePath);
                                if (!resolvedTypePath.isPresent()) {
                                    resolvedTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoTestPath);
                                }
                                if (resolvedTypePath.isPresent()) {
                                    if (!returnClassReferences.containsKey(qualifiedName)) {
                                        returnClassReferences.put(qualifiedName, new ArrayList<>());
                                    }
                                    final Path resolvedTypePathToCompare = resolvedTypePath.get();
                                    boolean isAlreadyPresent = returnClassReferences.get(qualifiedName).stream().anyMatch(p -> p.toString().equals(resolvedTypePathToCompare.toString()));
                                    if (!isAlreadyPresent) {
                                        returnClassReferences.get(qualifiedName).add(resolvedTypePath.get());
                                    }
                                } else {
                                    logger.error("Declaring type path not found for while processing a field match (" + token + " of type " + resolvedType.asReferenceType().getQualifiedName() + "): " + qualifiedName);
                                }

                                List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  resolvedType.asReferenceType().getTypeParametersMap();
                                for (ResolvedReferenceType ancestor: resolvedType.asReferenceType().getAllAncestors()) {
                                    typeParameters.addAll(ancestor.getTypeParametersMap());
                                }
                                for(com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameterPair: typeParameters) {
                                    ResolvedTypeParameterDeclaration typeParameterDeclaration = typeParameterPair.a;
                                    ResolvedType typeParameterValue = typeParameterPair.b;
                                    String typeParameterKey = typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">";
                                    if(typeParameterValue.isTypeVariable()) {
                                        String typeParameterValueKey = typeParameterValue.asTypeParameter().getContainerQualifiedName() + "<" + typeParameterValue.asTypeParameter().getName() + ">";
                                        if (tokenReferences.typeParametersReferences.containsKey(typeParameterValueKey)) {
                                            typeParameterValue = tokenReferences.typeParametersReferences.get(typeParameterValueKey).get(0);
                                        }
                                    }
                                    if (!typeParameterReferences.containsKey(typeParameterKey)) {
                                        typeParameterReferences.put(typeParameterKey, new ArrayList<>());
                                    }
                                    final ResolvedType typeParameterValueToCompare = typeParameterValue;
                                    boolean isAlreadyPresent = typeParameterReferences.get(typeParameterKey).stream().anyMatch(t -> t.equals(typeParameterValueToCompare));
                                    if (!isAlreadyPresent) {
                                        typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                                    }
                                }

                                try {
                                    try {
                                        collectMethodUsages(
                                                methodIdentifiers,
                                                resolvedType.asReferenceType().getTypeDeclaration().get().getAllMethods(),
                                                modifiers,
                                                List.of(new Pair("Test", Boolean.valueOf(false)))
                                        );
                                    } catch (Exception e) {
                                        String message = "Error resolving reference type: " + resolvedType + " - " + e.getMessage();
                                        logger.error(message);
                                        clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                        collectResolvedMethodDeclaration(
                                                methodIdentifiers,
                                                resolvedType.asReferenceType().getTypeDeclaration().get().getDeclaredMethods(),
                                                modifiers,
                                                List.of(new Pair("Test", Boolean.valueOf(false)))
                                        );
                                        logger.error("********************************************************************");
                                        logger.error("Stack trace:");
                                        for (StackTraceElement ste : e.getStackTrace()) {
                                            logger.error("\tat " + ste.toString());
                                        }
                                        logger.error("********************************************************************");
                                    }
                                    //updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                                    List<FieldDeclaration> fieldDeclarations =  resolvedType.asReferenceType().getTypeDeclaration().get().toAst().get().findAll(FieldDeclaration.class);
                                    updateHashMapOfFieldTypeIdentifiers(fieldIdentifiers, fieldDeclarations, modifiers);
                                    //updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                                } catch (Exception e) {
                                    String message = "Error resolving reference type: " + resolvedType + " - " + e.getMessage();
                                    logger.error(message);
                                    clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                    logger.error("********************************************************************");
                                    logger.error("Stack trace:");
                                    for (StackTraceElement ste : e.getStackTrace()) {
                                        logger.error("\tat " + ste.toString());
                                    }
                                    logger.error("********************************************************************");
                                }
                            }
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(classIdentifiers);
                        tokenIdentifiersCollector.setMethodIdentifiersMap(methodIdentifiers);
                        tokenIdentifiersCollector.setFieldIdentifiersMap(fieldIdentifiers);
                        tokenIdentifiersCollector.setParameterIdentifiersMap(parameterIdentifiers);
                        tokenIdentifiersCollector.setLambdaIdentifiersMap(lambdaIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(enumIdentifiers);
                        tokenReferences.setContextualInfoType(contextualInfoType);
                        tokenReferences.setCurrentClasses(currentClassReferences);
                        tokenReferences.setReturnClasses(returnClassReferences);
                        tokenReferences.setMethods(methodReferences);
                        tokenReferences.setFields(fieldReferences);
                        tokenReferences.setParameterReferences(parameterReferences);
                        tokenReferences.setConstructors(constructorReferences);
                        if (streamFlag) {
                            typeParameterReferences = tokenReferences.typeParametersReferences;
                        }
                        tokenReferences.setTypeParameterReferences(typeParameterReferences);
                        updateHashMapOfEnumConstantReferencesFromIdentifiers(enumReferences, enumIdentifiers);
                        tokenReferences.setEnums(enumReferences);
                        if (currentClassReferences.containsKey(streamClassReference) && !(token.equals("collect") && contextualInfoType.equals(ContextualInfoType.METHOD_CALL))) {
                            streamFlag = true;
                        }
                }
        }
        return tokenIdentifiersStack.get(tokenIdentifiersStack.size() - 1);
    }

    public static void updateHashMapOfJavaAPITypeDeclarationPathIdentifiers(
            HashMap<String, List<Path>> pathIdentifiers,
            Path basePath,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        final Path javaLibPath = basePath.resolve("lib");
        try {
            Files.walk(javaLibPath)
                    .filter(Files::isRegularFile) // Only process files, not directories
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().endsWith("module-info.java"))// Match pattern
                    .forEach(filePath -> {
                        try {
                            updateHashMapOfTypeDeclarationPathsIdentifiers(pathIdentifiers, List.of(javaLibPath), filePath, modifiersRules, false, false, null);
                        } catch (Exception e) {
                            logger.error("Failed to process compilation unit from file " + filePath);
                            logger.error("********************************************************************");
                            logger.error("Stack trace:");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                logger.error("\tat " + ste.toString());
                            }
                            logger.error("********************************************************************");
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to walk through java classes in the java lib folder" + javaLibPath);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
    }

    private static void processReturnClass(
            TypeDeclaration tc,
            TokenReferencesCollector tokenReferences,
            HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers,
            HashMap<String, List<MethodUsage>> methodIdentifiers,
            HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers,
            HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers,
            HashMap<String,List<Pair<VariableDeclarator,Type>>> fieldReferences,
            HashMap<String, List<MethodUsage>> methodReferences,
            HashMap<String, List<ResolvedConstructorDeclaration>> constructorReferences,
            HashMap<String, List<EnumConstantDeclaration>> enumReferences
    ) {
        // Get the compilation unit of the test class to get its package declaration
        // It's important to collect protected members from classes in the same package
        CompilationUnit testCu = (CompilationUnit) tc.getParentNode().get();
        for (Map.Entry<String, List<Path>> returnClassReference : tokenReferences.returnClasses.entrySet()) {
            String classNameKey = returnClassReference.getKey();
            List<Path> classPaths = returnClassReference.getValue();
            for (final Path classPath: classPaths) {
                try {
                    // Get the compilation unit of the matched class
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                    // Get the package declaration of the matched class and the test class
                    Optional<PackageDeclaration> matchedPackageDeclaration = cu.getPackageDeclaration();
                    Optional<PackageDeclaration> testPackageDeclaration = testCu.getPackageDeclaration();
                    int lastDotIndex = classNameKey.lastIndexOf('.');
                    int lastDiamondIndex = classNameKey.lastIndexOf('<');
                    String className = lastDiamondIndex == -1 ? classNameKey.substring(lastDotIndex + 1) : classNameKey.substring(lastDotIndex + 1, lastDiamondIndex);
                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, className);
                    // For each matched type declaration, collect its inner types as possible next class identifiers
                    for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                        updateHashMapOfResolvedConstructorDeclarationIdentifiers(constructorIdentifiers, targetTypeDeclaration);
                        updateHashMapOfResolvedConstructorReferencesFromIdentifiers(constructorReferences, constructorIdentifiers);
                        // Set up an initial list of modifiers rules for searching field and method members
                        List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>();
                        modifiers.add(List.of(
                                new Pair(Modifier.Keyword.STATIC, Boolean.valueOf(true)),
                                new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))
                        ));
                        modifiers.add(List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))));
                        // Check if the matched class is in the same package as the test class
                        if (matchedPackageDeclaration.isPresent() && testPackageDeclaration.isPresent()) {
                            String matchedPackageName = matchedPackageDeclaration.get().getNameAsString();
                            String testPackageName = testPackageDeclaration.get().getNameAsString();
                            // If the matched class is in the same package as the test class, add protected members to the search
                            if (testPackageName.equals(matchedPackageName)) {
                                modifiers.add(List.of(
                                        new Pair(Modifier.Keyword.STATIC, Boolean.valueOf(true)),
                                        new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))
                                ));
                            }
                        }
                        // Add all possible enum constant declarations if the type is an enum
                        updateHashMapOfEnumConstantDeclarationIdentifiers(enumIdentifiers, targetTypeDeclaration);
                        updateHashMapOfEnumConstantReferencesFromIdentifiers(enumReferences, enumIdentifiers);
                        // Collect method usages and field type identifiers from the matched type declaration
                        // Methods and fields must be static and public or protected (if in the same package)
                        updateHashMapOfMethodUsageIdentifiers(methodIdentifiers, targetTypeDeclaration, modifiers, List.of(new Pair("Test", Boolean.valueOf(false))));
                        updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                        updateHashMapOfFieldTypeIdentifiers(fieldIdentifiers, targetTypeDeclaration.getFields(), modifiers);
                        updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                    }
                } catch (IOException e) {
                    logger.error("Error reading compilation unit from file: " + classPath);
                    logger.error("********************************************************************");
                    logger.error("Stack trace:");
                    for (StackTraceElement ste : e.getStackTrace()) {
                        logger.error("\tat " + ste.toString());
                    }
                    logger.error("********************************************************************");
                }
            }
        }
    }

    private static void processDeclaringType(
            TokenReferencesCollector tokenReferences,
            String declaringTypeQualifiedName,
            HashMap<String,List<Path>> currentClassReferences,
            HashMap<String,List<ResolvedType>> typeParameterReferences,
            ResolvedType resolvedType,
            List<ResolvedReferenceType> ancestors
    ) {
        Optional<Path> declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(declaringTypeQualifiedName, repoSourcePath);
        if (!declaringTypePath.isPresent()) {
            declaringTypePath =JavaParserUtils.retrieveFilePathFromFullyQualifiedName(declaringTypeQualifiedName, repoTestPath);
        }
        if (declaringTypePath.isPresent()) {
            if (!currentClassReferences.containsKey(declaringTypeQualifiedName)) {
                currentClassReferences.put(declaringTypeQualifiedName, new ArrayList<>());
            }
            final Path declaringTypePathToCompare = declaringTypePath.get();
            boolean isAlreadyPresent = currentClassReferences.get(declaringTypeQualifiedName).stream().anyMatch(p -> p.toString().equals(declaringTypePathToCompare.toString()));
            if (!isAlreadyPresent) {
                currentClassReferences.get(declaringTypeQualifiedName).add(declaringTypePath.get());
            }
        } else {
            logger.error("Declaring type path not found for while processing: " + declaringTypeQualifiedName);
        }

        if (resolvedType.isReferenceType()) {
            List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  resolvedType.asReferenceType().getTypeParametersMap();
            for (ResolvedReferenceType ancestor: ancestors) {
                typeParameters.addAll(ancestor.getTypeParametersMap());
            }
            for(com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameterPair: typeParameters) {
                ResolvedTypeParameterDeclaration typeParameterDeclaration = typeParameterPair.a;
                ResolvedType typeParameterValue = typeParameterPair.b;

                String typeParameterKey = typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">";

                if(typeParameterValue.isTypeVariable()) {
                    String typeParameterValueKey = typeParameterValue.asTypeParameter().getContainerQualifiedName() + "<" + typeParameterValue.asTypeParameter().getName() + ">";
                    if (tokenReferences.typeParametersReferences.containsKey(typeParameterValueKey)) {
                        typeParameterValue = tokenReferences.typeParametersReferences.get(typeParameterValueKey).get(0);
                    }
                }
                if (!typeParameterReferences.containsKey(typeParameterKey)) {
                    typeParameterReferences.put(typeParameterKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = typeParameterReferences.get(typeParameterKey).contains(typeParameterValue);
                if (!isAlreadyPresent) {
                    typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                }
            }
        }
    }


    public static void updateHashMapOfJavaAPITypeDeclarationIdentifiers(
            HashMap<String, List<TypeDeclaration>> typeDeclarationIdentifiers,
            Path basePath,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        final Path javaLibPath = basePath.resolve("lib");
        try {
            Files.walk(javaLibPath)
                    .filter(Files::isRegularFile) // Only process files, not directories
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().endsWith("module-info.java"))// Match pattern
                    .forEach(filePath -> {
                        try {
                            CompilationUnit javaFileCu = JavaParserUtils.getCompilationUnit(filePath);
                            updateHashMapOfTypeDeclarationIdentifiers(typeDeclarationIdentifiers, List.of(javaLibPath), javaFileCu, modifiersRules, false, false);
                        } catch (Exception e) {
                            logger.error("Failed to process compilation unit from file " + filePath);
                            logger.error("********************************************************************");
                            logger.error("Stack trace:");
                            for (StackTraceElement ste : e.getStackTrace()) {
                                logger.error("\tat " + ste.toString());
                            }
                            logger.error("********************************************************************");
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to walk through java classes in the java lib folder" + javaLibPath);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
    }

    public static void updateHashMapOfTypeDeclarationIdentifiers(
            HashMap<String, List<TypeDeclaration>> typeDeclarationIdentifiers,
            List<Path> repoSourcePaths,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        updateHashMapOfTypeDeclarationIdentifiers(typeDeclarationIdentifiers, repoSourcePaths, cu, modifiersRules, true, true);
    }

    public static void updateHashMapOfTypeDeclarationPathIdentifiers(
            HashMap<String, List<Path>> pathIdentifiers,
            List<Path> repoSourcePaths,
            Path cuFilePath,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        updateHashMapOfTypeDeclarationPathsIdentifiers(pathIdentifiers, repoSourcePaths, cuFilePath, modifiersRules, true, true, null);
    }

    public static void updateHashMapOfTypeDeclarationPathIdentifiers(
            HashMap<String, List<Path>> pathIdentifiers,
            List<Path> repoSourcePaths,
            Path cuFilePath,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<String> errorMessages
    ) {
        updateHashMapOfTypeDeclarationPathsIdentifiers(pathIdentifiers, repoSourcePaths, cuFilePath, modifiersRules, true, true, errorMessages);
    }

    public static void updateHashMapOfTypeDeclarationPathsIdentifiers(
            HashMap<String, List<Path>> typeDeclarationPathIdentifiers,
            List<Path> repoSourcePaths,
            Path cuFilePath,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            boolean collectImportedClasses,
            boolean collectClassesInSamePackage,
            List<String> errorMessages
    ) {
        try {
            CompilationUnit cu = JavaParserUtils.getCompilationUnit(cuFilePath);
            NodeList<TypeDeclaration<?>> typeDeclarations = cu.getTypes();
            for (TypeDeclaration typeDeclaration : typeDeclarations) {
                if (!typeDeclarationPathIdentifiers.containsKey(typeDeclaration.getNameAsString())) {
                    typeDeclarationPathIdentifiers.put(typeDeclaration.getNameAsString(), new ArrayList<>());
                }
                boolean isAlreadyPresent = typeDeclarationPathIdentifiers.get(typeDeclaration.getNameAsString()).stream()
                        .anyMatch(t -> t.equals(cuFilePath));
                if (!isAlreadyPresent) {
                    typeDeclarationPathIdentifiers.get(typeDeclaration.getNameAsString()).add(cuFilePath);
                }
            }

            if (collectImportedClasses) {
                JavaParserUtils.collectPathsOfImportedClasses(typeDeclarationPathIdentifiers, repoSourcePaths, cu, List.of(
                        List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true)))
                ));
            }

            if (collectClassesInSamePackage) {
                for (Path path : repoSourcePaths) {
                    JavaParserUtils.collectPathsOfClassesInSamePackage(typeDeclarationPathIdentifiers, path, cu, modifiersRules, errorMessages);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to process compilation unit from file " + cuFilePath, e);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
    }

    public static void updateHashMapOfEnumConstantDeclarationIdentifiers(
            HashMap<String, List<EnumConstantDeclaration>> updatedEnumConstantDeclarations,
            TypeDeclaration typeDeclaration
    ) {
        if (typeDeclaration instanceof EnumDeclaration) {
            for (EnumConstantDeclaration enumConstant : typeDeclaration.asEnumDeclaration().getEntries()) {
                if (!updatedEnumConstantDeclarations.containsKey(enumConstant.getNameAsString())) {
                    updatedEnumConstantDeclarations.put(enumConstant.getNameAsString(), new ArrayList<>());
                }
                boolean isAlreadyPresent = updatedEnumConstantDeclarations.get(enumConstant.getNameAsString()).stream()
                        .anyMatch(e -> e.equals(enumConstant));
                if (!isAlreadyPresent) {
                    updatedEnumConstantDeclarations.get(enumConstant.getNameAsString()).add(enumConstant);
                }
            }
        }
    }

    public static void updateHashMapOfTypeDeclarationIdentifiers(
            HashMap<String, List<TypeDeclaration>> typeDeclarationIdentifiers,
            List<Path> repoSourcePaths,
            CompilationUnit cu,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            boolean collectImportedClasses,
            boolean collectClassesInSamePackage
    ) {
        NodeList<TypeDeclaration<?>> typeDeclarations = cu.getTypes();
        for (TypeDeclaration typeDeclaration : typeDeclarations) {
            if (!typeDeclarationIdentifiers.containsKey(typeDeclaration.getNameAsString())) {
                typeDeclarationIdentifiers.put(typeDeclaration.getNameAsString(), new ArrayList<>());
            }
            boolean isAlreadyPresent = typeDeclarationIdentifiers.get(typeDeclaration.getNameAsString()).stream()
                    .anyMatch(t -> t.equals(typeDeclaration));
            if (!isAlreadyPresent) {
                typeDeclarationIdentifiers.get(typeDeclaration.getNameAsString()).add(typeDeclaration);
            }
        }

        if (collectImportedClasses) {
            JavaParserUtils.collectImportedClasses(typeDeclarationIdentifiers, repoSourcePaths, cu, List.of(
                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true)))
            ));
        }

        if (collectClassesInSamePackage) {
            for (Path path : repoSourcePaths) {
                JavaParserUtils.collectClassesInSamePackage(typeDeclarationIdentifiers, path, cu, modifiersRules);
            }
        }
    }

    public static void updateHashMapOfMethodUsageIdentifiers(
            HashMap<String, List<MethodUsage>> methodUsageIdentifiers,
            TypeDeclaration typeDeclaration,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<Pair<String, Boolean>> annotations
    ) {
        // Collect callable methods from type declaration
        try {
            Set<MethodUsage> methodUsages = typeDeclaration.resolve().getAllMethods();
            collectMethodUsages(methodUsageIdentifiers, methodUsages, modifiersRules, annotations);
        } catch (Exception e) {
            logger.error("Unable to resolve type declaration " + (typeDeclaration.getFullyQualifiedName().isPresent() ? typeDeclaration.getFullyQualifiedName().get() : typeDeclaration.getNameAsString()) + " and collect methods from it: " + e.getMessage() /*+ " - " + e.getStackTrace()*/);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
    }

    private static void collectResolvedMethodDeclaration(
            HashMap<String, List<MethodUsage>> methodUsageIdentifiers,
            Set<ResolvedMethodDeclaration> resolvedMethodDeclarations,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<Pair<String, Boolean>> annotations
    ) {
        for (ResolvedMethodDeclaration resolvedMethodDeclaration : resolvedMethodDeclarations) {
            List<Modifier> methodModifiers = new ArrayList<>();
            AccessSpecifier accessSpecifier = resolvedMethodDeclaration.accessSpecifier();

            if (accessSpecifier == AccessSpecifier.PUBLIC) {
                methodModifiers.add(new Modifier(Modifier.Keyword.PUBLIC));
            } else if (accessSpecifier == AccessSpecifier.PRIVATE) {
                methodModifiers.add(new Modifier(Modifier.Keyword.PRIVATE));
            } else if (accessSpecifier == AccessSpecifier.PROTECTED) {
                methodModifiers.add(new Modifier(Modifier.Keyword.PROTECTED));
            }

            if (resolvedMethodDeclaration.isStatic()) {
                methodModifiers.add(new Modifier(Modifier.Keyword.STATIC));
            }
            if (resolvedMethodDeclaration.isAbstract()) {
                methodModifiers.add(new Modifier(Modifier.Keyword.ABSTRACT));
            }

            if (!JavaParserUtils.checkModifiersRules(modifiersRules, methodModifiers)) {
                // Skip methods that do not match the modifiers rules
                continue;
            }
            try {
                MethodDeclaration methodDeclaration = (MethodDeclaration) resolvedMethodDeclaration.toAst().get();
                // Check if the method has the required annotations
                boolean annotationCheck = true;
                for (Pair<String, Boolean> annotation : annotations) {
                    boolean hasAnnotation = methodDeclaration.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals(annotation.getValue0()));
                    if (hasAnnotation != annotation.getValue1()) {
                        annotationCheck = false;
                        break;
                    }
                }
                if (!annotationCheck) {
                    continue;
                }
            } catch (Exception e) {
                // Consider methods that cannot be resolved to check the annotations (they are not test methods)
            }

            if (!methodUsageIdentifiers.containsKey(resolvedMethodDeclaration.getName())) {
                methodUsageIdentifiers.put(resolvedMethodDeclaration.getName(), new ArrayList<>());
            }
        }
    }

    private static void collectMethodUsages(
            HashMap<String, List<MethodUsage>> methodUsageIdentifiers,
            Set<MethodUsage> methodUsages,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules,
            List<Pair<String, Boolean>> annotations
    ) {
        for (MethodUsage methodUsage : methodUsages) {
            List<Modifier> methodModifiers = new ArrayList<>();

            if (methodUsage.getDeclaration() instanceof JavaParserMethodDeclaration jpMethodDeclaration) {
                MethodDeclaration jpMethod = jpMethodDeclaration.getWrappedNode();
                methodModifiers.addAll(jpMethod.getModifiers());
            } else {
                try {
                    methodModifiers.addAll(JavaParserUtils.getMethodModifiers(methodUsage.getDeclaration()).stream().map(m -> new Modifier(Modifier.Keyword.valueOf(m.toUpperCase()))).collect(Collectors.toList()));
                } catch (IllegalArgumentException e) {}
            }
            if (!JavaParserUtils.checkModifiersRules(modifiersRules, methodModifiers)) {
                // Skip methods that do not match the modifiers rules
                continue;
            }
            try {
                MethodDeclaration methodDeclaration = (MethodDeclaration) methodUsage.getDeclaration().toAst().get();
                // Check if the method has the required annotations
                boolean annotationCheck = true;
                for (Pair<String, Boolean> annotation : annotations) {
                    boolean hasAnnotation = methodDeclaration.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals(annotation.getValue0()));
                    if (hasAnnotation != annotation.getValue1()) {
                        annotationCheck = false;
                        break;
                    }
                }
                if (!annotationCheck) {
                    continue;
                }
            } catch (Exception e) {
                // Consider methods that cannot be resolved to check the annotations (they are not test methods)
            }

            if (!methodUsageIdentifiers.containsKey(methodUsage.getName())) {
                methodUsageIdentifiers.put(methodUsage.getName(), new ArrayList<>());
            }
            // Check if the method usage is already present in the map
            boolean isAlreadyPresent = methodUsageIdentifiers.get(methodUsage.getName()).stream()
                    .anyMatch(m -> m.equals(methodUsage));
            if (!isAlreadyPresent) {
                methodUsageIdentifiers.get(methodUsage.getName()).add(methodUsage);
            }
        }
    }

    private static void collectTypeParametersReferencesFromFieldIdentifiers(
            List<Pair<VariableDeclarator,Type>> fieldIdentifiers,
            TokenReferencesCollector tokenReferencesCollector
    ) {
        HashMap<String, List<ResolvedType>> typeParametersReferences = tokenReferencesCollector.typeParametersReferences;
        for (Pair<VariableDeclarator,Type> entry: fieldIdentifiers) {
            Type type = entry.getValue1();
            if (type.isClassOrInterfaceType()) {
                try {
                    List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParametersMap = type.asClassOrInterfaceType().resolve().asReferenceType().getTypeParametersMap();
                    for (com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameter : typeParametersMap) {
                        String referenceKey = type.resolve().asReferenceType().getQualifiedName() + "<" + typeParameter.a.getName() + ">";
                        ResolvedType typeParameterResolvedType = typeParameter.b;
                        if (!typeParametersReferences.containsKey(referenceKey)) {
                            typeParametersReferences.put(referenceKey, new ArrayList<>());
                        }
                        boolean isAlreadyPresent = typeParametersReferences.get(referenceKey).stream().anyMatch(v -> v.equals(typeParameterResolvedType));
                        if (!isAlreadyPresent) {
                            typeParametersReferences.get(referenceKey).add(typeParameterResolvedType);
                        }
                    }
                } catch (Exception e) {
                    String message = "Unable to successfully resolve the type of the field to extract type parameters: " + e.getMessage() /*+ " - " + e.getStackTrace()*/;
                    logger.error(message);
                    logger.error("********************************************************************");
                    logger.error("Stack trace:");
                    for (StackTraceElement ste : e.getStackTrace()) {
                        logger.error("\tat " + ste.toString());
                    }
                    logger.error("********************************************************************");
                }
            }
        }
    }

    /**
     * Updates the map of resolved constructor declaration identifiers with the constructors from the given type declaration.
     * If a constructor name already exists in the map, it adds the new constructor to the list of existing ones.
     *
     * @param resolvedConstructorIdentifiers The map to update with resolved constructor declarations
     * @param typeDeclaration The type declaration from which to collect constructors
     */
    public static void updateHashMapOfResolvedConstructorDeclarationIdentifiers(
            HashMap<String, List<ResolvedConstructorDeclaration>> resolvedConstructorIdentifiers,
            TypeDeclaration typeDeclaration
    ) {
        try {
            List<ResolvedConstructorDeclaration> resolvedConstructors = typeDeclaration.resolve().getConstructors();
            for (ResolvedConstructorDeclaration resolvedConstructor : resolvedConstructors) {
                if (!resolvedConstructorIdentifiers.containsKey(resolvedConstructor.getName())) {
                    resolvedConstructorIdentifiers.put(resolvedConstructor.getName(), new ArrayList<>());
                }
                boolean isAlreadyPresent = resolvedConstructorIdentifiers.get(resolvedConstructor.getName()).stream()
                        .anyMatch(c -> c.equals(resolvedConstructor));
                if (!isAlreadyPresent) {
                    resolvedConstructorIdentifiers.get(resolvedConstructor.getName()).add(resolvedConstructor);
                }
            }
        } catch (Exception e) {
            logger.error("Unable to resolve the type declaration " + typeDeclaration.getFullyQualifiedName() + " to extract constructors from it: " + e.getMessage() /*+ " - " + e.getStackTrace()*/);
            logger.error("********************************************************************");
            logger.error("Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error("\tat " + ste.toString());
            }
            logger.error("********************************************************************");
        }
    }

    /**
     * Updates the map of variable declarator identifiers tokens with the types of the variable declarators
     * from the given type declaration. If a variable name already exists in the map, it adds the new variable
     * declarator to the list of existing ones.
     *
     * @param variableDeclaratorIdentifiers The map to update with variable declarators
     * @param fieldDeclarations The list of fields from which to extract the types of variable declarators
     * @param modifiersRules The modifiers to filter the variables (e.g., public, private)
     */
    public static void updateHashMapOfFieldTypeIdentifiers(
            HashMap<String, List<Pair<VariableDeclarator,Type>>> variableDeclaratorIdentifiers,
            List<FieldDeclaration> fieldDeclarations,
            List<List<Pair<Modifier.Keyword, Boolean>>> modifiersRules
    ) {
        HashMap<String, VariableDeclarator> tcVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), fieldDeclarations, modifiersRules);

        for (Map.Entry<String, VariableDeclarator> entry : tcVariablesMap.entrySet()) {
            String variableName = entry.getKey();
            VariableDeclarator variableDeclarator = entry.getValue();
            Type variableType = variableDeclarator.getType();
            if (!variableDeclaratorIdentifiers.containsKey(variableName)) {
                variableDeclaratorIdentifiers.put(variableName, new ArrayList<>());
            }
            boolean isAlreadyPresent = variableDeclaratorIdentifiers.get(variableName).stream().anyMatch(v -> v.getValue0().equals(variableDeclarator) && v.getValue1().equals(variableType));
            if (!isAlreadyPresent) {
                variableDeclaratorIdentifiers.get(variableName).add(new Pair(variableDeclarator, variableType));
            }
        }
    }

    /**
     * Updates the map of variable declarator identifiers tokens with the variable declarators from the given method declaration.
     * If a variable name already exists in the map, it adds the new variable declarator to the list of existing ones.
     *
     * @param fieldTypeIdentifiers The map to update with variable declarators
     * @param methodDeclaration The method declaration from which to collect variable declarators
     */
    public static void updateHashMapOfFieldTypeIdentifiers(
            HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldTypeIdentifiers,
            MethodDeclaration methodDeclaration
    ) {
        HashMap<String, VariableDeclarator> tcVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), methodDeclaration.getBody().get());

        for (Map.Entry<String, VariableDeclarator> entry : tcVariablesMap.entrySet()) {
            String variableName = entry.getKey();
            VariableDeclarator variableDeclarator = entry.getValue();
            Type variableType = variableDeclarator.getType();
            if (!fieldTypeIdentifiers.containsKey(variableName)) {
                fieldTypeIdentifiers.put(variableName, new ArrayList<>());
            }
            boolean isAlreadyPresent = fieldTypeIdentifiers.get(variableName).stream().anyMatch(v -> v.getValue0().equals(variableDeclarator) && v.getValue1().equals(variableType));
            if (!isAlreadyPresent) {
                fieldTypeIdentifiers.get(variableName).add(new Pair(variableDeclarator, variableType));
            }
        }
    }

    /**
     * Updates the map of parameters identifiers tokens with the resolved type from the given method declaration.
     * If a variable name already exists in the map, it adds the new variable declarator to the list of existing ones.
     *
     * @param parameterTypeIdentifiers The map to update with parameters
     * @param methodDeclaration The method declaration from which to collect variable declarators
     */
    public static void updateHashMapOfParameterIdentifiers(
            HashMap<String, List<Type>> parameterTypeIdentifiers,
            MethodDeclaration methodDeclaration
    ) {
        if (methodDeclaration.getBody().isPresent()) {
            List<Parameter> parameters = methodDeclaration.getBody().get().findAll(Parameter.class);
            for (Parameter parameter : parameters) {
                String parameterName = parameter.getNameAsString();
                if (!parameterTypeIdentifiers.containsKey(parameterName)) {
                    parameterTypeIdentifiers.put(parameterName, new ArrayList<>());
                }
                boolean isAlreadyPresent = parameterTypeIdentifiers.get(parameterName).stream().anyMatch(p -> p.equals(parameter.getType()));
                if (!isAlreadyPresent) {
                    parameterTypeIdentifiers.get(parameterName).add(parameter.getType());
                }
            }
        }
    }
}
