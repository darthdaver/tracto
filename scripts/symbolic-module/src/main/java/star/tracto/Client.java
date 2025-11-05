package star.tracto;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.utils.TokensUtils;
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

    public static void run(String[] args) throws IOException {
        String usage = args.length > 0 ? args[0] : "inference";
        String projectIdentifier = args[1];
        Path repoRootPath = Path.of(args[2]);
        Path outputPath = Path.of(args[3]);
        Path basePath = Path.of(args[4]);
        Path inputDatasetPath = Path.of(args[5]);;
        int serverPort = Integer.parseInt(args[6]);
        int promptConfig = Integer.parseInt(args[7]);
        int javaVersion = Integer.parseInt(args[8]);
        Path projectOutputPath = outputPath.resolve(projectIdentifier);
        Path nextTokenPath = projectOutputPath.resolve("next-token");
        FilesUtils.createDirectories(nextTokenPath);
        FilesUtils.createDirectories(projectOutputPath);
        String mlModelAPIUrl = String.format("http://127.0.0.1:%s/api/next_token?filepath=%s/token-step.json&config=%s&project_id=%s", serverPort, nextTokenPath, promptConfig, projectIdentifier);
        JavaUtils.setJavaLibPath(basePath, javaVersion);
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
        JavaType listType = mapper.getTypeFactory().constructCollectionType(List.class, innerType);
        // Walk through the directory and process files recursively
        Files.walk(inputDatasetPath)
                .filter(Files::isRegularFile) // Only process files, not directories
                .filter(path -> filePattern.matcher(path.getFileName().toString()).matches()) // Match pattern
                .filter(path -> path.getFileName().toString().startsWith("oracles-datapoints-" + projectIdentifier))
                .forEach(filePath -> {
                    List<TestClazzDatapoints<OracleDatapoint>> testClazzOraclesDatapoints = FilesUtils.readJSONList(filePath, listType);
                    for (TestClazzDatapoints testClazzDatapoint : testClazzOraclesDatapoints) {
                        try {
                            Pair<List<List<String>>,HashMap<String, HashMap<String, List<String>>>> result = TokensUtils.generateInference(repoRootPath, nextTokenPath, mlModelAPIUrl, testClazzDatapoint);
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
                });
    }
}
