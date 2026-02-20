package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Created by fang on 4/6/18.
 */

public class ProcessOneLog {

    public static void main(String[] args) {
        ArgParser ap = new ArgParser(args);
        Map<String, String> argMap = ap.parseArgs();
//        String path = "/home/lcl/logs/file_manipulation/1.txt";
        String[] localIP = MetaConfig.localIP;
//        String detection = "/home/lcl/wget-1.19/INSTALL";
//        String[] highRP = {};
        String[] midRP = MetaConfig.midRP;//        String[] lowRP = {"192.168.29.234:54764->208.118.235.20:80"};//"/media/lcl/LCL/bad.zip"};//"192.168.29.125:10289->192.168.29.234:22"}
        String path = argMap.get("path");
        String detection = argMap.get("detection");
        String highRPs = argMap.get("high");
        String[] highRP = highRPs == null ? new String[]{} : highRPs.split(",");
        String neutralRPs = argMap.get("neutral");
        String[] neutralRP = neutralRPs == null ? new String[]{} : neutralRPs.split(",");
        ArrayList<String> midRP2 = new ArrayList<>();
        midRP2.addAll(Arrays.asList(midRP));
        midRP2.addAll(Arrays.asList(neutralRP));
        String lowRPs = argMap.get("low");
        String[] lowRP = lowRPs == null ? new String[]{} : lowRPs.split(",");
        String resultDir = argMap.get("res");
        String suffix = argMap.get("suffix");
        double threshold = Double.parseDouble(argMap.get("thresh"));
        boolean trackOrigin = argMap.containsKey("origin");

        String[] paths = path.split("/");
        process(resultDir, suffix, threshold, trackOrigin, path, localIP, detection, highRP, midRP2.toArray(new String[midRP2.size()]), lowRP, paths[paths.length - 1], 0, new HashSet<>(), null);
    }

