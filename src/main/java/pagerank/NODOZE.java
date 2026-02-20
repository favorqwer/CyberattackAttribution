package pagerank;
import java.util.*;

import org.jgrapht.GraphPath;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;


public class NODOZE {
    Set<String> ipMalicious;
    Set<String> fileMalicious;
    Map<String, Map<String, Map<String, Double>>>  frequencyMap;
    Map<String, Double> inProcess;
    Map<String, Double> outProcess;
    Map<String, Double> fileScore;
    Map<String, Double> ipScore;
    DirectedPseudograph<EntityNode, EventEdge> rawGraph;
    DirectedPseudograph<EntityNode, EventEdge> backtrack;
    DirectedPseudograph<EntityNode, EventEdge> graphAfterCPR;
    String poiEvent;
    IterateGraph helper;
    Set<String> importantEntries = new HashSet<>();

    NODOZE(List<String> ip, List<String> file, DirectedPseudograph<EntityNode, EventEdge> original,
           DirectedPseudograph<EntityNode, EventEdge>backtrack, DirectedPseudograph<EntityNode, EventEdge>graphAfterCPR,
           String poi, String[] importantEntries){
        ipMalicious = new HashSet<>(ip);
        fileMalicious = new HashSet<>(file);
        frequencyMap = new HashMap<>();
        rawGraph = original;
        this.backtrack = backtrack;
        this.graphAfterCPR = graphAfterCPR;
        poiEvent = poi;
        helper = new IterateGraph(graphAfterCPR);
        inProcess = new HashMap<>();
        outProcess = new HashMap<>();
        fileScore = new HashMap<>();
        ipScore = new HashMap<>();
        for(String e: importantEntries){
            this.importantEntries.add(e);
        }
        initialize();
    }


    private void initialize(){
        updateFrequencyMap();
        updateInAndOutProcessMap();
        updateFileScoreMap();
        updateIPScore();
    }

    public int filterExp(){
        EntityNode target = helper.getGraphVertex(poiEvent);
        Set<EntityNode> vertex = graphAfterCPR.vertexSet();
        Set<EntityNode> targetVertex = new HashSet<>();
        targetVertex.add(target);
        List<GraphPath<EntityNode, EventEdge>> allPaths = new LinkedList<>();
        AllDirectedPaths<EntityNode, EventEdge> findPath = new AllDirectedPaths<>(graphAfterCPR);
        for(EntityNode v: vertex){
            if (v.getSignature().equals(poiEvent)){
                continue;
            }
            if(graphAfterCPR.inDegreeOf(v) == 0) {
                List<GraphPath<EntityNode, EventEdge>> path = findPath.getAllPaths(v, target, true, 7);
                allPaths.addAll(path);
                if(allPaths.size()>=18000){
                    break;
                }
            }
        }

        //List<GraphPath<EntityNode, EventEdge>> allPaths = findPath.getAllPaths(srcVertex,targetVertex,true, 10);
        System.out.println("Paths size: "+ String.valueOf(allPaths.size()));
        Map<Integer, List<Integer>> lengthIndex = getLengthIndex(allPaths);
        Map<Integer, Double> pathScores = calculateScoreForEachPath(allPaths);
        System.out.println("------PathScores--------");
        System.out.println(pathScores);
        Map<Integer, Double> averageScoreForEachLength = calculateScoreForEachLength(lengthIndex, pathScores);
        System.out.println("---------Average Path Score for each length");
        System.out.println(averageScoreForEachLength);
        List<Integer> filterRes = filterBasedOnScore(averageScoreForEachLength, pathScores, allPaths);
        System.out.println("------filterResSize-----");
        System.out.println(filterRes.size());
        Set<Long> edgeInPath = getEdgeInFilterRes(filterRes, allPaths);
        return edgeInPath.size();
    }

    private Set<Long> getEdgeInFilterRes(List<Integer> filterRes, List<GraphPath<EntityNode, EventEdge>>allPaths){
        Set<Long> res = new HashSet<>();
        for(Integer i : filterRes){
            GraphPath<EntityNode, EventEdge> path = allPaths.get(i);
            List<EventEdge> edge = path.getEdgeList();
            for(EventEdge e: edge){
                res.add(e.getID());
            }
        }
        return res;
    }

    private List<Integer> filterBasedOnScore(Map<Integer, Double>averScoreForEachLength, Map<Integer, Double> pathScores
            ,List<GraphPath<EntityNode, EventEdge>> allPath){
        List<Integer> res = new ArrayList<>();
        for (int i=0; i < allPath.size(); i++){
            GraphPath<EntityNode, EventEdge> curPath = allPath.get(i);
            Double pathScore = pathScores.get(i);
            Double standard = averScoreForEachLength.get(curPath.getLength())*1.000000000000003;
            if(pathScore >= standard){
                res.add(i);
            }
        }
        return res;
    }

