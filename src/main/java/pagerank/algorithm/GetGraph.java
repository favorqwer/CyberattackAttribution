package pagerank.algorithm;

import org.jgrapht.graph.DirectedPseudograph;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.entity.FileEntity;
import pagerank.entity.FtoPEvent;
import pagerank.entity.NtoPEvent;
import pagerank.entity.NetworkEntity;
import pagerank.entity.PtoFEvent;
import pagerank.entity.PtoNEvent;
import pagerank.entity.PtoPEvent;
import pagerank.entity.Process;
import pagerank.main.ProcessTheOriginalParserOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * GetGraph - 依赖图构建类
 *
 * 负责从 Sysdig 审计日志中解析事件，并构建进程、文件、网络实体之间的依赖图。
 */
public class GetGraph {
    public DirectedPseudograph<EntityNode, EventEdge> jg;

    private final Map<Long, EntityNode> entityNodeMap;
    private final ProcessTheOriginalParserOutput sysdigProcess;
    private boolean graphGenerated;

    public EntityNode POIEvent;
    private IterateGraph iter;

    public GetGraph(String path, String[] localIP) {
        this.POIEvent = null;
        this.jg = new DirectedPseudograph<>(EventEdge.class);
        this.entityNodeMap = new HashMap<>();
        this.sysdigProcess = new ProcessTheOriginalParserOutput(path, localIP);
        this.graphGenerated = false;
    }

    public DirectedPseudograph<EntityNode, EventEdge> getJg() {
        if (!graphGenerated) {
            GenerateGraph();
        }
        return jg;
    }

    public void GenerateGraph() {
        if (graphGenerated) {
            return;
        }
        addFileToProcessEvent(sysdigProcess.getFileProcessMap());
        addNetworkToProcessEvent(sysdigProcess.getNetworkProcessMap());
        addProcessToFileEvent(sysdigProcess.getProcessFileMap());
        addProcessToProcessEvent(sysdigProcess.getProcessProcessMap());
        addProcessToNetworkEvent(sysdigProcess.getProcessNetworkMap());
        assignEdgeId();
        graphGenerated = true;
        sysdigProcess.afterGraphBuilt();
    }

    private void assignEdgeId() {
        long edgeID = 1;
        for (EventEdge edge : jg.edgeSet()) {
            edge.id = edgeID++;
        }
    }

    private void addProcessToFileEvent(Map<String, PtoFEvent> pfmap) {
        for (PtoFEvent event : pfmap.values()) {
            addEventEdge(event.getSource(), event.getSink(), new EventEdge(event));
        }
    }

    private void addFileToProcessEvent(Map<String, FtoPEvent> fpmap) {
        for (FtoPEvent event : fpmap.values()) {
            addEventEdge(event.getSource(), event.getSink(), new EventEdge(event));
        }
    }

    private void addProcessToProcessEvent(Map<String, PtoPEvent> ppmap) {
        for (PtoPEvent event : ppmap.values()) {
            addEventEdge(event.getSource(), event.getSink(), new EventEdge(event));
        }
    }

    private void addNetworkToProcessEvent(Map<String, NtoPEvent> npmap) {
        for (NtoPEvent event : npmap.values()) {
            addEventEdge(event.getSource(), event.getSink(), new EventEdge(event));
        }
    }

    private void addProcessToNetworkEvent(Map<String, PtoNEvent> ptonMap) {
        for (PtoNEvent event : ptonMap.values()) {
            addEventEdge(event.getSource(), event.getSink(), new EventEdge(event));
        }
    }

    private void addEventEdge(pagerank.entity.Entity sourceEntity,
                              pagerank.entity.Entity sinkEntity,
                              EventEdge edge) {
        EntityNode source = getOrCreateNode(sourceEntity);
        EntityNode sink = getOrCreateNode(sinkEntity);
        jg.addVertex(source);
        jg.addVertex(sink);
        jg.addEdge(source, sink, edge);
    }

    private EntityNode getOrCreateNode(pagerank.entity.Entity entity) {
        return entityNodeMap.computeIfAbsent(entity.getUniqID(), key -> createEntityNode(entity));
    }

    private EntityNode createEntityNode(pagerank.entity.Entity entity) {
        if (entity instanceof FileEntity) {
            return new EntityNode((FileEntity) entity);
        }
        if (entity instanceof NetworkEntity) {
            return new EntityNode((NetworkEntity) entity);
        }
        if (entity instanceof Process) {
            return new EntityNode((Process) entity);
        }
        throw new IllegalArgumentException("Unsupported entity type: " + entity.getClass().getName());
    }

    public EntityNode getPOIEvent() {
        return POIEvent;
    }

    public void exportGraph(String file) {
        getJg();
        iter = new IterateGraph(jg);
        iter.exportGraph(file);
    }

    /**
     * 从原始图（未经过 BackTrack/CPR 压缩）中提取 POI 节点相关边的数据量作为 detectionSize。
     */
    public static double extractDetectionSizeFromGraph(
            DirectedPseudograph<EntityNode, EventEdge> graph, String detection) {
        EntityNode poiNode = null;
        for (EntityNode node : graph.vertexSet()) {
            if (node.getSignature().equals(detection)) {
                poiNode = node;
                break;
            }
        }
        if (poiNode == null) {
            System.out.println("extractDetectionSizeFromGraph: POI node '" + detection
                    + "' not found in original graph");
            return 0;
        }

        long maxSize = 0;
        Set<EventEdge> inEdges = graph.incomingEdgesOf(poiNode);
        for (EventEdge edge : inEdges) {
            if (edge.getSize() > maxSize) {
                maxSize = edge.getSize();
            }
        }

        if (maxSize == 0) {
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(poiNode);
            for (EventEdge edge : outEdges) {
                if (edge.getSize() > maxSize) {
                    maxSize = edge.getSize();
                }
            }
        }

        if (maxSize > 0) {
            System.out.println("extractDetectionSizeFromGraph: extracted detectionSize=" + maxSize
                    + " from POI node '" + detection + "' (original graph, "
                    + inEdges.size() + " in-edges, "
                    + graph.outgoingEdgesOf(poiNode).size() + " out-edges)");
        } else {
            System.out.println("extractDetectionSizeFromGraph: no edge data found for POI node '"
                    + detection + "' in original graph");
        }
        return maxSize;
    }
}
