package logparsers.cdm;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CdmTestRecords {
    private final Schema topSchema;
    private final Map<String, Schema> schemasByName;
    private final Set<Schema> indexedSchemas;
    private final GenericData.Fixed hostId;
    private int uuidCounter;

    CdmTestRecords() throws IOException {
        this.topSchema = new Schema.Parser().parse(new File("Transparent_Computing_Engagement_5_Dataset/Schema/TCCDMDatum.avsc"));
        this.schemasByName = new HashMap<>();
        this.indexedSchemas = new HashSet<>();
        indexSchema(topSchema);
        this.hostId = uuid(99);
    }

    Schema topSchema() {
        return topSchema;
    }

    GenericData.Fixed uuid(int value) {
        byte[] bytes = new byte[16];
        bytes[12] = (byte) ((value >>> 24) & 0xff);
        bytes[13] = (byte) ((value >>> 16) & 0xff);
        bytes[14] = (byte) ((value >>> 8) & 0xff);
        bytes[15] = (byte) (value & 0xff);
        return new GenericData.Fixed(schema("UUID"), bytes);
    }

    GenericRecord subject(int uuidValue, int cid, String path, String cmdLine, GenericData.Fixed parentSubject) {
        GenericRecord record = new GenericData.Record(schema("Subject"));
        record.put("uuid", uuid(uuidValue));
        record.put("type", enumValue("SubjectType", "SUBJECT_PROCESS"));
        record.put("cid", cid);
        record.put("parentSubject", parentSubject);
        record.put("localPrincipal", null);
        record.put("startTimestampNanos", null);
        record.put("unitId", null);
        record.put("iteration", null);
        record.put("count", null);
        record.put("cmdLine", cmdLine);
        record.put("privilegeLevel", null);
        record.put("importedLibraries", null);
        record.put("exportedLibraries", null);
        record.put("properties", path == null ? null : Map.of("path", path));
        return record;
    }

    GenericRecord fileObject(int uuidValue, String filename) {
        GenericRecord record = new GenericData.Record(schema("FileObject"));
        record.put("uuid", uuid(uuidValue));
        record.put("baseObject", abstractObject(filename == null ? null : Map.of("filename", filename)));
        record.put("type", enumValue("FileObjectType", "FILE_OBJECT_FILE"));
        record.put("fileDescriptor", null);
        record.put("localPrincipal", null);
        record.put("size", null);
        record.put("peInfo", null);
        record.put("hashes", null);
        return record;
    }

    GenericRecord netFlowObject(int uuidValue, String localAddress, int localPort,
                                String remoteAddress, int remotePort) {
        GenericRecord record = new GenericData.Record(schema("NetFlowObject"));
        record.put("uuid", uuid(uuidValue));
        record.put("baseObject", abstractObject(null));
        record.put("localAddress", localAddress);
        record.put("localPort", localPort);
        record.put("remoteAddress", remoteAddress);
        record.put("remotePort", remotePort);
        record.put("ipProtocol", null);
        record.put("initTcpSeqNum", null);
        record.put("fileDescriptor", null);
        return record;
    }

    GenericRecord event(String eventType, int subjectUuid, int predicateUuid,
                        String predicatePath, long timestampNanos, long size) {
        GenericRecord record = new GenericData.Record(schema("Event"));
        record.put("uuid", uuid(++uuidCounter + 100));
        record.put("sequence", null);
        record.put("type", enumValue("EventType", eventType));
        record.put("threadId", null);
        record.put("subject", uuid(subjectUuid));
        record.put("predicateObject", uuid(predicateUuid));
        record.put("predicateObjectPath", predicatePath);
        record.put("predicateObject2", null);
        record.put("predicateObject2Path", null);
        record.put("timestampNanos", timestampNanos);
        record.put("names", null);
        record.put("parameters", null);
        record.put("location", null);
        record.put("size", size);
        record.put("programPoint", null);
        record.put("properties", null);
        return record;
    }

    GenericRecord top(String recordType, GenericRecord datum) {
        GenericRecord record = new GenericData.Record(topSchema);
        record.put("datum", datum);
        record.put("CDMVersion", "20");
        record.put("type", enumValue("RecordType", recordType));
        record.put("hostId", hostId);
        record.put("sessionNumber", 1);
        record.put("source", enumValue("InstrumentationSource", "SOURCE_LINUX_THEIA"));
        return record;
    }

    void write(Path output, List<GenericRecord> records) throws IOException {
        try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(new GenericDatumWriter<>(topSchema))) {
            writer.create(topSchema, output.toFile());
            for (GenericRecord record : records) {
                writer.append(record);
            }
        }
    }

    private GenericRecord abstractObject(Map<String, String> properties) {
        GenericRecord record = new GenericData.Record(schema("AbstractObject"));
        record.put("permission", null);
        record.put("epoch", null);
        record.put("properties", properties);
        return record;
    }

    private GenericData.EnumSymbol enumValue(String schemaName, String symbol) {
        return new GenericData.EnumSymbol(schema(schemaName), symbol);
    }

    private Schema schema(String name) {
        Schema schema = schemasByName.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("Schema not found: " + name);
        }
        return schema;
    }

    private void indexSchema(Schema schema) {
        if (schema == null) {
            return;
        }
        if (!indexedSchemas.add(schema)) {
            return;
        }
        switch (schema.getType()) {
            case RECORD:
            case ENUM:
            case FIXED:
                schemasByName.putIfAbsent(schema.getName(), schema);
                if (schema.getType() == Schema.Type.RECORD) {
                    for (Schema.Field field : schema.getFields()) {
                        indexSchema(field.schema());
                    }
                }
                break;
            case UNION:
                for (Schema child : schema.getTypes()) {
                    indexSchema(child);
                }
                break;
            case ARRAY:
                indexSchema(schema.getElementType());
                break;
            case MAP:
                indexSchema(schema.getValueType());
                break;
            default:
                break;
        }
    }
}
