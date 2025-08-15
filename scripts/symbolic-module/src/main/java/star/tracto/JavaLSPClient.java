package star.tracto;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.*;
import org.eclipse.lsp4j.services.*;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class JavaLSPClient {

    public static void main(String[] args) throws Exception {
        // Path to JDT LS installation (after building)
        String jdtLauncher = "/Users/davidemolinelli/Documents/phd/repositories/eclipse.jdt.ls/org.eclipse.jdt.ls.product/target/repository/plugins/org.eclipse.equinox.launcher_1.7.0.v20250519-0528.jar";
        String workspace = "/Users/davidemolinelli/Documents/phd/repositories/llm-prompts-empirical-study/input/github-repos/twilio/twilio-java";
        String partialCode = "HttpClient.";

        System.out.println("step-1");

        List<String> command = List.of(
                "/Users/davidemolinelli/.sdkman/candidates/java/21.ea.8-open/bin/java", "-noverify", "-XX:+UseParallelGC", "-jar", jdtLauncher,
                "-configuration", "/Users/davidemolinelli/Documents/phd/repositories/eclipse.jdt.ls/org.eclipse.jdt.ls.product/target/repository/config_mac",
                "-data", workspace
        );

        System.out.println("step-2");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process serverProcess = processBuilder.start();

        InputStream serverInput = serverProcess.getInputStream();
        OutputStream serverOutput = serverProcess.getOutputStream();

        System.out.println("step-3");

//        new Thread(() -> {
//            try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {
//                String line;
//                while ((line = reader.readLine()) != null) {
//                    System.out.println("[JDT LS] " + line);
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }).start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println("[JDT LS ERROR] " + line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                new LanguageClient() {
                    @Override
                    public void telemetryEvent(Object object) {
                        // no-op
                    }

                    @Override
                    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
                        // no-op
                    }

                    @Override
                    public void showMessage(MessageParams messageParams) {
                        // no-op
                    }

                    @Override
                    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void logMessage(MessageParams message) {
                        // no-op
                    }

                    @JsonNotification("language/status")
                    public void languageStatus(Map<String, Object> params) {
                        System.out.println("[status] " + params);
                    }
                },
                serverInput,
                serverOutput
        );

        System.out.println("step-4");

        LanguageServer server = launcher.getRemoteProxy();
        launcher.startListening();

        System.out.println("step-5");

        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(new File(workspace).toURI().toString());
        TextDocumentClientCapabilities textCapabilities = new TextDocumentClientCapabilities();
        textCapabilities.setCompletion(new CompletionCapabilities());

        CompletionCapabilities completionCapabilities = new CompletionCapabilities();
        CompletionItemCapabilities itemCapabilities = new CompletionItemCapabilities();
        itemCapabilities.setSnippetSupport(true);
        completionCapabilities.setCompletionItem(itemCapabilities);

        ClientCapabilities clientCapabilities = new ClientCapabilities();
        clientCapabilities.setTextDocument(textCapabilities);

        initParams.setCapabilities(clientCapabilities);

        InitializeResult result = server.initialize(initParams).get();
        server.initialized(new InitializedParams());
        Thread.sleep(3000);

        System.out.println("step-6");

        // Simulate a Java file
        String fakeFileUri = new File("/Users/davidemolinelli/Documents/phd/repositories/llm-prompts-empirical-study/input/github-repos/twilio/twilio-java/src/main/java/com/twilio/Fake.java").toURI().toString();
        String source = "package com.twilio;\n\nimport java.lang.System;\npublic class Fake { void test() { HttpClient. } }";


        VersionedTextDocumentIdentifier docId = new VersionedTextDocumentIdentifier();
        docId.setUri(fakeFileUri);

        TextDocumentItem docItem = new TextDocumentItem(fakeFileUri, "java", 1, source);
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(docItem);
        server.getTextDocumentService().didOpen(openParams);
        Thread.sleep(3000);

        // Completion at the end of 'System.out.pr'
        int line = 3;
        int character = source.split("\n")[3].indexOf("HttpClient.") + "HttpClient.".length();

        System.out.println(character);

        CompletionParams completionParams = new CompletionParams(docId, new Position(line, character));
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> completions = server.getTextDocumentService()
                .completion(completionParams);

        Either<List<CompletionItem>, CompletionList> resultCompletion = completions.get();

        List<CompletionItem> items;

        if (resultCompletion.isLeft()) {
            items = resultCompletion.getLeft();
        } else {
            items = resultCompletion.getRight().getItems();
        }

        System.out.println("Completions:");
        if (items.isEmpty()) {
            System.out.println(" - (no completions returned)");
        }
        for (CompletionItem item : items) {
            System.out.println(" - " + item.getLabel());
        }

        server.shutdown();
        server.exit();
        serverProcess.destroy();
    }
}
