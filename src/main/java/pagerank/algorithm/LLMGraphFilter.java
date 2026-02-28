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

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private boolean llmEnabled;

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
        } catch (Exception e) {
            System.err.println("Warning: Could not load llm.properties. Using default values.");
            this.llmEnabled = false;
            this.baseUrl = "";
            this.apiKey = "";
            this.modelName = "";
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

        // 4. 生成 Prompt
        String prompt = buildPrompt(graphText, filteredEntryPoints, poiEvent);

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

        Set<String> llmEntryNodes = extractEntryNodesFromResponse(llmResponse);
        System.out.println("LLM identified " + llmEntryNodes.size() + " entry nodes.");

        // 8. 重建图
        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = buildFilteredGraph(originalGraph, keptEdgeIds);
// ====================== 新增：生成 LLM 原始输出的中间 SVG ======================
        try {
            // 仿照你提供的代码命名风格，生成中间结果路径
            // 将 .log 后缀替换为 _llm_raw 以示区分
            String intermediatePath = logFilePath.replace(".log", "_llm_raw");

            // 假设你的工程中有一个通用的 IterateGraph 类用于处理导出
            // 注意：IterateGraph 内部应该已经封装了导出 .dot 的逻辑
            IterateGraph intermediateOut = new IterateGraph(filteredGraph);
            intermediateOut.exportGraph(intermediatePath);

            // 调用你代码库中现有的 DotToSvg 静态方法
            // 假设该方法定义在某个工具类中，如果是本类定义的请直接调用，如果在其他类请加类名如：YourUtils.DotToSvg
            DotToSvg(intermediatePath + ".dot", intermediatePath + ".svg");

            System.out.println("LLM raw output visualization saved: " + intermediatePath + ".svg");
        } catch (Exception e) {
            System.err.println("Failed to generate intermediate LLM SVG: " + e.getMessage());
        }
        // ===========================================================================
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
     * 写入 LLM 交互日志
     */
    private void writeLLMLog(String logFilePath, String prompt, String response) {
        try {
            java.io.File logFile = new java.io.File(logFilePath);
            java.io.FileWriter writer = new java.io.FileWriter(logFile);
            writer.write("================ LLM PROMPT ================\n");
            writer.write(prompt);
            writer.write("\n\n================ LLM RESPONSE ================\n");
            writer.write(response != null ? response : "NULL (Request failed or returned empty)");
            writer.write("\n==============================================\n");
            writer.close();
            System.out.println("LLM Interaction log saved to: " + logFilePath);
        } catch (Exception e) {
            System.err.println("Failed to write LLM log file: " + e.getMessage());
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
     * 强调让 LLM 输出绝对属于攻击路径的边，以及它认为正确的入口节点。
     */
    private String buildPrompt(String graphText, List<String> entryPoints, String poiEvent) {
        return "You are a cybersecurity expert analyzing a provenance graph to track an attack. " +
                "The graph is provided in a structured JSON format describing system entities (nodes) and events (edges).\n\n" +
                "The known attack target (POI) is:\n" + poiEvent + "\n\n" +
                "Possible attack entry points:\n" + String.join(", ", entryPoints) + "\n\n" +
                "Graph Data (JSON):\n" + graphText + "\n\n" +
                "Your Task:\n" +
                "1. Analyze the causal relationships carefully.\n" +
                "2. Identify ONLY the edges that you are ABSOLUTELY CERTAIN belong to the malicious attack sequence.\n" +
                "3. From the possible attack entry points listed above, identify which ones you believe are the TRUE attack entry points " +
                "(i.e., the actual origin of the attack). You may select one or more.\n" +
                "4. DO NOT hallucinate or include borderline/noisy edges just to make the path fully connected. " +
                "It is PERFECTLY FINE (and expected) if the edges you select are disconnected from each other. Focus ONLY on high-confidence malicious behavior.\n\n" +
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
     */
    @SuppressWarnings("unchecked")
    private String callLLMAPI(String prompt) {
        int maxRetries = 3;
        int delayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; ++attempt) {
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
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

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
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 20480);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toJSONString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
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
                    return null;
                } else {
                    System.err.println("LLM API Error (Attempt " + attempt + "): HTTP " + responseCode);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"));
                    String line;
                    if (br != null) {
                        while ((line = br.readLine()) != null) {
                            System.err.println(line);
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
     * 解析方法：利用正则表达式从 AI 回复中提取 Edge ID。
     * 优先匹配 EDGES: [...] 格式，否则回退到原有的最后一个方括号匹配。
     */
    private Set<String> extractEdgeIdsFromResponse(String response) {
        Set<String> edgeIds = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return edgeIds;
        }

        // 优先匹配 EDGES: [...] 格式
        Pattern edgesPattern = Pattern.compile("EDGES:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
        Matcher edgesMatcher = edgesPattern.matcher(response);
        String matchContent = null;
        if (edgesMatcher.find()) {
            matchContent = edgesMatcher.group(1);
        } else {
            // 回退：取最后一个方括号中的内容（排除 ENTRY_NODES 标记的部分）
            String responseWithoutEntryNodes = response.replaceAll("(?i)ENTRY_NODES:\\s*\\[[^\\]]*\\]", "");
            Pattern pattern = Pattern.compile("\\[([a-zA-Z0-9_\\-\\s,\"']+)\\]");
            Matcher matcher = pattern.matcher(responseWithoutEntryNodes);
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
            System.err.println("Warning: Could not find edge IDs in expected format. Attempting fallback extraction.");
            Pattern fallbackPattern = Pattern.compile("\\b(\\d+)\\b");
            Matcher fallbackMatcher = fallbackPattern.matcher(response);
            while (fallbackMatcher.find()) {
                edgeIds.add(fallbackMatcher.group(1));
            }
        }
        return edgeIds;
    }

    /**
     * 从 LLM 响应中提取入口节点签名。
     * 优先匹配 ENTRY_NODES: [...] 格式。
     */
    private Set<String> extractEntryNodesFromResponse(String response) {
        Set<String> entryNodes = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return entryNodes;
        }

        Pattern entryPattern = Pattern.compile("ENTRY_NODES:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
        Matcher entryMatcher = entryPattern.matcher(response);
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
