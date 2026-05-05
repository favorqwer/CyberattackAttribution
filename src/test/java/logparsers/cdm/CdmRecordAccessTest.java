package logparsers.cdm;

import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CdmRecordAccessTest {
    @Test
    void readsUuidNullableValuesAndProperties() throws IOException {
        CdmTestRecords records = new CdmTestRecords();
        GenericRecord subject = records.subject(1, 4242, "/usr/bin/python3",
                "/usr/bin/python3 /tmp/task.py", null);

        assertEquals("00000000-0000-0000-0000-000000000001",
                CdmRecordAccess.uuid(subject, "uuid"));
        assertEquals("00000001", CdmRecordAccess.uuid8(CdmRecordAccess.uuid(subject, "uuid")));
        assertNull(CdmRecordAccess.nullableUuid(subject, "parentSubject"));
        assertEquals(4242, CdmRecordAccess.intValue(subject, "cid", 0));
        assertEquals("/usr/bin/python3", CdmRecordAccess.property(subject, "path"));
        assertEquals("/usr/bin/python3 /tmp/task.py", CdmRecordAccess.nullableString(subject, "cmdLine"));
    }
}
