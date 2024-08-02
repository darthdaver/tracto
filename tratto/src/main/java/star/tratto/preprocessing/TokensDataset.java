package star.tratto.preprocessing;

import star.tratto.lsp.LSPClient;
import star.tratto.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Path;

public class TokensDataset {

    public static void main(String[] args) {
        // Process arguments
        Path projectPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        Path eclipseJdtLsJarPath = Path.of(args[2]);
        Path configPath = Path.of(args[3]);
        Path dataPath = Path.of(args[4]);
        // Start LSP client-server
        LSPClient lspClient = new LSPClient();
        try {
            lspClient.startServer(eclipseJdtLsJarPath, configPath, dataPath);
        } catch (IOException e) {
            System.err.println("Error starting LSP server. Program will exit.");
            e.printStackTrace();
            System.exit(1);
        }
        // Generate directories to output path if they do not exist
        FileUtils.createDirectories(outputPath);
        // Initialize project
        lspClient.initializeProject(projectPath);
    }
}
