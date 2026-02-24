package pagerank.algorithm;

import pagerank.entity.EntityNode;
import pagerank.entity.Event;
import pagerank.entity.EventEdge;

import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLMGraphFilter 模块：利用大语言模型 (LLM) 进行溯源图降噪和路径提取。
 * 1. 将图结构序列化为 LLM 可读的文本（Nodes & Edges）。
 * 2. 构建包含安全上下文的 Prompt，引导 LLM 识别攻击因果链。
 * 3. 调用外部 API（支持 OpenAI 格式）获取分析结果。
 * 4. 解析结果并重建一个精简的、仅包含关键攻击步骤的子图。
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
     * 包含对 base_url、api_key 以及对应模型名称的读取。
     * 如果文件不存在或发生读取异常，后续逻辑将通过判空跳过 LLM 过滤
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
     * 调用该方法会依次执行图表序列化、Prompt 构建、LLM 远端请求、以及根据返回结果重建图表的过程。
     * 将提示词和 LLM 的原始回复日志保存在指定路径的文件中。
     *
     * @param originalGraph 原始由系统前/后向分析生成的完整溯源图
     * @param entryPoints   可能的攻击入口点列表
     * @param poiEvent      系统输入的POI事件（警报节点），作为攻击路径的终点
     * @param logFilePath   用于保存 LLM 请求和回复内容的日志文件路径
     * @return 经过 LLM 分析和过滤后的新生溯源图（仅包含识别出的关键边和相关联的节点）
     */
    public DirectedPseudograph<EntityNode, EventEdge> filterGraph(
            DirectedPseudograph<EntityNode, EventEdge> originalGraph,
            List<String> entryPoints,
            String poiEvent,
            String logFilePath) {
        // 1. 安全检查：如果配置无效，则不执行过滤，直接返回原图，确保程序鲁棒性
        if (this.baseUrl == null || this.baseUrl.trim().isEmpty() ||
                this.apiKey == null || this.apiKey.trim().isEmpty() ||
                this.modelName == null || this.modelName.trim().isEmpty()) {
            System.err.println(
                    "LLM API configuration is missing or incomplete. Skipping LLM filtering and returning the original graph.");
            return originalGraph;
        }

        System.out.println("Starting LLM graph filtering...");
        // 2. 将图对象转换为文本描述格式
        String graphText = serializeGraph(originalGraph);

        // 3. 数据预处理：如果入口点和终点是同一个，则移除该入口点以防干扰 AI 判断
        List<String> filteredEntryPoints = new ArrayList<>();
        for (String entry : entryPoints) {
            if (!entry.equals(poiEvent)) {
                filteredEntryPoints.add(entry);
            }
        }

        // 4. 生成 Prompt：告诉 AI 它是专家，给它数据，并要求它输出特定格式的结果
        String prompt = buildPrompt(graphText, filteredEntryPoints, poiEvent);

        // 5. 联网调用 LLM：获取模型对攻击路径的判定结果
        String llmResponse = callLLMAPI(prompt);

        // 6. 日志记录：将交互记录存入文件，方便 Debug 和复盘
        writeLLMLog(logFilePath, prompt, llmResponse);

        if (llmResponse == null || llmResponse.isEmpty()) {
            System.err.println("LLM response was empty or failed. Returning original graph.");
            return originalGraph;
        }

        // 7. 解析响应：从 AI 的自由文本回复中利用正则提取出需要保留的 Edge ID
        Set<String> keptEdgeIds = extractEdgeIdsFromResponse(llmResponse);
        System.out.println("LLM selected " + keptEdgeIds.size() + " edges to keep.");

        // 8. 重建图：根据 AI 筛选出的边 ID，从原图中抽取出子图，并补充入口点的所有可达出边
        return buildFilteredGraph(originalGraph, keptEdgeIds);
    }

    /**
     * 将发送给 LLM 的 Prompt 以及 LLM 返回的原始内容写入到日志文件中。
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
     * 序列化方法：将内存中的图结构转换为结构化的纯文本。
     * LLM 的理解能力受上下文长度限制，因此只保留关键字段：
     * - Nodes: 实体签名和类型。
     * - Edges: 唯一ID、起点、终点、系统调用类型、时间戳。
     */
    private String serializeGraph(DirectedPseudograph<EntityNode, EventEdge> graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("Nodes:\n");
        for (EntityNode node : graph.vertexSet()) {
            sb.append("- ").append(node.getSignature()).append(" (Type: ").append(node.getClass().getSimpleName())
                    .append(", Name: ").append(node.getSignature()).append(")\n");
        }
        sb.append("\nEdges:\n");
        for (EventEdge edge : graph.edgeSet()) {
            EntityNode source = graph.getEdgeSource(edge);
            EntityNode target = graph.getEdgeTarget(edge);
            sb.append("- EdgeID: ").append(edge.getID())
                    .append(" | Source: ").append(source.getSignature())
                    .append(" | Target: ").append(target.getSignature())
                    .append(" | Event: ").append(edge.getEvent())
                    .append(" | Time: ").append(edge.getStartTime())
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 根据序列化后的图表文本数据和入口点列表，结合预设的系统提示，构建发送给 LLM 的 Prompt 提示词。
     * 提示词中明确赋予了模型网络安全专家的身份设定，清晰界定了任务目标，并且对输出的结构也做出了强约束。
     */
    private String buildPrompt(String graphText, List<String> entryPoints, String poiEvent) {
        return "You are a cybersecurity expert analyzing a provenance graph to track an attack. " +
                "The graph describes system entities (processes, files, networks) and events (syscalls) between them. "
                +
                "The graph might contain noise (normal system activities) alongside the true attack behavior.\n\n" +
                "The known attack target is:\n" +
                poiEvent + "\n\n" +
                "Here are the possible attack entry points:\n" +
                String.join(", ", entryPoints) + "\n\n" +
                "Here is the provenance graph data:\n" + graphText + "\n\n" +
                "Please analyze the causal relationships and identify the entire attack subgraph that connects any of the possible attack entry points to the known attack target. "
                +
                "Keep in mind that the attack behavior might not be a single strict path. It can involve multiple branches, such as creating, reading, moving, or archiving intermediate sensitive files, or spawning various processes. "
                +
                "Ignore irrelevant noise edges.\n"
                +
                "Output the IDs of the edges that belong to the true attack subgraph. " +
                "Format your output clearly, and at the end of your response, provide a comma-separated list of the kept EdgeIDs enclosed in brackets like this: [EdgeID1, EdgeID2, ...]";
    }

    /**
     * 发起网络请求，调用远程 LLM API。
     * 实现细节：
     * - 自动补齐 OpenAI 标准的 /chat/completions 路由。
     * - 设置了连接和读取超时。
     * - 实现了指数退避（Exponential Backoff）的重试机制。
     */
    @SuppressWarnings("unchecked")
    private String callLLMAPI(String prompt) {
        int maxRetries = 3;// 最大重试次数
        int delayMs = 2000;// 基础延迟时间

        for (int attempt = 1; attempt <= maxRetries; ++attempt) {
            try {
                // 1. 构建 API 端点 URL
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
                // 2. 设置 HTTP 请求头
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(10000); // 10秒连接超时
                conn.setReadTimeout(60000); // 60秒读取超时
                conn.setDoOutput(true);

                // 3. 构建 JSON 请求体
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

                // 4. 发送数据
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toJSONString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                // 5. 处理响应
                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                    StringBuilder responseList = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        responseList.append(responseLine.trim());
                    }

                    // 6. 解析嵌套的 JSON (choices -> 0 -> message -> content)
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

                    // 如果 JSON 解析失败但请求本身成功，说明大概率是返回结构错误，我们不再重试，直接返回 null。
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
                if (attempt == maxRetries) {
                    return null;
                }
            } catch (Exception e) {
                System.err.println("LLM API generic error (Attempt " + attempt + "): " + e.getMessage());
                e.printStackTrace();
                if (attempt == maxRetries) {
                    return null;
                }
            }

            // 在重试前等待
            try {
                Thread.sleep(delayMs * attempt); // 指数退避策略
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 解析方法：利用正则表达式从 AI 回复的繁杂文字中精准提取出 Edge ID。
     * 主要匹配格式： [ID1, ID2, ID3]
     */
    private Set<String> extractEdgeIdsFromResponse(String response) {
        Set<String> edgeIds = new HashSet<>();
        if (response == null || response.isEmpty()) {
            return edgeIds;
        }

        // 正则表达式说明：
        // 查找以 [ 开头，] 结尾的部分，并捕获中间包含字母数字、逗号、引号的内容
        Pattern pattern = Pattern.compile("\\[([a-zA-Z0-9_\\-\\s,\"']+)\\]");
        Matcher matcher = pattern.matcher(response);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }

        if (lastMatch != null) {
            String[] ids = lastMatch.split(",");
            for (String id : ids) {
                String cleanId = id.trim().replaceAll("[\"']", ""); // 移除多余的引号
                if (!cleanId.isEmpty()) {
                    edgeIds.add(cleanId);
                }
            }
        } else {
            // 降级策略（Fallback）：如果没有找到预期的方括号格式，尝试提取任何连续的数字序列作为潜在的边 ID
            System.err.println(
                    "Warning: Could not find edge IDs in expected bracket format. Attempting fallback extraction.");
            Pattern fallbackPattern = Pattern.compile("\\b(\\d+)\\b");
            Matcher fallbackMatcher = fallbackPattern.matcher(response);
            while (fallbackMatcher.find()) {
                edgeIds.add(fallbackMatcher.group(1));
            }
        }
        return edgeIds;
    }

    /**
     * 根据 LLM 提供需要保留的 EdgeID 集合，基于原始溯源图重新构建并返回一个新的被过滤的图对象。
     * 遍历原始图中的边，如果该边的 ID 在保留集合内，则将其自身以及其相连的端点节点复制到新的子图中。
     *
     * 同时进行后处理：补充保留从入口点可达的节点的所有出边，确保不遗漏任何攻击分支。
     *
     * @param originalGraph 原始的大图对象
     * @param keptEdgeIds   LLM 判定需要保留并且属于因果攻击路径的边 ID 集合
     * @return 新生成的精简后的溯源子图
     */
    private DirectedPseudograph<EntityNode, EventEdge> buildFilteredGraph(
            DirectedPseudograph<EntityNode, EventEdge> originalGraph,
            Set<String> keptEdgeIds) {

        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = new DirectedPseudograph<>(EventEdge.class);

        // 根据 event id 的存储方式，将 keptEdgeIds 转换为 Integer 或 String 进行比较。
        // event.getID() 可能是 int 或是 long。安全起见我们直接转为 String 对比。
        for (EventEdge edge : originalGraph.edgeSet()) {
            if (keptEdgeIds.contains(String.valueOf(edge.getID()))) {
                EntityNode source = originalGraph.getEdgeSource(edge);
                EntityNode target = originalGraph.getEdgeTarget(edge);

                if (!filteredGraph.containsVertex(source)) {
                    filteredGraph.addVertex(source);
                }
                if (!filteredGraph.containsVertex(target)) {
                    filteredGraph.addVertex(target);
                }
                filteredGraph.addEdge(source, target, edge);
            }
        }

        // 如果严格要求则可以保留孤立的点，但通常过滤后的图只需要连通的节点。
        return filteredGraph;
    }
}
