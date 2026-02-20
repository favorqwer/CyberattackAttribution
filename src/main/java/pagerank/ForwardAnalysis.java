package pagerank;

import org.jgrapht.graph.DirectedPseudograph;
//import sun.awt.image.ImageWatched;

import java.math.BigDecimal;
import java.util.*;

public class ForwardAnalysis {
    DirectedPseudograph<EntityNode, EventEdge> input;

    ForwardAnalysis(DirectedPseudograph<EntityNode, EventEdge>graph){
        this.input = graph;
    }

    private EntityNode findSource(String signature){
        Set<EntityNode> vertexSet =input.vertexSet();
        for(EntityNode e: vertexSet){
            if(e.getSignature().equals(signature)){
                return e;
            }
        }
        return null;
    }

    public DirectedPseudograph<EntityNode, EventEdge> fowardTrack(String signature, int stepLimit){
        Queue<EntityNode> queue = new LinkedList<>();
        EntityNode start = findSource(signature);
        queue.offer(start);
        assert start!=null;
        Set<EntityNode> vertexInRes = new HashSet<>();
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        int step = 0;
        Queue<EntityNode> nextQueue = new LinkedList<>();
        while(!queue.isEmpty() && step <stepLimit){
            EntityNode cur = queue.poll();
            if (res.vertexSet().size() == 0){
                addNodeToRes(cur, res);
                Set<EventEdge> outgoing = input.outgoingEdgesOf(cur);
                for(EventEdge out: outgoing){
                    EntityNode target = out.getSink();
                    if (addNodeToRes(target, res)){
                        nextQueue.offer(target);
                    }
                    res.addEdge(cur, target, out);
                }
            }else{
                addNodeToRes(cur, res);
                BigDecimal earliestStart = getEarliestOPTime(cur, res);
                Set<EventEdge> outgoing = input.outgoingEdgesOf(cur);
                for(EventEdge out: outgoing){
                    if(out.startTime.compareTo(earliestStart)<0){	//FS: if follow re.endtime>min(collect(uin IN in(u) | uin.starttime)), should be out.endTime...
                        continue;
                    }
                    EntityNode target = out.getSink();
                    if(addNodeToRes(target, res)){
                        nextQueue.offer(target);
                    }
                    res.addEdge(cur, target, out);
                }
            }
            if(queue.size() == 0 && nextQueue.size()!=0){
                queue = nextQueue;
                nextQueue = new LinkedList<>();
                step++;
            }
        }
        return res;
    }

    public DirectedPseudograph<EntityNode, EventEdge> forwardLimitedByTime(String signature, BigDecimal POITime){
        Queue<EntityNode> queue = new LinkedList<>();
        EntityNode start = findSource(signature);
        queue.offer(start);
        assert start!=null;
        Set<EntityNode> vertexInRes = new HashSet<>();
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        Queue<EntityNode> nextQueue = new LinkedList<>();
        while(!queue.isEmpty()){
            while(!queue.isEmpty()) {
                EntityNode cur = queue.poll();
                if (res.vertexSet().size() == 0) {
                    addNodeToRes(cur, res);
                    Set<EventEdge> outgoing = input.outgoingEdgesOf(cur);
                    for (EventEdge out : outgoing) {
                        /*The start time of outgoing edge should be smaller than the backward POITime*/
                        if (out.getStartTime().compareTo(POITime) < 0) {
                            EntityNode target = out.getSink();
                            if (addNodeToRes(target, res)) {
                                nextQueue.offer(target);
                            }
                            res.addEdge(cur, target, out);
                        }
                    }
                } else {
                    addNodeToRes(cur, res);
                    BigDecimal earliestStart = getEarliestOPTime(cur, res);
                    Set<EventEdge> outgoing = input.outgoingEdgesOf(cur);
                    for (EventEdge out : outgoing) {
                        /*The start time of outgoing edge should be smaller than the backward POITime*/
                        if (out.getStartTime().compareTo(POITime) < 0) {
                            if (out.startTime.compareTo(earliestStart) < 0) {
                                continue;
                            }
                            EntityNode target = out.getSink();
                            if (addNodeToRes(target, res)) {
                                nextQueue.offer(target);
                            }
                            res.addEdge(cur, target, out);
                        }
                    }
                }
            }
            queue = nextQueue;
            nextQueue = new LinkedList<>();
        }
        return res;
    }

    public List<DirectedPseudograph<EntityNode, EventEdge>> multipleForwardLimitedByPOI(List<String> forwardStarts,
                                                                                        BigDecimal POITime){
        List<DirectedPseudograph<EntityNode, EventEdge>> graphs = new ArrayList<>();
        for(String start:forwardStarts){
            graphs.add(forwardLimitedByTime(start, POITime));
        }
        return graphs;
    }

    private boolean addNodeToRes(EntityNode node, DirectedPseudograph<EntityNode, EventEdge>res){
        return res.addVertex(node);
    }
    // Get earliest startTime of incoming edges
    private BigDecimal getEarliestOPTime(EntityNode node, DirectedPseudograph<EntityNode, EventEdge> res){
        Set<EventEdge> incoming = res.incomingEdgesOf(node);
        List<EventEdge> edgeList = new LinkedList<EventEdge>(incoming);
        assert edgeList.size()>0;
        edgeList.sort((a, b) -> a.startTime.compareTo(b.startTime));
        return edgeList.get(0).startTime;
    }

    public static void main(String[] args){
        String logfile = "C:\\Users\\fang2\\Desktop\\reptracker\\data\\attack_log\\cmd-inject.txt";
        GetGraph getGraph = new GetGraph(logfile, MetaConfig.localIP);
        getGraph.GenerateGraph();
        DirectedPseudograph<EntityNode, EventEdge> raw_graph = getGraph.getJg();
        ForwardAnalysis forwardTest = new ForwardAnalysis(raw_graph);
        String start = "172.31.77.48:46722->172.31.71.251:44444";
        int step = 10;
        DirectedPseudograph<EntityNode, EventEdge> forwardRes = forwardTest.fowardTrack(start, step);
        IterateGraph outputer = new IterateGraph(forwardRes);
        outputer.exportGraph("forwardTest");
        CausalityPreserve CPR = new CausalityPreserve(forwardRes);
        CPR.mergeEdgeFallInTheRange2(10.0);
        outputer = new IterateGraph(CPR.afterMerge);
        outputer.exportGraph("forward_aftermerge");

    }
}
