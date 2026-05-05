package logparsers.cdm;

import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CdmRecordAccess {
    private CdmRecordAccess() {
    }

    public static GenericRecord datum(GenericRecord topLevelRecord) {
        Object datum = topLevelRecord.get("datum");
        return datum instanceof GenericRecord ? (GenericRecord) datum : null;
    }

    public static String recordType(GenericRecord topLevelRecord) {
        Object type = topLevelRecord.get("type");
        return asString(type);
    }

    public static String datumName(GenericRecord datum) {
        if (datum == null || datum.getSchema() == null) {
            return "";
        }
        return datum.getSchema().getName();
    }

    public static String uuid(GenericRecord record, String fieldName) {
        return uuidString(record == null ? null : record.get(fieldName));
    }

    public static String nullableUuid(GenericRecord record, String fieldName) {
        return uuid(record, fieldName);
    }

    public static String uuidString(Object value) {
        if (value == null) {
            return null;
        }

        byte[] bytes;
        if (value instanceof GenericFixed) {
            bytes = ((GenericFixed) value).bytes();
        } else if (value instanceof ByteBuffer) {
            ByteBuffer duplicate = ((ByteBuffer) value).duplicate();
            bytes = new byte[duplicate.remaining()];
            duplicate.get(bytes);
        } else if (value instanceof byte[]) {
            bytes = (byte[]) value;
        } else {
            return asString(value);
        }

        if (bytes.length != 16) {
            return toHex(bytes);
        }

        return String.format(Locale.ROOT,
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                unsigned(bytes[0]), unsigned(bytes[1]), unsigned(bytes[2]), unsigned(bytes[3]),
                unsigned(bytes[4]), unsigned(bytes[5]),
                unsigned(bytes[6]), unsigned(bytes[7]),
                unsigned(bytes[8]), unsigned(bytes[9]),
                unsigned(bytes[10]), unsigned(bytes[11]), unsigned(bytes[12]), unsigned(bytes[13]),
                unsigned(bytes[14]), unsigned(bytes[15]));
    }

    public static String uuid8(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return "unknown";
        }
        String compact = uuid.replace("-", "");
        return compact.length() <= 8 ? compact : compact.substring(compact.length() - 8);
    }

    public static String string(GenericRecord record, String fieldName) {
        return asString(record == null ? null : record.get(fieldName));
    }

    public static String nullableString(GenericRecord record, String fieldName) {
        return string(record, fieldName);
    }

    public static Long nullableLong(GenericRecord record, String fieldName) {
        Object value = record == null ? null : record.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(asString(value));
    }

    public static long longValue(GenericRecord record, String fieldName, long defaultValue) {
        Long value = nullableLong(record, fieldName);
        return value == null ? defaultValue : value;
    }

    public static Integer nullableInt(GenericRecord record, String fieldName) {
        Object value = record == null ? null : record.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(asString(value));
    }

    public static int intValue(GenericRecord record, String fieldName, int defaultValue) {
        Integer value = nullableInt(record, fieldName);
        return value == null ? defaultValue : value;
    }

    public static GenericRecord record(GenericRecord record, String fieldName) {
        Object value = record == null ? null : record.get(fieldName);
        return value instanceof GenericRecord ? (GenericRecord) value : null;
    }

    public static Map<String, String> properties(GenericRecord record) {
        Object value = record == null ? null : record.get("properties");
        if (!(value instanceof Map<?, ?>)) {
            return Collections.emptyMap();
        }

        Map<String, String> properties = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                properties.put(asString(entry.getKey()), asString(entry.getValue()));
            }
        }
        return properties;
    }

    public static String property(GenericRecord record, String key) {
        return properties(record).get(key);
    }

    public static String baseObjectProperty(GenericRecord objectRecord, String key) {
        GenericRecord baseObject = record(objectRecord, "baseObject");
        return property(baseObject, key);
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Utf8) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", unsigned(b)));
        }
        return builder.toString();
    }
}
