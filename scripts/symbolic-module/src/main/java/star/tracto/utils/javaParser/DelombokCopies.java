package star.tracto.utils.javaParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tracto.data.enums.NamingConvention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The delomboked copies of the files of a repository that use Lombok (Step 43), written by
 * {@code tools/deps/resolve_maven.py} in {@code processed_libs/delombok/<folder>/}, one folder per source root, with the
 * manifest {@code processed_libs/delombok/roots.json}:
 * {@code {"roots": [{"folder": "src__main__java", "sourceRoot": "src/main/java"}, ...]}}. The members Lombok generates
 * (getters, setters, builders, constructors) exist only in the copies. They are read where the module looks for the
 * members of a class (the symbol solver, the members after the name of a class), never where it reads the code of the
 * repository as text (the test classes, the model input): the sources of the repository stay as they are.
 */
public final class DelombokCopies {

    private static final Logger logger = LoggerFactory.getLogger(DelombokCopies.class);
    private static final String MANIFEST = "roots.json";

    /** A source root of the repository and the folder of its delomboked copies. */
    record Root(Path sourceRoot, Path copyRoot) {}

    private static List<Root> roots = List.of();

    private DelombokCopies() {
    }

    /**
     * Reads the manifest of the delomboked copies of a repository (none when there is no manifest), for
     * {@link #copyRoots()} and {@link #copyOf(Path)}.
     */
    static void load(Path repoRootPath) {
        List<Root> loaded = new ArrayList<>();
        Path folder = repoRootPath.resolve(NamingConvention.DELOMBOK_LIB_FOLDER.getConventionName());
        Path manifest = folder.resolve(MANIFEST);
        if (Files.isRegularFile(manifest)) {
            try {
                for (JsonNode root : new ObjectMapper().readTree(manifest.toFile()).path("roots")) {
                    Path sourceRoot = repoRootPath.resolve(root.path("sourceRoot").asText()).toAbsolutePath().normalize();
                    Path copyRoot = folder.resolve(root.path("folder").asText()).toAbsolutePath().normalize();
                    if (Files.isDirectory(copyRoot)) {
                        loaded.add(new Root(sourceRoot, copyRoot));
                    }
                }
            } catch (IOException | RuntimeException e) {
                logger.error("Unable to read the delomboked copies of {}", manifest, e);
            }
        }
        loaded.sort(Comparator.comparing(root -> root.copyRoot().toString()));
        roots = List.copyOf(loaded);
    }

    /** The folders of the delomboked copies, one per source root, in a fixed order. */
    static List<Path> copyRoots() {
        return roots.stream().map(Root::copyRoot).toList();
    }

    /** The delomboked copy of a source file of the repository, or the file itself when it has none. */
    public static Path copyOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        for (Root root : roots) {
            if (absolute.startsWith(root.sourceRoot())) {
                Path copy = root.copyRoot().resolve(root.sourceRoot().relativize(absolute));
                if (Files.isRegularFile(copy)) {
                    return copy;
                }
            }
        }
        return file;
    }
}
