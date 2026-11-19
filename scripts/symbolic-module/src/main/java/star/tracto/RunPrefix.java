package star.tracto;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;


/** Play/Debug IntelliJ: PrefixClient originale gira nella stessa JVM del debugger. */
public final class RunPrefix {

    private static final String PREFIX_PROCESS_LOG_FILE = "processo.txt";
    private static final String PREFIX_PROCESS_LOG_LOCATION = "[PREFIX] Log del processo in %s%n";
    private static final String PREFIX_IDE_PREPARING_LOG = "[TRACTO 1/5 - Preparazione] Preparo i test da completare e gli strumenti Java";
    private static final String PREFIX_IDE_READY_LOG = "[TRACTO 1/5 - Preparazione] I test sono pronti. Avvio la generazione";
    private static final String CLASSPATH_PROPERTY = "tracto.prefix.classpath";
    private static final String BLOCK_FLAG = "--block";
    private static final String EMPTY_STRING = "";
    private static final String TRACTO_ROOT_MARKER = "Avvia-Tracto.cmd";
    private static final String PREFIX_ROOT_NOT_FOUND = "Aprire il progetto Tracto.";
    private static final String LOCAL_OUTPUT_DIRECTORY = "local-output";
    private static final String PREFIX_IDE_SESSION_PREFIX = "prefix-ide-original-";
    private static final String IDE_LAUNCH_FILE = "launch.txt";
    private static final String WINDOWS_PYTHON_PATH = ".venv/Scripts/python.exe";
    private static final String PREFIX_RUNNER_PATH = "scripts/prefix/run_original.py";
    private static final String IDE_MANIFEST_FLAG = "--ide-manifest";
    private static final String PROJECT_FLAG = "--project";
    private static final String DEFAULT_PREFIX_PROJECT = "simple";
    private static final String MODEL_FLAG = "--model";
    private static final String PREFIX_MODEL_PROPERTY = "tracto.prefix.model";
    private static final String DEFAULT_PREFIX_MODEL = "base";
    private static final String PREFIX_REPLAY_PROPERTY = "tracto.prefix.replay";
    private static final String REPLAY_FLAG = "--replay";
    private static final String PREFIX_REPLAY_PATH = "scripts/prefix/original-replay.json";
    private static final String PREFIX_BLOCK_REPLAY_PATH = "scripts/prefix/block-replay.json";
    private static final String PREFIX_PREPARATION_FAILED = "Preparazione originale fallita: leggere i log.";
    private static final String PREFIX_PREPARATION_TIMEOUT = "Preparazione oltre sei minuti.";
    private static final String MAIN_METHOD = "main";
    private static final String IDE_DONE_FILE = "launch.done";
    private static final String IDE_DONE_MARKER = "done";
    private static final String ERROR = "ERROR";

