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

import static pagerank.main.ProcessOneLogCMD_19.DotToSvg;

/**
 * LLMGraphFilter 模块：利用大语言模型 (LLM) 进行溯源图降噪和路径提取。
 * 1. 将图结构序列化为 LLM 可读的结构化 JSON。
 * 2. 引导 LLM 提取绝对确定的攻击边（允许不连通）。
 * 3. 算法后处理：如果提取出的子图不连通，在原图中寻找最短路径进行补全。
 */
public class LLMGraphFilter {

    private String baseUrl;
    private String apiKey;
    private String modelName;

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
            this.baseUrl = prop.getProperty("base_url", "");
            this.apiKey = prop.getProperty("api_key", "");
            this.modelName = prop.getProperty("model", "");
        } catch (Exception e) {
            System.err.println("Warning: Could not load llm.properties. Using default values.");
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

        // 7. 解析响应
        Set<String> keptEdgeIds = extractEdgeIdsFromResponse(llmResponse);
        System.out.println("LLM selected " + keptEdgeIds.size() + " edges to keep.");

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
        // 9. 连通性检查与后处理补全算法
        ensureConnectivity(originalGraph, filteredGraph);

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
     * 强调让 LLM 输出绝对属于攻击路径的边，且明确允许最终结果不连通。
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
                "3. DO NOT hallucinate or include borderline/noisy edges just to make the path fully connected. " +
                "It is PERFECTLY FINE (and expected) if the edges you select are disconnected from each other. Focus ONLY on high-confidence malicious behavior.\n\n" +
                "Output the IDs of the exact edges you consider part of the attack. " +
                "At the end of your response, provide a comma-separated list of the kept EdgeIDs enclosed in brackets like this: [EdgeID1, EdgeID2, ...]";
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
     * 解析方法：利用正则表达式从 AI 回复的繁杂文字中精准提取出 Edge ID。
     */
    private Set<String> extractEdgeIdsFromResponse(String response) {
        Set<String> edgeIds = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return edgeIds;
        }

        Pattern pattern = Pattern.compile("\\[([a-zA-Z0-9_\\-\\s,\"']+)\\]");
        Matcher matcher = pattern.matcher(response);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }

        if (lastMatch != null) {
            String[] ids = lastMatch.split(",");
            for (String id : ids) {
                String cleanId = id.trim().replaceAll("[\"']", "");
                if (!cleanId.isEmpty()) {
                    edgeIds.add(cleanId);
                }
            }
        } else {
            System.err.println("Warning: Could not find edge IDs in expected bracket format. Attempting fallback extraction.");
            Pattern fallbackPattern = Pattern.compile("\\b(\\d+)\\b");
            Matcher fallbackMatcher = fallbackPattern.matcher(response);
            while (fallbackMatcher.find()) {
                edgeIds.add(fallbackMatcher.group(1));
            }
        }
        return edgeIds;
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
     * 检查 LLM 输出的子图是否连通（忽略方向）。如果不连通，则在原图中寻找最短路径，
     * 将断开的连通分量重新缝合在一起。
     * * 核心思想：贪心算法。维护一个“主连通块”，依次计算其与其它游离连通块之间的最短距离，
     * 找到最短的桥接路径后，将该路径上的缺失节点和边补充到子图中，直至所有分量融为一体。
     *
     * @param originalGraph 原始的完整溯源图（包含所有系统行为日志）
     * @param filteredGraph LLM 筛选出来的攻击子图（可能包含多个不相交的连通分量）
     */
    private void ensureConnectivity(DirectedPseudograph<EntityNode, EventEdge> originalGraph,
                                    DirectedPseudograph<EntityNode, EventEdge> filteredGraph) {

        // 边界条件防御：如果过滤后的图中没有节点，或者只有一个节点，那它天生就是连通的，直接返回
        if (filteredGraph.vertexSet().size() <= 1) {
            return;
        }

        // 1. 连通性检测（忽略边的方向）
        // 因为攻击路径中的边可能有正向（如进程创建子进程）和反向（如进程读取文件），
        // 我们只关心它们在物理拓扑上是否相连，所以将其包装为无向图视图 (AsUndirectedGraph)
        AsUndirectedGraph<EntityNode, EventEdge> undirectedFiltered = new AsUndirectedGraph<>(filteredGraph);

        // 实例化连通性检测器
        ConnectivityInspector<EntityNode, EventEdge> inspector = new ConnectivityInspector<>(undirectedFiltered);

        // 获取所有的连通分量（Connected Components）。
        // 每一个 Set<EntityNode> 代表一个孤立的小岛（岛内部的节点是连通的，岛与岛之间断开）
        List<Set<EntityNode>> connectedComponents = inspector.connectedSets();

        // 如果孤岛数量小于等于1，说明整个图已经是一个完整的连通块，无需修补
        if (connectedComponents.size() <= 1) {
            System.out.println("LLM generated graph is already fully connected.");
            return;
        }

        System.out.println("Detected " + connectedComponents.size() + " disconnected components. Patching missing edges...");

        // 2. 准备寻路算法工具
        // 同样忽略原图的方向，因为攻击链的上下文补充可能需要逆向追溯
        AsUndirectedGraph<EntityNode, EventEdge> undirectedOriginal = new AsUndirectedGraph<>(originalGraph);
        // 在原图上实例化 Dijkstra 最短路径算法对象，用于后续寻找“桥梁”
        DijkstraShortestPath<EntityNode, EventEdge> dijkstra = new DijkstraShortestPath<>(undirectedOriginal);

        // 3. 执行贪心缝合策略
        // 将第一个连通块作为“主块”（根据地）。接下来的目标是把其他的块逐个拉拢合并到这个主块中。
        Set<EntityNode> mainComponent = new HashSet<>(connectedComponents.get(0));

        // 遍历剩下所有需要被合并的游离块
        for (int i = 1; i < connectedComponents.size(); i++) {
            Set<EntityNode> targetComponent = connectedComponents.get(i);

            GraphPath<EntityNode, EventEdge> bestPath = null;
            int minPathLength = Integer.MAX_VALUE;

            // 双层循环寻找主块与当前游离块之间的“全局最短路径”
            // 外层循环：遍历主块中的每一个节点，将其作为寻路起点
            for (EntityNode sourceNode : mainComponent) {
                // 计算该起点到原图中其他所有节点的最短路径树（缓存起来以提高效率）
                ShortestPathAlgorithm.SingleSourcePaths<EntityNode, EventEdge> paths = dijkstra.getPaths(sourceNode);

                // 内层循环：遍历游离块中的每一个节点，将其作为寻路终点
                for (EntityNode targetNode : targetComponent) {
                    // 获取从 sourceNode 到 targetNode 的具体路径
                    GraphPath<EntityNode, EventEdge> path = paths.getPath(targetNode);

                    // 如果存在路径，且比当前记录的“最短桥梁”还要短，则更新记录
                    if (path != null && path.getLength() < minPathLength) {
                        minPathLength = path.getLength();
                        bestPath = path;
                    }
                }
            }

            // 4. 将最短路径（桥梁）上的节点和边补充进过滤后的图中
            if (bestPath != null) {
                // 遍历这条补全路径上的每一条边
                for (EventEdge missingEdge : bestPath.getEdgeList()) {
                    // 提取这条边两端的节点（使用原图的方法获取正确的起点和终点，保留原有向图的方向性）
                    EntityNode edgeSource = originalGraph.getEdgeSource(missingEdge);
                    EntityNode edgeTarget = originalGraph.getEdgeTarget(missingEdge);

                    // 如果过滤图中还没有这两个节点，先将其加入图中（防止添加边时报 NoSuchVertexException）
                    if (!filteredGraph.containsVertex(edgeSource)) filteredGraph.addVertex(edgeSource);
                    if (!filteredGraph.containsVertex(edgeTarget)) filteredGraph.addVertex(edgeTarget);

                    // 如果这条边也不在过滤图中，则将其添加进去
                    if (!filteredGraph.containsEdge(missingEdge)) {
                        filteredGraph.addEdge(edgeSource, edgeTarget, missingEdge);
                    }

                    // 动态扩充主块的势力范围：
                    // 把桥梁路径上的节点也加入主块，这样在下一轮循环合并其他游离块时，
                    // 新加入的节点也可以作为寻找更短路径的起点
                    mainComponent.add(edgeSource);
                    mainComponent.add(edgeTarget);
                }
            } else {
                // 极端情况：原图本身就是断开的，主块和目标块之间在物理上没有任何路径相连
                System.err.println("Warning: Could not find a path to connect component " + i + " in the original graph.");
            }

            // 本轮缝合结束，无论是否成功找到路径，都把目标块中的所有节点并入主块，
            // 确保所有的节点在下一轮都能作为起点，参与后续的缝合计算
            mainComponent.addAll(targetComponent);
        }

        System.out.println("Graph patching complete.");
    }
}