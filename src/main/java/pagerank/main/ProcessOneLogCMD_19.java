package pagerank.main;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.algorithm.BackwardPropagate_pf;
import pagerank.algorithm.IterateGraph;
import pagerank.algorithm.GetGraph;
import pagerank.algorithm.CausalityPreserve;
import pagerank.algorithm.BackTrack;
import pagerank.algorithm.LLMGraphFilter;

import guru.nidi.graphviz.engine.*;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * ProcessOneLogCMD_19 - 单日志文件处理核心类
 * 
 * 本类是整个溯源系统的核心处理逻辑，实现了完整的攻击溯源流程：
 * 
 * 完整处理流程（反向溯源）：
 * 1. GetGraph - 从Sysdig日志构建依赖图
 * 2. BackTrack - 后向切片，从POI（检测点）反向追踪可能的因果路径
 * 3. CausalityPreserve (CPR) - 因果保持压缩，合并时间窗口内的连续操作
 * 4. 特征权重计算 - 根据不同模式计算边权重（时间、数据量、结构特征）
 * 5. PageRank传播 - 后向迭代传播恶意度分数
 * 6. 入口点识别 - 找出最可能的攻击入口点
 * 7. 前向分析验证 - 结合前向分析过滤出真正的攻击路径
 * 8. LLM过滤（可选）- 使用大语言模型进一步过滤噪音
 * 
 * 支持的权重计算模式（mode参数）：
 * - nonml: 手动权重（时间0.5+结构0.5，或时间0.1+结构0.4+数据量0.5）
 * - clusterall: 全局聚类 + FDA降维
 * - clusterlocal: 局部聚类 + FDA降维（论文核心方法）
 * - nonoutlier: 排除离群点后聚类
 * - localtime: 仅时间权重
 * - localamount: 仅数据量权重
 * - localstruct: 仅结构权重（扇出）
 * - fanout: 仅扇出权重
 * - nonmlrandom: 随机权重（基线对比）
 * 
 * @author fang
 * @date 2018/4/6
 */
