package star.tracto.utils;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The datasets are written one test class at a time (Step 23) instead of being kept in memory until the end: the files
 * must be byte for byte those written before from the whole list ({@code ObjectMapper.writeValue}), and the oracles
 * dataset must be split into the same chunks.
 */
class JsonArrayWritersTest {

    record Item(String name, List<Integer> values, Optional<String> note) {}

    /** Names with the characters the generators escape or encode: quotes, backslashes, control and non-ASCII ones. */
    private static final List<Item> ITEMS = List.of(
            new Item("plain", List.of(1, 2, 3), Optional.empty()),
            new Item("quote \" backslash \\ tab \t newline \n", List.of(), Optional.of("note")),
            new Item("non-ASCII: è ü ß 漢字 😀, line separator  , control \u0001", List.of(-1), Optional.empty()),
            new Item("x".repeat(100), List.of(42), Optional.of("y".repeat(50)))
    );

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());

    // ---- JsonArrayWriter (the tokens dataset: one output file per input file) ----

    @Test
    void anArrayWrittenOneElementAtATimeIsTheArrayWrittenAtOnce() throws IOException {
        Path expected = tempDir.resolve("expected.json");
        Path actual = tempDir.resolve("actual.json");
        mapper.writeValue(expected.toFile(), new ArrayList<>(ITEMS));
        try (JsonArrayWriter writer = new JsonArrayWriter(mapper, actual)) {
            for (Item item : ITEMS) {
                writer.write(item);
            }
            assertEquals(ITEMS.size(), writer.written());
        }
        assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual));
    }

    @Test
    void anEmptyArrayIsWrittenToo() throws IOException {
        Path expected = tempDir.resolve("expected.json");
        Path actual = tempDir.resolve("actual.json");
        mapper.writeValue(expected.toFile(), new ArrayList<>());
        new JsonArrayWriter(mapper, actual).close();
        assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual));
    }

    // ---- ChunkedJsonArrayWriter (the oracles dataset: chunks of at most 50 MB) ----

    @Test
    void elementsThatFitInOneChunkAreWrittenAsTheWholeArray() throws IOException {
        List<Path> chunks = writeChunked(ITEMS, Long.MAX_VALUE);
        assertEquals(1, chunks.size());
        assertArrayEquals(writtenAtOnce(ITEMS), Files.readAllBytes(chunks.get(0)));
    }

    @Test
    void theChunksAreTheChunksOfTheWholeList() throws IOException {
        // Every size from a chunk per element to a single chunk, through the sizes at which a chunk is exactly full
        long total = 0;
        for (Item item : ITEMS) {
            total += size(item);
        }
        for (long max = 1; max <= total + 1; max++) {
            List<List<Item>> expected = chunksOfTheWholeList(ITEMS, max);
            List<Path> chunks = writeChunked(ITEMS, max);
            assertEquals(expected.size(), chunks.size(), "max " + max);
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(writtenAtOnce(expected.get(i)), Files.readAllBytes(chunks.get(i)), "max " + max + ", chunk " + i);
            }
        }
    }

    @Test
    void aChunkIsClosedOnlyWhenTheNextElementDoesNotFit() throws IOException {
        // Sizes s0..s3; a maximum of s0 + s1 puts the first two together and each of the others alone
        long max = size(ITEMS.get(0)) + size(ITEMS.get(1));
        List<Path> chunks = writeChunked(ITEMS, max);
        assertEquals(3, chunks.size());
        assertArrayEquals(writtenAtOnce(ITEMS.subList(0, 2)), Files.readAllBytes(chunks.get(0)));
        assertArrayEquals(writtenAtOnce(ITEMS.subList(2, 3)), Files.readAllBytes(chunks.get(1)));
        assertArrayEquals(writtenAtOnce(ITEMS.subList(3, 4)), Files.readAllBytes(chunks.get(2)));
    }

    @Test
    void noElementWritesNoChunk() throws IOException {
        assertEquals(List.of(), writeChunked(List.of(), 10));
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    // ---- FilesUtils.readJSONArray (the oracles dataset read one test class at a time) ----

    @Test
    void anArrayReadOneElementAtATimeHasTheElementsOfTheList() throws IOException {
        Path file = tempDir.resolve("items.json");
        mapper.writeValue(file.toFile(), new ArrayList<>(ITEMS));
        JavaType itemType = mapper.getTypeFactory().constructType(Item.class);
        List<Item> read = new ArrayList<>();
        try (MappingIterator<Item> items = FilesUtils.readJSONArray(file, itemType)) {
            items.forEachRemaining(read::add);
        }
        assertEquals(FilesUtils.readJSONList(file, mapper.getTypeFactory().constructCollectionType(List.class, Item.class)), read);
        assertEquals(ITEMS, read);
    }

    // ---- helpers ----

    private List<Path> writeChunked(List<Item> items, long maxChunkBytes) throws IOException {
        List<Path> chunks = new ArrayList<>();
        try (ChunkedJsonArrayWriter writer = new ChunkedJsonArrayWriter(mapper, maxChunkBytes, i -> {
            Path chunk = tempDir.resolve("chunks").resolve("chunk-" + i + ".json");
            chunks.add(chunk);
            return chunk;
        })) {
            for (Item item : items) {
                writer.write(item);
            }
            assertEquals(items.size(), writer.written());
        }
        return chunks;
    }

    private long size(Item item) throws IOException {
        return mapper.writeValueAsString(item).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The bytes of the former writer of a chunk ({@code FilesUtils.writeChunkToFile}): the whole list at once. */
    private byte[] writtenAtOnce(List<Item> items) throws IOException {
        Path file = Files.createTempFile(tempDir, "at-once", ".json");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             var generator = mapper.getFactory().createGenerator(writer)) {
            mapper.writeValue(generator, new ArrayList<>(items));
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    /** The former split of the whole list into chunks (OraclesDataset before Step 23). */
    private List<List<Item>> chunksOfTheWholeList(List<Item> items, long maxChunkBytes) throws IOException {
        List<List<Item>> chunks = new ArrayList<>();
        List<Item> currentChunk = new ArrayList<>();
        long currentChunkSize = 0;
        for (Item item : items) {
            long itemSize = size(item);
            if (currentChunkSize + itemSize > maxChunkBytes && !currentChunk.isEmpty()) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentChunkSize = 0;
            }
            currentChunk.add(item);
            currentChunkSize += itemSize;
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }
}
