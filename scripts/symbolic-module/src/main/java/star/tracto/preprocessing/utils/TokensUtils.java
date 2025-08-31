package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import org.eclipse.xtend.lib.macro.declaration.ParameterDeclaration;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.*;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.preprocessing.builders.PartialOracleDatapointBuilder;
import star.tracto.preprocessing.builders.TestClazzDatapointsBuilder;
import star.tracto.preprocessing.builders.TokenStepBuilder;
import star.tracto.preprocessing.components.*;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
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

    /**
     * A support class to collect various types of candidate token identifiers categorized by their type when processing
     * an assertion.
     *
     */
    private static class TokenIdentifiersCollector {
        HashMap<String, List<Path>> classPathIdentifiers;
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers;
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
                HashMap<String, List<ResolvedType>> lambdaIdentifiers,
                HashMap<String, List<MethodUsage>> methodIdentifiers,
                HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers,
                HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers
        ) {
            this.classPathIdentifiers = classPathIdentifiers;
            this.fieldIdentifiers = fieldIdentifiers;
            this.lambdaIdentifiers = lambdaIdentifiers;
            this.methodIdentifiers = methodIdentifiers;
            this.constructorIdentifiers = constructorIdentifiers;
            this.enumIdentifiers = enumIdentifiers;
        }

        public TokenIdentifiersCollector() {
            this.classPathIdentifiers = new HashMap<>();
            this.fieldIdentifiers = new HashMap<>();
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
                HashMap<String, List<ResolvedType>> lambdaReferences,
                HashMap<String, List<Path>> currentClasses,
                HashMap<String, List<Path>> returnClasses,
                HashMap<String, List<Pair<VariableDeclarator,Type>>> fields,
                HashMap<String, List<MethodUsage>> methods,
                HashMap<String, List<ResolvedConstructorDeclaration>> constructors,
                HashMap<String, List<EnumConstantDeclaration>> enums
        ) {
            this.contextualInfoType = contextualInfoType;
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
                        String originalTargetAssertion = datapoint.target();
                        String targetAssertion = originalTargetAssertion.replace(" ", "");

                        //if (targetAssertion.contains("assertEquals(HttpMethod")) {
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
                            List<String> tokens = Antlr4Utils.tokenizeSnippet(targetAssertion);
                            // Check that the first token is a valid JUnit assertion method
                            try {
                                JUnitAssertionType.fromString(tokens.get(0));
                            } catch (IllegalArgumentException e) {
                                String message = "Assertion do not start with JUnit api (do not implemented yet): " + targetAssertion + " - " + e.getMessage();
                                logger.error(message);
                                clazzDatapointsErrors.get(testPrefix.getNameAsString()).get(targetAssertion).add(message);
                                continue;
                            }
                            // Start with an empty partial snippet
                            String partialSnippet = "";
                            // Iterate over the tokens to build the token steps
                            for (int i = 0; i < tokens.size(); i++) {
                                // Get the current token to process
                                String token = tokens.get(i);
                                // Define a new token step builder for the current token step
                                TokenStepBuilder tokenStepBuilder = new TokenStepBuilder();
                                tokenStepBuilder.setPartialSnippet(partialSnippet);
                                // In the first step, we know that the next possible tokens are only JUnit assertion methods
                                if (partialSnippet.isEmpty()) {
                                    tokenStepBuilder.setNextPossibleJavaTokens(Arrays.stream(JUnitAssertionType.values()).map(a -> a.getAssertionMethodName()).collect(Collectors.toList()));
                                    tokenStepBuilder.setNextPossibleProjectTokens(new ArrayList<>());
                                    tokenStepBuilder.setNextPossibleGenericTokens(new ArrayList<>());
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
                                List<String> nextANTLRTokens = Antlr4Utils.parseSnippet(JUnitAssertionType.fromString(tokens.get(0)), partialSnippet);
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
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.lambdaIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.enumIdentifiers.keySet());
                                        }
                                        if (streamFlag) {
                                            adjustedNextConcreteTokens.add("LAMBDA_PARAM");
                                        }
                                    } else {
                                        adjustedNextJavaTokens.add(nextANTLRToken);
                                    }
                                }
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
                                if (streamFlag) {
                                    tokenStepBuilder.setTarget("LAMBDA_PARAM" + ":" + token);
                                } else {
                                    tokenStepBuilder.setTarget(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                }
                                tokenStepBuilder.setContextualInfos(tokenIdentifiersStack.stream().map(t -> generateTokenStepContextualInfo(t.getValue1())).collect(Collectors.toList()));
                                tokenSteps.add(tokenStepBuilder.build());
                                // Update the partial snippet
                                partialSnippet += token;
                                // Update the token identifiers and references collectors
                                Pair<TokenIdentifiersCollector, TokenReferencesCollector> updatedCollectors = updateTokenIdentifiersAndReferences(tokenIdentifiersStack, token, tokens.get(i-1), testPrefixClass, testPrefix.getNameAsString(), targetAssertion);
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
                        }
                        //}
                        break;
                    }
                }
            }
            testClazzDatapointsBuilder.setDatapoints(partialOraclesDatapoints);
        } catch (IOException e) {
            logger.error("Error reading file: " + testPrefixClassPath);
        }
        return new Pair<>(testClazzDatapointsBuilder.build(), clazzDatapointsErrors);
    }

    public static TokenIdentifiersCollector cloneRootTokenIdentifiers () {
        TokenIdentifiersCollector clonedTokenIdentifierCollector = new TokenIdentifiersCollector(
                new HashMap<>(rootTokenIdentifiersCollector.classPathIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.fieldIdentifiers),
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
        }
        // Collect the list of methods defined within the test class (immediately accessible)
        HashMap<String, List<MethodUsage>> methodUsageIdentifiers = new HashMap<>();
        try {
            updateHashMapOfMethodUsageIdentifiers(methodUsageIdentifiers, tc, new ArrayList<>(), List.of(new Pair("Test", Boolean.valueOf(false))));
        } catch (Exception e) {
            String message = "Error collecting root method usage identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.get(tp.getNameAsString()).get(targetAssertion).add(e.getMessage());
        }
        // Setup the token identifiers map
        rootTokenIdentifiersCollector.setClassIdentifiersMap(typeDeclarationPathIdentifiers);
        rootTokenIdentifiersCollector.setFieldIdentifiersMap(fieldTypeIdentifiers);
        rootTokenIdentifiersCollector.setMethodIdentifiersMap(methodUsageIdentifiers);
    }

    public static void setupRootTokenReferences (
            TokenReferencesCollector tokenReferencesCollector,
            TokenIdentifiersCollector tokenIdentifiersCollector
    ) {
        tokenReferencesCollector.setCurrentClasses(tokenIdentifiersCollector.classPathIdentifiers);
        HashMap<String,List<MethodUsage>> methods = new HashMap<>();
        updateHashMapOfMethodUsageReferencesFromIdentifiers(methods,tokenIdentifiersCollector.methodIdentifiers);
        tokenReferencesCollector.setMethods(methods);
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
                } else {
                    fieldKey = fieldName;
                }
                if (!fieldTypeReferences.containsKey(fieldKey)) {
                    fieldTypeReferences.put(fieldKey, new ArrayList<>());
                }
                boolean isAlreadyPresent = fieldTypeReferences.get(fieldKey).stream().anyMatch(t -> t.equals(fieldType));
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

    private static void updateHashMapOfEnumConstantReferencesFromIdentifiers(
            HashMap<String, List<EnumConstantDeclaration>> enumConstantReferences,
            HashMap<String, List<EnumConstantDeclaration>> enumConstantIdentifiers
    ) {
        for (Map.Entry<String,List<EnumConstantDeclaration>> entry: enumConstantIdentifiers.entrySet()) {
            for (EnumConstantDeclaration enumConstant: entry.getValue()) {
                String enumKey = entry.getKey();
                Optional<Node> parentNode = enumConstant.getParentNode();
                Optional<EnumConstantDeclaration> enumDeclaration = Optional.empty();
                while(parentNode.isPresent() && !enumDeclaration.isPresent()) {
                    if (parentNode.get() instanceof EnumDeclaration) {
                        enumDeclaration = Optional.of((EnumConstantDeclaration) parentNode.get());
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

    public static TokenStep.ContextualInfo generateTokenStepContextualInfo(TokenReferencesCollector tokenReferences) {
        List<Clazz> currentClasses = new ArrayList<>();
        List<Clazz> returnClasses = new ArrayList<>();
        List<Callable> methods = new ArrayList<>();
        List<Callable> constructors = new ArrayList<>();
        List<TokenStep.FieldContextualInfo> fields = new ArrayList<>();
        List<String> enumConstants = new ArrayList<>();

        for (Map.Entry<String,List<Path>> entry: tokenReferences.currentClasses.entrySet()) {
            String identifier = entry.getKey();
            List<Path> classPaths = entry.getValue();
            for (final Path classPath: classPaths) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, identifier);
                    for (TypeDeclaration typeDeclaration: matchedTypeDeclarations) {
                        currentClasses.add(TestUtils.processTypeDeclarationForContextualInfo(typeDeclaration));
                    }
                } catch (IOException e) {
                    logger.error("Error reading compilation unit from file: " + classPath);
                }
            }
        }

        for (Map.Entry<String,List<Path>> entry: tokenReferences.returnClasses.entrySet()) {
            String identifier = entry.getKey();
            List<Path> classPaths = entry.getValue();
            for (final Path classPath: classPaths) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, identifier);
                    for (TypeDeclaration typeDeclaration: matchedTypeDeclarations) {
                        returnClasses.add(TestUtils.processTypeDeclarationForContextualInfo(typeDeclaration));
                    }
                } catch (IOException e) {
                    logger.error("Error reading compilation unit from file: " + classPath);
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
                if (fieldType.isArrayType()) {
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
                        //String message = "Error processing field type declaration for field: " + variableDeclarator.getNameAsString() + " - " + e.getMessage();
                        //logger.error(message);
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
        HashMap<String, List<ResolvedType>> lambdaIdentifiers = new HashMap<>();
        HashMap<String, List<MethodUsage>> methodIdentifiers = new HashMap<>();
        HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers = new HashMap<>();
        HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers = new HashMap<>();
        HashMap<String, List<Path>> currentClassReferences = new HashMap<>();
        HashMap<String, List<Path>> returnClassReferences = new HashMap<>();
        HashMap<String, List<ResolvedType>> typeParameterReferences = new HashMap<>();
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
                // Do nothing, keep the current token identifiers and references collectors
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
                            }
                        }
                    }
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
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setMethodIdentifiersMap(methodIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(constructorIdentifiers);
                        tokenIdentifiersCollector.setFieldIdentifiersMap(fieldIdentifiers);
                        tokenIdentifiersCollector.setEnumIdentifiersMap(enumIdentifiers);
                        tokenReferences.setContextualInfoType(ContextualInfoType.THIS);
                        currentClassReferences.put(tc.getNameAsString(), List.of(testPrefixClassPath));
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
                                                    typeDeclarationPathIdentifiers.get(key).add(path);
                                                }
                                            }
                                        }
                                    }
                                } catch (IOException e) {
                                    logger.error("Error reading compilation unit from file: " + path);
                                    if (!typeDeclarationPathIdentifiers.containsKey(key)) {
                                        typeDeclarationPathIdentifiers.put(key, new ArrayList<>());
                                    }
                                    typeDeclarationPathIdentifiers.get(key).add(path);
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
                        boolean lambdasMatch = tokenIdentifiersCollector.lambdaIdentifiers.containsKey(token);
                        List<ResolvedType> resolvedTypes = new ArrayList<>();

                        int matchCount = 0;
                        if (classesMatch) matchCount++;
                        if (methodsMatch) matchCount++;
                        if (fieldsMatch) matchCount++;
                        if (lambdasMatch) matchCount++;
                        // If the token does not match any identifier, clear all maps to avoid suggesting irrelevant tokens
                        // TODO: Manage tokens that could refer to lambda expressions or generics
                        if (!classesMatch && !methodsMatch && !fieldsMatch && !lambdasMatch) {
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
                                    lambdaReferences.get(token).addAll(lambdaParametersTypes);
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
                                                currentClassReferences.get(qualifiedName).add(lambdaParameterTypePath.get());
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
                                        currentClassReferences.get(fullyQualifiedName).add(classPath);
                                    }
                                    // If the previous token is "new", we are likely dealing with a constructor call
                                    if (previousTokenType == AntlrToken.NEW) {
                                        for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                                            // Collect the constructors from the matched type declaration according to the modifiers rules
                                            updateHashMapOfResolvedConstructorDeclarationIdentifiers(constructorIdentifiers, targetTypeDeclaration);
                                            updateHashMapOfResolvedConstructorReferencesFromIdentifiers(constructorReferences, constructorIdentifiers);
                                        }
                                        continue;
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
                                }
                            }
                            tokenReferences.setReturnClasses(new HashMap<>());
                            tokenReferences.setContextualInfoType(ContextualInfoType.CLASS_CALL);
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
                                            typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Error resolving reference type from lambda field: " + lambdaField);
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
                                resolvedTypes.add(resolvedType);
                                // Get the declaring type of the method usage
                                ResolvedReferenceTypeDeclaration declaringType = methodUsage.getDeclaration().declaringType();
                                String declaringTypeQualifiedName = declaringType.getQualifiedName();
                                Optional<Path> declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(declaringTypeQualifiedName, repoSourcePath);
                                if (!declaringTypePath.isPresent()) {
                                    declaringTypePath =JavaParserUtils.retrieveFilePathFromFullyQualifiedName(declaringTypeQualifiedName, repoTestPath);
                                }
                                if (declaringTypePath.isPresent()) {
                                    if (!currentClassReferences.containsKey(declaringTypeQualifiedName)) {
                                        currentClassReferences.put(declaringTypeQualifiedName, new ArrayList<>());
                                    }
                                    currentClassReferences.get(declaringTypeQualifiedName).add(declaringTypePath.get());
                                } else {
                                    logger.error("Declaring type path not found for while processing a method match (" + methodUsage.getName() + "): " + declaringTypeQualifiedName);

                                }

                                if (resolvedType.isReferenceType()) {
                                    List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  resolvedType.asReferenceType().getTypeParametersMap();
                                    for (ResolvedReferenceType ancestor: methodUsage.getDeclaration().getReturnType().asReferenceType().getAllAncestors()) {
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
                                        typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                                    }
                                }
                            }
                            // Collect the references to the matched methods
                            updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                            tokenReferences.setContextualInfoType(ContextualInfoType.METHOD_CALL);
                        }

                        if (fieldsMatch) {
                            List<Pair<VariableDeclarator,Type>> fields = tokenIdentifiersCollector.fieldIdentifiers.get(token);
                            for (Pair<VariableDeclarator,Type> field: fields) {
                                Type varType = field.getValue1();
                                try {
                                    ResolvedType resolvedType = varType.resolve();
                                    resolvedTypes.add(resolvedType);

                                    if (resolvedType.isReferenceType()) {
                                        String qualifiedName = resolvedType.asReferenceType().getQualifiedName();

                                        if (!currentClassReferences.containsKey(qualifiedName)) {
                                            Optional<Path> declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoSourcePath);
                                            if (!declaringTypePath.isPresent()) {
                                                declaringTypePath = JavaParserUtils.retrieveFilePathFromFullyQualifiedName(qualifiedName, repoTestPath);
                                            }
                                            if (declaringTypePath.isPresent()) {
                                                currentClassReferences.put(qualifiedName, List.of(declaringTypePath.get()));
                                            }
                                        }

                                        List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  resolvedType.asReferenceType().getTypeParametersMap();
                                        for (ResolvedReferenceType ancestor: varType.resolve().asReferenceType().getAllAncestors()) {
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
                                            typeParameterReferences.get(typeParameterKey).add(typeParameterValue);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Error resolving reference type: " + varType);
                                }
                            }
                            updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                            tokenReferences.setContextualInfoType(ContextualInfoType.FIELD_CALL);
                        }

                        for (ResolvedType resolvedType: resolvedTypes) {
                            List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>(List.of(
                                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))),
                                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(false)))
                            ));
                            if (resolvedType.isArray()) {
                                ResolvedArrayType resolvedArrayType = resolvedType.asArrayType();
                                while (resolvedArrayType.isArray()) {
                                    resolvedArrayType = resolvedArrayType.asArrayType();
                                    resolvedType = resolvedArrayType.getComponentType();
                                }
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
                                    returnClassReferences.get(qualifiedName).add(resolvedTypePath.get());
                                } else {
                                    logger.error("Declaring type path not found for while processing a field match (" + token + " of type " + resolvedType.asReferenceType().getQualifiedName() + "): " + qualifiedName);
                                }

                                List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParameters =  resolvedType.asReferenceType().getTypeParametersMap();
