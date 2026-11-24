package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of the order of the classes of an imported package of the JDK (Step 51): the candidates are the
 * same, in the same order, wherever the module is installed. The files of the package were collected in a
 * {@code HashSet<Path>}, whose order follows the hashes of their absolute paths, and the class names were added in that
 * order to the {@code HashMap} of the candidates, where the order of the names in the same bucket follows the order in
 * which they were added: seen in uuid ({@code import java.io.*}), where {@code PipedReader} and
 * {@code CharArrayWriter} swapped when the module was installed in another folder. The same holds for the packages of
 * the repository, wherever it is cloned ({@code JavaParserUtilsTest}).
 */
class ImportedPackageOrderTokensTest {

    @TempDir
    Path tempDir;

    @Test
    void theClassesOfAnImportedPackageOfTheJdkDoNotDependOnTheFolderOfTheModule() throws IOException {
        // The same repository, and the module in another folder (links to its folders of sources: the JDKs, the
        // assertion libraries)
        Path otherModule = tempDir.resolve("another/install/of/the/module");
        Files.createDirectories(otherModule);
        for (String folder : List.of("lib", "assertion-api")) {
            Files.createSymbolicLink(otherModule.resolve(folder), Path.of(folder).toAbsolutePath());
        }
        Path project = tempDir.resolve("journal");
        TokensFixture here = journalProject(project);
        here.generate();
        Map<String, List<List<String>>> candidatesHere = candidateLists(here);
        deleteRecursively(project);
        TokensFixture there = journalProject(project);
        there.generate(otherModule);

        assertEquals(Map.of(), here.unexpectedTokens());
        List<String> candidates = candidatesAfter(here.tokensDatapoints().get("testName_split_0"), "assertEquals(");
        assertTrue(candidates.containsAll(List.of("PipedReader", "CharArrayWriter", "File")), "the classes of java.io");
        assertEquals(candidatesHere, candidateLists(there));
    }

    /** A focal class importing a package of the JDK ({@code import java.io.*}, as {@code LockedFile} in uuid). */
    private static TokensFixture journalProject(Path folder) throws IOException {
        TokensFixture fixture = new TokensFixture(folder);
        fixture.write("src/main/java/com/acme/Journal.java", """
                package com.acme;

                import java.io.*;

                public class Journal {
                    private final File file;

                    public Journal(File file) {
                        this.file = file;
                    }

                    public String name() {
                        return file.getName();
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/JournalTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import java.io.File;
                import static org.junit.jupiter.api.Assertions.*;

                public class JournalTest {
                    @Test
                    public void testName() {
                        Journal journal = new Journal(new File("a.log"));
                        assertEquals("a.log", journal.name());
                    }
                }
                """);
        return fixture;
    }

    /** The candidate project tokens of every token step, by test prefix. */
    private static Map<String, List<List<String>>> candidateLists(TokensFixture fixture) throws IOException {
        Map<String, List<List<String>>> lists = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> datapoint : fixture.tokensDatapoints().entrySet()) {
            List<List<String>> steps = new ArrayList<>();
            for (JsonNode step : datapoint.getValue().get("tokenSteps")) {
                List<String> candidates = new ArrayList<>();
                step.get("nextPossibleProjectTokens").forEach(token -> candidates.add(token.asText()));
                steps.add(candidates);
            }
            lists.put(datapoint.getKey(), steps);
        }
        assertFalse(lists.isEmpty(), "no tokens datapoints");
        return lists;
    }

    private static void deleteRecursively(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }
}
