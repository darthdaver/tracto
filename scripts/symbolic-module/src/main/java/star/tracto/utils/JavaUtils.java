package star.tracto.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.NamingConvention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the sources of the Java standard library given to the symbol solver: one folder per JDK in the {@code lib}
 * folder of the module ({@code lib/java<version>}), whose source folders are listed in
 * {@code lib/java_classpaths.json}. Only the JDKs selected for the Java version of the repository under analysis are
 * used (see {@link #setJavaLibPath(Path, Optional)}).
 */
public class JavaUtils {

    private static final Logger logger = LoggerFactory.getLogger(JavaUtils.class);
    /** The value of the Java version argument that detects the version declared by the build files of the repository. */
    public static final String AUTO_JAVA_VERSION = "auto";
    /** The base path of the module, containing the {@code lib} folder (null if not set). */
    private static Path javaLibPath;

    /** The folder of the sources of the assertion libraries, in the base path of the module (as {@code lib}). */
    static final String ASSERTION_API_FOLDER = "assertion-api";

    /** The assertion libraries given to the symbol solver for every repository (folders of {@link #ASSERTION_API_FOLDER}). */
    static final List<String> ASSERTION_LIBRARIES = List.of("hamcrest-2.2");
    /** The source folders of the JDKs in {@code lib}, by major version, in ascending order of version. */
    private static Map<Integer, List<String>> jdkSourceFolders = Map.of();
    /**
     * The JDKs of the repository under analysis, in order of precedence: their sources are given to the symbol solver
     * first, and the lookups of the JDK classes by name use only them.
     */
    private static List<Integer> jdkVersions = List.of();
    /** The JDK of the JVM running the module, given to the symbol solver after the ones of the repository, if older. */
    private static Optional<Integer> runtimeJdkVersion = Optional.empty();
    /** The Java version the JDKs are selected for (given as argument or detected), if any. */
    private static Optional<Integer> javaVersion = Optional.empty();
    /** How the Java version was obtained: {@code argument}, {@code detected} or {@code not detected}. */
    private static String javaVersionSource = "not detected";

    /** Do not instantiate this class. */
    private JavaUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Selects the JDK sources for a repository, from the Java version argument of the command line.
     *
     * @param basePath the base path of the module, containing the {@code lib} folder
     * @param javaVersionArgument a major Java version (for example {@code 17}), or {@code auto} to use the version
     *                            declared by the build files of the repository
     * @param repoRootPath the root of the repository under analysis
     */
    public static void setJavaLibPath(Path basePath, String javaVersionArgument, Path repoRootPath) {
        if (javaVersionArgument.equals(AUTO_JAVA_VERSION)) {
            Optional<Integer> detectedVersion = JavaVersionDetector.detect(repoRootPath);
            setJavaLibPath(basePath, detectedVersion);
            javaVersionSource = detectedVersion.isPresent() ? "detected" : "not detected";
        } else {
            setJavaLibPath(basePath, Optional.of(Integer.parseInt(javaVersionArgument)));
            javaVersionSource = "argument";
        }
        logger.info("Java version: {} ({}), sources of the JDKs {}{}", javaVersion.map(String::valueOf).orElse("unknown"), javaVersionSource, jdkVersions,
                runtimeJdkVersion.map(version -> ", then of the JDK " + version + " of the running JVM").orElse(""));
    }

    /**
     * Selects the JDK sources for a Java version: the JDK of {@code lib} with the smallest version not older than the
     * given one (the newest JDK if all are older). Without a version, the newest JDK of {@code lib}, then the oldest
     * one: the classes and methods added by every version are found, and the oldest JDK provides the classes removed
     * since.
     * <p>
     * The symbol solver also gets, after them, the sources of the JDK of the JVM running the module when the selected
     * JDK is older: JavaParser describes some classes of the standard library by reflection on the running JVM (for
     * example {@code java.lang.Enum}, the superclass of every enum), and those reference classes added after older
     * versions ({@code java.lang.constant.Constable} since Java 12). A class of the selected JDK still comes from it.
     *
     * @param basePath the base path of the module, containing the {@code lib} folder
     * @param version the Java version of the repository under analysis, if known
     */
    public static void setJavaLibPath(Path basePath, Optional<Integer> version) {
        setJavaLibPath(basePath, version, Runtime.version().feature());
    }

    /** Selects the JDK sources for a Java version, given the major version of the JVM running the module. */
    static void setJavaLibPath(Path basePath, Optional<Integer> version, int runtimeVersion) {
        javaLibPath = basePath;
        javaVersion = version;
        javaVersionSource = version.isPresent() ? "argument" : "not detected";
        jdkSourceFolders = readJdkSourceFolders(basePath);
        List<Integer> availableVersions = new ArrayList<>(jdkSourceFolders.keySet());
        if (availableVersions.isEmpty()) {
            jdkVersions = List.of();
        } else if (version.isPresent()) {
            jdkVersions = List.of(smallestNotOlder(availableVersions, version.get()));
        } else {
            // The newest JDK has the classes and methods added by every version, the oldest one the classes removed
            // since (JAXB, CORBA, Nashorn in Java 8). More JDKs make the symbol solver unusable: when it does not find
            // a type, it parses the directories of every source folder looking for it
            int newestVersion = availableVersions.get(availableVersions.size() - 1);
            int oldestVersion = availableVersions.get(0);
            jdkVersions = newestVersion == oldestVersion ? List.of(newestVersion) : List.of(newestVersion, oldestVersion);
        }
        runtimeJdkVersion = availableVersions.isEmpty()
                ? Optional.empty()
                : Optional.of(smallestNotOlder(availableVersions, runtimeVersion))
                        .filter(runtimeJdk -> jdkVersions.stream().allMatch(selectedJdk -> selectedJdk < runtimeJdk));
    }

    /** Returns the smallest of the available versions not older than the given one, or the newest if all are older. */
    private static int smallestNotOlder(List<Integer> availableVersions, int version) {
        return availableVersions.stream()
                .filter(availableVersion -> availableVersion >= version)
                .findFirst()
                .orElse(availableVersions.get(availableVersions.size() - 1));
    }

    /** Returns the JDKs of the repository under analysis, in order of precedence. */
    public static List<Integer> getJdkVersions() {
        return jdkVersions;
    }

    /** Returns the JDK of the running JVM given to the symbol solver after the ones of the repository, if any. */
    public static Optional<Integer> getRuntimeJdkVersion() {
        return runtimeJdkVersion;
    }

    /** Returns the Java version the JDKs are selected for (given as argument or detected), if any. */
    public static Optional<Integer> getJavaVersion() {
        return javaVersion;
    }

    /** Returns how the Java version was obtained: {@code argument}, {@code detected} or {@code not detected}. */
    public static String getJavaVersionSource() {
        return javaVersionSource;
    }

    /**
     * Returns the classpath of the symbol solver (paths separated by ":"): the sources of the JDKs of the repository,
     * then those of the JDK of the running JVM, if older (see {@link #setJavaLibPath(Path, Optional)}), then the
     * sources of the assertion libraries known by the module (Hamcrest, Step 30; {@code assertion-api/README.md}).
     */
    public static String initializeJavaClassPath() {
        List<Integer> symbolSolverJdkVersions = new ArrayList<>(jdkVersions);
        runtimeJdkVersion.ifPresent(symbolSolverJdkVersions::add);
        List<Path> classpath = new ArrayList<>(sourceFolders(symbolSolverJdkVersions));
        classpath.addAll(assertionLibraryFolders());
        return classpath.stream().map(Path::toString).collect(Collectors.joining(":"));
    }

    /**
     * The source folders of the assertion libraries known by the module (Step 30), in the base path of the module: given
     * to the symbol solver, and searched for the classes by name as the JDK sources.
     */
    public static List<Path> assertionLibraryFolders() {
        List<Path> folders = new ArrayList<>();
        for (String library : ASSERTION_LIBRARIES) {
            Path libraryPath = javaLibPath.resolve(ASSERTION_API_FOLDER).resolve(library);
            if (Files.isDirectory(libraryPath)) {
                folders.add(libraryPath);
            } else {
                logger.error("Sources of the assertion library {} not found in {}", library, libraryPath);
            }
        }
        return folders;
    }

    public static List<Path> getPathsToJavaLibClassesFromPackage(Path packageName, boolean onlyPrefix) {
        List<Path> pathList = new ArrayList<>();
        try {
            for (Path javaLibPath : sourceFolders(jdkVersions)) {
                Path javaPackagePath = javaLibPath.resolve(packageName);
                if (Files.exists(javaPackagePath) && Files.isDirectory(javaPackagePath)) {
                    if (onlyPrefix) {
                        // If only the prefix is needed, add the directory itself
                        pathList.add(javaLibPath);
                    } else {
                        // If full paths to Java files are needed, walk through the directory
                        try (Stream<Path> files = Files.walk(javaPackagePath)) {
                            pathList.addAll(files
                                    .filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(".java"))
                                    .filter(path -> !path.toString().endsWith("module-info.java"))
                                    .collect(Collectors.toList()));
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
        for (Path javaLibPath : sourceFolders(jdkVersions)) {
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
        return pathList;
    }

    /**
     * The source files of the package {@code java.lang} itself (not of its subpackages) in the JDKs of {@code lib}
     * selected for the repository, in a fixed order (the files of the first JDK first).
     */
    public static List<Path> javaLangSourceFiles() {
        List<Path> files = new ArrayList<>();
        for (Path sourceFolder : sourceFolders(jdkVersions)) {
            Path javaLang = sourceFolder.resolve("java/lang");
            if (!Files.isDirectory(javaLang)) {
                continue;
            }
            try (Stream<Path> paths = Files.list(javaLang)) {
                files.addAll(paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                        .sorted()
                        .toList());
            } catch (IOException e) {
                logger.error("Failed to list the sources of java.lang in " + javaLang, e);
            }
        }
        return files;
    }

    /** Returns the source folders of the given JDKs of {@code lib}, in order. */
    private static List<Path> sourceFolders(List<Integer> versions) {
        List<Path> sourceFolders = new ArrayList<>();
        for (int version : versions) {
            Path versionPath = javaLibPath.resolve("lib/java" + version);
            for (String sourceFolder : jdkSourceFolders.get(version)) {
                sourceFolders.add(versionPath.resolve(sourceFolder));
            }
        }
        return sourceFolders;
    }

    /**
     * Reads the source folders of the JDKs in {@code lib} from {@code lib/java_classpaths.json}, by major version (the
     * versions whose folder is missing from {@code lib} are left out).
     */
    private static Map<Integer, List<String>> readJdkSourceFolders(Path basePath) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Map<String, List<String>> javaVersions = objectMapper.readValue(
                    basePath.resolve(NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName()).toFile(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, List.class)
            );
            Map<Integer, List<String>> sourceFolders = new TreeMap<>();
            javaVersions.forEach((version, folders) -> {
                // A version without its folder (for example, after downloading an archive of lib older than the list)
                // is not selected
                if (Files.isDirectory(basePath.resolve("lib/java" + version))) {
                    sourceFolders.put(Integer.parseInt(version), folders);
                } else {
                    logger.warn("The sources of the JDK {} listed in {} are missing: the JDK is ignored", version, NamingConvention.JAVA_CLASSPATHS_JSON.getConventionName());
                }
            });
            return sourceFolders;
        } catch (IOException e) {
            logger.error("Unable to read the JDK sources of {}: {}", basePath, e.getMessage());
            return Map.of();
        }
    }
}
