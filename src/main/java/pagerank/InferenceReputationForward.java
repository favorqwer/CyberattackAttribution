package pagerank;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.ml.clustering.*;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.alg.*;
import static org.junit.Assert.*;


/**
 * Created by fang on 3/12/18.
 *
 * Edited by Peng Gao on 10/31/18.
 */
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.*;

public class InferenceReputationForward {
    DirectedPseudograph<EntityNode, EventEdge> graph;
    /* the input  need to finish split step before this(this parameter need to run relevant functions first)*/
    private BigDecimal POITime;
    IterateGraph graphIterator;
    Map<Long, Double> weights;
    Map<Long, Double> timeWeights; // sink -> source
    Map<Long, Double> amountWeights;
    Map<Long, Double> structureWeights;
    double dumpingFactor;
    double detectionSize; // this value used to calculate amountWeight, default value is zero.
    Set<String> seedSources;  // get signature of seedSources in order to assign structure weight
    InferenceReputationForward(DirectedPseudograph<EntityNode, EventEdge> input){
        graph = input;
        graphIterator = new IterateGraph(graph);
        weights = new HashMap<>();
        timeWeights = new HashMap<>();
        amountWeights = new HashMap<>();
        structureWeights = new HashMap<>();
        POITime = getPOITime();
        dumpingFactor = 0.85;
        detectionSize = 0;
    }


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
                long edgeID = inEdge.id;
                timeWeights.put(edgeID, timeWeight);
                amountWeights.put(edgeID, dataWeight);
                structureWeights.put(edgeID, structureWeight);
            }
        }

        // Normalize three weights by incoming edges
        for(EntityNode n : vertexSet){
            double structureTotal = getStructureWeightTotal(n);
            // avoid bug caused by 0/0
            if(structureTotal<1e-8){
                structureTotal = 1.0;
            }

            double amountTotal = getAmountWeightTotal(n);
            double timeTotal = getTimeWeightTotal(n);
            Set<EventEdge> incoming = graph.incomingEdgesOf(n);
            for (EventEdge e:incoming){
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
            Set<EventEdge> inEdges = graph.incomingEdgesOf(n);
            for(EventEdge e : inEdges){
                amount += e.getSize();
            }
            double wTotal = 0.0;
            if(amount < 1e-8){
                // Calculate total weight in order to normalize
                for( EventEdge inEdge: inEdges){
                    wTotal += inEdge.timeWeight*0.5 + inEdge.structureWeight*0.5;
                }

                for(EventEdge inEdge: inEdges){
                    inEdge.weight = (0.5*inEdge.timeWeight + inEdge.structureWeight*0.5)/wTotal;
                    weights.put(inEdge.id, inEdge.weight);
                }
            }else{
                // Calculate total weight in order to normalize
                for(EventEdge inEdge: inEdges){
                    wTotal += inEdge.timeWeight*0.1 + inEdge.structureWeight*0.4+ inEdge.amountWeight*0.5;
                }

                for (EventEdge inEdge: inEdges){
                    inEdge.weight = (0.1*inEdge.timeWeight + 0.4*inEdge.structureWeight + 0.5*inEdge.amountWeight)/wTotal;
                    weights.put(inEdge.id, inEdge.weight);
                }
            }
        }
    }

    private double getStructureWeightTotal(EntityNode n){
        double total = 0.0;
        for (EventEdge e : graph.incomingEdgesOf(n)){
            total += structureWeights.get(e.id);
        }
        return total;
    }

    private double getAmountWeightTotal(EntityNode n){
        double total = 0.0;
        for (EventEdge e : graph.incomingEdgesOf(n)){
            total += amountWeights.get(e.id);
        }
        return total;
    }

    private double getTimeWeightTotal(EntityNode n){
        double total = 0.0;
        for(EventEdge e: graph.incomingEdgesOf(n)){
            total += timeWeights.get(e.id);
        }
        return total;
    }

    public void calculateWeights_ML(boolean normalizeByInEdges,int mode){
        System.out.println("calculateWeights_ML invoked: "+normalizeByInEdges);
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

                printEdgeWeights(inEdge);
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
                if (seedSources.contains(inEdge.getSource().getSignature())) {
                    // Source is seed
                    System.out.println();
                    System.out.println("Edges from seed sources: " + "EventEdge " + inEdge.getID() + " (" + inEdge.getSource().getID() + " " + inEdge.getSource().getSignature() + " ->" + inEdge.getEvent() + " " + inEdge.getSink().getID() + " " + inEdge.getSink().getSignature() + ")");
                    System.out.println("Normalized structureWeight before hard set: " + structureWeights.get(inEdge.id));
                    structureWeights.put(inEdge.id, 1.0);
                    System.out.println("Normalized structureWeight after hard set: " + structureWeights.get(inEdge.id));
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
            }
        }

        // Compute aggregated weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
        List<Double> finalWeights = null;
        if(mode == 1) {
            finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
        }else if(mode == 2){
            finalWeights = computeFinalWeights_v2(allEdges); // the weights correspond to the order of the edges
        }else if(mode == 3){
            finalWeights = computeFinalWeights_v3(allEdges); // the weights correspond to the order of the edges
        }
        // Normalize weights for incoming edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            double weightTotalForInEdges = 0.0;
            for (EventEdge inEdge: inEdges) {
//                System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForInEdges += inEdge.weight;
            }

            if(weightTotalForInEdges<1e-8){
                continue;
            }
            // Normalize by weightTotalForInEdges
            for (EventEdge inEdge: inEdges) {
//                System.out.println("Before normalization " + inEdge.weight);
//                System.out.println("Normalization factor " + weightTotalForInEdges);

                inEdge.weight /= weightTotalForInEdges;
//                System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(inEdge.id, inEdge.weight);
            }
        }
    }

    public void calculateWeights_Individual(boolean normalizeByInEdges, String weightType){
        System.out.println("calculateWeights_Individual invoked: " + normalizeByInEdges + " for featureType: " + weightType);
        Set<EntityNode> vertexSet = graph.vertexSet();
        // initializeWeights();

        // Compute individual weights
        Set<EventEdge> inEdges;
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                timeWeights.put(inEdge.id, getTimeWeight(inEdge));
                amountWeights.put(inEdge.id, getAmountWeight(inEdge));
                structureWeights.put(inEdge.id, getStructureWeight(inEdge));

                printEdgeWeights(inEdge);
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
                if (seedSources.contains(inEdge.getSource().getSignature())) {
                    // Source is seed
                    System.out.println();
                    System.out.println("Edges from seed sources: " + "EventEdge " + inEdge.getID() + " (" + inEdge.getSource().getID() + " " + inEdge.getSource().getSignature() + " ->" + inEdge.getEvent() + " " + inEdge.getSink().getID() + " " + inEdge.getSink().getSignature() + ")");
                    System.out.println("Normalized structureWeight before hard set: " + structureWeights.get(inEdge.id));
                    structureWeights.put(inEdge.id, 1.0);
                    System.out.println("Normalized structureWeight after hard set: " + structureWeights.get(inEdge.id));
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
            }
        }

        // Use individual weight as final weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
