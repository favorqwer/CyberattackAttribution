package pagerank;

import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class LLMGraphUtils {

    /**
     * 功能 1：将图序列化为 JSON 字符串（用于发送给 LLM）
     * [修改]：移除了 anomaly_score 和 data_size，强制 LLM 仅基于语义判断。
     */
    @SuppressWarnings("unchecked")
    public static String serializeGraphForLLM(DirectedPseudograph<EntityNode, EventEdge> graph, String poi) {
        JSONObject root = new JSONObject();
        root.put("poi_context", poi);
        root.put("total_edges", graph.edgeSet().size());

        JSONArray edgesArray = new JSONArray();
        for (EventEdge edge : graph.edgeSet()) {
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("edge_id", edge.getID());
            edgeJson.put("operation", edge.getEvent());

            // 简化 Source/Target 格式，让 LLM 更容易读懂语义
            edgeJson.put("source", edge.getSource().getSignature());
            edgeJson.put("target", edge.getSink().getSignature());

            if (edge.getEndTime() != null) {
                edgeJson.put("timestamp", edge.getEndTime().toString());
            }

            // [关键修改]：移除 anomaly_score 和 data_size
            // 确保 LLM 只能通过进程名和文件名来判断，而不是依赖异常分
            // edgeJson.put("anomaly_score", String.format("%.4f", edge.anomalyWeight));
            // edgeJson.put("data_size", edge.getSize());

            edgesArray.add(edgeJson);
        }
        root.put("edges", edgesArray);
        return root.toJSONString();
    }

    /**
     * [修改版] 功能 3：根据 LLM 返回的保留 ID 列表，过滤图中的边
     * 修复：使用 String 进行 ID 比对，避免 Integer/Long 类型不匹配导致误删所有边的问题。
     */
    public static void filterGraphWithRetentionList(DirectedPseudograph<EntityNode, EventEdge> graph, String jsonResponseString) {
        try {
            org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
            JSONArray keptIdsJson = (JSONArray) parser.parse(jsonResponseString);

            // 修复点 1：使用 String 存储 ID，避免 int vs long 的问题
            Set<String> keptIds = new HashSet<>();
            for (Object id : keptIdsJson) {
                keptIds.add(id.toString().trim());
            }

            // 找出所有不在保留列表中的边
            List<EventEdge> edgesToRemove = new ArrayList<>();
            for (EventEdge edge : graph.edgeSet()) {
                // 修复点 2：将边的 ID 也转为 String 进行比对
                String edgeIdStr = String.valueOf(edge.getID());
                if (!keptIds.contains(edgeIdStr)) {
                    edgesToRemove.add(edge);
                }
            }

            // 执行删除
            System.out.println("LLM Filter: Keeping " + keptIds.size() + " edges provided by LLM.");
            System.out.println("LLM Filter: Removing " + edgesToRemove.size() + " irrelevant edges based on semantic analysis.");
            graph.removeAllEdges(edgesToRemove);

            // 清理孤立节点
            List<EntityNode> nodesToRemove = new ArrayList<>();
            for (EntityNode node : graph.vertexSet()) {
                if (graph.degreeOf(node) == 0) {
                    nodesToRemove.add(node);
                }
            }
            graph.removeAllVertices(nodesToRemove);
            System.out.println("LLM Filter: Removed " + nodesToRemove.size() + " isolated nodes.");

        } catch (Exception e) {
            System.err.println("Error parsing LLM response or filtering graph: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 功能 2：将图导出为 .dot 文件（用于可视化）
     * 保持不变，用于可视化最终结果
     */
    public static void exportGraphToDot(DirectedPseudograph<EntityNode, EventEdge> graph, String outputPath) {
        if (graph == null) {
            System.err.println("Error: Graph is null, cannot export.");
            return;
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            writer.println("digraph G {");
            writer.println("  rankdir=LR;");
            writer.println("  node [shape=box, style=filled, fillcolor=\"white\", fontname=\"Arial\"];");
            writer.println("  edge [fontname=\"Arial\", fontsize=10];");

            for (EntityNode node : graph.vertexSet()) {
                String shape = node.isProcessNode() ? "ellipse" : "box";
                String id = String.valueOf(node.getID());
                String label = escapeDotLabel(node.getSignature());
                writer.printf("  \"%s\" [label=\"%s\", shape=%s];\n", id, label, shape);
            }

            for (EventEdge edge : graph.edgeSet()) {
                String sourceId = String.valueOf(edge.getSource().getID());
                String targetId = String.valueOf(edge.getSink().getID());
                String label = String.format("%s\\n(Score: %.4f)", edge.getEvent(), edge.anomalyWeight);

                // 依然保留标红逻辑，用于验证 LLM 保留的边是否也恰好是高分边（Result Validation）
                String style;
                if (edge.anomalyWeight > 0.7) {
                    style = ", color=\"red\", penwidth=2.0, fontcolor=\"red\"";
                } else {
                    style = ", color=\"black\", penwidth=1.0";
                }
                writer.printf("  \"%s\" -> \"%s\" [label=\"%s\"%s];\n", sourceId, targetId, label, style);
            }
            writer.println("}");
            System.out.println("Graph successfully exported to: " + outputPath);

        } catch (IOException e) {
            System.err.println("Failed to export DOT file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String escapeDotLabel(String label) {
        if (label == null) return "";
        return label.replace("\"", "\\\"").replace("\n", "\\n");
    }
}