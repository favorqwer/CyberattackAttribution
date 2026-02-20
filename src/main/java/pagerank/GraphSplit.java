package pagerank;

import org.jgrapht.ext.DOTExporter;
import org.jgrapht.graph.DirectedPseudograph;


import java.io.FileWriter;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Queue;
import java.util.*;

/**
 * Created by fang on 1/24/18.
 */

public class GraphSplit {
    private class VertexPair{
        EntityNode source;
        EntityNode sink;
        List<EventEdge> edgeList;

        VertexPair(EntityNode source, EntityNode sink, List<EventEdge> edges){
            this.source = source;
            this.sink = sink;
            edgeList = edges;

        }
    }
    DirectedPseudograph<EntityNode, EventEdge> inputGraph;
    long curMaxVertexID;
    long curMaxEdgeID;
    private DOTExporter<EntityNode,EventEdge> exporter;
    Set<String> originalSignature;
    IterateGraph iter;


    public GraphSplit(DirectedPseudograph<EntityNode, EventEdge> input){

        inputGraph = input;
        curMaxEdgeID = getMaxEdgeID(input);
        curMaxVertexID = getMaxVertexID(input);
        exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),
                new EntityNameProvider(),null);
        originalSignature = new HashSet<>();
        Set<EntityNode> set= inputGraph.vertexSet();
        for(EntityNode v: set){
            originalSignature.add(v.getSignature());
        }
    }

    public void splitGraph(){
        System.out.println("splitGraph invoked!");
        List<VertexPair> list = getVertexPairNeedToBeSplited(inputGraph);
        System.out.println("Original ID is correct or not :");
        IDissue(inputGraph);
        Set<EntityNode> vertexHaveBeenSplited = new HashSet<>();
        DirectedPseudograph<EntityNode, EventEdge> newGraph =
                new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);

        Set<EntityNode> vertexNeedToBeSplited = getVertexSet(list);
        Queue<VertexPair> queue = new LinkedList<>(list);
        if(queue.size() > 0) {
            System.out.println("This graph need split");
        }else{
            System.out.println("Don't need split");
        }

        while(!queue.isEmpty()){
            while(!queue.isEmpty()) {
                VertexPair vPair = queue.poll();
                if (vertexHaveBeenSplited.contains(vPair.source) || vertexHaveBeenSplited.contains(vPair.sink)) {
                    continue;
                }
                List<EventEdge> otherEdges = getOtherEdges(inputGraph, vPair.source, vPair.sink);
                List<EventEdge> edgesBetweenTheseTwo = vPair.edgeList;
                updateGraph(otherEdges, edgesBetweenTheseTwo, vPair.source, vPair.sink, vertexHaveBeenSplited);
            }
            List<VertexPair> nextRound = getVertexPairNeedToBeSplited(inputGraph);
            if(nextRound.size() != 0) {
                Queue<VertexPair> nextQueue = new LinkedList<>(nextRound);
                queue = nextQueue;
            }
        }
        rebuildTimeLogical(vertexHaveBeenSplited);
        IDissue(inputGraph);
        iter = new IterateGraph(inputGraph);
