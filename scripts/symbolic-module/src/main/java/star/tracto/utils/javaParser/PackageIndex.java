package star.tracto.utils.javaParser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The packages of the source roots read by the symbol solver (the projects of the repository, the sources and stubs
 * of the dependencies, the JDK), for the fully qualified names of classes ({@code new java.awt.Color(...)}, Step 44):
 * the first names of the packages, the subpackages of a package and its public top-level classes. A package is a
 * folder of a source root that contains Java files whose package declaration is the name of the folder relative to
 * the root: the roots found by {@code JavaParserUtils.findPotentialProjectsRoots} are a superset of the true ones
 * (a folder that contains a folder {@code java}, as {@code java.base/java} next to {@code java.base}), which the
 * solver tolerates (it finds nothing there) but which would give wrong names. A class is found in the first root that
 * has it (the order of the solver).
 */
public final class PackageIndex {

    private static final Logger logger = LoggerFactory.getLogger(PackageIndex.class);

    private static List<Path> indexedRoots = List.of();
    private static PackageIndex current = new PackageIndex(List.of());

    /** The subpackage names of each package ("" for the first names). */
    private final Map<String, SortedSet<String>> subpackages = new HashMap<>();
    /** The Java files of each package, by class name (the first root that has the class). */
    private final Map<String, TreeMap<String, Path>> files = new HashMap<>();
    /** The public classes of each package, computed when first asked. */
    private final Map<String, Map<String, Path>> publicClasses = new HashMap<>();

    private PackageIndex(List<Path> roots) {
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            // The verdict on each folder of the root: whether its name is the package of its files
            Map<Path, Boolean> packageFolders = new HashMap<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path)).sorted().toList()) {
                    String className = file.getFileName().toString().replace(".java", "");
                    Path folder = root.relativize(file).getParent();
                    if (folder == null || className.equals("package-info") || className.equals("module-info")) {
                        continue;
                    }
                    String packageName = folder.toString().replace(file.getFileSystem().getSeparator(), ".");
                    if (!isPackageName(packageName)
                            || !packageFolders.computeIfAbsent(file.getParent(), f -> packageName.equals(declaredPackage(file)))) {
                        continue;
                    }
                    files.computeIfAbsent(packageName, name -> new TreeMap<>()).putIfAbsent(className, file);
                    String parent = "";
                    for (String segment : packageName.split("\\.")) {
                        subpackages.computeIfAbsent(parent, name -> new TreeSet<>()).add(segment);
                        parent = parent.isEmpty() ? segment : parent + "." + segment;
                    }
                }
            } catch (IOException e) {
                logger.warn("Packages of {} not indexed - {}", root, e.getMessage());
            }
        }
    }

    /** The index of the source roots of the symbol solver (built once for the same roots). */
    public static synchronized PackageIndex ofSolver() {
        List<Path> roots = JavaParserUtils.sourceRoots();
        if (!roots.equals(indexedRoots)) {
            current = new PackageIndex(roots);
            indexedRoots = roots;
        }
        return current;
    }

    static PackageIndex of(List<Path> roots) {
        return new PackageIndex(roots);
    }

    /** The first names of the packages ({@code java}, {@code org}, ...), sorted. */
    public SortedSet<String> firstNames() {
        return subpackages.getOrDefault("", new TreeSet<>());
    }

    /** Whether a package (or the prefix of one) has this qualified name. */
    public boolean isPackage(String qualifiedName) {
        return subpackages.containsKey(qualifiedName) || files.containsKey(qualifiedName);
    }

    /** The names of the subpackages of a package ({@code util} for {@code java}), sorted. */
    public SortedSet<String> subpackagesOf(String packageName) {
        return subpackages.getOrDefault(packageName, new TreeSet<>());
    }

    /** The public top-level classes of a package, by name (sorted), with their files. */
    public synchronized Map<String, Path> publicClassesOf(String packageName) {
        return publicClasses.computeIfAbsent(packageName, name -> {
            Map<String, Path> classes = new LinkedHashMap<>();
            for (Map.Entry<String, Path> file : files.getOrDefault(name, new TreeMap<>()).entrySet()) {
                try {
                    CompilationUnit cu = JavaParserUtils.getCompilationUnit(file.getValue());
                    boolean isPublic = cu.getTypes().stream()
                            .anyMatch(type -> type.getNameAsString().equals(file.getKey()) && type.hasModifier(Modifier.Keyword.PUBLIC));
                    if (isPublic) {
                        classes.put(file.getKey(), file.getValue());
                    }
                } catch (Exception e) {
                    logger.warn("Class of package {} not parsed: {} - {}", name, file.getValue(), e.getMessage());
                }
            }
            return classes;
        });
    }

    private static final java.util.regex.Pattern PACKAGE_DECLARATION =
            java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** The package declared by a Java file (its first package declaration; a comment can hide one: rare), or "". */
    private static String declaredPackage(Path file) {
        try {
            java.util.regex.Matcher matcher = PACKAGE_DECLARATION.matcher(Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1));
            return matcher.find() ? matcher.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isPackageName(String name) {
        for (String segment : name.split("\\.")) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))
                    || !segment.chars().allMatch(Character::isJavaIdentifierPart)) {
                return false;
            }
        }
        return true;
    }
}
