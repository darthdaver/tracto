package star.tracto.preprocessing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.SymbolicModule;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.PartialOracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.utils.TokensUtils;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

// antlr4 -Dlanguage=Java ./grammars/JavaLexer.g4 ./grammars/JavaParser.g4

public class TokensDataset {

    /* The logger for the class. */
    private static final Logger logger = LoggerFactory.getLogger(TokensUtils.class);

    public static void generate(Path repoRootPath, Path outputPath, Path configPath, String projectIdentifier, String classpath) throws IOException {
        // Update classpath with the libraries paths
        List<Path> librariesPaths = SymbolicModule.findLibrariesPaths(repoRootPath);
        for (Path libraryPath : librariesPaths) {
            classpath += ":" + libraryPath.toString();
        }
        OraclesDatasetConfig oraclesDatasetConfig = FilesUtils.readJSON(configPath, OraclesDatasetConfig.class);
        // Define list of oracles datapoints
        List<TestClazzDatapoints<PartialOracleDatapoint>> testClassesPartialOracleDatapoints = new ArrayList<>();
        // Individuate the input oracles dataset path referencing the project identifier
        Path inputDatasetPath = outputPath.resolve("raw-oracles-dataset").resolve(projectIdentifier);
        // Generate directories to output path if they do not exist
        Path outputDatasetPath = outputPath.resolve("raw-tokens-dataset").resolve(projectIdentifier);
        Path outputStatisticsPath = outputPath.resolve("statistics-tokens-dataset");
        FilesUtils.createDirectories(outputDatasetPath);
        FilesUtils.createDirectories(outputStatisticsPath);
        // Setup the repository root path for JavaParser
        JavaParserUtils.setRepoJavaParser(repoRootPath, classpath);
        // Convert statistics to JSON
        ObjectMapper objectMapper = new ObjectMapper();
        // Define the filename pattern to match: oracles-datapoints-*.json
        Pattern filePattern = Pattern.compile(".*oracles-datapoints-.*\\.json");
        ObjectMapper mapper = new ObjectMapper();
        JavaType innerType = mapper.getTypeFactory().constructParametricType(TestClazzDatapoints.class, OracleDatapoint.class);
        JavaType listType = mapper.getTypeFactory().constructCollectionType(List.class, innerType);
        // Walk through the directory and process files recursively
        Files.walk(inputDatasetPath)
                .filter(Files::isRegularFile) // Only process files, not directories
                .filter(path -> filePattern.matcher(path.getFileName().toString()).matches()) // Match pattern
                .forEach(filePath -> {
                    List<List<PartialOracleDatapoint>> testClazzTokensDatapoints = new ArrayList<>();
                    List<TestClazzDatapoints<OracleDatapoint>> testClazzOraclesDatapoints = FilesUtils.readJSONList(filePath, listType);
                    for (TestClazzDatapoints testClazzDatapoint : testClazzOraclesDatapoints) {
                        try {
                            TestClazzDatapoints testClazzDatapoints = TokensUtils.generateTokenSteps(oraclesDatasetConfig, repoRootPath, testClazzDatapoint);
                            testClassesPartialOracleDatapoints.add(testClazzDatapoints);
                        } catch (IllegalStateException e) {
                            logger.error(e.getMessage().toString());
                        }
                    }

                    // Save it in a json file
                    logger.info("Datapoints processed: {}", testClassesPartialOracleDatapoints.size());

                    // Convert individual data points to JSON to estimate their size
                    String dataPointJSON = null;
                    try {
                        dataPointJSON = objectMapper.writeValueAsString(testClassesPartialOracleDatapoints);
                        FilesUtils.writeJSONFile(outputDatasetPath.resolve(filePath.getFileName().toString()), dataPointJSON);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                });

    }
}
