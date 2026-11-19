package star.tracto;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.javatuples.Pair;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.preprocessing.components.Assignment;
import star.tracto.preprocessing.utils.TestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.components.Callable;
import star.tracto.preprocessing.utils.TokensUtils;
import star.tracto.data.enums.OracleGenerationMode;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.JavaUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private static final String DATASET_CONFIG_RESOURCE = "/dataset/oracles-dataset_config.json";

    /** Result of one original Tracto oracle generation step. */
    public record OracleGenerationResult(String generatedOracle, String status, String message) {}

    /**
     * Runs the original token-by-token oracle generator for one test-class datapoint.
     * This small entry point is reusable by the future interleaved prefix flow.
     */
    public static Pair<List<List<String>>, HashMap<String, HashMap<String, List<String>>>> generateOracle(
            Path repoRootPath, Path nextTokenPath, String mlModelApiUrl,
            TestClazzDatapoints<OracleDatapoint> datapoint) {
        return TokensUtils.generateInference(repoRootPath, nextTokenPath, mlModelApiUrl, datapoint);
    }

    /** Generates one oracle using an updated test-prefix body and exposes the generated statement. */
    public static OracleGenerationResult generateOracleForPrefix(
            Path repoRootPath, Path nextTokenPath, String mlModelApiUrl,
            TestClazzDatapoints<OracleDatapoint> original, String prefixIdentifier, BlockStmt prefixBody,
            Assignment assignment)
            throws IOException {
        // The prefix is rebuilt as the dataset stores it (method source with the mask, methods invoked by
        // the generated statements), so that the oracle model sees the same prompt format as in training
        // and no information of the reference test.
        // TokensUtils writes each token step here before calling the model server.
        Files.createDirectories(nextTokenPath);
        Callable updatedPrefix = generatedPrefix(repoRootPath, original, prefixIdentifier, prefixBody);
        OracleDatapoint updatedPoint = new OracleDatapoint(updatedPrefix, "", assignment);
        TestClazzDatapoints<OracleDatapoint> updated = new TestClazzDatapoints<>(original.junitVersion(),
                original.testClass(), original.focalClass(), List.of(updatedPoint));
        Pair<List<List<String>>, HashMap<String, HashMap<String, List<String>>>> result =
                TokensUtils.generateInference(repoRootPath, nextTokenPath, mlModelApiUrl, updated,
                        OracleGenerationMode.PREFIX_ORACLE_CYCLE);
        // Row layout: 0-6 datapoint data, 7 generated oracle, 8 status, 9 duration (ms), 10 message.
        if (result.getValue0().isEmpty() || result.getValue0().get(0).size() < 11) {
            return new OracleGenerationResult("", "ERROR", "Oracle generator returned no complete row");
        }
        List<String> row = result.getValue0().get(0);
        return new OracleGenerationResult(row.get(7), row.get(8), row.get(10));
    }

    /** Builds the dataset test prefix of a generated body, in the copy of the test class saved by the dataset. */
    private static Callable generatedPrefix(Path repoRootPath, TestClazzDatapoints<OracleDatapoint> data,
                                            String prefixIdentifier, BlockStmt prefixBody) throws IOException {
        Path testFile = repoRootPath.resolve(data.testClass().prefixFilePath().substring(1));
        Path focalFile = repoRootPath.resolve(data.focalClass().filePath().substring(1));
        CompilationUnit testUnit = JavaParserUtils.getCompilationUnit(testFile);
        CompilationUnit focalUnit = JavaParserUtils.getCompilationUnit(focalFile);
        TypeDeclaration<?> testType = testUnit.getPrimaryType().orElse(testUnit.getType(0));
        TypeDeclaration<?> focalType = focalUnit.getPrimaryType().orElse(focalUnit.getType(0));
        MethodDeclaration prefixMethod = testType.getMethodsByName(prefixIdentifier).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Test prefix not found: " + prefixIdentifier));
        prefixMethod.setBody(prefixBody.clone());
        String fqnFocalClass = data.focalClass().packageIdentifier() + "." + data.focalClass().identifier();
        Path repoSourcePath = Path.of(focalFile.toString().replace(FilesUtils.getFQNPath(fqnFocalClass).toString(), ""));
        return TestUtils.processGeneratedPrefix(repoSourcePath, focalType, testType, prefixMethod, datasetConfig());
    }

    /** The oracles dataset configuration packaged with the module (mask placeholder, target strategy). */
    private static OraclesDatasetConfig datasetConfig() throws IOException {
        try (InputStream input = Client.class.getResourceAsStream(DATASET_CONFIG_RESOURCE)) {
            if (input == null) {
                throw new FileNotFoundException("Missing resource " + DATASET_CONFIG_RESOURCE);
            }
            return new ObjectMapper().readValue(input, OraclesDatasetConfig.class);
        }
    }

    public static void run(String[] args) throws IOException {
        String usage = args.length > 0 ? args[0] : "inference";
        String projectIdentifier = args[1];
        Path repoRootPath = Path.of(args[2]);
        Path outputPath = Path.of(args[3]);
        Path basePath = Path.of(args[4]);
        Path inputDatasetPath = Path.of(args[5]);;
        int serverPort = Integer.parseInt(args[6]);
        int promptConfig = Integer.parseInt(args[7]);
        // The Java version of the repository: a major version, or `auto` to detect it from its build files
        String javaVersion = args[8];
        Path projectOutputPath = outputPath.resolve(projectIdentifier);
        Path nextTokenPath = projectOutputPath.resolve("next-token");
        FilesUtils.createDirectories(nextTokenPath);
        FilesUtils.createDirectories(projectOutputPath);
        String mlModelAPIUrl = String.format("http://127.0.0.1:%s/api/next_token?filepath=%s/token-step.json&config=%s&project_id=%s", serverPort, nextTokenPath, promptConfig, projectIdentifier);
        JavaUtils.setJavaLibPath(basePath, javaVersion, repoRootPath);
        String classpath = JavaUtils.initializeJavaClassPath();
        // Update classpath with the libraries paths
        List<Path> librariesPaths = SymbolicModule.findLibrariesPaths(repoRootPath);
        for (Path libraryPath : librariesPaths) {
            classpath += ":" + libraryPath.toString();
        }
        // Instantiate csv writer to save information about processed test cases and target oracles
        FilesUtils.CSVWriter csvWriter = new FilesUtils.CSVWriter(
                projectOutputPath.resolve("oracles.csv"),
                List.of("test_id","test_class_path","class_name","test_name","signature","tp_body","tgt","generated","status","time","message")
        );
        // Setup the repository root path for JavaParser
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // ObjectMapper for JSON processing
        ObjectMapper objectMapper = new ObjectMapper();
        // Define the filename pattern to match: oracles-datapoints-*.json
        Pattern filePattern = Pattern.compile(".*oracles-datapoints-.*\\.json");
        // Set up Jackson type for reading input JSON
        ObjectMapper mapper = new ObjectMapper();
        JavaType innerType = mapper.getTypeFactory().constructParametricType(TestClazzDatapoints.class, OracleDatapoint.class);
        // Walk through the directory and process files recursively
        Files.walk(inputDatasetPath)
                .filter(Files::isRegularFile) // Only process files, not directories
                .filter(path -> filePattern.matcher(path.getFileName().toString()).matches()) // Match pattern
                .filter(path -> path.getFileName().toString().startsWith("oracles-datapoints-" + projectIdentifier))
                .forEach(filePath -> {
                    // Each test class is read one at a time, instead of the whole file at once
                    try (MappingIterator<TestClazzDatapoints<OracleDatapoint>> testClazzOraclesDatapoints = FilesUtils.readJSONArray(filePath, innerType)) {
                        while (testClazzOraclesDatapoints.hasNextValue()) {
                            TestClazzDatapoints<OracleDatapoint> testClazzDatapoint = testClazzOraclesDatapoints.nextValue();
                            try {
                                Pair<List<List<String>>,HashMap<String, HashMap<String, List<String>>>> result =
                                        generateOracle(repoRootPath, nextTokenPath, mlModelAPIUrl, testClazzDatapoint);
                                List<List<String>> rows = result.getValue0();
                                HashMap<String, HashMap<String, List<String>>> errorsStats = result.getValue1();
                                for (List<String> row : rows) {
                                    try {
                                        csvWriter.addRow(row);
                                    } catch (IOException e) {
                                        logger.error(e.getMessage());
                                    }
                                }
                                // Save statistics about errors
                                Path statsOutFile = projectOutputPath.resolve("stats.json");
                                try {
                                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(statsOutFile.toFile(), errorsStats);
                                } catch (IOException e) {
                                    throw new RuntimeException("Error writing JSON to file", e);
                                }
                            } catch (IllegalStateException e) {
                                logger.error(e.getMessage());
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException("Error when reading file " + filePath, e);
                    }
                });
    }
}
