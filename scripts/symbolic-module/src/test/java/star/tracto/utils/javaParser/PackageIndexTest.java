package star.tracto.utils.javaParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The packages of the source roots of the symbol solver (Step 44). */
class PackageIndexTest {

    private static void write(Path file, String source) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void thePackagesAreTheFoldersNamedAsTheirDeclarations(@TempDir Path tempDir) throws IOException {
        Path base = tempDir.resolve("java.base");
        write(base.resolve("java/util/Random.java"), "package java.util;\npublic class Random {}\n");
        write(base.resolve("java/util/Helper.java"), "package java.util;\nclass Helper {}\n");
        write(base.resolve("java/lang/String.java"), "/* package x; */\npackage java.lang;\npublic final class String {}\n");
        Path project = tempDir.resolve("project/src/main/java");
        write(project.resolve("com/acme/Spec.java"), "package com.acme;\npublic class Spec {}\n");
        write(project.resolve("com/acme/Random.java"), "package com.acme;\npublic class Random {}\n");

        // As findPotentialProjectsRoots gives them: java.base/java (a folder that contains a folder java) is not a root
        PackageIndex index = PackageIndex.of(List.of(project, base.resolve("java"), base));

        assertEquals(Set.of("com", "java"), index.firstNames());
        assertEquals(Set.of("lang", "util"), index.subpackagesOf("java"));
        assertTrue(index.isPackage("com.acme"));
        assertFalse(index.isPackage("util"));
        // The public classes only
        assertEquals(List.of("Random"), List.copyOf(index.publicClassesOf("java.util").keySet()));
        assertEquals(base.resolve("java/util/Random.java"), index.publicClassesOf("java.util").get("Random"));
        assertEquals(List.of("Random", "Spec"), List.copyOf(index.publicClassesOf("com.acme").keySet()));
    }
}
