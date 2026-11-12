package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import star.tracto.deps.StubGenerator;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of the libraries of the dependencies of a repository (Step 38), as {@code tools/deps/resolve_maven.py}
 * lays them out in {@code processed_libs}: the sources of a library unpacked in {@code decompiled/<library>/}; a library
 * without sources as stubs generated from its jar in {@code decompiled/<library>/} ({@link StubGenerator}); the binary
 * jar in {@code jars/} when no stub can be generated. The members of their types are offered as candidates; with the
 * jar alone, not the name of its classes (found by name in the {@code .java} files only), hence the stubs.
 */
class DependencyLibrariesTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        // A library with sources
        fixture.write("processed_libs/decompiled/org.widgets__widgets-1.0/org/widgets/Widget.java", """
                package org.widgets;

                public class Widget {
                    public String colour() {
                        return "red";
                    }
                }
                """);
        // A library without sources, stubs generated from its jar
        Path toolsJar = tempDir.resolve("tools-1.0.jar");
        jar(toolsJar, "org.tools.Tool", """
                package org.tools;

                public class Tool {
                    public int sharpness;

                    public static Tool make(String label) {
                        return new Tool();
                    }

                    public boolean cut(int depth) {
                        return true;
                    }
                }
                """);
        assertEquals(1, StubGenerator.generate(toolsJar, tempDir.resolve("repo/processed_libs/decompiled/org.tools__tools-1.0")));
        // A library without sources and without stubs: the binary jar
        jar(tempDir.resolve("repo/processed_libs/jars/org.gadgets__gadgets-1.0.jar"), "org.gadgets.Gadget", """
                package org.gadgets;

                public class Gadget {
                    public int spin() {
                        return 1;
                    }

                    public static Gadget create() {
                        return new Gadget();
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Shop.java", """
                package com.acme;

                import org.gadgets.Gadget;
                import org.tools.Tool;
                import org.widgets.Widget;

                public class Shop {
                    public Widget widget() {
                        return new Widget();
                    }

                    public Gadget gadget() {
                        return Gadget.create();
                    }

                    public Tool tool() {
                        return Tool.make("knife");
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ShopTest.java", """
                package com.acme;

                import org.gadgets.Gadget;
                import org.tools.Tool;
                import org.widgets.Widget;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ShopTest {
                    @Test
                    public void testWidget() {
                        Widget widget = new Shop().widget();
                        assertEquals("red", widget.colour());
                    }

                    @Test
                    public void testTool() {
                        Tool tool = new Shop().tool();
                        assertEquals(3, tool.sharpness);
                    }

                    @Test
                    public void testToolName() {
                        assertNotNull(Tool.make("saw"));
                    }

                    @Test
                    public void testGadget() {
                        Gadget gadget = new Shop().gadget();
                        assertEquals(1, gadget.spin());
                    }
                }
                """);
    }

    @Test
    void theMembersOfTheLibrariesAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        // The sources of a library
        assertTrue(candidatesAfter(datapoints.get("testWidget_split_0"), "assertEquals(\"red\",widget.").contains("colour"));
        // A library without sources, its stubs: the members (a field too) and the name of its classes
        List<String> afterTool = candidatesAfter(datapoints.get("testTool_split_0"), "assertEquals(3,tool.");
        assertTrue(afterTool.containsAll(List.of("sharpness", "cut")), afterTool.toString());
        List<String> classNames = candidatesAfter(datapoints.get("testToolName_split_0"), "assertNotNull(");
        assertTrue(classNames.contains("Tool"), classNames.toString());
        assertTrue(candidatesAfter(datapoints.get("testToolName_split_0"), "assertNotNull(Tool.").contains("make"));
        // A library without sources nor stubs: its binary jar, the members of its types
        List<String> afterGadget = candidatesAfter(datapoints.get("testGadget_split_0"), "assertEquals(1,gadget.");
        assertTrue(afterGadget.contains("spin"), afterGadget.toString());
    }

    /** Compiles one class and writes its class files into a jar. */
    private static void jar(Path jarPath, String className, String source) throws IOException {
        Path work = Files.createTempDirectory("tracto-jar");
        Path sourceFile = work.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), sourceFile.toString()));
        Files.createDirectories(jarPath.getParent());
        try (OutputStream out = Files.newOutputStream(jarPath); JarOutputStream jar = new JarOutputStream(out);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }
}
