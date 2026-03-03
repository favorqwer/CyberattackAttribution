package pagerank.algorithm;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static pagerank.main.ProcessOneLogCMD_19.DotToSvg;

/**
 * LLMGraphFilter 模块：利用大语言模型 (LLM) 进行溯源图降噪和路径提取。
 * 1. 将图结构序列化为 LLM 可读的结构化 JSON。
 * 2. 引导 LLM 提取绝对确定的攻击边以及它认为正确的入口节点。
 * 3. 算法后处理：以 POI、LLM 输出的边和入口节点为基础重建连通图，
 *    保证每个入口节点都有一条到 POI 的路径。
 */
public class LLMGraphFilter {

    // ============ 预编译正则表达式常量（优化#14） ============
    private static final Pattern EDGES_PATTERN = Pattern.compile("EDGES:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_NODES_PATTERN = Pattern.compile("ENTRY_NODES:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_NODES_REMOVE_PATTERN = Pattern.compile("(?i)ENTRY_NODES:\\s*\\[[^\\]]*\\]");
    private static final Pattern BRACKET_CONTENT_PATTERN = Pattern.compile("\\[([a-zA-Z0-9_\\-\\s,\"']+)\\]");

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private boolean llmEnabled;
    private double temperature;
    private int maxTokens;

    public LLMGraphFilter() {
        loadConfig();
    }

    /**
     * 加载 LLM 配置文件 (llm.properties)。
     */
    private void loadConfig() {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("llm.properties")) {
            prop.load(fis);
            this.llmEnabled = Boolean.parseBoolean(prop.getProperty("llm_enabled", "true").trim());
            this.baseUrl = prop.getProperty("base_url", "");
            this.apiKey = prop.getProperty("api_key", "");
            this.modelName = prop.getProperty("model", "");
            // 优化#11：temperature 和 max_tokens 从配置文件读取
            this.temperature = Double.parseDouble(prop.getProperty("temperature", "0.1").trim());
            this.maxTokens = Integer.parseInt(prop.getProperty("max_tokens", "20480").trim());
        } catch (Exception e) {
            System.err.println("Warning: Could not load llm.properties. Using default values.");
            this.llmEnabled = false;
            this.baseUrl = "";
            this.apiKey = "";
            this.modelName = "";
            this.temperature = 0.1;
            this.maxTokens = 20480;
        }
    }

