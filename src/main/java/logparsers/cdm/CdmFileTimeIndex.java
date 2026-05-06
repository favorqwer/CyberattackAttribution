package logparsers.cdm;

import org.apache.avro.generic.GenericRecord;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CdmFileTimeIndex {
    private static final String INDEX_FILE_NAME = ".cdm-time-index.json";

    private final Path indexPath;
    private final Map<String, Entry> entriesByFileName;

    private CdmFileTimeIndex(Path indexPath, Map<String, Entry> entriesByFileName) {
        this.indexPath = indexPath;
        this.entriesByFileName = entriesByFileName;
    }

    static CdmFileTimeIndex loadOrBuild(Path inputDir, List<Path> files) throws IOException {
        Path indexPath = inputDir.resolve(INDEX_FILE_NAME);
        Map<String, Entry> entriesByFileName = loadEntries(indexPath);
        int reusedEntries = 0;
        int rebuiltEntries = 0;

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            long sizeBytes = Files.size(file);
            long lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
            Entry existing = entriesByFileName.get(fileName);

            if (existing != null && existing.matches(sizeBytes, lastModifiedMillis)) {
                reusedEntries++;
                continue;
            }

            entriesByFileName.put(fileName, buildEntry(file, sizeBytes, lastModifiedMillis));
            rebuiltEntries++;
        }

        if (rebuiltEntries > 0) {
            saveEntries(indexPath, entriesByFileName);
        }

        System.out.println("CDM time index ready: path=" + indexPath.toAbsolutePath()
                + ", reusedEntries=" + reusedEntries
                + ", rebuiltEntries=" + rebuiltEntries);
        return new CdmFileTimeIndex(indexPath, entriesByFileName);
    }

    List<Path> selectFilesOverlappingWindow(List<Path> files, long startNanos, long endNanos) {
        List<Path> selected = new ArrayList<>();
        for (Path file : files) {
            Entry entry = entriesByFileName.get(file.getFileName().toString());
            if (entry == null) {
                selected.add(file);
                continue;
            }
            if (entry.hasEventRange() && entry.overlaps(startNanos, endNanos)) {
                selected.add(file);
            }
        }
        return selected;
    }

    Path getIndexPath() {
        return indexPath;
    }

    private static Map<String, Entry> loadEntries(Path indexPath) throws IOException {
        Map<String, Entry> entriesByFileName = new HashMap<>();
        if (!Files.exists(indexPath) || !Files.isRegularFile(indexPath)) {
            return entriesByFileName;
        }

        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            Object parsed = new JSONParser().parse(reader);
            if (!(parsed instanceof JSONObject)) {
                return entriesByFileName;
            }

            Object entriesValue = ((JSONObject) parsed).get("entries");
            if (!(entriesValue instanceof JSONArray)) {
                return entriesByFileName;
            }

            for (Object item : (JSONArray) entriesValue) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                Entry entry = Entry.fromJson((JSONObject) item);
                if (entry != null) {
                    entriesByFileName.put(entry.fileName, entry);
                }
            }
            return entriesByFileName;
        } catch (ParseException e) {
            throw new IOException("Failed to parse CDM time index: " + indexPath, e);
        }
    }

    private static void saveEntries(Path indexPath, Map<String, Entry> entriesByFileName) throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", 1L);
        JSONArray entries = new JSONArray();
        for (Entry entry : entriesByFileName.values()) {
            entries.add(entry.toJson());
        }
        root.put("entries", entries);

        Path tempPath = indexPath.resolveSibling(indexPath.getFileName().toString() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
            writer.write(root.toJSONString());
        }
        Files.move(tempPath, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Entry buildEntry(Path file, long sizeBytes, long lastModifiedMillis) throws IOException {
        long minTimestampNanos = Long.MAX_VALUE;
        long maxTimestampNanos = Long.MIN_VALUE;
        long eventCount = 0L;

        try (CdmAvroGzipReader reader = CdmAvroGzipReader.open(file)) {
            for (GenericRecord topLevel : reader) {
                if (!"RECORD_EVENT".equals(CdmRecordAccess.recordType(topLevel))) {
                    continue;
                }

                GenericRecord event = CdmRecordAccess.datum(topLevel);
                long timestampNanos = CdmRecordAccess.longValue(event, "timestampNanos", Long.MIN_VALUE);
                if (timestampNanos == Long.MIN_VALUE) {
                    continue;
                }
                minTimestampNanos = Math.min(minTimestampNanos, timestampNanos);
                maxTimestampNanos = Math.max(maxTimestampNanos, timestampNanos);
                eventCount++;
            }
        }

        if (eventCount == 0L) {
            minTimestampNanos = Long.MIN_VALUE;
            maxTimestampNanos = Long.MIN_VALUE;
        }

        return new Entry(file.getFileName().toString(), sizeBytes, lastModifiedMillis,
                minTimestampNanos, maxTimestampNanos, eventCount);
    }

    private static long longValue(JSONObject json, String key, long defaultValue) {
        Object value = json.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String stringValue(JSONObject json, String key) {
        Object value = json.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static final class Entry {
        private final String fileName;
        private final long sizeBytes;
        private final long lastModifiedMillis;
        private final long minTimestampNanos;
        private final long maxTimestampNanos;
        private final long eventCount;

        private Entry(String fileName, long sizeBytes, long lastModifiedMillis,
                      long minTimestampNanos, long maxTimestampNanos, long eventCount) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.lastModifiedMillis = lastModifiedMillis;
            this.minTimestampNanos = minTimestampNanos;
            this.maxTimestampNanos = maxTimestampNanos;
            this.eventCount = eventCount;
        }

        private boolean matches(long expectedSizeBytes, long expectedLastModifiedMillis) {
            return sizeBytes == expectedSizeBytes && lastModifiedMillis == expectedLastModifiedMillis;
        }

        private boolean hasEventRange() {
            return eventCount > 0 && minTimestampNanos != Long.MIN_VALUE && maxTimestampNanos != Long.MIN_VALUE;
        }

        private boolean overlaps(long startNanos, long endNanos) {
            return hasEventRange() && maxTimestampNanos >= startNanos && minTimestampNanos <= endNanos;
        }

        @SuppressWarnings("unchecked")
        private JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("fileName", fileName);
            json.put("sizeBytes", sizeBytes);
            json.put("lastModifiedMillis", lastModifiedMillis);
            json.put("minTimestampNanos", minTimestampNanos);
            json.put("maxTimestampNanos", maxTimestampNanos);
            json.put("eventCount", eventCount);
            return json;
        }

        private static Entry fromJson(JSONObject json) {
            String fileName = stringValue(json, "fileName");
            if (fileName == null || fileName.trim().isEmpty()) {
                return null;
            }

            return new Entry(
                    fileName.trim(),
                    longValue(json, "sizeBytes", -1L),
                    longValue(json, "lastModifiedMillis", -1L),
                    longValue(json, "minTimestampNanos", Long.MIN_VALUE),
                    longValue(json, "maxTimestampNanos", Long.MIN_VALUE),
                    longValue(json, "eventCount", 0L)
            );
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%s[size=%d, mtime=%d, min=%d, max=%d, events=%d]",
                    fileName, sizeBytes, lastModifiedMillis, minTimestampNanos, maxTimestampNanos, eventCount);
        }
    }
}
