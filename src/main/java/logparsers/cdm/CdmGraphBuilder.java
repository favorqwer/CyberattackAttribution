package logparsers.cdm;

import logparsers.cdm.CdmEventMapper.EntityKind;
import logparsers.cdm.CdmEventMapper.FlowKind;
import org.apache.avro.generic.GenericRecord;
import org.jgrapht.graph.DirectedPseudograph;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.entity.FileEntity;
import pagerank.entity.NetworkEntity;
import pagerank.entity.Process;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CdmGraphBuilder {
    private static final double DEFAULT_REPUTATION = 0.5;

    private final CdmGraphManifest manifest;
    private final DirectedPseudograph<EntityNode, EventEdge> graph;
    private final Map<String, NodeRef> uuidToNode;
    private final Map<String, NodeRef> signatureToNode;
    private final Map<String, SubjectInfo> subjects;
    private long nextNodeId;
    private long nextEdgeId;
    private long eventsInWindow;
    private long mappedEdges;

    public CdmGraphBuilder(CdmGraphManifest manifest) {
        this.manifest = manifest;
        this.graph = new DirectedPseudograph<>(EventEdge.class);
        this.uuidToNode = new HashMap<>();
        this.signatureToNode = new HashMap<>();
        this.subjects = new HashMap<>();
        this.nextNodeId = 1;
        this.nextEdgeId = 1;
    }

    public static DirectedPseudograph<EntityNode, EventEdge> build(Path manifestPath) throws IOException {
        return new CdmGraphBuilder(CdmGraphManifest.load(manifestPath)).build();
    }

    public DirectedPseudograph<EntityNode, EventEdge> build() throws IOException {
        List<Path> files = manifest.resolveInputFiles();
        firstPass(files);
        secondPass(files);
        printBuildSummary("two-pass");
        return graph;
    }

    private void firstPass(List<Path> files) throws IOException {
        for (Path file : files) {
            try (CdmAvroGzipReader reader = CdmAvroGzipReader.open(file)) {
                for (GenericRecord topLevel : reader) {
                    GenericRecord datum = CdmRecordAccess.datum(topLevel);
                    String recordType = CdmRecordAccess.recordType(topLevel);
                    cacheEntity(recordType, datum);
                }
            }
        }
    }

    private void secondPass(List<Path> files) throws IOException {
        for (Path file : files) {
            try (CdmAvroGzipReader reader = CdmAvroGzipReader.open(file)) {
                for (GenericRecord topLevel : reader) {
                    if (!"RECORD_EVENT".equals(CdmRecordAccess.recordType(topLevel))) {
                        continue;
                    }

                    GenericRecord event = CdmRecordAccess.datum(topLevel);
                    long timestampNanos = CdmRecordAccess.longValue(event, "timestampNanos", Long.MIN_VALUE);
                    if (timestampNanos < manifest.getStartNanos() || timestampNanos > manifest.getEndNanos()) {
                        continue;
                    }

                    if (manifest.getMaxEvents() > 0 && eventsInWindow >= manifest.getMaxEvents()) {
                        return;
                    }

                    eventsInWindow++;
                    mapEvent(event, timestampNanos);
                }
            }
        }
    }

    private void cacheEntity(String recordType, GenericRecord datum) {
        if (datum == null || recordType == null) {
            return;
        }

        switch (recordType) {
            case "RECORD_SUBJECT":
                cacheSubject(datum);
                break;
            case "RECORD_FILE_OBJECT":
                cacheFileObject(datum, null);
                break;
            case "RECORD_NET_FLOW_OBJECT":
                cacheNetFlowObject(datum);
                break;
            case "RECORD_IPC_OBJECT":
                cacheIpcObject(datum);
                break;
            case "RECORD_PACKET_SOCKET_OBJECT":
                cacheSocketLikeObject(datum, "socket");
                break;
            case "RECORD_REGISTRY_KEY_OBJECT":
                cacheGenericObject(datum, "registry://", CdmRecordAccess.string(datum, "key"));
                break;
            case "RECORD_MEMORY_OBJECT":
                cacheGenericObject(datum, "mem://", memoryLabel(datum));
                break;
            case "RECORD_SRC_SINK_OBJECT":
                cacheGenericObject(datum, "srcsink://", objectLabel(datum, "srcsink"));
                break;
            default:
                break;
        }
    }

    private void cacheSubject(GenericRecord subject) {
        String uuid = CdmRecordAccess.uuid(subject, "uuid");
        if (uuid == null) {
            return;
        }

        int cid = CdmRecordAccess.intValue(subject, "cid", 0);
        String parentUuid = CdmRecordAccess.nullableUuid(subject, "parentSubject");
        String path = CdmRecordAccess.property(subject, "path");
        String cmdLine = CdmRecordAccess.nullableString(subject, "cmdLine");
        String name = CdmRecordAccess.firstNonBlank(baseName(path), firstCommandToken(cmdLine),
                "subject-" + CdmRecordAccess.uuid8(uuid));
        String pid = cid + "@" + CdmRecordAccess.uuid8(uuid);

        NodeRef node = processNode(pid, name);
        uuidToNode.put(uuid, node);
        subjects.put(uuid, new SubjectInfo(node, parentUuid));
    }

    private void cacheFileObject(GenericRecord fileObject, String eventPathFallback) {
        String uuid = CdmRecordAccess.uuid(fileObject, "uuid");
        if (uuid == null) {
            return;
        }

        String filename = CdmRecordAccess.firstNonBlank(
                CdmRecordAccess.baseObjectProperty(fileObject, "filename"),
                CdmRecordAccess.baseObjectProperty(fileObject, "path"),
                eventPathFallback
        );
        NodeRef node = fileNode(filename == null ? "file://" + uuid : filename);
        uuidToNode.put(uuid, node);
    }

    private void cacheNetFlowObject(GenericRecord netFlowObject) {
        String uuid = CdmRecordAccess.uuid(netFlowObject, "uuid");
        if (uuid == null) {
            return;
        }

        String localAddress = CdmRecordAccess.firstNonBlank(
                CdmRecordAccess.nullableString(netFlowObject, "localAddress"), "0.0.0.0");
        String remoteAddress = CdmRecordAccess.firstNonBlank(
                CdmRecordAccess.nullableString(netFlowObject, "remoteAddress"), "0.0.0.0");
        String localPort = String.valueOf(CdmRecordAccess.intValue(netFlowObject, "localPort", 0));
        String remotePort = String.valueOf(CdmRecordAccess.intValue(netFlowObject, "remotePort", 0));

        NodeRef node = networkNode(localAddress, remoteAddress, localPort, remotePort);
        uuidToNode.put(uuid, node);
    }

    private void cacheGenericObject(GenericRecord objectRecord, String prefix, String label) {
        String uuid = CdmRecordAccess.uuid(objectRecord, "uuid");
        if (uuid == null) {
            return;
        }
        String suffix = CdmRecordAccess.firstNonBlank(label,
                CdmRecordAccess.baseObjectProperty(objectRecord, "name"),
                CdmRecordAccess.baseObjectProperty(objectRecord, "path"),
                uuid);
        uuidToNode.put(uuid, fileNode(prefix + suffix));
    }

    private void cacheIpcObject(GenericRecord objectRecord) {
        String objectType = CdmRecordAccess.string(objectRecord, "type");
        if (isSocketLikeObjectType(objectType)) {
            cacheSocketLikeObject(objectRecord, "ipc-socket");
            return;
        }
        cacheGenericObject(objectRecord, "ipc://", objectLabel(objectRecord, "ipc"));
    }

    private void cacheSocketLikeObject(GenericRecord objectRecord, String defaultName) {
        String uuid = CdmRecordAccess.uuid(objectRecord, "uuid");
        if (uuid == null) {
            return;
        }

        String path = CdmRecordAccess.firstNonBlank(
                CdmRecordAccess.baseObjectProperty(objectRecord, "path"),
                CdmRecordAccess.baseObjectProperty(objectRecord, "filename"),
                CdmRecordAccess.baseObjectProperty(objectRecord, "name"),
                defaultName + "-" + CdmRecordAccess.uuid8(uuid)
        );
        uuidToNode.put(uuid, networkNode("unix", path, "0", "0"));
    }

    private boolean isSocketLikeObjectType(String objectType) {
        if (objectType == null) {
            return false;
        }
        return objectType.toUpperCase(Locale.ROOT).contains("SOCKET");
    }

    private void mapEvent(GenericRecord event, long timestampNanos) {
        String eventType = CdmRecordAccess.string(event, "type");
        FlowKind flowKind = CdmEventMapper.flowKind(eventType);
        if (flowKind == FlowKind.SKIP) {
            return;
        }

        NodeRef subject = resolveSubject(CdmRecordAccess.nullableUuid(event, "subject"));
        NodeRef predicate = resolveObject(CdmRecordAccess.nullableUuid(event, "predicateObject"),
                CdmRecordAccess.nullableString(event, "predicateObjectPath"));
        NodeRef predicate2 = resolveObject(CdmRecordAccess.nullableUuid(event, "predicateObject2"),
                CdmRecordAccess.nullableString(event, "predicateObject2Path"));
        long size = CdmRecordAccess.longValue(event, "size", 0L);
        BigDecimal timestamp = nanosToSeconds(timestampNanos);

        switch (flowKind) {
            case OBJECT_TO_PROCESS:
                addEdge(predicate, subject, eventType, size, timestamp);
                if (CdmEventMapper.isExecute(eventType)) {
                    addParentEdge(subject, eventType, timestamp);
                }
                break;
            case PROCESS_TO_OBJECT:
                addEdge(subject, predicate, eventType, size, timestamp);
                break;
            case PROCESS_TO_PROCESS:
                addEdge(subject, predicate, eventType, size, timestamp);
                break;
            case OBJECT_TO_OBJECT:
                addEdge(predicate, predicate2, eventType, size, timestamp);
                break;
            case SKIP:
            default:
                break;
        }
    }

    private void addParentEdge(NodeRef child, String eventType, BigDecimal timestamp) {
        if (child == null) {
            return;
        }

        SubjectInfo childInfo = null;
        for (SubjectInfo subjectInfo : subjects.values()) {
            if (subjectInfo.node == child) {
                childInfo = subjectInfo;
                break;
            }
        }
        if (childInfo == null || childInfo.parentUuid == null) {
            return;
        }

        SubjectInfo parent = subjects.get(childInfo.parentUuid);
        if (parent != null) {
            addEdge(parent.node, child, eventType + "_PARENT", 0L, timestamp);
        }
    }

    private NodeRef resolveSubject(String uuid) {
        if (uuid == null) {
            return null;
        }
        return uuidToNode.get(uuid);
    }

    private NodeRef resolveObject(String uuid, String eventPathFallback) {
        if (uuid == null) {
            if (eventPathFallback == null || eventPathFallback.trim().isEmpty()) {
                return null;
            }
            return fileNode(eventPathFallback.trim());
        }

        NodeRef existing = uuidToNode.get(uuid);
        if (existing != null && !isSyntheticFileFallback(existing, uuid)) {
            return existing;
        }

        if (eventPathFallback != null && !eventPathFallback.trim().isEmpty()) {
            NodeRef pathNode = fileNode(eventPathFallback.trim());
            uuidToNode.put(uuid, pathNode);
            return pathNode;
        }

        if (existing != null) {
            return existing;
        }

        NodeRef fallback = fileNode("file://" + uuid);
        uuidToNode.put(uuid, fallback);
        return fallback;
    }

    private boolean isSyntheticFileFallback(NodeRef node, String uuid) {
        return node.kind == EntityKind.FILE && node.node.getSignature().equals("file://" + uuid);
    }

    private void addEdge(NodeRef source, NodeRef sink, String eventType, long size, BigDecimal timestamp) {
        if (source == null || sink == null) {
            return;
        }

        graph.addVertex(source.node);
        graph.addVertex(sink.node);
        String edgeType = CdmEventMapper.edgeType(source.kind, sink.kind);
        EventEdge edge = new EventEdge(edgeType, timestamp, timestamp, size, source.node, sink.node, nextEdgeId++);
        edge.setEdgeEvent(CdmEventMapper.normalizeEvent(eventType));
        graph.addEdge(source.node, sink.node, edge);
        mappedEdges++;
    }

    private void printBuildSummary(String mode) {
        System.out.println("CDM graph built (" + mode + ") from " + manifest.getManifestPath()
                + ": vertices=" + graph.vertexSet().size()
                + ", edges=" + graph.edgeSet().size()
                + ", eventsInWindow=" + eventsInWindow
                + ", mappedEdges=" + mappedEdges);
    }

    private NodeRef processNode(String pid, String name) {
        Process process = new Process(DEFAULT_REPUTATION, pid, name, nextNodeId);
        EntityNode candidate = new EntityNode(process);
        return canonicalNode(candidate, EntityKind.PROCESS);
    }

    private NodeRef fileNode(String path) {
        FileEntity file = new FileEntity(DEFAULT_REPUTATION, path, nextNodeId);
        EntityNode candidate = new EntityNode(file);
        return canonicalNode(candidate, EntityKind.FILE);
    }

    private NodeRef networkNode(String localAddress, String remoteAddress, String localPort, String remotePort) {
        NetworkEntity network = new NetworkEntity(DEFAULT_REPUTATION, localAddress, remoteAddress,
                localPort, remotePort, nextNodeId);
        EntityNode candidate = new EntityNode(network);
        return canonicalNode(candidate, EntityKind.NETWORK);
    }

    private NodeRef canonicalNode(EntityNode candidate, EntityKind kind) {
        NodeRef existing = signatureToNode.get(candidate.getSignature());
        if (existing != null) {
            return existing;
        }

        NodeRef created = new NodeRef(candidate, kind);
        signatureToNode.put(candidate.getSignature(), created);
        nextNodeId++;
        return created;
    }

    private static BigDecimal nanosToSeconds(long nanos) {
        return BigDecimal.valueOf(nanos).movePointLeft(9);
    }

    private static String objectLabel(GenericRecord objectRecord, String defaultName) {
        return CdmRecordAccess.firstNonBlank(
                CdmRecordAccess.baseObjectProperty(objectRecord, "name"),
                CdmRecordAccess.baseObjectProperty(objectRecord, "filename"),
                defaultName + "-" + CdmRecordAccess.uuid8(CdmRecordAccess.uuid(objectRecord, "uuid"))
        );
    }

    private static String memoryLabel(GenericRecord memoryObject) {
        Long address = CdmRecordAccess.nullableLong(memoryObject, "memoryAddress");
        if (address == null) {
            return null;
        }
        return "0x" + Long.toHexString(address);
    }

    private static String baseName(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash + 1 < normalized.length() ? normalized.substring(slash + 1) : normalized;
    }

    private static String firstCommandToken(String cmdLine) {
        if (cmdLine == null || cmdLine.trim().isEmpty()) {
            return null;
        }
        String trimmed = cmdLine.trim();
        if (trimmed.startsWith("\"")) {
            int closingQuote = trimmed.indexOf('"', 1);
            if (closingQuote > 1) {
                return baseName(trimmed.substring(1, closingQuote));
            }
        }
        int firstSpace = trimmed.indexOf(' ');
        String token = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;
        return baseName(token);
    }

    private static final class NodeRef {
        private final EntityNode node;
        private final EntityKind kind;

        private NodeRef(EntityNode node, EntityKind kind) {
            this.node = node;
            this.kind = kind;
        }
    }

    private static final class SubjectInfo {
        private final NodeRef node;
        private final String parentUuid;

        private SubjectInfo(NodeRef node, String parentUuid) {
            this.node = node;
            this.parentUuid = parentUuid;
        }
    }
}
