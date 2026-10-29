package star.tracto.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;
import star.tracto.data.enums.NamingConvention;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects the Java version of a repository from its build files: the highest version declared by its Maven POMs and
 * Gradle build scripts (a build can declare several versions, for example a lower one for the main code and a higher
 * one for the tests or for a module, and the tests must be resolved against an API containing everything they use).
 * <p>
 * Maven: the properties {@code maven.compiler.release}, {@code maven.compiler.source}, {@code maven.compiler.target}
 * (and their {@code test} variants), the properties named like {@code java.version} or {@code jdk.version}, and the
 * {@code release}, {@code source} and {@code target} settings of the {@code maven-compiler-plugin}, in the POMs and in
 * their profiles. A reference to a property ({@code ${name}}) is resolved with the properties of the POMs of the
 * repository: a property defined in a parent POM outside the repository gives no version.
 * <p>
 * Gradle: {@code sourceCompatibility}, {@code targetCompatibility}, the Java toolchain
 * ({@code JavaLanguageVersion.of(...)}, {@code jvmToolchain(...)}) and {@code options.release}.
 * <p>
 * The build files within the source folders (for example the projects used as test resources) and within the
 * processed libraries are ignored.
 */
public class JavaVersionDetector {

    private static final Logger logger = LoggerFactory.getLogger(JavaVersionDetector.class);

    /** The Maven properties that set the version of the compiler. */
    private static final Set<String> COMPILER_PROPERTIES = Set.of(
            "maven.compiler.release", "maven.compiler.source", "maven.compiler.target",
            "maven.compiler.testRelease", "maven.compiler.testSource", "maven.compiler.testTarget"
    );
    /** The custom Maven properties that conventionally hold the Java version ({@code java.version}, {@code jdk.version}, ...). */
    private static final Pattern VERSION_PROPERTY = Pattern.compile("(java|jdk|javac)[._-]?(version|release|source|target)", Pattern.CASE_INSENSITIVE);
    /** The settings of the {@code maven-compiler-plugin} that set the version of the compiler. */
    private static final Set<String> COMPILER_SETTINGS = Set.of("release", "source", "target", "testRelease", "testSource", "testTarget");
    /** A Java version, with or without the {@code 1.} prefix of the versions up to 8 ({@code 1.8}, {@code 8}, {@code 17}). */
    private static final Pattern VERSION = Pattern.compile("(?:1\\.)?(\\d+)");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    /** The declarations of the Java version in the Gradle build scripts (Groovy and Kotlin). */
    private static final List<Pattern> GRADLE_DECLARATIONS = List.of(
            Pattern.compile("(?:source|target)Compatibility\\s*=?\\s*(?:JavaVersion\\.VERSION_|['\"])?(?:1[._])?(\\d+)"),
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"),
            Pattern.compile("jvmToolchain\\(\\s*(\\d+)\\s*\\)"),
            Pattern.compile("options\\.release(?:\\.set\\(\\s*|\\s*=\\s*)(\\d+)")
    );
    /** The folders whose build files do not describe the repository. */
    private static final Set<String> IGNORED_FOLDERS = Set.of("src", "target", "build", "node_modules", NamingConvention.LIB_FOLDER.getConventionName());

    /** Do not instantiate this class. */
    private JavaVersionDetector() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Detects the Java version of a repository.
     *
     * @param repoRootPath the root of the repository
     * @return the highest Java version declared by the build files of the repository, or an empty optional if they
     * declare none (or only through properties defined outside the repository)
     */
    public static Optional<Integer> detect(Path repoRootPath) {
        List<Path> buildFiles = buildFiles(repoRootPath);
        List<Integer> versions = new ArrayList<>(mavenVersions(buildFiles.stream().filter(f -> f.getFileName().toString().equals("pom.xml")).toList()));
        versions.addAll(gradleVersions(buildFiles.stream().filter(f -> f.getFileName().toString().startsWith("build.gradle")).toList()));
        return versions.stream().max(Integer::compare);
    }

