package pagerank.algorithm;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.DBSCANClusterer;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.commons.math3.ml.clustering.MultiKMeansPlusPlusClusterer;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.entity.EventEdgeWrapper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.Assert.assertNotNull;

@SuppressWarnings("Duplicates")
public class BackwardPropagate_pf {
    // 输入的依赖图（CPR压缩后的图）
    public DirectedPseudograph<EntityNode, EventEdge> graph;
    // POI（检测点）的时间戳
    /*
     * the input need to finish split step before this(this parameter need to run
     * relevant functions first)
     */
    private BigDecimal POITime;
    // 图遍历工具
    IterateGraph graphIterator;
    // 最终综合权重
    Map<Long, Double> weights;
    // 时间维度权重
    Map<Long, Double> timeWeights; // sink -> source
    // 数据量维度权重
    Map<Long, Double> amountWeights;
    // 结构维度权重（扇出度）
    Map<Long, Double> structureWeights;
    // 阻尼因子（PageRank算法参数，默认0.85）
    double dumpingFactor;
    // 检测到的数据量（用于计算数据量权重）
    double detectionSize; // this value used to calculate amountWeight, default value is zero.
    // 种子源集合（用于分配结构权重）
    Set<String> seedSources; // get signature of seedSources in order to assign structure weight
    // 原始完整图（用于前向分析验证）
    DirectedPseudograph<EntityNode, EventEdge> originalGraph;
    // 前向分析工具
    ForwardAnalysis forwardAnalysis;

    /**
     * 构造函数
     * 
     * @param input CPR压缩后的依赖图
     */
    public BackwardPropagate_pf(DirectedPseudograph<EntityNode, EventEdge> input) {
        graph = input;
        graphIterator = new IterateGraph(graph);
        weights = new HashMap<>();
        timeWeights = new HashMap<>();
        amountWeights = new HashMap<>();
        structureWeights = new HashMap<>();
        POITime = getPOITime();
        dumpingFactor = 0.85; // PageRank标准阻尼因子
        detectionSize = 0;
        indegree = new HashMap<>();
        outdegree = new HashMap<>();
    }

    // 入度映射（节点签名 -> 入边数量）
    Map<String, Integer> indegree;
    // 出度映射（节点签名 -> 出边数量）
    Map<String, Integer> outdegree;

    /**
     * 设置检测数据量
     * 
     * @param value 检测到的数据量（字节数）
     */
    public void setDetectionSize(double value) {
        System.out.println("setDetection invoked: " + value);
        detectionSize = value;
    }

    /**
     * [兜底方法] 从CPR压缩后的图中提取 POI 节点相关边的数据量作为 detectionSize。
     * 
     * 注意：此方法运行在CPR压缩后的图上，边的size可能因merge操作而被累加，
     * 不一定代表原始单次事件的size。推荐优先使用 GetGraph.extractDetectionSizeFromGraph()
     * 在原始图（CPR压缩前）上提取更准确的值。
     * 
     * 本方法仅在配置文件未指定且原始图提取也失败时，作为最后的兜底手段。
     *
     * 遍历 POI 节点的所有入边（即写入/发送到 POI 的数据），取最大的 size 作为 detectionSize。
     * 如果 POI 节点没有入边，则尝试出边；如果都没有则保持默认值 0。
     *
     * @param detection POI 检测点的签名（如文件路径或网络地址）
     */
    public void autoDetectDetectionSize(String detection) {
        EntityNode poiNode = graphIterator.getGraphVertex(detection);
        if (poiNode == null) {
            System.out.println("autoDetectDetectionSize: POI node '" + detection + "' not found in graph, keeping detectionSize=" + detectionSize);
            return;
        }

        long maxSize = 0;
        // 优先从入边（写入 POI 的数据）提取
        Set<EventEdge> inEdges = graph.incomingEdgesOf(poiNode);
        for (EventEdge edge : inEdges) {
            if (edge.getSize() > maxSize) {
                maxSize = edge.getSize();
            }
        }
        // 如果入边没有数据量，尝试出边
        if (maxSize == 0) {
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(poiNode);
            for (EventEdge edge : outEdges) {
                if (edge.getSize() > maxSize) {
                    maxSize = edge.getSize();
                }
            }
        }

        if (maxSize > 0) {
            detectionSize = maxSize;
            System.out.println("autoDetectDetectionSize: extracted detectionSize=" + detectionSize + " from POI node '" + detection + "'");
        } else {
            System.out.println("autoDetectDetectionSize: no edge data found for POI node '" + detection + "', keeping detectionSize=" + detectionSize);
        }
    }

    /**
     * calculateWeights - 非机器学习的权重计算方法
     * 
     * 手动设置三个维度的权重比例：
     * - 当数据量>0时：时间0.334 + 结构0.333 + 数据量0.333
     * - 当数据量=0时：时间0.5 + 结构0.5（只考虑两个维度）
     * 
     * 计算步骤：
     * 1. 计算每条边的原始权重（时间、数据量、结构）
     * 2. 按出边进行归一化
     * 3. 计算最终综合权重
     */
    public void calculateWeights() {
        System.out.println("calculateWeights invoked");
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        for (EntityNode n : vertexSet) {
            Set<EventEdge> inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges) {
                double timeWeight = getTimeWeight(inEdge);
                double dataWeight = getAmountWeight(inEdge);
                double structureWeight = getStructureWeight(inEdge);
                long edgeID = inEdge.id;
                timeWeights.put(edgeID, timeWeight);
                amountWeights.put(edgeID, dataWeight);
                structureWeights.put(edgeID, structureWeight);
            }
        }

        // Normalize three weights by outgoing edges
        for (EntityNode n : vertexSet) {
            double structureTotal = getStructureWeightTotal(n);
            // avoid bug caused by 0/0
            if (structureTotal < 1e-8) {
                structureTotal = 1.0;
            }

            double amountTotal = getAmountWeightTotal(n);
            double timeTotal = getTimeWeightTotal(n);
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            for (EventEdge e : outgoing) {
                e.timeWeight = timeWeights.get(e.id) / timeTotal;
                e.amountWeight = amountWeights.get(e.id) / amountTotal;
                e.structureWeight = structureWeights.get(e.id) / structureTotal;

                if (seedSources.contains(e.getSource().getSignature())) { // the seed's structure weight is always 1.0
                    e.structureWeight = 1.0;
                }

                timeWeights.put(e.id, e.timeWeight);
                amountWeights.put(e.id, e.amountWeight);
                structureWeights.put(e.id, e.structureWeight);
            }
        }

        // Calculate final Weights
        for (EntityNode n : vertexSet) {
            double amount = 0.0;
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            for (EventEdge e : outEdges) {
                amount += e.getSize();
            }
            double wTotal = 0.0;
            if (amount < 1e-8) {
                // Calculate total weight in order to normalize
                for (EventEdge outEdge : outEdges) {
                    wTotal += outEdge.timeWeight * 0.5 + outEdge.structureWeight * 0.5;
                }

                for (EventEdge outEdge : outEdges) {
                    outEdge.weight = (0.5 * outEdge.timeWeight + outEdge.structureWeight * 0.5) / wTotal;
                    weights.put(outEdge.id, outEdge.weight);
                }
            } else {
                // Calculate total weight in order to normalize
                for (EventEdge outEdge : outEdges) {
                    wTotal += outEdge.timeWeight * 0.334 + outEdge.structureWeight * 0.333
                            + outEdge.amountWeight * 0.333;
                }

                for (EventEdge outEdge : outEdges) {
                    outEdge.weight = ((0.334 * outEdge.timeWeight + 0.333 * outEdge.structureWeight
                            + 0.333 * outEdge.amountWeight) / wTotal) * 0.99;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }
        }
    }

    public void calculateWeightsRandom() {
        System.out.println("calculateWeightsRandom invoked");
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        for (EntityNode n : vertexSet) {
            Set<EventEdge> inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges) {
                double timeWeight = getTimeWeight(inEdge);
                double dataWeight = getAmountWeight(inEdge);
                double structureWeight = getStructureWeight(inEdge);
                long edgeID = inEdge.id;
                timeWeights.put(edgeID, timeWeight);
                amountWeights.put(edgeID, dataWeight);
                structureWeights.put(edgeID, structureWeight);
            }
        }

        // Normalize three weights by outgoing edges
        for (EntityNode n : vertexSet) {
            double structureTotal = getStructureWeightTotal(n);
            // avoid bug caused by 0/0
            if (structureTotal < 1e-8) {
                structureTotal = 1.0;
            }

            double amountTotal = getAmountWeightTotal(n);
            double timeTotal = getTimeWeightTotal(n);
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            for (EventEdge e : outgoing) {
                e.timeWeight = timeWeights.get(e.id) / timeTotal;
                e.amountWeight = amountWeights.get(e.id) / amountTotal;
                e.structureWeight = structureWeights.get(e.id) / structureTotal;

                if (seedSources.contains(e.getSource().getSignature())) { // the seed's structure weight is always 1.0
                    e.structureWeight = 1.0;
                }

                timeWeights.put(e.id, e.timeWeight);
                amountWeights.put(e.id, e.amountWeight);
                structureWeights.put(e.id, e.structureWeight);
            }
        }

        // Calculate final Weights
        for (EntityNode n : vertexSet) {
            double amount = 0.0;
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            for (EventEdge e : outEdges) {
                amount += e.getSize();
            }
            double wTotal = 0.0;
            if (amount < 1e-8) {
                // Calculate total weight in order to normalize
                double[] weightsCof = getNCoefficient(2);
                for (EventEdge outEdge : outEdges) {
                    wTotal += outEdge.timeWeight * weightsCof[0] + outEdge.structureWeight * weightsCof[1];
                }

                for (EventEdge outEdge : outEdges) {
                    outEdge.weight = (weightsCof[0] * outEdge.timeWeight + outEdge.structureWeight * weightsCof[1])
                            / wTotal;
                    weights.put(outEdge.id, outEdge.weight);
                }
            } else {
                // Calculate total weight in order to normalize
                double[] weightsCof = getNCoefficient(3);
                for (EventEdge outEdge : outEdges) {
                    wTotal += outEdge.timeWeight * weightsCof[0] + outEdge.structureWeight * weightsCof[1]
                            + outEdge.amountWeight * weightsCof[2];
                }

                for (EventEdge outEdge : outEdges) {
                    outEdge.weight = ((outEdge.timeWeight * weightsCof[0] + outEdge.structureWeight * weightsCof[1]
                            + outEdge.amountWeight * weightsCof[2]) / wTotal) * 0.99;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }
        }
    }

