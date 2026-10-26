package star.tracto.preprocessing.utils;

import star.tracto.data.records.OraclesDatasetConfig;
import star.tracto.utils.FilesUtils;
import star.tracto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A temporary repository used as input of the dataset generation, with the parser configured on it. The dataset
 * generation writes generated files into the repository, so each test works on its own temporary copy.
 */
final class FixtureRepo {

    static final OraclesDatasetConfig CONFIG = new ConfigBuilder().build();

    /** The configuration committed in the module, used to generate the datasets. */
    static final Path COMMITTED_CONFIG_PATH = Path.of("src/main/resources/dataset/oracles-dataset_config.json");

    final Path root;
    final Path output;
    /** An empty directory, used as classpath of the parser (an empty classpath makes it scan the working directory). */
    final Path emptyClasspath;

    /**
     * Creates the repository {@code repo} (with {@code src/main/java} and {@code src/test/java}) and the output folder
     * {@code output} within the given directory, and configures the parser on the repository.
     */
    FixtureRepo(Path tempDir) throws IOException {
        root = tempDir.resolve("repo");
        output = tempDir.resolve("output");
        emptyClasspath = Files.createDirectories(tempDir.resolve("no-libs"));
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        JavaParserUtils.setRepoJavaParser(root, emptyClasspath.toString());
    }

    /** Loads the configuration committed in the module. */
    static OraclesDatasetConfig committedConfig() {
        return FilesUtils.readJSON(COMMITTED_CONFIG_PATH, OraclesDatasetConfig.class);
    }

    /** Writes a test class at the given path relative to {@code src/test/java} and returns its path. */
    Path writeTestClass(String relativePath, String source) throws IOException {
        return write(root.resolve("src/test/java").resolve(relativePath), source);
    }

    /** Writes a source (focal) class at the given path relative to {@code src/main/java} and returns its path. */
    Path writeSourceClass(String relativePath, String source) throws IOException {
        return write(root.resolve("src/main/java").resolve(relativePath), source);
    }

    /**
     * Configures the parser again on the repository, so that it sees the files written after the construction (the
     * pipeline does the same after each phase).
     */
    void refreshParser() {
        JavaParserUtils.setRepoJavaParser(root, emptyClasspath.toString());
    }

    private static Path write(Path file, String source) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
