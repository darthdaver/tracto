package star.tracto.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

public class LanguageClientReceiver implements LanguageClient {

    @Override
    public void telemetryEvent(Object object) {
        // This method is called when a telemetry event is received from the server.
        // Telemetry events can be used for logging, metrics, etc.
        System.out.println("Telemetry event received: " + object);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // This method is called when diagnostics (errors, warnings) are published by the server.
        // The diagnostics contain information about issues in the code.
        System.out.println("Diagnostics published for: " + diagnostics.getUri());
        List<Diagnostic> diagnosticList = diagnostics.getDiagnostics();
        for (Diagnostic diagnostic : diagnosticList) {
            System.out.println("Range: " + diagnostic.getRange());
            System.out.println("Severity: " + diagnostic.getSeverity());
            System.out.println("Message: " + diagnostic.getMessage());
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        // This method is called when the server wants to show a message to the user.
        // The message can be information, warning, or error.
        System.out.println("Show message: " + messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams messageParams) {
        // This method is called when the server requests the client to show a message and possibly wait for a user response.
        // The client can implement this to display messages in a GUI and handle user input if needed.
        System.out.println("Show message request: " + messageParams.getMessage());
        // Return a dummy future, you can customize this to handle user input if needed
        return CompletableFuture.completedFuture(new MessageActionItem("OK"));
    }

    @Override
    public void logMessage(MessageParams message) {
        // This method is called when the server wants to log a message.
        // The log can contain information, warnings, or errors intended for logging purposes.
        System.out.println("Log message: " + message.getMessage());
    }
}