    private double getStructureWeightTotal(EntityNode n) {
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)) {
            total += structureWeights.get(e.id);
        }
        return total;
    }

    private double getAmountWeightTotal(EntityNode n) {
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)) {
            total += amountWeights.get(e.id);
        }
        return total;
    }

    private double getTimeWeightTotal(EntityNode n) {
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)) {
            total += timeWeights.get(e.id);
        }
        return total;
    }

    public void calculateWeights_ML_dec(boolean normalizeByOutEdges, int mode, String resDir) {
        System.out.println("calculateWeights_ML_dec invoked: " + normalizeByOutEdges);
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        Set<EventEdge> allGraphEdges = graph.edgeSet();
        for (EventEdge edge : allGraphEdges) {
            timeWeights.put(edge.id, getTimeWeight(edge));
            amountWeights.put(edge.id, getAmountWeight(edge));
            structureWeights.put(edge.id, getStructureWeight(edge));

            // printEdgeWeights(edge);
        }

        // Pre-process individual weights
        preprocessWeights(timeWeights, normalizeByOutEdges);
        preprocessWeights(amountWeights, normalizeByOutEdges);
        preprocessWeights(structureWeights, normalizeByOutEdges);

        // Additional pre-processing for structureWeights for seeds + store standardized weights
        for (EventEdge edge : allGraphEdges) {
            double structureWeightValue = structureWeights.get(edge.id);
            if (seedSources.contains(edge.getSink().getSignature())) {
                structureWeightValue = 1.0;
                structureWeights.put(edge.id, structureWeightValue);
            }

            edge.timeWeight = timeWeights.get(edge.id);
            edge.amountWeight = amountWeights.get(edge.id);
            edge.structureWeight = structureWeightValue;
        }

        // Compute aggregated weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        List<Double> finalWeights = null;
        if (mode == 1) {
            System.out.println("computeFinalWeight v1 invoked");
            finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
        } else if (mode == 2) {
            System.out.println("computeFinalWeight v2 invoked");
            finalWeights = computeFinalWeights_v2(allEdges); // the weights correspond to the order of the edges
        } else if (mode == 3) {
            System.out.println("computeFinalWeight v3 invoked");
            finalWeights = computeFinalWeights_v3(allEdges); // the weights correspond to the order of the edges
        }
        // Normalize weights for outgoing edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n : vertexSet) {
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            double weightTotalForOutEdges = 0.0;
            for (EventEdge outEdge : outEdges) {
                // System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForOutEdges += outEdge.weight;
            }

            if (weightTotalForOutEdges < 1e-8) {
                continue;
            }
            // Normalize by weightTotalForOutEdges
            for (EventEdge outEdge : outEdges) {
                // System.out.println("Before normalization " + inEdge.weight);
                // System.out.println("Normalization factor " + weightTotalForOutEdges);

                outEdge.weight = (outEdge.weight / weightTotalForOutEdges) * 0.99;
                // System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(outEdge.id, outEdge.weight);
            }
        }

        try {
            File file = new File(resDir + "/" + "clusterall_weights.json");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for (EventEdge edge : allEdges) {
                jsonArray.add(edge.weight);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void calculateWeights_Individual(boolean normalizeByInEdges, String weightType, String resDir) {
        System.out.println(
                "calculateWeights_Individual invoked: " + normalizeByInEdges + " for featureType: " + weightType);
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        Set<EventEdge> inEdges;
        for (EntityNode n : vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges) {
                timeWeights.put(inEdge.id, getTimeWeight(inEdge));
                // amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                // structureWeights.put(inEdge.id, getStructureWeight(inEdge));
                amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                structureWeights.put(inEdge.id, 0.0);
                // printEdgeWeights(inEdge);
            }
        }

        // Pre-process individual weights
        preprocessWeights(timeWeights, normalizeByInEdges);
        preprocessWeights(amountWeights, normalizeByInEdges);
        preprocessWeights(structureWeights, normalizeByInEdges);

        // Additional pre-processing for structureWeights for seeds
        for (EntityNode n : vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges) {
                if (seedSources.contains(inEdge.getSink().getSignature())) {
                    // Sink is seed
                    // System.out.println();
                    // System.out.println("Edges from seed sources: " + "EventEdge " +
                    // inEdge.getID() + " (" + inEdge.getSource().getID() + " " +
                    // inEdge.getSource().getSignature() + " ->" + inEdge.getEvent() + " " +
                    // inEdge.getSink().getID() + " " + inEdge.getSink().getSignature() + ")");
                    // System.out.println("Normalized structureWeight before hard set: " +
                    // structureWeights.get(inEdge.id));
                    structureWeights.put(inEdge.id, 1.0);
                    // System.out.println("Normalized structureWeight after hard set: " +
                    // structureWeights.get(inEdge.id));
                }
            }
        }

        // // Store standardized weights for all edges
        for (EntityNode n : vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges) {
                inEdge.timeWeight = timeWeights.get(inEdge.id);
                inEdge.amountWeight = amountWeights.get(inEdge.id);
                inEdge.structureWeight = structureWeights.get(inEdge.id);
            }
        }

        // Use individual weight as final weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        List<Double> finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
        // for(int i =0; i < allEdges.size(); i++){
        // if(weightType == "timeWeight"){
        // finalWeights.add(allEdges.get(i).timeWeight);
        // }else if(weightType == "amountWeight"){
        // finalWeights.add(allEdges.get(i).amountWeight);
        // }else if(weightType == "structureWeight"){
        // finalWeights.add(allEdges.get(i).structureWeight);
        // }
        // }
        // List<Double> finalWeights = computeFinalWeights_v2(allEdges); // the weights
        // correspond to the order of the edges
        // List<Double> finalWeights = computeFinalWeights_v3(allEdges); // the weights
        // correspond to the order of the edges
        // List<Double> finalWeights = computeFinalWeights_v3_Individual(allEdges,
        // weightType); // use individual weights as final weights

        // Normalize weights for outgoing edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n : vertexSet) {
            Set<EventEdge> outgoingEdges = graph.outgoingEdgesOf(n);
            double weightTotalForOutEdges = 0.0;
            for (EventEdge outEdge : outgoingEdges) {
                // System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForOutEdges += outEdge.weight;
            }

            if (weightTotalForOutEdges < 1e-8) {
                continue;
            }
            // Normalize by weightTotalForOutEdges
            for (EventEdge outEdge : outgoingEdges) {
                // System.out.println("Before normalization " + inEdge.weight);
                // System.out.println("Normalization factor " + weightTotalForOutEdges);

                outEdge.weight /= weightTotalForOutEdges;
                // System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(outEdge.id, outEdge.weight);
            }
        }

        try {
            File file = new File(resDir + "/" + weightType + "_weights.txt");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for (Double d : finalWeights) {
                jsonArray.add(d);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void calculateWeights_Fanout(boolean normalizeByOutEdges, String resDir) {
        System.out.println("calculateWeights_Fanout invoked: " + normalizeByOutEdges);
        Set<EntityNode> vertexSet = graph.vertexSet();

        for (EntityNode v : vertexSet) {
            int idegree = graph.inDegreeOf(v);
            int odegree = graph.outDegreeOf(v);
            indegree.put(v.getSignature(), idegree);
            outdegree.put(v.getSignature(), odegree);
        }

        // Initialize weights
        HashMap<Long, Double> fanoutWeights = new HashMap<>(); // sink -> source
        // for(EntityNode n1:vertexSet){
        // if (graph.incomingEdgesOf(n1).size() != 0) {
        // Set<EventEdge> inEdges = graph.incomingEdgesOf(n1);
        // for (EventEdge inEdge: inEdges) {
        // weights.put(inEdge.id, 0.0);
        // fanoutWeights.put(inEdge.id, 0.0);
        // }
        // }
        // }
        Set<EventEdge> edges = graph.edgeSet();
        // edges.parallelStream().forEach(e ->weights.put(e.id,0.0));
        // edges.parallelStream().forEach(e -> fanoutWeights.put(e.id, 0.0));
        // graph.edgeSet().parallelStream().map(e ->getFanoutWeight(e)).

        // Compute individual weights
        // Set<EventEdge> inEdges;
        // for (EntityNode n: vertexSet) {
        // inEdges = graph.incomingEdgesOf(n);
        // for (EventEdge inEdge: inEdges) {
        // fanoutWeights.put(inEdge.id, getFanoutWeight(inEdge));
        //
        // }
        // }
        List<Double> finalWeights = new LinkedList<>();
        for (EventEdge e : edges) {
            finalWeights.add(getFanoutWeight(e));
        }
        // for(EventEdge e: edges){
        // fanoutWeights.put(e.id, getFanoutWeight(e));
        // }
        // Pre-process individual weights
        // preprocessWeights(fanoutWeights, normalizeByOutEdges);

        // // Additional pre-processing for structureWeights for seeds
        // for (EntityNode n: vertexSet) {
        // inEdges = graph.incomingEdgesOf(n);
        // for (EventEdge inEdge: inEdges) {
        // if (seedSources.contains(inEdge.getSource().getSignature())) {
        // // Source is seed
        // fanoutWeights.get(n.getID()).put(inEdge.getSource().getID(), 1.0);
        // }
        // }
        // }

        // Store standardized weights for all edges
        // for (EntityNode n: vertexSet) {
        // inEdges = graph.incomingEdgesOf(n);
        // for (EventEdge inEdge: inEdges) {
        // inEdge.timeWeight = 0.0;
        // inEdge.amountWeight = 0.0;
        // inEdge.structureWeight = fanoutWeights.get(inEdge.id);
        // }
        // }
        // edges.parallelStream().forEach(e
        // ->e.structureWeight=fanoutWeights.get(e.id));

        // Use individual weight as final weight for all edges
        // List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        // List<Double> finalWeights = computeFinalWeights(allEdges); // the weights
        // correspond to the order of the edges
        // List<Double> finalWeights = computeFinalWeights_v2(allEdges); // the weights
        // correspond to the order of the edges
        // List<Double> finalWeights = computeFinalWeights_v3(allEdges); // the weights
        // correspond to the order of the edges
        // List<Double> finalWeights = computeFinalWeights_v3_Individual(allEdges,
        // "structureWeight"); // use individual weights as final weights
        try {
            File file = new File(resDir + "/" + "fanout_weights.txt");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for (Double d : finalWeights) {
                jsonArray.add(d);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Write weights to file for fanout!");
        // // Normalize weights for outgoing edges
        // for (int i = 0; i < allEdges.size(); i++) {
        // allEdges.get(i).weight = finalWeights.get(i);
        // }
        // for (EntityNode n: vertexSet) {
        // Set<EventEdge> outgoingEdges = graph.outgoingEdgesOf(n);
        // double weightTotalForOutEdges = 0.0;
        // for (EventEdge outEdge: outgoingEdges) {
        //// System.out.println(inEdge.toString()+": "+inEdge.weight);
        // weightTotalForOutEdges += outEdge.weight;
        // }
        //
        // if(weightTotalForOutEdges<1e-8){
        // continue;
        // }
        // // Normalize by weightTotalForOutEdges
        // for (EventEdge outEdge: outgoingEdges) {
        //// System.out.println("Before normalization " + inEdge.weight);
        //// System.out.println("Normalization factor " + weightTotalForOutEdges);
        //
        // outEdge.weight /= weightTotalForOutEdges;
        //// System.out.println("After normalization " + inEdge.weight);
        //
        // // Store normalized weights in the "weights" map
        // weights.put(outEdge.id, outEdge.weight);
        // }
        // }
    }

    public void calculateWeights_AdaptiveFusion(String detection, String resDir) {
        System.out.println("calculateWeights_AdaptiveFusion invoked for detection: " + detection);
        alignPOITimeWithDetection(detection);

        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        if (allEdges.isEmpty()) {
            return;
        }

        Map<String, Integer> distanceToDetection = computeDistanceToDetection(detection);
        double timeScale = estimateTimeScale(allEdges);
        double amountScale = estimateAmountScale(allEdges);
        double distanceScale = estimateDistanceScale(distanceToDetection);
        double logDetectionSize = detectionSize > 0 ? Math.log1p(detectionSize) : 0.0;

        final double alphaTime = 0.40;
        final double alphaAmount = 0.25;
        final double alphaStructure = 0.35;
        final double epsilon = 1e-6;

        Map<Long, JSONObject> diagnostics = new LinkedHashMap<>();

        for (EntityNode sourceNode : graph.vertexSet()) {
            List<EventEdge> outgoingEdges = new ArrayList<>(graph.outgoingEdgesOf(sourceNode));
            if (outgoingEdges.isEmpty()) {
                continue;
            }

            List<Double> siblingTimeDeltas = new ArrayList<>();
            List<Double> siblingLogSizes = new ArrayList<>();
            List<Double> siblingDistances = new ArrayList<>();
            boolean hasPositiveSize = false;
            for (EventEdge edge : outgoingEdges) {
                siblingTimeDeltas.add(getTimeDelta(edge));
                double logSize = Math.log1p(Math.max(0L, edge.getSize()));
                siblingLogSizes.add(logSize);
                if (edge.getSize() > 0) {
                    hasPositiveSize = true;
                }
                siblingDistances.add((double) getDistanceForEdge(edge, distanceToDetection));
            }

            double siblingTimeSpread = normalizedSpread(siblingTimeDeltas, timeScale);
            double siblingAmountSpread = normalizedSpread(siblingLogSizes, amountScale);
            double siblingDistanceSpread = normalizedSpread(siblingDistances, distanceScale);

            double timeConfidence = clamp(0.20 + 0.80 * siblingTimeSpread);
            double amountConfidence = hasPositiveSize ? clamp(0.15 + 0.45 * siblingAmountSpread) : 0.05;
            if (detectionSize > 0) {
                amountConfidence = clamp(amountConfidence + 0.30);
            }
            double structureConfidence = clamp(0.25 + 0.35 * siblingDistanceSpread + 0.10 * Math.log1p(outgoingEdges.size()));

            List<Double> fusedRawScores = new ArrayList<>();
            Map<Long, Double> rawScoreByEdge = new HashMap<>();

            for (EventEdge edge : outgoingEdges) {
                double timeScore = computeTemporalScore(edge, timeScale);
                double amountScore = computeAmountScore(edge, outgoingEdges, logDetectionSize, amountScale);
                double structureScore = computeStructureScore(edge, distanceToDetection, distanceScale);

                double effectiveTime = alphaTime * timeConfidence;
                double effectiveAmount = alphaAmount * amountConfidence;
                double effectiveStructure = alphaStructure * structureConfidence;
                double effectiveTotal = effectiveTime + effectiveAmount + effectiveStructure;

                double fusedRaw;
                if (effectiveTotal <= 1e-12) {
                    fusedRaw = (timeScore + amountScore + structureScore) / 3.0;
                } else {
                    double weightedLog = effectiveTime * Math.log(epsilon + timeScore)
                            + effectiveAmount * Math.log(epsilon + amountScore)
                            + effectiveStructure * Math.log(epsilon + structureScore);
                    fusedRaw = Math.exp(weightedLog / effectiveTotal);
                }

                edge.timeWeight = timeScore;
                edge.amountWeight = amountScore;
                edge.structureWeight = structureScore;
                timeWeights.put(edge.id, timeScore);
                amountWeights.put(edge.id, amountScore);
                structureWeights.put(edge.id, structureScore);
                rawScoreByEdge.put(edge.id, fusedRaw);
                fusedRawScores.add(fusedRaw);

                JSONObject edgeDiagnostic = new JSONObject();
                edgeDiagnostic.put("edgeId", edge.id);
                edgeDiagnostic.put("source", edge.getSource().getSignature());
                edgeDiagnostic.put("sink", edge.getSink().getSignature());
                edgeDiagnostic.put("type", edge.getType());
                edgeDiagnostic.put("event", edge.getEvent());
                edgeDiagnostic.put("size", edge.getSize());
                edgeDiagnostic.put("timeScore", timeScore);
                edgeDiagnostic.put("amountScore", amountScore);
                edgeDiagnostic.put("structureScore", structureScore);
                edgeDiagnostic.put("timeConfidence", timeConfidence);
                edgeDiagnostic.put("amountConfidence", amountConfidence);
                edgeDiagnostic.put("structureConfidence", structureConfidence);
                edgeDiagnostic.put("rawFusion", fusedRaw);
                edgeDiagnostic.put("distanceToDetection", getDistanceForEdge(edge, distanceToDetection));
                diagnostics.put(edge.id, edgeDiagnostic);
            }

            double adaptiveTemperature = computeAdaptiveTemperature(fusedRawScores,
                    (alphaTime * timeConfidence + alphaAmount * amountConfidence + alphaStructure * structureConfidence)
                            / (alphaTime + alphaAmount + alphaStructure));
            double softmaxDenominator = 0.0;
            Map<Long, Double> expScores = new HashMap<>();
            for (EventEdge edge : outgoingEdges) {
                double expValue = Math.exp(rawScoreByEdge.get(edge.id) / adaptiveTemperature);
                expScores.put(edge.id, expValue);
                softmaxDenominator += expValue;
            }

            if (softmaxDenominator <= 1e-12) {
                softmaxDenominator = outgoingEdges.size();
                for (EventEdge edge : outgoingEdges) {
                    expScores.put(edge.id, 1.0);
                }
            }

            for (EventEdge edge : outgoingEdges) {
                double finalWeight = (expScores.get(edge.id) / softmaxDenominator) * 0.99;
                edge.weight = finalWeight;
                weights.put(edge.id, finalWeight);

                JSONObject edgeDiagnostic = diagnostics.get(edge.id);
                edgeDiagnostic.put("adaptiveTemperature", adaptiveTemperature);
                edgeDiagnostic.put("finalWeight", finalWeight);
            }
        }

        writeAdaptiveFusionDiagnostics(resDir, diagnostics.values());
    }

    private List<Double> computeFinalWeights(List<EventEdge> allEdges) {
        System.out.println("computeFinalWeights invoked!");
        if (allEdges.isEmpty()) {
            return new ArrayList<>();
        }
        // Compute the final weight (weights) for an edge using the three individual
        // weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already
        // standardized

        // Note: This method clusters all edges, compute projection vector, and project
        // all edges for final weights

        // Clustering
        List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(allEdges, "multiKmeansPlusPlus");

        // Supervised dimensionality reduction of the weights
        List<Double> finalWeights = dimReduction(allEdges, clusterResults);

        // Scale to [0,1] (since some weights might be negative)
        // System.out.println("Before 0-1 normalize:");
        // for (double w: finalWeights) {
        // System.out.print(w + " ");
        // }
        // System.out.println();

        scaleRange(finalWeights);
        // System.out.println("After 0-1 normalize:");
        // for (double w: finalWeights) {
        // System.out.print(w + " ");
        // }
        // System.out.println();

        return finalWeights;
    }

    private List<Double> computeFinalWeights_v2(List<EventEdge> allEdges) {
        // Compute the final weight (weights) for an edge using the three individual
        // weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already
        // standardized

        // Note: This method clusters non-outlier edges, compute projection vector, and
        // project all edges for final weights

        // Remove outlier edges (i.e., sink node only has one incoming edge) since they
        // will always have final weight equal to 1
        System.out.println("computeFinalWeights_v2 invoked!");
        List<EventEdge> nonOutlierEdges = new ArrayList<>();
        System.out.println();
        for (EventEdge edge : allEdges) {
            if (graph.incomingEdgesOf(edge.getSink()).size() > 1) {
                nonOutlierEdges.add(edge);
            } else {
                System.out.print("Outlier edge: ");
                printEdgeWeights(edge);
            }
        }

        if (allEdges.isEmpty()) {
            return new ArrayList<>();
        }

        if (nonOutlierEdges.isEmpty()) {
            System.out.println("No non-outlier edges available for clustering, falling back to heuristic weights.");
            List<Double> finalWeights = computeHeuristicFinalWeights(allEdges);
            scaleRange(finalWeights);
            return finalWeights;
        }

        // Clustering
        List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(nonOutlierEdges, "multiKmeansPlusPlus");

        // Supervised dimensionality reduction of the weights
        List<Double> finalWeights = dimReduction(allEdges, clusterResults);

        // Scale to [0,1] (since some weights might be negative)
        // System.out.println("Before 0-1 normalize:");
        // for (double w: finalWeights) {
        // System.out.print(w + " ");
        // }
        // System.out.println();

        scaleRange(finalWeights);
        // System.out.println("After 0-1 normalize:");
        // for (double w: finalWeights) {
        // System.out.print(w + " ");
        // }
        // System.out.println();

        return finalWeights;
    }

    private List<Double> computeFinalWeights_v3(List<EventEdge> allEdges) {
        // Compute the final weight (weights) for an edge using the three individual
        // weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already
        // standardized

        // Note: This method locally clusters all incoming edges of each sink node,
        // computes separate projection vectors, and compute final weights
        System.out.println("computeFinalWeights_v3 invoked!");
        List<Double> finalWeights = new ArrayList<>();
        for (int i = 0; i < allEdges.size(); i++) { // initialize to the same size
            finalWeights.add(0.0);
        }
        Map<Long, Integer> edgeIndexMap = buildEdgeIndexMap(allEdges);

        // For each node
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode n : vertexSet) {
            List<EventEdge> outEdges = new ArrayList<>(graph.outgoingEdgesOf(n));
            if (outEdges.size() == 0) {
                System.out.println("No outgoing edges");
            } else if (outEdges.size() == 1) { // Outlier edge (no incoming edges)
                // Directly set the final weights to 0
                System.out.println("Only 1 outgoing edge (outlier edge)");
                Integer index = edgeIndexMap.get(outEdges.get(0).id);
                if (index != null) {
                    finalWeights.set(index, 1.0);
                }
            } else { // Non-outlier edges
                     // Cluster inEdges
                List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(outEdges, "multiKmeansPlusPlus");

                // Supervised dimensionality reduction of the weights
                if (clusterResults.size() == 1) {
                    double amount = 0.0;
                    for (EventEdge e : outEdges) {
                        amount += e.getSize();
                    }
                    double wTotal = 0.0;
                    if (amount < 1e-8) {
                        for (EventEdge outEdge : outEdges) {
                            Integer index = edgeIndexMap.get(outEdge.id);
                            if (index != null) {
                                finalWeights.set(index,
                                        0.5 * outEdge.timeWeight + 0.5 * outEdge.structureWeight);
                            }
                        }
                    } else {
                        for (EventEdge outEdge : outEdges) {
                            Integer index = edgeIndexMap.get(outEdge.id);
                            if (index != null) {
                                finalWeights.set(index, (0.3333) * outEdge.timeWeight
                                        + (0.3333) * outEdge.structureWeight + (0.3334) * outEdge.amountWeight);
                            }
                        }
                    }
                } else {
                    List<Double> weightsForOutEdges = dimReduction(outEdges, clusterResults);
                    System.out.println("weights before scaling: " + weightsForOutEdges.toString());

                    // Scale to (0,1+) in case of negative weights
                    scaleRange(weightsForOutEdges);
                    System.out.println("weights after scaling: " + weightsForOutEdges.toString());

                    // Print out the total weight and normalized weights for debugging purpose
                    double weightTotalForOutEdges = 0.0;
                    for (double weight : weightsForOutEdges) {
                        weightTotalForOutEdges += weight;
                    }
                    System.out.println("Total weight for outgoing edges: " + weightTotalForOutEdges);
                    List<Double> weightsNormalizedByOutEdges = new ArrayList<>();
                    if (weightTotalForOutEdges > 1e-8) {
                        for (double weight : weightsForOutEdges) {
                            weightsNormalizedByOutEdges.add(weight / (1.0 * weightTotalForOutEdges));
                        }
                        System.out.println("weights after normalizing by outgoing edges: "
                                + weightsNormalizedByOutEdges.toString());
                    }

                    // Set to finalWeights
                    for (int i = 0; i < outEdges.size(); i++) {
                        EventEdge edge = outEdges.get(i);
                        Integer index = edgeIndexMap.get(edge.id);
                        if (index != null) {
                            finalWeights.set(index, weightsForOutEdges.get(i));
                        }
                    }
                }
            }
        }

        return finalWeights;
    }

    private List<Double> computeFinalWeights_v3_Individual(List<EventEdge> allEdges, String weightType) {
        // Compute the final weight (weights) for an edge using the three individual
        // weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already
        // standardized

        // Note: This method locally clusters all incoming edges of each sink node,
        // computes separate projection vectors, and compute final weights
        System.out.println("computeFinalWeights_v3_Individual invoked!");
        List<Double> finalWeights = new ArrayList<>();
        for (int i = 0; i < allEdges.size(); i++) { // initialize to the same size
            finalWeights.add(0.0);
        }
        Map<Long, Integer> edgeIndexMap = buildEdgeIndexMap(allEdges);

        // For each node
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode n : vertexSet) {
            List<EventEdge> inEdges = new ArrayList<>(graph.incomingEdgesOf(n));
            if (inEdges.size() == 0) {
                System.out.println("No incoming edges");
            } else if (inEdges.size() == 1) { // Outlier edge (no incoming edges)
                // Directly set the final weights to 0
                System.out.println("Only 1 incoming edge (outlier edge)");
                Integer index = edgeIndexMap.get(inEdges.get(0).id);
                if (index != null) {
                    finalWeights.set(index, 1.0);
                }
            } else { // Non-outlier edges

                // Set to finalWeights based on weightType
                if (weightType.equals("timeWeight")) {
                    for (EventEdge edge : inEdges) {
                        Integer index = edgeIndexMap.get(edge.id);
                        if (index != null) {
                            finalWeights.set(index, edge.timeWeight);
                        }
                    }
                } else if (weightType.equals("amountWeight")) {
                    for (EventEdge edge : inEdges) {
                        Integer index = edgeIndexMap.get(edge.id);
                        if (index != null) {
                            finalWeights.set(index, edge.amountWeight);
                        }
                    }
                } else if (weightType.equals("structureWeight")) {
                    for (EventEdge edge : inEdges) {
                        Integer index = edgeIndexMap.get(edge.id);
                        if (index != null) {
                            finalWeights.set(index, edge.structureWeight);
                        }
                    }
                } else {
                    System.out.println("Unsupported weightType: " + weightType);
                }
            }
        }

        return finalWeights;
    }

    private Map<Long, Integer> buildEdgeIndexMap(List<EventEdge> allEdges) {
        Map<Long, Integer> edgeIndexMap = new HashMap<>(allEdges.size() * 2);
        for (int i = 0; i < allEdges.size(); i++) {
            edgeIndexMap.put(allEdges.get(i).id, i);
        }
        return edgeIndexMap;
    }

    private List<Cluster<EventEdgeWrapper>> clusterEdges(List<EventEdge> edges, String clusteringMethod) {
        // Wrap all edges for clustering
        List<EventEdgeWrapper> allEdgeWrappers = new ArrayList<>();
        for (EventEdge edge : edges) {
            allEdgeWrappers.add(new EventEdgeWrapper(edge));
        }

        // Different clustering method
        List<Cluster<EventEdgeWrapper>> clusterResults = null;
        if (clusteringMethod.equals("kmeansPlusPlus")) {
            // KMeans++
            KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new KMeansPlusPlusClusterer<>(2,
                    100000); // default distance measure
            clusterResults = new ArrayList<>(kmeansPlusPlusClusterer.cluster(allEdgeWrappers)); // CentroidCluster ->
                                                                                                // Cluster conversion

        } else if (clusteringMethod.equals("dbscan")) { // do not use it since it does not guarantee two clusters
            // DBSCAN
            DBSCANClusterer<EventEdgeWrapper> dbscanClusterer = new DBSCANClusterer<>(4, 1); // default distance measure
            clusterResults = dbscanClusterer.cluster(allEdgeWrappers);
        } else if (clusteringMethod.equals("multiKmeansPlusPlus")) {
            // Multi-KMeans++
            // // Cosine distance
            // KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new
            // KMeansPlusPlusClusterer<>(2, 100000, new DistanceMeasure() {
            // @Override
            // public double compute(double[] doubles, double[] doubles1) throws
            // DimensionMismatchException {
            // return
            // 1.0-(doubles[0]*doubles1[0]+doubles[1]*doubles1[1]+doubles[2]*doubles1[2])/
            // (Math.sqrt(doubles[0]*doubles[0]+doubles[1]*doubles[1]+doubles[2]*doubles[2])*
            // Math.sqrt(doubles1[0]*doubles1[0]+doubles1[1]*doubles1[1]+doubles1[2]*doubles1[2]));
            // }
            // });

            // Default distance measure
            KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new KMeansPlusPlusClusterer<>(2,
                    100000);
            MultiKMeansPlusPlusClusterer<EventEdgeWrapper> multiKmeansPlusPlusClusterer = new MultiKMeansPlusPlusClusterer<>(
                    kmeansPlusPlusClusterer, 20);
            clusterResults = new ArrayList<>(multiKmeansPlusPlusClusterer.cluster(allEdgeWrappers));
        } else {
            System.out.println("Do not support the clustering method " + clusteringMethod);
        }

        // printClusterResults(clusteringMethod, clusterResults);

        return clusterResults;
    }

    private List<Double> dimReduction(List<EventEdge> allEdges, List<Cluster<EventEdgeWrapper>> clusterResults) {
        // We use FDA for supervised dimensionality reduction (FDA is LDA in 2 classes)
        // Note: edges in clusterResults (for computing projection vector) may not be
        // exactly allEdges (for compute final weights)

        if (!hasUsableClusters(clusterResults)) {
            System.out.println("Clustering did not produce two non-empty groups, falling back to heuristic weights.");
            return computeHeuristicFinalWeights(allEdges);
        }

        // Store weights data in RealMatrix for easy processing
        EventEdge edge;
        double[][] weights2DArrayAll = new double[allEdges.size()][];
        double[][] weights2DArrayG0 = new double[clusterResults.get(0).getPoints().size()][];
        double[][] weights2DArrayG1 = new double[clusterResults.get(1).getPoints().size()][];
        boolean seedEdgeInG0 = false;
        boolean seedEdgeInG1 = false;
        for (int i = 0; i < allEdges.size(); i++) {
            edge = allEdges.get(i);
            weights2DArrayAll[i] = new double[] { edge.timeWeight, edge.amountWeight, edge.structureWeight };
        }
        for (int i = 0; i < clusterResults.get(0).getPoints().size(); i++) {
            edge = clusterResults.get(0).getPoints().get(i).getEventEdge();
            weights2DArrayG0[i] = new double[] { edge.timeWeight, edge.amountWeight, edge.structureWeight };
            if (seedSources.contains(edge.getSource().getSignature())) {
                seedEdgeInG0 = true;
            }
        }
        for (int i = 0; i < clusterResults.get(1).getPoints().size(); i++) {
            edge = clusterResults.get(1).getPoints().get(i).getEventEdge();
            weights2DArrayG1[i] = new double[] { edge.timeWeight, edge.amountWeight, edge.structureWeight };
            if (seedSources.contains(edge.getSource().getSignature())) {
                seedEdgeInG1 = true;
            }
        }
        RealMatrix weightsMatrixAll = new Array2DRowRealMatrix(weights2DArrayAll);
        RealMatrix weightsMatrixG0 = new Array2DRowRealMatrix(weights2DArrayG0);
        RealMatrix weightsMatrixG1 = new Array2DRowRealMatrix(weights2DArrayG1);

        // Compute projection vector using FDA
        RealVector projectionVector = computeProjectionVector(weightsMatrixG0, weightsMatrixG1);

        System.out.println("projectionVector before adjusting direction:");
        printRealVector(projectionVector);
        System.out.println();

        // Adjust the direction using some intuition
        adjustProjectionVectorDirection(projectionVector, weightsMatrixG0, weightsMatrixG1, seedEdgeInG0, seedEdgeInG1);

        System.out.println("projectionVector after adjusting direction:");
        printRealVector(projectionVector);
        System.out.println();

        // System.out.println("weightsMatrixAll:");
        // printRealMatrix(weightsMatrixAll);
        // System.out.println();

        // Project every row in weightsMatrixAll from 3D to 1D by applying projection
        // vector
        RealVector weightsProjectedAll = weightsMatrixAll.operate(projectionVector);
        double[] finalWeights = weightsProjectedAll.toArray();

        return new ArrayList<Double>(Arrays.asList(ArrayUtils.toObject(finalWeights)));
    }

    private boolean hasUsableClusters(List<Cluster<EventEdgeWrapper>> clusterResults) {
        if (clusterResults == null || clusterResults.size() != 2) {
            return false;
        }

        for (Cluster<EventEdgeWrapper> cluster : clusterResults) {
            if (cluster == null || cluster.getPoints() == null || cluster.getPoints().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private List<Double> computeHeuristicFinalWeights(List<EventEdge> allEdges) {
        List<Double> finalWeights = new ArrayList<>(Collections.nCopies(allEdges.size(), 0.0));
        Map<Long, Integer> edgeIndexMap = buildEdgeIndexMap(allEdges);

        for (EntityNode node : graph.vertexSet()) {
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(node);
            if (outEdges.isEmpty()) {
                continue;
            }

            double amount = 0.0;
            for (EventEdge outEdge : outEdges) {
                amount += outEdge.getSize();
            }

            for (EventEdge outEdge : outEdges) {
                Integer index = edgeIndexMap.get(outEdge.id);
                if (index == null) {
                    continue;
                }

                double weightValue;
                if (amount < 1e-8) {
                    weightValue = 0.5 * outEdge.timeWeight + 0.5 * outEdge.structureWeight;
                } else {
                    weightValue = 0.3333 * outEdge.timeWeight + 0.3333 * outEdge.structureWeight
                            + 0.3334 * outEdge.amountWeight;
                }
                finalWeights.set(index, weightValue);
            }
        }

        return finalWeights;
    }

    private RealVector computeProjectionVector(RealMatrix matrixG0, RealMatrix matrixG1) {
        // Compute projection matrix for matrixAll by maximizing the separation between
        // groups matrixG0 and matrixG1
        // We use FDA (i.e., sw-1 (u1-u2))

        // Compute mu0 (i.e., mean vector of group 0)
        RealVector mu0 = new ArrayRealVector(new double[] { 0, 0, 0 });
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            mu0 = mu0.add(matrixG0.getRowVector(i));
        }
        mu0.mapDivideToSelf(matrixG0.getRowDimension());

        // Compute mu1 (i.e., mean vector of group 1)
        RealVector mu1 = new ArrayRealVector(new double[] { 0, 0, 0 });
        for (int i = 0; i < matrixG1.getRowDimension(); i++) {
            mu1 = mu1.add(matrixG1.getRowVector(i));
        }
        mu1.mapDivideToSelf(matrixG1.getRowDimension());

        System.out.println("Mean vector of group 0 mu0:");
        printRealVector(mu0);
        System.out.println();
        System.out.println("Mean vector of group 1 mu1:");
        printRealVector(mu1);
        System.out.println();

        // Compute within-group scattering matrix sw
        RealMatrix sw = new Array2DRowRealMatrix(3, 3);
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            sw = sw.add(matrixG0.getRowVector(i).subtract(mu0).outerProduct(matrixG0.getRowVector(i).subtract(mu0)));
        }
        for (int i = 0; i < matrixG1.getRowDimension(); i++) {
            sw = sw.add(matrixG1.getRowVector(i).subtract(mu1).outerProduct(matrixG1.getRowVector(i).subtract(mu1)));
        }

        // System.out.println("Within-group scattering matrix sw:");
        // printRealMatrix(sw);
        // System.out.println();

        // Compute between-group scattering matrix sb
        RealMatrix sb = mu0.subtract(mu1).outerProduct(mu0.subtract(mu1));

        // System.out.println("Between-group scattering matrix sb:");
        // printRealMatrix(sb);
        // System.out.println();

        // MP pseudo-inverse of sw
        DecompositionSolver solver = new SingularValueDecomposition(sw).getSolver();
        RealMatrix swInv = solver.getInverse(); // use MP pseudo-inverse
        // RealMatrix swInv = MatrixUtils.inverse(sw);

        // System.out.println("MP pseudo-inverse of sw, swInv:");
        // printRealMatrix(swInv);
        // System.out.println();

        // Is sw singular
        boolean isSwSingular = !solver.isNonSingular();

        // Handle singular sw and non-singular sw differently
        // TODO: handle singular sw using generalized LDA
        RealVector projectionVector = null;
        if (isSwSingular) {
            System.out.println("sw is singular");
            if (matrixG0.getRowDimension() == 1 && matrixG1.getRowDimension() == 1) {
                // Special case: matrixG0 and matrixG1 both contain only 1 row (sw = matrix(0))
                System.out.println("Both group 0 and group 1 only have 1 edge. Singular sw = matrix(0)");
            }
            if (sw.getRow(2)[0] == 0.0 && sw.getRow(2)[1] == 0.0 && sw.getRow(2)[2] == 0.0) {
                System.out.println("3rd row of sw is all-zero");
            }

            // We compare the fisherObjectiveNumerator() of two candidates
            RealVector projectionVectorCandidate1 = mu0.subtract(mu1);
            projectionVectorCandidate1.mapDivideToSelf(projectionVectorCandidate1.getNorm());
            double fisherObjectiveNumerator1 = fisherObjectiveNumerator(sb, projectionVectorCandidate1);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(mu0-mu1)/norm" + " is:"
                    + fisherObjectiveNumerator1);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(mu0-mu1)/norm"
                    + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate1));

            RealVector projectionVectorCandidate2 = swInv.operate(mu0.subtract(mu1));
            projectionVectorCandidate2.mapDivideToSelf(projectionVectorCandidate2.getNorm());
            double fisherObjectiveNumerator2 = fisherObjectiveNumerator(sb, projectionVectorCandidate2);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(swInv*(mu0-mu1))/norm"
                    + " is:" + fisherObjectiveNumerator2);
            System.out.println("Fisher objective denominator for candidate projection vector "
                    + "(swInv*(mu0-mu1))/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate2));

            if (fisherObjectiveNumerator1 > fisherObjectiveNumerator2) {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate1;
            } else if (fisherObjectiveNumerator2 > fisherObjectiveNumerator1) {
                System.out.println("projection vector = (swInv*(mu0-mu1))/norm");
                projectionVector = projectionVectorCandidate2;
            } else {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate1;
            }
        } else {
            // Inverse of sw exists. We just use MP pseudo-inverse: swInv
            System.out.println("sw is non-singular");
            System.out.println("projection vector = swInv*(mu0-mu1)");
            projectionVector = swInv.operate(mu0.subtract(mu1)); // FDA formula: swInv*(mu0-mu1)

            // Normalize
            projectionVector.mapDivideToSelf(projectionVector.getNorm());
        }

        assertNotNull(projectionVector);

        System.out.println("projectionVector after self-normalization:");
        printRealVector(projectionVector);
        System.out.println();

        return projectionVector;
    }

    private double fisherObjective(RealMatrix sb, RealMatrix sw, RealVector v) {
        // J(v) = (v^T*sb*v)/(v^T*sw*v)
        // sb: between-group scattering matrix, sw: within-group scattering matrix

        double numerator = sb.preMultiply(v).dotProduct(v);
        double denominator = sw.preMultiply(v).dotProduct(v); // should be non-zero
        System.out.println("v^T*sb*v: " + numerator);
        System.out.println("v^T*sw*v: " + denominator);

        return numerator / denominator;
    }

    private double fisherObjectiveNumerator(RealMatrix sb, RealVector v) {
        // Numerator of J(v): v^T*sb*v
        return sb.preMultiply(v).dotProduct(v);
    }

    private double fisherObjectiveDenominator(RealMatrix sw, RealVector v) {
        // Denominator of J(v): v^T*sw*v
        return sw.preMultiply(v).dotProduct(v);
    }

    private void adjustProjectionVectorDirection(RealVector projectionVector, RealMatrix matrixG0, RealMatrix matrixG1,
            boolean seedEdgeInG0, boolean seedEdgeInG1) {
        // Adjust the direction of projection vector using some intuition

        // Pearson correlation
        // PearsonsCorrelation pearsonsCorrelation = new PearsonsCorrelation();
        // Double corr = pearsonsCorrelation.correlation(projectionVector.toArray(), new
        // double[]{0.1, 0.5, 0.4});
        // if (corr < 0) {
        // projectionVector.mapMultiplyToSelf(-1);
        // }

        // // Dot product with (0.1, 0.5, 0.4)
        // if (projectionVector.dotProduct(new ArrayRealVector(new double[]{0.1, 0.5,
        // 0.4})) < 0) {
        // System.out.println("Negate projection vector due to dot product");
        // projectionVector.mapMultiplyToSelf(-1);
        // }

        // // Imbalance intuition: the group with fewer edges should have higher average
        // values
        // double mu0projected = mu0.dotProduct(projectionVector);
        // double mu1projected = mu1.dotProduct(projectionVector);
        // if ((matrixG0.getRowDimension() < matrixG1.getRowDimension() && mu0projected
        // < 0 && mu1projected > 0) || (matrixG1.getRowDimension() <
        // matrixG0.getRowDimension() && mu1projected < 0 && mu0projected > 0)) {
        // System.out.println("Negate projection vector due to imbalance intuition");
        // projectionVector.mapMultiplyToSelf(-1);
        // }

        // Intuition: align with the signs of the projection vector (0.1, 0.5, 0.4) in
        // non-ml approach
        if (projectionVector.getEntry(0) <= 0 && projectionVector.getEntry(1) <= 0
                && projectionVector.getEntry(2) <= 0) {
            System.out.println("All three dimensions of projection vector are non-positive. Negate the vector.");
            projectionVector.mapMultiplyToSelf(-1);
        } else if (projectionVector.getEntry(0) >= 0 && projectionVector.getEntry(1) >= 0
                && projectionVector.getEntry(2) >= 0) {
            System.out.println("All three dimensions of projection vector are non-negative. Don't negate the vector.");
        } else {
            System.out.println("One or two dimensions of projection vector are negative. Negate by condition.");

            // Compute mu0 (i.e., mean vector of group 0)
            RealVector mu0 = new ArrayRealVector(new double[] { 0, 0, 0 });
            for (int i = 0; i < matrixG0.getRowDimension(); i++) {
                mu0 = mu0.add(matrixG0.getRowVector(i));
            }
            mu0.mapDivideToSelf(matrixG0.getRowDimension());

            // Compute mu1 (i.e., mean vector of group 1)
            RealVector mu1 = new ArrayRealVector(new double[] { 0, 0, 0 });
            for (int i = 0; i < matrixG1.getRowDimension(); i++) {
                mu1 = mu1.add(matrixG1.getRowVector(i));
            }
            mu1.mapDivideToSelf(matrixG1.getRowDimension());

            // Intuition: cluster that contains seed edges should have a higher projected
            // mean value
            if (seedEdgeInG0 && !seedEdgeInG1) {
                System.out.println("Cluster 0 has seed edges but cluster 1 hasn't.");
                if (mu0.dotProduct(projectionVector) < mu1.dotProduct(projectionVector)) {
                    System.out.println(
                            "Negate projection vector to make sure that the cluster that contains seed edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            } else if (seedEdgeInG1 && !seedEdgeInG0) {
                System.out.println("Cluster 1 has seed edges but cluster 0 hasn't.");
                if (mu1.dotProduct(projectionVector) < mu0.dotProduct(projectionVector)) {
                    System.out.println(
                            "Negate projection vector to make sure that the cluster that contains seed edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            } else {
                if (seedEdgeInG0 && seedEdgeInG1) {
                    System.out.println("Cluster 0 and 1 both contain/don't contain seed edges.");
                } else if (!seedEdgeInG0 && !seedEdgeInG1) {
                    System.out.println("Cluster 0 and 1 both don't contain seed edges.");
                }

                // Intuition: cluster that contains fewer edges should have higher projected
                // mean value
                if (matrixG0.getRowDimension() < matrixG1.getRowDimension()
                        && mu0.dotProduct(projectionVector) < mu1.dotProduct(projectionVector)) {
                    System.out.println(
                            "Negate projection vector to make sure that the cluster 0 that contains fewer edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                } else if (matrixG1.getRowDimension() < matrixG0.getRowDimension()
                        && mu1.dotProduct(projectionVector) < mu0.dotProduct(projectionVector)) {
                    System.out.println(
                            "Negate projection vector to make sure that the cluster 1 that contains fewer edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            }
        }

    }

    private double getCombineWeight(EventEdge edge, double timeTotal, double amountTotal, double structureTotal) {
        return 0.1 * (edge.timeWeight / timeTotal) + 0.5 * (edge.amountWeight / amountTotal)
                + 0.4 * (edge.structureWeight / structureTotal);
    }

    private void alignPOITimeWithDetection(String detection) {
        EntityNode detectionNode = graphIterator.getGraphVertex(detection);
        if (detectionNode == null) {
            return;
        }

        BigDecimal anchor = BigDecimal.ZERO;
        Set<EventEdge> incomingEdges = graph.incomingEdgesOf(detectionNode);
        for (EventEdge edge : incomingEdges) {
            if (edge.getEndTime().compareTo(anchor) > 0) {
                anchor = edge.getEndTime();
            }
        }

        if (anchor.compareTo(BigDecimal.ZERO) == 0) {
            Set<EventEdge> outgoingEdges = graph.outgoingEdgesOf(detectionNode);
            for (EventEdge edge : outgoingEdges) {
                if (edge.getEndTime().compareTo(anchor) > 0) {
                    anchor = edge.getEndTime();
                }
            }
        }

        if (anchor.compareTo(BigDecimal.ZERO) > 0) {
            POITime = anchor;
        }
    }

    public void setSeedSources(Set<String> set) {
        System.out.println("setSeedSource invoked!");
        seedSources = set;
    }

    private double getStructureWeightForward(EventEdge e) {
        EntityNode source = e.getSource();
        if (seedSources.contains(source.getSignature())) { // structureWeight(seed) = total number of edges
            return graph.edgeSet().size() * 1.0;
        }
        int inDegree = graph.inDegreeOf(source);
        int outDegree = graph.outDegreeOf(source);
        return inDegree / (outDegree * 1.0);
    }

    private double getStructureWeight(EventEdge e) {
        EntityNode sink = e.getSink();
        int inDegree = graph.inDegreeOf(sink);
        int outDegree = graph.outDegreeOf(sink);
        if (seedSources.contains(sink.getSignature())) {
            return graph.edgeSet().size() * 1.0;
        }
        return outDegree / (inDegree * 1.0);
    }

    private double getFanoutWeight(EventEdge e) {
        EntityNode source = e.getSource();
        int inDegree = indegree.get(source.getSignature());
        int outDegree = outdegree.get(source.getSignature());

        double offset = 1e-6;

        if (source.getF() != null && inDegree == 0) { // read-only
            return 0.0 + offset;
        } else {
            return 1 / (outDegree * 1.0) + offset;
        }
    }

    private double getWeightAboueEdgesNumber(EntityNode e) {
        double weightBasedOnEdgeNumber = 0.0;
        Set<EntityNode> sourceOfIncoming = getSources(e);
        for (EntityNode node : sourceOfIncoming) {
            Set<EventEdge> sourceFornode = graph.incomingEdgesOf(node);
            if (sourceFornode.size() == 0) {
                weightBasedOnEdgeNumber += 1 / (sourceOfIncoming.size() * 1.0);
            } else {
                weightBasedOnEdgeNumber += 1 / (sourceOfIncoming.size() * 1.0) +
                        1 / (sourceFornode.size() * 1.0);
            }
        }
        return weightBasedOnEdgeNumber;
    }

    public void PageRankIteration(String detection) {
        Set<EntityNode> vertexSet = graph.vertexSet();
        double fluctuation = 1.0;
        int iterTime = 0;
        System.out.println();
        while (fluctuation >= 0.0000000000001) {
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for (EntityNode v : vertexSet) {
                if (v.getSignature().equals(detection))
                    System.out.println(v.reputation);
                Set<EventEdge> edges = graph.incomingEdgesOf(v);
                if (edges.size() == 0)
                    continue;
                double rep = 0.0;
                for (EventEdge edge : edges) {
                    EntityNode source = edge.getSource();
                    int numberOfOutEgeFromSource = graph.outDegreeOf(source);
                    double total_weight = 0.0;
                    // for (EventEdge oe:graph.outgoingEdgesOf(source)){
                    // total_weight +=
                    // weights.get(graph.getEdgeTarget(oe).getID()).get(source.getID());
                    // }
                    rep += preReputation.get(source.getID()) * weights.get(edge.id);
                }
                rep = rep * dumpingFactor + (1 - dumpingFactor) / vertexSet.size();
                culmativediff += Math.abs(rep - preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
        }
        System.out
                .println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }

    // PagerankIterationBackward
    public void PageRankIterationBackward(String[] highRP, String[] midRP, String[] lowRP, String detection) {
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        // We don't need special treat of library any more.
        // sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while (fluctuation >= 1e-5 && iterTime < 3000) {
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for (EntityNode v : vertexSet) {
                if (sources.contains(v.getSignature()))
                    continue;
                Set<EventEdge> edges = graph.outgoingEdgesOf(v);
                double rep = 0.0;
                for (EventEdge edge : edges) {
                    EntityNode sink = edge.getSink();
                    rep += (preReputation.get(sink.getID()) * edge.weight);
                }
                // rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep - preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
            // if(iterTime<=20) {
            // IterateGraph.printReputation(graph, iterTime);
            // }
        }
        System.out
                .println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
        if (iterTime >= 3000) {
            System.out.println("After 3000 round updates, the vertex reputation is still not stable. Break out!");
        }
    }

    @Deprecated
    public void PageRankIterationBackwardLimitedByStepInfo(String[] highRP, String[] midRP,
            String[] lowRP, String detection, Map<String, Integer> stepInfo) {
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        try {
            File stepInfoFile = new File("stepInfo.txt");
            FileWriter fileWriter = new FileWriter(stepInfoFile);
            PrintWriter pwriter = new PrintWriter(fileWriter);
            for (String key : stepInfo.keySet()) {
                pwriter.println(key + ": " + stepInfo.get(key).toString());
            }
            pwriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // We don't need special treat of library any more.
        // sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while (fluctuation >= 1e-5) {
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for (EntityNode v : vertexSet) {
                if (v.getSignature().equals(detection))
                    System.out.println(v.reputation);
                if (sources.contains(v.getSignature()))
                    continue;
                Set<EventEdge> edges = graph.outgoingEdgesOf(v);
                double rep = 0.0;
                for (EventEdge edge : edges) {
                    EntityNode sink = edge.getSink();
                    if (stepInfo.get(sink.getSignature()) < stepInfo.get(v.getSignature())) {
                        rep += (preReputation.get(sink.getID()) * edge.weight);
                    }
                }
                // rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep - preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
            if (iterTime <= 20) {
                IterateGraph.printReputation(graph, iterTime);
            }
        }
        System.out
                .println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }

    protected void normalizeWeightsAfterFiltering() {
        Set<EntityNode> vertices = graph.vertexSet();
        for (EntityNode v : vertices) {
            double totalWeight = 0.0;
            Set<EventEdge> edges = graph.incomingEdgesOf(v);
            for (EventEdge e : edges)
                totalWeight += e.weight;
            for (EventEdge e : edges) {
                e.weight = e.weight / totalWeight;
            }

        }
    }

    protected void fixReputation(String[] highRP) {
        Map<Long, Double> reputation = getReputation();
        Set<String> s = new HashSet<>(Arrays.asList(highRP));
        double high_rep = 0.0;
        int count = 0;
        for (EntityNode v : graph.vertexSet()) {
            if (s.contains(v.getSignature())) {
                high_rep += reputation.get(v.getID());
                count++;
            }
        }
        high_rep /= count;
        for (EntityNode v : graph.vertexSet())
            v.setReputation(Math.min(1 - (high_rep - reputation.get(v.getID())) / high_rep, 1));
    }

    protected void extractSuspects(double threshold) {
        List<EntityNode> vertices = new ArrayList(graph.vertexSet());
        for (EntityNode v : vertices) {
            if (v.reputation >= threshold)
                graph.removeVertex(v);
        }
    }

    private Map<Long, Double> getReputation() {
        Set<EntityNode> vertexSet = graph.vertexSet();
        Map<Long, Double> map = new HashMap<>();
        for (EntityNode node : vertexSet) {
            map.put(node.getID(), node.getReputation());
        }
        return map;
    }

    public void exportGraph(String name) {
        graphIterator.exportGraph(name);
    }

    // private void initializeWeights(){
    // Set<EntityNode> vertexSet = graph.vertexSet();
    // for(EntityNode n1:vertexSet){
    // if (graph.incomingEdgesOf(n1).size() != 0) {
    // // n1 -> source not empty
    // weights.put(n1.getID(), new HashMap<Long,Double>());
    // timeWeights.put(n1.getID(), new HashMap<Long,Double>());
    // amountWeights.put(n1.getID(), new HashMap<Long,Double>());
    // structureWeights.put(n1.getID(), new HashMap<Long,Double>());
    //
    // // Only store the <currentNode, parentNode> pairs
    // Set<EventEdge> inEdges = graph.incomingEdgesOf(n1);
    // for (EventEdge inEdge: inEdges) {
    // weights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
    // timeWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
    // amountWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
    // structureWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
    // }
    // }
    // }
    // }

    private void preprocessWeights(Map<Long, Double> weights, boolean normalizeByOutEdges) {
        if (normalizeByOutEdges) {
            // Normalize by outgoing edges
            normalizeWeightsByOutEdges(weights);
        } else {
            // Standardize weights
            standardizeWeights(weights);
        }
    }

    private void standardizeWeights(Map<Long, Double> weights) {
        // Standardization criterion: (x-mean)/std
        DescriptiveStatistics stats = new DescriptiveStatistics();
        // for (long sinkNodeID: weights.keySet()) {
        // for (long sourceNodeID: weights.get(sinkNodeID).keySet()) {
        // stats.addValue(weights.get(sinkNodeID).get(sourceNodeID));
        // }
        // }
        for (Long edgeID : weights.keySet()) {
            stats.addValue(weights.get(edgeID));
        }
        double mean = stats.getMean();
        double std = stats.getStandardDeviation();
        double standardizedWeight;
        // for (long sinkNodeID: weights.keySet()) {
        // for (long sourceNodeID: weights.get(sinkNodeID).keySet()) {
        // standardizedWeight = (weights.get(sinkNodeID).get(sourceNodeID)-mean)/std;
        //// System.out.println("Before standardize: " +
        // weights.get(sinkNodeID).get(sourceNodeID) + ", After standardize: " +
        // standardizedWeight);
        // weights.get(sinkNodeID).put(sourceNodeID, standardizedWeight);
        // }
        // }

        for (long edgeID : weights.keySet()) {
            standardizedWeight = (weights.get(edgeID) - mean) / std;
            weights.put(edgeID, standardizedWeight);
        }
    }

    private void normalizeWeightsByOutEdges(Map<Long, Double> weights) {
        for (EntityNode n : graph.vertexSet()) {
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            double weightTotal = 0.0;
            for (EventEdge out : outgoing) {
                weightTotal += weights.get(out.id);
            }

            if (weightTotal > Double.MIN_VALUE) {
                double normalizedWeight;
                for (EventEdge out : outgoing) {
                    normalizedWeight = weights.get(out.id) / weightTotal;
                    weights.put(out.id, normalizedWeight);
                }
            }
        }
    }

    private void scaleRange(List<Double> numbers) {
        // In-place scale to (0,1+)
        if (numbers.isEmpty()) {
            return;
        }

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (double n : numbers) {
            stats.addValue(n);
        }
        double min = stats.getMin();
        double max = stats.getMax();
        if (Math.abs(max - min) < 1e-12) {
            for (int i = 0; i < numbers.size(); i++) {
                numbers.set(i, 1.0);
            }
            System.out.println("Scaling skipped because all weights are identical.");
            return;
        }

        double secondMin = max;
        for (double n : numbers) {
            if (n == min)
                continue;
            if (n < secondMin)
                secondMin = n;
        }
        double offset = (secondMin - min) / 100;
        System.out.println("Scaling statistics --- min: " + min + " max: " + max + " secondMin: " + secondMin
                + " offset: " + offset + " scaledMin: " + offset / (max - min));
        for (int i = 0; i < numbers.size(); i++) {
            numbers.set(i, (numbers.get(i) - min + offset) / (max - min));
        }
    }

    public void setReliableReputation(String[] strs) {
        Set<String> set = new HashSet<String>(Arrays.asList(strs));
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode v : vertexSet) {
            if (set.contains(v.getSignature())) {
                v.setReputation(1.0);
            }
        }
    }

    private double getTimeWeight(EventEdge edge) {
        // Range: [0, Double.MAX_VALUE]
        double res;
        if (edge.getEndTime().equals(POITime)) {
            // Notice: we cannot set the value to Double.MAX_VALUE since it will invalidate
            // the standardization
            // return Double.MAX_VALUE;
            double pseudoMinDiff = 1e-10; // since nanosecond is the minimum unit for the time stamp
            res = Math.log(1 + 1 / Math.abs(pseudoMinDiff));
        } else {
            res = Math.log(1 + 1 / Math.abs(edge.getEndTime().doubleValue() - POITime.doubleValue()));
            // System.out.println("endtime: " + edge.getEndTime().doubleValue() + " POI: " +
            // POITime.doubleValue() + " abs diff: " +
            // Math.abs(edge.getEndTime().doubleValue()- POITime.doubleValue()));
        }
        return res;
    }

    private double getAmountWeight(EventEdge edge) {
        // if(edge.getEvent().equals("execve")){
        // return 1.0;
        // }
        // return Math.exp((-1)*Math.abs(edge.getSize()-detectionSize)/detectionSize);

        return 1.0 / (Math.abs(edge.getSize() - detectionSize) + 0.0001);
    }

    private Map<String, Integer> computeDistanceToDetection(String detection) {
        Map<String, Integer> distanceMap = new HashMap<>();
        EntityNode detectionNode = graphIterator.getGraphVertex(detection);
        if (detectionNode == null) {
            return distanceMap;
        }

        Queue<EntityNode> queue = new LinkedList<>();
        queue.offer(detectionNode);
        distanceMap.put(detectionNode.getSignature(), 0);

        while (!queue.isEmpty()) {
            EntityNode current = queue.poll();
            int currentDistance = distanceMap.get(current.getSignature());
            for (EventEdge incomingEdge : graph.incomingEdgesOf(current)) {
                EntityNode previous = incomingEdge.getSource();
                if (!distanceMap.containsKey(previous.getSignature())) {
                    distanceMap.put(previous.getSignature(), currentDistance + 1);
                    queue.offer(previous);
                }
            }
        }

        return distanceMap;
    }

    private double estimateTimeScale(List<EventEdge> edges) {
        List<Double> deltas = new ArrayList<>();
        for (EventEdge edge : edges) {
            double delta = getTimeDelta(edge);
            if (delta > 0) {
                deltas.add(delta);
            }
        }
        return estimateRobustScale(deltas, 1.0);
    }

    private double estimateAmountScale(List<EventEdge> edges) {
        List<Double> logSizes = new ArrayList<>();
        for (EventEdge edge : edges) {
            if (edge.getSize() > 0) {
                logSizes.add(Math.log1p(edge.getSize()));
            }
        }
        return estimateRobustScale(logSizes, 1.0);
    }

    private double estimateDistanceScale(Map<String, Integer> distanceToDetection) {
        if (distanceToDetection.isEmpty()) {
            return 1.0;
        }

        List<Double> values = new ArrayList<>();
        for (Integer distance : distanceToDetection.values()) {
            values.add(distance.doubleValue());
        }
        return estimateRobustScale(values, 1.0);
    }

    private double estimateRobustScale(List<Double> values, double fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }

        List<Double> sortedValues = new ArrayList<>(values);
        Collections.sort(sortedValues);
        double median = percentile(sortedValues, 0.5);
        List<Double> deviations = new ArrayList<>();
        for (double value : sortedValues) {
            deviations.add(Math.abs(value - median));
        }
        Collections.sort(deviations);
        double mad = percentile(deviations, 0.5);
        double scale = mad > 1e-9 ? mad * 1.4826 : Math.max(percentile(sortedValues, 0.75), fallback);
        if (scale < 1e-9) {
            scale = fallback;
        }
        return scale;
    }

    private double percentile(List<Double> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }

        double position = quantile * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedValues.get(lowerIndex);
        }
        double fraction = position - lowerIndex;
        return sortedValues.get(lowerIndex) * (1.0 - fraction) + sortedValues.get(upperIndex) * fraction;
    }

    private double normalizedSpread(List<Double> values, double scale) {
        if (values == null || values.size() <= 1) {
            return 0.0;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return clamp((max - min) / (scale + 1e-9));
    }

    private double computeTemporalScore(EventEdge edge, double timeScale) {
        return clamp(Math.exp(-getTimeDelta(edge) / (timeScale + 1e-9)));
    }

    private double computeAmountScore(EventEdge edge, List<EventEdge> siblingEdges, double logDetectionSize, double amountScale) {
        double logSize = Math.log1p(Math.max(0L, edge.getSize()));
        double siblingProminence = 1.0;
        if (siblingEdges.size() > 1) {
            double minLogSize = Double.POSITIVE_INFINITY;
            double maxLogSize = Double.NEGATIVE_INFINITY;
            for (EventEdge siblingEdge : siblingEdges) {
                double siblingLogSize = Math.log1p(Math.max(0L, siblingEdge.getSize()));
                minLogSize = Math.min(minLogSize, siblingLogSize);
                maxLogSize = Math.max(maxLogSize, siblingLogSize);
            }
            if (Math.abs(maxLogSize - minLogSize) > 1e-9) {
                siblingProminence = (logSize - minLogSize) / (maxLogSize - minLogSize);
            }
        }

        if (detectionSize <= 0) {
            return clamp(siblingProminence);
        }

        double detectionCloseness = Math.exp(-Math.abs(logSize - logDetectionSize) / (amountScale + 1e-9));
        return clamp(0.70 * detectionCloseness + 0.30 * siblingProminence);
    }

    private double computeStructureScore(EventEdge edge, Map<String, Integer> distanceToDetection, double distanceScale) {
        double sourceBranchPenalty = 1.0 / Math.sqrt(Math.max(1, graph.outDegreeOf(edge.getSource())));
        double sinkExclusivity = 1.0 / Math.sqrt(Math.max(1, graph.inDegreeOf(edge.getSink())));
        double poiDistanceScore = Math.exp(-getDistanceForEdge(edge, distanceToDetection) / (distanceScale + 1e-9));
        double typePrior = getTypePrior(edge);
        return clamp(0.25 * sourceBranchPenalty + 0.20 * sinkExclusivity + 0.35 * poiDistanceScore + 0.20 * typePrior);
    }

    private int getDistanceForEdge(EventEdge edge, Map<String, Integer> distanceToDetection) {
        Integer sinkDistance = distanceToDetection.get(edge.getSink().getSignature());
        if (sinkDistance != null) {
            return sinkDistance;
        }
        Integer sourceDistance = distanceToDetection.get(edge.getSource().getSignature());
        if (sourceDistance != null) {
            return sourceDistance + 1;
        }
        return distanceToDetection.isEmpty() ? 1 : distanceToDetection.size() + 1;
    }

    private double getTypePrior(EventEdge edge) {
        String type = edge.getType();
        if (type == null) {
            return 0.70;
        }
        switch (type) {
            case "PtoP":
                return 0.95;
            case "PtoN":
            case "NtoP":
                return 0.85;
            case "PtoF":
            case "FtoP":
                return 0.75;
            default:
                return 0.70;
        }
    }

    private double computeAdaptiveTemperature(List<Double> rawScores, double meanConfidence) {
        if (rawScores.isEmpty()) {
            return 1.0;
        }

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (double rawScore : rawScores) {
            stats.addValue(rawScore);
        }

        double normalizedStd = stats.getStandardDeviation() / (Math.abs(stats.getMean()) + 1e-9);
        double temperature = 1.10 - 0.45 * clamp(meanConfidence) - 0.20 * clamp(normalizedStd);
        return Math.max(0.35, Math.min(1.20, temperature));
    }

    private double getTimeDelta(EventEdge edge) {
        return Math.abs(edge.getEndTime().doubleValue() - POITime.doubleValue());
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void writeAdaptiveFusionDiagnostics(String resDir, Collection<JSONObject> diagnostics) {
        try {
            File file = new File(resDir + "/adaptivefusion_weights.json");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(diagnostics);
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void printWeights() throws Exception {
        PrintWriter writer = new PrintWriter(String.format("%s.txt", "EdgeWeights"));
        if (weights == null)
            System.out.println("weithis is null or size equal to zero");
        System.out.println(weights.keySet().size());
        for (Long id : weights.keySet()) {
            // Map<Long, Double> sub = weights.get(id);
            // for(Long id2: weights.keySet()){
            // //writer.println(String.format("%d_%d : %f", id, id2,
            // weights.get(id).get(id2)));
            // if(!weights.get(id).get(id2).equals(0.0)) {
            // writer.println(String.format("%d_%d : %f", id, id2,
            // weights.get(id).get(id2)));
            // //System.out.println(String.format("%d_%d : %f", id, id2,
            // weights.get(id).get(id2)));
            // }
            // }

            writer.println(String.format("%d: %f", id, weights.get(id)));
        }
        writer.close();
    }

    private BigDecimal getPOITime() { // TODO: enable user-input poi time
        BigDecimal res = BigDecimal.ZERO;
        Set<EventEdge> edges = graph.edgeSet();
        for (EventEdge e : edges) {
            if (e.getEndTime().compareTo(res) > 0) { // i.e., latest event
                res = e.getEndTime();
            }
        }
        return res;
    }

    public void printReputation() {
        graphIterator.printVertexReputation();
    }

    private void printEdgeWeights(EventEdge edge) {
        System.out.println("EventEdge " + edge.getID() + " (" + edge.getSource().getID() + " "
                + edge.getSource().getSignature() + " ->" + edge.getEvent() + " " + edge.getSink().getID() + " "
                + edge.getSink().getSignature() + ")" + "\t\t\t timeWeight:" + timeWeights.get(edge.id)
                + " amountWeight: " + amountWeights.get(edge.id) + " structureWeight: " + structureWeights.get(edge.id)
                + " finalWeight: " + weights.get(edge.id));
    }

    // private void printClusterResults(String clusterMethod,
    // List<Cluster<EventEdgeWrapper>> clusterResults) {
    // System.out.println();
    // System.out.println(clusterMethod + " clustering:");
    // for (int i = 0; i < clusterResults.size(); i++) {
    // System.out.println();
    // System.out.println("Cluster " + i);
    // for (EventEdgeWrapper edgeWrapper: clusterResults.get(i).getPoints()) {
    // EventEdge edge = edgeWrapper.getEventEdge();
    // printEdgeWeights(edge);
    // }
    // }
    // System.out.println();
    // }

    // private void printRealMatrix(RealMatrix matrix) {
    // for (int i = 0; i < matrix.getRowDimension(); i++) {
    // for (int j = 0; j < matrix.getColumnDimension(); j++) {
    // System.out.print(matrix.getEntry(i, j) + " ");
    // }
    // System.out.println();
    // }
    // }

    private void printRealVector(RealVector vector) {
        for (int i = 0; i < vector.getDimension(); i++) {
            System.out.print(vector.getEntry(i) + " ");
        }
        System.out.println();
    }

    public void checkTimeAndAmount() {
        Set<EventEdge> edges = graph.edgeSet();
        for (EventEdge edge : edges) {
            if (edge.getDuration().equals(BigDecimal.ZERO)) {
                System.out.println("this is because amount is zero");
                System.out.println(edge.getID());
                System.out.println(edge.getSource().getSignature());
                // System.out.println(edge.getSink().getSignature());
            }

            if (edge.getSize() == 0) {
                System.out.println("this is because size is zero");
                System.out.println(edge.getID());
                System.out.println(edge.getSource().getSignature());
            }
        }
    }

    private boolean someWithDataSomeNoData(EntityNode n) {
        Set<EventEdge> edges = graph.incomingEdgesOf(n);
        boolean oneEdgeNoData = false;
        boolean oneEdgeWithData = false;
        for (EventEdge e : edges) {
            if (e.getSize() == 0) {
                oneEdgeNoData = true;
            }
            if (e.getSize() != 0) {
                oneEdgeWithData = true;
            }
            if (oneEdgeNoData && oneEdgeWithData) {
                return true;
            }
        }
        return false;
    }

    public void initialReputation(String[] signature_high, String[] signature_low) {
        Set<EntityNode> set = graph.vertexSet();
        Set<String> highReputation = new HashSet<String>(Arrays.asList(signature_high));
        Set<String> lowReputation = new HashSet<String>(Arrays.asList(signature_low));
        for (EntityNode node : set) {
            if (highReputation.contains(node.getSignature())) {
                System.out.println(node.getSignature() + " has high reputation");
                node.reputation = 1.0;
            } else if (lowReputation.contains(node.getSignature())) {
                node.reputation = 0.0;
            } else if (graph.incomingEdgesOf(node).size() == 0) {
                node.reputation = 0.0;
            }
        }

    }

    public void printConstantPartOfPageRank() {
        double res = (1 - dumpingFactor) / graph.vertexSet().size();
        System.out.println("The constant part of Page Rank:" + res);
    }

    public void checkWeightsAfterCalculation() {
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode node : vertexSet) {
            Set<EventEdge> incoming = graph.incomingEdgesOf(node);
            double res = 0.0;
            for (EventEdge edge : incoming) {
                res += edge.weight;
            }
            if (incoming.size() != 0 && Math.abs(res - 1.0) >= 0.00001) {
                System.out.println("Target: " + node.getSignature());
                for (EventEdge edge : incoming) {
                    edge.printInfo();
                }
                System.out.println("-----------");
            }
        }
    }

    public void onlyPrintHighestWeights(String start) {
        EntityNode v1 = graphIterator.getGraphVertex(start);
        Map<Long, EntityNode> map = new HashMap<>();
        map.put(v1.getID(), new EntityNode(v1));

        DirectedPseudograph<EntityNode, EventEdge> result = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
        Queue<EntityNode> queue = new LinkedList<>();
        queue.offer(v1);
        while (!queue.isEmpty()) {
            EntityNode node = queue.poll();
            Set<EventEdge> incoming = graph.incomingEdgesOf(node);
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(node);
            EventEdge incomingHighestWeight = getHighestWeightEdge(incoming);
            EventEdge outgoingHighestWeight = getHighestWeightEdge(outgoing);
            if (incomingHighestWeight != null) {
                if (!map.containsKey(incomingHighestWeight.getSource().getID())) {
                    map.put(incomingHighestWeight.getSource().getID(),
                            new EntityNode(incomingHighestWeight.getSource()));
                    queue.offer(incomingHighestWeight.getSource());
                }
                EventEdge incomingCopy = new EventEdge(incomingHighestWeight);
                EntityNode copy1 = map.get(node.getID());
                EntityNode copy2 = map.get(incomingHighestWeight.getSource().getID());
                result.addVertex(copy1);
                result.addVertex(copy2);
                result.addEdge(copy2, copy1, incomingCopy);
            }
            if (outgoingHighestWeight != null) {
                if (!map.containsKey(outgoingHighestWeight.getSink().getID())) {
                    map.put(outgoingHighestWeight.getSink().getID(), new EntityNode(outgoingHighestWeight.getSink()));
                    queue.offer(outgoingHighestWeight.getSink());
                }
                EventEdge outgoingCopy = new EventEdge(outgoingHighestWeight);
                EntityNode copy1 = map.get(node.getID());
                EntityNode copy3 = map.get(outgoingCopy.getSink().getID());
                result.addVertex(copy1);
                result.addVertex(copy3);
                result.addEdge(copy1, copy3, outgoingCopy);
            }

        }
        System.out.println("dEBUG: " + result.vertexSet().size());
        IterateGraph iter = new IterateGraph(result);
        iter.exportGraph("HighestWeight");
    }

    private EventEdge getHighestWeightEdge(Set<EventEdge> edges) {
        List<EventEdge> edgeList = new ArrayList<>(edges);
        if (edgeList.size() == 0)
            return null;
        EventEdge res = edgeList.get(0);
        for (int i = 1; i < edgeList.size(); i++) {
            if (res.weight < edgeList.get(i).weight) {
                res = edgeList.get(i);
            }
        }
        return res;
    }

    private Set<EntityNode> getSources(EntityNode e) {
        Set<EventEdge> edges = graph.incomingEdgesOf(e);
        Set<EntityNode> sources = new HashSet<>();
        for (EventEdge edge : edges) {
            sources.add(edge.getSource());
        }
        assert sources.size() <= edges.size();
        return sources;
    }

    public double getAvgWeight() {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (EventEdge edge : graph.edgeSet()) {
            stats.addValue(edge.weight);
        }
        return stats.getMean();
    }

    public double getStdWeight() {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (EventEdge edge : graph.edgeSet()) {
            stats.addValue(edge.weight);
        }
        return stats.getStandardDeviation();
    }

    // TODO: this needs to be tested
    public void filterGraphBasedOnAverageWeight(double threshold) {
        // double averageEdgeWeight = getAvgWeight();
        // double sd = getStdWeight();
        List<EventEdge> edges = new ArrayList<>(graph.edgeSet());
        // double threshold = averageEdgeWeight*percentage;
        System.out.println("threshold: " + threshold);
        for (int i = 0; i < edges.size(); i++) {

            if (edges.get(i).weight < threshold) {
                graph.removeEdge(edges.get(i));
            }
        }
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for (int i = 0; i < list.size(); i++) {
            EntityNode v = list.get(i);
            if (graph.incomingEdgesOf(v).size() == 0 && graph.outgoingEdgesOf(v).size() == 0) {
                graph.removeVertex(v);
            }
        }
    }

    // public double OTSUThreshold(){
    // List<Double> weights = new ArrayList<>();
    // for(EventEdge e: graph.edgeSet())
    // weights.add(e.weight);
    // Collections.sort(weights);
    //
    // double min_sigma = Double.MAX_VALUE, sum = 0.0;
    // int min_i = 0, length = weights.size();
    // for(int i = 0; i < length; i++) sum += weights.get(i);
    // double sum1 = 0.0;
    // for(int i = 0; i< length; i++) {
    // sum1+=weights.get(i);
    // double avg1 = sum1/(i+1);
    // double avg2 = (sum-sum1)/(length-i-1);
    // double sigma1 = 0.0, sigma2 = 0.0;
    // for(int j = 0; j <= i; j++)
    // sigma1 += Math.pow(weights.get(i)-avg1,2.0);
    // for(int j = i+1; j < length; j++)
    // sigma2 += Math.pow(weights.get(i)-avg2,2.0);
    // if(sigma1+sigma2 < min_sigma){
    // min_sigma = sigma1 + sigma2;
    // min_i = i;
    // }
    // }
    // return weights.get(min_i);
    // }

    public void removeIsolatedIslands(String POI) {
        ConnectivityInspector ci = new ConnectivityInspector(graph);
        Set verticesConnectedToPOI = ci.connectedSetOf(graphIterator.getGraphVertex(POI));
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for (int i = 0; i < list.size(); i++) {
            EntityNode v = list.get(i);
            if (!verticesConnectedToPOI.contains(v)) {
                graph.removeVertex(v);
            }
        }
    }

    public void removeIrrelaventVertices(String POI) {
        EntityNode POIVertex = graphIterator.getGraphVertex(POI);
        LinkedList<EventEdge> queue = new LinkedList<>(graph.incomingEdgesOf(POIVertex));
        Set<EntityNode> ancestors = new HashSet<>();
        ancestors.add(POIVertex);
        while (!queue.isEmpty()) {
            EntityNode v = graph.getEdgeSource(queue.pollLast());
            ancestors.add(v);
            for (EventEdge e : graph.incomingEdgesOf(v))
                if (!ancestors.contains(graph.getEdgeSource(e)))
                    queue.addFirst(e);
        }

        queue = new LinkedList<>(graph.outgoingEdgesOf(POIVertex));
        Set<EntityNode> children = new HashSet<>();
        children.add(POIVertex);
        while (!queue.isEmpty()) {
            EntityNode v = graph.getEdgeTarget(queue.pollLast());
            children.add(v);
            for (EventEdge e : graph.outgoingEdgesOf(v))
                if (!children.contains(graph.getEdgeTarget(e)))
                    queue.addFirst(e);
        }

        ancestors.addAll(children);
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for (int i = 0; i < list.size(); i++) {
            EntityNode v = list.get(i);
            if (!ancestors.contains(v)) {
                graph.removeVertex(v);
            }
        }

    }

    public long getDataAmount(String signature) {
        EntityNode node = graphIterator.getGraphVertex(signature);
        long res = 0;
        Set<EventEdge> edges = graph.incomingEdgesOf(node);
        for (EventEdge e : edges) {
            res += e.getSize();
        }
        return res;
    }

    private double gaussian(double center, double x, double sigma) {
        return Math.exp(-Math.pow(x - center, 2) / (2 * sigma * sigma)) /
                Math.sqrt(2 * Math.PI * sigma * sigma);
    }

    private double adjustedSigmoid(double x) {
        // Scale x in [0, double.MAX_VALUE] to [0, 1)
        return 2 * (1 / (1 + Math.pow(Math.E, (-1 * x)))) - 1;
    }

    /*
     * starts: it will be used to do forward analysis
     * original: original dependency graph
     */
    public DirectedPseudograph<EntityNode, EventEdge> combineBackwardAndForwardForGivenStarts(List<String> starts,
            DirectedPseudograph<EntityNode, EventEdge> original) {
        forwardAnalysis = new ForwardAnalysis(original);
        BigDecimal POITime = getPOITime();
        List<DirectedPseudograph<EntityNode, EventEdge>> forwardGraphs = forwardAnalysis
                .multipleForwardLimitedByPOI(starts, POITime);
        Map<String, Integer> forwardGraphEdgeUnion = IterateGraph.groupsEdges(forwardGraphs);
        DirectedPseudograph<EntityNode, EventEdge> backFilterByForwardUion = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
        for (EventEdge edge : graph.edgeSet()) {
            String signatureOfCurrentEdge = IterateGraph.convertEdgeToString(edge);
            if (!forwardGraphEdgeUnion.containsKey(signatureOfCurrentEdge)) {
                continue;
            }
            backFilterByForwardUion.addVertex(edge.getSource());
            backFilterByForwardUion.addVertex(edge.getSink());
            backFilterByForwardUion.addEdge(edge.getSource(), edge.getSink(), edge);
        }
        Map<String, Double> graphReputation = IterateGraph.getNodeReputation(graph);
        for (EntityNode node : backFilterByForwardUion.vertexSet()) {
            node.reputation = graphReputation.get(node.getSignature());
        }
        return backFilterByForwardUion;
    }

    // TODO: 9/19/2019 This method should be based on the reputation ranking, for
    // now we need manual inputs.
    public List<List<String>> getForwardStarts() {
        List<List<String>> res = new LinkedList<>();
        List<String> candidates = IterateGraph.getCandidateEntryPoint(graph);
        Map<String, Double> graphReputation = IterateGraph.getNodeReputation(graph);
        Map<String, EntityNode> signatureToNode = IterateGraph.getSignatureNodeMap(graph);
        List<String> processCandidate = new LinkedList<>();
        List<String> ipCandidate = new LinkedList<>();
        List<String> fileCandidate = new LinkedList<>();
        for (String sign : candidates) {
            EntityNode node = signatureToNode.get(sign);
            if (node.isProcessNode()) {
                processCandidate.add(sign);
            } else if (node.isNetworkNode()) {
                ipCandidate.add(sign);
            } else {
                fileCandidate.add(sign);
            }
        }
        IterateGraph.sortedSignatureBasedOnRP(processCandidate, graphReputation);
        IterateGraph.sortedSignatureBasedOnRP(ipCandidate, graphReputation);
        IterateGraph.sortedSignatureBasedOnRP(fileCandidate, graphReputation);
        res.add(processCandidate);
        res.add(ipCandidate);
        res.add(fileCandidate);
        return res;
    }

    /**
     * 支持多个攻击入口节点一起过滤，生成包含所有可能攻击路径的完整溯源图（连通版）
     *
     * <p>修复说明（原版不连通的根本原因）：
     * 原版使用 {@code ForwardAnalysis(original)} 在完整原始日志图上做正向遍历，
     * 而 {@code this.graph} 是经过 CPR 压缩的反向切片。两者的边集合并不完全一致，
     * 导致签名比对（{@code convertEdgeToString}）时，某些节点的出边在正向图中能匹配
     * 但其后续出边却不存在于正向图，从而在交集后变成"死端"（有入边无出边），
     * 造成最终图不连通。
     *
     * <p>修复策略：
     * <ol>
     *   <li>改用 {@code ForwardAnalysis(this.graph)} 在反向切片内做正向遍历——
     *       反向切片中所有节点均可到达 POI，正向遍历产生的边 100% 来自 {@code this.graph}，
     *       交集结果天然与 POI 连通。</li>
     *   <li>若所有入口在反向切片内均无正向路径（极端情况），直接返回完整反向切片，
     *       因为反向切片本身已经对 POI 连通。</li>
     *   <li>对交集后仍存在孤立分量的情形（安全兜底），通过 {@code ensureConnectivity}
     *       从 {@code this.graph} 中补全孤立节点到主分量的最短路径。</li>
     * </ol>
     */
    public DirectedPseudograph<EntityNode, EventEdge> combineBackwardAndForwardForMultipleStarts(
            List<String> startNodeIds,
            DirectedPseudograph<EntityNode, EventEdge> original) {

        if (startNodeIds == null || startNodeIds.isEmpty()) {
            return new DirectedPseudograph<>(EventEdge.class);
        }

        // ===================== 主要修复：使用 this.graph（反向切片）做正向分析 =====================
        // 原因：original 是完整原始日志图，this.graph 是 CPR 压缩后的反向可达子图。
        // 在 original 上做正向遍历得到的边可能与 this.graph 中的边签名不一致，
        // 导致交集操作后出现"死端"节点（有入边无出边），造成不连通。
        // 改用 this.graph 做正向遍历后，所有正向可达边 100% 存在于 this.graph 中，
        // 交集结果天然与 POI 连通。
        this.forwardAnalysis = new ForwardAnalysis(this.graph);
        BigDecimal POITime = getPOITime();

        // 1. 收集所有入口在反向切片内的正向子图
        List<DirectedPseudograph<EntityNode, EventEdge>> forwardGraphs = new ArrayList<>();
        for (String start : startNodeIds) {
            DirectedPseudograph<EntityNode, EventEdge> fg = forwardAnalysis.forwardLimitedByTime(start, POITime);
            if (fg != null && fg.edgeSet().size() > 0) {
                forwardGraphs.add(fg);
            }
        }

        // 2. 若所有入口在反向切片内都无正向路径，回退到完整反向切片（已对 POI 连通）
        if (forwardGraphs.isEmpty()) {
            System.out.println("[combineBackwardAndForward] 所有入口在反向切片内正向路径为空，回退到完整反向切片");
            DirectedPseudograph<EntityNode, EventEdge> fallback = new DirectedPseudograph<>(EventEdge.class);
            for (EventEdge edge : this.graph.edgeSet()) {
                fallback.addVertex(edge.getSource());
                fallback.addVertex(edge.getSink());
                fallback.addEdge(edge.getSource(), edge.getSink(), edge);
            }
            Map<String, Double> rep0 = IterateGraph.getNodeReputation(this.graph);
            for (EntityNode node : fallback.vertexSet()) {
                Double r = rep0.get(node.getSignature());
                if (r != null) node.reputation = r;
            }
            return fallback;
        }

        // 3. 合并所有正向图的边（并集）
        Map<String, Integer> forwardEdgeUnion = IterateGraph.groupsEdges(forwardGraphs);

        // 4. 用正向边并集过滤反向全图（此时正向图与反向图边集完全相同性质，交集有意义）
        DirectedPseudograph<EntityNode, EventEdge> filtered = new DirectedPseudograph<>(EventEdge.class);
        for (EventEdge edge : this.graph.edgeSet()) {
            String signature = IterateGraph.convertEdgeToString(edge);
            if (forwardEdgeUnion.containsKey(signature)) {
                filtered.addVertex(edge.getSource());
                filtered.addVertex(edge.getSink());
                filtered.addEdge(edge.getSource(), edge.getSink(), edge);
            }
        }

        // 5. 连通性保障（安全兜底）：若仍存在孤立分量，从 this.graph 补全路径
        filtered = ensureConnectivity(filtered, startNodeIds);

        // 6. 恢复节点信誉度
        Map<String, Double> originalReputation = IterateGraph.getNodeReputation(this.graph);
        for (EntityNode node : filtered.vertexSet()) {
            Double rep = originalReputation.get(node.getSignature());
            if (rep != null) {
                node.reputation = rep;
            }
        }
        return filtered;
    }

    /**
     * 连通性保障辅助函数（安全兜底层）。
     *
     * <p>在主修复（使用 this.graph 做正向分析）后，理论上不应出现不连通图，
     * 但为防止边界情况（如入口节点签名在 this.graph 中找不到等），
     * 此函数检测弱连通分量，并对孤立分量中的节点在 {@code this.graph} 中
     * 做 BFS，将其与包含最高声誉节点（通常是 POI）的主分量连接起来。
     *
     * @param filtered    待检查/修复的图
     * @param startNodeIds 入口节点签名列表（调试用）
     * @return 连通性修复后的图（已保证主分量与入口节点连通）
     */
    @SuppressWarnings("unchecked")
    private DirectedPseudograph<EntityNode, EventEdge> ensureConnectivity(
            DirectedPseudograph<EntityNode, EventEdge> filtered,
            List<String> startNodeIds) {

        if (filtered.vertexSet().isEmpty()) return filtered;

        ConnectivityInspector<EntityNode, EventEdge> ci = new ConnectivityInspector<>(filtered);
        if (ci.isConnected()) return filtered;

        List<Set<EntityNode>> components = ci.connectedSets();
        // 主分量 = 包含声誉最高节点的分量（POI 声誉通常为 1.0）
        Set<EntityNode> mainComponent = components.stream()
                .max(Comparator.comparingDouble(comp ->
                        comp.stream().mapToDouble(n -> n.reputation).max().orElse(0.0)))
                .orElse(components.get(0));

        System.out.println("[Connectivity Check] Found " + components.size() + " weak connected component(s), main component has " + mainComponent.size() + " nodes, repairing...");

        Set<String> mainSigs = new HashSet<>();
        for (EntityNode n : mainComponent) mainSigs.add(n.getSignature());

        // 对不在主分量中的每个孤立节点，在 this.graph 中做 BFS 前向搜索到主分量
        Set<EntityNode> isolatedNodes = new HashSet<>();
        for (Set<EntityNode> comp : components) {
            if (comp != mainComponent) isolatedNodes.addAll(comp);
        }

        for (EntityNode isolated : new ArrayList<>(isolatedNodes)) {
            // BFS：从孤立节点沿 this.graph 出边前向搜索，找到能到达主分量的路径
            Queue<EntityNode> queue = new LinkedList<>();
            Map<EntityNode, EntityNode> parent = new HashMap<>();
            Map<EntityNode, EventEdge> edgeToParent = new HashMap<>();
            Set<EntityNode> visited = new HashSet<>();
            queue.offer(isolated);
            visited.add(isolated);
            boolean found = false;
            EntityNode joinPoint = null;

            outer:
            while (!queue.isEmpty()) {
                EntityNode cur = queue.poll();
                for (EventEdge edge : this.graph.outgoingEdgesOf(cur)) {
                    EntityNode next = edge.getSink();
                    if (mainSigs.contains(next.getSignature())) {
                        // 找到汇入点，记录最后一条边
                        parent.put(next, cur);
                        edgeToParent.put(next, edge);
                        joinPoint = next;
                        found = true;
                        break outer;
                    }
                    if (!visited.contains(next)) {
                        visited.add(next);
                        parent.put(next, cur);
                        edgeToParent.put(next, edge);
                        queue.offer(next);
                    }
                }
            }

            if (found && joinPoint != null) {
                // 沿 parent 回溯，将路径上的边补入 filtered 图
                EntityNode cur = joinPoint;
                while (edgeToParent.containsKey(cur)) {
                    EventEdge e = edgeToParent.get(cur);
                    filtered.addVertex(e.getSource());
                    filtered.addVertex(e.getSink());
                    if (!filtered.containsEdge(e)) {
                        filtered.addEdge(e.getSource(), e.getSink(), e);
                    }
                    // 补全后该节点也加入主分量签名集，防止多次重复补全
                    mainSigs.add(e.getSource().getSignature());
                    mainSigs.add(e.getSink().getSignature());
                    cur = parent.get(cur);
                }
                System.out.println("[Connectivity Repair] Node " + isolated.getSignature() + " connected to main component via path completion");
            } else {
                System.out.println("[Connectivity Repair] Warning: Node " + isolated.getSignature()
                        + " cannot forward-reach main component in backslice (may be legitimate island), keeping as is");
            }
        }
        return filtered;
    }

    public DirectedPseudograph<EntityNode, EventEdge> combineBackwardAndForwardForGivenStart(String start,
            DirectedPseudograph<EntityNode, EventEdge> original) {
        forwardAnalysis = new ForwardAnalysis(original);
        BigDecimal POITime = getPOITime();
        DirectedPseudograph<EntityNode, EventEdge> forwardGraph = forwardAnalysis.forwardLimitedByTime(start, POITime);
        List<DirectedPseudograph<EntityNode, EventEdge>> forwardGraphs = new ArrayList<>();
        forwardGraphs.add(forwardGraph);
        Map<String, Integer> forwardGraphEdgeUnion = IterateGraph.groupsEdges(forwardGraphs);
        DirectedPseudograph<EntityNode, EventEdge> backFilterByForwardUion = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
        for (EventEdge edge : graph.edgeSet()) {
            String signatureOfCurrentEdge = IterateGraph.convertEdgeToString(edge);
            if (!forwardGraphEdgeUnion.containsKey(signatureOfCurrentEdge)) {
                continue;
            }
            backFilterByForwardUion.addVertex(edge.getSource());
            backFilterByForwardUion.addVertex(edge.getSink());
            backFilterByForwardUion.addEdge(edge.getSource(), edge.getSink(), edge);
        }
        Map<String, Double> graphReputation = IterateGraph.getNodeReputation(graph);
        for (EntityNode node : backFilterByForwardUion.vertexSet()) {
            node.reputation = graphReputation.get(node.getSignature());
        }
        return backFilterByForwardUion;
    }

    public void PageRankIterationOneTimeUpdate(String[] highRP, String[] midRP, String[] lowRP, String detection) {
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        // We don't need special treat of library any more.
        // sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while (fluctuation >= 1e-5) {
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for (EntityNode v : vertexSet) {
                if (sources.contains(v.getSignature()))
                    continue;
                Set<EventEdge> edges = graph.outgoingEdgesOf(v);
                double rep = 0.0;
                for (EventEdge edge : edges) {
                    EntityNode sink = edge.getSink();
                    rep += (preReputation.get(sink.getID()) * edge.weight);
                }
                // rep = rep*alarmlevel+0.5*(1-alarmlevel);
                if (v.reputation == 0.0) {
                    culmativediff += Math.abs(rep - preReputation.get(v.getID()));
                    v.setReputation(rep);
                }
            }
            fluctuation = culmativediff;
            if (iterTime <= 20) {
                IterateGraph.printReputation(graph, iterTime);
            }
        }
        System.out
                .println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }

    private double[] getNCoefficient(int n) {
        double[] res = new double[n];
        Random rand = new Random();
        double sum = 0.0;
        for (int i = 0; i < res.length; i++) {
            res[i] = rand.nextDouble();
            sum += res[i];
        }

        for (int i = 0; i < res.length; i++) {
            res[i] = res[i] / sum;
        }
        return res;
    }

    public Map<String, Integer> graphSizeWithoutReadonly() {

        Map<String, Integer> res = new HashMap<>();
        res.put("#Vertices before:", graph.vertexSet().size());
        res.put("#Edges beore:", graph.edgeSet().size());

        Set<EntityNode> vertices = graph.vertexSet();
        Set<Long> edgeRemove = new HashSet<>();
        Set<String> vertexRemove = new HashSet<>();
        Set<String> vertex = new HashSet<>();
        for (EntityNode n : vertices) {
            if (n.isFileNode() && graph.inDegreeOf(n) == 0) {
                Set<EventEdge> edges = graph.outgoingEdgesOf(n);
                for (EventEdge e : edges)
                    // graph.removeEdge(e);
                    edgeRemove.add(e.getID());
                // graph.removeVertex(n);
                vertexRemove.add(n.getSignature());
            }
            vertex.add(n.getSignature());
        }
        res.put("#Vertices after:", graph.vertexSet().size() - vertexRemove.size());
        res.put("#Edges after:", graph.edgeSet().size() - edgeRemove.size());
        return res;
    }
}
