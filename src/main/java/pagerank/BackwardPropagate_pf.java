package pagerank;

import net.bytebuddy.dynamic.scaffold.MethodGraph;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.ml.clustering.*;
import org.apache.commons.math3.ml.clustering.evaluation.ClusterEvaluator;
import org.apache.commons.math3.ml.clustering.evaluation.SumOfClusterVariances;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.alg.*;
import static org.junit.Assert.*;
import java.util.Random;


/**
 * Created by fang on 3/12/18.
 *
 * Edited by Peng Gao on 10/31/18.
 */
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@SuppressWarnings("Duplicates")
public class  BackwardPropagate_pf {
    DirectedPseudograph<EntityNode, EventEdge> graph;
    /* the input  need to finish split step before this(this parameter need to run relevant functions first)*/
    private BigDecimal POITime;
    IterateGraph graphIterator;
    Map<Long, Double> weights;
    Map<Long, Double> timeWeights; // sink -> source
    Map<Long, Double> amountWeights;
    Map<Long, Double> structureWeights;
    Map<Long, Double> anomalyWeights; // [新增] 用于存储异常相关性权重

    double dumpingFactor;
    double detectionSize; // this value used to calculate amountWeight, default value is zero.
    Set<String> seedSources;  // get signature of seedSources in order to assign structure weight
    DirectedPseudograph<EntityNode, EventEdge> originalGraph;
    ForwardAnalysis forwardAnalysis;
    BackwardPropagate_pf (DirectedPseudograph<EntityNode, EventEdge> input){
        graph = input;
        graphIterator = new IterateGraph(graph);
        weights = new HashMap<>();
        timeWeights = new HashMap<>();
        amountWeights = new HashMap<>();
        structureWeights = new HashMap<>();
        anomalyWeights = new HashMap<>(); // [新增] 初始化 Map
        POITime = getPOITime();
        dumpingFactor = 0.85;
        detectionSize = 0;
        indegree = new HashMap<>();
        outdegree = new HashMap<>();
    }
    Map<String, Integer> indegree;
    Map<String, Integer> outdegree;


    public void setDetectionSize(double value){
        System.out.println("setDetection invoked: "+value);
        detectionSize = value;
    }

    public void calculateWeights(){
        System.out.println("calculateWeights invoked");
        Set<EntityNode> vertexSet = graph.vertexSet();
        //initializeWeights();

        // Compute individual weights
        for(EntityNode n:vertexSet){
            Set<EventEdge> inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges){
                double timeWeight = getTimeWeight(inEdge);
                double dataWeight = getAmountWeight(inEdge);
                double structureWeight = getStructureWeight(inEdge);
                double anomalyWeight = getAnomalyWeight(inEdge);// [新增] 提取异常特征
                long edgeID = inEdge.id;
                timeWeights.put(edgeID, timeWeight);
                amountWeights.put(edgeID, dataWeight);
                structureWeights.put(edgeID, structureWeight);
                anomalyWeights.put(edgeID, anomalyWeight); // [新增]
            }
        }

        // Normalize three weights by outgoing edges
        for(EntityNode n : vertexSet){
            double structureTotal = getStructureWeightTotal(n);
            // avoid bug caused by 0/0
            if(structureTotal<1e-8){
                structureTotal = 1.0;
            }

            double amountTotal = getAmountWeightTotal(n);
            double timeTotal = getTimeWeightTotal(n);

            double anomalyTotal = getAnomalyWeightTotal(n);// [新增] 获取该节点所有出边的异常分数总和用于归一化
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            for (EventEdge e:outgoing){
                e.timeWeight = timeWeights.get(e.id)/timeTotal;
                e.amountWeight = amountWeights.get(e.id)/amountTotal;
                e.structureWeight = structureWeights.get(e.id)/structureTotal;
                e.anomalyWeight = anomalyWeights.get(e.id)/anomalyTotal;// [新增]
                if(seedSources.contains(e.getSource().getSignature())){ // the seed's structure weight is always 1.0
                    e.structureWeight = 1.0;
                }

                timeWeights.put(e.id, e.timeWeight);
                amountWeights.put(e.id, e.amountWeight);
                structureWeights.put(e.id, e.structureWeight);
                anomalyWeights.put(e.id, e.anomalyWeight);// [新增]
            }
        }

