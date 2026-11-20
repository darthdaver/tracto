package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import star.tracto.data.enums.NamingConvention;
import star.tracto.deps.StubGenerator;
import star.tracto.deps.LombokMembers;
import star.tracto.preprocessing.OraclesDataset;
import star.tracto.preprocessing.TokensDataset;
import star.tracto.utils.JavaUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SymbolicModule {
    private static final String INFERENCE = "inference";
    private static final String TEST = "test";
    private static final String TEST_PREFIX = "test-prefix";
    private static final String PREFIX = "prefix";
    private static final String RUN_CONFIG = "run-config";
    private static final String STUBS = "stubs";
    private static final String LOMBOK_MEMBERS = "lombok-members";
    private static final String ORACLES = "oracles";
    private static final String TOKENS = "tokens";
    private static final String DEFAULT_TOKEN_MODE = "training";
    private static final int MIN_DATASET_ARGUMENTS = 6;

    public static void main(String[] args) throws Exception {
        requireArguments(args, 1, "Missing command.");
        switch (args[0]) {
            case INFERENCE, TEST -> Client.run(args);
            case TEST_PREFIX, PREFIX -> PrefixClient.main(Arrays.copyOfRange(args, 1, args.length));
            case RUN_CONFIG -> {
                if (args.length != 2) throw new IllegalArgumentException("Usage: run-config <generation.json>");
                GenerationConfig.run(Path.of(args[1]));
            }
            case STUBS -> StubGenerator.main(Arrays.copyOfRange(args, 1, args.length));
            case LOMBOK_MEMBERS -> LombokMembers.main(Arrays.copyOfRange(args, 1, args.length));
            case ORACLES, TOKENS -> runDataset(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    /** Prepares the classpath and generates the requested dataset. */
    private static void runDataset(String[] args) throws IOException {
        requireArguments(args, MIN_DATASET_ARGUMENTS, "Dataset arguments are incomplete.");
        String datasetType = args[0];
        String projectIdentifier = args[1];
        Path repoRootPath = Path.of(args[2]);
        Path outputPath = Path.of(args[3]);
        Path basePath = Path.of(args[4]);
        JavaUtils.setJavaLibPath(basePath, args[5], repoRootPath);
        String classpath = JavaUtils.initializeJavaClassPath();
        if (ORACLES.equals(datasetType)) {
            generateOracles(args, repoRootPath, outputPath, projectIdentifier, classpath);
        } else {
            String mode = args.length > 6 ? args[6] : DEFAULT_TOKEN_MODE;
            TokensDataset.generate(repoRootPath, outputPath, mode, projectIdentifier, classpath);
        }
    }

    /** Generates the oracle dataset with configuration and optional repository tracking. */
    private static void generateOracles(String[] args, Path repoRootPath, Path outputPath,
                                        String projectIdentifier, String classpath) throws IOException {
        requireArguments(args, 7, "Oracle dataset arguments are incomplete.");
        Path configPath = Path.of(args[6]);
        Optional<Path> repoTrackPath = args.length > 7
                ? Optional.of(Path.of(args[7]))
                : Optional.empty();
        OraclesDataset.generate(repoRootPath, repoTrackPath, configPath,
                outputPath, projectIdentifier, classpath);
    }

    /** Checks the minimum number of arguments before accessing the array. */
    private static void requireArguments(String[] args, int minimum, String message) {
        if (args == null || args.length < minimum) {
            throw new IllegalArgumentException(message);
        }
    }

    public static List<Path> findLibrariesPaths(Path repoRootPath) {
        List<Path> matchingPaths = new ArrayList<>();
        try {
            collectLibraryDirectories(repoRootPath, matchingPaths);
        } catch (IOException e) {
            System.err.println("Error while searching for folders: " + e.getMessage());
        }
        sortLibraryPaths(matchingPaths);
        return matchingPaths;
    }

    /** Walks the repository and collects directories containing library sources or jars. */
    private static void collectLibraryDirectories(Path repoRootPath, List<Path> matchingPaths)
            throws IOException {
        Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (isLibraryDirectory(directory)) {
                    matchingPaths.add(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Returns whether a directory follows one of Tracto's library directory conventions. */
    private static boolean isLibraryDirectory(Path directory) {
        String path = directory.toString();
        return path.endsWith(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())
                || path.endsWith(NamingConvention.JAR_LIB_FOLDER.getConventionName());
    }

    /** Sorts paths to keep the classpath order deterministic for the symbol solver. */
    private static void sortLibraryPaths(List<Path> libraryPaths) {
        libraryPaths.sort(null);
    }
}