//        List<Double> finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
//        List<Double> finalWeights = computeFinalWeights_v2(allEdges); // the weights correspond to the order of the edges
//        List<Double> finalWeights = computeFinalWeights_v3(allEdges); // the weights correspond to the order of the edges
        List<Double> finalWeights  = computeFinalWeights_v3_Individual(allEdges, weightType); // use individual weights as final weights

        // Normalize weights for incoming edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            double weightTotalForInEdges = 0.0;
            for (EventEdge inEdge: inEdges) {
//                System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForInEdges += inEdge.weight;
            }

            if(weightTotalForInEdges<1e-8){
                continue;
            }
            // Normalize by weightTotalForInEdges
            for (EventEdge inEdge: inEdges) {
//                System.out.println("Before normalization " + inEdge.weight);
//                System.out.println("Normalization factor " + weightTotalForInEdges);

                inEdge.weight /= weightTotalForInEdges;
//                System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(inEdge.id, inEdge.weight);
            }
        }
    }

    public void calculateWeights_Fanout(boolean normalizeByInEdges){
        System.out.println("calculateWeights_Fanout invoked: " + normalizeByInEdges);
        Set<EntityNode> vertexSet = graph.vertexSet();

        // Initialize weights
        HashMap<Long, Double> fanoutWeights = new HashMap<>(); // sink -> source
        for(EntityNode n1:vertexSet){
            if (graph.incomingEdgesOf(n1).size() != 0) {
                Set<EventEdge> inEdges = graph.incomingEdgesOf(n1);
                for (EventEdge inEdge: inEdges) {
                    weights.put(inEdge.id, 0.0);
                    fanoutWeights.put(inEdge.id, 0.0);
                }
            }
        }

        // Compute individual weights
        Set<EventEdge> inEdges;
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                fanoutWeights.put(inEdge.id, getFanoutWeight(inEdge));

//                printEdgeWeights(inEdge);
            }
        }

        // Pre-process individual weights
        preprocessWeights(fanoutWeights, normalizeByInEdges);

//        // Additional pre-processing for structureWeights for seeds
//        for (EntityNode n: vertexSet) {
//            inEdges = graph.incomingEdgesOf(n);
//            for (EventEdge inEdge: inEdges) {
//                if (seedSources.contains(inEdge.getSource().getSignature())) {
//                    // Source is seed
//                    fanoutWeights.get(n.getID()).put(inEdge.getSource().getID(), 1.0);
//                }
//            }
//        }

        // Store standardized weights for all edges
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            for (EventEdge inEdge: inEdges) {
                inEdge.timeWeight = 0.0;
                inEdge.amountWeight = 0.0;
                inEdge.structureWeight = fanoutWeights.get(inEdge.id);
            }
        }

        // Use individual weight as final weight for all edges
        List<EventEdge> allEdges = new ArrayList<>(graph.edgeSet());
