package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import star.tracto.preprocessing.components.*;
import star.tracto.preprocessing.utils.TestUtils;
import star.tracto.preprocessing.utils.TokensUtilsTestPrefix;
import star.tracto.lsp.antlr4.Antlr4Utils;
import star.tracto.lsp.antlr4.PrefixSyntax;
import star.tracto.data.enums.JUnitAssertionType;
import star.tracto.utils.javaParser.JavaParserUtils;


import javax.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Target prefix del Tracto originale: stessi collector, datapoint, trasporto ed E-WASH. */
public final class PrefixInference {

    private static final String PREFIX_BLOCK_REQUEST_LOG = "[TRACTO 3/5 - Generazione] Chiedo tutto il codice del test in una volta (tentativo %d)%n";
    private static final String PREFIX_BLOCK_RESPONSE_LOG = "[TRACTO 3/5 - Generazione] Ho ricevuto %d caratteri di codice. Risposta salvata in %s%n";
    private static final String PREFIX_BLOCK_VALIDATION_LOG = "[TRACTO 4/5 - Controllo] Verifico che il codice compili e chiami la classe da verificare, senza eseguire il test";
    private static final String PREFIX_TRACE_PROPERTY = "tracto.prefix.trace";
    private static final String PREFIX_TRACE_TEST = "[PREFIX 3/5] Metodo inviato al generatore%n%s%n";
    private static final String PREFIX_TRACE_RAW_BLOCK = "[PREFIX 3/5] Corpo ricevuto prima dei tagli%n%s%n";
    private static final String PREFIX_TRACE_FINAL_BLOCK = "[TRACTO 4/5 - Controllo] Questo è il codice che provo a compilare%n%s%n";
    private static final String PREFIX_TRACE_COMPILE = "[TRACTO 4/5 - Controllo] Risultato della compilazione (OK significa riuscita): %s%n";
    private static final String PREFIX_TRACE_FOCAL = "[TRACTO 4/5 - Controllo] Il codice chiama la classe %s? %s%n";
    private static final String PREFIX_INCREMENTAL_START_LOG = "[TRACTO 3/5 - Generazione] Svuoto il test originale. Costruisco il nuovo codice un elemento alla volta, senza aggiungere asserzioni";
    private static final String PREFIX_INCREMENTAL_VALIDATED_LOG = "[TRACTO 4/5 - Controllo] Generazione conclusa: il codice compila e chiama la classe da verificare. I test non sono stati eseguiti";
    private static final String PREFIX_CANDIDATES_LOG = "[TRACTO - Passo %d] Preparo le scelte ammesse: %d nomi del progetto, %d parole o simboli Java, %d tipi di valori da scrivere%n";
    private static final String PREFIX_CHOICE_LOG = "[TRACTO - Scelta] Il generatore ha risposto: %s. Ora controllo se posso accettarlo%n";
    private static final String PREFIX_STATEMENT_CHECK_LOG = "[TRACTO - Controllo] Il codice forma istruzioni complete. Provo a compilarlo";
    private static final String PREFIX_END_AVAILABLE_LOG = "[TRACTO - Controllo] Il codice compila e usa la classe da verificare: ora il generatore può scegliere END_PREFIX per terminare";
    private static final String TEST = "test";
    private static final String OK = "OK";
    private static final String EMPTY_STRING = "";
    private static final String DASH = "-";
    private static final String SPACE = " ";
    private static final String TOKEN_STEP_FILE = "token-step.json";
    private static final String DIAGNOSTICS_FIELD = "diagnostics";
    private static final String REPAIR_REQUEST_FILE = "repair-request.json";
    private static final String BLOCK_REQUEST_FILE = "block-request.json";
    private static final String LESS_THAN = "<";
    private static final String OPEN_BLOCK_SPACE = "{ ";
    private static final String PREFIX_MASK = "/*<MASK_PLACEHOLDER>*/";
    private static final String PREFIX_END = "END_PREFIX";
    private static final String TOKEN_DECIMAL_LITERAL = "DECIMAL_LITERAL";
    private static final String TOKEN_HEX_LITERAL = "HEX_LITERAL";
    private static final String TOKEN_OCT_LITERAL = "OCT_LITERAL";
    private static final String TOKEN_BINARY_LITERAL = "BINARY_LITERAL";
    private static final String TOKEN_FLOAT_LITERAL = "FLOAT_LITERAL";
    private static final String TOKEN_HEX_FLOAT_LITERAL = "HEX_FLOAT_LITERAL";
    private static final String TOKEN_CHAR_LITERAL = "CHAR_LITERAL";
    private static final String TOKEN_STRING_LITERAL = "STRING_LITERAL";
    private static final String TOKEN_TEXT_BLOCK = "TEXT_BLOCK";
    private static final String LEADING_PATH_SEPARATOR_PATTERN = "^[\\\\/]";
    private static final String EMPTY_BLOCK = "{}";
    private static final String METHOD_BODY_START = " {\n";
    private static final String CLOSE_BLOCK_LINE = "\n}";
    private static final String FOCAL_SOURCE_FIELD = "focalSource";
    private static final String ERROR = "ERROR";
    private static final String BLOCK_RESPONSE_FILE = "block-response.txt";
    private static final String REPAIR_RESPONSE_FILE = "repair-response.txt";
    private static final String PREFIX_EMPTY_BODY = "Corpo vuoto.";
    private static final String CONTINUATION_FIELD = "continuation";
    private static final String PREFIX_CYCLE_FINISHED = "Nessun nuovo segmento: il ciclo prefix/oracolo è concluso.";
    private static final String FINAL_BODY_DIRECTORY = "final";
    private static final String PREFIX_FINAL_COMPILED = "Il test completo (prefix e oracoli) compila e chiama la classe sotto test.";
    private static final String OPEN_BLOCK_LINE = "{\n";
    private static final String ASSERT_THAT = "assertThat";
    private static final String NEWLINE = "\n";
    private static final String PREFIX_ASSERTION_TRIMMED = "Tracto prefix: estratto il corpo prima dell'asserzione.";
    private static final String PREFIX_NO_STATEMENTS = "Nessuna istruzione prima dell'asserzione.";
    private static final String STATIC_CONTEXT_DIAGNOSTIC = "cannot be referenced from a static context";
    private static final String RECEIVER_REPAIR_FILE = "receiver-repair.txt";
    private static final String PREVIOUS_BODY_FIELD = "previousBody";
    private static final String PREFIX_REPAIR_ATTEMPT = "[TRACTO - Correzione] Invio al generatore gli errori di compilazione e chiedo di correggere il codice una volta.";
    private static final String PREFIX_MISSING_FOCAL_CALL = "Manca una chiamata risolta alla classe sotto test.";
    private static final String JAVA_EXTENSION = ".java";
    private static final String PREFIX_BLOCK_REPAIRED = "Blocco compilato dopo correzione AST del ricevente.";
    private static final String PREFIX_BLOCK_COMPILED = "Blocco compilato, con chiamata risolta alla classe sotto test.";
    private static final String RESULT_FILE = "result.json";
    private static final String NEW_EXPRESSION = "new ";
    private static final String EMPTY_ARGUMENTS = "()";
    private static final String GENERATED_RECEIVER = "tractoReceiver";
    private static final String NEW_RECEIVER_ASSIGNMENT = " = new ";
    private static final String EMPTY_CALL_STATEMENT = "();";
    private static final String PACKAGE_SEPARATOR_PATTERN = "\\.";
    private static final String CLOSE_BLOCK_SPACE = " }";
    private static final String LIMIT = "LIMIT";
    private static final String PREFIX_STEP_LIMIT = "Limite di passi raggiunto.";
    private static final String IDENTIFIER_TOKEN = "IDENTIFIER";
    private static final String RIGHT_BRACE = "}";
    private static final String JAVA_VAR = "var";
    private static final String JAVA_TRUE = "true";
    private static final String JAVA_FALSE = "false";
    private static final String JAVA_NULL = "null";
    private static final String PLUS = "+";
    private static final String BANG = "!";
    private static final String TILDE = "~";
    private static final String PREFIX_NO_CANDIDATES = "Non trovo una continuazione valida per il codice costruito finora.";
    private static final String TOKEN_STEPS_DIRECTORY = "token-steps";
    private static final String TOKEN_STEP_FILE_FORMAT = "step-%03d.json";
    private static final String INVALID_EMISSION_PREFIX = "Emissione non valida per ";
    private static final String REJECTED_CATEGORY = "REJECTED_CATEGORY";
    private static final String STEPS_FILE = "steps.json";
    private static final String PREFIX_DISCARDED_CATEGORY = "[TRACTO - Riprovo] Il valore prodotto non è valido. Escludo per questo punto il tipo [";
    private static final String BRACKET_COLON = "]: ";
    private static final String PREFIX_COMPILED = "Il codice compila e chiama la classe da verificare. Test non eseguito.";
    private static final String PREFIX_INVALID_CHOICE = "Scelta non valida.";
    private static final String PREFIX_UNKNOWN_CHOICE = "La risposta non fa parte delle scelte ammesse in questo punto.";
    private static final String PREFIX_INVALID_CONTINUATION = "Aggiungendo questa risposta, il codice non può proseguire con una sintassi Java valida.";
    private static final String REJECTED = "REJECTED";
    private static final String PREFIX_DISCARDED_TOKEN = "[TRACTO - Riprovo] Non aggiungo al codice la risposta [";
    private static final String EQUALS_SIGN = "=";
    private static final String SYMBOLIC_ERROR = "SYMBOLIC_ERROR";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String PREFIX_LOG = "[TRACTO - Accettato] Codice costruito finora: ";
    private static final String PREFIX_REPEATED_CHOICE = "Ripetizione rilevata dal controllo originale di Tracto.";
    private static final String PREFIX_FIELD = "prefix";
    private static final String CHOICE_FIELD = "choice";
    private static final String STATUS_FIELD = "status";
    private static final String REASON_FIELD = "reason";
    private static final String CANDIDATE_IDENTIFIER = "__tractoCandidate";
    private static final String PROBE_DECIMAL = "0";
    private static final String PROBE_HEX = "0x1";
    private static final String PROBE_BINARY = "0b1";
    private static final String PROBE_FLOAT = "1.0";
    private static final String PROBE_HEX_FLOAT = "0x1.0p1";
    private static final String PROBE_CHAR = "'a'";
    private static final String PROBE_STRING = "\"x\"";
    private static final String PROBE_TEXT_BLOCK = "\"\"\"\ntext\n\"\"\"";
    private static final String DECLARATION_PROBE_SUFFIX = " __tractoName; }";
    private static final String DECLARATION_NAME_PROBE = "__tractoName";
    private static final String JAVA_MODULE = "module";
    private static final String JAVA_OPEN = "open";
    private static final String JAVA_REQUIRES = "requires";
    private static final String JAVA_EXPORTS = "exports";
    private static final String JAVA_OPENS = "opens";
    private static final String JAVA_TO = "to";
    private static final String JAVA_USES = "uses";
    private static final String JAVA_PROVIDES = "provides";
    private static final String JAVA_WITH = "with";
    private static final String JAVA_TRANSITIVE = "transitive";
    private static final String JAVA_YIELD = "yield";
    private static final String JAVA_RECORD = "record";
    private static final String JAVA_SEALED = "sealed";
    private static final String JAVA_PERMITS = "permits";
    private static final String JAVA_ASSERT = "assert";
    private static final String ASSERT_CLASS = "Assert";
    private static final String DOT = ".";
    private static final String VALIDATION_SOURCE_DIRECTORY = "validation-src";
    private static final String VALIDATION_CLASSES_DIRECTORY = "validation-classes";
    private static final String JAVAC_NO_PROCESSING = "-proc:none";
    private static final String JAVAC_NO_IMPLICIT = "-implicit:none";
    private static final String JAVAC_CLASSPATH = "-classpath";
    private static final String JAVAC_SOURCEPATH = "-sourcepath";
    private static final String JAVAC_OUTPUT = "-d";
    private static final String DIAGNOSTIC_SEPARATOR = "; ";

