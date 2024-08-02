package star.tratto.preprocessing.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.List;

public class JavaFileCollector {

    /** Do not instantiate this class. */
    private JavaFileCollector() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Process the whole repository to find test files. A file is considered a test file
     * if it contains the @Test annotation. The method walks the file tree starting from
     * the root path of the repository and checks each file for the presence of the @Test
     * annotation. The method returns a list of paths referring to the test files found
     * within the repository, according to the given rule.
     *
     * @param repoRootPath the root path of the repository
     * @return a list of paths referring to test files
     */
    public static List<Path> collectTestFilePaths(Path repoRootPath) {
        // Create a list to store the paths of the test files
        List<Path> testFiles = new ArrayList<>();
        try {
            // Walk the file tree starting from the root path
            // The method includes a visitor that checks if a file is a test file
            // by looking for the @Test annotation
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // Check if the file is a Java file
                    if (file.toString().endsWith(".java")) {
                        // Read the lines of the file
                        List<String> lines = Files.readAllLines(file);
                        // Process each line of the file and check if it contains the @Test annotation
                        for (String line : lines) {
                            // If the line contains the @Test annotation, add the file to the list
                            if (line.contains("@Test")) {
                                testFiles.add(file);
                                break;
                            }
                        }
                    }
                    // Continue walking the file tree
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Return the list of collected test java file paths
        return testFiles;
    }

    /**
     * Collect all non-test Java files within the repository. The method walks the file tree
     * starting from the root path of the repository and checks each file for the presence of
     * the .java extension. The method excludes test files from the list of source files.
     * The method calls the collectTestFiles method to collect the test files first.
     *
     * @param repoRootPath the root path of the repository
     * @return a list of paths referring to source files (excluding test files)
     */
    public static List<Path> collectSourceFilePaths(Path repoRootPath) {
        List<Path> testFiles = JavaFileCollector.collectTestFilePaths(repoRootPath);
        return JavaFileCollector.collectSourceFilePaths(repoRootPath, testFiles);
    }

    /**
     * Collect all non-test Java files within the repository. The method walks the file tree
     * starting from the root path of the repository and checks each file for the presence of
     * the .java extension. The method excludes test files from the list of source files.
     *
     * @param repoRootPath the root path of the repository
     * @param testFiles a list of paths referring to test files
     * @return a list of paths referring to source files (excluding test files)
     */
    public static List<Path> collectSourceFilePaths(Path repoRootPath, List<Path> testFiles) {
        // Collect all non-test Java files
        List<Path> srcFiles = new ArrayList<>();
        // Collect all source java files (excluding test files)
        try {
            // Walk the file tree starting from the root path
            // The method includes a visitor that checks if a file is a source file
            // by looking for the .java extension and excluding test files
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Check if the file is a Java file
                    if (file.toString().endsWith(".java")) {
                        // If the file is not a test file, add it to the list
                        if (!testFiles.contains(file) && !file.toString().contains("src/test")) {
                            srcFiles.add(file);
                        }
                    }
                    // Continue walking the file tree
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Return the list of collected source java file paths
        return srcFiles;
    }

    /**
     * Collect all Java files within the repository. The method walks the file tree
     * starting from the root path of the repository and checks each file for the presence of
     * the .java extension.
     *
     * @param repoRootPath the root path of the repository
     * @return a list of paths referring to all Java files
     */
    public static List<Path> collectAllJavaFilePaths(Path repoRootPath) {
        // Collect all Java files
        List<Path> javaFilesourceFilePaths = new ArrayList<>();
        // Collect all source java files (excluding test files)
        try {
            // Walk the file tree starting from the root path
            // The method includes a visitor that checks if a file is a java file
            // by looking for the .java extension
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Check if the file is a Java file
                    if (file.toString().endsWith(".java")) {
                        // If the file is a java file, add it to the list
                        javaFilesourceFilePaths.add(file);
                    }
                    // Continue walking the file tree
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Return the list of collected source java file paths
        return javaFilesourceFilePaths;
    }
}
