package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import star.tracto.data.TractoNamingConvention;
import star.tracto.preprocessing.OraclesDataset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SymbolicModule {

    public static void main(String[] args) {
        if (args[0].equals("oracles-dataset")) {
            // Process arguments
            Path repoRootPath = Path.of(args[1]);
            Path outputPath = Path.of(args[2]);
            String classpath = initializeJavaClassPath(Path.of(args[3]));
            Path configPath = Path.of(args[4]);
            // Project identifier
            String projectIdentifier = args[1].substring(args[1].lastIndexOf("/") + 1);
            try {
                OraclesDataset.generate(repoRootPath, configPath, outputPath, projectIdentifier, classpath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String initializeJavaClassPath(Path basePath) {
        String classpath = "";
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    basePath.resolve(TractoNamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
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
