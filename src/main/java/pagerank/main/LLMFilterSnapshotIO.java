package pagerank.main;

import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.entity.FileEntity;
import pagerank.entity.NetworkEntity;
import pagerank.entity.Process;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LLMFilterSnapshotIO {

    private LLMFilterSnapshotIO() {
    }

    public static final class SnapshotData {
        public final DirectedPseudograph<EntityNode, EventEdge> graph;
        public final List<String> entryPoints;
        public final String poiEvent;

        SnapshotData(DirectedPseudograph<EntityNode, EventEdge> graph, List<String> entryPoints, String poiEvent) {
            this.graph = graph;
            this.entryPoints = entryPoints;
            this.poiEvent = poiEvent;
        }
    }

    @SuppressWarnings("unchecked")
    public static void writeSnapshot(
            DirectedPseudograph<EntityNode, EventEdge> graph,
            List<String> entryPoints,
            String poiEvent,
            File snapshotFile) {

        JSONObject root = new JSONObject();
        root.put("poi_event", poiEvent == null ? "" : poiEvent);

        JSONArray entryArray = new JSONArray();
        if (entryPoints != null) {
            for (String entry : entryPoints) {
                if (entry != null && !entry.trim().isEmpty()) {
                    entryArray.add(entry.trim());
                }
            }
        }
        root.put("entry_points", entryArray);

        JSONArray nodesArray = new JSONArray();
        for (EntityNode node : graph.vertexSet()) {
            JSONObject nodeObj = new JSONObject();
            nodeObj.put("id", node.getID());
            nodeObj.put("signature", node.getSignature());
            nodeObj.put("reputation", node.getReputation());

            if (node.isProcessNode() && node.getP() != null) {
                nodeObj.put("node_type", "Process");
                nodeObj.put("pid", node.getP().getPid());
                nodeObj.put("name", node.getP().getName());
            } else if (node.isFileNode() && node.getF() != null) {
                nodeObj.put("node_type", "FileEntity");
                nodeObj.put("path", node.getF().getPath());
            } else if (node.isNetworkNode() && node.getN() != null) {
                nodeObj.put("node_type", "NetworkEntity");
                nodeObj.put("conn", node.getN().getSrcAndDstIP());
            } else {
                nodeObj.put("node_type", "EntityNode");
            }

            nodesArray.add(nodeObj);
        }
        root.put("nodes", nodesArray);

        JSONArray edgesArray = new JSONArray();
        for (EventEdge edge : graph.edgeSet()) {
            JSONObject edgeObj = new JSONObject();
            edgeObj.put("id", edge.getID());
            edgeObj.put("source", graph.getEdgeSource(edge).getSignature());
            edgeObj.put("target", graph.getEdgeTarget(edge).getSignature());
            edgeObj.put("type", edge.getType() == null ? "" : edge.getType());
            edgeObj.put("event", edge.getEvent() == null ? "" : edge.getEvent());
            edgeObj.put("start_time", edge.getStartTime() == null ? "0" : edge.getStartTime().toString());
            edgeObj.put("end_time", edge.getEndTime() == null ? "0" : edge.getEndTime().toString());
            edgeObj.put("size", edge.getSize());
            edgeObj.put("weight", edge.weight);
            edgeObj.put("time_weight", edge.timeWeight);
            edgeObj.put("amount_weight", edge.amountWeight);
            edgeObj.put("structure_weight", edge.structureWeight);
            edgesArray.add(edgeObj);
        }
        root.put("edges", edgesArray);

        File parent = snapshotFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(snapshotFile)) {
            writer.write(root.toJSONString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to write LLM snapshot: " + snapshotFile.getAbsolutePath(), e);
        }
    }

    public static SnapshotData readSnapshot(File snapshotFile) {
        try (FileReader reader = new FileReader(snapshotFile)) {
            JSONObject root = (JSONObject) new JSONParser().parse(reader);

            String poiEvent = asString(root.get("poi_event"));

            List<String> entryPoints = new ArrayList<>();
            JSONArray entryArray = asArray(root.get("entry_points"));
            for (Object item : entryArray) {
                String entry = asString(item);
                if (!entry.isEmpty()) {
                    entryPoints.add(entry);
                }
            }

            DirectedPseudograph<EntityNode, EventEdge> graph = new DirectedPseudograph<>(EventEdge.class);
            Map<String, EntityNode> nodeBySignature = new LinkedHashMap<>();

            JSONArray nodesArray = asArray(root.get("nodes"));
            for (Object item : nodesArray) {
                JSONObject n = (JSONObject) item;
                EntityNode node = createNode(n);
                nodeBySignature.put(node.getSignature(), node);
                graph.addVertex(node);
            }

            JSONArray edgesArray = asArray(root.get("edges"));
            for (Object item : edgesArray) {
                JSONObject e = (JSONObject) item;
                String sourceSig = asString(e.get("source"));
                String targetSig = asString(e.get("target"));
                EntityNode source = nodeBySignature.get(sourceSig);
                EntityNode target = nodeBySignature.get(targetSig);
                if (source == null || target == null) {
                    continue;
                }

                long id = asLong(e.get("id"));
                String type = asString(e.get("type"));
                String event = asString(e.get("event"));
                long size = asLong(e.get("size"));
                BigDecimal start = asBigDecimal(e.get("start_time"));
                BigDecimal end = asBigDecimal(e.get("end_time"));

                EventEdge edge = new EventEdge(type, start, end, size, source, target, id);
                edge.setEdgeEvent(event);
                edge.weight = asDouble(e.get("weight"));
                edge.timeWeight = asDouble(e.get("time_weight"));
                edge.amountWeight = asDouble(e.get("amount_weight"));
                edge.structureWeight = asDouble(e.get("structure_weight"));

                graph.addEdge(source, target, edge);
            }

            return new SnapshotData(graph, entryPoints, poiEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read LLM snapshot: " + snapshotFile.getAbsolutePath(), e);
        }
    }

    private static EntityNode createNode(JSONObject nodeJson) {
        long id = asLong(nodeJson.get("id"));
        double reputation = asDouble(nodeJson.get("reputation"));
        String signature = asString(nodeJson.get("signature"));
        String nodeType = asString(nodeJson.get("node_type"));

        if ("Process".equals(nodeType)) {
            String pid = asString(nodeJson.get("pid"));
            String name = asString(nodeJson.get("name"));
            Process p = new Process(reputation, pid, name, id);
            return new EntityNode(p);
        }

        if ("FileEntity".equals(nodeType)) {
            String path = asString(nodeJson.get("path"));
            FileEntity f = new FileEntity(reputation, path, id);
            return new EntityNode(f);
        }

        if ("NetworkEntity".equals(nodeType)) {
            String conn = asString(nodeJson.get("conn"));
            String[] parsed = parseConn(conn);
            if (parsed != null) {
                NetworkEntity n = new NetworkEntity(reputation, parsed[0], parsed[2], parsed[1], parsed[3], id);
                return new EntityNode(n);
            }
        }

        return new EntityNode(id, reputation, signature);
    }

    private static String[] parseConn(String conn) {
        if (conn == null || conn.isEmpty()) {
            return null;
        }
        String[] parts = conn.split("->", 2);
        if (parts.length != 2) {
            return null;
        }
        String[] left = splitHostPort(parts[0]);
        String[] right = splitHostPort(parts[1]);
        if (left == null || right == null) {
            return null;
        }
        return new String[]{left[0], left[1], right[0], right[1]};
    }

    private static String[] splitHostPort(String value) {
        int lastColon = value.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == value.length() - 1) {
            return null;
        }
        String host = value.substring(0, lastColon).trim();
        String port = value.substring(lastColon + 1).trim();
        if (host.isEmpty() || port.isEmpty()) {
            return null;
        }
        return new String[]{host, port};
    }

    private static JSONArray asArray(Object value) {
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        return new JSONArray();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String str = asString(value);
        if (str.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(str);
    }

    private static double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String str = asString(value);
        if (str.isEmpty()) {
            return 0.0d;
        }
        return Double.parseDouble(str);
    }

    private static BigDecimal asBigDecimal(Object value) {
        String str = asString(value);
        if (str.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(str);
    }
}