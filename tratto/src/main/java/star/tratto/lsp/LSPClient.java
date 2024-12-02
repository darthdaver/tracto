package star.tratto.lsp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.services.LanguageServer;
import star.tratto.utils.FileUtils;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LSPClient {
    private LanguageServer languageServer;
    private Process process;

    public void startServer(Path eclipseJdtLsJarPath, Path configPath, Path dataPath) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("/Users/davidemolinelli/.sdkman/candidates/java/17.0.9-oracle/bin/java");
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=ALL");
        command.add("-Xmx1G");
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens");
        command.add("java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add("/Users/davidemolinelli/Documents/phd/repositories/eclipse.jdt.ls/org.eclipse.jdt.ls.product/target/repository/plugins/org.eclipse.equinox.launcher_1.6.900.v20240613-2009.jar");
        command.add("-configuration");
        command.add("/Users/davidemolinelli/Documents/phd/repositories/eclipse.jdt.ls/org.eclipse.jdt.ls.product/target/repository/config_mac");
        command.add("-data");
        command.add("/Users/davidemolinelli/.jdtls");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // Create the directories to the data path if they do not exist
        FileUtils.createDirectories(dataPath);
        // Launch server
        this.process = processBuilder.start();
        InputStream inputStream = process.getInputStream();
        OutputStream outputStream = process.getOutputStream();

        // Log the server's output and error streams
        logStream(process.getInputStream(), "OUTPUT");
        logStream(process.getErrorStream(), "ERROR");

        Launcher<LanguageServer> launcher = Launcher.createLauncher(
                new LanguageClientReceiver(),
                LanguageServer.class,
                inputStream,
                outputStream
        );
        // Get reference to the language server
        this.languageServer = launcher.getRemoteProxy();
        // Start listening
        launcher.startListening();

        // Add a shutdown hook to close the streams when the JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                inputStream.close();
                outputStream.close();
                process.destroy();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
    }

    public LanguageServer getLanguageServer() {
        return languageServer;
    }

    public void initializeProject(Path projectPath) {
        InitializeParams params = new InitializeParams();
        params.setCapabilities(new ClientCapabilities());
        params.setRootUri(projectPath.toUri().toString());

        // Log the workspace folder URI
        WorkspaceFolder workspaceFolder = new WorkspaceFolder(projectPath.toUri().toString());
        System.out.println("WorkspaceFolder URI: " + workspaceFolder.getUri());

        // Setting workspace folders
        params.setWorkspaceFolders(Collections.singletonList(workspaceFolder));

        System.out.println("Initializing project...");


        // Log the initialize parameters
        logJsonRpcMessage(params);
        CompletableFuture<InitializeResult> result = this.languageServer.initialize(params);

        try {
            System.out.println("Initializing project...");
            InitializeResult initResult = result.get();
            logJsonRpcMessage(initResult); // Log the initialize result
            System.out.println("Project initialized");
        } catch (Exception e) {
            System.err.printf("Error initializing project: %s%n", projectPath);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Utility method to log JSON-RPC messages
    private void logJsonRpcMessage(Object message) {
        System.out.println("JSON-RPC message:");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(message));
    }

    // Example of lenient JSON parsing
    private <T> T parseJsonLenient(String json, Class<T> classOfT) {
        Gson gson = new Gson();
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);  // Enable lenient parsing

        try {
            return gson.fromJson(reader, classOfT);
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<CompletionItem> nextEligibleTokens(Path filePath, Position position) {
        CompletionParams completionParams = new CompletionParams(
                new TextDocumentIdentifier(filePath.toUri().toString()),
                position
        );

        CompletableFuture<Either<List<CompletionItem>,CompletionList>> completions = languageServer
                .getTextDocumentService()
                .completion(completionParams);

        try {
            Either<List<CompletionItem>, CompletionList> either = completions.get();
            List<CompletionItem> items;
            if (either.isLeft()) {
                items = either.getLeft();
            } else {
                items = either.getRight().getItems();
            }
            return items;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    private void logStream(InputStream stream, String streamName) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.printf("%s: %s%n", streamName, line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}