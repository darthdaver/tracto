package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import star.tracto.data.enums.NamingConvention;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SymbolicModule {

    public static void main(String[] args) {
        // Process arguments
        String datasetType = args[0];
        String projectIdentifier = args[1];
        Path repoRootPath = Path.of(args[2]);
        Path outputPath = Path.of(args[3]);
        Path basePath = Path.of(args[4]);
        int javaVersion = Integer.parseInt(args[5]);
        JavaUtils.setJavaLibPath(basePath, javaVersion);
        String classpath = JavaUtils.initializeJavaClassPath();
        // Project identifier
        try {
            if (datasetType.equals("oracles")) {
                Path configPath = Path.of(args[6]);
                Optional<Path> repoTrackPath = args.length > 7 ? Optional.of(Path.of(args[7])) : Optional.empty();
                OraclesDataset.generate(repoRootPath, repoTrackPath, configPath, outputPath, projectIdentifier, classpath);
            } else if (datasetType.equals("tokens")) {
                String mode = args.length > 6 ? args[6] : "training";
                TokensDataset.generate(repoRootPath, outputPath, mode, projectIdentifier, classpath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<Path> findLibrariesPaths(Path repoRootPath) {
        List<Path> matchingPaths = new ArrayList<>();
        try {
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.toString().endsWith(NamingConvention.DECOMPILED_LIB_FOLDER.getConventionName())) {
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
