package logparsers.cdm;

import org.jgrapht.graph.DirectedPseudograph;
import org.junit.jupiter.api.Test;
import pagerank.algorithm.BackTrack;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdmGraphBuilderTest {
    @Test
    void buildsGraphFromManifestAndKeepsPoiReachableForBackTrack() throws IOException {
        Path tempDir = Files.createTempDirectory(Path.of("target"), "cdm-graph-test-");
        CdmTestRecords records = new CdmTestRecords();
        Path avro = tempDir.resolve("case.avro");
        String poiPath = "/home/admin/.vnc/ta1-theia-target-1:1.log";

        records.write(avro, List.of(
                records.top("RECORD_SUBJECT", records.subject(1, 31337, "/usr/bin/python3",
                        "/usr/bin/python3 attack.py", null)),
                records.top("RECORD_FILE_OBJECT", records.fileObject(2, null)),
                records.top("RECORD_EVENT", records.event("EVENT_WRITE", 1, 2, poiPath,
                        1_557_241_091_148_522_395L, 128L))
        ));

        Path manifest = tempDir.resolve("theia-e5.cdm");
        String manifestInputDir = tempDir.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(manifest, ""
                + "cdm.input_dir=" + manifestInputDir + "\n"
                + "cdm.file_glob=case.avro\n"
                + "cdm.start_nanos=1557241091148522395\n"
                + "cdm.end_nanos=1557241091148522395\n"
                + "cdm.max_events=0\n");

        DirectedPseudograph<EntityNode, EventEdge> graph = CdmGraphBuilder.build(manifest);

        EntityNode poi = graph.vertexSet().stream()
                .filter(node -> poiPath.equals(node.getSignature()))
                .findFirst()
                .orElse(null);
        assertNotNull(poi);

        EventEdge edge = graph.incomingEdgesOf(poi).iterator().next();
        assertEquals("PtoF", edge.getType());
        assertEquals("WRITE", edge.getEvent());
        assertEquals(128L, edge.getSize());
        assertEquals("1557241091.148522395", edge.getStartTime().toPlainString());
        assertTrue(edge.getSource().getSignature().startsWith("31337@00000001python3"));

        BackTrack backTrack = new BackTrack(graph);
        DirectedPseudograph<EntityNode, EventEdge> sliced = backTrack.backTrackPOIEvent(poiPath);
        assertEquals(2, sliced.vertexSet().size());
        assertEquals(1, sliced.edgeSet().size());
    }
}