        // Calculate final Weights
        for(EntityNode n: vertexSet){
            double amount = 0.0;
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            for(EventEdge e : outEdges){
                amount += e.getSize();
            }
            double wTotal = 0.0;
            if(amount < 1e-8){
                // Calculate total weight in order to normalize
                // [修改] 数据量极小时，平分给时间、结构和异常 (例如 0.33, 0.33, 0.34)
                for( EventEdge outEdge: outEdges){
                    wTotal += outEdge.timeWeight*0.33 + outEdge.structureWeight*0.33 + outEdge.anomalyWeight*0.34;
                }

                for(EventEdge outEdge: outEdges){
                    outEdge.weight = (0.33*outEdge.timeWeight + 0.33*outEdge.structureWeight + 0.34*outEdge.anomalyWeight)/wTotal;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }else{
                // Calculate total weight in order to normalize
                // [修改] 正常情况，4个特征各占 0.25
                for(EventEdge outEdge: outEdges){
                    wTotal += outEdge.timeWeight*0.25 + outEdge.structureWeight*0.25 + outEdge.amountWeight*0.25 + outEdge.anomalyWeight*0.25;
                }
                for (EventEdge outEdge: outEdges){
                    outEdge.weight = ((0.25*outEdge.timeWeight + 0.25*outEdge.structureWeight + 0.25*outEdge.amountWeight + 0.25*outEdge.anomalyWeight)/wTotal)*0.99;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }
        }
    }
    public void calculateWeightsRandom(){
        System.out.println("calculateWeightsRandom invoked");
        Set<EntityNode> vertexSet = graph.vertexSet();
        //initializeWeights();

        // Compute individual weights
        for(EntityNode n:vertexSet){
            Set<EventEdge> inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge : inEdges){
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
        for(EntityNode n : vertexSet){
            double structureTotal = getStructureWeightTotal(n);
            // avoid bug caused by 0/0
            if(structureTotal<1e-8){
                structureTotal = 1.0;
            }

            double amountTotal = getAmountWeightTotal(n);
            double timeTotal = getTimeWeightTotal(n);
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            for (EventEdge e:outgoing){
                e.timeWeight = timeWeights.get(e.id)/timeTotal;
                e.amountWeight = amountWeights.get(e.id)/amountTotal;
                e.structureWeight = structureWeights.get(e.id)/structureTotal;

                if(seedSources.contains(e.getSource().getSignature())){ // the seed's structure weight is always 1.0
                    e.structureWeight = 1.0;
                }

                timeWeights.put(e.id, e.timeWeight);
                amountWeights.put(e.id, e.amountWeight);
                structureWeights.put(e.id, e.structureWeight);
            }
        }

        // Calculate final Weights
        for(EntityNode n: vertexSet){
            double amount = 0.0;
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            for(EventEdge e : outEdges){
                amount += e.getSize();
            }
            double wTotal = 0.0;
            if(amount < 1e-8){
                // Calculate total weight in order to normalize
                double[] weightsCof = getNCoefficient(2);
                for( EventEdge outEdge: outEdges){
                    wTotal += outEdge.timeWeight*weightsCof[0] + outEdge.structureWeight*weightsCof[1];
                }

                for(EventEdge outEdge: outEdges){
                    outEdge.weight = (weightsCof[0]*outEdge.timeWeight + outEdge.structureWeight*weightsCof[1])/wTotal;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }else{
                // Calculate total weight in order to normalize
                double[] weightsCof = getNCoefficient(3);
                for(EventEdge outEdge: outEdges){
                    wTotal += outEdge.timeWeight*weightsCof[0] + outEdge.structureWeight*weightsCof[1]+ outEdge.amountWeight*weightsCof[2];
                }

                for (EventEdge outEdge: outEdges){
                    outEdge.weight = ((outEdge.timeWeight*weightsCof[0] + outEdge.structureWeight*weightsCof[1]+ outEdge.amountWeight*weightsCof[2])/wTotal)*0.99;
                    weights.put(outEdge.id, outEdge.weight);
                }
            }
        }
    }
    private double getStructureWeightTotal(EntityNode n){
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)){
            total += structureWeights.get(e.id);
        }
        return total;
    }

    private double getAmountWeightTotal(EntityNode n){
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)){
            total += amountWeights.get(e.id);
        }
        return total;
    }

    private double getTimeWeightTotal(EntityNode n){
        double total = 0.0;
        for(EventEdge e: graph.outgoingEdgesOf(n)){
            total += timeWeights.get(e.id);
        }
        return total;
    }

    // [新增] 计算节点出边的异常分数总和（用于归一化）
    private double getAnomalyWeightTotal(EntityNode n){
        double total = 0.0;
        for (EventEdge e : graph.outgoingEdgesOf(n)){
            Double val = anomalyWeights.get(e.id);
            total += (val != null) ? val : 0.0;
        }
        return (total == 0) ? 1.0 : total; // 防止除以0
    }

    public void calculateWeights_ML_dec(boolean normalizeByOutEdges,int mode, String resDir){
        System.out.println("calculateWeights_ML_dec invoked: "+normalizeByOutEdges);
        Set<EntityNode> vertexSet = graph.vertexSet();
        //initializeWeights();

        // Compute individual weights
        Set<EventEdge> inEdges;
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                timeWeights.put(inEdge.id, getTimeWeight(inEdge));
                amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                structureWeights.put(inEdge.id, getStructureWeight(inEdge));
                anomalyWeights.put(inEdge.id, getAnomalyWeight(inEdge));// [新增] 存入原始异常权重
                //printEdgeWeights(inEdge);
            }
        }


//        // 【调试探针 START】
//        System.out.println("====== DEBUG: Checking Anomaly Scores BEFORE Normalization ======");
//        int nonZeroCount = 0;
//        int totalEdges = 0;
//        for (EntityNode n: vertexSet) {
//            for (EventEdge e : graph.outgoingEdgesOf(n)) {
//                double score = getAnomalyWeight(e); // 此时这应该返回原始分数
//                totalEdges++;
//                if (score > 0.0001) {
//                    nonZeroCount++;
//                    // 打印前5个非零的边看看
//                    if (nonZeroCount <= 5) {
//                        System.out.println("Edge " + e.getID() + " has raw anomaly score: " + score);
//                    }
//                }
//            }
//        }
//        System.out.println("Total Edges: " + totalEdges + ", Edges with Score > 0: " + nonZeroCount);
//        if (nonZeroCount == 0) {
//            System.err.println("!!! CRITICAL WARNING: All anomaly scores are ZERO. Data ingestion failed. !!!");
//        }
//        System.out.println("=================================================================");
//        // 【调试探针 END】


        // Pre-process individual weights
        preprocessWeights(timeWeights, normalizeByOutEdges);
        preprocessWeights(amountWeights, normalizeByOutEdges);
        preprocessWeights(structureWeights, normalizeByOutEdges);
        preprocessWeights(anomalyWeights, normalizeByOutEdges);// [新增] 预处理异常权重（标准化或归一化）

        // Additional pre-processing for structureWeights for seeds
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                if (seedSources.contains(inEdge.getSink().getSignature())) {
                    // Sink is seed
                    //System.out.println();
                    //System.out.println("Edges from seed sources: " + "EventEdge " + inEdge.getID() + " (" + inEdge.getSource().getID() + " " + inEdge.getSource().getSignature() + " ->" + inEdge.getEvent() + " " + inEdge.getSink().getID() + " " + inEdge.getSink().getSignature() + ")");
                    //System.out.println("Normalized structureWeight before hard set: " + structureWeights.get(inEdge.id));
                    structureWeights.put(inEdge.id, 1.0);
                    //System.out.println("Normalized structureWeight after hard set: " + structureWeights.get(inEdge.id));
                }
            }
        }
        // Store standardized weights for all edges
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                inEdge.timeWeight = timeWeights.get(inEdge.id);
                inEdge.amountWeight = amountWeights.get(inEdge.id);
                inEdge.structureWeight = structureWeights.get(inEdge.id);
                inEdge.anomalyWeight = anomalyWeights.get(inEdge.id);// [新增]
            }
        }

        // Compute aggregated weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        List<Double> finalWeights = null;
        if(mode == 1) {
            System.out.println("computeFinalWeight v1 invoked");
            finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
        }else if(mode == 2){
            System.out.println("computeFinalWeight v2 invoked");
            finalWeights = computeFinalWeights_v2(allEdges); // the weights correspond to the order of the edges
        }else if(mode == 3){
            System.out.println("computeFinalWeight v3 invoked");
            finalWeights = computeFinalWeights_v3(allEdges); // the weights correspond to the order of the edges
        }
        // Normalize weights for outgoing edges