    /**
     * 核心过滤方法。
     */
    public DirectedPseudograph<EntityNode, EventEdge> filterGraph(
            DirectedPseudograph<EntityNode, EventEdge> originalGraph,
            List<String> entryPoints,
            String poiEvent,
            String logFilePath) {

        if (!this.llmEnabled) {
            System.out.println("LLM filtering is disabled by configuration (llm_enabled=false). Returning original graph.");
            return originalGraph;
        }

        // 1. 安全检查
        if (this.baseUrl == null || this.baseUrl.trim().isEmpty() ||
                this.apiKey == null || this.apiKey.trim().isEmpty() ||
                this.modelName == null || this.modelName.trim().isEmpty()) {
            System.err.println("LLM API configuration is missing or incomplete. Skipping LLM filtering and returning the original graph.");
            return originalGraph;
        }

        System.out.println("Starting LLM graph filtering...");

        // 2. 将图对象转换为 JSON 结构化文本
        String graphText = serializeGraph(originalGraph);

        // 3. 数据预处理
        List<String> filteredEntryPoints = new ArrayList<>();
        for (String entry : entryPoints) {
            if (!entry.equals(poiEvent)) {
                filteredEntryPoints.add(entry);
            }
        }

        // 优化#8：计算图统计摘要信息
        String graphStatsSummary = buildGraphStatsSummary(originalGraph);

        // 4. 生成 Prompt
        String prompt = buildPrompt(graphText, filteredEntryPoints, poiEvent, graphStatsSummary);

        // 5. 联网调用 LLM
        String llmResponse = callLLMAPI(prompt);

        // 6. 日志记录
        writeLLMLog(logFilePath, prompt, llmResponse);

        if (llmResponse == null || llmResponse.isEmpty()) {
            System.err.println("LLM response was empty or failed. Returning original graph.");
            return originalGraph;
        }

        // 7. 解析响应：提取边 ID 和 LLM 认为正确的入口节点
        Set<String> keptEdgeIds = extractEdgeIdsFromResponse(llmResponse);
        System.out.println("LLM selected " + keptEdgeIds.size() + " edges to keep.");

        // 优化#13：校验 LLM 返回的 Edge ID 是否在原始图中实际存在
        Set<String> validEdgeIds = new HashSet<>();
        for (EventEdge edge : originalGraph.edgeSet()) {
            validEdgeIds.add(String.valueOf(edge.getID()));
        }
        Set<String> invalidIds = new HashSet<>(keptEdgeIds);
        invalidIds.removeAll(validEdgeIds);
        if (!invalidIds.isEmpty()) {
            System.err.println("Warning: LLM returned " + invalidIds.size() + " non-existent edge IDs (hallucinated): " + invalidIds);
            keptEdgeIds.removeAll(invalidIds);
            System.out.println("After validation, " + keptEdgeIds.size() + " valid edges remain.");
        }

        Set<String> llmEntryNodes = extractEntryNodesFromResponse(llmResponse);
        System.out.println("LLM identified " + llmEntryNodes.size() + " entry nodes.");

        // 8. 重建图
        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = buildFilteredGraph(originalGraph, keptEdgeIds);

        // 优化#6：生成 LLM 原始输出的中间 SVG（抽取为独立方法）
        exportIntermediateVisualization(filteredGraph, logFilePath);

        // 9. 将 LLM 识别的入口节点加入过滤图（确保它们存在于图中）
        Set<EntityNode> llmEntryEntityNodes = new HashSet<>();
        for (EntityNode node : originalGraph.vertexSet()) {
            if (llmEntryNodes.contains(node.getSignature())) {
                if (!filteredGraph.containsVertex(node)) {
                    filteredGraph.addVertex(node);
                }
                llmEntryEntityNodes.add(node);
            }
        }

        // 10. 找到 POI 对应的节点
        EntityNode poiNode = null;
        for (EntityNode node : originalGraph.vertexSet()) {
            if (node.getSignature().equals(poiEvent)) {
                poiNode = node;
                break;
            }
        }
        if (poiNode != null && !filteredGraph.containsVertex(poiNode)) {
            filteredGraph.addVertex(poiNode);
        }

        // 11. 连通性检查与后处理补全算法：保证每个入口节点到 POI 都有路径
        ensureConnectivity(originalGraph, filteredGraph, llmEntryEntityNodes, poiNode);

        return filteredGraph;
    }

    /**
     * 写入 LLM 交互日志（优化#1：使用 try-with-resources 确保资源释放）
     */
    private void writeLLMLog(String logFilePath, String prompt, String response) {
        java.io.File logFile = new java.io.File(logFilePath);
        try (java.io.FileWriter writer = new java.io.FileWriter(logFile)) {
            writer.write("================ LLM PROMPT ================\n");
            writer.write(prompt);
            writer.write("\n\n================ LLM RESPONSE ================\n");
            writer.write(response != null ? response : "NULL (Request failed or returned empty)");
            writer.write("\n==============================================\n");
            System.out.println("LLM Interaction log saved to: " + logFilePath);
        } catch (Exception e) {
            System.err.println("Failed to write LLM log file: " + e.getMessage());
        }
    }

    /**
     * 优化#6：生成 LLM 原始过滤结果的中间可视化 SVG，抽取为独立方法。
     */
    private void exportIntermediateVisualization(
            DirectedPseudograph<EntityNode, EventEdge> filteredGraph, String logFilePath) {
        try {
            String intermediatePath = logFilePath.replace(".log", "_llm_raw");
            IterateGraph intermediateOut = new IterateGraph(filteredGraph);
            intermediateOut.exportGraph(intermediatePath);
            DotToSvg(intermediatePath + ".dot", intermediatePath + ".svg");
            System.out.println("LLM raw output visualization saved: " + intermediatePath + ".svg");
        } catch (Exception e) {
            System.err.println("Failed to generate intermediate LLM SVG: " + e.getMessage());
        }
    }

