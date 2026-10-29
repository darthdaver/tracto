package star.tracto.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the detection of the Java version declared by the build files of a repository ({@link JavaVersionDetector}).
 */
class JavaVersionDetectorTest {

    @TempDir
    Path repo;

    @Test
    void mavenCompilerReleaseProperty() throws IOException {
        pom("pom.xml", "<properties><maven.compiler.release>17</maven.compiler.release></properties>");

        assertEquals(Optional.of(17), JavaVersionDetector.detect(repo));
    }

    @Test
    void mavenSourceAndTargetWithTheOneDotPrefix() throws IOException {
        pom("pom.xml", """
                <properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                </properties>
                """);

        assertEquals(Optional.of(8), JavaVersionDetector.detect(repo));
    }

    @Test
    void javaVersionPropertyOfSpringBoot() throws IOException {
        pom("pom.xml", "<properties><java.version>21</java.version></properties>");

        assertEquals(Optional.of(21), JavaVersionDetector.detect(repo));
    }

    @Test
    void compilerPluginConfigurationReferencingAProperty() throws IOException {
        pom("pom.xml", """
                <properties><jdk>11</jdk></properties>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <release>${jdk}</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
                """);

        assertEquals(Optional.of(11), JavaVersionDetector.detect(repo));
    }

    @Test
    void propertyDefinedInTheRootPomAndUsedInAModule() throws IOException {
        pom("pom.xml", "<properties><java.level>11</java.level></properties>");
        pom("core/pom.xml", "<properties><maven.compiler.release>${java.level}</maven.compiler.release></properties>");

        assertEquals(Optional.of(11), JavaVersionDetector.detect(repo));
    }

    @Test
    void highestVersionOfTheModulesAndProfiles() throws IOException {
        pom("pom.xml", """
                <properties><maven.compiler.release>8</maven.compiler.release></properties>
                <profiles>
                    <profile>
                        <id>jdk11</id>
                        <properties><maven.compiler.testRelease>11</maven.compiler.testRelease></properties>
                    </profile>
                </profiles>
                """);
        pom("core/pom.xml", "<properties><maven.compiler.release>9</maven.compiler.release></properties>");

        assertEquals(Optional.of(11), JavaVersionDetector.detect(repo));
    }

    @Test
    void propertyDefinedInSeveralProfilesGivesItsHighestValue() throws IOException {
        pom("pom.xml", """
                <build>
                    <plugins>
                        <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <source>${project.build.sourceVersion}</source>
                                <target>${project.build.sourceVersion}</target>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
                <profiles>
                    <profile>
                        <id>jdk11</id>
                        <activation><jdk>11</jdk></activation>
                        <properties><project.build.sourceVersion>11</project.build.sourceVersion></properties>
                    </profile>
                    <profile>
                        <id>jdk17</id>
                        <activation><jdk>17</jdk></activation>
                        <properties><project.build.sourceVersion>17</project.build.sourceVersion></properties>
                    </profile>
                </profiles>
                """);

        assertEquals(Optional.of(17), JavaVersionDetector.detect(repo));
    }

    @Test
    void buildFilesWithinSourcesAndLibrariesAreIgnored() throws IOException {
        pom("pom.xml", "<properties><maven.compiler.release>8</maven.compiler.release></properties>");
        pom("src/test/resources/project/pom.xml", "<properties><maven.compiler.release>21</maven.compiler.release></properties>");
        pom("processed_libs/sources/lib/pom.xml", "<properties><maven.compiler.release>17</maven.compiler.release></properties>");

        assertEquals(Optional.of(8), JavaVersionDetector.detect(repo));
    }

    @Test
    void propertyDefinedOutsideTheRepositoryGivesNoVersion() throws IOException {
        pom("pom.xml", """
                <parent>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>plugin</artifactId>
                    <version>4.80</version>
                </parent>
                <properties><maven.compiler.release>${jenkins.java.version}</maven.compiler.release></properties>
                """);

        assertEquals(Optional.empty(), JavaVersionDetector.detect(repo));
    }

    @Test
    void repositoryWithoutDeclarationGivesNoVersion() throws IOException {
        pom("pom.xml", "<properties><junit.version>5.10.0</junit.version></properties>");

        assertEquals(Optional.empty(), JavaVersionDetector.detect(repo));
    }

    @Test
    void malformedPomIsSkipped() throws IOException {
        pom("pom.xml", "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        write("broken/pom.xml", "<project><properties>");

        assertEquals(Optional.of(17), JavaVersionDetector.detect(repo));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "build.gradle     | sourceCompatibility = 1.8                                                  | 8",
            "build.gradle     | sourceCompatibility = '11'                                                 | 11",
            "build.gradle     | java { sourceCompatibility = JavaVersion.VERSION_1_8 }                     | 8",
            "build.gradle.kts | java { targetCompatibility = JavaVersion.VERSION_17 }                      | 17",
            "build.gradle.kts | java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }     | 21",
            "build.gradle     | java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }        | 17",
            "build.gradle.kts | kotlin { jvmToolchain(11) }                                                | 11",
            "build.gradle     | tasks.withType(JavaCompile) { options.release = 11 }                       | 11",
            "build.gradle.kts | tasks.withType<JavaCompile> { options.release.set(17) }                    | 17",
    })
    void gradleDeclarations(String buildFile, String declaration, int version) throws IOException {
        write(buildFile, "plugins { id 'java' }\n" + declaration + "\n");

        assertEquals(Optional.of(version), JavaVersionDetector.detect(repo));
    }

    /** Writes a POM with the given content within its {@code project} element. */
    private void pom(String relativePath, String content) throws IOException {
        write(relativePath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>calc</artifactId>
                    <version>1.0</version>
                %s
                </project>
                """.formatted(content));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = repo.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
