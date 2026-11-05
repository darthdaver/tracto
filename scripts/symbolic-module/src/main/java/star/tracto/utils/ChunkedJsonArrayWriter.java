package star.tracto.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntFunction;

/**
 * Writes a JSON array split into chunks of files, one element at a time (Step 23), so that the elements need not be
 * kept in memory until the end.
 *
 * <p>The chunks are those of the former split of the whole list: an element goes into the current chunk unless the
 * chunk is not empty and the element would take it over the maximum size (the sum of the sizes of the serialized
 * elements, in UTF-8 bytes; an element larger than the maximum gets a chunk of its own). Each chunk is byte for byte
 * the one the former writer wrote from its list ({@code objectMapper.writeValue} on a generator over a UTF-8
 * {@code BufferedWriter}): the elements, serialized by the same mapper to text, joined by commas in brackets.</p>
 *
 * <p>The file of a chunk is created when its first element arrives: no element, no file.</p>
 */
public final class ChunkedJsonArrayWriter implements Closeable {

    private final ObjectMapper objectMapper;
    private final long maxChunkBytes;
    private final IntFunction<Path> chunkFile;
    private BufferedWriter chunk;
    private long chunkBytes;
    private int chunks;
    private int written;

    /**
     * @param objectMapper the mapper that serializes the elements
     * @param maxChunkBytes the maximum size of a chunk (sum of the sizes of its elements, in UTF-8 bytes)
     * @param chunkFile the file of the chunk with the given index (0, 1, ...)
     */
    public ChunkedJsonArrayWriter(ObjectMapper objectMapper, long maxChunkBytes, IntFunction<Path> chunkFile) {
        this.objectMapper = objectMapper;
        this.maxChunkBytes = maxChunkBytes;
        this.chunkFile = chunkFile;
    }

    /**
     * Writes an element, in the current chunk or in a new one.
     *
     * @param element the element
     * @throws IOException if the element cannot be written
     */
    public void write(Object element) throws IOException {
        String json = objectMapper.writeValueAsString(element);
        long bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (chunk != null && chunkBytes + bytes > maxChunkBytes) {
            closeChunk();
        }
        if (chunk == null) {
            Path file = chunkFile.apply(chunks++);
            FilesUtils.createFile(file);
            chunk = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            chunk.write('[');
        } else {
            chunk.write(',');
        }
        chunk.write(json);
        chunkBytes += bytes;
        written++;
    }

    /** @return the number of elements written */
    public int written() {
        return written;
    }

    /** Closes the last chunk, if any. */
    @Override
    public void close() throws IOException {
        if (chunk != null) {
            closeChunk();
        }
    }

    private void closeChunk() throws IOException {
        try {
            chunk.write(']');
        } finally {
            chunk.close();
            chunk = null;
            chunkBytes = 0;
        }
    }
}
