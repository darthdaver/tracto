package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import star.tracto.data.enums.JUnitAssertionType;
import star.tracto.preprocessing.OracleInsertion;
import star.tracto.preprocessing.PrefixInference;
import star.tracto.preprocessing.components.*;
import star.tracto.preprocessing.utils.TokensUtilsTestPrefix;
import star.tracto.data.enums.PrefixGenerationMode;
import star.tracto.utils.JavaUtils;
import star.tracto.utils.javaParser.JavaParserUtils;
import java.nio.file.*;
import java.util.*;


/** Entry point for test-prefix generation. */
public final class PrefixClient {

    private static final String OK = "OK";
    private static final String LIMIT = "LIMIT";
    private static final String ERROR = "ERROR";
    static final int DEFAULT_MAX_ORACLES = 4;
    static final int DEFAULT_MAX_STEPS = 128;
    /** The JDK sources used by the prefix modes when no Java version is configured. */
    private static final String DEFAULT_JAVA_VERSION = "21";
    private static final String CYCLE_BODY = "oracleCycleBody";
    private static final String CYCLE_REJECTED = "oracleCycleRejected";
    private static final String CYCLE_DRAFT = "oracleCycleDraft";
    private static final String CYCLE_INSERTION_POINTS = "oracleCycleInsertionPoints";
    private static final String CYCLE_DIAGNOSTIC = "oracleCycleDiagnostic";
    private static final String READY = "READY";
    private static final String STOP = "STOP";
    private static final String BLOCK_REQUEST_FILE = "block-request.json";
    private static final String BLOCK_RESPONSE_FILE = "block-response.txt";
    private static final String REPAIR_REQUEST_FILE = "repair-request.json";
    private static final String ORACLE_TOKEN_DIRECTORY = "oracle-next-token";
    private static final String TOKEN_STEP_FILE = "token-step.json";
    private static final String PREFIX_BLOCK_ENDPOINT = "/api/prefix_block";
    private static final String NEXT_TOKEN_ENDPOINT = "/api/next_token";
    private static final String LOCAL_SERVER = "http://127.0.0.1:";
    private static final String TRACE_PROPERTY = "tracto.prefix.trace";

    /** Coordinates generation, saves the report, and validates the outcome. */
    public static void main(String[] args) throws Exception {
        run(args, null, null, null);
    }

