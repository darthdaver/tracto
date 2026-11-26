package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the names of the anonymous classes in the datasets (Step 52): JavaParser names an anonymous class
 * {@code Anonymous-<UUID.randomUUID()>} ({@code JavaParserAnonymousClassDeclaration}), a new name each time the symbol
 * solver builds its declaration, so the outputs differed between two runs of the same inputs (seen in ffmpeg: the
 * constructor of the anonymous {@code NutReaderListener} of {@code NutReaderTest} among the invoked methods). It reached
 * the invoked methods of the test prefixes only: the creation of an anonymous class of an interface, which has no
 * constructor, resolves to the default constructor of the anonymous class, named after it.
 */
class AnonymousClassNamesTokensTest {

    private static final Pattern RANDOM_NAME = Pattern.compile("Anonymous-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        writeProject(fixture);
    }

    /** The project: anonymous classes of interfaces and of classes, in the test prefixes, a field, a setup, a lambda. */
    private static void writeProject(TokensFixture fixture) throws IOException {
        fixture.write("src/main/java/com/acme/Listener.java", """
                package com.acme;

                public interface Listener {
                    void on(String event);

                    default String name() {
                        return "listener";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Reader.java", """
                package com.acme;

                public class Reader {
                    private final Listener listener;

                    public Reader(Listener listener) {
                        this.listener = listener;
                    }

                    public String read() {
                        listener.on("read");
                        return "read";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/TypeRef.java", """
                package com.acme;

                public abstract class TypeRef<T> {
                    protected TypeRef() {
                    }

                    public String type() {
                        return "t";
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Node.java", """
                package com.acme;

                import java.util.Iterator;

                public class Node {
                    int depth = 0;

                    public Iterator<Integer> depths() {
                        return new Iterator<Integer>() {
                            int cursor = 0;

                            public boolean hasNext() {
                                return cursor < depth;
                            }

                            public Integer next() {
                                return cursor++;
                            }
                        };
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ReaderTest.java", """
                package com.acme;

                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;

                import java.util.ArrayList;
                import java.util.Comparator;
                import java.util.Iterator;
                import java.util.List;
                import java.util.function.Supplier;

                import static org.junit.jupiter.api.Assertions.*;

                public class ReaderTest {
                    private final Listener field = new Listener() {
                        public void on(String event) {
                        }
                    };
                    private Listener setUpListener;

                    @BeforeEach
                    public void setUp() {
                        setUpListener = new Listener() {
                            public void on(String event) {
                            }
                        };
                    }

                    @Test
                    public void testArgument() {
                        Reader reader = new Reader(new Listener() {
                            public void on(String event) {
                            }
                        });
                        assertEquals("read", reader.read());
                    }

                    @Test
                    public void testVariable() {
                        Listener listener = new Listener() {
                            public void on(String event) {
                            }

                            public String name() {
                                return "anonymous";
                            }
                        };
                        assertEquals("anonymous", listener.name());
                    }

                    @Test
                    public void testMembersOfTheAnonymousClass() {
                        var counter = new Object() {
                            int count = 1;

                            int twice() {
                                return 2 * count;
                            }
                        };
                        assertEquals(2, counter.twice());
                        assertEquals(1, counter.count);
                    }

                    @Test
                    public void testGeneric() {
                        TypeRef<List<String>> ref = new TypeRef<List<String>>() {
                        };
                        assertEquals("t", ref.type());
                    }

                    @Test
                    public void testDoubleBrace() {
                        List<String> list = new ArrayList<String>() {{
                            add("a");
                        }};
                        assertEquals(1, list.size());
                    }

                    @Test
                    public void testInLambdaAndNested() {
                        Supplier<Listener> supplier = () -> new Listener() {
                            public void on(String event) {
                                Listener inner = new Listener() {
                                    public void on(String e) {
                                    }
                                };
                                inner.on(event);
                            }
                        };
                        assertNotNull(supplier.get());
                    }

                    @Test
                    public void testFocalMethodReturnsAnAnonymousClass() {
                        Iterator<Integer> depths = new Node().depths();
                        assertFalse(depths.hasNext());
                    }

                    @Test
                    public void testAssertionArgument() {
                        assertEquals("listener", new Listener() {
                            public void on(String e) {
                            }
                        }.name());
                    }

                    @Test
                    public void testSubclassWithArguments() {
                        Reader reader = new Reader(field) {
                            public String read() {
                                return "sub";
                            }
                        };
                        assertEquals("sub", reader.read());
                    }

                    @Test
                    public void testInterfaceOfTheJdk() {
                        Comparator<String> byLength = new Comparator<String>() {
                            public int compare(String a, String b) {
                                return 0;
                            }
                        };
                        assertEquals(0, byLength.compare("a", "b"));
                    }

                    @Test
                    public void testFieldAndSetUp() {
                        assertEquals("listener", field.name());
                        assertEquals("listener", setUpListener.name());
                    }
                }
                """);
    }

    @Test
    void noRandomNameOfAnAnonymousClassInTheDatasets() throws IOException {
        fixture.generate();

        assertEquals(List.of(), randomNames(tempDir.resolve("output")));
    }

    @Test
    void constructorOfAnAnonymousClassOfAnInterfaceIsNamedAfterTheInterface() throws IOException {
        fixture.generate();

        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        assertEquals(List.of("public Reader(Listener listener)", "Anonymous-com.acme.Listener()"), invokedSignatures(datapoints.get("testArgument_split_0")));
        assertEquals(List.of("Anonymous-com.acme.Listener()"), invokedSignatures(datapoints.get("testVariable_split_0")));
        // the two anonymous classes (in a lambda, one in the other) were five entries with five random names
        assertEquals(List.of("void on(String event)", "Anonymous-com.acme.Listener()"), invokedSignatures(datapoints.get("testInLambdaAndNested_split_0")));
        // an interface of the JDK (its sources in lib): its qualified name (Step 53)
        assertEquals(List.of("Anonymous-java.util.Comparator()"), invokedSignatures(datapoints.get("testInterfaceOfTheJdk_split_0")));
        // anonymous classes of classes: the constructor of the class, as before
        assertEquals(List.of("protected TypeRef()"), invokedSignatures(datapoints.get("testGeneric_split_0")));
        assertEquals(List.of("public Reader(Listener listener)"), invokedSignatures(datapoints.get("testSubclassWithArguments_split_0")));
    }

    @Test
    void twoRunsWriteTheSameDatasets() throws IOException {
        TokensFixture second = new TokensFixture(tempDir.resolve("second"));
        writeProject(second);

        fixture.generate();
        second.generate();

        for (String dataset : List.of("raw-oracles-dataset-training", "raw-tokens-dataset-training")) {
            Map<String, String> first = contents(tempDir.resolve("output").resolve(dataset));
            assertFalse(first.isEmpty(), "no file in " + dataset);
            assertEquals(first, contents(tempDir.resolve("second").resolve("output").resolve(dataset)), dataset);
        }
    }

    /** The signatures of the invoked methods of the test prefix of a datapoint. */
    private static List<String> invokedSignatures(JsonNode datapoint) {
        assertNotNull(datapoint, "no tokens datapoint");
        List<String> signatures = new ArrayList<>();
        datapoint.get("testPrefix").get("invokedMethods").forEach(method -> signatures.add(method.get("signature").asText()));
        return signatures;
    }

    /** The text of each file of a folder, by path relative to the folder. */
    private static Map<String, String> contents(Path folder) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(folder.relativize(file).toString(), Files.readString(file));
            }
        }
        return contents;
    }

    /** Each random name in the files of the output, with the 160 characters around it (where it is). */
    private static List<String> randomNames(Path output) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String text = Files.readString(file);
                Matcher matcher = RANDOM_NAME.matcher(text);
                while (matcher.find()) {
                    found.add(output.relativize(file) + ": ..." + text.substring(Math.max(0, matcher.start() - 100), Math.min(text.length(), matcher.end() + 60)));
                }
            }
        }
        return found;
    }
}