    /** Prepara la sessione IntelliJ e avvia PrefixClient nella JVM del debugger. */
    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        Path session = Files.createTempDirectory(Files.createDirectories(root.resolve(LOCAL_OUTPUT_DIRECTORY)), PREFIX_IDE_SESSION_PREFIX);
        Path logPath = session.resolve(PREFIX_PROCESS_LOG_FILE);
        PrintStream console = System.out;
        PrintStream errors = System.err;
        try (OutputStream log = Files.newOutputStream(logPath)) {
            System.setOut(mirrorOutput(console, log));
            System.setErr(mirrorOutput(errors, log));
            try {
                System.out.printf(PREFIX_PROCESS_LOG_LOCATION, logPath);
                runSession(root, session, args);
            } catch (Exception error) {
                error.printStackTrace(System.err);
                throw error;
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(console);
                System.setErr(errors);
            }
        }
    }

    /** Avvia il preparatore e poi il client nella stessa JVM. */
    private static void runSession(Path root, Path session, String[] args) throws Exception {
        Path manifest = session.resolve(IDE_LAUNCH_FILE);
        System.out.println(PREFIX_IDE_PREPARING_LOG);
        Process helper = startPreparation(root, manifest, args);
        Thread helperOutput = forwardPreparationOutput(helper, System.out);
        Thread cleanup = new Thread(helper::destroy);
        Runtime.getRuntime().addShutdownHook(cleanup);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        String previousClasspath = System.getProperty(CLASSPATH_PROPERTY);
        try {
            waitForManifest(manifest, helper);
            System.out.println(PREFIX_IDE_READY_LOG);
            runInference(Files.readAllLines(manifest));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            if (previousClasspath == null) System.clearProperty(CLASSPATH_PROPERTY);
            else System.setProperty(CLASSPATH_PROPERTY, previousClasspath);
            Files.writeString(manifest.resolveSibling(IDE_DONE_FILE), IDE_DONE_MARKER);
            if (!helper.waitFor(10, TimeUnit.SECONDS)) {
                helper.destroyForcibly();
                helper.waitFor();
            }
            helperOutput.join();
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }

    /** Cerca la radice di Tracto risalendo dalla cartella di lavoro. */
    private static Path findProjectRoot() {
        Path root = Path.of(EMPTY_STRING).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(TRACTO_ROOT_MARKER))) root = root.getParent();
        if (root == null) throw new IllegalStateException(PREFIX_ROOT_NOT_FOUND);
        return root;
    }

    /** Avvia il processo Python che prepara la sessione locale. */
    private static Process startPreparation(Path root, Path manifest, String[] args) throws java.io.IOException {
        List<String> arguments = Arrays.asList(args);
        boolean block = arguments.contains(BLOCK_FLAG);
        String project = arguments.stream().filter(argument -> !BLOCK_FLAG.equals(argument))
                .findFirst().orElse(DEFAULT_PREFIX_PROJECT);
        List<String> command = new ArrayList<>(List.of(root.resolve(WINDOWS_PYTHON_PATH).toString(),
                root.resolve(PREFIX_RUNNER_PATH).toString(), IDE_MANIFEST_FLAG, manifest.toString(),
                PROJECT_FLAG, project, MODEL_FLAG, System.getProperty(PREFIX_MODEL_PROPERTY, DEFAULT_PREFIX_MODEL)));
        if (block) {
            command.add(BLOCK_FLAG);
        }
        if (Boolean.getBoolean(PREFIX_REPLAY_PROPERTY)) {
            String replayPath = block ? PREFIX_BLOCK_REPLAY_PATH : PREFIX_REPLAY_PATH;
            command.add(REPLAY_FLAG);
            command.add(root.resolve(replayPath).toString());
        }
        return new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    }

    /** Inoltra i log di Python alla console e al file durante la preparazione. */
    private static Thread forwardPreparationOutput(Process helper, PrintStream output) {
        return Thread.startVirtualThread(() -> {
            try (var input = helper.getInputStream()) {
                input.transferTo(output);
            } catch (IOException error) {
                error.printStackTrace(output);
            }
        });
    }

    /** Duplica ogni scrittura sul terminale originale e sul file condiviso. */
    private static PrintStream mirrorOutput(PrintStream console, OutputStream log) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                synchronized (log) {
                    console.write(value);
                    log.write(value);
                }
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                synchronized (log) {
                    console.write(bytes, offset, length);
                    log.write(bytes, offset, length);
                }
            }

            @Override
            public void flush() throws IOException {
                synchronized (log) {
                    console.flush();
                    log.flush();
                }
            }
        }, true, StandardCharsets.UTF_8);
    }

    /** Attende il file di avvio e segnala un arresto anticipato o un timeout. */
    private static void waitForManifest(Path manifest, Process helper) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(6);
        while (!Files.exists(manifest)) {
            if (!helper.isAlive()) throw new IllegalStateException(PREFIX_PREPARATION_FAILED);
            if (System.nanoTime() > deadline) throw new IllegalStateException(PREFIX_PREPARATION_TIMEOUT);
            Thread.sleep(200);
        }
    }

    /** Carica il runtime preparato e invoca il suo entry point nella JVM corrente. */
    private static void runInference(List<String> launch) throws Exception {
        System.setProperty(CLASSPATH_PROPERTY, launch.get(1));
        try (URLClassLoader runtime = new URLClassLoader(new java.net.URL[]{Path.of(launch.get(0)).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(runtime);
            Class.forName(InferenceClient.class.getName(), true, runtime).getMethod(MAIN_METHOD, String[].class)
                    .invoke(null, (Object) launch.subList(2, launch.size()).toArray(String[]::new));
        }
    }
}

