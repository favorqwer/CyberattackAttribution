package logparsers.cdm;

import logparsers.cdm.CdmEventMapper.EntityKind;
import logparsers.cdm.CdmEventMapper.FlowKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdmEventMapperTest {
    @Test
    void mapsCoreEventFamiliesToInformationFlowDirections() {
        assertEquals(FlowKind.OBJECT_TO_PROCESS, CdmEventMapper.flowKind("EVENT_READ"));
        assertEquals(FlowKind.OBJECT_TO_PROCESS, CdmEventMapper.flowKind("EVENT_RECVFROM"));
        assertEquals(FlowKind.OBJECT_TO_PROCESS, CdmEventMapper.flowKind("EVENT_EXECUTE"));
        assertEquals(FlowKind.PROCESS_TO_OBJECT, CdmEventMapper.flowKind("EVENT_WRITE"));
        assertEquals(FlowKind.PROCESS_TO_OBJECT, CdmEventMapper.flowKind("EVENT_SENDTO"));
        assertEquals(FlowKind.PROCESS_TO_PROCESS, CdmEventMapper.flowKind("EVENT_FORK"));
        assertEquals(FlowKind.OBJECT_TO_OBJECT, CdmEventMapper.flowKind("EVENT_RENAME"));
        assertEquals(FlowKind.SKIP, CdmEventMapper.flowKind("EVENT_OPEN"));
    }

    @Test
    void choosesExistingEdgeTypesWhenEntityKindsFitProjectModel() {
        assertEquals("FtoP", CdmEventMapper.edgeType(EntityKind.FILE, EntityKind.PROCESS));
        assertEquals("PtoF", CdmEventMapper.edgeType(EntityKind.PROCESS, EntityKind.FILE));
        assertEquals("NtoP", CdmEventMapper.edgeType(EntityKind.NETWORK, EntityKind.PROCESS));
        assertEquals("PtoN", CdmEventMapper.edgeType(EntityKind.PROCESS, EntityKind.NETWORK));
        assertEquals("PtoP", CdmEventMapper.edgeType(EntityKind.PROCESS, EntityKind.PROCESS));
        assertEquals("ObjectFlow", CdmEventMapper.edgeType(EntityKind.FILE, EntityKind.NETWORK));
    }
}