//        for (int i = 0; i < allEdges.size(); i++) {
//            allEdges.get(i).weight = finalWeights.get(i);
//        }
        for (int i = 0; i < allEdges.size(); i++) {
            // 【暴力测试修改】强制只使用异常分数
            // 注意：这里假设 anomalyWeight 已经被归一化到了 [0,1]
            // 如果你的原始分数很大，这里可能需要注意
            double rawScore = allEdges.get(i).anomalyWeight;
            // 强制逻辑：如果有异常分，权重就是 1.0 (极高)；如果没有，就是 0.01 (极低)
            // 这样能最大化差异
            allEdges.get(i).weight = (rawScore > 0) ? 1.0 : 0.001;
        }
        for (EntityNode n: vertexSet) {
            Set<EventEdge> outEdges = graph.outgoingEdgesOf(n);
            double weightTotalForOutEdges = 0.0;
            for (EventEdge outEdge: outEdges) {
//                System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForOutEdges += outEdge.weight;
            }

            if(weightTotalForOutEdges<1e-8){
                continue;
            }
            // Normalize by weightTotalForOutEdges
            for (EventEdge outEdge: outEdges) {
//                System.out.println("Before normalization " + inEdge.weight);
//                System.out.println("Normalization factor " + weightTotalForOutEdges);

                outEdge.weight = (outEdge.weight/weightTotalForOutEdges)*0.99;
//                System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(outEdge.id, outEdge.weight);
            }
        }

        List<Double> weightList = new LinkedList<>();
        for(EventEdge edge: allEdges){
            weightList.add(edge.weight);
        }
        try{
            File file = new File(resDir+"/"+"clusterall_weights.json");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for(Double d : weightList){
                jsonArray.add(d);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void calculateWeights_Individual(boolean normalizeByInEdges, String weightType, String resDir){
        System.out.println("calculateWeights_Individual invoked: " + normalizeByInEdges + " for featureType: " + weightType);
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        Set<EventEdge> inEdges;
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                timeWeights.put(inEdge.id, getTimeWeight(inEdge));
                //amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                //structureWeights.put(inEdge.id, getStructureWeight(inEdge));
                amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                structureWeights.put(inEdge.id, 0.0);
                //printEdgeWeights(inEdge);
            }
        }

        // Pre-process individual weights
        preprocessWeights(timeWeights, normalizeByInEdges);
        preprocessWeights(amountWeights, normalizeByInEdges);
        preprocessWeights(structureWeights, normalizeByInEdges);

        // Additional pre-processing for structureWeights for seeds
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                if (seedSources.contains(inEdge.getSink().getSignature())) {
                    // Sink is seed
                    //System.out.println();
                    //System.out.println("Edges from seed sources: " + "EventEdge " + inEdge.getID() + " (" + inEdge.getSource().getID() + " " + inEdge.getSource().getSignature() + " ->" + inEdge.getEvent() + " " + inEdge.getSink().getID() + " " + inEdge.getSink().getSignature() + ")");
                    //System.out.println("Normalized structureWeight before hard set: " + structureWeights.get(inEdge.id));
                    structureWeights.put(inEdge.id, 1.0);
                    //System.out.println("Normalized structureWeight after hard set: " + structureWeights.get(inEdge.id));
                }
            }
        }