    public static final String MASK = PREFIX_MASK;
    public static final String END = PREFIX_END;
    private static final Set<String> LITERALS = Set.of(TOKEN_DECIMAL_LITERAL, TOKEN_HEX_LITERAL, TOKEN_OCT_LITERAL, TOKEN_BINARY_LITERAL,
            TOKEN_FLOAT_LITERAL, TOKEN_HEX_FLOAT_LITERAL, TOKEN_CHAR_LITERAL, TOKEN_STRING_LITERAL, TOKEN_TEXT_BLOCK);
    @FunctionalInterface public interface Neural {
        String next(TestClazzDatapoints<PartialOracleDatapoint> step) throws Exception;
    }
    public record Result(String test, String prefix, String status, long milliseconds, String message) {}
    /** Impedisce di istanziare questa classe di utilità. */
    private PrefixInference() {}

    @FunctionalInterface public interface BlockNeural {
        String generate(Map<String, String> request) throws Exception;
    }

    /** Raccoglie i sorgenti e i nodi AST usati dai due percorsi di generazione. */
    private record PreparedTest(Path testFile, Path focalFile, CompilationUnit unit,
                                TypeDeclaration<?> type, MethodDeclaration method) {}

    /** Carica una copia della classe di test e individua il metodo del caso. */
    private static PreparedTest prepareTest(Path repo, TestClazzDatapoints<OracleDatapoint> data,
                                            OracleDatapoint point) throws Exception {
        Path testFile = repo.resolve(data.testClass().prefixFilePath().replaceFirst(LEADING_PATH_SEPARATOR_PATTERN, EMPTY_STRING));
        Path focalFile = repo.resolve(data.focalClass().filePath().replaceFirst(LEADING_PATH_SEPARATOR_PATTERN, EMPTY_STRING));
        CompilationUnit unit = JavaParserUtils.getCompilationUnit(testFile).clone();
        TypeDeclaration<?> type = unit.getPrimaryType().orElse(unit.getType(0));
        MethodDeclaration method = type.getMethodsByName(point.testPrefix().identifier()).stream().findFirst().orElseThrow();
        return new PreparedTest(testFile, focalFile, unit, type, method);
    }

