package pagerank;
import guru.nidi.graphviz.engine.*;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by fang on 4/6/18.
 */
@SuppressWarnings("Duplicates")
public class ProcessOneLogCMD_19 {
    static OutputStream os = null;
    public static int topStarts = 3;     //parameter for choosing top N starts for forward analysis

    public static void process_backward(String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection, String[] highRP, String[] midRP, String[] lowRP, String filename, double detectionSize, Set<String> seedSources, String[] criticalEdges, String mode, JSONObject jsonLog) {
        OutputStream weightfile = null;
        try {
            os = new FileOutputStream(resultDir + filename + suffix + "_stats");
            GetGraph getGraph = new GetGraph(logfile, IP);
            long startTime = System.currentTimeMillis();
            getGraph.GenerateGraph();
            DirectedPseudograph<EntityNode, EventEdge> orignal = getGraph.getJg();
            System.out.println("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size());
            os.write(("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size() + "\n").getBytes());
            long endTime = System.currentTimeMillis();
            double timeCost = getTimeCost(startTime, endTime);
            System.out.println("Build Original Graph time cost is: " + timeCost);
            os.write(("Build Original Graph time cost is: " + timeCost + "\n").getBytes());
            jsonLog.put("origionVertexNumber", orignal.vertexSet().size());
            jsonLog.put("origionEdgeNumber", orignal.edgeSet().size());
            jsonLog.put("CostForOrigionGraph", timeCost);
            run_exp_backward(orignal, resultDir, suffix, threshold, trackOrigin, logfile, IP, detection, highRP, midRP, lowRP, filename, detectionSize, seedSources, criticalEdges, mode, jsonLog, new String[0]);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
//                weightfile.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void run_exp_backward_without_backtrack(DirectedPseudograph<EntityNode, EventEdge> backtrack, String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection, String[] highRP, String[] midRP, String[] lowRP,
                                                          String filename, double detectionSize, Set<String> seedSources, String[] criticalEdges, String mode, JSONObject jsonlog, String[] importantEntries) {
        OutputStream weightfile = null;
        try {
            os = new FileOutputStream(resultDir + filename + suffix + "_stats");
            long start = System.currentTimeMillis();
//            BackTrack backTrack = new BackTrack(orignal);
//            backTrack.backTrackPOIEvent(detection);
            long end = System.currentTimeMillis();
            os.write(String.format("Backtrack V: %d E: %d", backtrack.vertexSet().size(), backtrack.edgeSet().size()).getBytes());
            double timeCost = getTimeCost(start, end);


            CausalityPreserve CPR = new CausalityPreserve(backtrack);
            //CPR.CPR(2);
            start = System.currentTimeMillis();
            double timeWindow = 10.0;
            CPR.mergeEdgeFallInTheRange2(timeWindow);
            System.out.println("The size of time window is :" + timeWindow + "(s)");
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Edge Merge cost is: " + timeCost);
            os.write(("Edge Merge cost is: " + timeCost + "\n").getBytes());
            System.out.println("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size() + "\n").getBytes());
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRVertexNumber", String.valueOf(CPR.afterMerge.vertexSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPREdgeNumber", String.valueOf(CPR.afterMerge.edgeSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRTimeCost", String.valueOf(timeCost));
            IterateGraph out = new IterateGraph(CPR.afterMerge);
            out.exportGraph(resultDir + "AfterCPR_" + filename + suffix);


            BackwardPropagate_pf infer = new BackwardPropagate_pf(CPR.afterMerge);


            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
            start = System.currentTimeMillis();
            switch (mode) {
                case "nonml":
                    infer.calculateWeights();
                    break;
                case "clusterall":
                    infer.calculateWeights_ML_dec(true, 1, resultDir);
                    break;
                case "nonoutlier":
                    infer.calculateWeights_ML_dec(true, 2, resultDir);
                    break;
                case "clusterlocal_dec":
                    infer.calculateWeights_ML_dec(true, 3, resultDir);
                    break;
                case "clusterlocal":
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
                case "fanout":
                    //InferenceReputation fanoutCalculate = new InferenceReputation(CPR.afterMerge);
                    infer.calculateWeights_Fanout(true, resultDir);
                    break;
                case "nonmlrandom":
                    infer.calculateWeightsRandom();
                    break;
                case "nodoze":
                    return;
                case "read_only":
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


            List<String> skipmode = new ArrayList<>();
            skipmode.add("fanout");
            if (!skipmode.contains(mode)) {
                infer.initialReputation(highRP, lowRP);
                start = System.currentTimeMillis();
                infer.PageRankIterationBackward(highRP, midRP, lowRP, detection);
                end = System.currentTimeMillis();
                timeCost = getTimeCost(start, end);
                timeCostInfo = "Propagation time cost is: " + timeCost + "\n";
                System.out.println(timeCostInfo);
                os.write(timeCostInfo.getBytes());
                ProcessOneLogCMD_19.putToJsonLog(jsonlog, "PropagationTimeCost", String.valueOf(timeCost));

                infer.exportGraph(resultDir + "Weight_" + filename + suffix);
                List<List<String>> forwardStarts = infer.getForwardStarts();
                List<String> nodesignatures = infer.graph.vertexSet().stream().map(v -> v.getSignature()).collect(Collectors.toList());
                List<String> randomStarts = IterateGraph.getRandomStarts(nodesignatures, 3);  // not based on category
                List<List<String>> randomStartsCategory = IterateGraph.randomPickEntryStartsBasedOnCategory(forwardStarts);
                Map<String, Double> nodeReputation = IterateGraph.getNodeReputation(infer.graph);
                IterateGraph.outputTopStarts(resultDir, forwardStarts, nodeReputation);
                boolean outputFilterGraph = true;
                ProcessOneLogCMD_19.filter_graph_by_forward_category(forwardStarts, backtrack, "sysrep", resultDir,
                        filename, suffix, "1", 3, infer, outputFilterGraph);
                outputFilterGraph = true;

                for (int i = 0; i < 20; i++) {
                    randomStartsCategory = IterateGraph.randomPickEntryStartsBasedOnCategory(forwardStarts);
                    randomStarts = IterateGraph.getRandomStarts(nodesignatures, 3);
                    ProcessOneLogCMD_19.filter_graph_by_forward_category(randomStartsCategory, backtrack, "randomCategory",
                            resultDir, filename, suffix, String.valueOf(i), 3, infer, outputFilterGraph);
                    ProcessOneLogCMD_19.filter_graph_by_forward(randomStarts, backtrack, "random", resultDir, filename,
                            suffix, String.valueOf(i), infer, outputFilterGraph);
                }


                List<String> entryPoints = IterateGraph.getCandidateEntryPoint(infer.graph);
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

    //backtrack + backward propagate
    public static void run_exp_backward(DirectedPseudograph<EntityNode, EventEdge> orignal, String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection, String[] highRP, String[] midRP, String[] lowRP, String filename, double detectionSize, Set<String> seedSources, String[] criticalEdges, String mode, JSONObject jsonlog, String[] importantEntries) {
        OutputStream weightfile = null;
        try {
            //1. 打开统计文件（用于记录每一步的节点/边数量、耗时等）
            os = new FileOutputStream(resultDir + filename + suffix + "_stats");

            //2. BackTrack 阶段（核心后向切片）
            //从检测到的恶意事件节点开始，向后进行图切片只保留能够到达 detection 的节点和边（即可能导致这个恶意事件的因果路径）。
            //得到一个大幅缩小的子图 backTrack.afterBackTrack。这步相当于 “从报警点往回找所有可能的前因”
            long start = System.currentTimeMillis();
            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            long end = System.currentTimeMillis();
            double timeCost = getTimeCost(start, end);
            System.out.println("BackTrack time cost is: " + timeCost);
            os.write(("BackTrack time cost is: " + timeCost + "\n").getBytes());
            System.out.println("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size() + "\n").getBytes());
            jsonlog.put("BackTrackVertexNumber", backTrack.afterBackTrack.vertexSet().size());
            jsonlog.put("BackTrackEdgeNumber", backTrack.afterBackTrack.edgeSet().size());
            jsonlog.put("BackTrackTimeCost", timeCost);
            //IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            //out.exportGraph(resultDir + "BackTrack_" + filename + suffix);

            //3. Causality Preserve Reduction（因果保持压缩）——CPR 阶段
            //在 BackTrack 得到的子图上，进一步合并时间窗口内（10秒内）连续的同主体同客体操作（比如同一个进程连续写同一个文件，会合并成一条边）。
            //目的是在不破坏因果关系的前提下进一步压缩图（减少边数）。
            //这步是很多 provenance 压缩论文（如 ProvTracer、MPI、OmegaLog 等）都会做的操作。
            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            start = System.currentTimeMillis();
            double timeWindow = 10.0;
            CPR.mergeEdgeFallInTheRange2(timeWindow);
            System.out.println("The size of time window is :" + timeWindow + "(s)");
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Edge Merge cost is: " + timeCost);
            os.write(("Edge Merge cost is: " + timeCost + "\n").getBytes());
            System.out.println("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size() + "\n").getBytes());
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRVertexNumber", String.valueOf(CPR.afterMerge.vertexSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPREdgeNumber", String.valueOf(CPR.afterMerge.edgeSet().size()));
            ProcessOneLogCMD_19.putToJsonLog(jsonlog, "CPRTimeCost", String.valueOf(timeCost));
            //out = new IterateGraph(CPR.afterMerge);
            //out.exportGraph(resultDir + "AfterCPR_" + filename + suffix);

            //4. 特征权重计算
            BackwardPropagate_pf infer = new BackwardPropagate_pf(CPR.afterMerge);
            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
            start = System.currentTimeMillis();
            //根据不同的mode参数，使用不同的权重计算策略。
            //论文中最核心的消融实验，对比不同特征/模型对溯源准确率的影响。
            switch (mode) {
                case "nonml"://非机器学习方法计算权重
                    infer.calculateWeights();
                    break;
                case "clusterall"://使用聚类的机器学习方法
                    infer.calculateWeights_ML_dec(true, 1, resultDir);
                    break;
                case "nonoutlier":
                    infer.calculateWeights_ML_dec(true, 2, resultDir);
                    break;
                case "clusterlocal_dec":
                    infer.calculateWeights_ML_dec(true, 3, resultDir);
                    break;
                case "clusterlocal"://局部聚类方法
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
                case "fanout"://只用扇出特征
                    //InferenceReputation fanoutCalculate = new InferenceReputation(CPR.afterMerge);
                    infer.calculateWeights_Fanout(true, resultDir);
                    break;
                case "nonmlrandom"://随机权重（用于基线对比）
                    infer.calculateWeightsRandom();
                    break;
                case "nodoze"://运行 NODOZE 算法（另一个论文的方法），直接返回
                    List<String> fileMalicious = new ArrayList<>();
                    fileMalicious.add(detection);
                    List<String> ipMalicious = new ArrayList<>();
                    NODOZE nodoze = new NODOZE(fileMalicious, ipMalicious, orignal, backTrack.afterBackTrack, CPR.afterMerge, detection, importantEntries);
                    long nodozeStart = System.currentTimeMillis();
                    int nodozeRes = nodoze.filterExp();
                    long nodozeEnd = System.currentTimeMillis();
                    long nodozeTimeCost = nodozeEnd - nodozeStart;
                    File nodozeResFile = new File(resultDir + "/nodoze.txt");
                    FileWriter fileWriter = new FileWriter(nodozeResFile);
                    fileWriter.write("Nodoze Res:" + String.valueOf(nodozeRes) + "\n");
                    fileWriter.write("Nodoze time: " + String.valueOf(nodozeTimeCost));
                    System.out.println("Size of Nodoze Res: " + String.valueOf(nodozeRes));
                    System.out.println("Time of Nodoze: " + String.valueOf(nodozeTimeCost));
                    fileWriter.close();
                    return;
                case "read_only"://只统计只读节点数量，输出文件后返回
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


            //5. 执行后向 PageRank 式传播（核心溯源算法）
            //从检测点 detection 开始，反向迭代 PageRank（即沿着依赖边反向传播“恶意度”）。
            //最终每个节点会得到一个 reputation 分数，分数越高越可能是真正的攻击入口。
            List<String> skipmode = new ArrayList<>();
            skipmode.add("fanout");
            if (!skipmode.contains(mode)) {
                //初始化已知高/低可信节点
                //highRP：已知高可信进程/文件（如 system32 下的系统进程）
                //lowRP：已知低可信（如临时目录下的可疑文件）
                infer.initialReputation(highRP, lowRP);
                start = System.currentTimeMillis();
                infer.PageRankIterationBackward(highRP, midRP, lowRP, detection);
                end = System.currentTimeMillis();
                timeCost = getTimeCost(start, end);
                timeCostInfo = "Propagation time cost is: " + timeCost + "\n";
                System.out.println(timeCostInfo);
                os.write(timeCostInfo.getBytes());
                ProcessOneLogCMD_19.putToJsonLog(jsonlog, "PropagationTimeCost", String.valueOf(timeCost));
                infer.exportGraph(resultDir + "Weight_" + filename + suffix);

                //6. 找出可能的攻击入口点（Entry Points）并生成最终溯源结果
                List<List<String>> forwardStarts = infer.getForwardStarts();
                Map<String, Double> nodeReputation = IterateGraph.getNodeReputation(infer.graph);

                IterateGraph.outputTopStarts(resultDir, forwardStarts, nodeReputation);
                boolean outputFilterGraph = true;
                //只保留能从 forwardStarts 正向到达 detection 的所有路径。
                //输出文件名叫 sysrep 开头 → 代表这是“我们系统（sysrep）”找到的攻击路径图，人工看起来最干净、最准。
                ProcessOneLogCMD_19.filter_graph_by_forward_category(forwardStarts, orignal, "results", resultDir,
                        filename, suffix, "1", 3, infer, outputFilterGraph);


                List<String> entryPoints = IterateGraph.getCandidateEntryPoint(infer.graph);
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


    public static double getTimeCost(long start, long end) {
        return (end - start) * 1.0 / 1000.0;
    }

    public static Timestamp getTimeStamp() {
        Calendar calendar = Calendar.getInstance();
        Timestamp currentTimestamp = new java.sql.Timestamp(calendar.getTime().getTime());
        return currentTimestamp;
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
                                                        BackwardPropagate_pf infer, boolean outputGraph) {
        try {
            File resFolderForFilter = new File(resultDir + "/" + method);
            if (!resFolderForFilter.exists()) {
                resFolderForFilter.mkdir();
            }
            File recordStarts = new File(resFolderForFilter.getAbsolutePath() + "/" + "forward_starts_" + filename + "_" + time + "_" + method + ".txt");
            FileWriter fileWriter = new FileWriter(recordStarts);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            printWriter.println("Entry Points for forward:");
            int startsNum = 0;
            List<String> allSelectedStarts = new ArrayList<>();
            for (List<String> category : starts) {
                for (int r = 0; r < startLimitForEachCategory && r < category.size(); r++) {
                    String entry = category.get(r);
                    printWriter.println(entry);
                    allSelectedStarts.add(entry);

                    DirectedPseudograph<EntityNode, EventEdge> filtered = infer.combineBackwardAndForwardForGivenStart(entry, orignal);

                    if (outputGraph) {
                        IterateGraph out = new IterateGraph(filtered);
                        String dotPath = resFolderForFilter.getAbsolutePath() + "/" +
                                "filtered_by_forward_" + startsNum + "_" + filename + "_" + method + suffix;
                        out.exportGraph(dotPath);
                        // ======== 新增：自动转换为 SVG ========
                        DotToSvg(dotPath + ".dot", dotPath + ".svg");
                    }
                    startsNum++;
                }
            }
            printWriter.close();

            // ====================== 生成包含所有入口的完整溯源图 ======================
            if (!allSelectedStarts.isEmpty() && outputGraph) {
                System.out.println("Generating complete provenance graph with all entry points merged...");

                // 1. 生成原始合并图
                DirectedPseudograph<EntityNode, EventEdge> allInOneGraph =
                        infer.combineBackwardAndForwardForMultipleStarts(allSelectedStarts, orignal);

                // =========================================================================
                // [步骤 A] 先导出【原始未过滤】的图 (ORIGINAL)
                // =========================================================================
                String originalPath = resFolderForFilter.getAbsolutePath() + "/" +
                        "complete_graph_ORIGINAL_" + filename + "_" + method + suffix;

                IterateGraph originalOut = new IterateGraph(allInOneGraph);
                originalOut.exportGraph(originalPath);
                try {
                    DotToSvg(originalPath + ".dot", originalPath + ".svg");
                    System.out.println("Complete provenance graph generated successfully (" + allSelectedStarts.size() + " entries merged):");
                    System.out.println("File: " + originalPath + ".svg");
                    System.out.println("Vertices: " + allInOneGraph.vertexSet().size() + "   Edges: " + allInOneGraph.edgeSet().size());
                } catch (Exception e) { System.err.println("SVG Error: " + e.getMessage()); }


                // =========================================================================
                // [步骤 B] LLM 语义过滤交互 (Human-in-the-loop)
                // =========================================================================
                System.out.println("\n-------------------------------------------------------------");
                System.out.println(">>> [LLM Filter] Starting Semantic Filtering Phase...");

                // 序列化当前图为 JSON (纯语义，无分数)
                String jsonForLLM = LLMGraphUtils.serializeGraphForLLM(allInOneGraph, "Combined_Entry_Points");

                // 保存 JSON 文件
                String jsonPath = resFolderForFilter.getAbsolutePath() + "/" + "llm_context_" + filename + ".json";
                try (FileWriter jsonWriter = new FileWriter(jsonPath)) {
                    jsonWriter.write(jsonForLLM);
                }

                // 交互式暂停
                System.out.println(">>> [ACTION REQUIRED] JSON context saved to: " + jsonPath);
                System.out.println(">>> Please upload this file to the LLM with the Broad Concept Prompt.");
                System.out.println(">>> Paste the LLM's returned ID list (e.g. [120, 125]) below and press ENTER:");

                Scanner scanner = new Scanner(System.in);
                String llmResponse = scanner.nextLine();

                // 执行过滤 (注意：这会直接修改 allInOneGraph 对象)
                if (llmResponse != null && !llmResponse.trim().isEmpty() && !llmResponse.trim().equals("[]")) {
                    System.out.println(">>> Applying LLM semantic filter...");
                    LLMGraphUtils.filterGraphWithRetentionList(allInOneGraph, llmResponse);

                    // =========================================================================
                    // [步骤 C] 导出【LLM过滤后】的图 (FILTERED)
                    // =========================================================================
                    String filteredPath = resFolderForFilter.getAbsolutePath() + "/" +
                            "complete_graph_LLM_FILTERED_" + filename + "_" + method + suffix;

                    IterateGraph filteredOut = new IterateGraph(allInOneGraph);
                    filteredOut.exportGraph(filteredPath);

                    try {
                        DotToSvg(filteredPath + ".dot", filteredPath + ".svg");
                        System.out.println(">>> [Saved] LLM Filtered Graph: " + filteredPath + ".svg");
                        System.out.println(">>> Graph size reduced: Vertices: " + allInOneGraph.vertexSet().size() + "   Edges: " + allInOneGraph.edgeSet().size());
                    } catch (Exception e) { System.err.println("SVG Error: " + e.getMessage()); }

                } else {
                    System.out.println(">>> No input received. Skipping filter and saving duplicate of original.");
                }
                System.out.println("-------------------------------------------------------------\n");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void filter_graph_by_forward(List<String> start, DirectedPseudograph<EntityNode, EventEdge> orignal,
                                               String method, String resultDir, String filename, String suffix, String time, BackwardPropagate_pf infer, boolean output) {
        try {
            File resFolderForFilter = new File(resultDir + "/" + method);
            if (!resFolderForFilter.exists()) {
                resFolderForFilter.mkdir();
            }
            File folderForFtime = new File(resFolderForFilter.getAbsolutePath() + "/" + time);
            if (!folderForFtime.exists()) {
                folderForFtime.mkdir();
            }
            File recordStarts = new File(folderForFtime.getAbsolutePath() + "/" + "forward_starts_" + filename + "_" + time + "_" + method + ".txt");
            FileWriter fileWriter = new FileWriter(recordStarts);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            printWriter.println("Entry Points for forward:");
            int startsNum = 0;
            for (String s : start) {
                printWriter.println(s);
                DirectedPseudograph<EntityNode, EventEdge> backresFilteredByForwad = infer.
                        combineBackwardAndForwardForGivenStart(s, orignal);
                System.out.println("Size of randome start: " + backresFilteredByForwad.vertexSet().size());
                IterateGraph out = new IterateGraph(backresFilteredByForwad);
                if (output) {
                    out.exportGraph(folderForFtime.getAbsolutePath() + "/" + "filtered_by_forward_" + String.valueOf(startsNum) + "_" + filename + "_" + method + suffix);
                }
                startsNum++;
            }
            printWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static double[] getMissingRateAndRedundantRate(DirectedPseudograph<EntityNode, EventEdge> graph, String[] criticalEdges) {
        double missingNum = 0.0;
        Set<String> edges = new HashSet<>();
        for (EventEdge edge : graph.edgeSet()) {
            edges.add(IterateGraph.convertEdgeToString(edge));
        }
        for (String s : criticalEdges) {
            String[] srcAndTarget = s.split(",");
            String curEdge = srcAndTarget[0] + " -> " + srcAndTarget[1];
            if (!edges.contains(curEdge)) {
                missingNum += 1;
            }
        }

        Set<String> stringRepOfCirticalEdges = convertCriticalEdge(criticalEdges);
        double redundantNum = 0.0;
        for (String e : edges) {
            if (!stringRepOfCirticalEdges.contains(e)) {
                redundantNum += 1;
            }
        }
        double[] res = new double[2];
        res[0] = missingNum / criticalEdges.length;
        res[1] = redundantNum / graph.edgeSet().size();
        return res;
    }

    public static Set<String> convertCriticalEdge(String[] criticalEdges) {
        Set<String> res = new HashSet<>();
        for (String s : criticalEdges) {
            String[] srcAndTarget = s.split(",");
            String curEdge = srcAndTarget[0] + " -> " + srcAndTarget[1];
            res.add(curEdge);
        }
        return res;
    }
}