    private Map<Integer, Double> calculateScoreForEachLength(Map<Integer, List<Integer>> lengthIndex, Map<Integer, Double> pathScores){
        Map<Integer, Double> average = new HashMap<>();
        for(Integer length : lengthIndex.keySet()){
            List<Integer> paths = lengthIndex.get(length);
            double scoreSum = 0.0;
            for(Integer i:paths){
                scoreSum += pathScores.get(i);
            }
            double averageScore = scoreSum/paths.size();
            average.put(length, averageScore);
        }
        return average;
    }

    private Map<Integer, Double> calculateScoreForEachPath(List<GraphPath<EntityNode, EventEdge>> allPaths){
        Map<Integer, Double> pathScore = new HashMap<>();

        for (int i = 0; i < allPaths.size(); i++){
            GraphPath<EntityNode, EventEdge> curPath = allPaths.get(i);
            List<EventEdge> edges = curPath.getEdgeList();
            double score = 1.0;
            for(EventEdge e : edges){
                String src = e.getSource().getSignature();
                String dst = e.getSink().getSignature();
                String evt = e.getEvent();
                double scoreSrc = importantEntries.contains(src)? 0.0:1.0;
                double dstScore = importantEntries.contains(dst)? 0.0:1.0;
                score *= scoreSrc*calculatePossibilityScore(src,  dst, evt)*dstScore;
            }
            score = 1 - score;
            pathScore.put(i, score);
        }
        return pathScore;
    }

    private Map<Integer, List<Integer>> getLengthIndex(List<GraphPath<EntityNode, EventEdge>> allPaths){
        Map<Integer, List<Integer>> lengthIndex = new HashMap<>();
        for (int i = 0; i < allPaths.size(); i++){
            GraphPath<EntityNode, EventEdge> curPath = allPaths.get(i);
            int length = curPath.getLength();
            if (!lengthIndex.containsKey(length)){
                lengthIndex.put(length, new LinkedList<>());
            }
            lengthIndex.get(length).add(i);
        }
        return lengthIndex;
    }

    private void updateInAndOutProcessMap(){
        Set<EntityNode> vertex = rawGraph.vertexSet();
        for (EntityNode v : vertex){
            if(v.isProcessNode()){
                String signature = v.getSignature();
                double total = (rawGraph.inDegreeOf(v) + rawGraph.outDegreeOf(v))*1.0;
                if(!inProcess.containsKey(signature)){
                    inProcess.put(signature, rawGraph.inDegreeOf(v)/total);
                }
                if(!outProcess.containsKey(signature)){
                    outProcess.put(signature, rawGraph.outDegreeOf(v)/total);
                }
            }
        }
    }

    private void updateIPScore(){
        Set<EntityNode> vertex = rawGraph.vertexSet();
        for(EntityNode v : vertex){
            if(v.isNetworkNode()){
                String signature = v.getSignature();
                if (!ipScore.containsKey(signature)){
                    ipScore.put(signature, 1.0);
                }
            }
        }
    }

    private void updateFrequencyMap(){
        Set<EventEdge> edgeSet = rawGraph.edgeSet();
        for (EventEdge edge:edgeSet){
            String src = edge.getSource().getSignature();
            String dst = edge.getSink().getSignature();
            String evt = edge.getEvent();
            if (!frequencyMap.containsKey(src)){
                frequencyMap.put(src, new HashMap<>());
            }

            if(!frequencyMap.get(src).containsKey(evt)){
                frequencyMap.get(src).put(evt, new HashMap<>());
            }

            frequencyMap.get(src).get(evt).put(dst, frequencyMap.get(src).get(evt).getOrDefault(dst, 0.0)+1);
        }
    }

    private void updateFileScoreMap(){
        Set<EntityNode> vertex = rawGraph.vertexSet();
        for(EntityNode v : vertex){
            if(v.isFileNode()){
                String signature = v.getSignature();
                if (!fileScore.containsKey(signature)){
                    if(signature.endsWith("txt")){
                        fileScore.put(signature, 1.0);
                    }else{
                        fileScore.put(signature, 0.0);
                    }
                }
            }
        }
    }


    private double getSumBasedOnEvtSrcAndType(String evtSrc, String evtType){
        if (!frequencyMap.containsKey(evtSrc)){
            System.out.println("The input evtSrc doesn't not exist in FrequencyMap");
        }

        Map<String, Map<String, Double>> events = frequencyMap.get(evtSrc);

        if (!events.containsKey(evtType))  {
            System.out.println("The given evt type is not found for " + evtSrc);
        }

        Map<String, Double> givenTypeEvents = events.get(evtType);
        double  sum = 0.0;
        for(String dst : givenTypeEvents.keySet()){
            sum += givenTypeEvents.get(dst);
        }
        return sum;
    }

    private double calculatePossibilityScore(String src, String dst, String evt){
        double giveEventTimes = frequencyMap.get(src).get(evt).get(dst);
        double givenSrcAndEvt = getSumBasedOnEvtSrcAndType(src, evt);
        return giveEventTimes/givenSrcAndEvt;
    }

    private double calculateInScore(String src){
        if(inProcess.containsKey(src)){
            return inProcess.get(src);
        }
        return 1.0;
    }

}