//                                if (typeParameters.size() > 0 ) {
//                                    for (com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameterPair: typeParameters) {
//                                        ResolvedTypeParameterDeclaration typeParameterDeclaration = typeParameterPair.a;
//                                        ResolvedType typeParameterValue = typeParameterPair.b;
//                                        if (!tokenReferences.typeParametersReferences.containsKey(typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">")) {
//                                            tokenReferences.typeParametersReferences.put(typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">", new ArrayList<>());
//                                        }
//                                        tokenReferences.typeParametersReferences.get(typeParameterDeclaration.getContainerQualifiedName() + "<" + typeParameterDeclaration.getName() + ">").add(typeParameterValue);
//                                    }
//                                    String typeParameterKey = resolvedType.asTypeParameter().getContainerQualifiedName() + "<" + resolvedType.asTypeParameter().getName() + ">";
//                                }

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
                                    }
                                    //updateHashMapOfMethodUsageReferencesFromIdentifiers(methodReferences, methodIdentifiers);
                                    List<FieldDeclaration> fieldDeclarations =  resolvedType.asReferenceType().getTypeDeclaration().get().toAst().get().findAll(FieldDeclaration.class);
                                    updateHashMapOfFieldTypeIdentifiers(fieldIdentifiers, fieldDeclarations, modifiers);
                                    //updateHashMapOfFieldTypeReferencesFromIdentifiers(fieldReferences, fieldIdentifiers);
                                } catch (Exception e) {
                                    String message = "Error resolving reference type: " + resolvedType + " - " + e.getMessage();
                                    logger.error(message);
                                    clazzDatapointsErrors.get(testPrefixName).get(targetAssertion).add(message);
                                }
                            }
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(classIdentifiers);
                        tokenIdentifiersCollector.setMethodIdentifiersMap(methodIdentifiers);
                        tokenIdentifiersCollector.setFieldIdentifiersMap(fieldIdentifiers);
                        tokenIdentifiersCollector.setLambdaIdentifiersMap(lambdaIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(enumIdentifiers);
                        tokenReferences.setContextualInfoType(ContextualInfoType.IDENTIFIER);
                        tokenReferences.setCurrentClasses(currentClassReferences);
                        tokenReferences.setReturnClasses(returnClassReferences);
                        tokenReferences.setMethods(methodReferences);
                        tokenReferences.setFields(fieldReferences);
                        tokenReferences.setConstructors(constructorReferences);
                        if (streamFlag) {
                            typeParameterReferences = tokenReferences.typeParametersReferences;
                        }
                        tokenReferences.setTypeParameterReferences(typeParameterReferences);
                        updateHashMapOfEnumConstantReferencesFromIdentifiers(enumReferences, enumIdentifiers);
                        tokenReferences.setEnums(enumReferences);
                        if (currentClassReferences.containsKey(streamClassReference)) {
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
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to walk through java classes in the java lib folder" + javaLibPath);
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
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to walk through java classes in the java lib folder" + javaLibPath);
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
        Set<MethodUsage> methodUsages = typeDeclaration.resolve().getAllMethods();
        collectMethodUsages(methodUsageIdentifiers, methodUsages, modifiersRules, annotations);
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
                List<com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParametersMap = type.asClassOrInterfaceType().resolve().asReferenceType().getTypeParametersMap();
                for (com.github.javaparser.utils.Pair<ResolvedTypeParameterDeclaration, ResolvedType> typeParameter: typeParametersMap) {
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
            boolean isAlreadyPresent = variableDeclaratorIdentifiers.get(variableName).stream().anyMatch(v -> v.equals(variableType));
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
            fieldTypeIdentifiers.get(variableName).add(new Pair<>(variableDeclarator,variableType));
        }
    }
}
