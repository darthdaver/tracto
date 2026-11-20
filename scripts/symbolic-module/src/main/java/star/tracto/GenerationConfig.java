package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Dispatches the selected generation mode to the existing command-line client. */
record GenerationConfig(String mode, String repository, String dataset, String output, String javaContext,
                        String project, String javaVersion, Integer tractoPort, Integer prefixPort,
                        Integer promptConfig, Integer maxSteps, Integer maxOracles, String replay) {

    static void run(Path path) throws Exception {
        Path configPath = path.toAbsolutePath().normalize();
        GenerationConfig config = new ObjectMapper().readValue(configPath.toFile(), GenerationConfig.class);
        if (config == null) throw new IllegalArgumentException("Expected a generation configuration object");
        config.launch(configPath.getParent());
    }

    private void launch(Path base) throws Exception {
        String prefixFlag = switch (required(mode, "mode")) {
            case "tracto" -> null;
            case "prefix" -> "--block";
            case "prefix-block-cycle" -> "--interleaved";
            case "prefix-line-cycle" -> "--interleaved-lines";
            default -> throw new IllegalArgumentException("Unknown generation mode: " + mode);
        };
        String repoPath = resolve(base, repository, "repository");
        String datasetPath = resolve(base, dataset, "dataset");
        String outputPath = resolve(base, output, "output");
        String contextPath = resolve(base, javaContext, "javaContext");
        String prompt = positive(promptConfig, 1, "promptConfig");

        if (prefixFlag == null) {
            Client.run(new String[]{"inference", required(project, "project"), repoPath, outputPath, contextPath,
                    datasetPath, port(tractoPort, "tractoPort"), prompt, javaVersion == null ? "auto" : javaVersion});
            return;
        }

        String oraclePort = "prefix".equals(mode) ? null : port(tractoPort, "tractoPort");
        var args = new ArrayList<>(List.of(repoPath, datasetPath, outputPath, contextPath,
                port(prefixPort, "prefixPort"), prompt, positive(maxSteps, PrefixClient.DEFAULT_MAX_STEPS, "maxSteps"),
                prefixFlag));
        if (replay != null) args.add(resolve(base, replay, "replay"));
        PrefixClient.run(args.toArray(String[]::new), oraclePort,
                Integer.valueOf(positive(maxOracles, PrefixClient.DEFAULT_MAX_ORACLES, "maxOracles")),
                javaVersion == null ? "auto" : javaVersion);
    }

    private static String resolve(Path base, String value, String field) {
        return base.resolve(required(value, field)).normalize().toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration: " + field);
        return value;
    }

    private static String port(Integer value, String field) {
        if (value == null || value < 1 || value > 65535) {
            throw new IllegalArgumentException(field + " must be a port between 1 and 65535");
        }
        return value.toString();
    }

    private static String positive(Integer value, int defaultValue, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < 1) throw new IllegalArgumentException(field + " must be positive");
        return Integer.toString(resolved);
    }
}