//        // Store standardized weights for all edges
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                inEdge.timeWeight = timeWeights.get(inEdge.id);
                inEdge.amountWeight = amountWeights.get(inEdge.id);
                inEdge.structureWeight = structureWeights.get(inEdge.id);
            }
        }

        // Use individual weight as final weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        List<Double> finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges

        // Normalize weights for outgoing edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n: vertexSet) {
            Set<EventEdge> outgoingEdges = graph.outgoingEdgesOf(n);
            double weightTotalForOutEdges = 0.0;
            for (EventEdge outEdge: outgoingEdges) {
//                System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForOutEdges += outEdge.weight;
            }

            if(weightTotalForOutEdges<1e-8){
                continue;
            }
            // Normalize by weightTotalForOutEdges
            for (EventEdge outEdge: outgoingEdges) {
//                System.out.println("Before normalization " + inEdge.weight);
//                System.out.println("Normalization factor " + weightTotalForOutEdges);

                outEdge.weight /= weightTotalForOutEdges;
//                System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(outEdge.id, outEdge.weight);
            }
        }

        try{
            File file = new File(resDir+"/"+weightType+"_weights.txt");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for(Double d : finalWeights){
                jsonArray.add(d);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void calculateWeights_Fanout(boolean normalizeByOutEdges, String resDir){
        System.out.println("calculateWeights_Fanout invoked: " + normalizeByOutEdges);
        Set<EntityNode> vertexSet = graph.vertexSet();

        for(EntityNode v: vertexSet){
            int idegree = graph.inDegreeOf(v);
            int odegree = graph.outDegreeOf(v);
            indegree.put(v.getSignature(), idegree);
            outdegree.put(v.getSignature(), odegree);
        }

        // Initialize weights
        HashMap<Long, Double> fanoutWeights = new HashMap<>(); // sink -> source

        Set<EventEdge> edges = graph.edgeSet();

        List<Double> finalWeights = new LinkedList<>();
        for(EventEdge e:edges){
            finalWeights.add(getFanoutWeight(e));
        }
        try{
            File file = new File(resDir+"/"+"fanout_weights.txt");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            JSONArray jsonArray = new JSONArray();
            for(Double d : finalWeights){
                jsonArray.add(d);
            }
            printWriter.write(jsonArray.toJSONString());
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
        System.out.println("Write weights to file for fanout!");
    }


    private List<Double> computeFinalWeights(List<EventEdge> allEdges) {
        System.out.println("computeFinalWeights invoked!");
        List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(allEdges, "multiKmeansPlusPlus");
        List<Double> finalWeights = dimReduction(allEdges, clusterResults);
        scaleRange(finalWeights);
        return finalWeights;
    }

    private List<Double> computeFinalWeights_v2(List<EventEdge> allEdges) {
        // Remove outlier edges (i.e., sink node only has one incoming edge) since they will always have final weight equal to 1
        System.out.println("computeFinalWeights_v2 invoked!");
        List<EventEdge> nonOutlierEdges = new ArrayList<>();
        System.out.println();
        for (EventEdge edge: allEdges) {
            if (graph.incomingEdgesOf(edge.getSink()).size() > 1) {
                nonOutlierEdges.add(edge);
            }
            else {
                System.out.print("Outlier edge: ");
                printEdgeWeights(edge);
            }
        }

        // Clustering
        List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(nonOutlierEdges, "multiKmeansPlusPlus");

        // Supervised dimensionality reduction of the weights
        List<Double> finalWeights = dimReduction(allEdges, clusterResults);

        // Scale to [0,1] (since some weights might be negative)
//        System.out.println("Before 0-1 normalize:");
//        for (double w: finalWeights) {
//            System.out.print(w + " ");
//        }
//        System.out.println();

        scaleRange(finalWeights);
//        System.out.println("After 0-1 normalize:");
//        for (double w: finalWeights) {
//            System.out.print(w + " ");
//        }
//        System.out.println();

        return finalWeights;
    }

    private List<Double> computeFinalWeights_v3(List<EventEdge> allEdges) {
        // Compute the final weight (weights) for an edge using the three individual weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already standardized

        // Note: This method locally clusters all incoming edges of each sink node, computes separate projection vectors, and compute final weights
        System.out.println("computeFinalWeights_v3 invoked!");
        List<Double> finalWeights = new ArrayList<>();
        for (int i = 0; i < allEdges.size(); i++) { // initialize to the same size
            finalWeights.add(0.0);
        }

        // For each node
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode n: vertexSet) {
            List<EventEdge> outEdges = new ArrayList<>(graph.outgoingEdgesOf(n));
            if (outEdges.size() == 0) {
                System.out.println("No outgoing edges");
            }
            else if (outEdges.size() == 1) { // Outlier edge (no incoming edges)
                // Directly set the final weights to 0
                System.out.println("Only 1 outgoing edge (outlier edge)");
                finalWeights.set(allEdges.indexOf(outEdges.get(0)), 1.0);
            }
            else { // Non-outlier edges
                // Cluster inEdges
                List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(outEdges, "multiKmeansPlusPlus");

                // Supervised dimensionality reduction of the weights
                if(clusterResults.size() == 1) {
                    double amount = 0.0;
                    for (EventEdge e : outEdges) {
                        amount += e.getSize();
                    }
                    double wTotal = 0.0;
                    if (amount < 1e-8) {
                        for (EventEdge outEdge : outEdges) {
                            finalWeights.set(allEdges.indexOf(outEdge), 0.5*outEdge.timeWeight+0.5*outEdge.structureWeight);
                        }
                    } else {
                        for (EventEdge outEdge : outEdges) {
                            finalWeights.set(allEdges.indexOf(outEdge), (0.3333) * outEdge.timeWeight + (0.3333) * outEdge.structureWeight + (0.3334) * outEdge.amountWeight);
                        }
                    }
                }else {
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
                        System.out.println("weights after normalizing by outgoing edges: " + weightsNormalizedByOutEdges.toString());
                    }

                    // Set to finalWeights
                    for (EventEdge edge : outEdges) {
                        finalWeights.set(allEdges.indexOf(edge), weightsForOutEdges.get(outEdges.indexOf(edge)));
                    }
                }
            }
        }

        return finalWeights;
    }

    private List<Cluster<EventEdgeWrapper>> clusterEdges(List<EventEdge> edges, String clusteringMethod) {
        // Wrap all edges for clustering
        List<EventEdgeWrapper> allEdgeWrappers = new ArrayList<>();
        for (EventEdge edge: edges) {
            allEdgeWrappers.add(new EventEdgeWrapper(edge));
        }

        // Different clustering method
        List<Cluster<EventEdgeWrapper>> clusterResults = null;
        if (clusteringMethod.equals("kmeansPlusPlus")) {
            // KMeans++
            KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new KMeansPlusPlusClusterer<>(2, 100000); // default distance measure
            clusterResults = new ArrayList<>(kmeansPlusPlusClusterer.cluster(allEdgeWrappers)); // CentroidCluster -> Cluster conversion

        }
        else if (clusteringMethod.equals("dbscan")) { // do not use it since it does not guarantee two clusters
            // DBSCAN
            DBSCANClusterer<EventEdgeWrapper> dbscanClusterer = new DBSCANClusterer<>(4, 1); // default distance measure
            clusterResults = dbscanClusterer.cluster(allEdgeWrappers);
        }
        else if (clusteringMethod.equals("multiKmeansPlusPlus")) {
            // Multi-KMeans++
//            // Cosine distance
//            KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new KMeansPlusPlusClusterer<>(2, 100000, new DistanceMeasure() {
//                @Override
//                public double compute(double[] doubles, double[] doubles1) throws DimensionMismatchException {
//                    return 1.0-(doubles[0]*doubles1[0]+doubles[1]*doubles1[1]+doubles[2]*doubles1[2])/
//                            (Math.sqrt(doubles[0]*doubles[0]+doubles[1]*doubles[1]+doubles[2]*doubles[2])*
//                                    Math.sqrt(doubles1[0]*doubles1[0]+doubles1[1]*doubles1[1]+doubles1[2]*doubles1[2]));
//                }
//            });

            // Default distance measure
            KMeansPlusPlusClusterer<EventEdgeWrapper> kmeansPlusPlusClusterer = new KMeansPlusPlusClusterer<>(2,100000);
            MultiKMeansPlusPlusClusterer<EventEdgeWrapper> multiKmeansPlusPlusClusterer = new MultiKMeansPlusPlusClusterer<>(kmeansPlusPlusClusterer, 20);
            clusterResults = new ArrayList<>(multiKmeansPlusPlusClusterer.cluster(allEdgeWrappers));
        }
        else {
            System.out.println("Do not support the clustering method " + clusteringMethod);
        }

        //printClusterResults(clusteringMethod, clusterResults);

        return clusterResults;
    }

    private List<Double> dimReduction(List<EventEdge> allEdges, List<Cluster<EventEdgeWrapper>> clusterResults) {
        // We use FDA for supervised dimensionality reduction (FDA is LDA in 2 classes)
        // Note: edges in clusterResults (for computing projection vector) may not be exactly allEdges (for compute final weights)

        assert(clusterResults.size() == 2); // assert there are only 2 groups

        // Store weights data in RealMatrix for easy processing
        EventEdge edge;
        double[][] weights2DArrayAll = new double[allEdges.size()][4];
        double[][] weights2DArrayG0 = new double[clusterResults.get(0).getPoints().size()][];
        double[][] weights2DArrayG1 = new double[clusterResults.get(1).getPoints().size()][];
        boolean seedEdgeInG0 = false;
        boolean seedEdgeInG1 = false;
        for (int i = 0; i < allEdges.size(); i++) {
            edge = allEdges.get(i);
            weights2DArrayAll[i] = new double[] {edge.timeWeight, edge.amountWeight, edge.structureWeight, edge.anomalyWeight};
        }
        for (int i = 0; i < clusterResults.get(0).getPoints().size(); i++) {
            edge = clusterResults.get(0).getPoints().get(i).getEventEdge();
            weights2DArrayG0[i] = new double[]{edge.timeWeight, edge.amountWeight, edge.structureWeight, edge.anomalyWeight};
            if (seedSources.contains(edge.getSource().getSignature())) {
                seedEdgeInG0 = true;
            }
        }
        for (int i = 0; i < clusterResults.get(1).getPoints().size(); i++) {
            edge = clusterResults.get(1).getPoints().get(i).getEventEdge();
            weights2DArrayG1[i] = new double[]{edge.timeWeight, edge.amountWeight, edge.structureWeight, edge.anomalyWeight};
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

//        System.out.println("weightsMatrixAll:");
//        printRealMatrix(weightsMatrixAll);
//        System.out.println();

        // Project every row in weightsMatrixAll from 3D to 1D by applying projection vector
        RealVector weightsProjectedAll = weightsMatrixAll.operate(projectionVector);
        double[] finalWeights = weightsProjectedAll.toArray();

        return new ArrayList<Double>(Arrays.asList(ArrayUtils.toObject(finalWeights)));
    }

    private RealVector computeProjectionVector(RealMatrix matrixG0, RealMatrix matrixG1) {
        // Compute projection matrix for matrixAll by maximizing the separation between groups matrixG0 and matrixG1
        // We use FDA (i.e., sw-1 (u1-u2))

        // Compute mu0 (i.e., mean vector of group 0)
        RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0, 0});
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            mu0 = mu0.add(matrixG0.getRowVector(i));
        }
        mu0.mapDivideToSelf(matrixG0.getRowDimension());

        // Compute mu1 (i.e., mean vector of group 1)
        RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0, 0});
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
        RealMatrix sw = new Array2DRowRealMatrix(4, 4);
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            sw = sw.add(matrixG0.getRowVector(i).subtract(mu0).outerProduct(matrixG0.getRowVector(i).subtract(mu0)));
        }
        for (int i = 0; i < matrixG1.getRowDimension(); i++) {
            sw = sw.add(matrixG1.getRowVector(i).subtract(mu1).outerProduct(matrixG1.getRowVector(i).subtract(mu1)));
        }