//        removeNecessaryVertex();
    }

    private void updateGraph(List<EventEdge> otherEdges, List<EventEdge> edgesBetweenTheseTwoVertexs, EntityNode source,
                             EntityNode sink,Set<EntityNode>vertexHaveBeenSplited){
        List<EntityNode> newVertexs = generateVertex(edgesBetweenTheseTwoVertexs);
        assert newVertexs.size()==edgesBetweenTheseTwoVertexs.size();
        //Set<EventEdge> incomingEdgeOfSpliedVertex = inputGraph.incomingEdgesOf(source);
        //split
        for(int i=0;i<newVertexs.size();i++){
            //EventEdge newEdges = new EventEdge(edgesBetweenTheseTwoVertexs.get(i), ++curMaxEdgeID);
            EventEdge newEdges = new EventEdge(edgesBetweenTheseTwoVertexs.get(i), newVertexs.get(i), sink, ++curMaxEdgeID);
            inputGraph.addVertex(newVertexs.get(i));
            inputGraph.addEdge(newVertexs.get(i),sink, newEdges);
        }
        // add the other outgoing edge to the split vertex
        for (int i=0;i<newVertexs.size();i++){
            for(EventEdge e: otherEdges){
                //EventEdge newEdge = new EventEdge(e, ++curMaxEdgeID);
                EventEdge newEdge = new EventEdge(e, newVertexs.get(i), e.getSink(),++curMaxEdgeID);
                inputGraph.addEdge(newVertexs.get(i), inputGraph.getEdgeTarget(e),newEdge);
            }
        }

        //add the incoming edge of splited vertex

        Set<EventEdge> incomingEdges = inputGraph.incomingEdgesOf(source);
        for(int i=0; i < newVertexs.size();i++){
            for(EventEdge e: incomingEdges){
                //EventEdge newEdge = new EventEdge(e, ++curMaxEdgeID);
                EventEdge newEdge = new EventEdge(e, e.getSource(), newVertexs.get(i), ++curMaxEdgeID);
                inputGraph.addEdge(inputGraph.getEdgeSource(e),newVertexs.get(i),newEdge);
            }
        }
        removeEdgeOfSplitedVertex(incomingEdges);
        removeEdgeOfSplitedVertex(inputGraph.outgoingEdgesOf(source));
        inputGraph.removeVertex(source);
        vertexHaveBeenSplited.add(source);
    }

    private void removeEdgeOfSplitedVertex(Set<EventEdge> set){
        List<EventEdge> list = new ArrayList<>(set);
        for(int i=0;i<list.size();i++){
            inputGraph.removeEdge(list.get(i));
        }

    }

    private List<EntityNode> generateVertex(List<EventEdge> edges){
        List<EntityNode> list = new ArrayList<>();
        for(EventEdge e:edges){
            EntityNode v = new EntityNode(e.getSource(),++curMaxVertexID);
            list.add(v);
        }
        return list;
    }

    private List<EventEdge> getOtherEdges(DirectedPseudograph<EntityNode,EventEdge> graph,EntityNode source,
                                          EntityNode sink){
        Set<EventEdge> outEdges = graph.outgoingEdgesOf(source);
        List<EventEdge> list = new ArrayList<>();
        for(EventEdge e: outEdges){
            if(!e.getSink().equals(sink)){
                list.add(e);
            }
        }
        return list;
    }


    private Set<EntityNode> getVertexSet(List<VertexPair> list){
        Set<EntityNode> set = new HashSet<>();
        for(VertexPair p:list){
            set.add(p.sink);
            set.add(p.source);
        }
        return set;
    }

    private List<VertexPair> getVertexPairNeedToBeSplited(DirectedPseudograph<EntityNode, EventEdge> graph){
        Set<EntityNode> vertexSet = graph.vertexSet();
        List<VertexPair> list = new LinkedList<>();
        for(EntityNode s:vertexSet){
            Set<EventEdge> incomingEdges = graph.incomingEdgesOf(s);
            Map<EntityNode, Integer> count = countEdgesBetweenVertexs(incomingEdges);        // Kes is the source Vertex
            for(EntityNode v:count.keySet()){
                if(count.get(v)>1){
                    List<EventEdge> edges = getEdgesBetweenTheseTwoVertexs(incomingEdges,v);
                    VertexPair vPair = new VertexPair(v,s,edges);
                    list.add(vPair);

                }
            }
        }
        return list;
    }

    private Map<EntityNode, Integer> countEdgesBetweenVertexs(Set<EventEdge> iEdge){
        Map<EntityNode, Integer>res = new HashMap<>();
        for(EventEdge i: iEdge){
            EntityNode source = i.getSource();
            res.put(source, res.getOrDefault(source,0)+1);
        }
        return res;
    }

    private List<EventEdge> getEdgesBetweenTheseTwoVertexs(Set<EventEdge> iEdges, EntityNode source){
        List<EventEdge> list = new ArrayList<>();
        for(EventEdge i:iEdges){
            if(i.getSource().equals(source)){
                list.add(i);
            }
        }
        return list;
    }
    private long getMaxEdgeID(DirectedPseudograph<EntityNode, EventEdge> afterCPR){
        if(afterCPR == null){
            throw new IllegalArgumentException("input parameter is null");
        }
        Set<EventEdge> edgeSet = afterCPR.edgeSet();
        long maxID = Long.MIN_VALUE;
        for(EventEdge e : edgeSet){
            maxID = Math.max(maxID, e.getID());
        }
        return maxID;
    }

    private long getMaxVertexID(DirectedPseudograph<EntityNode, EventEdge> afterCPR){
        Set<EntityNode> vertexSet = afterCPR.vertexSet();
        long maxId = Long.MIN_VALUE;
        for(EntityNode e: vertexSet) {
            maxId = Math.max(maxId, e.getID());
        }
        return maxId;

    }
    public void getOutPutGraph(String i){
        IDissue(inputGraph);
        checkSignature();
        try {
            exporter.exportGraph(inputGraph, new FileWriter(String.format("%s.dot", i)));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private boolean IDissue(DirectedPseudograph<EntityNode, EventEdge> graph){
        Set<Long> vertexID = new HashSet<>();
        Set<Long> edgeID = new HashSet<>();
        Set<EntityNode> vertexs = graph.vertexSet();
        Set<EventEdge> edges = graph.edgeSet();
        for(EntityNode v: vertexs){
            if(!vertexID.add(v.getID())){
                System.out.println(v.getSignature()+v.getID());
                System.out.println("Duplicate vertex ID");
                break;
            }
        }

        for(EventEdge e: edges){
            if(!edgeID.add(e.getID())){
                System.out.println("Source: "+e.getSource().getID()+" Target: "+e.getSink().getID());
                System.out.println("Duplicate edge id: "+ e.id);
                break;
            }
        }
        return true;
    }

    public void outputGraph(String file){
        iter.exportGraph(file);
    }

    private void checkSignature(){
        Set<EntityNode> vertex = inputGraph.vertexSet();
        Set<String> newSignature = new HashSet<>();
        for(EntityNode v: vertex){
            newSignature.add(v.getSignature());
        }
        for(String s : originalSignature){
            if(!newSignature.contains(s)){
                System.out.println("lose vertex");
            }
        }
    }
    // get the largest time of outgoing edge of one vertex, if income edges occur later than this time, it is not necessay
    @Deprecated
    public void rebuildTimeLogical(){
        Set<EntityNode> vertexs = inputGraph.vertexSet();

        for(EntityNode v: vertexs){
            BigDecimal largestTimeOfOutEdges = getLargestTimeOfOutEdges(v);
            Set<EventEdge> incomEdgesOfV = inputGraph.incomingEdgesOf(v);
            Set<EventEdge> edgeNeedRemoved = new HashSet<>();
            for(EventEdge iEdge : incomEdgesOfV){
                if(!largestTimeOfOutEdges.equals(BigDecimal.ZERO) && iEdge.getStartTime().compareTo(largestTimeOfOutEdges)>0){
                    edgeNeedRemoved.add(iEdge);
                }
            }

            for(EventEdge edge: edgeNeedRemoved){
                inputGraph.removeEdge(edge);
            }
        }

        removeNecessaryVertex();
    }
    
    public void rebuildTimeLogical(Set<EntityNode> splited){
        Set<EntityNode> vertexs = inputGraph.vertexSet();
        for(EntityNode v : vertexs){
            for(EntityNode v2:splited){
                if(v.getSignature().equals(v2.getSignature())){
                    BigDecimal largestTimeOfOutEdges = getLargestTimeOfOutEdges(v);
                    Set<EventEdge> incomEdgesOfV = inputGraph.incomingEdgesOf(v);
                    Set<EventEdge> edgeNeedRemoved = new HashSet<>();
                    for(EventEdge iEdge : incomEdgesOfV){
                        if(!largestTimeOfOutEdges.equals(BigDecimal.ZERO) && iEdge.getStartTime().compareTo(largestTimeOfOutEdges)>0){
                            edgeNeedRemoved.add(iEdge);
                        }
                    }

                    for(EventEdge edge: edgeNeedRemoved){
                        inputGraph.removeEdge(edge);
                    }
                }
            }
        }
        removeNecessaryVertex();
    }


    private BigDecimal getLargestTimeOfOutEdges(EntityNode v){
        Set<EventEdge> outgoingEdges = inputGraph.outgoingEdgesOf(v);
        if(outgoingEdges.size() == 0){
            return BigDecimal.ZERO;
        }

        BigDecimal res = BigDecimal.ZERO;
        for(EventEdge e:outgoingEdges){
            if(e.getEndTime().compareTo(res)>0){
                res = e.getEndTime();
            }
        }
        return res;
    }

    private void removeNecessaryVertex(){
        Set<EntityNode> vertex = inputGraph.vertexSet();
        Set<EntityNode> vertexNeedRemoved = new HashSet<>();
        for(EntityNode v: vertex){
            Set<EventEdge> outEdges = inputGraph.outgoingEdgesOf(v);
            Set<EventEdge> inEdges = inputGraph.incomingEdgesOf(v);
            if(outEdges.size() == 0 && inEdges.size() == 0){
                vertexNeedRemoved.add(v);
            }
        }

        for(EntityNode v: vertexNeedRemoved){
            inputGraph.removeVertex(v);
        }
    }

    public static void main(String[] args){
        String[] localIP={"129.22.21.193"};
        ProcessGraph test = new ProcessGraph("DataForSplit.txt", localIP);
        test.backTrack("/home/fang/thesis2/code_about_data/test_output.txt", "File");
        GraphSplit test2 = new GraphSplit(test.backTrack);

//        iterGraph.bfsWithHopCount();
        test2.splitGraph();
        IterateGraph iterGraph = new IterateGraph(test2.inputGraph);
//        try{
//            iterGraph.bfsWithHopCount("/home/fang/thesis2/code_about_data/test_output.txt", "File",3);
//        }catch (Exception e){
//            System.out.println(e.getStackTrace());
//        }
    }
}