@SuppressWarnings("Duplicates")
public class ProcessOneLogCMD_19 {
    // 统计文件输出流
    static OutputStream os = null;
    /**
     * run_exp_backward - 反向溯源分析核心方法
     * 
     * 完整处理流程：
     * 1. BackTrack - 后向切片：从POI检测点反向追踪，保留所有能到达POI的路径
     * 2. CPR - 因果保持压缩：合并时间窗口内的连续同操作
     * 3. 权重计算：根据mode选择不同策略（聚类/非ML/扇出等）
     * 4. PageRank传播：后向迭代传播恶意度分数
     * 5. 入口点识别：找出候选攻击入口
     * 6. 前向分析：验证从入口点到POI的因果路径
     * 7. LLM过滤（可选）：使用大语言模型进一步精简攻击路径
     * 
     * @param orignal 完整的依赖图
     * @param resultDir 结果输出目录
     * @param suffix 文件名后缀
     * @param threshold 阈值
     * @param trackOrigin 是否追踪源头
     * @param logfile 日志文件路径
     * @param IP 本地IP地址
     * @param detection POI检测点
     * @param highRP 高可疑实体（已知恶意/可疑实体，如POI，reputation=1.0）
     * @param midRP 背景噪音实体（系统库等）
     * @param lowRP 低可疑实体（已知良性/可信实体，reputation=0.0）
     * @param filename 文件名
     * @param detectionSize 检测数据量
     * @param seedSources 种子源
     * @param criticalEdges 关键边
     * @param mode 权重模式
     * @param jsonlog JSON日志
     * @param importantEntries 重要入口点
     */
    // backtrack + backward propagate
    public static void run_exp_backward(DirectedPseudograph<EntityNode, EventEdge> orignal, String resultDir,
            String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection,
            String[] highRP, String[] midRP, String[] lowRP, String filename, double detectionSize,
            Set<String> seedSources, String[] criticalEdges, String mode, String cprMode, double cprTimeWindow, JSONObject jsonlog,
            String[] importantEntries) {
        try {
            // 1. 打开统计文件（用于记录每一步的节点/边数量、耗时等）
            os = new FileOutputStream(resultDir + filename + suffix + "_stats");

            // 1.5 当detectionSize未指定时，从原始图（BackTrack/CPR压缩前）中自动提取
            if (detectionSize <= 0) {
                double extractedSize = GetGraph.extractDetectionSizeFromGraph(orignal, detection);
                if (extractedSize > 0) {
                    detectionSize = extractedSize;
                    System.out.println("Auto-detected detectionSize from original graph: " + detectionSize);
                }
            }

            // 2. BackTrack 阶段（核心后向切片）
            // 从检测到的恶意事件节点开始，向后进行图切片只保留能够到达 detection 的节点和边（即可能导致这个恶意事件的因果路径）。
            // 得到一个大幅缩小的子图 backTrack.afterBackTrack。这步相当于 “从报警点往回找所有可能的前因”
            long start = System.currentTimeMillis();
            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            long end = System.currentTimeMillis();
            double timeCost = getTimeCost(start, end);
            System.out.println("BackTrack time cost is: " + timeCost);
            os.write(("BackTrack time cost is: " + timeCost + "\n").getBytes());
            System.out.println("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size()
                    + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size()
                    + " edge number: " + backTrack.afterBackTrack.edgeSet().size() + "\n").getBytes());
            jsonlog.put("BackTrackVertexNumber", backTrack.afterBackTrack.vertexSet().size());
            jsonlog.put("BackTrackEdgeNumber", backTrack.afterBackTrack.edgeSet().size());
            jsonlog.put("BackTrackTimeCost", timeCost);
            // IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            // out.exportGraph(resultDir + "BackTrack_" + filename + suffix);

            // 3. Causality Preserve Reduction（因果保持压缩）——CPR 阶段
            // 在 BackTrack 得到的子图上，进一步合并时间窗口内（10秒内）连续的同主体同客体操作（比如同一个进程连续写同一个文件，会合并成一条边）。
            // 目的是在不破坏因果关系的前提下进一步压缩图（减少边数）。
            // 这步是很多 provenance 压缩论文（如 ProvTracer、MPI、OmegaLog 等）都会做的操作。
            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            start = System.currentTimeMillis();
            String cprModeNormalized = cprMode == null ? CausalityPreserve.MODE_WINDOWED_SEQUENCE
                    : cprMode.trim().toLowerCase(Locale.ROOT);
            switch (cprModeNormalized) {
                case CausalityPreserve.MODE_STANDARD_CPR:
                case CausalityPreserve.MODE_CAUSAL_STRICT:
                    CPR.applyMode(cprModeNormalized, cprTimeWindow);
                    System.out.println("CPR mode standard_cpr (paper-aligned causality-preserved reduction)");
                    break;
                case CausalityPreserve.MODE_FULL_MERGE:
                case CausalityPreserve.MODE_ENDPOINT_AGGREGATION:
                    CPR.applyMode(cprModeNormalized, cprTimeWindow);
                    System.out.println("CPR mode full_merge (merge all edges per directed endpoint pair)");
                    break;
                case CausalityPreserve.MODE_WINDOWED_SEQUENCE:
                    CPR.applyMode(CausalityPreserve.MODE_WINDOWED_SEQUENCE, cprTimeWindow);
                    System.out.println("CPR mode windowed_sequence, time window is :" + cprTimeWindow + "(s)");
                    break;
                case CausalityPreserve.MODE_NO_MERGE:
                    CPR.applyMode(CausalityPreserve.MODE_NO_MERGE, cprTimeWindow);
                    System.out.println("CPR mode no_merge (no edge fusion)");
                    break;
                case CausalityPreserve.MODE_PCAR:
                    CPR.applyMode(CausalityPreserve.MODE_PCAR, cprTimeWindow);
                    System.out.println("CPR mode pcar (standard CPR + hot-process approximation, hot window=5s, threshold=20)");
                    break;
                case CausalityPreserve.MODE_FD:
                    CPR.applyMode(CausalityPreserve.MODE_FD, cprTimeWindow);
                    System.out.println("CPR mode fd (paper-aligned FD: REO + RNO + 2-node CCO)");
                    break;
                case CausalityPreserve.MODE_SD:
                    CPR.applyMode(CausalityPreserve.MODE_SD, cprTimeWindow);
                    System.out.println("CPR mode sd (paper-aligned SD: FD reductions + source-set filtering)");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported cpr_mode: " + cprMode
                            + ". Supported values: "
                            + CausalityPreserve.MODE_STANDARD_CPR + ", "
                            + CausalityPreserve.MODE_CAUSAL_STRICT + ", "
                            + CausalityPreserve.MODE_FULL_MERGE + ", "
                            + CausalityPreserve.MODE_ENDPOINT_AGGREGATION + ", "
                            + CausalityPreserve.MODE_WINDOWED_SEQUENCE + ", "
                            + CausalityPreserve.MODE_NO_MERGE + ", "
                            + CausalityPreserve.MODE_PCAR + ", "
                            + CausalityPreserve.MODE_FD + ", "
                            + CausalityPreserve.MODE_SD);
            }
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Edge Merge cost is: " + timeCost);
            os.write(("Edge Merge cost is: " + timeCost + "\n").getBytes());
            System.out.println("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: "
                    + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: "
                    + CPR.afterMerge.edgeSet().size() + "\n").getBytes());
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRVertexNumber",
                    String.valueOf(CPR.afterMerge.vertexSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPREdgeNumber", String.valueOf(CPR.afterMerge.edgeSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRTimeCost", String.valueOf(timeCost));
            // out = new IterateGraph(CPR.afterMerge);
            // out.exportGraph(resultDir + "AfterCPR_" + filename + suffix);

            // 4. 特征权重计算
            BackwardPropagate_pf infer = new BackwardPropagate_pf(CPR.afterMerge);
            infer.setDetectionSize(detectionSize);
            // 当detectionSize仍为0时（原始图中也未提取到），作为最后兜底从CPR压缩后的图中提取
            if (detectionSize <= 0) {
                System.out.println("WARNING: detectionSize not available from config or original graph, falling back to CPR graph extraction");
                infer.autoDetectDetectionSize(detection);
            }
            infer.setSeedSources(seedSources); // set structure weight for source
            start = System.currentTimeMillis();
            // 根据不同的mode参数，使用不同的权重计算策略。
            // 论文中最核心的消融实验，对比不同特征/模型对溯源准确率的影响。
            switch (mode) {
                case "nonml":// 非机器学习方法计算权重
                    infer.calculateWeights();
                    break;
                case "clusterall":// 使用聚类的机器学习方法
                    infer.calculateWeights_ML_dec(true, 1, resultDir);
                    break;
                case "nonoutlier":
                    infer.calculateWeights_ML_dec(true, 2, resultDir);
                    break;
                case "clusterlocal_dec":
                    infer.calculateWeights_ML_dec(true, 3, resultDir);
                    break;
                case "clusterlocal":// 局部聚类方法
                    infer.calculateWeights_ML_dec(true, 3, resultDir);
                    break;
                case "localtime":
                    infer.calculateWeights_Individual(true, "timeWeight", resultDir);
                    break;
                case "localamount":
                    infer.calculateWeights_Individual(true, "amountWeight", resultDir);
                    break;
                case "localstruct":
                    infer.calculateWeights_Individual(true, "structureWeight", resultDir);
                    break;
                case "fanout":// 只用扇出特征
                    // InferenceReputation fanoutCalculate = new
                    // InferenceReputation(CPR.afterMerge);
                    infer.calculateWeights_Fanout(true, resultDir);
                    break;
                case "nonmlrandom":// 随机权重（用于基线对比）
                    infer.calculateWeightsRandom();
                    break;
                case "nodoze":// 已移除 NODOZE 算法实现，保持兼容行为：直接返回
                    return;
                case "read_only":// 只统计只读节点数量，输出文件后返回
                    Map<String, Integer> res = infer.graphSizeWithoutReadonly();
                    File read_onlyRES = new File(resultDir + "/readOnly.txt");
                    FileWriter readOnlyFileWriter = new FileWriter(read_onlyRES);
                    for (String k : res.keySet()) {
                        readOnlyFileWriter.write(String.format("%s:%d", k, res.get(k)));
                    }
                    readOnlyFileWriter.close();
                default:
                    System.out.println("Unknown mode: " + mode);
            }
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            String timeCostInfo = String.format("Weight Calculation (%s) time cost is: ", mode) + timeCost + "\n";
            System.out.println(timeCostInfo);
            os.write(timeCostInfo.getBytes());
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "WeightCalculationTimeCost", String.valueOf(timeCost));

            // 5. 执行后向 PageRank 式传播（核心溯源算法）
            // 从检测点 detection 开始，反向迭代 PageRank（即沿着依赖边反向传播“恶意度”）。
            // 最终每个节点会得到一个 reputation 分数，分数越高越可能是真正的攻击入口。
            List<String> skipmode = new ArrayList<>();
            skipmode.add("fanout");
            if (!skipmode.contains(mode)) {
                // 初始化已知高/低可疑节点的恶意度
                // highRP：已知高可疑/恶意实体（如POI检测点），reputation初始化为1.0
                // lowRP：已知良性/可信实体（如正常系统进程），reputation初始化为0.0
                infer.initialReputation(highRP, lowRP);
                start = System.currentTimeMillis();
                infer.PageRankIterationBackward(highRP, midRP, lowRP, detection);
                end = System.currentTimeMillis();
                timeCost = getTimeCost(start, end);
                timeCostInfo = "Propagation time cost is: " + timeCost + "\n";
                System.out.println(timeCostInfo);
                os.write(timeCostInfo.getBytes());
                ProcessOneLogCMD_19.putToJsonLog(jsonlog, "PropagationTimeCost", String.valueOf(timeCost));

                // 6. 找出可能的攻击入口点（Entry Points）并生成最终溯源结果
                List<List<String>> forwardStarts = infer.getForwardStarts(detection);
                List<String> highlightedEntries = collectHighlightedEntries(forwardStarts, importantEntries, 3);
                IterateGraph highlightedWeightGraph = new IterateGraph(infer.graph, detection, highlightedEntries);
                highlightedWeightGraph.exportGraph(resultDir + "Weight_" + filename + suffix);
                Map<String, Double> nodeReputation = IterateGraph.getNodeReputation(infer.graph);
                IterateGraph.outputTopStarts(resultDir, forwardStarts, nodeReputation);
                boolean outputFilterGraph = true;
                // 只保留能从 forwardStarts 正向到达 detection 的所有路径。
                // 输出文件名叫 sysrep 开头 → 代表这是“我们系统（sysrep）”找到的攻击路径图，人工看起来最干净、最准。
                ProcessOneLogCMD_19.filter_graph_by_forward_category(forwardStarts, orignal, "results", resultDir,
                        filename, suffix, "1", 3, infer, outputFilterGraph, detection);

                List<String> entryPoints = IterateGraph.getCandidateEntryPoint(infer.graph, detection);
                JSONObject entryJson = new JSONObject();
                entryJson.put("EntryPointsNumber", entryPoints.size());
                ProcessOneLogCMD_19.putToJsonLog(jsonlog, "EntryPointsNumber", String.valueOf(entryPoints.size()));
                JSONArray ponintsJson = new JSONArray();
                entryPoints.stream().forEach(s -> ponintsJson.add(s));
                entryJson.put("EntryPoints", ponintsJson);
                File entryPointsJsonFile = new File(resultDir + filename + suffix + "_entry_points.json");
                FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
                jsonWriter.write(entryJson.toJSONString());
                jsonWriter.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static List<String> collectHighlightedEntries(List<List<String>> starts, String[] configuredEntries,
            int perCategoryLimit) {
        LinkedHashSet<String> highlightedEntries = new LinkedHashSet<>();

        if (configuredEntries != null) {
            for (String entry : configuredEntries) {
                if (entry != null && !entry.trim().isEmpty()) {
                    highlightedEntries.add(entry.trim());
                }
            }
        }

        if (starts != null) {
            for (List<String> category : starts) {
                if (category == null) {
                    continue;
                }
                int limit = perCategoryLimit > 0 ? Math.min(perCategoryLimit, category.size()) : category.size();
                for (int i = 0; i < limit; i++) {
                    String entry = category.get(i);
                    if (entry != null && !entry.trim().isEmpty()) {
                        highlightedEntries.add(entry.trim());
                    }
                }
            }
        }

        return new ArrayList<>(highlightedEntries);
    }

    public static double getTimeCost(long start, long end) {
        return (end - start) * 1.0 / 1000.0;
    }

    public static void putToJsonLog(JSONObject jsonLog, String key, String value) {
        jsonLog.put(key, value);
    }

    public static void DotToSvg(String dotPath, String svgPath) throws Exception {
        String dotContent = new String(Files.readAllBytes(Paths.get(dotPath)));
        String svg = Graphviz.fromString(dotContent).render(Format.SVG).toString();
        Files.write(Paths.get(svgPath), svg.getBytes());
    }

    public static void filter_graph_by_forward_category(List<List<String>> starts,
                                                        DirectedPseudograph<EntityNode, EventEdge> orignal,
                                                        String method, String resultDir, String filename, String suffix,
                                                        String time, int startLimitForEachCategory,
                                                        BackwardPropagate_pf infer, boolean outputGraph, String poiEvent) {
        try {
            File resFolderForFilter = new File(resultDir + "/" + method);
            if (!resFolderForFilter.exists()) {
                resFolderForFilter.mkdir();
            }
            File recordStarts = new File(resFolderForFilter.getAbsolutePath() + "/" + "forward_starts_" + filename + "_"
                    + time + "_" + method + ".txt");
            FileWriter fileWriter = new FileWriter(recordStarts);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            printWriter.println("Entry Points for forward:");
            int startsNum = 0;
            List<String> allSelectedStarts = new ArrayList<>();
            for (List<String> category : starts) {
                for (int r = 0; r < startLimitForEachCategory && r < category.size(); r++) {
                    String entry = category.get(r);
                    printWriter.println(entry);

                    // 将入口点暂存在内存列表中，供后续一并生成完整图
                    allSelectedStarts.add(entry);

                    // [修改]：移除了单节点图 combineBackwardAndForwardForGivenStart 的计算
                    // [修改]：移除了单节点图 exportGraph 和 DotToSvg 的磁盘 I/O 操作

                    startsNum++;
                }
            }
            printWriter.close();

            // ====================== 生成包含所有入口的完整溯源图 ======================
            if (!allSelectedStarts.isEmpty() && outputGraph) {
                System.out.println("Generating complete provenance graph with all entry points merged, total "
                        + allSelectedStarts.size() + " entries...");

                // 使用内存中暂存的所有入口点，一次性生成完整的溯源图
                DirectedPseudograph<EntityNode, EventEdge> allInOneGraph = infer
                        .combineBackwardAndForwardForMultipleStarts(allSelectedStarts, orignal);

                IterateGraph mergedOut = new IterateGraph(allInOneGraph, poiEvent, allSelectedStarts);
                String mergedPath = resFolderForFilter.getAbsolutePath() + "/" +
                        "complete_provenance_graph_" + filename + "_" + method + suffix;

                mergedOut.exportGraph(mergedPath);
                DotToSvg(mergedPath + ".dot", mergedPath + ".svg");

                System.out.println("Complete provenance graph generated successfully (" + allSelectedStarts.size()
                        + " entries merged):");
                System.out.println("File: " + mergedPath + ".svg");
                System.out.println("Vertices: " + allInOneGraph.vertexSet().size() + "   Edges: "
                        + allInOneGraph.edgeSet().size());

                // ======== 利用 LLM 过滤图 ========
                try {
                    System.out.println("Starting LLM Filtering Process...");
                    LLMGraphFilter llmFilter = new LLMGraphFilter();

                    String llmLogPath = new File(resultDir,
                            "llm_interaction_" + filename + "_" + method + suffix + ".log").getAbsolutePath();

                    DirectedPseudograph<EntityNode, EventEdge> llmFilteredGraph = llmFilter.filterGraph(allInOneGraph,
                            allSelectedStarts, poiEvent, llmLogPath);

                    IterateGraph filteredOut = new IterateGraph(llmFilteredGraph, poiEvent, allSelectedStarts);
                    String filteredPath = resFolderForFilter.getAbsolutePath() + "/" +
                            "llm_filtered_graph_" + filename + "_" + method + suffix;

                    filteredOut.exportGraph(filteredPath);
                    DotToSvg(filteredPath + ".dot", filteredPath + ".svg");

                    System.out.println("LLM Filtered graph generated successfully.");
                    System.out.println("File: " + filteredPath + ".svg");
                    System.out.println("Vertices: " + llmFilteredGraph.vertexSet().size() + "   Edges: "
                            + llmFilteredGraph.edgeSet().size());
                } catch (Exception e) {
                    System.err.println("LLM Filtering failed: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
