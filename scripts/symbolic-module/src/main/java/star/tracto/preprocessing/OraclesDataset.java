package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.SymbolicModule;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.data.records.RepositoryTrack;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.components.TestStats;
import star.tracto.preprocessing.utils.JavaFileCollector;
import star.tracto.preprocessing.utils.TestUtils;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This class provides the main method to generate the oracles dataset.
 */
public class OraclesDataset {

    private static final Logger logger = LoggerFactory.getLogger(OraclesDataset.class);

    /**
     * Generates the dataset of target oracles from a given Java project. The method retrieves the test classes defined
     * within the project and the corresponding focal classes. For each match (test class <--> focal class), the method
     * collects the list of test cases defined in the test class and process them to extract the original oracles and create
     * pairs of test prefixes and target oracles. The method creates a list of oracle datapoints {@link OracleDatapoint}
     * saved in a JSON file in the output oath (provided as argument, @code outputPath).
     *
     * The method can generate both training and inference datasets of oracles. If the path to the @code repoTrackPath is
     * not empty, the method will operate to generate a dataset for inference scope, otherwise for training. The @code repoTrackPath
     * contains an optional path to a JSON object that contains all the information about the evolution of the repository
     * of the java projects, in terms of commits. It keeps track of the test cases added/modified to the project after a
     * given date. At inference time, this information is important to prevent data leakage issues during an experimental
     * evaluation.
     *
     * The configuration file passed to the method through the {@code configPath} argument, defines customizable parameters
     * on how to process the test cases.
     *
     * Before to process the test cases in the test classes, the method normalizes the test cases adding additional oracles
     * extracted from methods called by the test cases that contains general parametric assertions shared among different test cases.
     *
     * @param repoRootPath The path to the root of the Java repository to process
     * @param repoTrackPath The optional path to the repo track file. Empty if the method operates in training generation mode.
     * @param configPath The path to a file with information about how to process the test cases and test oracles.
     * @param outputPath The path where to save the generated dataset of oracles.
     * @param projectIdentifier The identifier of the Java project to process
     * @param classpath The classpath of the project.
     * @throws IOException in case of errors when reading project files
     */
    public static void generate(Path repoRootPath, Optional<Path> repoTrackPath, Path configPath, Path outputPath, String projectIdentifier, String classpath) throws IOException {
        // Update classpath with the libraries paths
        List<Path> librariesPaths = SymbolicModule.findLibrariesPaths(repoRootPath);
        for (Path libraryPath : librariesPaths) {
            classpath += ":" + libraryPath.toString();
        }
        // Load configuration file
        OraclesDatasetConfig oraclesDatasetConfig = FilesUtils.readJSON(configPath, OraclesDatasetConfig.class);
        Optional<RepositoryTrack> repoTrack = repoTrackPath.isPresent() ? Optional.of(FilesUtils.readJSON(repoTrackPath.get(), RepositoryTrack.class)) : Optional.empty();
        // Define test mapping hash map
        HashMap<Path, Path> mappedTests = new HashMap<>();
        // Define statistics hash map
        HashMap<String, Integer> statistics = new HashMap<>();
        // Define list of oracles datapoints
        List<TestClazzDatapoints<OracleDatapoint>> testClassesOracleDatapoints = new ArrayList<>();
        // Generate directories to output path if they do not exist
        Path outputDatasetPath = outputPath.resolve(repoTrackPath.isPresent() ? "raw-oracles-dataset-inference" : "raw-oracles-dataset-training");
        Path outputStatisticsPath = outputPath.resolve(repoTrackPath.isPresent() ? "statistics-oracles-dataset-inference" : "statistics-oracles-dataset-training");
        FilesUtils.createDirectories(outputDatasetPath);
        FilesUtils.createDirectories(outputStatisticsPath);
        // Log step 1
        logger.info("1. Repository classes analysis...");
        // Setup the repository root path for JavaParser
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // Clean repository from previous execution of the current program
        JavaFileCollector.cleanRepository(repoRootPath, outputPath);
        // Collect java files within the repository
        List<Path> javaFilePaths = JavaFileCollector.collectAllJavaFilePaths(repoRootPath);
        // Collect java test files within the repository
        List<Path> testFilePaths = repoTrack.isPresent() ?
                repoTrack.get().track().keySet().stream().map(testClassPath -> Paths.get(repoRootPath.toString(), testClassPath)).collect(Collectors.toList())
                :
                JavaFileCollector.collectTestFilePaths(repoRootPath);
        // Collect java source files within the repository
        List<Path> sourceFilePaths = JavaFileCollector.collectSourceFilePaths(repoRootPath, testFilePaths);
        // Collect the java classes excluded from the test and source files
        List<Path> excludedClasses = excludedClasses(javaFilePaths, testFilePaths, sourceFilePaths);
        // Store statistics
        statistics.put("javaFilePaths", javaFilePaths.size());
        statistics.put("testFilePaths", testFilePaths.size());
        statistics.put("sourceFilePaths", sourceFilePaths.size());
        statistics.put("excludedClasses", excludedClasses.size());
        // Log info data
        logger.info("Java Files: {}", javaFilePaths.size());
        logger.info("Test Classes: {}", testFilePaths.size());
        logger.info("Source Classes: {}", sourceFilePaths.size());
        logger.info("Excluded Classes: {}", excludedClasses.size());
        // Log step 2
        logger.info("2. Perfect name matching analysis (Test Class <--> Source Class)");
        List<Path> perfectMatchNotFounds = new ArrayList<>();
        for (Path testClassPath : testFilePaths) {
            Path expectedSourceClassNormPath = testClassPathToSourceClassNormPath(testClassPath);
            for(Path sourceFilePath : sourceFilePaths) {
                if (sourceFilePath.toString().toLowerCase().equals(expectedSourceClassNormPath.toString().toLowerCase())) {
                    mappedTests.put(testClassPath, sourceFilePath);
                    break;
                }
            }
            if (!mappedTests.containsKey(testClassPath)) {
                perfectMatchNotFounds.add(testClassPath);
            }
        }
        // Log info data
        logger.info("Perfect matches found: {}", mappedTests.size());
        logger.info("Perfect matches not found: {}", perfectMatchNotFounds.size());
        // Store statistics
        statistics.put("perfectMatches", mappedTests.size());
        statistics.put("perfectMatchesNotFound", perfectMatchNotFounds.size());
        List<Pair<Path,Path>> normalizedTestFilePaths = new ArrayList<>();
        List<TestStats> testStatsList = new ArrayList<>();
        // Remove the test classes that are not perfect matches
        testFilePaths.removeAll(perfectMatchNotFounds);
        // Iterate over the original test class files and normalize them
        for (Path testClassPath : testFilePaths) {
            // if (testClassPath.toString().contains("/TableauCutGeneratorTest")) {
            try {
                logger.info("Normalizing test class: {}", testClassPath.toString().replace(repoRootPath.toString(), ""));
                Optional<List<RepositoryTrack.TestCase>> testCaseFilterList = Optional.empty();
                if (repoTrack.isPresent()) {
                    for (Map.Entry<String, List<RepositoryTrack.TestCase>> testClassRepoTrack: repoTrack.get().track().entrySet()) {
                        if (testClassPath.toString().contains(testClassRepoTrack.getKey())) {
                            testCaseFilterList = Optional.of(testClassRepoTrack.getValue());
                            break;
                        }
                    }
                }
                Pair<Path,List<TestStats>> normalizedTestClassresult = TestUtils.normalizeTest(
                        oraclesDatasetConfig,
                        repoRootPath,
                        testClassPath,
                        Paths.get(outputPath.toString(), "test-normalize", projectIdentifier),
                        testCaseFilterList
                );
                Path normalizedTestClassPath = normalizedTestClassresult.getValue0();
                testStatsList.addAll(normalizedTestClassresult.getValue1());
                // Store the normalized test class path and the original test class path
                normalizedTestFilePaths.add(new Pair<>(normalizedTestClassPath, testClassPath));
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
            // }
        }
        // Update java parser considering the new split files generated within the repository
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // Define the list to store the test pair of split test cases (generated from the original test cases) and the
        // corresponding source class (if available)
        List<Pair<Pair<Path,Path>, Optional<Path>>> splittedTestFilePaths = new ArrayList<>();
        // Iterate over the original test class files and split the test cases at any occurrence of an assertion
        for (Pair<Path,Path> normalizedTestClassPair : normalizedTestFilePaths) {
            Path normalizedTestClassPath = normalizedTestClassPair.getValue0();
            Path originalTestClassPath = normalizedTestClassPair.getValue1();
            // if (normalizedTestClassPath.toString().contains("/TableauCutGeneratorTest")) {
            try {
                logger.info("Splitting test class: {}", normalizedTestClassPath.toString().replace(repoRootPath.toString(), ""));
                Pair<Path,Path> result = TestUtils.splitTest(
                        oraclesDatasetConfig,
                        repoRootPath,
                        normalizedTestClassPath,
                        Paths.get(outputPath.toString(), "test-split", projectIdentifier)
                );
                splittedTestFilePaths.add(new Pair<>(result, Optional.ofNullable(mappedTests.get(originalTestClassPath))));
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
            // }
        }
        // Update java parser considering the new split files generated within the repository
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // Convert statistics to JSON
        ObjectMapper objectMapper = new ObjectMapper();
        int oraclesDatapoints = 0;
        HashMap<String,HashMap<String, HashMap<String, List<String>>>> errorsStatistics = new HashMap<>();
        // Iterate over the original test class files and split the test cases at any occurrence of an assertion
        for (Pair<Pair<Path,Path>, Optional<Path>> splitTestClassPathsPair : splittedTestFilePaths) {
            Path splitTestClassPath = splitTestClassPathsPair.getValue0().getValue0();
            Path splitTestPrefixClassPath = splitTestClassPathsPair.getValue0().getValue1();
            Optional<Path> sourceFilePath = splitTestClassPathsPair.getValue1();
            logger.info("Processing split test class {} to generate oracles datapoints", splitTestClassPath.toString().replace(repoRootPath.toString(), ""));
            if (sourceFilePath.isPresent()) {
                // Debugging
                // if (splitTestClassPath.toString().replace(repoRootPath.toString(), "").contains("/TableauCutGeneratorTest")) {
                //     try {
                //         Pair<TestClazzDatapoints<OracleDatapoint>, HashMap<String, HashMap<String, List<String>>>> processedSplitTestClassResult = TestUtils.processSplitTestClass(oraclesDatasetConfig, splitTestClassPath, sourceFilePath.get(), repoRootPath);
                //         TestClazzDatapoints<OracleDatapoint> splitTestClasstestClassesOracleDatapoints = processedSplitTestClassResult.getValue0();
                //         HashMap<String, HashMap<String, List<String>>> splitTestClassErrorsStatistics = processedSplitTestClassResult.getValue1();
                //         oraclesDatapoints += splitTestClasstestClassesOracleDatapoints.datapoints().size();
                //         testClassesOracleDatapoints.add(splitTestClasstestClassesOracleDatapoints);
                //     } catch (Exception e) {
                //         logger.error("Error while processing split test class: {}", splitTestClassPath.toString().replace(repoRootPath.toString(), ""));
                //         logger.error("Error message: {}", e.getMessage());
                //     }
                // }
                try {
                    Pair<TestClazzDatapoints<OracleDatapoint>, HashMap<String, HashMap<String, List<String>>>> processedSplitTestClassResult = TestUtils.processSplitTestClass(oraclesDatasetConfig, splitTestClassPath, splitTestPrefixClassPath, sourceFilePath.get(), repoRootPath);
                    TestClazzDatapoints<OracleDatapoint> splitTestClasstestClassesOracleDatapoints = processedSplitTestClassResult.getValue0();
                    HashMap<String, HashMap<String, List<String>>> splitTestClassErrorsStatistics = processedSplitTestClassResult.getValue1();
                    testClassesOracleDatapoints.add(splitTestClasstestClassesOracleDatapoints);
                    oraclesDatapoints += splitTestClasstestClassesOracleDatapoints.datapoints().size();
                    errorsStatistics.put(splitTestClassPath.toString().replace(repoRootPath.toString(), ""), splitTestClassErrorsStatistics);
                } catch (Exception e) {
                    logger.error("Error while processing split test class: {}", splitTestClassPath.toString().replace(repoRootPath.toString(), ""));
                    logger.error("Error message: {}", e.getMessage());
                }
            }
        }
        // TODO: Log statistics
        // Log info data
        logger.info("Test classes processed: {}", testClassesOracleDatapoints.size());
        logger.info("Oracles datapoints generated: {}", oraclesDatapoints);
        int maxFileSizeBytes = 50 * 1024 * 1024; // 50 MB
        int fileCounter = 0;
        List<TestClazzDatapoints<OracleDatapoint>> currentChunk = new ArrayList<>();
        long currentChunkSize = 0;

        for (TestClazzDatapoints<OracleDatapoint> dataPoint : testClassesOracleDatapoints) {
            // Convert individual data points to JSON to estimate their size
            String dataPointJSON = objectMapper.writeValueAsString(dataPoint);
            long dataPointSize = dataPointJSON.getBytes(StandardCharsets.UTF_8).length;
            // Check if adding this data point exceeds the max file size
            if (currentChunkSize + dataPointSize > maxFileSizeBytes && !currentChunk.isEmpty()) {
                // Write current chunk to a file
                FilesUtils.writeChunkToFile(currentChunk, fileCounter++, outputDatasetPath.resolve(projectIdentifier), projectIdentifier, objectMapper);
                // Start a new chunk
                currentChunk = new ArrayList<>();
                currentChunkSize = 0;
            }
            // Add the data point to the current chunk
            currentChunk.add(dataPoint);
            currentChunkSize += dataPointSize;
        }
        // Write the last chunk if any
        if (!currentChunk.isEmpty()) {
            FilesUtils.writeChunkToFile(currentChunk, fileCounter++, outputDatasetPath.resolve(projectIdentifier), projectIdentifier, objectMapper);
        }
        // Store statistics
        statistics.put("testClassesProcessed", testClassesOracleDatapoints.size());
        statistics.put("oracleDatapoints", oraclesDatapoints);
        String statisticsJSON = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(statistics);
        String errorsStatisticsJSON = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorsStatistics);
        String testStatsJSON = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(testStatsList);
        // Write statistics to file
        FilesUtils.writeJSONFile(Paths.get(outputStatisticsPath.resolve(projectIdentifier).toString(), String.format("statistics-%s.json", projectIdentifier)), statisticsJSON);
        FilesUtils.writeJSONFile(Paths.get(outputStatisticsPath.resolve(projectIdentifier).toString(), String.format("errors-statistics-%s.json", projectIdentifier)), errorsStatisticsJSON);
        FilesUtils.writeJSONFile(Paths.get(outputStatisticsPath.resolve(projectIdentifier).toString(), String.format("test-stats-%s.json", projectIdentifier)), testStatsJSON);
    }

    /**
     * Maps the test class path to the expected corresponding source class normalized path.
     * The normalization is performed transforming the original string into a corresponding
     * string with only lower case characters.
     *
     * @param testClassPath the test class path
     * @return the corresponding source class path
     */
    private static Path testClassPathToSourceClassNormPath(Path testClassPath) {
        // Normalize the test class path string
        String testFileNormStr = testClassPath.toString().toLowerCase();
        // Split the normalized test class path string
        String[] testFileNormStrSplitted = testFileNormStr.split("/");
        // Get the test class name
        String testClassName = testFileNormStrSplitted[testFileNormStrSplitted.length - 1];
        // Generate the expected corresponding source class name
        String sourceClassName = testClassName.replace("test", "");
        // Apply changes to the original test class path to complete the transformation
        String sourceClassPathStr = testFileNormStr
                .replace("/src/test/", "/src/main/")
                .replace(testClassName, sourceClassName);
        // Return the expected corresponding source class path
        return Paths.get(sourceClassPathStr);
    }

    /**
     * Collects the java classes that are part of the repository (contained within the {@code javaFilePaths}), but are not
     * contained neither in the {@code testFilePaths} nor in the {@code sourceFilePaths}.
     *
     * @param javaFilePaths
     * @param testFilePaths
     * @param sourceFilePaths
     * @return
     */
    private static List<Path> excludedClasses(List<Path> javaFilePaths, List<Path> testFilePaths, List<Path> sourceFilePaths) {
        List<Path> excludedClasses = new ArrayList<>();

        // Iterate over all the collected java files of the repository
        for (Path classPath : javaFilePaths) {
            // Check if the class of the project is not contained in the test file list and in the source file list
            if (!testFilePaths.contains(classPath) && !sourceFilePaths.contains(classPath)) {
                // Add the class to the excluded class list
                excludedClasses.add(classPath);
            }
        }
        // Return the list of excluded classes
        return excludedClasses;
    }
}