//        System.out.println("Within-group scattering matrix sw:");
//        printRealMatrix(sw);
//        System.out.println();

        // Compute between-group scattering matrix sb
        RealMatrix sb = mu0.subtract(mu1).outerProduct(mu0.subtract(mu1));

//        System.out.println("Between-group scattering matrix sb:");
//        printRealMatrix(sb);
//        System.out.println();

        // MP pseudo-inverse of sw
        DecompositionSolver solver = new SingularValueDecomposition(sw).getSolver();
        RealMatrix swInv = solver.getInverse(); // use MP pseudo-inverse
        // RealMatrix swInv = MatrixUtils.inverse(sw);

//        System.out.println("MP pseudo-inverse of sw, swInv:");
//        printRealMatrix(swInv);
//        System.out.println();

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
            if (sw.getRow(3)[0] == 0.0 && sw.getRow(3)[3] == 0.0) {
                System.out.println("4th row of sw is all-zero");
            }

            // We compare the fisherObjectiveNumerator() of two candidates
            RealVector projectionVectorCandidate1 = mu0.subtract(mu1);
            projectionVectorCandidate1.mapDivideToSelf(projectionVectorCandidate1.getNorm());
            double fisherObjectiveNumerator1 = fisherObjectiveNumerator(sb, projectionVectorCandidate1);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(mu0-mu1)/norm" + " is:" + fisherObjectiveNumerator1);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(mu0-mu1)/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate1));

            RealVector projectionVectorCandidate2 = swInv.operate(mu0.subtract(mu1));
            projectionVectorCandidate2.mapDivideToSelf(projectionVectorCandidate2.getNorm());
            double fisherObjectiveNumerator2 = fisherObjectiveNumerator(sb, projectionVectorCandidate2);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(swInv*(mu0-mu1))/norm" + " is:" + fisherObjectiveNumerator2);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(swInv*(mu0-mu1))/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate2));

            if (fisherObjectiveNumerator1 > fisherObjectiveNumerator2) {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate1;
            }
            else if (fisherObjectiveNumerator2 > fisherObjectiveNumerator1) {
                System.out.println("projection vector = (swInv*(mu0-mu1))/norm");
                projectionVector = projectionVectorCandidate2;
            }
            else {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate1;
            }
        }
        else {
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

    private double fisherObjectiveNumerator(RealMatrix sb, RealVector v) {
        // Numerator of J(v): v^T*sb*v
        return sb.preMultiply(v).dotProduct(v);
    }

    private double fisherObjectiveDenominator(RealMatrix sw, RealVector v) {
        // Denominator of J(v): v^T*sw*v
        return sw.preMultiply(v).dotProduct(v);
    }

    private void adjustProjectionVectorDirection(RealVector projectionVector, RealMatrix matrixG0, RealMatrix matrixG1, boolean seedEdgeInG0, boolean seedEdgeInG1) {
        // Adjust the direction of projection vector using some intuition

        // Pearson correlation
//        PearsonsCorrelation pearsonsCorrelation = new PearsonsCorrelation();
//        Double corr = pearsonsCorrelation.correlation(projectionVector.toArray(), new double[]{0.1, 0.5, 0.4});
//        if (corr < 0) {
//            projectionVector.mapMultiplyToSelf(-1);
//        }

//        // Dot product with (0.1, 0.5, 0.4)
//        if (projectionVector.dotProduct(new ArrayRealVector(new double[]{0.1, 0.5, 0.4})) < 0) {
//            System.out.println("Negate projection vector due to dot product");
//            projectionVector.mapMultiplyToSelf(-1);
//        }

//        // Imbalance intuition: the group with fewer edges should have higher average values
//        double mu0projected = mu0.dotProduct(projectionVector);
//        double mu1projected = mu1.dotProduct(projectionVector);
//        if ((matrixG0.getRowDimension() < matrixG1.getRowDimension() && mu0projected < 0 && mu1projected > 0) || (matrixG1.getRowDimension() < matrixG0.getRowDimension() && mu1projected < 0 && mu0projected > 0)) {
//            System.out.println("Negate projection vector due to imbalance intuition");
//            projectionVector.mapMultiplyToSelf(-1);
//        }


        // Intuition: align with the signs of the projection vector (0.1, 0.5, 0.4) in non-ml approach
        if (projectionVector.getEntry(0) <= 0 && projectionVector.getEntry(1) <= 0 && projectionVector.getEntry(2) <= 0 && projectionVector.getEntry(3) <= 0) {
            System.out.println("All 4 dimensions are non-positive. Negate.");
            projectionVector.mapMultiplyToSelf(-1);
        }
        else if (projectionVector.getEntry(0) >= 0 && projectionVector.getEntry(1) >= 0 && projectionVector.getEntry(2) >= 0 && projectionVector.getEntry(3) >= 0) {
            System.out.println("All 4 dimensions are non-negative. Keep.");
        }
        else {
            System.out.println("One or two dimensions of projection vector are negative. Negate by condition.");

            // [修改 2] 初始化 mu0 为 4维 向量 (之前是 new double[]{0,0,0})
            // 这是导致 3!=4 报错的核心原因
            RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0, 0});
            for (int i = 0; i < matrixG0.getRowDimension(); i++) {
                mu0 = mu0.add(matrixG0.getRowVector(i));
            }
            mu0.mapDivideToSelf(matrixG0.getRowDimension());

            // [修改 3] 初始化 mu1 为 4维 向量
            RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0, 0});
            for (int i = 0; i < matrixG1.getRowDimension(); i++) {
                mu1 = mu1.add(matrixG1.getRowVector(i));
            }
            mu1.mapDivideToSelf(matrixG1.getRowDimension());

            // Intuition: cluster that contains seed edges should have a higher projected mean value
            if (seedEdgeInG0 && !seedEdgeInG1) {
                System.out.println("Cluster 0 has seed edges but cluster 1 hasn't.");
                if (mu0.dotProduct(projectionVector) < mu1.dotProduct(projectionVector)) {
                    System.out.println("Negate projection vector to make sure that the cluster that contains seed edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            }
            else if (seedEdgeInG1 && !seedEdgeInG0) {
                System.out.println("Cluster 1 has seed edges but cluster 0 hasn't.");
                if (mu1.dotProduct(projectionVector) < mu0.dotProduct(projectionVector)) {
                    System.out.println("Negate projection vector to make sure that the cluster that contains seed edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            }
            else {
                if (seedEdgeInG0 && seedEdgeInG1) {
                    System.out.println("Cluster 0 and 1 both contain/don't contain seed edges.");
                }
                else if (!seedEdgeInG0 && !seedEdgeInG1) {
                    System.out.println("Cluster 0 and 1 both don't contain seed edges.");
                }

                // Intuition: cluster that contains fewer edges should have higher projected mean value
                if (matrixG0.getRowDimension() < matrixG1.getRowDimension() && mu0.dotProduct(projectionVector) < mu1.dotProduct(projectionVector)) {
                    System.out.println("Negate projection vector to make sure that the cluster 0 that contains fewer edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
                else if (matrixG1.getRowDimension() < matrixG0.getRowDimension() && mu1.dotProduct(projectionVector) < mu0.dotProduct(projectionVector)) {
                    System.out.println("Negate projection vector to make sure that the cluster 1 that contains fewer edges has a higher projected mean.");
                    projectionVector.mapMultiplyToSelf(-1);
                }
            }
        }

    }

    public void setSeedSources(Set<String> set){
        System.out.println("setSeedSource invoked!");
        seedSources = set;
    }

    private double getStructureWeight(EventEdge e){
        EntityNode sink = e.getSink();
        int inDegree = graph.inDegreeOf(sink);
        int outDegree = graph.outDegreeOf(sink);
        if(seedSources.contains(sink.getSignature())){
            return graph.edgeSet().size()*1.0;
        }
        return outDegree/(inDegree*1.0);
    }

    // [新增] 从 Edge 中获取异常分数
// 假设 EventEdge 类中已有 getAnomalyScore() 方法
    private double getAnomalyWeight(EventEdge edge){
        try {
            // 如果使用了 Edge Merge，确保这里返回的是合并后的最大值或平均值
            return edge.getAnomalyScore();
        } catch (Exception e) {
            return 0.0; // 默认值
        }
    }

    private double getFanoutWeight(EventEdge e) {
        EntityNode source = e.getSource();
        int inDegree = indegree.get(source.getSignature());
        int outDegree = outdegree.get(source.getSignature());

        double offset = 1e-6;

        if (source.getF() != null && inDegree == 0) { // read-only
            return 0.0 + offset;
        }
        else {
            return 1/(outDegree * 1.0) + offset;
        }
    }


    //PagerankIterationBackward
    public void PageRankIterationBackward(String[] highRP,String[] midRP, String[] lowRP,String detection){
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        //We don't need special treat of library any more.
        //sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while(fluctuation>=1e-5 && iterTime <3000){
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for(EntityNode v: vertexSet){
                if(sources.contains(v.getSignature())) continue;
                Set<EventEdge> edges = graph.outgoingEdgesOf(v);
                double rep = 0.0;
                for(EventEdge edge: edges){
                    EntityNode sink = edge.getSink();
                    rep += (preReputation.get(sink.getID())* edge.weight);
                }
//                rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep-preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
//            if(iterTime<=20) {
//                IterateGraph.printReputation(graph, iterTime);
//            }
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
        if(iterTime >= 3000){
            System.out.println("After 3000 round updates, the vertex reputation is still not stable. Break out!");
        }
    }
    @Deprecated
    public void PageRankIterationBackwardLimitedByStepInfo(String[] highRP,String[] midRP,
                                                           String[] lowRP,String detection, Map<String, Integer> stepInfo){
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        try{
            File stepInfoFile = new File("stepInfo.txt");
            FileWriter fileWriter = new FileWriter(stepInfoFile);
            PrintWriter pwriter = new PrintWriter(fileWriter);
            for(String key: stepInfo.keySet()){
                pwriter.println(key+": "+stepInfo.get(key).toString());
            }
            pwriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
        //We don't need special treat of library any more.
        //sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while(fluctuation>=1e-5){
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for(EntityNode v: vertexSet){
                if(v.getSignature().equals(detection))
                    System.out.println(v.reputation);
                if(sources.contains(v.getSignature())) continue;
                Set<EventEdge> edges = graph.outgoingEdgesOf(v);
                double rep = 0.0;
                for(EventEdge edge: edges){
                    EntityNode sink = edge.getSink();
                    if(stepInfo.get(sink.getSignature())<stepInfo.get(v.getSignature())) {
                        rep += (preReputation.get(sink.getID()) * edge.weight);
                    }
                }
//                rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep-preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
            if(iterTime<=20) {
                IterateGraph.printReputation(graph, iterTime);
            }
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }


    private Map<Long, Double> getReputation(){
        Set<EntityNode> vertexSet = graph.vertexSet();
        Map<Long, Double> map = new HashMap<>();
        for(EntityNode node:vertexSet){
            map.put(node.getID(), node.getReputation());
        }
        return map;
    }

    public void exportGraph(String name){
        graphIterator.exportGraph(name);
    }

    private void preprocessWeights(Map<Long, Double> weights, boolean normalizeByOutEdges) {
        if (normalizeByOutEdges) {
            // Normalize by outgoing edges
            normalizeWeightsByOutEdges(weights);
        }
        else {
            // Standardize weights
            standardizeWeights(weights);
        }
    }

    private void standardizeWeights(Map<Long,Double> weights) {
        // Standardization criterion: (x-mean)/std
        DescriptiveStatistics stats = new DescriptiveStatistics();
//        for (long sinkNodeID: weights.keySet()) {
//            for (long sourceNodeID: weights.get(sinkNodeID).keySet()) {
//                stats.addValue(weights.get(sinkNodeID).get(sourceNodeID));
//            }
//        }
        for (Long edgeID: weights.keySet()){
            stats.addValue(weights.get(edgeID));
        }
        double mean = stats.getMean();
        double std = stats.getStandardDeviation();
        double standardizedWeight;
//        for (long sinkNodeID: weights.keySet()) {
//            for (long sourceNodeID: weights.get(sinkNodeID).keySet()) {
//                standardizedWeight = (weights.get(sinkNodeID).get(sourceNodeID)-mean)/std;
////                System.out.println("Before standardize: " + weights.get(sinkNodeID).get(sourceNodeID) + ", After standardize: " + standardizedWeight);
//                weights.get(sinkNodeID).put(sourceNodeID, standardizedWeight);
//            }
//        }

        for(long edgeID: weights.keySet()){
            standardizedWeight = (weights.get(edgeID)-mean)/std;
            weights.put(edgeID, standardizedWeight);
        }
    }

    private void normalizeWeightsByOutEdges(Map<Long, Double> weights) {
        for (EntityNode n: graph.vertexSet()){
            Set<EventEdge> outgoing = graph.outgoingEdgesOf(n);
            double weightTotal = 0.0;
            for(EventEdge out: outgoing){
                weightTotal += weights.get(out.id);
            }

            if(weightTotal > Double.MIN_VALUE) {
                double normalizedWeight;
                for(EventEdge out: outgoing){
                    normalizedWeight = weights.get(out.id)/weightTotal;
                    weights.put(out.id, normalizedWeight);
                }
            }
        }
    }


    private void scaleRange(List<Double> numbers) {
        // In-place scale to (0,1+)
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (double n: numbers) {
            stats.addValue(n);
        }
        double min = stats.getMin();
        double max = stats.getMax();
        double secondMin = max;
        for (double n: numbers) {
            if (n == min) continue;
            if (n < secondMin) secondMin = n;
        }
        double offset = (secondMin-min)/100;
        System.out.println("Scaling statistics --- min: " + min + " max: " + max + " secondMin: " + secondMin + " offset: " + offset + " scaledMin: " + offset/(max-min));
        for (int i = 0; i < numbers.size(); i++) {
            numbers.set(i, (numbers.get(i)-min+offset)/(max-min));
        }
    }

    private double getTimeWeight(EventEdge edge){
        // Range: [0, Double.MAX_VALUE]
        double res;
        if(edge.getEndTime().equals(POITime)){
            // Notice: we cannot set the value to Double.MAX_VALUE since it will invalidate the standardization
            //            return Double.MAX_VALUE;
            double pseudoMinDiff = 1e-10; // since nanosecond is the minimum unit for the time stamp
            res = Math.log(1+1/Math.abs(pseudoMinDiff));
        }else{
            res = Math.log(1+1/Math.abs(edge.getEndTime().doubleValue()- POITime.doubleValue()));
//            System.out.println("endtime: " + edge.getEndTime().doubleValue() + " POI: " + POITime.doubleValue() + " abs diff: " + Math.abs(edge.getEndTime().doubleValue()- POITime.doubleValue()));
        }
        return res;
    }

    private double getAmountWeight(EventEdge edge){
//        if(edge.getEvent().equals("execve")){
//            return 1.0;
//        }
//        return Math.exp((-1)*Math.abs(edge.getSize()-detectionSize)/detectionSize);

        return 1.0/(Math.abs(edge.getSize()-detectionSize)+0.0001);
    }

    private BigDecimal getPOITime(){ // TODO: enable user-input poi time
        BigDecimal res = BigDecimal.ZERO;
        Set<EventEdge> edges = graph.edgeSet();
        for(EventEdge e : edges){
            if(e.getEndTime().compareTo(res) > 0){ // i.e., latest event
                res = e.getEndTime();
            }
        }
        return res;
    }

    private void printEdgeWeights(EventEdge edge) {
        System.out.println("EventEdge " + edge.getID() + " (" + edge.getSource().getID() + " " + edge.getSource().getSignature() + " ->" + edge.getEvent() + " " + edge.getSink().getID() + " " + edge.getSink().getSignature() + ")" + "\t\t\t timeWeight:" + timeWeights.get(edge.id) + " amountWeight: " + amountWeights.get(edge.id) + " structureWeight: " + structureWeights.get(edge.id) + " finalWeight: " + weights.get(edge.id));
    }


    private void printRealVector(RealVector vector) {
        for (int i = 0; i < vector.getDimension(); i++) {
            System.out.print(vector.getEntry(i) + " ");
        }
        System.out.println();
    }

    public void initialReputation(String[] signature_high, String[] signature_low){
        Set<EntityNode> set = graph.vertexSet();
        Set<String> highReputation = new HashSet<String>(Arrays.asList(signature_high));
        Set<String> lowReputation = new HashSet<String>(Arrays.asList(signature_low));
        for(EntityNode node : set) {
            if(highReputation.contains(node.getSignature())) {
                System.out.println(node.getSignature()+" has high reputation");
                node.reputation = 1.0;
            }else if(lowReputation.contains(node.getSignature())) {
                node.reputation = 0.0;
            }else if(graph.incomingEdgesOf(node).size() == 0) {
                node.reputation = 0.0;
            }
        }

    }

    private Set<EntityNode> getSources(EntityNode e){
        Set<EventEdge> edges  = graph.incomingEdgesOf(e);
        Set<EntityNode> sources = new HashSet<>();
        for(EventEdge edge: edges){
            sources.add(edge.getSource());
        }
        assert sources.size() <= edges.size();
        return sources;
    }

    // TODO: this needs to be tested
    public void filterGraphBasedOnAverageWeight(double threshold){
//        double averageEdgeWeight = getAvgWeight();
//        double sd = getStdWeight();
        List<EventEdge> edges = new ArrayList<>(graph.edgeSet());
//        double threshold = averageEdgeWeight*percentage;
        System.out.println("threshold: "+threshold);
        for(int i=0; i< edges.size(); i++){

            if(edges.get(i).weight < threshold){
                graph.removeEdge(edges.get(i));
            }
        }
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for(int i=0; i< list.size(); i++){
            EntityNode v = list.get(i);
            if(graph.incomingEdgesOf(v).size() == 0 && graph.outgoingEdgesOf(v).size() == 0){
                graph.removeVertex(v);
            }
        }
    }

    //TODO: 9/19/2019 This method should be based on the reputation ranking, for now we need manual inputs.
    public List<List<String>> getForwardStarts(){
        List<List<String>> res = new LinkedList<>();
        List<String> candidates = IterateGraph.getCandidateEntryPoint(graph);
        Map<String, Double> graphReputation = IterateGraph.getNodeReputation(graph);
        Map<String, EntityNode> signatureToNode = IterateGraph.getSignatureNodeMap(graph);
        List<String> processCandidate = new LinkedList<>();
        List<String> ipCandidate = new LinkedList<>();
        List<String> fileCandidate = new LinkedList<>();
        for (String sign : candidates){
            EntityNode node = signatureToNode.get(sign);
            if(node.isProcessNode()){
                processCandidate.add(sign);
            }else if(node.isNetworkNode()){
                ipCandidate.add(sign);
            }else{
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
     * 支持多个攻击入口节点一起过滤，生成包含所有可能攻击路径的完整溯源图
     * 逻辑：所有入口正向可达边的并集 → 用来过滤反向图（与单个入口完全一致的过滤精度）
     */
    public DirectedPseudograph<EntityNode, EventEdge> combineBackwardAndForwardForMultipleStarts(
            List<String> startNodeIds,
            DirectedPseudograph<EntityNode, EventEdge> original) {

        if (startNodeIds == null || startNodeIds.isEmpty()) {
            return new DirectedPseudograph<>(EventEdge.class);
        }

        // 重新初始化正向分析器（防止状态残留）
        this.forwardAnalysis = new ForwardAnalysis(original);
        BigDecimal POITime = getPOITime();

        // 1. 收集所有入口的正向子图
        List<DirectedPseudograph<EntityNode, EventEdge>> forwardGraphs = new ArrayList<>();
        for (String start : startNodeIds) {
            DirectedPseudograph<EntityNode, EventEdge> fg = forwardAnalysis.forwardLimitedByTime(start, POITime);
            // 只有真正能正向到达 POI 的才加入（避免空图干扰）
            if (fg != null && fg.edgeSet().size() > 0) {
                forwardGraphs.add(fg);
            }
        }

        // 如果所有入口都到不了 POI，直接返回空图
        if (forwardGraphs.isEmpty()) {
            return new DirectedPseudograph<>(EventEdge.class);
        }

        // 2. 合并所有正向图的边（并集）
        Map<String, Integer> forwardEdgeUnion = IterateGraph.groupsEdges(forwardGraphs);

        // 3. 用正向边并集过滤反向全图（this.graph 是类成员，已是反向可达子图）
        DirectedPseudograph<EntityNode, EventEdge> filtered = new DirectedPseudograph<>(EventEdge.class);

        for (EventEdge edge : this.graph.edgeSet()) {
            String signature = IterateGraph.convertEdgeToString(edge);
            if (forwardEdgeUnion.containsKey(signature)) {
                filtered.addVertex(edge.getSource());
                filtered.addVertex(edge.getSink());
                filtered.addEdge(edge.getSource(), edge.getSink(), edge);
            }
        }

        // 4. 恢复节点信誉度（与单入口完全一致）
        Map<String, Double> originalReputation = IterateGraph.getNodeReputation(this.graph);
        for (EntityNode node : filtered.vertexSet()) {
            Double rep = originalReputation.get(node.getSignature());
            if (rep != null) {
                node.reputation = rep;
            }
        }
        return filtered;
    }

    public DirectedPseudograph<EntityNode, EventEdge> combineBackwardAndForwardForGivenStart(String start,
                                                                                             DirectedPseudograph<EntityNode, EventEdge>original){
        forwardAnalysis = new ForwardAnalysis(original);
        BigDecimal POITime = getPOITime();
        DirectedPseudograph<EntityNode, EventEdge> forwardGraph = forwardAnalysis.forwardLimitedByTime(start, POITime);
        List<DirectedPseudograph<EntityNode, EventEdge>>forwardGraphs = new ArrayList<>();
        forwardGraphs.add(forwardGraph);
        Map<String, Integer> forwardGraphEdgeUnion = IterateGraph.groupsEdges(forwardGraphs);
        DirectedPseudograph<EntityNode, EventEdge> backFilterByForwardUion = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        for(EventEdge edge: graph.edgeSet()){
            String signatureOfCurrentEdge = IterateGraph.convertEdgeToString(edge);
            if(!forwardGraphEdgeUnion.containsKey(signatureOfCurrentEdge)){
                continue;
            }
            backFilterByForwardUion.addVertex(edge.getSource());
            backFilterByForwardUion.addVertex(edge.getSink());
            backFilterByForwardUion.addEdge(edge.getSource(), edge.getSink(), edge);
        }
        Map<String, Double> graphReputation = IterateGraph.getNodeReputation(graph);
        for(EntityNode node: backFilterByForwardUion.vertexSet()){
            node.reputation = graphReputation.get(node.getSignature());
        }
        return backFilterByForwardUion;
    }

    private double[] getNCoefficient(int n){
        double[] res = new double[n];
        Random rand = new Random();
        double sum = 0.0;
        for(int i=0;i<res.length; i++){
            res[i] = rand.nextDouble();
            sum += res[i];
        }

        for(int i=0; i < res.length; i++){
            res[i] = res[i]/sum;
        }
        return res;
    }

    Map<String, Integer> graphSizeWithoutReadonly() {

        Map<String, Integer> res = new HashMap<>();
        res.put("#Vertices before:", graph.vertexSet().size());
        res.put("#Edges beore:", graph.edgeSet().size());

        Set<EntityNode> vertices = graph.vertexSet();
        Set<Long> edgeRemove = new HashSet<>();
        Set<String> vertexRemove = new HashSet<>();
        Set<String> vertex = new HashSet<>();
        for(EntityNode n : vertices) {
            if(n.isFileNode() && graph.inDegreeOf(n)==0) {
                Set<EventEdge> edges = graph.outgoingEdgesOf(n);
                for(EventEdge e : edges)
                    //graph.removeEdge(e);
                    edgeRemove.add(e.getID());
                //graph.removeVertex(n);
                vertexRemove.add(n.getSignature());
            }
            vertex.add(n.getSignature());
        }
        res.put("#Vertices after:", graph.vertexSet().size()-vertexRemove.size());
        res.put("#Edges after:", graph.edgeSet().size()-edgeRemove.size());
        return res;
    }
}



