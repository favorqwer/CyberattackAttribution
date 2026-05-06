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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public final class CdmGraphBuilder {
    private static final double DEFAULT_REPUTATION = 0.5;
    private static final long PROGRESS_LOG_INTERVAL_MS = 10_000L;
    private static final long PROGRESS_RECORD_GRANULARITY = 250_000L;

    private final CdmGraphManifest manifest;
    private final boolean preSliceEnabled;
    private final Set<String> poiSignatures;
    private final DirectedPseudograph<EntityNode, EventEdge> graph;
    private final Map<String, RawNodeInfo> rawNodesByUuid;
    private final Map<String, SubjectInfo> subjects;
    private final Map<String, Set<Ref>> signatureToRefs;
    private final Map<String, String> uuidToFallbackSignature;
    private final Set<String> referencedUuids;
    private final Set<String> executeChildSubjectUuids;
    private final List<CompactEvent> compactEvents;
    private final Map<Ref, List<CompactEvent>> incomingIndex;
    private final Map<Ref, NodeRef> materializedRefs;
    private final Map<String, NodeRef> signatureToNode;
    private long nextNodeId;
    private long nextEdgeId;
    private long nextCompactEventId;
    private long eventsInWindow;
    private long mappedEdges;
    private long compactEventCount;
    private long keptEventCount;
    private long keptRefCount;
    private long presliceTimeMs;
    private long materializeTimeMs;

    private CdmGraphBuilder(CdmGraphManifest manifest, Collection<String> poiSignatures, boolean preSliceEnabled) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.preSliceEnabled = preSliceEnabled;
        this.poiSignatures = normalizePoiSignatures(poiSignatures);
        this.graph = new DirectedPseudograph<>(EventEdge.class);
        this.rawNodesByUuid = new HashMap<>();
        this.subjects = new HashMap<>();
        this.signatureToRefs = new HashMap<>();
        this.uuidToFallbackSignature = new HashMap<>();
        this.referencedUuids = new LinkedHashSet<>();
        this.executeChildSubjectUuids = new LinkedHashSet<>();
        this.compactEvents = new ArrayList<>();
        this.incomingIndex = new HashMap<>();
        this.materializedRefs = new HashMap<>();
        this.signatureToNode = new HashMap<>();
        this.nextNodeId = 1;
        this.nextEdgeId = 1;
        this.nextCompactEventId = 1;
    }

    public static DirectedPseudograph<EntityNode, EventEdge> build(Path manifestPath) throws IOException {
        return new CdmGraphBuilder(CdmGraphManifest.load(manifestPath), Collections.emptySet(), false).build();
    }

    public static DirectedPseudograph<EntityNode, EventEdge> build(Path manifestPath,
                                                                   Collection<String> poiSignatures) throws IOException {
        return new CdmGraphBuilder(CdmGraphManifest.load(manifestPath), poiSignatures, true).build();
    }

    public DirectedPseudograph<EntityNode, EventEdge> build() throws IOException {
        if (preSliceEnabled && poiSignatures.isEmpty()) {
            throw new IOException("CDM pre-slice requires at least one non-empty POI signature");
        }

        List<Path> files = manifest.resolveInputFiles();
        ScanProgress progress = new ScanProgress(files);
        progress.logScanStart();
        scanEvents(files, progress);
        resolveReferencedEntities(files);
        addExecuteParentEvents();
        indexCompactEvents();
        compactEventCount = compactEvents.size();

        PreSliceSelection selection;
        String mode;
        if (preSliceEnabled) {
            long started = System.currentTimeMillis();
            System.out.println("CDM pre-slice started: poiCount=" + poiSignatures.size()
                    + ", compactEventCount=" + compactEvents.size());
            selection = preSlice();
            presliceTimeMs = System.currentTimeMillis() - started;
            mode = selection.fallbackFullBuild ? "preslice-fallback-full" : "preslice";
        } else {
            selection = fullBuildSelection();
            presliceTimeMs = 0L;
            mode = "full";
        }

        long materializeStarted = System.currentTimeMillis();
        System.out.println("CDM materialization started: keptEventCount=" + selection.events.size()
                + ", keptRefCount=" + selection.refs.size());
        materialize(selection.events, selection.refs);
        materializeTimeMs = System.currentTimeMillis() - materializeStarted;
        keptEventCount = selection.events.size();
        keptRefCount = selection.refs.size();
        printBuildSummary(mode);
        return graph;
    }

    private void scanEvents(List<Path> files, ScanProgress progress) throws IOException {
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            Path file = files.get(fileIndex);
            progress.onFileStart(fileIndex, file);
            try (CdmAvroGzipReader reader = CdmAvroGzipReader.open(file)) {
                for (GenericRecord topLevel : reader) {
                    progress.onRecordProcessed();
                    if (!"RECORD_EVENT".equals(CdmRecordAccess.recordType(topLevel))) {
                        progress.maybeLog();
                        continue;
                    }

                    GenericRecord datum = CdmRecordAccess.datum(topLevel);
                    if (!collectEvent(datum)) {
                        progress.onFileEnd();
                        progress.logEarlyStop("Reached cdm.max_events limit");
                        return;
                    }
                    progress.maybeLog();
                }
            }
            progress.onFileEnd();
        }
    }

    private boolean collectEvent(GenericRecord event) {
        if (event == null) {
            return true;
        }

        long timestampNanos = CdmRecordAccess.longValue(event, "timestampNanos", Long.MIN_VALUE);
        if (timestampNanos < manifest.getStartNanos() || timestampNanos > manifest.getEndNanos()) {
            return true;
        }
        if (manifest.getMaxEvents() > 0 && eventsInWindow >= manifest.getMaxEvents()) {
            return false;
        }
        eventsInWindow++;

        String eventType = CdmRecordAccess.string(event, "type");
        FlowKind flowKind = CdmEventMapper.flowKind(eventType);
        if (flowKind == FlowKind.SKIP) {
            return true;
        }

        Ref subject = resolveEventSubjectRef(CdmRecordAccess.nullableUuid(event, "subject"));
        Ref predicate = resolveEventObjectRef(CdmRecordAccess.nullableUuid(event, "predicateObject"),
                CdmRecordAccess.nullableString(event, "predicateObjectPath"));
        Ref predicate2 = resolveEventObjectRef(CdmRecordAccess.nullableUuid(event, "predicateObject2"),
                CdmRecordAccess.nullableString(event, "predicateObject2Path"));
        long size = CdmRecordAccess.longValue(event, "size", 0L);
        String normalizedEventType = CdmEventMapper.normalizeEvent(eventType);

        if ("EXECUTE".equals(normalizedEventType) && subject != null && subject.kind == RefKind.UUID) {
            executeChildSubjectUuids.add(subject.value);
        }

        switch (flowKind) {
            case OBJECT_TO_PROCESS:
                addCompactEvent(predicate, subject, normalizedEventType, timestampNanos, size);
                break;
            case PROCESS_TO_OBJECT:
                addCompactEvent(subject, predicate, normalizedEventType, timestampNanos, size);
                break;
            case PROCESS_TO_PROCESS:
                addCompactEvent(subject, predicate, normalizedEventType, timestampNanos, size);
                break;
            case OBJECT_TO_OBJECT:
                addCompactEvent(predicate, predicate2, normalizedEventType, timestampNanos, size);
                break;
            case SKIP:
            default:
                break;
        }
        return true;
    }

    private Ref resolveEventSubjectRef(String uuid) {
        if (uuid == null) {
            return null;
        }
        referencedUuids.add(uuid);
        return Ref.uuid(uuid);
    }

    private Ref resolveEventObjectRef(String uuid, String eventPathFallback) {
        String normalizedFallback = normalizeSignature(eventPathFallback);
        if (uuid == null) {
            if (normalizedFallback == null) {
                return null;
            }
            Ref ref = Ref.fallbackSignature(normalizedFallback);
            registerSignatureRef(normalizedFallback, ref);
            return ref;
        }

        referencedUuids.add(uuid);
        Ref ref = Ref.uuid(uuid);
        if (normalizedFallback != null) {
            uuidToFallbackSignature.putIfAbsent(uuid, normalizedFallback);
            registerSignatureRef(normalizedFallback, ref);
        }
        return ref;
    }

    private void resolveReferencedEntities(List<Path> files) throws IOException {
        if (referencedUuids.isEmpty() && executeChildSubjectUuids.isEmpty()) {
            System.out.println("CDM entity resolution skipped: no referenced UUIDs collected from time-window events.");
            return;
        }

        referencedUuids.addAll(executeChildSubjectUuids);

        int pass = 1;
        while (true) {
            int unresolvedBefore = countUnresolvedReferencedUuids();
            if (unresolvedBefore == 0) {
                break;
            }

            int resolvedNodesBefore = rawNodesByUuid.size();
            int referencedBefore = referencedUuids.size();
            System.out.println("CDM entity resolution pass " + pass + " started: referencedUuids="
                    + referencedUuids.size() + ", unresolved=" + unresolvedBefore);

            for (Path file : files) {
                try (CdmAvroGzipReader reader = CdmAvroGzipReader.open(file)) {
                    for (GenericRecord topLevel : reader) {
                        String recordType = CdmRecordAccess.recordType(topLevel);
                        if ("RECORD_EVENT".equals(recordType)) {
                            continue;
                        }
                        GenericRecord datum = CdmRecordAccess.datum(topLevel);
                        resolveEntityIfReferenced(recordType, datum);
                    }
                }
            }

            int unresolvedAfter = countUnresolvedReferencedUuids();
            System.out.println("CDM entity resolution pass " + pass + " completed: resolvedNodes="
                    + rawNodesByUuid.size() + ", unresolved=" + unresolvedAfter);

            boolean madeProgress = rawNodesByUuid.size() > resolvedNodesBefore
                    || referencedUuids.size() > referencedBefore;
            if (!madeProgress) {
                break;
            }
            pass++;
        }

        int unresolved = countUnresolvedReferencedUuids();
        if (unresolved > 0) {
            System.out.println("WARNING: CDM entity resolution ended with " + unresolved
                    + " unresolved referenced UUID(s); path fallbacks will be used when available.");
        }
    }

    private int countUnresolvedReferencedUuids() {
        int count = 0;
        for (String uuid : referencedUuids) {
            if (!rawNodesByUuid.containsKey(uuid) && !uuidToFallbackSignature.containsKey(uuid)) {
                count++;
            }
        }
        return count;
    }

    private void resolveEntityIfReferenced(String recordType, GenericRecord datum) {
        if (datum == null || recordType == null) {
            return;
        }

        String uuid = CdmRecordAccess.uuid(datum, "uuid");
        if (uuid == null || !referencedUuids.contains(uuid) || rawNodesByUuid.containsKey(uuid)) {
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

        RawNodeInfo rawNodeInfo = RawNodeInfo.process(pid, name);
        rawNodesByUuid.put(uuid, rawNodeInfo);
        subjects.put(uuid, new SubjectInfo(parentUuid));
        registerSignatureRef(rawNodeInfo.signature, Ref.uuid(uuid));

        if (parentUuid != null && !parentUuid.trim().isEmpty()) {
            referencedUuids.add(parentUuid);
        }
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
        String signature = filename == null ? "file://" + uuid : filename;
        RawNodeInfo rawNodeInfo = RawNodeInfo.file(signature);
        rawNodesByUuid.put(uuid, rawNodeInfo);
        registerSignatureRef(rawNodeInfo.signature, Ref.uuid(uuid));
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

        RawNodeInfo rawNodeInfo = RawNodeInfo.network(localAddress, remoteAddress, localPort, remotePort);
        rawNodesByUuid.put(uuid, rawNodeInfo);
        registerSignatureRef(rawNodeInfo.signature, Ref.uuid(uuid));
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
        RawNodeInfo rawNodeInfo = RawNodeInfo.file(prefix + suffix);
        rawNodesByUuid.put(uuid, rawNodeInfo);
        registerSignatureRef(rawNodeInfo.signature, Ref.uuid(uuid));
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
        RawNodeInfo rawNodeInfo = RawNodeInfo.network("unix", path, "0", "0");
        rawNodesByUuid.put(uuid, rawNodeInfo);
        registerSignatureRef(rawNodeInfo.signature, Ref.uuid(uuid));
    }

    private boolean isSocketLikeObjectType(String objectType) {
        if (objectType == null) {
            return false;
        }
        return objectType.toUpperCase(Locale.ROOT).contains("SOCKET");
    }

    private void addCompactEvent(Ref sourceRef, Ref targetRef, String eventType, long timestampNanos, long size) {
        if (sourceRef == null || targetRef == null) {
            return;
        }
        compactEvents.add(new CompactEvent(nextCompactEventId++, sourceRef, targetRef, eventType, timestampNanos, size));
    }

    private void addExecuteParentEvents() {
        List<CompactEvent> parentEvents = new ArrayList<>();
        for (CompactEvent event : compactEvents) {
            if (!"EXECUTE".equals(event.eventType) || event.targetRef.kind != RefKind.UUID) {
                continue;
            }

            SubjectInfo childInfo = subjects.get(event.targetRef.value);
            if (childInfo == null || childInfo.parentUuid == null) {
                continue;
            }
            if (!rawNodesByUuid.containsKey(childInfo.parentUuid)) {
                continue;
            }

            parentEvents.add(new CompactEvent(nextCompactEventId++,
                    Ref.uuid(childInfo.parentUuid),
                    event.targetRef,
                    event.eventType + "_PARENT",
                    event.timestampNanos,
                    0L));
        }
        compactEvents.addAll(parentEvents);
    }

    private void indexCompactEvents() {
        for (CompactEvent event : compactEvents) {
            incomingIndex.computeIfAbsent(event.targetRef, ignored -> new ArrayList<>()).add(event);
        }
    }

    private PreSliceSelection preSlice() {
        Set<Long> keptEventIds = new LinkedHashSet<>();
        Set<Ref> keptRefs = new LinkedHashSet<>();
        boolean resolvedAnyPoi = false;

        for (String poiSignature : poiSignatures) {
            Set<Ref> refs = signatureToRefs.get(poiSignature);
            if (refs == null || refs.isEmpty()) {
                System.out.println("WARNING: CDM pre-slice could not resolve POI signature: " + poiSignature);
                continue;
            }

            resolvedAnyPoi = true;
            for (Ref ref : refs) {
                keptRefs.add(ref);
                backwardSliceFrom(ref, keptRefs, keptEventIds);
            }
        }

        if (!resolvedAnyPoi) {
            System.out.println("WARNING: CDM pre-slice resolved no POIs, falling back to full graph materialization");
            return new PreSliceSelection(new ArrayList<>(compactEvents), collectAllRefs(compactEvents), true);
        }

        List<CompactEvent> keptEvents = new ArrayList<>();
        for (CompactEvent event : compactEvents) {
            if (keptEventIds.contains(event.eventId)) {
                keptEvents.add(event);
            }
        }
        return new PreSliceSelection(keptEvents, keptRefs, false);
    }

    private void backwardSliceFrom(Ref start, Set<Ref> keptRefs, Set<Long> keptEventIds) {
        Map<Ref, Long> thresholds = new HashMap<>();
        Set<Ref> queued = new LinkedHashSet<>();
        Queue<Ref> queue = new LinkedList<>();
        thresholds.put(start, latestIncomingTime(start));
        queued.add(start);
        queue.offer(start);

        while (!queue.isEmpty()) {
            Ref current = queue.poll();
            keptRefs.add(current);
            long currentThreshold = thresholds.getOrDefault(current, 0L);

            List<CompactEvent> incomingEvents = incomingIndex.get(current);
            if (incomingEvents == null) {
                continue;
            }

            for (CompactEvent event : incomingEvents) {
                if (event.timestampNanos > currentThreshold) {
                    continue;
                }

                keptEventIds.add(event.eventId);
                keptRefs.add(event.sourceRef);
                keptRefs.add(event.targetRef);

                long sourceThreshold = Math.min(event.timestampNanos, currentThreshold);
                Long existingThreshold = thresholds.get(event.sourceRef);
                if (existingThreshold == null || existingThreshold < sourceThreshold) {
                    thresholds.put(event.sourceRef, sourceThreshold);
                }
                if (queued.add(event.sourceRef)) {
                    queue.offer(event.sourceRef);
                }
            }
        }
    }

    private long latestIncomingTime(Ref ref) {
        List<CompactEvent> incomingEvents = incomingIndex.get(ref);
        long latest = 0L;
        if (incomingEvents == null) {
            return latest;
        }
        for (CompactEvent event : incomingEvents) {
            if (event.timestampNanos > latest) {
                latest = event.timestampNanos;
            }
        }
        return latest;
    }

    private PreSliceSelection fullBuildSelection() {
        return new PreSliceSelection(new ArrayList<>(compactEvents), collectAllRefs(compactEvents), false);
    }

    private Set<Ref> collectAllRefs(List<CompactEvent> events) {
        Set<Ref> refs = new LinkedHashSet<>();
        for (CompactEvent event : events) {
            refs.add(event.sourceRef);
            refs.add(event.targetRef);
        }
        return refs;
    }

    private void materialize(List<CompactEvent> selectedEvents, Set<Ref> extraRefs) {
        Set<Ref> refsToCreate = new LinkedHashSet<>(extraRefs);
        for (CompactEvent event : selectedEvents) {
            refsToCreate.add(event.sourceRef);
            refsToCreate.add(event.targetRef);
        }

        for (Ref ref : refsToCreate) {
            NodeRef nodeRef = materializeRef(ref);
            if (nodeRef != null) {
                graph.addVertex(nodeRef.node);
            }
        }

        for (CompactEvent event : selectedEvents) {
            NodeRef source = materializeRef(event.sourceRef);
            NodeRef target = materializeRef(event.targetRef);
            if (source == null || target == null) {
                continue;
            }

            graph.addVertex(source.node);
            graph.addVertex(target.node);
            BigDecimal timestamp = nanosToSeconds(event.timestampNanos);
            EventEdge edge = new EventEdge(CdmEventMapper.edgeType(source.kind, target.kind),
                    timestamp, timestamp, event.size, source.node, target.node, nextEdgeId++);
            edge.setEdgeEvent(event.eventType);
            graph.addEdge(source.node, target.node, edge);
            mappedEdges++;
        }
    }

    private NodeRef materializeRef(Ref ref) {
        if (ref == null) {
            return null;
        }

        NodeRef existing = materializedRefs.get(ref);
        if (existing != null) {
            return existing;
        }

        RawNodeInfo rawNodeInfo = resolveRawNodeInfo(ref);
        if (rawNodeInfo == null) {
            return null;
        }

        NodeRef created;
        switch (rawNodeInfo.kind) {
            case PROCESS:
                created = processNode(rawNodeInfo.processPid, rawNodeInfo.processName);
                break;
            case NETWORK:
                created = networkNode(rawNodeInfo.localAddress, rawNodeInfo.remoteAddress,
                        rawNodeInfo.localPort, rawNodeInfo.remotePort);
                break;
            case FILE:
            case OBJECT:
            default:
                created = fileNode(rawNodeInfo.signature);
                break;
        }

        materializedRefs.put(ref, created);
        return created;
    }

    private RawNodeInfo resolveRawNodeInfo(Ref ref) {
        if (ref.kind == RefKind.UUID) {
            RawNodeInfo rawNodeInfo = rawNodesByUuid.get(ref.value);
            String fallbackSignature = uuidToFallbackSignature.get(ref.value);
            if (rawNodeInfo != null) {
                if (fallbackSignature != null && isSyntheticFileFallback(rawNodeInfo, ref.value)) {
                    return RawNodeInfo.file(fallbackSignature);
                }
                return rawNodeInfo;
            }
            if (fallbackSignature != null) {
                return RawNodeInfo.file(fallbackSignature);
            }
            return null;
        }

        return RawNodeInfo.file(ref.value);
    }

    private void registerSignatureRef(String signature, Ref ref) {
        String normalizedSignature = normalizeSignature(signature);
        if (normalizedSignature == null || ref == null) {
            return;
        }
        signatureToRefs.computeIfAbsent(normalizedSignature, ignored -> new LinkedHashSet<>()).add(ref);
    }

    private void printBuildSummary(String mode) {
        System.out.println("CDM graph built (" + mode + ") from " + manifest.getManifestPath()
                + ": vertices=" + graph.vertexSet().size()
                + ", edges=" + graph.edgeSet().size()
                + ", eventsInWindow=" + eventsInWindow
                + ", compactEventCount=" + compactEventCount
                + ", keptEventCount=" + keptEventCount
                + ", keptRefCount=" + keptRefCount
                + ", referencedUuids=" + referencedUuids.size()
                + ", resolvedNodes=" + rawNodesByUuid.size()
                + ", presliceTimeMs=" + presliceTimeMs
                + ", materializeTimeMs=" + materializeTimeMs
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

    private static Set<String> normalizePoiSignatures(Collection<String> poiSignatures) {
        if (poiSignatures == null) {
            return Collections.emptySet();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String poiSignature : poiSignatures) {
            String trimmed = normalizeSignature(poiSignature);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static BigDecimal nanosToSeconds(long nanos) {
        return BigDecimal.valueOf(nanos).movePointLeft(9);
    }

    private static String normalizeSignature(String signature) {
        if (signature == null) {
            return null;
        }
        String trimmed = signature.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private static boolean isSyntheticFileFallback(RawNodeInfo rawNodeInfo, String uuid) {
        return rawNodeInfo.kind == EntityKind.FILE && ("file://" + uuid).equals(rawNodeInfo.signature);
    }

    private enum RefKind {
        UUID,
        FALLBACK_SIGNATURE
    }

    private static final class Ref {
        private final RefKind kind;
        private final String value;

        private Ref(RefKind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        private static Ref uuid(String uuid) {
            return new Ref(RefKind.UUID, uuid);
        }

        private static Ref fallbackSignature(String signature) {
            return new Ref(RefKind.FALLBACK_SIGNATURE, signature);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Ref)) {
                return false;
            }
            Ref ref = (Ref) o;
            return kind == ref.kind && value.equals(ref.value);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + value.hashCode();
        }
    }

    private static final class CompactEvent {
        private final long eventId;
        private final Ref sourceRef;
        private final Ref targetRef;
        private final String eventType;
        private final long timestampNanos;
        private final long size;

        private CompactEvent(long eventId, Ref sourceRef, Ref targetRef, String eventType,
                             long timestampNanos, long size) {
            this.eventId = eventId;
            this.sourceRef = sourceRef;
            this.targetRef = targetRef;
            this.eventType = eventType;
            this.timestampNanos = timestampNanos;
            this.size = size;
        }
    }

    private static final class SubjectInfo {
        private final String parentUuid;

        private SubjectInfo(String parentUuid) {
            this.parentUuid = parentUuid;
        }
    }

    private static final class RawNodeInfo {
        private final EntityKind kind;
        private final String signature;
        private final String processPid;
        private final String processName;
        private final String localAddress;
        private final String remoteAddress;
        private final String localPort;
        private final String remotePort;

        private RawNodeInfo(EntityKind kind, String signature, String processPid, String processName,
                            String localAddress, String remoteAddress, String localPort, String remotePort) {
            this.kind = kind;
            this.signature = signature;
            this.processPid = processPid;
            this.processName = processName;
            this.localAddress = localAddress;
            this.remoteAddress = remoteAddress;
            this.localPort = localPort;
            this.remotePort = remotePort;
        }

        private static RawNodeInfo process(String pid, String name) {
            return new RawNodeInfo(EntityKind.PROCESS, pid + name, pid, name, null, null, null, null);
        }

        private static RawNodeInfo file(String signature) {
            return new RawNodeInfo(EntityKind.FILE, signature, null, null, null, null, null, null);
        }

        private static RawNodeInfo network(String localAddress, String remoteAddress,
                                           String localPort, String remotePort) {
            String signature = localAddress + ":" + localPort + "->" + remoteAddress + ":" + remotePort;
            return new RawNodeInfo(EntityKind.NETWORK, signature, null, null,
                    localAddress, remoteAddress, localPort, remotePort);
        }
    }

    private static final class PreSliceSelection {
        private final List<CompactEvent> events;
        private final Set<Ref> refs;
        private final boolean fallbackFullBuild;

        private PreSliceSelection(List<CompactEvent> events, Set<Ref> refs, boolean fallbackFullBuild) {
            this.events = events;
            this.refs = refs;
            this.fallbackFullBuild = fallbackFullBuild;
        }
    }

    private static final class NodeRef {
        private final EntityNode node;
        private final EntityKind kind;

        private NodeRef(EntityNode node, EntityKind kind) {
            this.node = node;
            this.kind = kind;
        }
    }

    private final class ScanProgress {
        private final int totalFiles;
        private final long totalCompressedBytes;
        private final long scanStartedAtMs;
        private long totalRecordsProcessed;
        private long currentFileRecordsProcessed;
        private long currentFileStartedAtMs;
        private long currentFileStartedEventsInWindow;
        private long currentFileStartedCompactEvents;
        private long lastProgressLogAtMs;
        private Path currentFile;
        private int currentFileIndex;

        private ScanProgress(List<Path> files) throws IOException {
            this.totalFiles = files.size();
            long compressedBytes = 0L;
            for (Path file : files) {
                compressedBytes += Files.size(file);
            }
            this.totalCompressedBytes = compressedBytes;
            this.scanStartedAtMs = System.currentTimeMillis();
            this.lastProgressLogAtMs = scanStartedAtMs;
        }

        private void logScanStart() {
            double windowSeconds = (manifest.getEndNanos() - manifest.getStartNanos()) / 1_000_000_000.0;
            System.out.println("CDM scan started: files=" + totalFiles
                    + ", totalCompressed=" + formatBytes(totalCompressedBytes)
                    + ", timeWindowSeconds=" + String.format(Locale.ROOT, "%.3f", windowSeconds)
                    + ", poiCount=" + poiSignatures.size());
        }

        private void onFileStart(int fileIndex, Path file) throws IOException {
            this.currentFileIndex = fileIndex + 1;
            this.currentFile = file;
            this.currentFileRecordsProcessed = 0L;
            this.currentFileStartedAtMs = System.currentTimeMillis();
            this.currentFileStartedEventsInWindow = eventsInWindow;
            this.currentFileStartedCompactEvents = compactEvents.size();
            System.out.println("CDM scan file " + currentFileIndex + "/" + totalFiles
                    + " started: " + file.getFileName()
                    + " (" + formatBytes(Files.size(file)) + ")");
        }

        private void onRecordProcessed() {
            totalRecordsProcessed++;
            currentFileRecordsProcessed++;
        }

        private void maybeLog() {
            if (totalRecordsProcessed % PROGRESS_RECORD_GRANULARITY != 0) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastProgressLogAtMs < PROGRESS_LOG_INTERVAL_MS) {
                return;
            }
            lastProgressLogAtMs = now;
            logProgress("progress");
        }

        private void onFileEnd() {
            logProgress("completed");
        }

        private void logEarlyStop(String reason) {
            long elapsedMs = System.currentTimeMillis() - scanStartedAtMs;
            System.out.println("CDM scan stopped early: reason=" + reason
                    + ", elapsedSeconds=" + String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0)
                    + ", totalRecords=" + totalRecordsProcessed
                    + ", eventsInWindow=" + eventsInWindow
                    + ", compactEvents=" + compactEvents.size());
        }

        private void logProgress(String status) {
            long now = System.currentTimeMillis();
            long fileElapsedMs = Math.max(0L, now - currentFileStartedAtMs);
            long totalElapsedMs = Math.max(0L, now - scanStartedAtMs);
            long fileEventsInWindow = eventsInWindow - currentFileStartedEventsInWindow;
            long fileCompactEvents = compactEvents.size() - currentFileStartedCompactEvents;
            System.out.println("CDM scan file " + currentFileIndex + "/" + totalFiles + " " + status
                    + ": name=" + currentFile.getFileName()
                    + ", fileRecords=" + currentFileRecordsProcessed
                    + ", totalRecords=" + totalRecordsProcessed
                    + ", fileEventsInWindow=" + fileEventsInWindow
                    + ", totalEventsInWindow=" + eventsInWindow
                    + ", fileCompactEvents=" + fileCompactEvents
                    + ", totalCompactEvents=" + compactEvents.size()
                    + ", cachedEntities=" + rawNodesByUuid.size()
                    + ", elapsedFileSeconds=" + String.format(Locale.ROOT, "%.1f", fileElapsedMs / 1000.0)
                    + ", elapsedTotalSeconds=" + String.format(Locale.ROOT, "%.1f", totalElapsedMs / 1000.0));
        }

        private String formatBytes(long bytes) {
            if (bytes >= 1024L * 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
            }
            if (bytes >= 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
            }
            if (bytes >= 1024L) {
                return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
            }
            return bytes + " B";
        }
    }
}