//        List<Double> finalWeights = computeFinalWeights(allEdges); // the weights correspond to the order of the edges
//        List<Double> finalWeights = computeFinalWeights_v2(allEdges); // the weights correspond to the order of the edges
//        List<Double> finalWeights = computeFinalWeights_v3(allEdges); // the weights correspond to the order of the edges
        List<Double> finalWeights  = computeFinalWeights_v3_Individual(allEdges, "structureWeight"); // use individual weights as final weights

        // Normalize weights for incoming edges
        for (int i = 0; i < allEdges.size(); i++) {
            allEdges.get(i).weight = finalWeights.get(i);
        }
        for (EntityNode n: vertexSet) {
            inEdges = graph.incomingEdgesOf(n);
            double weightTotalForInEdges = 0.0;
            for (EventEdge inEdge: inEdges) {
//                System.out.println(inEdge.toString()+": "+inEdge.weight);
                weightTotalForInEdges += inEdge.weight;
            }

            if(weightTotalForInEdges<1e-8){
                continue;
            }
            // Normalize by weightTotalForInEdges
            for (EventEdge inEdge: inEdges) {
//                System.out.println("Before normalization " + inEdge.weight);
//                System.out.println("Normalization factor " + weightTotalForInEdges);

                inEdge.weight /= weightTotalForInEdges;
//                System.out.println("After normalization " + inEdge.weight);

                // Store normalized weights in the "weights" map
                weights.put(inEdge.id, inEdge.weight);
            }
        }
    }


    private List<Double> computeFinalWeights(List<EventEdge> allEdges) {
        System.out.println("computeFinalWeights invoked!");
        // Compute the final weight (weights) for an edge using the three individual weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already standardized

        // Note: This method clusters all edges, compute projection vector, and project all edges for final weights

        // Clustering
        List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(allEdges, "multiKmeansPlusPlus");

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

    private List<Double> computeFinalWeights_v2(List<EventEdge> allEdges) {
        // Compute the final weight (weights) for an edge using the three individual weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already standardized

        // Note: This method clusters non-outlier edges, compute projection vector, and project all edges for final weights

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
            List<EventEdge> inEdges = new ArrayList<>(graph.incomingEdgesOf(n));
            if (inEdges.size() == 0) {
                System.out.println("No incoming edges");
            }
            else if (inEdges.size() == 1) { // Outlier edge (no incoming edges)
                // Directly set the final weights to 0
                System.out.println("Only 1 incoming edge (outlier edge)");
                finalWeights.set(allEdges.indexOf(inEdges.get(0)), 1.0);
            }
            else { // Non-outlier edges
                // Cluster inEdges
                List<Cluster<EventEdgeWrapper>> clusterResults = clusterEdges(inEdges, "multiKmeansPlusPlus");

                // Supervised dimensionality reduction of the weights
                List<Double> weightsForInEdges = dimReduction(inEdges, clusterResults);
                System.out.println("weights before scaling: " + weightsForInEdges.toString());

                // Scale to (0,1+) in case of negative weights
                scaleRange(weightsForInEdges);
                System.out.println("weights after scaling: " + weightsForInEdges.toString());

                // Print out the total weight and normalized weights for debugging purpose
                double weightTotalForInEdges = 0.0;
                for (double weight: weightsForInEdges) {
                    weightTotalForInEdges += weight;
                }
                System.out.println("Total weight for incoming edges: " + weightTotalForInEdges);
                List<Double> weightsNormalizedByInEdges = new ArrayList<>();
                if (weightTotalForInEdges > 1e-8) {
                    for (double weight: weightsForInEdges) {
                        weightsNormalizedByInEdges.add(weight/(1.0 * weightTotalForInEdges));
                    }
                    System.out.println("weights after normalizing by incoming edges: " + weightsNormalizedByInEdges.toString());
                }

                // Set to finalWeights
                for (EventEdge edge: inEdges) {
                    finalWeights.set(allEdges.indexOf(edge), weightsForInEdges.get(inEdges.indexOf(edge)));
                }
            }
        }

        return finalWeights;
    }

    private List<Double> computeFinalWeights_v3_Individual(List<EventEdge> allEdges, String weightType) {
        // Compute the final weight (weights) for an edge using the three individual weights (timeWeights, amountWeights, structureWeights).
        // Note: timeWeights, amountWeights, structureWeights should be already standardized

        // Note: This method locally clusters all incoming edges of each sink node, computes separate projection vectors, and compute final weights
        System.out.println("computeFinalWeights_v3_Individual invoked!");
        List<Double> finalWeights = new ArrayList<>();
        for (int i = 0; i < allEdges.size(); i++) { // initialize to the same size
            finalWeights.add(0.0);
        }

        // For each node
        Set<EntityNode> vertexSet = graph.vertexSet();
        for (EntityNode n: vertexSet) {
            List<EventEdge> inEdges = new ArrayList<>(graph.incomingEdgesOf(n));
            if (inEdges.size() == 0) {
                System.out.println("No incoming edges");
            }
            else if (inEdges.size() == 1) { // Outlier edge (no incoming edges)
                // Directly set the final weights to 0
                System.out.println("Only 1 incoming edge (outlier edge)");
                finalWeights.set(allEdges.indexOf(inEdges.get(0)), 1.0);
            }
            else { // Non-outlier edges

                // Set to finalWeights based on weightType
                if (weightType.equals("timeWeight")) {
                    for (EventEdge edge: inEdges) {
                        finalWeights.set(allEdges.indexOf(edge), edge.timeWeight);
                    }
                }
                else if (weightType.equals("amountWeight")) {
                    for (EventEdge edge: inEdges) {
                        finalWeights.set(allEdges.indexOf(edge), edge.amountWeight);
                    }
                }
                else if (weightType.equals("structureWeight")) {
                    for (EventEdge edge: inEdges) {
                        finalWeights.set(allEdges.indexOf(edge), edge.structureWeight);
                    }
                }
                else {
                    System.out.println("Unsupported weightType: " + weightType);
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

        printClusterResults(clusteringMethod, clusterResults);

        return clusterResults;
    }

    private List<Double> dimReduction(List<EventEdge> allEdges, List<Cluster<EventEdgeWrapper>> clusterResults) {
        // We use FDA for supervised dimensionality reduction (FDA is LDA in 2 classes)
        // Note: edges in clusterResults (for computing projection vector) may not be exactly allEdges (for compute final weights)

        assert(clusterResults.size() == 2); // assert there are only 2 groups

        // Store weights data in RealMatrix for easy processing
        EventEdge edge;
        double[][] weights2DArrayAll = new double[allEdges.size()][];
        double[][] weights2DArrayG0 = new double[clusterResults.get(0).getPoints().size()][];
        double[][] weights2DArrayG1 = new double[clusterResults.get(1).getPoints().size()][];
        boolean seedEdgeInG0 = false;
        boolean seedEdgeInG1 = false;
        for (int i = 0; i < allEdges.size(); i++) {
            edge = allEdges.get(i);
            weights2DArrayAll[i] = new double[] {edge.timeWeight, edge.amountWeight, edge.structureWeight};
        }
        for (int i = 0; i < clusterResults.get(0).getPoints().size(); i++) {
            edge = clusterResults.get(0).getPoints().get(i).getEventEdge();
            weights2DArrayG0[i] = new double[]{edge.timeWeight, edge.amountWeight, edge.structureWeight};
            if (seedSources.contains(edge.getSource().getSignature())) {
                seedEdgeInG0 = true;
            }
        }
        for (int i = 0; i < clusterResults.get(1).getPoints().size(); i++) {
            edge = clusterResults.get(1).getPoints().get(i).getEventEdge();
            weights2DArrayG1[i] = new double[]{edge.timeWeight, edge.amountWeight, edge.structureWeight};
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

        System.out.println("weightsMatrixAll:");
        printRealMatrix(weightsMatrixAll);
        System.out.println();

        // Project every row in weightsMatrixAll from 3D to 1D by applying projection vector
        RealVector weightsProjectedAll = weightsMatrixAll.operate(projectionVector);
        double[] finalWeights = weightsProjectedAll.toArray();

        return new ArrayList<Double>(Arrays.asList(ArrayUtils.toObject(finalWeights)));
    }

    private RealVector computeProjectionVector(RealMatrix matrixG0, RealMatrix matrixG1) {
        // Compute projection matrix for matrixAll by maximizing the separation between groups matrixG0 and matrixG1
        // We use FDA (i.e., sw-1 (u1-u2))

        // Compute mu0 (i.e., mean vector of group 0)
        RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0});
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            mu0 = mu0.add(matrixG0.getRowVector(i));
        }
        mu0.mapDivideToSelf(matrixG0.getRowDimension());

        // Compute mu1 (i.e., mean vector of group 1)
        RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0});
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
        System.out.println("Within-group scattering matrix sw:");
        printRealMatrix(sw);
        System.out.println();

        // Compute between-group scattering matrix sb
        RealMatrix sb = mu0.subtract(mu1).outerProduct(mu0.subtract(mu1));
        System.out.println("Between-group scattering matrix sb:");
        printRealMatrix(sb);
        System.out.println();

        // MP pseudo-inverse of sw
        DecompositionSolver solver = new SingularValueDecomposition(sw).getSolver();
        RealMatrix swInv = solver.getInverse(); // use MP pseudo-inverse
        // RealMatrix swInv = MatrixUtils.inverse(sw);
        System.out.println("MP pseudo-inverse of sw, swInv:");
        printRealMatrix(swInv);
        System.out.println();

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

    private RealVector computeProjectionVector_old_v2(RealMatrix matrixG0, RealMatrix matrixG1) {
        // Compute projection matrix for matrixAll by maximizing the separation between groups matrixG0 and matrixG1
        // We use FDA (i.e., sw-1 (u1-u2))

        // Compute mu0 (i.e., mean vector of group 0)
        RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0});
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            mu0 = mu0.add(matrixG0.getRowVector(i));
        }
        mu0.mapDivideToSelf(matrixG0.getRowDimension());

        // Compute mu1 (i.e., mean vector of group 1)
        RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0});
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
        System.out.println("Within-group scattering matrix sw:");
        printRealMatrix(sw);
        System.out.println();

        // Compute between-group scattering matrix sb
        RealMatrix sb = mu0.subtract(mu1).outerProduct(mu0.subtract(mu1));
        System.out.println("Between-group scattering matrix sb:");
        printRealMatrix(sb);
        System.out.println();

        // MP pseudo-inverse of sw
        DecompositionSolver solver = new SingularValueDecomposition(sw).getSolver();
        RealMatrix swInv = solver.getInverse(); // use MP pseudo-inverse
        // RealMatrix swInv = MatrixUtils.inverse(sw);
        System.out.println("MP pseudo-inverse of sw, swInv:");
        printRealMatrix(swInv);
        System.out.println();

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

            // We compare the fisherObjectiveNumerator() of three candidates
            RealVector projectionVectorCandidate1 = new ArrayRealVector(new double[]{0.1, 0.5, 0.4});
            projectionVectorCandidate1.mapDivideToSelf(projectionVectorCandidate1.getNorm());
            double fisherObjectiveNumerator1 = fisherObjectiveNumerator(sb, projectionVectorCandidate1);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(0.1, 0.5, 0.4)/norm" + " is:" + fisherObjectiveNumerator1);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(0.1, 0.5, 0.4)/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate1));

            RealVector projectionVectorCandidate2 = mu0.subtract(mu1);
            projectionVectorCandidate2.mapDivideToSelf(projectionVectorCandidate2.getNorm());
            double fisherObjectiveNumerator2 = fisherObjectiveNumerator(sb, projectionVectorCandidate2);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(mu0-mu1)/norm" + " is:" + fisherObjectiveNumerator2);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(mu0-mu1)/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate2));

            RealVector projectionVectorCandidate3 = swInv.operate(mu0.subtract(mu1));
            projectionVectorCandidate3.mapDivideToSelf(projectionVectorCandidate3.getNorm());
            double fisherObjectiveNumerator3 = fisherObjectiveNumerator(sb, projectionVectorCandidate3);
            System.out.println("Fisher objective numerator for candidate projection vector " + "(swInv*(mu0-mu1))/norm" + " is:" + fisherObjectiveNumerator3);
            System.out.println("Fisher objective denominator for candidate projection vector " + "(swInv*(mu0-mu1))/norm" + " is:" + fisherObjectiveDenominator(sw, projectionVectorCandidate3));

            if (fisherObjectiveNumerator1 > fisherObjectiveNumerator2 && fisherObjectiveNumerator1 > fisherObjectiveNumerator3) {
                System.out.println("projection vector = (0.1, 0.5, 0.4)/norm");
                projectionVector = projectionVectorCandidate1;
            }
            else if (fisherObjectiveNumerator2 > fisherObjectiveNumerator1 && fisherObjectiveNumerator2 > fisherObjectiveNumerator3) {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate2;
            }
            else if (fisherObjectiveNumerator3 > fisherObjectiveNumerator1 && fisherObjectiveNumerator3 > fisherObjectiveNumerator2) {
                System.out.println("projection vector = (swInv*(mu0-mu1))/norm");
                projectionVector = projectionVectorCandidate3;
            }
            else {
                System.out.println("projection vector = (mu0-mu1)/norm");
                projectionVector = projectionVectorCandidate2;
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

    private RealVector computeProjectionVector_old_v1(RealMatrix matrixG0, RealMatrix matrixG1) {
        // Compute projection matrix for matrixAll by maximizing the separation between groups matrixG0 and matrixG1
        // We use FDA (i.e., sw-1 (u1-u2))

        // Compute mu0 (i.e., mean vector of group 0)
        RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0});
        for (int i = 0; i < matrixG0.getRowDimension(); i++) {
            mu0 = mu0.add(matrixG0.getRowVector(i));
        }
        mu0.mapDivideToSelf(matrixG0.getRowDimension());

        // Compute mu1 (i.e., mean vector of group 1)
        RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0});
        for (int i = 0; i < matrixG1.getRowDimension(); i++) {
            mu1 = mu1.add(matrixG1.getRowVector(i));
        }
        mu1.mapDivideToSelf(matrixG1.getRowDimension());

        System.out.println("Mean vector of group 0 mu0:");
        printRealVector(mu0);
        System.out.println("Mean vector of group 1 mu1:");
        printRealVector(mu1);

        RealVector projectionVector;
        // Special case: matrixG0 and matrixG1 both contain only 1 row (sw = matrix(0))
        if (matrixG0.getRowDimension() == 1 && matrixG1.getRowDimension() == 1) {
            projectionVector = mu0.subtract(mu1);
        }
        else {
            // Compute within-group scattering matrix sw
            RealMatrix sw = new Array2DRowRealMatrix(3, 3);
            for (int i = 0; i < matrixG0.getRowDimension(); i++) {
                sw = sw.add(matrixG0.getRowVector(i).subtract(mu0).outerProduct(matrixG0.getRowVector(i).subtract(mu0)));
            }
            for (int i = 0; i < matrixG1.getRowDimension(); i++) {
                sw = sw.add(matrixG1.getRowVector(i).subtract(mu1).outerProduct(matrixG1.getRowVector(i).subtract(mu1)));
            }
            System.out.println("Within-group scattering matrix sw:");
            printRealMatrix(sw);

            // Compute projection vector using the FDA formula: sw-1 (u1-u2)
            DecompositionSolver solver = new SingularValueDecomposition(sw).getSolver();
            RealMatrix swInv = solver.getInverse(); // use MP pseudo-inverse
//        RealMatrix swInv = MatrixUtils.inverse(sw);

            System.out.println("swInv:");
            printRealMatrix(swInv);

            projectionVector = swInv.operate(mu0.subtract(mu1)); // swInv*(mu0-mu1)
        }

        // Normalize
        projectionVector.mapDivideToSelf(projectionVector.getNorm());

        return projectionVector;
    }

    private double fisherObjective(RealMatrix sb, RealMatrix sw, RealVector v) {
        // J(v) = (v^T*sb*v)/(v^T*sw*v)
        // sb: between-group scattering matrix, sw: within-group scattering matrix

        double numerator = sb.preMultiply(v).dotProduct(v);
        double denominator = sw.preMultiply(v).dotProduct(v); // should be non-zero
        System.out.println("v^T*sb*v: " + numerator);
        System.out.println("v^T*sw*v: " + denominator);

        return numerator/denominator;
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
        if (projectionVector.getEntry(0) <= 0 && projectionVector.getEntry(1) <= 0 && projectionVector.getEntry(2) <= 0) {
            System.out.println("All three dimensions of projection vector are non-positive. Negate the vector.");
            projectionVector.mapMultiplyToSelf(-1);
        }
        else if (projectionVector.getEntry(0) >= 0 && projectionVector.getEntry(1) >= 0 && projectionVector.getEntry(2) >= 0) {
            System.out.println("All three dimensions of projection vector are non-negative. Don't negate the vector.");
        }
        else {
            System.out.println("One or two dimensions of projection vector are negative. Negate by condition.");

            // Compute mu0 (i.e., mean vector of group 0)
            RealVector mu0 = new ArrayRealVector(new double[]{0, 0, 0});
            for (int i = 0; i < matrixG0.getRowDimension(); i++) {
                mu0 = mu0.add(matrixG0.getRowVector(i));
            }
            mu0.mapDivideToSelf(matrixG0.getRowDimension());

            // Compute mu1 (i.e., mean vector of group 1)
            RealVector mu1 = new ArrayRealVector(new double[]{0, 0, 0});
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


    private double getCombineWeight(EventEdge edge, double timeTotal, double amountTotal, double structureTotal){
        return 0.1*(edge.timeWeight/timeTotal) + 0.5*(edge.amountWeight/amountTotal) + 0.4*(edge.structureWeight/structureTotal);
    }

    private double getStructureWeight_old_v1(EventEdge e){
        EntityNode source = e.getSource();
        EntityNode target = e.getSink();
        double numOfInEdgesOfTarget = graph.incomingEdgesOf(target).size()*1.0;
        double weight = 0.0;
        double numOfInEdgesOfSource = graph.incomingEdgesOf(source).size()*1.0;
        if(numOfInEdgesOfSource == 0.0){
            weight = 1/numOfInEdgesOfTarget;
        }else{
            weight = 1/numOfInEdgesOfTarget+1/numOfInEdgesOfSource;
        }
        if(weight == 0.0){
            System.out.println("numOfInEdgesOfSource: " + numOfInEdgesOfSource);
            System.out.println("numOfInEdgesOfTarget: "+ numOfInEdgesOfTarget);
        }
        return weight;
    }

    private double getStructureWeight_old_v2(EventEdge e){
        EntityNode source = e.getSource();
        int incomingNumber =  graph.inDegreeOf(source);
        if(incomingNumber == 0){
            return 0.0;
        }
        return 1/(incomingNumber*1.0);
    }

    public void setSeedSources(Set<String> set){
        System.out.println("setSeedSource invoked!");
        seedSources = set;
    }

    private double getStructureWeight(EventEdge e){
        EntityNode source = e.getSource();
        if(seedSources.contains(source.getSignature())){ // structureWeight(seed) = total number of edges
            return graph.edgeSet().size()*1.0;
        }
        int inDegree = graph.inDegreeOf(source);
        int outDegree = graph.outDegreeOf(source);
        return inDegree/(outDegree*1.0);
    }

    private double getFanoutWeight(EventEdge e) {
        EntityNode source = e.getSource();
        int inDegree = graph.inDegreeOf(source);
        int outDegree = graph.outDegreeOf(source);

        double offset = 1e-6;

        if (source.getF() != null && inDegree == 0) { // read-only
            return 0.0 + offset;
        }
        else {
            return 1/(outDegree * 1.0) + offset;
        }
    }

    private double getWeightAboueEdgesNumber(EntityNode e){
        double weightBasedOnEdgeNumber = 0.0;
        Set<EntityNode> sourceOfIncoming = getSources(e);
        for(EntityNode node: sourceOfIncoming){
            Set<EventEdge> sourceFornode = graph.incomingEdgesOf(node);
            if(sourceFornode.size() == 0){
                weightBasedOnEdgeNumber += 1/(sourceOfIncoming.size()*1.0);
            }else{
                weightBasedOnEdgeNumber += 1/(sourceOfIncoming.size()*1.0) +
                        1/(sourceFornode.size()*1.0);
            }
        }
        return weightBasedOnEdgeNumber;
    }

    public void PageRankIteration(String detection){
        Set<EntityNode> vertexSet = graph.vertexSet();
        double fluctuation = 1.0;
        int iterTime = 0;
        System.out.println();
        while(fluctuation >= 0.0000000000001){
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for(EntityNode v: vertexSet){
                if(v.getSignature().equals(detection))
                    System.out.println(v.reputation);
                Set<EventEdge> edges = graph.incomingEdgesOf(v);
                if(edges.size() == 0) continue;
                double rep = 0.0;
                for(EventEdge edge: edges){
                    EntityNode source = edge.getSource();
                    int numberOfOutEgeFromSource = graph.outDegreeOf(source);
                    double total_weight = 0.0;
//                    for (EventEdge oe:graph.outgoingEdgesOf(source)){
//                        total_weight += weights.get(graph.getEdgeTarget(oe).getID()).get(source.getID());
//                    }
                    rep+= preReputation.get(source.getID())*weights.get(edge.id);
                }
                rep = rep*dumpingFactor+(1-dumpingFactor)/vertexSet.size();
                culmativediff += Math.abs(rep-preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }
    // Forward propagate
    public void PageRankIterationForward(String[] highRP,String[] midRP, String[] lowRP,String detection){
        double alarmlevel = 0.85;
        Set<EntityNode> vertexSet = graph.vertexSet();
        // Accorfing to new design we only have low or high two initial value
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
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
                Set<EventEdge> edges = graph.incomingEdgesOf(v);
                double rep = 0.0;
                //No need for special treat of library any more
//                if(edges.size() == 0)
//                    rep = 0.5;
                for(EventEdge edge: edges){
                    EntityNode source = edge.getSource();
//                    int numberOfOutEgeFromSource = graph.outDegreeOf(source);
//                    double total_weight = 0.0;
//                    for (EventEdge oe:graph.outgoingEdgesOf(source)){
//                        total_weight += weights.get(graph.getEdgeTarget(oe).getID()).get(source.getID());
//                    }
//                    rep+=(preReputation.get(source.getID())*weights.get(v.getID()).get(source.getID())/1);
                    rep += (preReputation.get(source.getID())* edge.weight);
                }
//                rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep-preReputation.get(v.getID()));
                v.setReputation(rep);
            }
            //Need to remove in the future
            fluctuation = culmativediff;
            IterateGraph.printReputation(graph, iterTime);
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }

    protected void normalizeWeightsAfterFiltering(){
        Set<EntityNode> vertices = graph.vertexSet();
        for(EntityNode v: vertices){
            double totalWeight = 0.0;
            Set<EventEdge> edges = graph.incomingEdgesOf(v);
            for(EventEdge e: edges)
                totalWeight += e.weight;
            for(EventEdge e: edges){
                e.weight = e.weight/totalWeight;
            }

        }
    }

    protected void fixReputation(String[] highRP){
        Map<Long, Double> reputation = getReputation();
        Set<String> s = new HashSet<>(Arrays.asList(highRP));
        double high_rep = 0.0;
        int count = 0;
        for(EntityNode v: graph.vertexSet()){
            if(s.contains(v.getSignature())) {
                high_rep += reputation.get(v.getID());
                count++;
            }
        }
        high_rep /= count;
        for(EntityNode v: graph.vertexSet())
            v.setReputation(Math.min(1-(high_rep-reputation.get(v.getID()))/high_rep,1));
    }

    protected  void extractSuspects(double threshold){
        List<EntityNode> vertices = new ArrayList(graph.vertexSet());
        for(EntityNode v:vertices){
            if(v.reputation>=threshold)
                graph.removeVertex(v);
        }
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

//    private void initializeWeights(){
//        Set<EntityNode> vertexSet = graph.vertexSet();
//        for(EntityNode n1:vertexSet){
//            if (graph.incomingEdgesOf(n1).size() != 0) {
//                // n1 -> source not empty
//                weights.put(n1.getID(), new HashMap<Long,Double>());
//                timeWeights.put(n1.getID(), new HashMap<Long,Double>());
//                amountWeights.put(n1.getID(), new HashMap<Long,Double>());
//                structureWeights.put(n1.getID(), new HashMap<Long,Double>());
//
//                // Only store the <currentNode, parentNode> pairs
//                Set<EventEdge> inEdges = graph.incomingEdgesOf(n1);
//                for (EventEdge inEdge: inEdges) {
//                    weights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
//                    timeWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
//                    amountWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
//                    structureWeights.get(n1.getID()).put(inEdge.getSource().getID(),0.0);
//                }
//            }
//        }
//    }

    private void preprocessWeights(Map<Long, Double> weights, boolean normalizeByInEdges) {
        if (normalizeByInEdges) {
            // Normalize by incoming edges
            normalizeWeightsByInEdges(weights);
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

    private void normalizeWeightsByInEdges(Map<Long, Double> weights) {
        for (EntityNode n: graph.vertexSet()){
            Set<EventEdge> incoming = graph.incomingEdgesOf(n);
            double weightTotal = 0.0;
            for(EventEdge incom: incoming){
                weightTotal += weights.get(incom.id);
            }

            if(weightTotal > 1e-8) {
                double normalizedWeight;
                for(EventEdge incom: incoming){
                    normalizedWeight = weights.get(incom.id)/weightTotal;
                    weights.put(incom.id, normalizedWeight);
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

    public void setReliableReputation(String[] strs){
        Set<String> set = new HashSet<String>(Arrays.asList(strs));
        Set<EntityNode> vertexSet = graph.vertexSet();
        for(EntityNode v:vertexSet){
            if(set.contains(v.getSignature())){
                v.setReputation(1.0);
            }
        }
    }

    private double getTimeWeight_old(EventEdge edge){
        if(edge.getEndTime().equals(POITime)){
            return 1;
        }else{
            BigDecimal diff = POITime.subtract(edge.getEndTime());
            //System.out.println("Time diff is: "+ diff.toString());
            if(diff.compareTo(new BigDecimal(1))>0){
                return 1/diff.doubleValue();
            }
            double res = Math.log(1/ diff.doubleValue());
            if(res == 0.0){
                System.out.println("timeWight should not be zero");

            }
            if(res < 0.0) System.out.println("Minus TimeWeight:" + res);
            return res;
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

    private double getAmountWeight_old(EventEdge edge){
        //System.out.println("Amount weight: "+ edge.getSize());
        return edge.getSize();
    }

    private double getAmountWeight(EventEdge edge){
//        if(edge.getEvent().equals("execve")){
//            return 1.0;
//        }
//        return Math.exp((-1)*Math.abs(edge.getSize()-detectionSize)/detectionSize);

        return 1.0/(Math.abs(edge.getSize()-detectionSize)+0.0001);
    }

    public void printWeights() throws Exception{
        PrintWriter writer = new PrintWriter(String.format("%s.txt", "EdgeWeights"));
        if(weights == null) System.out.println("weithis is null or size equal to zero");
        System.out.println(weights.keySet().size());
        for(Long id:  weights.keySet()){
//            Map<Long, Double> sub = weights.get(id);
//            for(Long id2: weights.keySet()){
//                //writer.println(String.format("%d_%d : %f", id, id2, weights.get(id).get(id2)));
//                if(!weights.get(id).get(id2).equals(0.0)) {
//                    writer.println(String.format("%d_%d : %f", id, id2, weights.get(id).get(id2)));
//                    //System.out.println(String.format("%d_%d : %f", id, id2, weights.get(id).get(id2)));
//                }
//            }

            writer.println(String.format("%d: %f", id, weights.get(id)));
        }
        writer.close();
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

    public void printReputation(){
        graphIterator.printVertexReputation();
    }

    private void printEdgeWeights(EventEdge edge) {
        System.out.println("EventEdge " + edge.getID() + " (" + edge.getSource().getID() + " " + edge.getSource().getSignature() + " ->" + edge.getEvent() + " " + edge.getSink().getID() + " " + edge.getSink().getSignature() + ")" + "\t\t\t timeWeight:" + timeWeights.get(edge.id) + " amountWeight: " + amountWeights.get(edge.id) + " structureWeight: " + structureWeights.get(edge.id) + " finalWeight: " + weights.get(edge.id));
    }

    private void printClusterResults(String clusterMethod, List<Cluster<EventEdgeWrapper>> clusterResults) {
        System.out.println();
        System.out.println(clusterMethod + " clustering:");
        for (int i = 0; i < clusterResults.size(); i++) {
            System.out.println();
            System.out.println("Cluster " + i);
            for (EventEdgeWrapper edgeWrapper: clusterResults.get(i).getPoints()) {
                EventEdge edge = edgeWrapper.getEventEdge();
                printEdgeWeights(edge);
            }
        }
        System.out.println();
    }

    private void printRealMatrix(RealMatrix matrix) {
        for (int i = 0; i < matrix.getRowDimension(); i++) {
            for (int j = 0; j < matrix.getColumnDimension(); j++) {
                System.out.print(matrix.getEntry(i, j) + " ");
            }
            System.out.println();
        }
    }

    private void printRealVector(RealVector vector) {
        for (int i = 0; i < vector.getDimension(); i++) {
            System.out.print(vector.getEntry(i) + " ");
        }
        System.out.println();
    }

    public void checkTimeAndAmount(){
        Set<EventEdge> edges = graph.edgeSet();
        for(EventEdge edge : edges){
            if(edge.getDuration().equals(BigDecimal.ZERO)){
                System.out.println("this is because amount is zero");
                System.out.println(edge.getID());
                System.out.println(edge.getSource().getSignature());
                //System.out.println(edge.getSink().getSignature());
            }

            if(edge.getSize() == 0){
                System.out.println("this is because size is zero");
                System.out.println(edge.getID());
                System.out.println(edge.getSource().getSignature());
            }
        }
    }

    private boolean someWithDataSomeNoData(EntityNode n){
        Set<EventEdge> edges = graph.incomingEdgesOf(n);
        boolean oneEdgeNoData = false;
        boolean oneEdgeWithData = false;
        for(EventEdge e: edges){
            if(e.getSize()==0){
                oneEdgeNoData = true;
            }
            if(e.getSize()!=0){
                oneEdgeWithData = true;
            }
            if(oneEdgeNoData && oneEdgeWithData){
                return true;
            }
        }
        return false;
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

    public void printConstantPartOfPageRank(){
        double res = (1-dumpingFactor)/graph.vertexSet().size();
        System.out.println("The constant part of Page Rank:" + res);
    }

    public void checkWeightsAfterCalculation(){
        Set<EntityNode> vertexSet = graph.vertexSet();
        for(EntityNode node : vertexSet){
            Set<EventEdge> incoming = graph.incomingEdgesOf(node);
            double res = 0.0;
            for(EventEdge edge:incoming){
                res += edge.weight;
            }
            if(incoming.size() != 0 && Math.abs(res-1.0) >=0.00001){
                System.out.println("Target: "+ node.getSignature());
                for(EventEdge edge :incoming){
                    edge.printInfo();
                }
                System.out.println("-----------");
            }
        }
    }

    public void onlyPrintHighestWeights(String start){
        EntityNode v1 = graphIterator.getGraphVertex(start);
        Map<Long, EntityNode> map = new HashMap<>();
        map.put(v1.getID(), new EntityNode(v1));

        DirectedPseudograph<EntityNode, EventEdge> result = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        Queue<EntityNode> queue = new LinkedList<>();
        queue.offer(v1);
        while(!queue.isEmpty()){
            EntityNode node = queue.poll();
            Set<EventEdge> incoming = graph.incomingEdgesOf(node);
            Set<EventEdge> outgoing = graph. outgoingEdgesOf(node);
            EventEdge incomingHighestWeight = getHighestWeightEdge(incoming);
            EventEdge outgoingHighestWeight = getHighestWeightEdge(outgoing);
            if(incomingHighestWeight!= null){
                if(!map.containsKey(incomingHighestWeight.getSource().getID())) {
                    map.put(incomingHighestWeight.getSource().getID(), new EntityNode(incomingHighestWeight.getSource()));
                    queue.offer(incomingHighestWeight.getSource());
                }
                EventEdge incomingCopy = new EventEdge(incomingHighestWeight);
                EntityNode copy1 = map.get(node.getID());
                EntityNode copy2 = map.get(incomingHighestWeight.getSource().getID());
                result.addVertex(copy1);
                result.addVertex(copy2);
                result.addEdge(copy2, copy1, incomingCopy);
            }
            if(outgoingHighestWeight != null) {
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

    private EventEdge getHighestWeightEdge(Set<EventEdge> edges){
        List<EventEdge> edgeList = new ArrayList<>(edges);
        if(edgeList.size() == 0) return null;
        EventEdge res = edgeList.get(0);
        for(int i=1; i< edgeList.size(); i++){
            if(res.weight < edgeList.get(i).weight){
                res = edgeList.get(i);
            }
        }
        return res;
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

    public double getAvgWeight(){
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for(EventEdge edge : graph.edgeSet()){
            stats.addValue(edge.weight);
        }
        return stats.getMean();
    }

    public double getStdWeight(){
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for(EventEdge edge : graph.edgeSet()){
            stats.addValue(edge.weight);
        }
        return stats.getStandardDeviation();
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

//    public double OTSUThreshold(){
//        List<Double> weights  = new ArrayList<>();
//        for(EventEdge e: graph.edgeSet())
//            weights.add(e.weight);
//        Collections.sort(weights);
//
//        double min_sigma = Double.MAX_VALUE, sum = 0.0;
//        int min_i = 0, length = weights.size();
//        for(int i = 0; i < length; i++) sum += weights.get(i);
//        double sum1 = 0.0;
//        for(int i = 0; i< length; i++) {
//            sum1+=weights.get(i);
//            double avg1 = sum1/(i+1);
//            double avg2 = (sum-sum1)/(length-i-1);
//            double sigma1 = 0.0, sigma2 = 0.0;
//            for(int j = 0; j <= i; j++)
//                sigma1 += Math.pow(weights.get(i)-avg1,2.0);
//            for(int j = i+1; j < length; j++)
//                sigma2 += Math.pow(weights.get(i)-avg2,2.0);
//            if(sigma1+sigma2 < min_sigma){
//                min_sigma = sigma1 + sigma2;
//                min_i = i;
//            }
//        }
//        return weights.get(min_i);
//    }

    public void removeIsolatedIslands(String POI){
        ConnectivityInspector ci = new ConnectivityInspector(graph);
        Set verticesConnectedToPOI = ci.connectedSetOf(graphIterator.getGraphVertex(POI));
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for(int i=0; i< list.size(); i++){
            EntityNode v = list.get(i);
            if(!verticesConnectedToPOI.contains(v)){
                graph.removeVertex(v);
            }
        }
    }

    public void removeIrrelaventVertices(String POI){
        EntityNode POIVertex = graphIterator.getGraphVertex(POI);
        LinkedList<EventEdge> queue = new LinkedList<>(graph.incomingEdgesOf(POIVertex));
        Set<EntityNode> ancestors = new HashSet<>();
        ancestors.add(POIVertex);
        while(!queue.isEmpty()){
            EntityNode v = graph.getEdgeSource(queue.pollLast());
            ancestors.add(v);
            for(EventEdge e:graph.incomingEdgesOf(v))
                if(!ancestors.contains(graph.getEdgeSource(e)))
                    queue.addFirst(e);
        }

        queue = new LinkedList<>(graph.outgoingEdgesOf(POIVertex));
        Set<EntityNode> children = new HashSet<>();
        children.add(POIVertex);
        while(!queue.isEmpty()){
            EntityNode v = graph.getEdgeTarget(queue.pollLast());
            children.add(v);
            for(EventEdge e:graph.outgoingEdgesOf(v))
                if(!children.contains(graph.getEdgeTarget(e)))
                    queue.addFirst(e);
        }

        ancestors.addAll(children);
        List<EntityNode> list = new ArrayList<>(graph.vertexSet());
        for(int i=0; i< list.size(); i++){
            EntityNode v = list.get(i);
            if(!ancestors.contains(v)){
                graph.removeVertex(v);
            }
        }

    }

    public long getDataAmount(String signature){
        EntityNode node = graphIterator.getGraphVertex(signature);
        long res = 0;
        Set<EventEdge> edges = graph.incomingEdgesOf(node);
        for(EventEdge e:edges){
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
        return 2*(1/( 1 + Math.pow(Math.E,(-1*x))))-1;
    }

}

