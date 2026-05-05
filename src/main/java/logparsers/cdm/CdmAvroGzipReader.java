package logparsers.cdm;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

public final class CdmAvroGzipReader implements AutoCloseable, Iterable<GenericRecord> {
    public static final String EXPECTED_SCHEMA_FULL_NAME = "com.bbn.tc.schema.avro.cdm20.TCCDMDatum";

    private static final int STREAM_BUFFER_BYTES = 8 * 1024 * 1024;

    private final Path path;
    private final InputStream rawInput;
    private final InputStream avroInput;
    private final DataFileStream<GenericRecord> dataFileStream;
    private boolean closed;

    private CdmAvroGzipReader(Path path, InputStream rawInput, InputStream avroInput,
                             DataFileStream<GenericRecord> dataFileStream) {
        this.path = path;
        this.rawInput = rawInput;
        this.avroInput = avroInput;
        this.dataFileStream = dataFileStream;
    }

    public static CdmAvroGzipReader open(Path path) throws IOException {
        InputStream rawInput = null;
        InputStream avroInput = null;
        DataFileStream<GenericRecord> dataFileStream = null;

        try {
            rawInput = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER_BYTES);
            avroInput = isGzip(path) ? new GZIPInputStream(rawInput, STREAM_BUFFER_BYTES) : rawInput;
            dataFileStream = new DataFileStream<>(avroInput, new GenericDatumReader<>());

            CdmAvroGzipReader reader = new CdmAvroGzipReader(path, rawInput, avroInput, dataFileStream);
            reader.validateSchema();
            return reader;
        } catch (IOException | RuntimeException e) {
            closeQuietly(dataFileStream);
            if (avroInput != rawInput) {
                closeQuietly(avroInput);
            }
            closeQuietly(rawInput);
            throw e;
        }
    }

    public Schema getSchema() {
        ensureOpen();
        return dataFileStream.getSchema();
    }

    @Override
    public Iterator<GenericRecord> iterator() {
        ensureOpen();
        Iterator<GenericRecord> delegate = dataFileStream.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                ensureOpen();
                return delegate.hasNext();
            }

            @Override
            public GenericRecord next() {
                ensureOpen();
                if (!delegate.hasNext()) {
                    throw new NoSuchElementException();
                }
                return delegate.next();
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;
        IOException closeFailure = null;

        try {
            dataFileStream.close();
        } catch (IOException e) {
            closeFailure = e;
        }

        if (avroInput != rawInput) {
            try {
                avroInput.close();
            } catch (IOException e) {
                if (closeFailure == null) {
                    closeFailure = e;
                } else {
                    closeFailure.addSuppressed(e);
                }
            }
        }

        try {
            rawInput.close();
        } catch (IOException e) {
            if (closeFailure == null) {
                closeFailure = e;
            } else {
                closeFailure.addSuppressed(e);
            }
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void validateSchema() throws IOException {
        String actualFullName = getSchema().getFullName();
        if (!EXPECTED_SCHEMA_FULL_NAME.equals(actualFullName)) {
            throw new IOException("Unsupported Avro schema in " + path + ": expected "
                    + EXPECTED_SCHEMA_FULL_NAME + " but found " + actualFullName);
        }
    }

    private static boolean isGzip(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".gz");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Reader is already closed: " + path);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