    /** Richiede il corpo intero e verifica che il prefix compili e chiami la classe sotto test. */
    public static Result generateBlock(Path repo, Path output, String classpath,
                                       TestClazzDatapoints<OracleDatapoint> data, OracleDatapoint point,
                                       boolean repair, boolean repairReceiver, BlockNeural neural) throws Exception {
        return generateBlock(repo, output, classpath, data, point, repair, repairReceiver, neural, EMPTY_STRING);
    }

    /**
     * Generates the next block starting from an existing interleaved body.
     * The original block-generation API delegates here with an empty body.
     */
    public static Result generateBlock(Path repo, Path output, String classpath,
                                       TestClazzDatapoints<OracleDatapoint> data, OracleDatapoint point,
                                       boolean repair, boolean repairReceiver, BlockNeural neural,
                                       String existingBody) throws Exception {
        Files.createDirectories(output);
        ObjectMapper mapper = new ObjectMapper();
        PreparedTest test = prepareTest(repo, data, point);
        CompilationUnit unit = test.unit();
        TypeDeclaration<?> type = test.type();
        MethodDeclaration method = test.method();
        String initialBody = existingBody == null ? EMPTY_STRING : existingBody.strip();
        method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + initialBody + CLOSE_BLOCK_LINE));
        boolean continuation = !initialBody.isEmpty();
        Map<String, String> request = new LinkedHashMap<>();
        request.put(TEST, method.getDeclarationAsString() + METHOD_BODY_START
                + (continuation ? initialBody + NEWLINE : EMPTY_STRING) + MASK + CLOSE_BLOCK_LINE);
        request.put(FOCAL_SOURCE_FIELD, Files.readString(test.focalFile()));
        if (continuation) {
            // Lets the model end the prefix/oracle cycle with an empty segment.
            request.put(CONTINUATION_FIELD, Boolean.TRUE.toString());
        }
        mapper.writeValue(output.resolve(BLOCK_REQUEST_FILE).toFile(), request);
        boolean trace = Boolean.getBoolean(PREFIX_TRACE_PROPERTY);
        if (trace) {
            System.out.printf(PREFIX_TRACE_TEST, request.get(TEST));
        }
        String body = EMPTY_STRING;
        String status = ERROR;
        String message = EMPTY_STRING;
        boolean receiverRepaired = false;
        long start = System.nanoTime();
        try {
            Map<String, String> currentRequest = request;
            for (int attempt = 0; attempt <= (repair ? 1 : 0); attempt++) {
                System.out.printf(PREFIX_BLOCK_REQUEST_LOG, attempt + 1);
                body = Objects.requireNonNull(neural.generate(currentRequest)).strip();
                Path responseFile = output.resolve(attempt == 0 ? BLOCK_RESPONSE_FILE : REPAIR_RESPONSE_FILE);
                Files.writeString(responseFile, body);
                System.out.printf(PREFIX_BLOCK_RESPONSE_LOG, body.length(), responseFile.getFileName());
                if (trace) {
                    System.out.printf(PREFIX_TRACE_RAW_BLOCK, body);
                }
                body = beforeMethodClose(body);
                if (body.isEmpty() && continuation) {
                    status = OK;
                    message = PREFIX_CYCLE_FINISHED;
                    break;
                }
                if (body.isEmpty()) throw new IllegalArgumentException(PREFIX_EMPTY_BODY);
                BlockStmt parsed;
                try {
                    parsed = StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + body + CLOSE_BLOCK_LINE);
                } catch (RuntimeException parseError) {
                    if (attempt == 0 && repair) {
                        currentRequest = prepareRepairRequest(request, body, parseError.toString(), output, mapper);
                        continue;
                    }
                    throw parseError;
                }
                if (trimAfterAssertion(parsed)) {
                    body = renderStatements(parsed);
                    System.out.println(PREFIX_ASSERTION_TRIMMED);
                }
                if (body.isBlank() && continuation) {
                    // Only a new assertion after an existing body: no further setup to generate.
                    body = EMPTY_STRING;
                    status = OK;
                    message = PREFIX_CYCLE_FINISHED;
                    break;
                }
                if (body.isBlank()) throw new IllegalArgumentException(PREFIX_NO_STATEMENTS);
                System.out.println(PREFIX_BLOCK_VALIDATION_LOG);
                // A segment is validated together with the body generated before it.
                String fullBody = joinBodies(initialBody, body);
                if (trace) {
                    System.out.printf(PREFIX_TRACE_FINAL_BLOCK, fullBody);
                }
                message = compile(unit, method, fullBody, output, classpath,
                        packageRoot(test.focalFile(), data.focalClass().packageIdentifier()),
                        packageRoot(test.testFile(), data.testClass().packageIdentifier()));
                if (trace) {
                    System.out.printf(PREFIX_TRACE_COMPILE, message.isEmpty() ? OK : message);
                }
                if (repairReceiver && message.contains(STATIC_CONTEXT_DIAGNOSTIC)) {
                    var corrected = tryReceiverRepair(parsed, initialBody, test, data, output, classpath);
                    if (corrected.isPresent()) {
                        parsed = corrected.get();
                        body = renderStatements(parsed);
                        fullBody = joinBodies(initialBody, body);
                        message = EMPTY_STRING;
                        receiverRepaired = true;
                    }
                }
                if (message.isEmpty()) {
                    boolean focalCall = invokesFocal(method, fullBody, data.focalClass());
                    if (trace) {
                        System.out.printf(PREFIX_TRACE_FOCAL, data.focalClass().identifier(), focalCall ? "sì" : "no");
                    }
                    if (!focalCall) {
                        message = PREFIX_MISSING_FOCAL_CALL;
                    }
                }
                // Repair after all validations, including the required focal-class call.
                if (!message.isEmpty() && attempt == 0 && repair) {
                    currentRequest = prepareRepairRequest(request, body, message, output, mapper);
                    continue;
                }
                if (message.isEmpty()) {
                    method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + fullBody + CLOSE_BLOCK_LINE));
                    Files.writeString(output.resolve(type.getNameAsString() + JAVA_EXTENSION), unit.toString());
                    status = OK;
                    message = receiverRepaired ? PREFIX_BLOCK_REPAIRED
                            : PREFIX_BLOCK_COMPILED;
                }
                break;
            }
        } catch (Exception error) { message = error.toString(); }
        var result = new Result(method.getNameAsString(), body, status, (System.nanoTime() - start) / 1_000_000, message);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(RESULT_FILE).toFile(), result);
        return result;
    }

    /** Loads the request fields shared by all rounds for one test. */
    public static Map<String, String> oracleCycleTemplate(Path repo, TestClazzDatapoints<OracleDatapoint> data,
                                                          OracleDatapoint point) throws Exception {
        PreparedTest test = prepareTest(repo, data, point);
        Map<String, String> request = new LinkedHashMap<>();
        request.put(TEST, test.method().getDeclarationAsString() + METHOD_BODY_START + OracleInsertion.MASK + CLOSE_BLOCK_LINE);
        request.put(FOCAL_SOURCE_FIELD, Files.readString(test.focalFile()));
        return request;
    }

    /** Publishes the test already compiled during the last successful round. */
    public static void publishValidatedBody(Path segmentOutput, Path caseOutput) throws IOException {
        Path source = segmentOutput.resolve(FINAL_BODY_DIRECTORY);
        Path destination = caseOutput.resolve(FINAL_BODY_DIRECTORY);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Compiles a complete test body (prefix segments and oracles) and checks the focal-class call.
     * On success the generated test class is saved under {@code output/final}.
     */
    public static Result validateBody(Path repo, Path output, String classpath,
                                      TestClazzDatapoints<OracleDatapoint> data, OracleDatapoint point,
                                      BlockStmt body) throws Exception {
        long start = System.nanoTime();
        Path finalOutput = Files.createDirectories(output.resolve(FINAL_BODY_DIRECTORY));
        PreparedTest test = prepareTest(repo, data, point);
        String status = ERROR;
        String message;
        try {
            test.method().setBody(body.clone());
            message = compile(test.unit(), finalOutput, classpath,
                    packageRoot(test.focalFile(), data.focalClass().packageIdentifier()),
                    packageRoot(test.testFile(), data.testClass().packageIdentifier()));
            if (message.isEmpty() && !invokesFocal(test.method(), data.focalClass())) {
                message = PREFIX_MISSING_FOCAL_CALL;
            }
            if (message.isEmpty()) {
                Files.writeString(finalOutput.resolve(test.type().getNameAsString() + JAVA_EXTENSION),
                        test.unit().toString());
                status = OK;
                message = PREFIX_FINAL_COMPILED;
            }
        } catch (Exception error) { message = error.toString(); }
        var result = new Result(test.method().getNameAsString(), OracleInsertion.contents(body), status,
                (System.nanoTime() - start) / 1_000_000, message);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(finalOutput.resolve(RESULT_FILE).toFile(), result);
        return result;
    }

    /** Compiles the proposed setup with a temporary substitute for the pending oracle. */
    public static String validateOraclePrefix(Path repo, Path output, String classpath,
                                              TestClazzDatapoints<OracleDatapoint> data, OracleDatapoint point,
                                              BlockStmt body) throws Exception {
        PreparedTest test = prepareTest(repo, data, point);
        test.method().setBody(OracleInsertion.withoutOracle(body));
        return compile(test.unit(), output, classpath,
                packageRoot(test.focalFile(), data.focalClass().packageIdentifier()),
                packageRoot(test.testFile(), data.testClass().packageIdentifier()));
    }

    /** Joins two Java statement sequences, keeping them on separate lines. */
    private static String joinBodies(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? EMPTY_STRING : second.strip();
        if (second == null || second.isBlank()) return first.strip();
        return first.strip() + NEWLINE + second.strip();
    }

    /** Prova la correzione del ricevente e accetta solo un blocco compilabile con una chiamata risolta. */
    private static Optional<BlockStmt> tryReceiverRepair(BlockStmt parsed, String initialBody, PreparedTest test,
                                                         TestClazzDatapoints<OracleDatapoint> data,
                                                         Path output, String classpath) throws Exception {
        BlockStmt corrected = parsed.clone();
        var focal = JavaParserUtils.getCompilationUnit(test.focalFile());
        if (!repairReceiver(corrected, focal, data.focalClass().identifier())) {
            return Optional.empty();
        }

        String candidate = joinBodies(initialBody, renderStatements(corrected));
        Files.writeString(output.resolve(RECEIVER_REPAIR_FILE), candidate);
        String diagnostic = compile(test.unit(), test.method(), candidate, output, classpath,
                packageRoot(test.focalFile(), data.focalClass().packageIdentifier()),
                packageRoot(test.testFile(), data.testClass().packageIdentifier()));
        if (!diagnostic.isEmpty() || !invokesFocal(test.method(), candidate, data.focalClass())) {
            return Optional.empty();
        }
        return Optional.of(corrected);
    }

    /** Aggiunge il blocco precedente e il messaggio di javac alla richiesta di correzione. */
    private static Map<String, String> prepareRepairRequest(Map<String, String> request, String body,
                                                            String diagnostic, Path output,
                                                            ObjectMapper mapper) throws java.io.IOException {
        Map<String, String> retry = new LinkedHashMap<>(request);
        retry.put(PREVIOUS_BODY_FIELD, body);
        retry.put(DIAGNOSTICS_FIELD, diagnostic);
        mapper.writeValue(output.resolve(REPAIR_REQUEST_FILE).toFile(), retry);
        System.out.println(PREFIX_REPAIR_ATTEMPT);
        return retry;
    }

    /** Taglia il blocco a partire dalla prima istruzione che contiene un'asserzione. */
    private static boolean trimAfterAssertion(BlockStmt block) {
        for (int i = 0; i < block.getStatements().size(); i++) {
            var statement = block.getStatement(i);
            boolean containsAssertion = statement.isAssertStmt()
                    || !statement.findAll(com.github.javaparser.ast.stmt.AssertStmt.class).isEmpty()
                    || statement.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    isAssertion(call.getNameAsString()) || ASSERT_THAT.equals(call.getNameAsString()));
            if (containsAssertion) {
                while (block.getStatements().size() > i) {
                    block.getStatements().remove(i);
                }
                return true;
            }
        }
        return false;
    }

    /** Converte le istruzioni analizzate in testo Java senza le graffe del blocco. */
    private static String renderStatements(BlockStmt block) {
        return String.join(NEWLINE, block.getStatements().stream().map(Object::toString).toList());
    }

    // Caso conservativo: un solo metodo di istanza e un tipo pubblico senza
    // stato o inizializzazione di istanza, con costruttore implicito.
    /** Corregge una chiamata di istanza scritta come statica nei casi ammessi. */
    private static boolean repairReceiver(BlockStmt body,
                                          CompilationUnit focal, String name) {
        var declaration = focal.getClassByName(name).orElse(null);
        if (declaration == null || !declaration.isPublic() || declaration.isInterface()
                || declaration.isAbstract() || !declaration.getConstructors().isEmpty()
                || !declaration.getExtendedTypes().isEmpty() || !declaration.getTypeParameters().isEmpty()
                || declaration.getFields().stream().anyMatch(field -> !field.isStatic())
                || declaration.getMembers().stream().anyMatch(member -> member.isInitializerDeclaration()
                        && !member.asInitializerDeclaration().isStatic())) return false;
        // Non confondere un valore omonimo con il nome del tipo.
        if (body.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(name))) return false;
        var calls = body.findAll(MethodCallExpr.class).stream().filter(call ->
                call.getScope().filter(scope -> scope.isNameExpr()
                        && scope.asNameExpr().getNameAsString().equals(name)).isPresent()).filter(call -> {
                    var methods = declaration.getMethodsByName(call.getNameAsString());
                    return methods.size() == 1 && methods.get(0).isPublic() && !methods.get(0).isStatic()
                            && methods.get(0).getParameters().size() == call.getArguments().size();
                }).toList();
        if (calls.isEmpty() || calls.stream().map(MethodCallExpr::getNameAsString).distinct().count() != 1) return false;
        String type = declaration.getFullyQualifiedName().orElse(name);
        if (calls.size() == 1) {
            calls.get(0).setScope(StaticJavaParser.parseExpression(NEW_EXPRESSION + type + EMPTY_ARGUMENTS));
        } else {
            var names = body.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                    .map(com.github.javaparser.ast.expr.SimpleName::asString).collect(java.util.stream.Collectors.toSet());
            String receiver = GENERATED_RECEIVER;
            for (int suffix = 1; names.contains(receiver); suffix++) receiver = GENERATED_RECEIVER + suffix;
            body.addStatement(0, StaticJavaParser.parseStatement(type + SPACE + receiver + NEW_RECEIVER_ASSIGNMENT + type + EMPTY_CALL_STATEMENT));
            for (var call : calls) call.setScope(new com.github.javaparser.ast.expr.NameExpr(receiver));
        }
        return true;
    }

    /** Conserva il testo prima della prima graffa che chiude il metodo. */
    private static String beforeMethodClose(String body) {
        var lexer = new star.tracto.lsp.antlr4.antlr4Class.JavaLexer(
                org.antlr.v4.runtime.CharStreams.fromString(body));
        lexer.removeErrorListeners();
        int depth = 0;
        for (var token = lexer.nextToken(); token.getType() != org.antlr.v4.runtime.Token.EOF; token = lexer.nextToken()) {
            if (token.getChannel() != org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) continue;
            if (token.getType() == star.tracto.lsp.antlr4.antlr4Class.JavaLexer.LBRACE) depth++;
            if (token.getType() == star.tracto.lsp.antlr4.antlr4Class.JavaLexer.RBRACE && --depth < 0)
                return body.substring(0, body.offsetByCodePoints(0, token.getStartIndex())).strip();
        }
        return body;
    }

    /** Risale alla radice dei sorgenti a partire dal percorso del file e dal package. */
    private static Path packageRoot(Path file, String packageName) {
        Path root = file.toAbsolutePath().getParent();
        if (!packageName.isEmpty()) for (String part : packageName.split(PACKAGE_SEPARATOR_PATTERN)) root = root.getParent();
        return root;
    }

    /** Costruisce e verifica il prefix un token Java alla volta. */
    public static Result generate(Path repo, Path output, String classpath,
                                  TestClazzDatapoints<OracleDatapoint> data, OracleDatapoint point,
                                  Neural neural, int maxSteps) throws Exception {
        Files.createDirectories(output);
        ObjectMapper mapper = new ObjectMapper();
        PreparedTest test = prepareTest(repo, data, point);
        CompilationUnit unit = test.unit();
        TypeDeclaration<?> type = test.type();
        MethodDeclaration method = test.method();
        // Nessun prefix di riferimento viene lasciato nel corpo o inviato al modello.
        method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_SPACE + MASK + CLOSE_BLOCK_SPACE));
        Callable masked = TestUtils.processCallable(type, method, List.of());
        TestClazz maskedTestClass = withMaskedTest(data.testClass(), masked);
        TokensUtilsTestPrefix.PrefixSession session = new TokensUtilsTestPrefix.PrefixSession(test.testFile(), test.focalFile(), unit, method);
        System.out.println(PREFIX_INCREMENTAL_START_LOG);
        List<String> tokens = new ArrayList<>();
        Map<String, Set<String>> excluded = new HashMap<>();
        List<Map<String, Object>> events = new ArrayList<>();
        String prefix = EMPTY_STRING;
        String status = LIMIT;
        String message = PREFIX_STEP_LIMIT;
        long started = System.nanoTime();
        try {
            for (int decision = 0; decision < maxSteps; decision++) {
                Set<String> rejected = excluded.computeIfAbsent(prefix, k -> new HashSet<>());
                boolean declarationName = declaresName(prefix);
                StepCandidates candidates = collectCandidates(prefix, session, tokens, rejected, declarationName);
                Set<String> project = candidates.project();
                Set<String> java = candidates.java();
                Set<String> generic = candidates.generic();
                boolean complete = complete(prefix);
                boolean focal = complete && invokesFocal(method, prefix, data.focalClass());
                if (focal && compile(unit, method, prefix, output, classpath, session).isEmpty()) {
                    java.add(END);
                    if (!rejected.contains(END)) System.out.println(PREFIX_END_AVAILABLE_LOG);
                }
                java.removeAll(rejected);
                if (project.isEmpty() && java.isEmpty() && generic.isEmpty()) throw new IllegalStateException(PREFIX_NO_CANDIDATES);
                TokenStep tokenStep = new TokenStep(prefix, new ArrayList<>(project), new ArrayList<>(java),
                        new ArrayList<>(generic), complete, EMPTY_STRING, session.contextualInfos());
                var payload = new TestClazzDatapoints<>(data.junitVersion(), maskedTestClass, data.focalClass(),
                        List.of(new PartialOracleDatapoint(masked, EMPTY_STRING, List.of(tokenStep))));
                saveTokenStep(mapper, output, decision, payload);
                String choice;
                try {
                    System.out.printf(PREFIX_CANDIDATES_LOG, decision + 1, project.size(), java.size(), generic.size());
                    choice = neural.next(payload);
                    System.out.printf(PREFIX_CHOICE_LOG, choice);
                } catch (star.tracto.data.exceptions.TokenNotFoundException error) {
                    // Rifiuta la categoria che non ha emesso un valore valido, poi lascia
                    // al modello una nuova scelta sullo stesso prefix. Nessun token inventato.
                    String detail = error.getMessage();
                    String category = rejectedEmissionCategory(error, generic);
                    rejected.add(category);
                    events.add(event(prefix, category, REJECTED_CATEGORY, detail));
                    mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(STEPS_FILE).toFile(), events);
                    System.out.println(PREFIX_DISCARDED_CATEGORY + category + BRACKET_COLON + detail);
                    if (rejected.stream().filter(s -> LITERALS.contains(s) || s.equals(IDENTIFIER_TOKEN)).count() >= 3) throw error;
                    continue;
                }
                if (END.equals(choice) && java.contains(END)) {
                    System.out.println(PREFIX_INCREMENTAL_VALIDATED_LOG);
                    status = OK;
                    message = PREFIX_COMPILED;
                    break;
                }
                String proposed = prefix + (prefix.isEmpty() ? EMPTY_STRING : SPACE) + choice;
                String reason = validateChoice(choice, proposed, candidates, rejected, declarationName);
                boolean statementComplete = reason.isEmpty() && complete(proposed);
                if (statementComplete) {
                    System.out.println(PREFIX_STATEMENT_CHECK_LOG);
                    reason = compile(unit, method, proposed, output, classpath, session);
                }
                if (!reason.isEmpty()) {
                    if (choice != null) rejected.add(choice);
                    events.add(event(prefix, choice, REJECTED, reason));
                    System.out.println(PREFIX_DISCARDED_TOKEN + choice + BRACKET_COLON + reason);
                } else {
                    String previous = tokens.isEmpty() ? EMPTY_STRING : tokens.get(tokens.size() - 1);
                    try {
                        updateSession(session, method, proposed, choice, previous,
                                statementComplete, declarationName);
                    } catch (RuntimeException error) {
                        events.add(event(prefix, choice, SYMBOLIC_ERROR, error.toString()));
                        throw error;
                    }
                    tokens.add(choice);
                    prefix = proposed;
                    events.add(event(prefix, choice, ACCEPTED, EMPTY_STRING));
                    System.out.println(PREFIX_LOG + prefix);
                    if (TokensUtilsTestPrefix.detectPatternRepetition(tokens, 2, 10, 3))
                        throw new IllegalStateException(PREFIX_REPEATED_CHOICE);
                }
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(STEPS_FILE).toFile(), events);
            }
        } catch (Exception error) { status = ERROR; message = error.toString(); }
        Result result = new Result(method.getNameAsString(), prefix, status, (System.nanoTime() - started) / 1_000_000, message);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(RESULT_FILE).toFile(), result);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(STEPS_FILE).toFile(), events);
        if (status.equals(OK)) {
            method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + prefix + CLOSE_BLOCK_LINE));
            Files.writeString(output.resolve(type.getNameAsString() + JAVA_EXTENSION), unit.toString());
        }
        return result;
    }

    /** Raggruppa i simboli del progetto, i token Java e le categorie da concretizzare. */
    private record StepCandidates(Set<String> project, Set<String> java, Set<String> generic) {}

    /** Conserva il contesto della classe esponendo al modello soltanto il test mascherato. */
    private static TestClazz withMaskedTest(TestClazz original, Callable masked) {
        return new TestClazz(original.identifier(), original.packageIdentifier(), original.superclasses(),
                original.interfaces(), original.filePath(), original.prefixFilePath(), original.fields(),
                original.auxiliaryMethods(), original.setupTearDownMethods(), List.of(masked));
    }

    /** Salva la richiesta corrente e la copia numerata usata per ricostruire le decisioni. */
    private static void saveTokenStep(ObjectMapper mapper, Path output, int decision,
                                      TestClazzDatapoints<PartialOracleDatapoint> payload) throws java.io.IOException {
        var writer = mapper.writerWithDefaultPrettyPrinter();
        var request = List.of(payload);
        writer.writeValue(output.resolve(TOKEN_STEP_FILE).toFile(), request);
        Path trace = Files.createDirectories(output.resolve(TOKEN_STEPS_DIRECTORY));
        writer.writeValue(trace.resolve(String.format(TOKEN_STEP_FILE_FORMAT, decision)).toFile(), request);
    }

    /** Traduce il protocollo di errore del modello in una categoria riprovabile. */
    private static String rejectedEmissionCategory(
            star.tracto.data.exceptions.TokenNotFoundException error,
            Set<String> offeredCategories) throws star.tracto.data.exceptions.TokenNotFoundException {
        String detail = error.getMessage();
        if (detail == null || !detail.startsWith(INVALID_EMISSION_PREFIX)) {
            throw error;
        }
        int separator = detail.indexOf(':', INVALID_EMISSION_PREFIX.length());
        if (separator < 0) {
            throw error;
        }
        String category = detail.substring(INVALID_EMISSION_PREFIX.length(), separator);
        if (!offeredCategories.contains(category)) {
            throw error;
        }
        return category;
    }

    /** Aggiorna i simboli solo dopo che la scelta ha superato la validazione. */
    private static void updateSession(TokensUtilsTestPrefix.PrefixSession session, MethodDeclaration method,
                                      String proposed, String choice, String previous,
                                      boolean statementComplete, boolean declarationName) {
        if (statementComplete) {
            method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_SPACE + proposed + CLOSE_BLOCK_SPACE));
            session.refresh();
        } else if (EQUALS_SIGN.equals(choice)) {
            session.resetExpression();
        } else if (!declarationName) {
            session.accept(choice, previous);
        }
    }

    /** Calcola e filtra le scelte disponibili per il prossimo token. */
    private static StepCandidates collectCandidates(String prefix, TokensUtilsTestPrefix.PrefixSession session,
                                                    List<String> tokens, Set<String> rejected,
                                                    boolean declarationName) throws Exception {
        List<String> raw = Antlr4Utils.parseTestPrefix(prefix);
        // C3 puo includere suggerimenti ottenuti dopo error recovery.
        // Mantiene il collettore originale, ma richiede una continuazione
        // realmente riconoscibile dalla sua grammatica, senza recovery.
        raw = raw.stream().filter(c -> PrefixSyntax.isViable(prefix + SPACE + representative(c))).toList();
        Set<String> project = new LinkedHashSet<>(), java = new LinkedHashSet<>(), generic = new LinkedHashSet<>();
        boolean statementStart = prefix.isBlank() || complete(prefix);
        for (String candidate : raw) {
            if (candidate.equals(IDENTIFIER_TOKEN)) {
                project.addAll(session.identifiers());
                if (declarationName) generic.add(IDENTIFIER_TOKEN);
            } else if (LITERALS.contains(candidate)) generic.add(candidate);
            else if (!candidate.equals(RIGHT_BRACE) && !isAssertion(candidate)) java.add(candidate);
        }
        // I nomi contestuali devono essere simboli risolti, non un ingresso alternativo agli identificatori.
        java.removeIf(s -> isContextualName(s) && !s.equals(JAVA_VAR));
        if (statementStart) {
            // Un primitivo isolato non puo iniziare una statement-expression Java.
            // Restano ammessi String literal ("text".trim()), chiamate e dichiarazioni.
            generic.removeAll(Set.of(TOKEN_DECIMAL_LITERAL, TOKEN_HEX_LITERAL, TOKEN_OCT_LITERAL, TOKEN_BINARY_LITERAL,
                    TOKEN_FLOAT_LITERAL, TOKEN_HEX_FLOAT_LITERAL, TOKEN_CHAR_LITERAL));
            java.removeAll(Set.of(JAVA_TRUE, JAVA_FALSE, JAVA_NULL, PLUS, DASH, BANG, TILDE));
        }
        project.removeIf(PrefixInference::isAssertion);
        if (!tokens.isEmpty() && !session.permitsTypeArguments(tokens.get(tokens.size() - 1))) java.remove(LESS_THAN);
        project.removeAll(rejected);
        java.removeAll(rejected);
        generic.removeAll(rejected);
        return new StepCandidates(project, java, generic);
    }

    /** Restituisce il motivo per cui una scelta va scartata, se presente. */
    private static String validateChoice(String choice, String proposed, StepCandidates candidates,
                                         Set<String> rejected, boolean declarationName) {
        if (choice == null || choice.isBlank() || rejected.contains(choice) || isAssertion(choice)
                || LITERALS.contains(choice) || choice.equals(IDENTIFIER_TOKEN) || choice.equals(END)) {
            return PREFIX_INVALID_CHOICE;
        }
        Set<String> generic = candidates.generic();
        if (!candidates.project().contains(choice) && !candidates.java().contains(choice)
                && !(declarationName && generic.contains(IDENTIFIER_TOKEN) && isName(choice))
                && !generic.contains(lexicalCategory(choice))) {
            return PREFIX_UNKNOWN_CHOICE;
        }
        return PrefixSyntax.isViable(proposed) ? EMPTY_STRING : PREFIX_INVALID_CONTINUATION;
    }

    /** Crea una voce del log delle decisioni. */
    private static Map<String, Object> event(String prefix, String choice, String status, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put(PREFIX_FIELD, prefix); event.put(CHOICE_FIELD, choice); event.put(STATUS_FIELD, status); event.put(REASON_FIELD, reason);
        return event;
    }
    /** Fornisce un esempio concreto per ogni categoria lessicale. */
    private static String representative(String candidate) {
        return switch (candidate) {
            case IDENTIFIER_TOKEN -> CANDIDATE_IDENTIFIER;
            case TOKEN_DECIMAL_LITERAL, TOKEN_OCT_LITERAL -> PROBE_DECIMAL;
            case TOKEN_HEX_LITERAL -> PROBE_HEX;
            case TOKEN_BINARY_LITERAL -> PROBE_BINARY;
            case TOKEN_FLOAT_LITERAL -> PROBE_FLOAT;
            case TOKEN_HEX_FLOAT_LITERAL -> PROBE_HEX_FLOAT;
            case TOKEN_CHAR_LITERAL -> PROBE_CHAR;
            case TOKEN_STRING_LITERAL -> PROBE_STRING;
            case TOKEN_TEXT_BLOCK -> PROBE_TEXT_BLOCK;
            default -> candidate;
        };
    }
    /** Verifica se il frammento è già un blocco Java analizzabile. */
    private static boolean complete(String snippet) {
        if (snippet.isBlank()) return false;
        try { StaticJavaParser.parseBlock(OPEN_BLOCK_SPACE + snippet + CLOSE_BLOCK_SPACE); return true; }
        catch (RuntimeException ignored) { return false; }
    }
    /** Controlla se il frammento ammette un nuovo nome di variabile. */
    private static boolean declaresName(String prefix) {
        try {
            var body = StaticJavaParser.parseBlock(OPEN_BLOCK_SPACE + prefix + DECLARATION_PROBE_SUFFIX);
            return body.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(DECLARATION_NAME_PROBE));
        } catch (RuntimeException ignored) { return false; }
    }
    /** Controlla se il valore è un identificatore Java. */
    private static boolean isName(String value) {
        try { return IDENTIFIER_TOKEN.equals(lexicalCategory(value)); }
        catch (RuntimeException ignored) { return false; }
    }
    /** Riconosce le parole chiave contestuali di Java. */
    private static boolean isContextualName(String name) {
        return Set.of(JAVA_MODULE, JAVA_OPEN, JAVA_REQUIRES, JAVA_EXPORTS, JAVA_OPENS, JAVA_TO, JAVA_USES, JAVA_PROVIDES, JAVA_WITH, JAVA_TRANSITIVE, JAVA_VAR, JAVA_YIELD, JAVA_RECORD, JAVA_SEALED, JAVA_PERMITS).contains(name);
    }
    /** Determina la categoria lessicale di un singolo token. */
    private static String lexicalCategory(String value) {
        var lexer = new star.tracto.lsp.antlr4.antlr4Class.JavaLexer(org.antlr.v4.runtime.CharStreams.fromString(value));
        lexer.removeErrorListeners();
        var first = lexer.nextToken();
        if (!first.getText().equals(value) || lexer.nextToken().getType() != org.antlr.v4.runtime.Token.EOF) return EMPTY_STRING;
        return lexer.getVocabulary().getSymbolicName(first.getType());
    }
    /** Riconosce assert e le asserzioni JUnit conosciute. */
    private static boolean isAssertion(String value) {
        return JAVA_ASSERT.equals(value) || ASSERT_CLASS.equals(value) || Arrays.stream(JUnitAssertionType.values()).anyMatch(a -> a.getAssertionMethodName().equals(value));
    }
    /** Controlla se una chiamata si risolve verso la classe sotto test. */
    private static boolean invokesFocal(MethodDeclaration method, String prefix, Clazz focal) {
        var old = method.getBody().orElseThrow();
        try {
            method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + prefix + CLOSE_BLOCK_LINE));
            return invokesFocal(method, focal);
        } finally { method.setBody(old); }
    }

    private static boolean invokesFocal(MethodDeclaration method, Clazz focal) {
        String fqn = focal.packageIdentifier().isEmpty() ? focal.identifier() : focal.packageIdentifier() + DOT + focal.identifier();
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
            if (call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) return false;
            try { return call.resolve().declaringType().getQualifiedName().equals(fqn); }
            catch (RuntimeException ignored) { return false; }
        });
    }
    /** Compila la classe con il prefix inserito e restituisce la diagnostica. */
    private static String compile(CompilationUnit unit, MethodDeclaration method, String prefix, Path output,
                                  String classpath, TokensUtilsTestPrefix.PrefixSession session) throws Exception {
        return compile(unit, method, prefix, output, classpath, session.sourceRoot(), session.testRoot());
    }

    /** Compila la classe con il prefix inserito e restituisce la diagnostica. */
    private static String compile(CompilationUnit unit, MethodDeclaration method, String prefix, Path output,
                                  String classpath, Path sourceRoot, Path testRoot) throws Exception {
        var old = method.getBody().orElseThrow();
        try {
            method.setBody(StaticJavaParser.parseBlock(OPEN_BLOCK_LINE + prefix + CLOSE_BLOCK_LINE));
            return compile(unit, output, classpath, sourceRoot, testRoot);
        } finally { method.setBody(old); }
    }

    private static String compile(CompilationUnit unit, Path output, String classpath,
                                  Path sourceRoot, Path testRoot) throws IOException {
        String name = unit.getPrimaryTypeName().orElse(unit.getType(0).getNameAsString());
        Path source = output.resolve(VALIDATION_SOURCE_DIRECTORY).resolve(name + JAVA_EXTENSION);
        Files.createDirectories(source.getParent());
        Files.writeString(source, unit.toString());
        Path classes = Files.createDirectories(output.resolve(VALIDATION_CLASSES_DIRECTORY));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(errors, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            String sep = java.io.File.pathSeparator;
            var options = List.of(JAVAC_NO_PROCESSING, JAVAC_NO_IMPLICIT, JAVAC_CLASSPATH, classpath,
                    JAVAC_SOURCEPATH, sourceRoot + sep + testRoot, JAVAC_OUTPUT, classes.toString());
            boolean ok = compiler.getTask(null, files, errors, options, null,
                    files.getJavaFileObjects(source.toFile())).call();
            return ok ? EMPTY_STRING : errors.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(Locale.ROOT)).collect(java.util.stream.Collectors.joining(DIAGNOSTIC_SEPARATOR));
        }
    }
}