    /**
     * Runs a prefix mode. Non-null arguments, from a configuration file, take precedence over the
     * corresponding settings ({@code TRACTO_PREFIX_ORACLE_PORT}, {@code TRACTO_PREFIX_MAX_ORACLES},
     * {@code TRACTO_PREFIX_JAVA_VERSION}).
     */
    static void run(String[] args, String configuredOraclePort, Integer configuredMaxOracles,
                    String configuredJavaVersion) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Options options = readOptions(args, mapper, configuredOraclePort, configuredMaxOracles, configuredJavaVersion);
        String mode = options.mode().name().toLowerCase(Locale.ROOT);
        System.out.printf("mode=%s responseSource=%s%n", mode,
                options.replay() != null ? "replay" : "model");
        Files.createDirectories(options.output());
        String classpath = configureJavaParser(options.repo(), options.javaContext(), options.javaVersion());
        DatasetResult datasetResult = processDataset(options, classpath, mapper);
        saveResults(options.output(), datasetResult.results(), mapper);
        validateResults(options.selectedCases(), datasetResult);
    }

    /** Reads launcher arguments, filters, scenarios, and optional replay responses. */
    private static Options readOptions(String[] args, ObjectMapper mapper, String configuredOraclePort,
                                       Integer configuredMaxOracles, String configuredJavaVersion) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException("repo dataset output java-context port config [maxSteps] "
                    + "[--block|--interleaved|--interleaved-lines] [choices.json]");
        }
        int maxSteps = args.length > 6 ? Integer.parseInt(args[6]) : DEFAULT_MAX_STEPS;
        PrefixGenerationMode mode = readGenerationMode(args);
        if (mode == PrefixGenerationMode.PREFIX_ORACLE_CYCLE_LINES && maxSteps < 1) {
            throw new IllegalArgumentException("Line generation requires a positive maxSteps");
        }
        int replayIndex = mode == PrefixGenerationMode.TOKEN ? 7 : 8;
        Iterator<String> replay = args.length > replayIndex
                ? Arrays.asList(mapper.readValue(Path.of(args[replayIndex]).toFile(), String[].class)).iterator()
                : null;

        // Without a filter, all dataset cases are processed.
        Set<String> selectedCases = new HashSet<>(Arrays.asList(setting("TRACTO_PREFIX_CASES", "").split(",")));
        selectedCases.remove("");
        String scenarioFile = setting("TRACTO_PREFIX_SCENARIOS", null);
        Map<String, String> scenarios = scenarioFile == null || scenarioFile.isBlank() ? Map.of() : mapper.readValue(
                Path.of(scenarioFile).toFile(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});

        String oraclePort = configuredOraclePort == null
                ? setting("TRACTO_PREFIX_ORACLE_PORT", null) : configuredOraclePort;
        if (mode.usesOracleCycle() && oraclePort == null) {
            System.out.println("WARNING: TRACTO_PREFIX_ORACLE_PORT is not set: the oracle uses the prefix model server.");
        }
        int maxOracles = configuredMaxOracles != null ? configuredMaxOracles
                : Integer.parseInt(setting("TRACTO_PREFIX_MAX_ORACLES", Integer.toString(DEFAULT_MAX_ORACLES)));
        if (maxOracles < 1) throw new IllegalArgumentException("The oracle limit must be positive");
        String javaVersion = configuredJavaVersion != null ? configuredJavaVersion
                : setting("TRACTO_PREFIX_JAVA_VERSION", DEFAULT_JAVA_VERSION);
        return new Options(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                args[4], args[5], oraclePort == null ? args[4] : oraclePort, javaVersion,
                maxSteps, maxOracles, mode, replay, selectedCases, scenarios,
                Boolean.parseBoolean(setting("TRACTO_PREFIX_REPAIR", "false")),
                Boolean.parseBoolean(setting("TRACTO_PREFIX_REPAIR_RECEIVER", "false")));
    }

    /** Reads an option from a system property (IntelliJ launcher) or from the environment (CMD launcher). */
    private static String setting(String name, String defaultValue) {
        String value = System.getProperty(name, System.getenv(name));
        return value == null ? defaultValue : value;
    }

    /** Reads the optional generation strategy from the command line. */
    private static PrefixGenerationMode readGenerationMode(String[] args) {
        if (args.length <= 7 || !args[7].startsWith("--") || "--token".equals(args[7])) {
            return PrefixGenerationMode.TOKEN;
        }
        return switch (args[7]) {
            case "--block" -> PrefixGenerationMode.BLOCK;
            case "--prefix-oracle-cycle", "--interleaved" -> PrefixGenerationMode.PREFIX_ORACLE_CYCLE;
            case "--interleaved-lines" -> PrefixGenerationMode.PREFIX_ORACLE_CYCLE_LINES;
            default -> throw new IllegalArgumentException("Unknown prefix mode: " + args[7]);
        };
    }

    /** Configures JavaParser with the libraries of the analyzed project. */
    private static String configureJavaParser(Path repo, Path javaContext, String javaVersion) throws Exception {
        // A major version, or `auto` to detect it from the build files of the repository, as in `tracto` mode
        JavaUtils.setJavaLibPath(javaContext, javaVersion, repo);
        String classpath = JavaUtils.initializeJavaClassPath();
        for (Path library : SymbolicModule.findLibrariesPaths(repo)) {
            classpath += java.io.File.pathSeparator + library;
        }
        String extras = System.getProperty("tracto.prefix.classpath", System.getenv("TRACTO_PROJECT_CLASSPATH"));
        if (extras != null && !extras.isBlank()) {
            classpath += java.io.File.pathSeparator + extras;
        }
        JavaParserUtils.setRepoJavaParser(repo, classpath);
        return classpath;
    }

    /** Finds dataset JSON files and sorts them by path. */
    private static List<Path> findDatasetFiles(Path dataset) throws java.io.IOException {
        try (var files = Files.walk(dataset)) {
            return files.filter(path -> path.getFileName().toString().startsWith("oracles-datapoints-")
                            && path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    /** Reads dataset files and collects test-class outcomes. */
    private static DatasetResult processDataset(Options options, String classpath, ObjectMapper mapper) throws Exception {
        var testClassType = mapper.getTypeFactory().constructParametricType(TestClazzDatapoints.class, OracleDatapoint.class);
        var datasetFileType = mapper.getTypeFactory().constructCollectionType(List.class, testClassType);
        DatasetResult datasetResult = new DatasetResult(new ArrayList<>(), new HashSet<>());

        for (Path file : findDatasetFiles(options.dataset())) {
            System.out.printf("file=%s%n", file.getFileName());
            List<TestClazzDatapoints<OracleDatapoint>> testClasses = mapper.readValue(file.toFile(), datasetFileType);
            for (var testClass : testClasses) {
                processTestClass(testClass, options, classpath, mapper, datasetResult);
            }
        }
        return datasetResult;
    }

    /** Generates a prefix for each selected case and records its result. */
    private static void processTestClass(TestClazzDatapoints<OracleDatapoint> testClass, Options options,
                                         String classpath, ObjectMapper mapper,
                                         DatasetResult datasetResult) throws Exception {
        for (OracleDatapoint testCase : testClass.datapoints()) {
            String caseName = testCase.testPrefix().identifier();
            String originalName = originalTestName(caseName);
            if (!isSelectedCase(options.selectedCases(), originalName)) {
                continue;
            }
            datasetResult.foundCases().add(originalName);
            System.out.printf("name=%s focalClass=%s mode=%s%n", caseName,
                    testClass.focalClass().identifier(), options.mode().name().toLowerCase(Locale.ROOT));

            Path caseOutput = options.output().resolve(testClass.testClass().identifier() + "-" + caseName);
            Files.createDirectories(caseOutput);
            PrefixInference.Result result = generatePrefix(testClass, testCase, originalName, caseOutput,
                    options, classpath, mapper);
            datasetResult.results().add(result);
            String outcome = describeOutcome(result.status());
            System.out.printf("test=%s status=%s outcome=%s durationMs=%d message=%s%n",
                    result.test(), result.status(), outcome, result.milliseconds(), result.message());
        }
    }

    /** Returns whether a case passes the optional case-name filter. */
    private static boolean isSelectedCase(Set<String> selectedCases, String caseName) {
        return selectedCases.isEmpty() || selectedCases.contains(caseName);
    }

    /** Converts an internal status into a short human-readable description. */
    private static String describeOutcome(String status) {
        return switch (status) {
            case OK -> "completed";
            case LIMIT -> "stopped: step limit reached";
            default -> "failed";
        };
    }

    /** Starts block or incremental generation for a single case. */
    private static PrefixInference.Result generatePrefix(TestClazzDatapoints<OracleDatapoint> testClass,
                                                          OracleDatapoint testCase, String originalName,
                                                          Path caseOutput, Options options,
                                                          String classpath, ObjectMapper mapper) throws Exception {
        if (options.mode() == PrefixGenerationMode.BLOCK) {
            return PrefixInference.generateBlock(options.repo(), caseOutput, classpath, testClass, testCase,
                    options.repair(), options.repairReceiver(), request ->
                            generateBlockResponse(request, caseOutput, originalName, options, mapper));
        }

        if (options.mode().usesOracleCycle()) {
            return generatePrefixOracleCycle(testClass, testCase, originalName, caseOutput,
                    options, classpath, mapper);
        }
        String endpoint = tokenEndpoint(options.port(), options.config(), caseOutput, "prefix");
        return PrefixInference.generate(options.repo(), caseOutput, classpath, testClass, testCase,
                step -> options.replay() == null
                        ? TokensUtilsTestPrefix.getNextToken(step, caseOutput, endpoint)
                        : options.replay().next(),
                options.maxSteps());
    }

    /**
     * Alternates a model A insertion proposal with one oracle from original Tracto.
     * A rejected round (invalid insertion, prefix that does not compile, or test that does not compile once the
     * oracle is inserted) is retried once, showing model A the diagnostic and its rejected proposal.
     */
    private static PrefixInference.Result generatePrefixOracleCycle(
            TestClazzDatapoints<OracleDatapoint> testClass, OracleDatapoint testCase,
            String originalName, Path caseOutput, Options options,
            String classpath, ObjectMapper mapper) {
        long start = System.nanoTime();
        String testName = testCase.testPrefix().identifier();
        CycleState state = new CycleState(caseOutput);
        int segment = 1;
        try {
            var request = PrefixInference.oracleCycleTemplate(options.repo(), testClass, testCase);
            for (; segment <= options.maxOracles() + 1; segment++) {
                Path segmentOutput = Files.createDirectories(caseOutput.resolve("segment-" + segment));
                request.put(CYCLE_BODY, state.body);
                request.put(CYCLE_INSERTION_POINTS, OracleInsertion.insertionPoints(state.accepted));
                request.remove(CYCLE_DIAGNOSTIC);
                request.remove(CYCLE_REJECTED);
                for (int attempt = 0; ; attempt++) {
                    Path attemptOutput = attempt == 0 ? segmentOutput
                            : Files.createDirectories(segmentOutput.resolve("prefix-repair"));
                    String proposal = null;
                    try {
                        var generated = readCycleProposal(request, attemptOutput, originalName, options, mapper);
                        if (generated.isEmpty()) {
                            return state.incomplete(testName, LIMIT, start,
                                    "Prefix line limit reached in segment " + segment);
                        }
                        proposal = generated.get();
                        Files.writeString(attemptOutput.resolve(BLOCK_RESPONSE_FILE), proposal);
                        if (STOP.equals(proposal)) {
                            if (state.validated == null) {
                                throw new IllegalArgumentException("STOP before the first oracle");
                            }
                            PrefixInference.publishValidatedBody(state.validatedOutput, caseOutput);
                            return new PrefixInference.Result(testName, state.body, OK,
                                    elapsedMillis(start), state.validated.message());
                        }
                        if (segment > options.maxOracles()) {
                            return state.incomplete(testName, LIMIT, start, "Oracle limit reached");
                        }
                        runRound(proposal, state, attemptOutput, testClass, testCase, testName, options, classpath);
                        break;
                    } catch (IllegalArgumentException | ParseProblemException rejected) {
                        if (attempt != 0) throw rejected;
                        request.put(CYCLE_DIAGNOSTIC, String.valueOf(rejected.getMessage()));
                        if (proposal != null) request.put(CYCLE_REJECTED, proposal);
                    }
                }
            }
        } catch (Exception error) {
            return state.incomplete(testName, ERROR, start, "Segment " + segment + ": " + error);
        }
        throw new IllegalStateException("Oracle cycle ended without a result");
    }

    /**
     * Applies one insertion, asks Tracto for its oracle, and accepts the round only if the whole test compiles.
     * Rejections that model A can correct are reported as {@link IllegalArgumentException}.
     */
    private static void runRound(String proposal, CycleState state, Path output,
                                 TestClazzDatapoints<OracleDatapoint> testClass, OracleDatapoint testCase,
                                 String testName, Options options, String classpath) throws Exception {
        BlockStmt proposedBody = OracleInsertion.applyProposal(state.accepted, proposal);
        if (assertionCount(proposedBody) != assertionCount(state.accepted)) {
            throw new IllegalArgumentException("Leave the new assertion to Tracto");
        }
        String diagnostic = PrefixInference.validateOraclePrefix(options.repo(),
                output.resolve("prefix-validation"), classpath, testClass, testCase, proposedBody);
        if (!diagnostic.isEmpty()) throw new IllegalArgumentException(diagnostic);
        var insertion = OracleInsertion.position(proposedBody);
        String oracle = generatePrefixOracle(testClass, testName, output, options,
                proposedBody, OracleInsertion.assignment(insertion));
        try {
            OracleInsertion.insert(insertion, oracle);
        } catch (ParseProblemException notAStatement) {
            throw new IllegalArgumentException("The generated oracle is not a Java statement: " + oracle);
        }
        var result = PrefixInference.validateBody(options.repo(), output, classpath, testClass,
                testCase, proposedBody);
        if (!OK.equals(result.status())) {
            throw new IllegalArgumentException("With the generated oracle " + oracle
                    + " the test is invalid: " + result.message());
        }
        state.accept(proposedBody, result, output);
    }

    /** The last validated round of a prefix/oracle cycle. */
    private static final class CycleState {
        private final Path caseOutput;
        private BlockStmt accepted = new BlockStmt();
        private String body = "";
        private PrefixInference.Result validated;
        private Path validatedOutput;
        private int oracles;

        private CycleState(Path caseOutput) {
            this.caseOutput = caseOutput;
        }

        private void accept(BlockStmt proposedBody, PrefixInference.Result result, Path output) {
            accepted = proposedBody;
            body = result.prefix();
            validated = result;
            validatedOutput = output;
            oracles++;
        }

        /** Ends the cycle early, publishing the last test that compiled, if any. */
        private PrefixInference.Result incomplete(String testName, String status, long start, String message) {
            if (validatedOutput != null) {
                try {
                    PrefixInference.publishValidatedBody(validatedOutput, caseOutput);
                    message += " (published the last validated test, with " + oracles + " oracles)";
                } catch (java.io.IOException publishError) {
                    message += " (could not publish the last validated test: " + publishError + ")";
                }
            }
            return new PrefixInference.Result(testName, body, status, elapsedMillis(start), message);
        }
    }

    private static Optional<String> readCycleProposal(Map<String, String> request, Path output, String originalName,
                                                       Options options, ObjectMapper mapper) throws Exception {
        if (options.mode() == PrefixGenerationMode.PREFIX_ORACLE_CYCLE_LINES) {
            mapper.writeValue(output.resolve(BLOCK_REQUEST_FILE).toFile(), request);
            return generateLineProposal(request, output, originalName, options, mapper);
        }
        return Optional.of(requestCycleReply(request, output, originalName, options, mapper).strip());
    }

    /** Collects a draft one physical line at a time before it enters the shared oracle cycle. */
    private static Optional<String> generateLineProposal(Map<String, String> request, Path segmentOutput,
                                                         String originalName, Options options, ObjectMapper mapper)
            throws Exception {
        var lineRequest = new LinkedHashMap<>(request);
        var draft = new StringBuilder();
        for (int lines = 0; lines <= options.maxSteps(); lines++) {
            Path lineOutput = Files.createDirectories(segmentOutput.resolve("line-" + (lines + 1)));
            lineRequest.put(CYCLE_DRAFT, draft.toString());
            String response = requestCycleReply(lineRequest, lineOutput, originalName, options, mapper).stripTrailing();
            Files.writeString(lineOutput.resolve(BLOCK_RESPONSE_FILE), response);
            if (response.isBlank() && !draft.isEmpty()) {
                // An empty line inside the insertion: kept, so that the next request continues after it.
                if (lines == options.maxSteps()) return Optional.empty();
                draft.append('\n');
                continue;
            }
            if (response.isBlank() || response.lines().count() != 1) {
                throw new IllegalArgumentException("Expected one Java line, READY or STOP");
            }
            switch (response.strip()) {
                case READY -> {
                    if (draft.isEmpty()) throw new IllegalArgumentException("READY before the first line");
                    return Optional.of(draft.toString());
                }
                case STOP -> {
                    if (!draft.isEmpty()) throw new IllegalArgumentException("STOP after starting a prefix draft");
                    return Optional.of(STOP);
                }
                default -> {
                    if (lines == options.maxSteps()) return Optional.empty();
                    draft.append(response).append('\n');
                }
            }
        }
        throw new IllegalStateException("Prefix line generation ended without a result");
    }

    private static String requestCycleReply(Map<String, String> request, Path output, String originalName,
                                            Options options, ObjectMapper mapper) throws Exception {
        mapper.writeValue(output.resolve(BLOCK_REQUEST_FILE).toFile(), request);
        return generateBlockResponse(request, output, originalName, options, mapper);
    }

    /** Counts the assertions anywhere in the body, including those inside lambdas and nested calls. */
    private static int assertionCount(BlockStmt body) {
        return body.findAll(AssertStmt.class).size()
                + body.findAll(MethodCallExpr.class,
                        call -> JUnitAssertionType.isJUnitAssertion(call.getNameAsString())).size();
    }

    /** Milliseconds elapsed since the given {@link System#nanoTime()} value. */
    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Generates one oracle and records it for the current interleaved context. */
    private static String generatePrefixOracle(TestClazzDatapoints<OracleDatapoint> testClass,
                                               String prefixIdentifier, Path caseOutput,
                                               Options options, BlockStmt body, Assignment assignment) throws Exception {
        Path tokenOutput = caseOutput.resolve(ORACLE_TOKEN_DIRECTORY);
        String endpoint = tokenEndpoint(options.oraclePort(), options.config(), tokenOutput, "interleaved");
        Client.OracleGenerationResult oracle = Client.generateOracleForPrefix(
                options.repo(), tokenOutput, endpoint, testClass, prefixIdentifier, body, assignment);
        Files.writeString(caseOutput.resolve("generated-oracle.txt"), oracle.generatedOracle());
        System.out.printf("[PREFIX_ORACLE_CYCLE][ORACLE] model status=%s value=%s%n",
                oracle.status(), oracle.generatedOracle());
        if (!OK.equals(oracle.status())) throw new IllegalStateException("Oracle generation failed: " + oracle.message());
        return oracle.generatedOracle();
    }


    /** Extracts the method name before splitting, used for filters and scenarios. */
    private static String originalTestName(String caseName) {
        return caseName.replaceFirst("_split_[0-9]+$", "");
    }

    /** Writes the JSON report before validation reports possible errors. */
    private static void saveResults(Path output, List<PrefixInference.Result> results,
                                    ObjectMapper mapper) throws java.io.IOException {
        Path report = output.resolve("results.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), results);
        System.out.printf("results=%s cases=%d%n", report, results.size());
    }

    /** Reports missing requested cases and failed generations. */
    private static void validateResults(Set<String> selectedCases, DatasetResult datasetResult) {
        Set<String> missingCases = new HashSet<>(selectedCases);
        missingCases.removeAll(datasetResult.foundCases());
        if (!missingCases.isEmpty()) {
            throw new IllegalStateException("Casi richiesti non trovati: " + missingCases);
        }
        if (datasetResult.results().isEmpty()
                || datasetResult.results().stream().anyMatch(result -> !OK.equals(result.status()))) {
            throw new IllegalStateException("Generazione prefix non completata: consultare " + "results.json");
        }
    }

    /** Options shared while generating cases. */
    private record Options(Path repo, Path dataset, Path output, Path javaContext, String port, String config,
                           String oraclePort, String javaVersion,
                           int maxSteps, int maxOracles, PrefixGenerationMode mode, Iterator<String> replay, Set<String> selectedCases,
                           Map<String, String> scenarios, boolean repair, boolean repairReceiver) {}

    /** Generated results and case names found in the dataset. */
    private record DatasetResult(List<PrefixInference.Result> results, Set<String> foundCases) {}

    /** Builds the server address for incremental generation. */
    private static String tokenEndpoint(String port, String config, Path tokenOutput, String projectId) {
        String stepFile = java.net.URLEncoder.encode(
                tokenOutput.resolve(TOKEN_STEP_FILE).toAbsolutePath().toString(),
                java.nio.charset.StandardCharsets.UTF_8);
        return LOCAL_SERVER + port + NEXT_TOKEN_ENDPOINT + "?filepath=" + stepFile
                + "&config=" + config + "&project_id=" + projectId;
    }

    /** Uses a saved response or sends the request with its scenario to the model. */
    private static String generateBlockResponse(Map<String, String> request, Path caseOutput, String originalName,
                                                Options options, ObjectMapper mapper) throws Exception {
        // Replay replaces the initial response; a retry uses the server.
        boolean retry = request.containsKey("diagnostics");
        if (options.replay() != null && !retry) {
            if (Boolean.getBoolean(TRACE_PROPERTY)) {
                System.out.println("using saved model response");
            }
            return options.replay().next();
        }
        if (!options.scenarios().containsKey(originalName)) {
            return requestBlock(options.port(), request, mapper);
        }

        var requestWithScenario = new LinkedHashMap<>(request);
        requestWithScenario.put("scenario", options.scenarios().get(originalName));
        Path requestFile = caseOutput.resolve(retry ? REPAIR_REQUEST_FILE : BLOCK_REQUEST_FILE);
        mapper.writeValue(requestFile.toFile(), requestWithScenario);
        return requestBlock(options.port(), requestWithScenario, mapper);
    }

    /** Sends JSON to the Python server and returns the generated Java body. */
    private static String requestBlock(String port, Map<String, String> request, ObjectMapper mapper) throws Exception {
        if (Boolean.getBoolean(TRACE_PROPERTY)) {
            System.out.printf("POST %s port=%s%n", PREFIX_BLOCK_ENDPOINT, port);
        }
        return PrefixServerClient.postBody(LOCAL_SERVER + Integer.parseInt(port) + PREFIX_BLOCK_ENDPOINT,
                mapper, request);
    }
}