    /**
     * 采用 JSON 格式化输出 Nodes 和 Edges，让大语言模型能更好地解析关联属性
     */
    @SuppressWarnings("unchecked")
    private String serializeGraph(DirectedPseudograph<EntityNode, EventEdge> graph) {
        JSONObject graphJson = new JSONObject();

        JSONArray nodesArray = new JSONArray();
        for (EntityNode node : graph.vertexSet()) {
            JSONObject nodeObj = new JSONObject();
            nodeObj.put("id", node.getSignature());
            nodeObj.put("type", node.getClass().getSimpleName());
            nodesArray.add(nodeObj);
        }

        JSONArray edgesArray = new JSONArray();
        for (EventEdge edge : graph.edgeSet()) {
            JSONObject edgeObj = new JSONObject();
            edgeObj.put("edge_id", String.valueOf(edge.getID()));
            edgeObj.put("source", graph.getEdgeSource(edge).getSignature());
            edgeObj.put("target", graph.getEdgeTarget(edge).getSignature());
            edgeObj.put("event_type", edge.getEvent());
            edgeObj.put("timestamp", edge.getStartTime());
            edgesArray.add(edgeObj);
        }

        graphJson.put("nodes", nodesArray);
        graphJson.put("edges", edgesArray);

        return graphJson.toJSONString();
    }

