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

        Path manifest = writeManifest(tempDir, "case.avro",
                1_557_241_091_148_522_395L, 1_557_241_091_148_522_395L);

        DirectedPseudograph<EntityNode, EventEdge> fullGraph = CdmGraphBuilder.build(manifest);
        DirectedPseudograph<EntityNode, EventEdge> preslicedGraph = CdmGraphBuilder.build(manifest, List.of(poiPath));

        EntityNode poi = preslicedGraph.vertexSet().stream()
                .filter(node -> poiPath.equals(node.getSignature()))
                .findFirst()
                .orElse(null);
        assertNotNull(poi);

        EventEdge edge = preslicedGraph.incomingEdgesOf(poi).iterator().next();
        assertEquals("PtoF", edge.getType());
        assertEquals("WRITE", edge.getEvent());
        assertEquals(128L, edge.getSize());
        assertEquals("1557241091.148522395", edge.getStartTime().toPlainString());
        assertTrue(edge.getSource().getSignature().startsWith("31337@00000001python3"));

        assertBackTrackMatches(fullGraph, preslicedGraph, poiPath, 2, 1);
    }

    @Test
    void keepsMultiplePoiChainsInUnionPreslice() throws IOException {
        Path tempDir = Files.createTempDirectory(Path.of("target"), "cdm-graph-test-");
        CdmTestRecords records = new CdmTestRecords();
        Path avro = tempDir.resolve("case.avro");
        String poiA = "/tmp/poi-a.log";
        String poiB = "/tmp/poi-b.log";
        long timestamp = 1_557_241_091_148_522_395L;

        records.write(avro, List.of(
                records.top("RECORD_SUBJECT", records.subject(1, 31337, "/usr/bin/python3",
                        "/usr/bin/python3 attack.py", null)),
                records.top("RECORD_SUBJECT", records.subject(2, 4242, "/bin/bash",
                        "/bin/bash stage.sh", null)),
                records.top("RECORD_FILE_OBJECT", records.fileObject(3, null)),
                records.top("RECORD_FILE_OBJECT", records.fileObject(4, null)),
                records.top("RECORD_EVENT", records.event("EVENT_WRITE", 1, 3, poiA, timestamp, 32L)),
                records.top("RECORD_EVENT", records.event("EVENT_WRITE", 2, 4, poiB, timestamp + 1, 64L))
        ));

        Path manifest = writeManifest(tempDir, "case.avro", timestamp, timestamp + 1);
        DirectedPseudograph<EntityNode, EventEdge> fullGraph = CdmGraphBuilder.build(manifest);
        DirectedPseudograph<EntityNode, EventEdge> preslicedGraph = CdmGraphBuilder.build(manifest, List.of(poiA, poiB));

        assertEquals(4, preslicedGraph.vertexSet().size());
        assertEquals(2, preslicedGraph.edgeSet().size());
        assertBackTrackMatches(fullGraph, preslicedGraph, poiA, 2, 1);
        assertBackTrackMatches(fullGraph, preslicedGraph, poiB, 2, 1);
    }

    @Test
    void keepsExecuteParentChainDuringPreslice() throws IOException {
        Path tempDir = Files.createTempDirectory(Path.of("target"), "cdm-graph-test-");
        CdmTestRecords records = new CdmTestRecords();
        Path avro = tempDir.resolve("case.avro");
        long timestamp = 1_557_241_091_148_522_395L;
        String childProcessSignature = "200@00000002python3";

        records.write(avro, List.of(
                records.top("RECORD_SUBJECT", records.subject(1, 100, "/bin/bash",
                        "/bin/bash", null)),
                records.top("RECORD_SUBJECT", records.subject(2, 200, "/usr/bin/python3",
                        "/usr/bin/python3 attack.py", records.uuid(1))),
                records.top("RECORD_FILE_OBJECT", records.fileObject(3, "/tmp/payload.py")),
                records.top("RECORD_EVENT", records.event("EVENT_EXECUTE", 2, 3, null, timestamp, 0L))
        ));

        Path manifest = writeManifest(tempDir, "case.avro", timestamp, timestamp);
        DirectedPseudograph<EntityNode, EventEdge> fullGraph = CdmGraphBuilder.build(manifest);
        DirectedPseudograph<EntityNode, EventEdge> preslicedGraph =
                CdmGraphBuilder.build(manifest, List.of(childProcessSignature));

        assertEquals(3, preslicedGraph.vertexSet().size());
        assertEquals(2, preslicedGraph.edgeSet().size());
        assertTrue(preslicedGraph.vertexSet().stream()
                .anyMatch(node -> "100@00000001bash".equals(node.getSignature())));
        assertBackTrackMatches(fullGraph, preslicedGraph, childProcessSignature, 3, 2);
    }

    @Test
    void resolvesPredicateObjectPathFallbackWhenObjectRecordMissing() throws IOException {
        Path tempDir = Files.createTempDirectory(Path.of("target"), "cdm-graph-test-");
        CdmTestRecords records = new CdmTestRecords();
        Path avro = tempDir.resolve("case.avro");
        long timestamp = 1_557_241_091_148_522_395L;
        String poiPath = "/tmp/fallback-only.log";

        records.write(avro, List.of(
                records.top("RECORD_SUBJECT", records.subject(1, 31337, "/usr/bin/python3",
                        "/usr/bin/python3 attack.py", null)),
                records.top("RECORD_EVENT", records.event("EVENT_WRITE", 1, 99, poiPath, timestamp, 512L))
        ));

        Path manifest = writeManifest(tempDir, "case.avro", timestamp, timestamp);
        DirectedPseudograph<EntityNode, EventEdge> fullGraph = CdmGraphBuilder.build(manifest);
        DirectedPseudograph<EntityNode, EventEdge> preslicedGraph = CdmGraphBuilder.build(manifest, List.of(poiPath));

        assertTrue(preslicedGraph.vertexSet().stream().anyMatch(node -> poiPath.equals(node.getSignature())));
        assertBackTrackMatches(fullGraph, preslicedGraph, poiPath, 2, 1);
    }

    private static void assertBackTrackMatches(DirectedPseudograph<EntityNode, EventEdge> fullGraph,
                                               DirectedPseudograph<EntityNode, EventEdge> preslicedGraph,
                                               String poiSignature,
                                               int expectedVertices,
                                               int expectedEdges) {
        BackTrack fullBackTrack = new BackTrack(fullGraph);
        DirectedPseudograph<EntityNode, EventEdge> fullSlice = fullBackTrack.backTrackPOIEvent(poiSignature);

        BackTrack preslicedBackTrack = new BackTrack(preslicedGraph);
        DirectedPseudograph<EntityNode, EventEdge> preslicedSlice = preslicedBackTrack.backTrackPOIEvent(poiSignature);

        assertEquals(expectedVertices, preslicedSlice.vertexSet().size());
        assertEquals(expectedEdges, preslicedSlice.edgeSet().size());
        assertEquals(fullSlice.vertexSet().size(), preslicedSlice.vertexSet().size());
        assertEquals(fullSlice.edgeSet().size(), preslicedSlice.edgeSet().size());
    }

    private static Path writeManifest(Path tempDir, String fileGlob, long startNanos, long endNanos) throws IOException {
        Path manifest = tempDir.resolve("theia-e5.cdm");
        String manifestInputDir = tempDir.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(manifest, ""
                + "cdm.input_dir=" + manifestInputDir + "\n"
                + "cdm.file_glob=" + fileGlob + "\n"
                + "cdm.start_nanos=" + startNanos + "\n"
                + "cdm.end_nanos=" + endNanos + "\n"
                + "cdm.max_events=0\n");
        return manifest;
    }
}