    public static void process(
            String resultDir,          // 输出结果的目录
            String suffix,             // 文件名后缀，用于区分实验
            double threshold,          // 阈值（未直接使用）
            boolean trackOrigin,       // 是否跟踪来源（未直接使用）
            String logfile,            // 输入日志文件路径
            String[] IP,               // 日志中涉及的IP地址列表
            String detection,          // 目标事件（POI, Point of Interest）
            String[] highRP,           // 高信誉节点
            String[] midRP,            // 中信誉节点
            String[] lowRP,            // 低信誉节点
            String filename,           // 基础文件名
            double detectionSize,      // 检测规模参数
            Set<String> seedSources,   // 种子节点（初始信誉源）
            String[] criticalEdges     // 关键边集合，用于权重与信誉统计
    ) {
        OutputStream os = null;
        OutputStream weightfile = null;
        try {
            //1.初始化输出
            //创建结果输出目录；
            //新建一个统计日志文件 _stats，后续分析信息将写入其中。
            long start = System.currentTimeMillis();
            File result_folder = new File(resultDir);
            if (!result_folder.exists()) {
                result_folder.mkdir();
            }
            String explogfile = resultDir + File.separator + filename + suffix + "_stats";
            os = new FileOutputStream(explogfile);


            //2.图构建阶段   是整个分析流程的输入数据结构。
            //调用自定义类 GetGraph，根据日志和 IP 生成一个有向伪图（DirectedPseudograph），
            //每个 EntityNode 表示一个实体（如主机、进程、文件），
            //每个 EventEdge 表示一个事件（如读写、连接、执行）。
            GetGraph getGraph = new GetGraph(logfile, IP);
            getGraph.GenerateGraph();
            DirectedPseudograph<EntityNode, EventEdge> orignal = getGraph.getJg();

            long end = System.currentTimeMillis();
            System.out.println("Parsing time:" + (end - start) / 1000.0);
            System.out.println("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size());
            os.write(("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size() + "\n").getBytes());


            //3.回溯处理   相当于“溯源”的初步阶段，筛选出与目标相关的攻击路径。
            //以 POI 事件为目标，沿事件因果链反向追踪；
            //过滤出与该事件相关的节点与边；
            //输出“回溯后”的图结构大小变化。
            start = System.currentTimeMillis();
            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            end = System.currentTimeMillis();
            System.out.println("Backtrack time:" + (end - start) / 1000.0);
            System.out.println("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: " + backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size() + "\n").getBytes());
            //将处理后的图导出为.dot文件
            IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            out.exportGraph(resultDir + "BackTrack_" + filename + suffix);
//            backTrack.exportGraph("backTrack");
//
//            // If we run cpr1 and cpr2 together, the cpr1 result will effect cpr2, need more investigation
//            CausalityPreserve CPR1 = new CausalityPreserve(backTrack.afterBackTrack);
//            CPR1.CPR(1);
//            System.out.println("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size());
//            os.write(("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size()+"\n").getBytes());


            //4.因果保持与边合并（CPR）
            //对图执行因果合并操作，将时间范围内的事件边进行合并，参数 10.0 表示时间合并范围（秒或任意时间单位）。
            //目的是减少冗余边、压缩图结构、保持时序一致性；
            start = System.currentTimeMillis();
            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            CPR.mergeEdgeFallInTheRange2(10.0);
            end = System.currentTimeMillis();
            System.out.println("CPR time:" + (end - start) / 1000.0);
            System.out.println("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: " + CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size() + "\n").getBytes());
            //将处理后的图导出为.dot文件
            out = new IterateGraph(CPR.afterMerge);
            out.exportGraph(resultDir + "AfterCPR_" + filename + suffix);


            //5.图拆分与推理结构准备
            //通过多轮顶点分裂（Vertex Splitting）消除多重边，同时修复时间逻辑，得到一个干净的无多重边因果图，用于后续威胁分析
            start = System.currentTimeMillis();
            GraphSplit split = new GraphSplit(CPR.afterMerge);
            split.splitGraph();
            end = System.currentTimeMillis();
            System.out.println("split time:" + (end - start) / 1000.0);
            System.out.println("After Split vertex number is: " + split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());


            //6.推理与权重计算   是整个算法的核心：根据结构与特征推断信息传播强度。
            //基于拆分后的图初始化信誉推理器；
            //calculateWeights_ML() 表示使用机器学习模型（如回归或统计学习）计算每条边的权重；
            //参数 (true,3) 表示启用某种迭代或层数。
            InferenceReputation infer = new InferenceReputation(split.inputGraph);
            os.write(("After Split vertex number is: " + split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size() + "\n").getBytes());
//            weightfile = new FileOutputStream(resultDir+"weights_"+filename+suffix);
//            for(EventEdge e: infer.graph.edgeSet()){
//                weightfile.write((String.valueOf(e.weight)+",").getBytes());
//            }
            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
            start = System.currentTimeMillis();
            infer.calculateWeights_ML(true, 3);
//            infer.calculateWeights_Individual(true, "timeWeight");
//            infer.calculateWeights();
            //System.out.println("OTSU: "+infer.OTSUThreshold());
            end = System.currentTimeMillis();
            System.out.println("Weight computation time:" + (end - start) / 1000.0);


            //7.节点信誉初始化与传播   是溯源算法的关键部分，类似于攻击路径权重传播。
            //根据输入的高/中/低信誉节点进行初值设定；
            //执行基于 PageRank 改进的信誉传播算法；
            //输出节点的最终信誉值。
            //infer.normalizeWeightsAfterFiltering();
            infer.initialReputation(highRP, midRP, lowRP);
            start = System.currentTimeMillis();
            infer.PageRankIteration2(highRP, midRP, lowRP, detection);
            end = System.currentTimeMillis();
            double timeCost = (end - start) * 1.0 / 1000.0;
            System.out.println("Reputation propergation time is: " + timeCost);
            //infer.PageRankIteration(detection);
            //infer.fixReputation(highRP);

//            System.out.println("After Filter vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());
//            os.write(("After Filter vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size()).getBytes());

//        //infer.onlyPrintHighestWeights(detection);


            //8.剔除无关节点与结果导出
            //清除与目标无关节点；
            //导出加权后的图文件；
            //用于生成 SVG 可视化图。
            infer.removeIrrelaventVertices(detection);
            infer.exportGraph(resultDir + "Weight_" + filename + suffix);
//        IterateGraph iterGraph = new IterateGraph(infer.graph);


            //输出POI的信誉
            for (EntityNode v : infer.graph.vertexSet())
                if (v.getSignature().equals(detection)) {
                    os.write(("POI Reputation: " + v.getReputation()).getBytes());
                    break;
                }
            //构建关键节点集合
            //把所有关键边的两个端点加入 criticalNodes，用于后续关键节点统计。
            //构建关键节点集合

//            todo：目前有问题，配置文件通常没写criticalEdges
//            Set<String> criticalNodes = new HashSet<>();
//            for (String edge : criticalEdges) {
//                criticalNodes.add(edge.split(",")[0]);
//                criticalNodes.add(edge.split(",")[1]);
//            }

            // ---------- 安全遍历 criticalEdges ----------
            Set<String> criticalNodes = new HashSet<>();
            if (criticalEdges != null && criticalEdges.length > 0) {
                for (String edge : criticalEdges) {
                    if (edge == null || edge.trim().isEmpty()) {
                        continue;                              // 跳过空字符串
                    }
                    String[] parts = edge.trim().split("\\s*,\\s*"); // 支持空格： "A , B"
                    if (parts.length == 2) {
                        criticalNodes.add(parts[0]);
                        criticalNodes.add(parts[1]);
                    }
                    // 如果格式不对，直接忽略，不崩溃
                }
            }
            // --------------------------------------------


            //统计“根”结点
            // 计算图中入度为 0 的节点数，通常表示没有前驱的源节点
            int roots = 0;
            for (EntityNode n : infer.graph.vertexSet()) {
                if (infer.graph.incomingEdgesOf(n).size() == 0) {
                    roots++;
                }
            }

            //初始化用于统计的累加变量
            double totalCriticalWeights = 0.0;
            double totalNonCriticalWeights = 0.0;

            double totalCriticalRep = 0.0;
            double totalNonCriticalRep = 0.0;

            int criticalEdgeCount = 0;
            int criticalNodeCount = 0;

            //建立关键边集合并统计边权重
            Set<String> criticalEdgeSet = new HashSet<>(Arrays.asList(criticalEdges));
            for (EventEdge e : infer.graph.edgeSet()) {
                if (criticalEdgeSet.contains(e.getSource().getSignature() + "," + e.getSink().getSignature())) {
                    criticalEdgeCount++;
                    totalCriticalWeights += e.weight;
                } else {
                    totalNonCriticalWeights += e.weight;
                }
            }
            //统计关键节点信誉
            for (EntityNode n : infer.graph.vertexSet()) {
                if (criticalNodes.contains(n.getSignature())) {
                    criticalNodeCount++;
                    totalCriticalRep += n.reputation;
                } else {
                    totalNonCriticalRep += n.reputation;
                }
            }

            System.out.println("entries:" + roots);

            System.out.println("#critical edges:" + criticalEdgeCount);
            System.out.println("#non-critical edges:" + (infer.graph.edgeSet().size() - criticalEdgeCount));

            System.out.println("#critical nodes:" + criticalNodeCount);
            System.out.println("#non-critical nodes:" + (infer.graph.vertexSet().size() - criticalNodeCount));


            System.out.println("avg critical edge weight:" + totalCriticalWeights / criticalEdgeCount);
            System.out.println("avg non-critical edge weight:" + totalNonCriticalWeights / (infer.graph.edgeSet().size() - criticalEdgeCount));

            System.out.println("avg critical node rep:" + totalCriticalRep / criticalNodeCount);
            System.out.println("avg non-critical node rep:" + totalNonCriticalRep / (infer.graph.vertexSet().size() - criticalNodeCount));


//            infer.extractSuspects(0.5);
//            infer.exportGraph(resultDir+"Suspect_"+filename+suffix);
//        iterGraph.filterGraphBasedOnVertexReputation();
//        iterGraph.removeSingleVertex();
//        iterGraph.exportGraph("FilteredInstallMongodb");
//        List<DirectedPseudograph<EntityNode, EventEdge>> paths = iterGraph.getHighWeightPaths(detection);
//        for(int i=0; i< paths.size();i++){
//            IterateGraph iter = new IterateGraph(paths.get(i));
//            String fileName = String.valueOf(i) + "path";
//            iter.exportGraph(fileName);
//        }
            //iterGraph.printEdgesOfVertex("11035dpkg");
//        infer.checkWeightsAfterCalculation();
//        infer.exportGraph("UnrarReputation");




            //todo：自动将dot转换为svg，目前有问题
//            Runtime rt = Runtime.getRuntime();
////            String[] cmd = {"/bin/sh","-c","dot -T svg "+resultDir+"BackTrack_"+filename+suffix+".dot"
////                    + " > "+resultDir+"BackTrack_"+filename+suffix+".svg"};
////            rt.exec(cmd);
//            String[] cmd = new String[]{"/bin/sh", "-c", "dot -T svg " + resultDir + "AfterCPR_" + filename + suffix + ".dot"
//                    + " > " + resultDir + "AfterCPR_" + filename + suffix + ".svg"};
//            rt.exec(cmd);
//            cmd = new String[]{"/bin/sh", "-c", "dot -T svg " + resultDir + "Weight_" + filename + suffix + ".dot"
//                    + " > " + resultDir + "Weight_" + filename + suffix + ".svg"};
//            rt.exec(cmd);





            double avg = infer.getAvgWeight();

            //todo:原版即注释
//            double minCritical = 0.05;
//            Set<String> criticalSet = new HashSet<>(Arrays.asList(criticalEdges));
//            PriorityQueue<EventEdge> pq = new PriorityQueue<>(Comparator.comparing(e -> e.weight));
//
//            Map<String,Double> weightMap = new HashMap<>();
//            for(EventEdge e : infer.graph.edgeSet()){
//                pq.offer(e);
//                weightMap.put(e.getSource().getSignature()+","+e.getSink().getSignature(),e.weight);
//            }

//            List<Double> criticalWeights = new ArrayList<>();
//
//            for(String criticalEdge : criticalEdges){
//                if(!weightMap.containsKey(criticalEdge)){
//                    System.out.println("no key:"+criticalEdge);
//                }
//                double weight1 = weightMap.getOrDefault(criticalEdge,0.0);
//                System.out.println(criticalEdge+":"+weight1);
//                String back = criticalEdge.split(",")[1]+","+criticalEdge.split(",")[0];
//                double weight2 = weightMap.getOrDefault(back,0.0);
//                System.out.println(back+":"+weight2);
//                criticalWeights.add(Math.max(weight1,weight2)/avg);
//                minCritical = Math.min(minCritical,Math.max(weight1,weight2));
//
//            }
//
//            PrintWriter pw = new PrintWriter(resultDir+"/critical_weights");
//            for(double d : criticalWeights)
//                pw.println(d);
//            pw.close();

// ──────────────────────────────────────────────────────────────
// 动态计算关键边的最小权重（支持不配 criticalEdge 也不崩溃）
// ──────────────────────────────────────────────────────────────
            double minCritical = Double.MAX_VALUE;  // 先设成最大值
            Map<String, Double> weightMap = new HashMap<>();

// 先把图中所有边的权重存进 map，方便快速查找
            for (EventEdge e : infer.graph.edgeSet()) {
                String key = e.getSource().getSignature() + "," + e.getSink().getSignature();
                weightMap.put(key, e.weight);
            }

            boolean hasCriticalEdge = false;
            for (String criticalEdge : criticalEdges) {
                if (criticalEdge == null || criticalEdge.trim().isEmpty()) continue;
                String[] parts = criticalEdge.split(",");
                if (parts.length != 2) continue;

                String forward = parts[0].trim() + "," + parts[1].trim();
                String backward = parts[1].trim() + "," + parts[0].trim();

                double w1 = weightMap.getOrDefault(forward, 0.0);
                double w2 = weightMap.getOrDefault(backward, 0.0);
                double maxW = Math.max(w1, w2);

                if (maxW > 0) {
                    minCritical = Math.min(minCritical, maxW);
                    hasCriticalEdge = true;
                }
            }
// 如果用户完全没配 criticalEdge，或者配的边在图里没找到，自动回退到 0.05
            if (!hasCriticalEdge || minCritical == Double.MAX_VALUE) {
                minCritical = 0.05;
                System.out.println("Warning: No valid criticalEdge found in graph, fallback to 0.05");
            }




            double startToMiss = minCritical / avg;
            if (1 - minCritical > 1e-8) {
                infer.filterGraphBasedOnAverageWeight(minCritical);
                infer.removeIsolatedIslands(detection);
            }
            infer.exportGraph(resultDir + "Filter_" + filename + suffix);

            System.out.println(String.format("critical edge number: %d", criticalEdges.length));
            os.write(String.format("\ncritical edge number: %d", criticalEdges.length).getBytes());

            System.out.println(String.format("minimum critical weight: %.3f", minCritical));
            os.write(String.format("\nminimum critical weight: %.3f", minCritical).getBytes());

            System.out.println(String.format("start to miss: %.3f", startToMiss));
            os.write(String.format("\nstart to miss: %.3f", startToMiss).getBytes());

            System.out.println("After Filter vertex number is: " + infer.graph.vertexSet().size() + " edge number: " + infer.graph.edgeSet().size());
            os.write(String.format("\nAfter Filter vertex number is: %d edge number: %d", infer.graph.vertexSet().size(), infer.graph.edgeSet().size()).getBytes());

            //todo:自动将dot转换为svg，目前有问题
//            cmd = new String[]{"/bin/sh", "-c", "dot -T svg " + resultDir + "Filter_" + filename + suffix + ".dot"
//                    + " > " + resultDir + "Filter_" + filename + suffix + ".svg"};
//            rt.exec(cmd);



//
//            for(double i=0.0; i<=2.5; i+=0.05){
//                while(pq.size()>0&&pq.peek().weight<i*avg) pq.poll();
//                System.out.println(String.format("%.2f - After Filter edge number is: ",i)+ pq.size());
//                os.write(String.format("\n %.2f - After Filter edge number is: %d",i,pq.size()).getBytes());
//            }


        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                os.close();
//                weightfile.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
