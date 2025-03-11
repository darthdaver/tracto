package star.tracto.preprocessing;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.OraclesDatasetConfig;
import star.tracto.data.SplitStrategyType;
import star.tracto.data.TractoNamingConvention;
import star.tracto.data.TryCatchFinallyStrategyType;
import star.tracto.preprocessing.components.TestClassOracleDatapoints;
import star.tracto.preprocessing.utils.JavaFileCollector;
import star.tracto.preprocessing.utils.TestUtils;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;


/**
 * This class provides the main method to generate the oracles dataset.
 */
public class OraclesDataset {

    private static final Logger logger = LoggerFactory.getLogger(OraclesDataset.class);

    public static void generate(Path repoRootPath, Path configPath, Path outputPath, String projectIdentifier, String classpath) throws IOException {
        // Update classpath with the libraries paths
        List<Path> librariesPaths = findLibrariesPaths(repoRootPath);
        for (Path libraryPath : librariesPaths) {
            classpath += ":" + libraryPath.toString();
        }
        // Load configuration file
        OraclesDatasetConfig oraclesDatasetConfig = FilesUtils.readJSON(configPath, OraclesDatasetConfig.class);
        // Define test mapping hash map
        HashMap<Path, Path> mappedTests = new HashMap<>();
        // Define statistics hash map
        HashMap<String, Integer> statistics = new HashMap<>();
        // Define list of oracles datapoints
        List<TestClassOracleDatapoints> testClassesOracleDatapoints = new ArrayList<>();
        // Generate directories to output path if they do not exist
        Path outputDatasetPath = outputPath.resolve("raw-oracles-dataset");
        Path outputStatisticsPath = outputPath.resolve("statistics");
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
        List<Path> testFilePaths = JavaFileCollector.collectTestFilePaths(repoRootPath);
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
        // Define the list to store the test pair of split test cases (generated from the original test cases) and the
        // corresponding source class (if available)
        List<Pair<Path, Optional<Path>>> splittedTestFilePaths = new ArrayList<>();
        // Iterate over the original test class files and split the test cases at any occurrence of an assertion
        for (Path testClassPath : testFilePaths) {
            logger.info("Splitting test class: {}", testClassPath.toString().replace(repoRootPath.toString(), ""));
            try {
                Path splitTestClassPath = TestUtils.splitTest(
                        oraclesDatasetConfig,
                        repoRootPath,
                        testClassPath,
                        Paths.get(outputPath.toString(), "test-split", projectIdentifier)
                );
                splittedTestFilePaths.add(new Pair<>(splitTestClassPath, Optional.ofNullable(mappedTests.get(testClassPath))));
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }
        // Update java parser considering the new split files generated within the repository
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // Convert statistics to JSON
        ObjectMapper objectMapper = new ObjectMapper();
        int oraclesDatapoints = 0;
        HashMap<String,HashMap<String, HashMap<String, List<String>>>> errorsStatistics = new HashMap<>();
        // Iterate over the original test class files and split the test cases at any occurrence of an assertion
        for (Pair<Path, Optional<Path>> splitTestClassPathsPair : splittedTestFilePaths) {
            Path splitTestClassPath = splitTestClassPathsPair.getValue0();
            Optional<Path> sourceFilePath = splitTestClassPathsPair.getValue1();
            logger.info("Processing split test class {} to generate oracles datapoints", splitTestClassPath.toString().replace(repoRootPath.toString(), ""));
            if (sourceFilePath.isPresent()) {
                // Debugging
                 if (splitTestClassPath.toString().replace(repoRootPath.toString(), "").contains("SentinelTimeCodingTestTractoSplit")) {
                     Pair<TestClassOracleDatapoints, HashMap<String, HashMap<String, List<String>>>> processedSplitTestClassResult = TestUtils.processSplitTestClass(oraclesDatasetConfig, splitTestClassPath, sourceFilePath.get());
                     TestClassOracleDatapoints splitTestClasstestClassesOracleDatapoints = processedSplitTestClassResult.getValue0();
                     HashMap<String, HashMap<String, List<String>>> splitTestClassErrorsStatistics = processedSplitTestClassResult.getValue1();
                     oraclesDatapoints += splitTestClasstestClassesOracleDatapoints.datapoints().size();
                     testClassesOracleDatapoints.add(splitTestClasstestClassesOracleDatapoints);
                 }
//                try {
//                    Pair<TestClassOracleDatapoints, HashMap<String, HashMap<String, List<String>>>> processedSplitTestClassResult = TestUtils.processSplitTestClass(oraclesDatasetConfig, splitTestClassPath, sourceFilePath.get());
//                    TestClassOracleDatapoints splitTestClasstestClassesOracleDatapoints = processedSplitTestClassResult.getValue0();
//                    HashMap<String, HashMap<String, List<String>>> splitTestClassErrorsStatistics = processedSplitTestClassResult.getValue1();
//                    testClassesOracleDatapoints.add(splitTestClasstestClassesOracleDatapoints);
//                    oraclesDatapoints += splitTestClasstestClassesOracleDatapoints.datapoints().size();
//                    errorsStatistics.put(splitTestClassPath.toString().replace(repoRootPath.toString(), ""), splitTestClassErrorsStatistics);
//                } catch (Exception e) {
//                    logger.error("Error while processing split test class: {}", splitTestClassPath.toString().replace(repoRootPath.toString(), ""));
//                    logger.error("Error message: {}", e.getMessage());
//                }
            }
        }
        // TODO: Log statistics
        // Log info data
        logger.info("Test classes processed: {}", testClassesOracleDatapoints.size());
        logger.info("Oracles datapoints generated: {}", oraclesDatapoints);
        int maxFileSizeBytes = 50 * 1024 * 1024; // 50 MB
        int fileCounter = 0;
        List<TestClassOracleDatapoints> currentChunk = new ArrayList<>();
        long currentChunkSize = 0;

        for (TestClassOracleDatapoints dataPoint : testClassesOracleDatapoints) {
            // Convert individual data points to JSON to estimate their size
            String dataPointJSON = objectMapper.writeValueAsString(dataPoint);
            long dataPointSize = dataPointJSON.getBytes(StandardCharsets.UTF_8).length;
            // Check if adding this data point exceeds the max file size
            if (currentChunkSize + dataPointSize > maxFileSizeBytes && !currentChunk.isEmpty()) {
                // Write current chunk to a file
                writeChunkToFile(currentChunk, fileCounter++, outputDatasetPath.resolve(projectIdentifier), projectIdentifier, objectMapper);
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
            writeChunkToFile(currentChunk, fileCounter++, outputDatasetPath.resolve(projectIdentifier), projectIdentifier, objectMapper);
        }
        // Store statistics
        statistics.put("oracleDatapoints", testClassesOracleDatapoints.size());
        String statisticsJSON = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(statistics);
        String errorsStatisticsJSON = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorsStatistics);
        // Write statistics to file
        FilesUtils.writeJSONFile(Paths.get(outputStatisticsPath.resolve(projectIdentifier).toString(), String.format("statistics-%s.json", projectIdentifier)), statisticsJSON);
        FilesUtils.writeJSONFile(Paths.get(outputStatisticsPath.resolve(projectIdentifier).toString(), String.format("errors-statistics-%s.json", projectIdentifier)), errorsStatisticsJSON);
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

    private static void writeChunkToFile(List<TestClassOracleDatapoints> chunk, int fileCounter, Path outputPath, String projectIdentifier, ObjectMapper objectMapper) throws IOException {
        Path outputFile = Paths.get(outputPath.toString(), String.format("oracles-datapoints-%s-%d.json", projectIdentifier, fileCounter));
        FilesUtils.createFile(outputFile);
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8);
             JsonGenerator jsonGenerator = objectMapper.getFactory().createGenerator(writer)) {
            objectMapper.writeValue(jsonGenerator, chunk);
        }
    }

    private static List<Path> findLibrariesPaths(Path repoRootPath) {
        List<Path> matchingPaths = new ArrayList<>();
        try {
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.toString().endsWith(TractoNamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())) {
                        matchingPaths.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error while searching for folders: " + e.getMessage());
        }
        return matchingPaths;
    }
}
