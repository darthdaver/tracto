package star.tracto.utils;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes a JSON array to a file one element at a time (Step 23), so that the elements need not be kept in memory until
 * the end. The file is byte for byte the one written by {@code objectMapper.writeValue(file, list)} with the same
 * elements: the same generator (created by the mapper, UTF-8, to a file) and the same serializers (each element by
 * its runtime type, as the serializer of a list does).
 */
public final class JsonArrayWriter implements Closeable {

    private final ObjectMapper objectMapper;
    private final JsonGenerator generator;
    private int written;

    /**
     * Creates the file (and its directories) and opens the array.
     *
     * @param objectMapper the mapper that serializes the elements
     * @param file the file to write
     * @throws IOException if the file cannot be written
     */
    public JsonArrayWriter(ObjectMapper objectMapper, Path file) throws IOException {
        this.objectMapper = objectMapper;
        FilesUtils.createFile(file);
        this.generator = objectMapper.createGenerator(file.toFile(), JsonEncoding.UTF8);
        this.generator.writeStartArray();
    }

    /**
     * Writes an element of the array.
     *
     * @param element the element
     * @throws IOException if the element cannot be written
     */
    public void write(Object element) throws IOException {
        objectMapper.writeValue(generator, element);
        written++;
    }

    /** @return the number of elements written */
    public int written() {
        return written;
    }

    /** Closes the array and the file. */
    @Override
    public void close() throws IOException {
        try {
            generator.writeEndArray();
        } finally {
            generator.close();
        }
    }
}
