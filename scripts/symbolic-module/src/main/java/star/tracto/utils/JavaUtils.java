package star.tracto.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.NamingConvention;
import star.tracto.preprocessing.OraclesDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JavaUtils {

    private static final Logger logger = LoggerFactory.getLogger(OraclesDataset.class);
    private static Path javaLibPath;
    private static int javaVersion;

    /** Do not instantiate this class. */
    private JavaUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    public static void setJavaLibPath(Path path) {
        javaLibPath = path;
        javaVersion = -1;
    }

    public static void setJavaLibPath(Path path, int version) {
        javaLibPath = path;

        if (version >= 1 && version <= 8) {
            javaVersion = 8;
        } else if (version > 8 && version <= 11) {
            javaVersion = 11;
        } else if (version > 11 && version <= 17) {
            javaVersion = 17;
        } else if (version > 17 && version <= 21) {
            javaVersion = 21;
        } else if (version > 21 && version <= 24) {
            javaVersion = 24;
        } else {
            logger.error("Unsupported Java version: " + javaVersion + ". Supported versions are 8, 11, 17, 21, and 24.");
            javaVersion = -1;
        }
    }

    public static String initializeJavaClassPath() {
        String classpath = "";
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    javaLibPath.resolve(NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, List.class)
            );
            for (String javaVersion : javaVersions.keySet()) {
                Path javaVersionPath = javaLibPath.resolve("lib/java" + javaVersion);
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

    public static List<Path> getPathsToJavaLibClassesFromPackage(Path packageName, boolean onlyPrefix) {
        List<Path> pathList = new ArrayList<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    javaLibPath.resolve(NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, List.class)
            );

            for (String version : javaVersions.keySet()) {
                if (javaVersion == Integer.parseInt(version) || javaVersion == -1) {
                    Path versionPath = javaLibPath.resolve("lib/java" + version);
                    for (String javaLib : javaVersions.get(version)) {
                        Path javaLibPath = versionPath.resolve(javaLib);
                        Path javaPackagePath = javaLibPath.resolve(packageName);
                        if (Files.exists(javaPackagePath) && Files.isDirectory(javaPackagePath)) {
                            if (onlyPrefix) {
                                // If only the prefix is needed, add the directory itself
                                pathList.add(javaLibPath);
                            } else {
                                // If full paths to Java files are needed, walk through the directory
                                pathList.addAll(Files.walk(javaPackagePath)
                                        .filter(Files::isRegularFile)
                                        .filter(path -> path.toString().endsWith(".java"))
                                        .filter(path -> !path.toString().endsWith("module-info.java"))
                                        .collect(Collectors.toList()));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return pathList;
    }

    public static List<Path> getPathToJavaLibClassFromFqn(Path fqnPath, boolean onlyPrefix) {
        List<Path> pathList = new ArrayList<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    javaLibPath.resolve(NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, List.class)
            );

            for (String version : javaVersions.keySet()) {
                if (javaVersion == Integer.parseInt(version) || javaVersion == -1) {
                    Path versionPath = javaLibPath.resolve("lib/java" + version);
                    for (String javaLib : javaVersions.get(version)) {
                        Path javaLibPath = versionPath.resolve(javaLib);
                        Path classPath = javaLibPath.resolve(fqnPath);
                        if (Files.exists(classPath)) {
                            if (onlyPrefix) {
                                // If only the prefix is needed, add the directory itself
                                pathList.add(javaLibPath);
                            } else {
                                // If full path to the class file is needed, add the class file
                                pathList.add(classPath);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return pathList;
    }
}
