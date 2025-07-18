package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import star.tracto.SymbolicModule;
import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.preprocessing.builders.TestClazzDatapointsBuilder;
import star.tracto.preprocessing.components.OracleDatapoint;
import star.tracto.preprocessing.components.PartialOracleDatapoint;
import star.tracto.preprocessing.components.TestClazzDatapoints;
import star.tracto.preprocessing.components.TokenStep;
import star.tracto.preprocessing.utils.TokensUtils;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// antlr4 -Dlanguage=Java ./grammars/JavaLexer.g4 ./grammars/JavaParser.g4

public class TokensDataset {

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
                    TestClazzDatapointsBuilder<PartialOracleDatapoint> testClazzDatapointsBuilder = new TestClazzDatapointsBuilder<>();
                    List<PartialOracleDatapoint> partialOracleDatapoints = new ArrayList<>();
                    List<TestClazzDatapoints<OracleDatapoint>> testClazzDatapoints = FilesUtils.readJSONList(filePath, listType);
                    for (TestClazzDatapoints testClazzDatapoint : testClazzDatapoints) {
                        TokensUtils.generateTokenSteps(oraclesDatasetConfig, repoRootPath, testClazzDatapoint);
                    }
                });
    }
}