    /**
     * 优化#8：构建图统计摘要信息，包括节点总数、各类型节点数量、边总数、各事件类型边数量。
     */
    private String buildGraphStatsSummary(DirectedPseudograph<EntityNode, EventEdge> graph) {
        int totalNodes = graph.vertexSet().size();
        int totalEdges = graph.edgeSet().size();

        Map<String, Integer> nodeTypeCounts = new HashMap<>();
        for (EntityNode node : graph.vertexSet()) {
            String type = node.getClass().getSimpleName();
            nodeTypeCounts.merge(type, 1, Integer::sum);
        }

        Map<String, Integer> edgeTypeCounts = new HashMap<>();
        for (EventEdge edge : graph.edgeSet()) {
            String eventType = edge.getEvent() != null ? edge.getEvent() : edge.getType();
            edgeTypeCounts.merge(eventType, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Graph Statistics: ").append(totalNodes).append(" nodes (");
        List<String> nodeStats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : nodeTypeCounts.entrySet()) {
            nodeStats.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", nodeStats)).append("), ");
        sb.append(totalEdges).append(" edges (");
        List<String> edgeStats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : edgeTypeCounts.entrySet()) {
            edgeStats.add(entry.getValue() + " " + entry.getKey());
        }
        sb.append(String.join(", ", edgeStats)).append(").");
        return sb.toString();
    }

    /**
     * 强调让 LLM 输出绝对属于攻击路径的边，以及它认为正确的入口节点。
     * 优化#8：在 Prompt 中加入图的统计摘要信息，帮助 LLM 更好地理解图的全局结构。
     */
    private String buildPrompt(String graphText, List<String> entryPoints, String poiEvent, String graphStatsSummary) {
        return "You are a cybersecurity expert analyzing a provenance graph to track an attack. " +
                "The graph is provided in a structured JSON format describing system entities (nodes) and events (edges).\n\n" +
                "The known attack target is:\n" + poiEvent + "\n\n" +
                "Possible attack entry points:\n" + String.join(", ", entryPoints) + "\n\n" +
                graphStatsSummary + "\n\n" +
                "Graph Data (JSON):\n" + graphText + "\n\n" +
                "Your Task:\n" +
                "1. Analyze the causal relationships carefully.\n" +
                "2. Identify ONLY the edges that you are ABSOLUTELY CERTAIN belong to the malicious attack sequence.DO NOT hallucinate or include borderline/noisy edges just to make the path fully connected.\n" +
                "3. From the possible attack entry points listed above, identify which ones you believe are ABSOLUTELY CERTAIN belong to the true attack entry points " +
                "(i.e., the actual origin of the attack). You may select one or more.\n" +
                "It is FINE if the edges you select are disconnected from each other. Focus ONLY on high-confidence malicious behavior.\n\n" +
                "Output Format (MUST follow strictly):\n" +
                "1. First, provide your analysis.\n" +
                "2. Then, provide the kept edge IDs as a comma-separated list enclosed in brackets prefixed with 'EDGES:' like this:\n" +
                "   EDGES: [EdgeID1, EdgeID2, ...]\n" +
                "3. Finally, provide the entry node signatures you believe are the true attack entry points, " +
                "as a comma-separated list enclosed in brackets prefixed with 'ENTRY_NODES:' like this:\n" +
                "   ENTRY_NODES: [node_signature1, node_signature2, ...]\n" +
                "The node signatures must exactly match the node IDs from the graph data.";
    }

    /**
     * 发起网络请求，调用远程 LLM API。
     * 优化#2：在 finally 中确保 HTTP 连接断开
     * 优化#3：对 ErrorStream 进行 null 判断后再创建 Reader
     * 优化#11：使用可配置的 temperature 和 max_tokens
     */
    @SuppressWarnings("unchecked")
    private String callLLMAPI(String prompt) {
        int maxRetries = 3;
        int delayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; ++attempt) {
            HttpURLConnection conn = null;
            try {
                String endpoint = baseUrl;
                if (!endpoint.endsWith("/chat/completions")) {
                    if (endpoint.endsWith("/")) {
                        endpoint += "chat/completions";
                    } else {
                        endpoint += "/chat/completions";
                    }
                }
                URL url = new URL(endpoint);
                conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);

                JSONObject message = new JSONObject();
                message.put("role", "user");
                message.put("content", prompt);

                JSONArray messages = new JSONArray();
                messages.add(message);

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", modelName);
                requestBody.put("messages", messages);
                requestBody.put("temperature", this.temperature);
                requestBody.put("max_tokens", this.maxTokens);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toJSONString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder responseList = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            responseList.append(responseLine.trim());
                        }

                        try {
                            JSONParser parser = new JSONParser();
                            JSONObject jsonResponse = (JSONObject) parser.parse(responseList.toString());

                            if (jsonResponse != null && jsonResponse.containsKey("choices")) {
                                JSONArray choices = (JSONArray) jsonResponse.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    JSONObject firstChoice = (JSONObject) choices.get(0);
                                    if (firstChoice != null && firstChoice.containsKey("message")) {
                                        JSONObject msg = (JSONObject) firstChoice.get("message");
                                        if (msg != null && msg.containsKey("content")) {
                                            return (String) msg.get("content");
                                        }
                                    }
                                }
                            }
                            System.err.println("Warning: Unexpected JSON format from LLM response.");
                            System.err.println("Raw response: " + responseList.toString());

                        } catch (org.json.simple.parser.ParseException pe) {
                            System.err.println("Failed to parse JSON response from LLM:");
                            System.err.println("Raw response: " + responseList.toString());
                            pe.printStackTrace();
                        } catch (Exception processEx) {
                            System.err.println("Error processing JSON response:");
                            processEx.printStackTrace();
                        }
                    }
                    return null;
                } else {
                    System.err.println("LLM API Error (Attempt " + attempt + "): HTTP " + responseCode);
                    // 优化#3：先检查 ErrorStream 是否为 null，再创建 Reader
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                System.err.println(line);
                            }
                        }
                    }
                    if (attempt == maxRetries) {
                        System.err.println("Max retries reached. LLM request failed.");
                        return null;
                    }
                }
            } catch (java.net.SocketTimeoutException ste) {
                System.err.println("LLM API timeout (Attempt " + attempt + "): " + ste.getMessage());
                if (attempt == maxRetries) return null;
            } catch (Exception e) {
                System.err.println("LLM API generic error (Attempt " + attempt + "): " + e.getMessage());
                e.printStackTrace();
                if (attempt == maxRetries) return null;
            } finally {
                // 优化#2：确保 HTTP 连接在 finally 中断开
                if (conn != null) {
                    conn.disconnect();
                }
            }

            try {
                Thread.sleep(delayMs * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 解析方法：利用预编译正则表达式从 AI 回复中提取 Edge ID。
     * 优化#14：使用预编译的 Pattern 常量避免重复编译。
     * 优先匹配 EDGES: [...] 格式，否则回退到原有的最后一个方括号匹配。
     */
    private Set<String> extractEdgeIdsFromResponse(String response) {
        Set<String> edgeIds = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return edgeIds;
        }

        // 优先匹配 EDGES: [...] 格式（使用预编译常量）
        Matcher edgesMatcher = EDGES_PATTERN.matcher(response);
        String matchContent = null;
        if (edgesMatcher.find()) {
            matchContent = edgesMatcher.group(1);
        } else {
            // 回退：取最后一个方括号中的内容（排除 ENTRY_NODES 标记的部分，使用预编译常量）
            String responseWithoutEntryNodes = ENTRY_NODES_REMOVE_PATTERN.matcher(response).replaceAll("");
            Matcher matcher = BRACKET_CONTENT_PATTERN.matcher(responseWithoutEntryNodes);
            while (matcher.find()) {
                matchContent = matcher.group(1);
            }
        }

        if (matchContent != null) {
            String[] ids = matchContent.split(",");
            for (String id : ids) {
                String cleanId = id.trim().replaceAll("[\"']", "");
                if (!cleanId.isEmpty()) {
                    edgeIds.add(cleanId);
                }
            }
        } else {
            System.err.println("Warning: Could not find edge IDs in EDGES: [...] format or bracketed list. No edges extracted.");
        }
        return edgeIds;
    }

    /**
     * 从 LLM 响应中提取入口节点签名。
     * 优化#14：使用预编译的 Pattern 常量。
     * 优先匹配 ENTRY_NODES: [...] 格式。
     */
    private Set<String> extractEntryNodesFromResponse(String response) {
        Set<String> entryNodes = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return entryNodes;
        }

        Matcher entryMatcher = ENTRY_NODES_PATTERN.matcher(response);
        if (entryMatcher.find()) {
            String matchContent = entryMatcher.group(1);
            String[] nodes = matchContent.split(",");
            for (String node : nodes) {
                String cleanNode = node.trim().replaceAll("[\"']", "");
                if (!cleanNode.isEmpty()) {
                    entryNodes.add(cleanNode);
                }
            }
        } else {
            System.err.println("Warning: Could not find ENTRY_NODES in LLM response. No entry nodes extracted.");
        }
        return entryNodes;
    }

    /**
     * 根据 LLM 提供需要保留的 EdgeID 集合，重新构建子图。
     */
    private DirectedPseudograph<EntityNode, EventEdge> buildFilteredGraph(
            DirectedPseudograph<EntityNode, EventEdge> originalGraph,
            Set<String> keptEdgeIds) {

        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = new DirectedPseudograph<>(EventEdge.class);

        for (EventEdge edge : originalGraph.edgeSet()) {
            if (keptEdgeIds.contains(String.valueOf(edge.getID()))) {
                EntityNode source = originalGraph.getEdgeSource(edge);
                EntityNode target = originalGraph.getEdgeTarget(edge);

                if (!filteredGraph.containsVertex(source)) filteredGraph.addVertex(source);
                if (!filteredGraph.containsVertex(target)) filteredGraph.addVertex(target);

                filteredGraph.addEdge(source, target, edge);
            }
        }
        return filteredGraph;
    }

    /**
     * 以 POI 节点和 LLM 识别的入口节点为基础，确保每个入口节点到 POI 都有路径。
     *
     * @param originalGraph    原始的完整溯源图
     * @param filteredGraph    LLM 筛选出来的攻击子图
     * @param llmEntryNodes    LLM 认为正确的入口节点集合
     * @param poiNode          POI（检测点）对应的节点
     */
    private void ensureConnectivity(DirectedPseudograph<EntityNode, EventEdge> originalGraph,
                                    DirectedPseudograph<EntityNode, EventEdge> filteredGraph,
                                    Set<EntityNode> llmEntryNodes,
                                    EntityNode poiNode) {

        if (filteredGraph.vertexSet().size() <= 1) {
            return;
        }

        AsUndirectedGraph<EntityNode, EventEdge> undirectedOriginal = new AsUndirectedGraph<>(originalGraph);
        DijkstraShortestPath<EntityNode, EventEdge> dijkstra = new DijkstraShortestPath<>(undirectedOriginal);

        // ============ Phase 1: Ensure each LLM entry node has a path to POI ============
        if (poiNode != null && !llmEntryNodes.isEmpty()) {
            System.out.println("Phase 1: Ensuring each LLM entry node has a path to POI...");

            for (EntityNode entryNode : llmEntryNodes) {
                AsUndirectedGraph<EntityNode, EventEdge> curUndirected = new AsUndirectedGraph<>(filteredGraph);
                ConnectivityInspector<EntityNode, EventEdge> curInspector = new ConnectivityInspector<>(curUndirected);

                if (filteredGraph.containsVertex(entryNode) && filteredGraph.containsVertex(poiNode)
                        && curInspector.pathExists(entryNode, poiNode)) {
                    System.out.println("  Entry node [" + entryNode.getSignature() + "] already connected to POI.");
                    continue;
                }

                try {
                    GraphPath<EntityNode, EventEdge> path = dijkstra.getPath(entryNode, poiNode);
                    if (path != null) {
                        System.out.println("  Patching path from entry [" + entryNode.getSignature()
                                + "] to POI [" + poiNode.getSignature() + "], length=" + path.getLength());
                        addPathToGraph(originalGraph, filteredGraph, path);
                    } else {
                        System.err.println("  Warning: No path found from entry node [" + entryNode.getSignature()
                                + "] to POI in original graph.");
                    }
                } catch (Exception e) {
                    System.err.println("  Error finding path for entry [" + entryNode.getSignature() + "]: " + e.getMessage());
                }
            }
        } else {
            if (poiNode == null) {
                System.err.println("Warning: POI node not found in original graph. Skipping entry-to-POI patching.");
            }
            if (llmEntryNodes.isEmpty()) {
                System.out.println("No LLM entry nodes identified. Skipping entry-to-POI patching.");
            }
        }

        // ============ Phase 2: Stitch remaining disconnected components ============
        AsUndirectedGraph<EntityNode, EventEdge> undirectedFiltered = new AsUndirectedGraph<>(filteredGraph);
        ConnectivityInspector<EntityNode, EventEdge> finalInspector = new ConnectivityInspector<>(undirectedFiltered);
        List<Set<EntityNode>> connectedComponents = finalInspector.connectedSets();

        if (connectedComponents.size() <= 1) {
            System.out.println("Graph is fully connected after entry-to-POI patching.");
            return;
        }

        System.out.println("Phase 2: " + connectedComponents.size() + " components remain. Stitching disconnected components...");

        Set<EntityNode> mainComponent = null;
        if (poiNode != null) {
            for (Set<EntityNode> comp : connectedComponents) {
                if (comp.contains(poiNode)) {
                    mainComponent = new HashSet<>(comp);
                    break;
                }
            }
        }
        if (mainComponent == null) {
            mainComponent = new HashSet<>(connectedComponents.stream()
                    .max(Comparator.comparingInt(Set::size))
                    .orElse(connectedComponents.get(0)));
        }

        for (Set<EntityNode> targetComponent : connectedComponents) {
            if (mainComponent.containsAll(targetComponent)) {
                continue;
            }

            GraphPath<EntityNode, EventEdge> bestPath = null;
            int minPathLength = Integer.MAX_VALUE;

            for (EntityNode sourceNode : mainComponent) {
                ShortestPathAlgorithm.SingleSourcePaths<EntityNode, EventEdge> paths = dijkstra.getPaths(sourceNode);
                for (EntityNode targetNode : targetComponent) {
                    GraphPath<EntityNode, EventEdge> path = paths.getPath(targetNode);
                    if (path != null && path.getLength() < minPathLength) {
                        minPathLength = path.getLength();
                        bestPath = path;
                    }
                }
            }

            if (bestPath != null) {
                addPathToGraph(originalGraph, filteredGraph, bestPath);
            } else {
                System.err.println("Warning: Could not find a path to connect a disconnected component in the original graph.");
            }

            mainComponent.addAll(targetComponent);
        }

        System.out.println("Graph patching complete.");
    }

    /**
     * 将一条路径上的所有边和节点添加到过滤图中。
     */
    private void addPathToGraph(DirectedPseudograph<EntityNode, EventEdge> originalGraph,
                                DirectedPseudograph<EntityNode, EventEdge> filteredGraph,
                                GraphPath<EntityNode, EventEdge> path) {
        for (EventEdge edge : path.getEdgeList()) {
            EntityNode edgeSource = originalGraph.getEdgeSource(edge);
            EntityNode edgeTarget = originalGraph.getEdgeTarget(edge);

            if (!filteredGraph.containsVertex(edgeSource)) filteredGraph.addVertex(edgeSource);
            if (!filteredGraph.containsVertex(edgeTarget)) filteredGraph.addVertex(edgeTarget);

            if (!filteredGraph.containsEdge(edge)) {
                filteredGraph.addEdge(edgeSource, edgeTarget, edge);
            }
        }
    }
}