    /** Collects the POMs and the Gradle build scripts of the repository, outside the ignored folders. */
    private static List<Path> buildFiles(Path repoRootPath) {
        try (Stream<Path> files = Files.walk(repoRootPath)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts");
                    })
                    .filter(file -> {
                        Path relativePath = repoRootPath.relativize(file);
                        for (int i = 0; i < relativePath.getNameCount() - 1; i++) {
                            String folder = relativePath.getName(i).toString();
                            if (IGNORED_FOLDERS.contains(folder) || folder.startsWith(".")) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .toList();
        } catch (IOException e) {
            logger.error("Error collecting the build files of {}: {}", repoRootPath, e.getMessage());
            return List.of();
        }
    }

    /** Returns the Java versions declared by the POMs. */
    private static List<Integer> mavenVersions(List<Path> poms) {
        // Parse the POMs and collect the properties of all of them, to resolve the references across modules (a
        // property can be defined several times, by the modules or by the profiles)
        List<Element> projects = new ArrayList<>();
        Map<String, List<String>> properties = new HashMap<>();
        for (Path pom : poms) {
            parse(pom).ifPresent(project -> {
                projects.add(project);
                for (Element propertiesElement : descendants(project, "properties")) {
                    for (Element property : children(propertiesElement)) {
                        properties.computeIfAbsent(property.getTagName(), name -> new ArrayList<>()).add(property.getTextContent().trim());
                    }
                }
            });
        }
        List<Integer> versions = new ArrayList<>();
        for (Element project : projects) {
            List<String> values = new ArrayList<>();
            for (Element propertiesElement : descendants(project, "properties")) {
                for (Element property : children(propertiesElement)) {
                    String name = property.getTagName();
                    if (COMPILER_PROPERTIES.contains(name) || VERSION_PROPERTY.matcher(name).matches()) {
                        values.add(property.getTextContent().trim());
                    }
                }
            }
            for (Element plugin : descendants(project, "plugin")) {
                if (childText(plugin, "artifactId").equals("maven-compiler-plugin")) {
                    for (Element configuration : descendants(plugin, "configuration")) {
                        for (Element setting : children(configuration)) {
                            if (COMPILER_SETTINGS.contains(setting.getTagName())) {
                                values.add(setting.getTextContent().trim());
                            }
                        }
                    }
                }
            }
            for (String value : values) {
                for (String resolvedValue : resolve(value, properties, 5)) {
                    version(resolvedValue).ifPresent(versions::add);
                }
            }
        }
        return versions;
    }

    /** Returns the Java versions declared by the Gradle build scripts. */
    private static List<Integer> gradleVersions(List<Path> buildScripts) {
        List<Integer> versions = new ArrayList<>();
        for (Path buildScript : buildScripts) {
            try {
                String source = Files.readString(buildScript);
                for (Pattern declaration : GRADLE_DECLARATIONS) {
                    Matcher matcher = declaration.matcher(source);
                    while (matcher.find()) {
                        versions.add(Integer.parseInt(matcher.group(1)));
                    }
                }
            } catch (IOException e) {
                logger.error("Error reading the build script {}: {}", buildScript, e.getMessage());
            }
        }
        return versions;
    }

    /**
     * Resolves the references to properties within a value, following chains of properties up to the given depth: a
     * property defined several times gives one value per definition.
     */
    private static Set<String> resolve(String value, Map<String, List<String>> properties, int depth) {
        Matcher reference = PROPERTY_REFERENCE.matcher(value);
        if (depth == 0 || !reference.find() || !properties.containsKey(reference.group(1))) {
            return Set.of(value);
        }
        Set<String> values = new HashSet<>();
        for (String definition : properties.get(reference.group(1))) {
            values.addAll(resolve(reference.replaceFirst(Matcher.quoteReplacement(definition)), properties, depth - 1));
        }
        return values;
    }

    /** Parses a Java version ({@code 1.8} and {@code 8} give 8); a value still referencing a property gives none. */
    private static Optional<Integer> version(String value) {
        Matcher matcher = VERSION.matcher(value);
        return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    /** Parses a POM and returns its root element, or an empty optional if it cannot be parsed. */
    private static Optional<Element> parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The POM is only read: no external entity or DTD is loaded
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // The errors are reported by the exception below, not printed by the parser
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(pom.toFile());
            return Optional.of(document.getDocumentElement());
        } catch (Exception e) {
            logger.warn("Unable to parse the POM {}: {}", pom, e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns the elements with the given tag name within an element, at any depth. */
    private static List<Element> descendants(Element element, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = element.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    /** Returns the child elements of an element. */
    private static List<Element> children(Element element) {
        List<Element> elements = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                elements.add(child);
            }
        }
        return elements;
    }

    /** Returns the text of the first child element with the given tag name, or an empty string. */
    private static String childText(Element element, String tagName) {
        return children(element).stream()
                .filter(child -> child.getTagName().equals(tagName))
                .map(child -> child.getTextContent().trim())
                .findFirst()
                .orElse("");
    }
}
