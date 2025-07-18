package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import star.tracto.data.enums.NamingConvention;
import star.tracto.preprocessing.OraclesDataset;
import star.tracto.preprocessing.TokensDataset;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SymbolicModule {

    public static void main(String[] args) {
        // Process arguments
        String datasetType = args[0];
        String projectIdentifier = args[1];
        Path repoRootPath = Path.of(args[2]);
        Path outputPath = Path.of(args[3]);
        String classpath = initializeJavaClassPath(Path.of(args[4]));
        Path configPath = Path.of(args[5]);
        // Project identifier
        try {
            if (datasetType.equals("oracles-dataset")) {
                Path repoTrackPath = Path.of(args[6]);
                OraclesDataset.generate(repoRootPath, repoTrackPath, configPath, outputPath, projectIdentifier, classpath);
            } else if (datasetType.equals("tokens-dataset")) {
                TokensDataset.generate(repoRootPath, outputPath, configPath, projectIdentifier, classpath);
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

    private static String initializeJavaClassPath(Path basePath) {
        String classpath = "";
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    basePath.resolve(NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, List.class)
            );
            for (String javaVersion : javaVersions.keySet()) {
                Path javaVersionPath = basePath.resolve("lib/java" + javaVersion);
                for (String javaLib : javaVersions.get(javaVersion)) {
                    Path javaLibPath = javaVersionPath.resolve(javaLib);
                    classpath += classpath.isEmpty() ? javaLibPath : ":" + javaLibPath;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return classpath;
    }
}
