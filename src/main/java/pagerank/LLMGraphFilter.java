package pagerank;

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
 * 基于大语言模型 (LLM) 的溯源图过滤模块。
 * 该类负责将系统生成的溯源图转化为文本 Prompt，并调用大语言模型 API（如 OpenAI, NVIDIA DeepSeek 等）
 * 来分析核心的因果关系，并提取出最有可能属于真实攻击路径上的边，从而过滤除掉背景噪音点和边。
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
     * 如果文件不存在或发生读取异常，将降级使用空值处理。
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

        System.out.println("Starting LLM graph filtering...");
        String graphText = serializeGraph(originalGraph);

        // 排查和剔除可能的起点中等同于 POI（终点） 的干扰项
        List<String> filteredEntryPoints = new ArrayList<>();
        for (String entry : entryPoints) {
            if (!entry.equals(poiEvent)) {
                filteredEntryPoints.add(entry);
            }
        }

        String prompt = buildPrompt(graphText, filteredEntryPoints, poiEvent);

        String llmResponse = callLLMAPI(prompt);

        // 记录 Prompt 和 LLM Response 到文件
        writeLLMLog(logFilePath, prompt, llmResponse);

        if (llmResponse == null || llmResponse.isEmpty()) {
            System.err.println("LLM response was empty or failed. Returning original graph.");
            return originalGraph;
        }

        Set<String> keptEdgeIds = extractEdgeIdsFromResponse(llmResponse);
        System.out.println("LLM selected " + keptEdgeIds.size() + " edges to keep.");

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
     * 将图形数据结构序列化成便于 LLM 理解的纯文本格式。
     * 首先列出所有图中的节点对象（包含签名和节点类型），
     * 随后列出图中所有的边对象（包含边的唯一 ID、起点、终点、系统事件类型和发生时间）。
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
                "The graph might contain noise (normal system activities) alongside the true attack path.\n\n" +
                "The known attack target is:\n" +
                poiEvent + "\n\n" +
                "Here are the possible attack entry points:\n" +
                String.join(", ", entryPoints) + "\n\n" +
                "Here is the provenance graph data:\n" + graphText + "\n\n" +
                "Please analyze the causal relationships and identify the critical path starting from any of the possible attack entry points and ending at the known attack target. "
                +
                "This path represents the core attack behavior. Ignore irrelevant noise edges.\n" +
                "Output the IDs of the edges that belong to the true attack path. " +
                "Format your output clearly, and at the end of your response, provide a comma-separated list of the kept EdgeIDs enclosed in brackets like this: [EdgeID1, EdgeID2, ...]";
    }

    /**
     * 调用符合 OpenAI 格式规范的 LLM API 端点发送 Prompt，并提取并返回模型的文本回复内容。
     * 支持自动补齐 "/chat/completions" 路由。
     * 
     * @param prompt 提交给模型的完整提示词文本
     * @return LLM 返还的文本数据。如果发生网络请求失败或解析出错则返回 null
     */
    @SuppressWarnings("unchecked")
    private String callLLMAPI(String prompt) {
        try {
            // NVIDIA deepseek endpoint may be exact or missing chat/completions depending
            // on base url format
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
            requestBody.put("max_tokens", 4096);

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

                JSONParser parser = new JSONParser();
                JSONObject jsonResponse = (JSONObject) parser.parse(responseList.toString());
                JSONArray choices = (JSONArray) jsonResponse.get("choices");
                if (choices != null && choices.size() > 0) {
                    JSONObject firstChoice = (JSONObject) choices.get(0);
                    JSONObject msg = (JSONObject) firstChoice.get("message");
                    return (String) msg.get("content");
                }
            } else {
                System.err.println("LLM API Error: HTTP " + responseCode);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"));
                String line;
                if (br != null) {
                    while ((line = br.readLine()) != null) {
                        System.err.println(line);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 通过正则表达式从 LLM 自由文本响应中，匹配并提取出模型指定保留的 EdgeID 列表。
     * 模型应在回复的一开始或是末尾包含形如：[123, 124, 60] 这样的格式。
     * 此方法会自动抽取满足这一规则的数据并建立去重的集合。
     */
    private Set<String> extractEdgeIdsFromResponse(String response) {
        Set<String> edgeIds = new HashSet<>();
        // Look for [id1, id2, ...]
        Pattern pattern = Pattern.compile("\\[([a-zA-Z0-9_\\-\\s,]+)\\]");
        Matcher matcher = pattern.matcher(response);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }

        if (lastMatch != null) {
            String[] ids = lastMatch.split(",");
            for (String id : ids) {
                edgeIds.add(id.trim());
            }
        }
        return edgeIds;
    }

    /**
     * 根据 LLM 提供需要保留的 EdgeID 集合，基于原始溯源图重新构建并返回一个新的被过滤的图对象。
     * 遍历原始图中的边，如果该边的 ID 在保留集合内，则将其自身以及其相连的端点节点复制到新的子图中。
     * 
     * @param originalGraph 原始的大图对象
     * @param keptEdgeIds   LLM 判定需要保留并且属于因果攻击路径的边 ID 集合
     * @return 新生成的精简后的溯源子图
     */
    private DirectedPseudograph<EntityNode, EventEdge> buildFilteredGraph(
            DirectedPseudograph<EntityNode, EventEdge> originalGraph,
            Set<String> keptEdgeIds) {

        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = new DirectedPseudograph<>(EventEdge.class);

        // Convert keptEdgeIds to Integer or String comparison depending on how event id
        // is stored
        // event.getID() might be int or long. Let's compare as String.
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

        // Keep isolated vertices if we strictly want them, but usually filtered graph
        // only needs connected ones.
        return filteredGraph;
    }
}
