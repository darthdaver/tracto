package star.tratto.preprocessing;

import com.github.javaparser.JavaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.preprocessing.utils.JavaFileCollector;
import star.tratto.preprocessing.utils.TestUtils;
import star.tratto.utils.FileUtils;
import star.tratto.utils.javaParser.JavaParserUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class OraclesDataset {

    private static final Logger logger = LoggerFactory.getLogger(OraclesDataset.class);

    public static void main(String[] args) throws IOException {
        // Process arguments
        Path repoRootPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        // Define test mapping hash map
        HashMap<Path, Path> mappedTests = new HashMap<>();
        // Define statistics hash map
        HashMap<String, Integer> statistics = new HashMap<>();
        // Generate directories to output path if they do not exist
        FileUtils.createDirectories(outputPath);
        // Log step 1
        logger.info("1. Repository classes analysis...");
        // Setup the repository root path for JavaParser
        JavaParserUtils.setRepoJavaParser(repoRootPath);
        // Collect java files within the repository
        List<Path> javaFilePaths = JavaFileCollector.collectAllJavaFilePaths(repoRootPath);
        // Collect java test files within the repository
        List<Path> testFilePaths = JavaFileCollector.collectTestFilePaths(repoRootPath);
        // Collect java source files within the repository
        List<Path> sourceFilePaths = JavaFileCollector.collectSourceFilePaths(repoRootPath, testFilePaths);
        // Collect the java classes excluded from the test and source files
        List<Path> excludedClasses = excludedClasses(javaFilePaths, testFilePaths, sourceFilePaths);
        // Store statistics
        statistics.put("javaFilePaths", javaFilePaths.size());
        statistics.put("testFilePaths", testFilePaths.size());
        statistics.put("sourceFilePaths", sourceFilePaths.size());
        statistics.put("excludedClasses", excludedClasses.size());
        // Log info data
        logger.info("Java Files: {}", javaFilePaths.size());
        logger.info("Test Classes: {}", testFilePaths.size());
        logger.info("Source Classes: {}", sourceFilePaths.size());
        // Log step 2
        logger.info("2. Perfect name matching analysis (Test Class <--> Source Class)");
        List<Path> perfectMatchNotFounds = new ArrayList<>();
        for (Path testClassPath : testFilePaths) {
            Path expectedSourceClassNormPath = testClassPathToSourceClassNormPath(testClassPath);
            for(Path sourceFilePath : sourceFilePaths) {
                if (sourceFilePath.toString().toLowerCase().equals(expectedSourceClassNormPath.toString().toLowerCase())) {
                    mappedTests.put(testClassPath, sourceFilePath);
                    break;
                }
            }
            if (!mappedTests.containsKey(testClassPath)) {
                perfectMatchNotFounds.add(testClassPath);
            }
        }
        // Log info data
        logger.info("Perfect matches found: {}", mappedTests.size());
        logger.info("Perfect matches not found: {}", perfectMatchNotFounds.size());
        // Store statistics
        statistics.put("perfectMatches", mappedTests.size());
        statistics.put("perfectMatchesNotFound", perfectMatchNotFounds.size());
        // Define the list to store the test class files with the splitted test cases
        List<Path> splittedTestFilePaths = new ArrayList<>();
        // Iterate over the original test class files and 
        for (Path testClassPath : testFilePaths) {
            logger.info("Splitting test class: {}", testClassPath.toString().replace(repoRootPath.toString(), ""));
            Path splitTestClassPath = Paths.get(outputPath.toString(), "test-split");
            TestUtils.splitTest(testClassPath, splitTestClassPath);
            splittedTestFilePaths.add(splitTestClassPath);
        }
    }

    /**
     * Maps the test class path to the expected corresponding source class normalized path.
     * The normalization is performed transforming the original string into a corresponding
     * string with only lower case characters.
     *
     * @param testClassPath the test class path
     * @return the corresponding source class path
     */
    private static Path testClassPathToSourceClassNormPath(Path testClassPath) {
        // Normalize the test class path string
        String testFileNormStr = testClassPath.toString().toLowerCase();
        // Split the normalized test class path string
        String[] testFileNormStrSplitted = testFileNormStr.split("/");
        // Get the test class name
        String testClassName = testFileNormStrSplitted[testFileNormStrSplitted.length - 1];
        // Generate the expected corresponding source class name
        String sourceClassName = testClassName.replace("test", "");
        // Apply changes to the original test class path to complete the transformation
        String sourceClassPathStr = testFileNormStr
                .replace("/src/test/", "/src/main/")
                .replace(testClassName, sourceClassName);
        // Return the expected corresponding source class path
        return Paths.get(sourceClassPathStr);
    }

    /**
     * Collects the java classes that are part of the repository (contained within the {@code javaFilePaths}), but are not
     * contained neither in the {@code testFilePaths} nor in the {@code sourceFilePaths}.
     *
     * @param javaFilePaths
     * @param testFilePaths
     * @param sourceFilePaths
     * @return
     */
    private static List<Path> excludedClasses(List<Path> javaFilePaths, List<Path> testFilePaths, List<Path> sourceFilePaths) {
        List<Path> excludedClasses = new ArrayList<>();

        // Iterate over all the collected java files of the repository
        for (Path classPath : javaFilePaths) {
            // Check if the class of the project is not contained in the test file list and in the source file list
            if (!testFilePaths.contains(classPath) && !sourceFilePaths.contains(classPath)) {
                // Add the class to the excluded class list
                excludedClasses.add(classPath);
            }
        }
        // Return the list of excluded classes
        return excludedClasses;
    }
}
