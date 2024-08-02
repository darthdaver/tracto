package star.tratto.utils;

import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/**
 * This class provides file input and output utilities, such as: creating, copying, moving, writing, reading, etc.
 */
public class FileUtils {
    /** Do not instantiate this class. */
    private FileUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Creates an empty directory. Creates parent directories if necessary. If
     * the directory already exists, then this method does nothing. <br> This
     * method is a wrapper method of {@link Files#createDirectories(Path, FileAttribute[])}
     * to substitute {@link IOException} with {@link Error} and avoid
     * superfluous try/catch blocks.
     *
     * @param path a path
     * @throws Error if an error occurs while creating the directory
     */
    public static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new Error("Error when creating directory " + path, e);
        }
    }

    /**
     * Creates an empty file. Creates parent directories if necessary. If the
     * file already exists, then this method does nothing.
     *
     * @param path a file
     * @throws Error if an error occurs while creating the parent directories
     * or new file
     */
    public static void createFile(Path path) {
        try {
            createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
        } catch (IOException e) {
            throw new Error("Error when creating file " + path, e);
        }
    }

    /**
     * Get the absolute path of the Tratto project root.
     */
    public static Path getProjectRootAbsolutePath() {
        // Get the absolute path to the current class:
        // [path_to_root]/target/classes/star/tratto/utils/FileUtils.class
        Path absPathToCurrentClass = Path.of(FileUtils.class.getResource("FileUtils.class").getPath());
        // Return the path to the root of the Tratto project (6 levels up from the current class)
        return absPathToCurrentClass.getParent().getParent().getParent().getParent().getParent().getParent();
    }

    /**
     * Writes an object representing a compilation unit to a Java file.
     *
     * @param path the path to the file
     * @throws Error if an error occurs while writing the file
     */
    public static void writeJavaFile(Path path, CompilationUnit cu) {
        createFile(path);
        try {
            Files.write(path, cu.toString().getBytes());
        } catch (IOException e) {
            throw new Error("Error when writing to file " + path, e);
        }
    }
}
