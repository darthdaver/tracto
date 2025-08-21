package star.tracto.preprocessing.utils;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import org.javatuples.Pair;
import org.javatuples.Triplet;
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

import javax.swing.text.html.Option;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class TokensUtils {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TokensUtils.class);
    private static TokenIdentifiersCollector rootTokenIdentifiersCollector;

    /**
     * A support class to collect various types of candidate token identifiers categorized by their type when processing
     * an assertion.
     *
     */
    private static class TokenIdentifiersCollector {
        public HashMap<String, List<Path>> classPathIdentifiers;
        HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers;
        HashMap<String, List<MethodUsage>> methodIdentifiers;
        HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers;
        HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers;

        /*
         * Constructor to initialize the token identifiers collector.
         * @param classPathIdentifiers A map of class identifiers, where the key is the identifier name and the value
         *                         is a list of TypeDeclarations representing the homonyms classes
         * @param fieldIdentifiers A map of field identifiers, where the key is the identifier name and the value
         *                         is a list of VariableDeclarator representing the homonyms fields
         * @param methodIdentifiers A map of method identifiers, where the key is the identifier name and the value
         *                          is a list of MethodUsage representing the homonyms methods
         * @param constructorIdentifiers A map of constructor identifiers, where the key is the identifier name and
         *                               the value is a list of ResolvedConstructorDeclaration representing the
         *                               homonyms constructors
         */
        public TokenIdentifiersCollector(
                HashMap<String, List<Path>> classPathIdentifiers,
                HashMap<String, List<Pair<VariableDeclarator,Type>>> fieldIdentifiers,
                HashMap<String, List<MethodUsage>> methodIdentifiers,
                HashMap<String, List<ResolvedConstructorDeclaration>> constructorIdentifiers,
                HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers
        ) {
            this.classPathIdentifiers = classPathIdentifiers;
            this.fieldIdentifiers = fieldIdentifiers;
            this.methodIdentifiers = methodIdentifiers;
            this.constructorIdentifiers = constructorIdentifiers;
            this.enumIdentifiers = enumIdentifiers;
        }

        public TokenIdentifiersCollector() {
            this.classPathIdentifiers = new HashMap<>();
            this.fieldIdentifiers = new HashMap<>();
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
        List<Pair<TypeDeclaration,Path>> clazzes;
        List<Pair<VariableDeclarator,Type>> fields;
        List<MethodUsage> methods;
        List<ResolvedConstructorDeclaration> constructors;
        List<EnumConstantDeclaration> enums;

        /*
         * Constructor to initialize the token identifiers collector.
         * @param classPathIdentifiers A map of class identifiers, where the key is the identifier name and the value
         *                         is a list of TypeDeclarations representing the homonyms classes
         * @param fieldIdentifiers A map of field identifiers, where the key is the identifier name and the value
         *                         is a list of VariableDeclarator representing the homonyms fields
         * @param methodIdentifiers A map of method identifiers, where the key is the identifier name and the value
         *                          is a list of MethodUsage representing the homonyms methods
         * @param constructorIdentifiers A map of constructor identifiers, where the key is the identifier name and
         *                               the value is a list of ResolvedConstructorDeclaration representing the
         *                               homonyms constructors
         */
        public TokenReferencesCollector(
                List<Pair<TypeDeclaration, Path>> clazzes,
                List<Pair<VariableDeclarator,Type>> fields,
                List<MethodUsage> methods,
                List<ResolvedConstructorDeclaration> constructors,
                List<EnumConstantDeclaration> enums
        ) {
            this.clazzes = clazzes;
            this.fields = fields;
            this.methods = methods;
            this.constructors = constructors;
            this.enums = enums;
        }

        public TokenReferencesCollector() {
            this.clazzes = new ArrayList<>();
            this.fields = new ArrayList<>();
            this.methods = new ArrayList<>();
            this.constructors = new ArrayList<>();
            this.enums = new ArrayList<>();
        }

        public void setClazzes(List<Pair<TypeDeclaration,Path>> clazzes) {
            this.clazzes = clazzes;
        }

        public void setFields(List<Pair<VariableDeclarator,Type>> fieldTypes) {
            this.fields = fieldTypes;
        }

        public void setMethods(List<MethodUsage> methods) {
            this.methods = methods;
        }

        public void setConstructors(List<ResolvedConstructorDeclaration> constructors) {
            this.constructors = constructors;
        }

        public void setEnums(List<EnumConstantDeclaration> enums) {
            this.enums = enums;
        }
    }

    /**
     * Processes the test prefix and the compilation unit of the test class and the focal class,
     * and generates a list of token steps from the target assertion
     *
     * @param repoRootPath the path to the repository root
     */
    public static Pair<TestClazzDatapoints,HashMap<String, HashMap<String, List<String>>>> generateTokenSteps (
            Path repoRootPath,
            TestClazzDatapoints<OracleDatapoint> testClazzDatapoints
    ) throws IllegalStateException {
        // Create a map to store the processing errors when processing the datapoints
        HashMap<String, HashMap<String, List<String>>> clazzDatapointsErrors = new HashMap<>();
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
        String fqnTestClass = testClazzDatapoints.testClass().packageIdentifier() + "." + testClazzDatapoints.testClass().identifier();
        String fqnFocalClass = testClazzDatapoints.focalClass().packageIdentifier() + "." + testClazzDatapoints.focalClass().identifier();
        // Get the path of the test directory of the repository
        Path repoTestPath = Path.of(testFilePath.toString().replace(FilesUtils.getFQNPath(fqnTestClass).toString(), ""));
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
                return new Pair<>(testClazzDatapointsBuilder.build(), clazzDatapointsErrors);
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
                        List<Pair<TokenIdentifiersCollector, TokenReferencesCollector>> tokenIdentifiersStack = new ArrayList<>();
                        rootTokenIdentifiersCollector = new TokenIdentifiersCollector();
                        // Get the target assertion from the test prefix
                        String originalTargetAssertion = datapoint.target();
                        String targetAssertion = originalTargetAssertion.replace(" ", "");
                        //if (targetAssertion.contains("assertEquals(HttpMethod")) {
                        try {
                            // Setup error map for the test class
                            clazzDatapointsErrors.putIfAbsent(testCase.getNameAsString(), new HashMap<>());
                            // Setup error map for the test case
                            clazzDatapointsErrors.get(testCase.getNameAsString()).putIfAbsent(targetAssertion, new ArrayList<>());
                            clazzDatapointsErrors.get(testCase.getNameAsString()).putIfAbsent("targetFound", new ArrayList<>());
                            clazzDatapointsErrors.get(testCase.getNameAsString()).putIfAbsent("targetMissing", new ArrayList<>());
                            setUpRootTokenIdentifiers(rootTokenIdentifiersCollector, clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion), repoSourcePath, repoTestPath, testFilePath, focalFilePath, tcCu, fcCu, testCase);
                            TokenIdentifiersCollector currentTokenIdentifiersCollector = cloneRootTokenIdentifiers();
                            TokenReferencesCollector currentTokenReferences = new TokenReferencesCollector();
                            tokenIdentifiersStack.add(new Pair<>(currentTokenIdentifiersCollector, currentTokenReferences));
                            List<TokenStep> tokenSteps = new ArrayList<>();
//                        Statement tgtStatement;
//                        if (config.splitStrategy() == SplitStrategyType.ASSERTION) {
//                            // Get the assertion statement in the test case
//                            Optional<Statement> lastAssertion = JavaParserUtils.getLastAssertionInMethodDeclaration(testCase);
//                            if (lastAssertion.isEmpty()) {
//                                logger.error("No assertion found in test case: " + testCase.getNameAsString());
//                                continue;
//                            }
//                            tgtStatement = lastAssertion.get();
//                        } else {
//                            List<Statement> statements = testCase.findAll(Statement.class);
//                            tgtStatement = statements.get(statements.size() - 1);
//                        }
                            List<String> tokens = Antlr4Utils.tokenizeSnippet(targetAssertion);

                            try {
                                JUnitAssertionType.fromString(tokens.get(0));
                            } catch (IllegalArgumentException e) {
                                String message = "Assertion do not start with JUnit api (do not implemented yet): " + targetAssertion + " - " + e.getMessage();
                                logger.error(message);
                                clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion).add(message);
                                continue;
                            }

                            String partialSnippet = "";
                            for (int i = 0; i < tokens.size(); i++) {
                                // Get the current token to process
                                String token = tokens.get(i);

                                // Define a new token step builder for the current token step
                                TokenStepBuilder tokenStepBuilder = new TokenStepBuilder();
                                tokenStepBuilder.setPartialSnippet(partialSnippet);
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
                                List<String> nextANTLRTokens = Antlr4Utils.parseSnippet(JUnitAssertionType.fromString(tokens.get(0)), partialSnippet);

                                if (nextANTLRTokens.isEmpty() && !token.equals(';')) {
                                    // raise exception
                                    IllegalStateException exception = new IllegalStateException("Unexpected empty token candidates after partial snippet: " + partialSnippet + " and token: " + token);
                                    clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion).add(exception.getMessage());
                                    throw exception;
                                }

                                for (String nextANTLRToken : nextANTLRTokens) {
                                    // A token can be: a Java token, a literal, an identifier
                                    if (literalsSet.contains(nextANTLRToken)) {
                                        adjustedNextGenericTokens.add(nextANTLRToken);
                                    } else if (nextANTLRToken.equals("IDENTIFIER")) {
                                        if (!Antlr4Utils.getAntlrToken(tokens.get(i - 1)).equals(AntlrToken.IDENTIFIER)) {
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.classPathIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.methodIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.constructorIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.fieldIdentifiers.keySet());
                                            adjustedNextConcreteTokens.addAll(currentTokenIdentifiersCollector.enumIdentifiers.keySet());
                                        }
                                    } else {
                                        // For non-literal, non-IDENTIFIER tokens (e.g., keywords),
                                        // we can filter them based on expectedType if needed.
                                        // For now, we add them and filter afterward.
                                        adjustedNextJavaTokens.add(nextANTLRToken);
                                    }
                                }

                                if (!adjustedNextConcreteTokens.contains(token) && !adjustedNextJavaTokens.contains(token) && !adjustedNextGenericTokens.contains(token)) {
                                    if (!literalsSet.contains(Antlr4Utils.getAntlrToken(token).toString())) {
                                        String message = "Unexpected token: " + token + " not found in the collected list of candidate tokens: " + partialSnippet;
                                        logger.error(message);
                                        clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion).add(message);
                                        clazzDatapointsErrors.get(testCase.getNameAsString()).get("targetMissing").add(token);
                                    } else {
                                        clazzDatapointsErrors.get(testCase.getNameAsString()).get("targetFound").add(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                    }
                                } else {
                                    clazzDatapointsErrors.get(testCase.getNameAsString()).get("targetFound").add(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                }

                                tokenStepBuilder.setNextPossibleGenericTokens(adjustedNextGenericTokens);
                                tokenStepBuilder.setNextPossibleProjectTokens(adjustedNextConcreteTokens);
                                tokenStepBuilder.setNextPossibleJavaTokens(adjustedNextJavaTokens);
                                tokenStepBuilder.setSyntaxCorrect(false);
                                tokenStepBuilder.setTarget(Antlr4Utils.getAntlrToken(token) + ":" + token);
                                tokenStepBuilder.setContextualInfos(tokenIdentifiersStack.stream().map(t -> generateTokenStepContextualInfo(t.getValue1())).collect(Collectors.toList()));
                                tokenSteps.add(tokenStepBuilder.build());
                                // Update the partial snippet
                                partialSnippet += token;
                                Pair<TokenIdentifiersCollector, TokenReferencesCollector> updatedCollectors = updateTokenIdentifiers(tokenIdentifiersStack, token, testClass, testCase.getNameAsString(), clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion));
                                currentTokenIdentifiersCollector = updatedCollectors.getValue0();
                                currentTokenReferences = updatedCollectors.getValue1();
                            }
                            partialOracleDatapointBuilder.setTestPrefix(testPrefix);
                            partialOracleDatapointBuilder.setTarget(targetAssertion);
                            partialOracleDatapointBuilder.setDatapoints(tokenSteps);
                            partialOraclesDatapoints.add(partialOracleDatapointBuilder.build());
                        } catch (Exception e) {
                            logger.error(e.getMessage());
                            clazzDatapointsErrors.get(testCase.getNameAsString()).get(targetAssertion).add(e.getMessage());
                        }
                        //}
                        break;
                    }
                }
            }
            testClazzDatapointsBuilder.setDatapoints(partialOraclesDatapoints);
        } catch (IOException e) {
            logger.error("Error reading file: " + testFilePath);
        }
        return new Pair<>(testClazzDatapointsBuilder.build(), clazzDatapointsErrors);
    }

    public static TokenIdentifiersCollector cloneRootTokenIdentifiers () {
        TokenIdentifiersCollector clonedTokenIdentifierCollector = new TokenIdentifiersCollector(
                new HashMap<>(rootTokenIdentifiersCollector.classPathIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.fieldIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.methodIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.constructorIdentifiers),
                new HashMap<>(rootTokenIdentifiersCollector.enumIdentifiers)
        );
        return clonedTokenIdentifierCollector;
    }

    /**
     * Resets the token identifiers map for the given compilation units (test class and focal class) and the test prefix
     * method declaration.
     * This is useful to clear any previously collected token identifiers before processing a new test case or a new
     * expression within the same test case.
     *
     * @param tokenIdentifiersCollector the map of token identifiers to reset
     * @param repoSourcePath the path to the source directory of the repository
     * @param repoTestPath the path to the test directory of the repository
     * @param tcCu the compilation unit of the test class
     * @param fcCu the compilation unit of the focal class
     * @param tp the method declaration of the test prefix
     */
    public static void setUpRootTokenIdentifiers (
            TokenIdentifiersCollector tokenIdentifiersCollector,
            List<String> clazzDatapointsErrors,
            Path repoSourcePath,
            Path repoTestPath,
            Path tcFilePath,
            Path fcFilePath,
            CompilationUnit tcCu,
            CompilationUnit fcCu,
            MethodDeclaration tp
    ) {
        TypeDeclaration tc = tcCu.getPrimaryType().get();
        HashMap<String, List<Pair<VariableDeclarator,Type>>> variableTypeIdentifiers = new HashMap<>();
        try {
            updateHashMapOfVariableTypeIdentifiers(variableTypeIdentifiers, tc.getFields(), List.of(
                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))),
                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(true)))
            ));
            updateHashMapOfVariableTypeIdentifiers(variableTypeIdentifiers, tp);
        } catch (Exception e) {
            String message = "Error collecting root variables type identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.add(e.getMessage());
        }
        HashMap<String, List<Path>> javaAPIIdentifiers = new HashMap<>();
        HashMap<String, List<Path>> typeDeclarationPathIdentifiers = new HashMap<>();
        List<List<Pair<Modifier.Keyword, Boolean>>> typeDeclModifiersRules = List.of(List.of(
                new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(false)),
                new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))
        ));
        //updateHashMapOfJavaAPITypeDeclarationPathIdentifiers(javaAPIIdentifiers, basePath, typeDeclModifiersRules);
        try {
            List<String> errorMessages = new ArrayList<>();
            updateHashMapOfTypeDeclarationPathIdentifiers(typeDeclarationPathIdentifiers, List.of(repoTestPath, repoSourcePath), tcFilePath, typeDeclModifiersRules, errorMessages);
            updateHashMapOfTypeDeclarationPathIdentifiers(typeDeclarationPathIdentifiers, List.of(repoSourcePath), fcFilePath, typeDeclModifiersRules, errorMessages);
            clazzDatapointsErrors.addAll(errorMessages);
        } catch (Exception e) {
            String message = "Error collecting root type declaration identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.add(e.getMessage());
        }
        HashMap<String, List<MethodUsage>> methodUsageIdentifiers = new HashMap<>();
        try {
            updateHashMapOfMethodUsageIdentifiers(methodUsageIdentifiers, tc, new ArrayList<>(), List.of(new Pair("Test", Boolean.valueOf(false))));
        } catch (Exception e) {
            String message = "Error collecting root method usage identifiers for test case: " + tp.getNameAsString() + " - " + e.getMessage();
            logger.error(message);
            clazzDatapointsErrors.add(e.getMessage());
        }
        HashMap<String, List<ResolvedConstructorDeclaration>> resolvedConstructorsIdentifiers = new HashMap<>();
        //updateHashMapOfResolvedConstructorDeclarationIdentifiers(resolvedConstructorsIdentifiers, tc);
        // Reset the token identifiers map
        tokenIdentifiersCollector.setClassIdentifiersMap(typeDeclarationPathIdentifiers);
        tokenIdentifiersCollector.setFieldIdentifiersMap(variableTypeIdentifiers);
        tokenIdentifiersCollector.setMethodIdentifiersMap(methodUsageIdentifiers);
        tokenIdentifiersCollector.setConstructorIdentifiersMap(resolvedConstructorsIdentifiers);
    }

    public static TokenStep.ContextualInfo generateTokenStepContextualInfo(TokenReferencesCollector tokenReferences) {
        List<Clazz> clazzes = new ArrayList<>();
        List<Callable> methods = new ArrayList<>();
        List<Callable> constructors = new ArrayList<>();
        List<TokenStep.FieldContextualInfo> fields = new ArrayList<>();
        List<String> enumConstants = new ArrayList<>();

        for (Pair<TypeDeclaration,Path> classReference : tokenReferences.clazzes) {
            clazzes.add(TestUtils.processTypeDeclarationForContextualInfo(classReference.getValue0()));
        }

        for (MethodUsage method : tokenReferences.methods) {
            methods.add(TestUtils.processCallable(method));
        }

        for (ResolvedConstructorDeclaration constructor : tokenReferences.constructors) {
            constructors.add(TestUtils.processCallable(constructor));
        }

        for (Pair<VariableDeclarator,Type> field : tokenReferences.fields) {
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

        for (EnumConstantDeclaration enumConstant : tokenReferences.enums) {
            enumConstants.add(enumConstant.getNameAsString());
        }

        return new TokenStep.ContextualInfo(
                clazzes,
                methods,
                constructors,
                fields,
                enumConstants
        );
    }

    public static Pair<TokenIdentifiersCollector, TokenReferencesCollector> updateTokenIdentifiers(
            List<Pair<TokenIdentifiersCollector, TokenReferencesCollector>> tokenIdentifiersStack,
            final String token,
            TypeDeclaration tc,
            String testCaseName,
            List<String> clazzDatapointsErrors
    ) {
        AntlrTokenAction tokenAction = AntlrTokenMapper.getTokenAction(token);
        AntlrToken tokenType = Antlr4Utils.getAntlrToken(token);
        Pair<TokenIdentifiersCollector, TokenReferencesCollector> currentTokenIdentifiersAndCategory = tokenIdentifiersStack.get(tokenIdentifiersStack.size() - 1);
        TokenIdentifiersCollector tokenIdentifiersCollector = currentTokenIdentifiersAndCategory.getValue0();
        TokenReferencesCollector tokenReferences = currentTokenIdentifiersAndCategory.getValue1();
        switch (tokenAction) {
            case ADD_ROOT:
                tokenIdentifiersStack.add(new Pair<>(cloneRootTokenIdentifiers(), new TokenReferencesCollector()));
                break;
            case SET_TO_ROOT:
                tokenIdentifiersStack.remove(currentTokenIdentifiersAndCategory);
                tokenIdentifiersStack.add(new Pair<>(cloneRootTokenIdentifiers(), new TokenReferencesCollector()));
                break;
            case KEEP_CURRENT:
                break;
            case REMOVE_CURRENT:
                tokenIdentifiersStack.remove(currentTokenIdentifiersAndCategory);
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
                    case STRING_LITERAL:
                    case TEXT_BLOCK:
                    case NULL_LITERAL:
                        tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(new HashMap<>());
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
                        tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        break;
                    case THIS:
                        HashMap<String, List<Pair<VariableDeclarator,Type>>> variableTypeIdentifiers = new HashMap<>();
                        HashMap<String, List<MethodUsage>> methodUsageIdentifiers = new HashMap<>();
                        HashMap<String, List<ResolvedConstructorDeclaration>> resolvedConstructorsIdentifiers = new HashMap<>();
                        HashMap<String, List<EnumConstantDeclaration>> enumIdentifiers = new HashMap<>();
                        try {
                            updateHashMapOfVariableTypeIdentifiers(variableTypeIdentifiers, tc.getFields(), List.of(
                                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))),
                                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(true)))
                            ));
                            updateHashMapOfMethodUsageIdentifiers(methodUsageIdentifiers, tc, new ArrayList<>(), List.of(new Pair("Test", Boolean.valueOf(false))));
                            updateHashMapOfResolvedConstructorDeclarationIdentifiers(resolvedConstructorsIdentifiers, tc);
                            updateHashMapOfEnumConstantDeclarationIdentifiers(enumIdentifiers, tc);
                        } catch (Exception e) {
                            String message = "Error collecting variable type identifiers for test case: " + testCaseName + " - " + e.getMessage();
                            logger.error(message);
                            clazzDatapointsErrors.add(e.getMessage());
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setMethodIdentifiersMap(methodUsageIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(variableTypeIdentifiers);
                        tokenReferences.setClazzes(new ArrayList<>());
                        tokenReferences.setFields(variableTypeIdentifiers.values().stream().flatMap(List::stream).collect(Collectors.toList()));
                        tokenReferences.setMethods(methodUsageIdentifiers.values().stream().flatMap(List::stream).collect(Collectors.toList()));
                        tokenReferences.setConstructors(resolvedConstructorsIdentifiers.values().stream().flatMap(List::stream).collect(Collectors.toList()));
                        tokenReferences.setEnums(enumIdentifiers.values().stream().flatMap(List::stream).collect(Collectors.toList()));
                        break;
                    case THROW:
                    case THROWS:
                        HashMap<String, List<Path>> typeDeclarationPathIdentifiers = new HashMap<>();
                        for (String key: tokenIdentifiersCollector.classPathIdentifiers.keySet()) {
                            List<Path> keyValues = tokenIdentifiersCollector.classPathIdentifiers.get(key);
                            for (Path path: keyValues) {
                                try {
                                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(path);
                                    for (TypeDeclaration typeDeclaration: cu.getTypes()) {
                                        if (typeDeclaration.getNameAsString().equals(key)) {
                                            if (typeDeclaration instanceof ClassOrInterfaceDeclaration) {
                                                List<ClassOrInterfaceType> extendedTypes = ((ClassOrInterfaceDeclaration) typeDeclaration).getExtendedTypes();
                                                if (extendedTypes.stream().anyMatch(t -> t.getNameAsString().endsWith("Exception"))) {
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
                        tokenIdentifiersCollector.setClassIdentifiersMap(typeDeclarationPathIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                        tokenReferences.setClazzes(typeDeclarationPathIdentifiers.values().stream()
                                .flatMap(List::stream).map(
                                p -> {
                                    try {
                                        CompilationUnit cu = JavaParserUtils.getCompilationUnit(p);
                                        return JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, token).stream().map(t -> new Pair<>(t, p)).collect(Collectors.toList());
                                    } catch (IOException e) {
                                        logger.error("Error reading compilation unit from file: " + p);
                                        return new ArrayList<Pair<TypeDeclaration,Path>>();
                                    }
                                }
                        ).flatMap(List::stream).collect(Collectors.toList()));
                        tokenReferences.setFields(new ArrayList<>());
                        tokenReferences.setMethods(new ArrayList<>());
                        tokenReferences.setConstructors(new ArrayList<>());
                        tokenReferences.setEnums(new ArrayList<>());
                        break;
                    case IDENTIFIER:
                        boolean classesMatch = tokenIdentifiersCollector.classPathIdentifiers.containsKey(token);
                        boolean methodsMatch = tokenIdentifiersCollector.methodIdentifiers.containsKey(token);
                        boolean fieldsMatch = tokenIdentifiersCollector.fieldIdentifiers.containsKey(token);
                        boolean constructorsMatch = tokenIdentifiersCollector.constructorIdentifiers.containsKey(token);

                        if (!classesMatch && !methodsMatch && !fieldsMatch && !constructorsMatch) {
                            tokenIdentifiersCollector.setClassIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setMethodIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                            tokenIdentifiersCollector.setFieldIdentifiersMap(new HashMap<>());
                            break;
                        }

                        HashMap<String, List<Path>> updatedTypeDeclaratorIdentifiers = new HashMap<>();
                        HashMap<String, List<Pair<VariableDeclarator,Type>>> updatedVarTypeIdentifiers = new HashMap<>();
                        HashMap<String, List<MethodUsage>> updatedMethodUsageIdentifiers = new HashMap<>();
                        HashMap<String, List<EnumConstantDeclaration>> updatedEnumConstantDeclarations = new HashMap<>();
                        List<TypeDeclaration> matchedTypes = new ArrayList<>();
                        List<MethodUsage> matchedMethods = new ArrayList<>();
                        List<ResolvedConstructorDeclaration> matchedConstructors = new ArrayList<>();
                        List<Pair<VariableDeclarator,Type>> matchedFields = new ArrayList<>();

                        if (classesMatch) {
                            List<Path> classPaths = tokenIdentifiersCollector.classPathIdentifiers.get(token);
                            List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = new ArrayList<>();
                            modifiers.add(List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))));
                            modifiers.add(List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))));
                            CompilationUnit testCu = (CompilationUnit) tc.getParentNode().get();

                            for (final Path classPath: classPaths) {
                                try {
                                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(classPath);
                                    Optional<PackageDeclaration> packageDeclaration = cu.getPackageDeclaration();
                                    if (packageDeclaration.isPresent()) {
                                        String packageName = packageDeclaration.get().getNameAsString();
                                        if (testCu.getPackageDeclaration().get().getNameAsString().equals(packageName)) {
                                            modifiers.add(List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(true))));
                                        }
                                    }

                                    List<TypeDeclaration> matchedTypeDeclarations = JavaParserUtils.getTypeDeclarationsFromCompilationUnitAndName(cu, token);
                                    tokenReferences.setClazzes(matchedTypeDeclarations.stream().map(t -> new Pair<>(t, classPath)).collect(Collectors.toList()));

                                    for (TypeDeclaration targetTypeDeclaration: matchedTypeDeclarations) {
                                        targetTypeDeclaration.getMembers().stream()
                                                .filter(m -> ((BodyDeclaration) m).isTypeDeclaration())
                                                .forEach(t -> updatedTypeDeclaratorIdentifiers.put(((TypeDeclaration) t).getNameAsString(), List.of(classPath)));
                                        HashMap<String, List<ResolvedConstructorDeclaration>> targetTypeDeclarationConstructorsIdentifiers = new HashMap<>();
                                        updateHashMapOfResolvedConstructorDeclarationIdentifiers(targetTypeDeclarationConstructorsIdentifiers, targetTypeDeclaration);
                                        matchedConstructors.addAll(targetTypeDeclarationConstructorsIdentifiers.values().stream().flatMap(List::stream).collect(Collectors.toList()));
                                        tokenIdentifiersCollector.setClassIdentifiersMap(updatedTypeDeclaratorIdentifiers);
                                        updateHashMapOfEnumConstantDeclarationIdentifiers(updatedEnumConstantDeclarations, targetTypeDeclaration);
                                        updateHashMapOfMethodUsageIdentifiers(updatedMethodUsageIdentifiers, targetTypeDeclaration, modifiers, List.of(new Pair("Test", Boolean.valueOf(false))));
                                        updateHashMapOfVariableTypeIdentifiers(updatedVarTypeIdentifiers, targetTypeDeclaration.getFields(), modifiers);
                                    }
                                } catch (IOException e) {
                                    logger.error("Error reading compilation unit from file: " + classPath);
                                }
                            }
                        }

                        List<ResolvedType> resolvedTypes = new ArrayList<>();

                        if (methodsMatch) {
                            List<MethodUsage> methodUsages = tokenIdentifiersCollector.methodIdentifiers.get(token);
                            for (MethodUsage methodUsage: methodUsages) {
                                ResolvedType resolvedType = methodUsage.getDeclaration().getReturnType();
                                resolvedTypes.add(resolvedType);
                            }
                            matchedMethods.addAll(methodUsages);
                        }

                        if (fieldsMatch) {
                            List<Pair<VariableDeclarator,Type>> fields = tokenIdentifiersCollector.fieldIdentifiers.get(token);
                            for (Pair<VariableDeclarator,Type> field: fields) {
                                Type varType = field.getValue1();
                                try {
                                    ResolvedType resolvedType = varType.resolve();
                                    resolvedTypes.add(resolvedType);
                                } catch (Exception e) {
                                    logger.error("Error resolving reference type: " + varType);
                                }
                            }
                            matchedFields.addAll(fields);
                        }

                        for (ResolvedType resolvedType: resolvedTypes) {
                            List<List<Pair<Modifier.Keyword,Boolean>>> modifiers = List.of(
                                    List.of(new Pair(Modifier.Keyword.PUBLIC, Boolean.valueOf(true))),
                                    List.of(new Pair(Modifier.Keyword.PRIVATE, Boolean.valueOf(false))),
                                    List.of(new Pair(Modifier.Keyword.PROTECTED, Boolean.valueOf(false)))
                            );
                            if (resolvedType.isArray()) {
                                ResolvedArrayType resolvedArrayType = resolvedType.asArrayType();
                                while (resolvedArrayType.isArray()) {
                                    resolvedArrayType = resolvedArrayType.asArrayType();
                                    resolvedType = resolvedArrayType.getComponentType();
                                }
                            }
                            if (resolvedType.isReferenceType()) {
                                try {
                                    try {
                                        collectMethodUsages(
                                                updatedMethodUsageIdentifiers,
                                                resolvedType.asReferenceType().getTypeDeclaration().get().getAllMethods(),
                                                modifiers,
                                                List.of(new Pair("Test", Boolean.valueOf(false)))
                                        );
                                    } catch (Exception e) {
                                        String message = "Error resolving reference type: " + resolvedType + " - " + e.getMessage();
                                        logger.error(message);
                                        clazzDatapointsErrors.add(message);
                                        collectResolvedMethodDeclaration(
                                                updatedMethodUsageIdentifiers,
                                                resolvedType.asReferenceType().getTypeDeclaration().get().getDeclaredMethods(),
                                                modifiers,
                                                List.of(new Pair("Test", Boolean.valueOf(false)))
                                        );
                                    }

                                    List<FieldDeclaration> fieldDeclarations =  resolvedType.asReferenceType().getTypeDeclaration().get().toAst().get().findAll(FieldDeclaration.class);
                                    updateHashMapOfVariableTypeIdentifiers(updatedVarTypeIdentifiers, fieldDeclarations, modifiers);
                                } catch (Exception e) {
                                    String message = "Error resolving reference type: " + resolvedType + " - " + e.getMessage();
                                    logger.error(message);
                                    clazzDatapointsErrors.add(message);
                                }
                            }
                        }
                        tokenIdentifiersCollector.setClassIdentifiersMap(updatedTypeDeclaratorIdentifiers);
                        tokenIdentifiersCollector.setMethodIdentifiersMap(updatedMethodUsageIdentifiers);
                        tokenIdentifiersCollector.setFieldIdentifiersMap(updatedVarTypeIdentifiers);
                        tokenIdentifiersCollector.setConstructorIdentifiersMap(new HashMap<>());
                        tokenIdentifiersCollector.setEnumIdentifiersMap(updatedEnumConstantDeclarations);
                        tokenReferences.setMethods(matchedMethods);
                        tokenReferences.setFields(matchedFields);
                        tokenReferences.setConstructors(matchedConstructors);
                        tokenReferences.setEnums(updatedEnumConstantDeclarations.values().stream().flatMap(List::stream).collect(Collectors.toList()));
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
                for (Path repoSourcePath : repoSourcePaths) {
                    JavaParserUtils.collectPathsOfClassesInSamePackage(typeDeclarationPathIdentifiers, repoSourcePath, cu, modifiersRules, errorMessages);
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
            for (Path repoSourcePath : repoSourcePaths) {
                JavaParserUtils.collectClassesInSamePackage(typeDeclarationIdentifiers, repoSourcePath, cu, modifiersRules);
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
    public static void updateHashMapOfVariableTypeIdentifiers(
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
     * @param variableTypeIdentifiers The map to update with variable declarators
     * @param methodDeclaration The method declaration from which to collect variable declarators
     */
    public static void updateHashMapOfVariableTypeIdentifiers(
            HashMap<String, List<Pair<VariableDeclarator,Type>>> variableTypeIdentifiers,
            MethodDeclaration methodDeclaration
    ) {
        HashMap<String, VariableDeclarator> tcVariablesMap = JavaParserUtils.collectExistingVariables(new HashMap<>(), methodDeclaration.getBody().get());

        for (Map.Entry<String, VariableDeclarator> entry : tcVariablesMap.entrySet()) {
            String variableName = entry.getKey();
            VariableDeclarator variableDeclarator = entry.getValue();
            Type variableType = variableDeclarator.getType();
            if (!variableTypeIdentifiers.containsKey(variableName)) {
                variableTypeIdentifiers.put(variableName, new ArrayList<>());
            }
            variableTypeIdentifiers.get(variableName).add(new Pair<>(variableDeclarator,variableType));
        }
    }
}
