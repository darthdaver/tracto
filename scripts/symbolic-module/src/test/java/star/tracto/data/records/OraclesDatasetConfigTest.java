package star.tracto.data.records;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import star.tracto.data.enums.TryCatchFinallyStrategyType;
import star.tracto.utils.FilesUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the loading of the configuration of the oracles dataset ({@link OraclesDatasetConfig}).
 */
class OraclesDatasetConfigTest {

    private static final Path COMMITTED_CONFIG = Path.of("src/main/resources/dataset/oracles-dataset_config.json");

    @TempDir
    Path tempDir;

    @Test
    void committedConfigurationConvertsTheTryStatementsIntoAssertThrows() {
        OraclesDatasetConfig config = FilesUtils.readJSON(COMMITTED_CONFIG, OraclesDatasetConfig.class);

        assertEquals(TryCatchFinallyStrategyType.TO_ASSERT_THROWS, config.tryCatchFinallyStrategy());
    }

    @Test
    void keepTryCatchIsAccepted() throws IOException {
        Path configFile = writeConfig("\"try-catch-finally\": \"keep-try-catch\"");

        OraclesDatasetConfig config = FilesUtils.readJSON(configFile, OraclesDatasetConfig.class);

        assertEquals(TryCatchFinallyStrategyType.KEEP_TRY_CATCH, config.tryCatchFinallyStrategy());
    }

    @Test
    void missingTryCatchFinallyKeyIsRejected() throws IOException {
        Path configFile = writeConfig("\"split\": \"assertion\"");

        Error error = assertThrows(Error.class, () -> FilesUtils.readJSON(configFile, OraclesDatasetConfig.class));

        assertTrue(rootCauseMessage(error).contains("try-catch-finally"), rootCauseMessage(error));
    }

    @Test
    void valueOfTheRemovedStrategiesIsRejected() throws IOException {
        Path configFile = writeConfig("\"try-catch-finally\": \"standard\"");

        Error error = assertThrows(Error.class, () -> FilesUtils.readJSON(configFile, OraclesDatasetConfig.class));

        assertTrue(rootCauseMessage(error).contains("Unknown value: standard"), rootCauseMessage(error));
    }

    private Path writeConfig(String keys) throws IOException {
        return Files.writeString(tempDir.resolve("config.json"), "{" + keys + "}");
    }

    private static String rootCauseMessage(Throwable throwable) {
        while (throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable.getMessage();
    }
}
