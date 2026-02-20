package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.math.BigDecimal;
import java.util.*;

/**
 * Created by fang on 3/23/18.
 */
public class CausalityPreserve {
    DirectedPseudograph<EntityNode, EventEdge> input;
    IterateGraph graphIter;
    DirectedPseudograph<EntityNode, EventEdge> afterMerge;
    CausalityPreserve(DirectedPseudograph<EntityNode, EventEdge> input){
        this.input = (DirectedPseudograph<EntityNode, EventEdge>)input.clone();
    }

    /*Provide three different kind merge methods*/
    public DirectedPseudograph<EntityNode, EventEdge> CPR (int choose){
        System.out.println("CPR invoked: "+choose);
        if(choose < 1 || choose > 3){
            String usage = "The legal parameter:(int) 1,2,3\n"+
                    "Method 1: this merge method considers about time and event type\n" +
                    "Method 2: this merge method only considers event type\n"+
                    "Method 3: thie merge method doesn't consider about time and event type";
            throw new IllegalArgumentException(usage);
        }
        if(choose == 1){
            return mergeConsiderTimeAndType();
        }else if(choose == 2){
            return mergeConsiderType();
        }else{
            return mergeWithoutConsideringTimeAndType();
        }
    }
    /* Fang's comment: this merge method keep the causal relationship about different events, this mean it need to
       consider the time window and event type. For more detail, read the Professor Xiao's paper.
    */
    private DirectedPseudograph<EntityNode, EventEdge> mergeConsiderTimeAndType(){
        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(edgeSet);
        Collections.sort(edgeList,(a,b)->a.getStartTime().compareTo(b.getStartTime()));
        Iterator iter = edgeList.iterator();
        //Map<EntityNode, Map<EntityNode, Deque<EventEdge>>> mapOfStack = new HashMap<>();
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> pairStacks = initializePairStack(edgeSet);
        while(iter.hasNext()){
            EventEdge cur = (EventEdge)iter.next();
            EntityNode source = cur.getSource();
            EntityNode target = cur.getSink();
            Stack<EventEdge> stack = pairStacks.get(cur.getEvent()).get(source).get(target);
            if(stack.isEmpty()){
                stack.push(cur);
            }else{
                EventEdge edgePrevious = stack.pop();
                if(backwardCheck(edgePrevious,cur,source)){
                    edgePrevious = merge(edgePrevious, cur);
                    stack.push(edgePrevious);
                }else{
                    stack.push(cur);
                }
            }
        }
        //DirectedPseudograph<EntityNode, EventEdge> res = getCPR(mapOfStack);
        afterMerge = input;
        return afterMerge;
    }

    // Fang's comment merge edge if the time difference is smaller than the input range, this range could be
    // 1s, 100 ms and so on. The unit for this input is "s"
    public DirectedPseudograph<EntityNode, EventEdge> mergeEdgeFallInTheRange(double range){

        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                if(a.getStartTime().compareTo(b.getStartTime()) == 0){
                    return a.getEndTime().compareTo(b.getEndTime());
                }
                return a.getStartTime().compareTo(b.getStartTime());
            }
        };
        BigDecimal timeDiff = new BigDecimal(range);
        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(edgeSet);
        Collections.sort(edgeList, cmp);
        Iterator<EventEdge> iter = edgeList.iterator();
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> pairStacks = initializePairStack(edgeSet);

        while(iter.hasNext()){
            EventEdge cur = (EventEdge)iter.next();
            EntityNode source = cur.getSource();
            EntityNode target = cur.getSink();
            Stack<EventEdge> stack = pairStacks.get(cur.getEvent()).get(source).get(target);
            if(stack.isEmpty()){
                stack.push(cur);
            }else{
                EventEdge edgePrevious = stack.pop();
                BigDecimal diff = cur.getStartTime().subtract(edgePrevious.endTime);
                if(diff.compareTo(timeDiff)<=0) {
                    edgePrevious = merge(edgePrevious, cur);
                    stack.push(edgePrevious);
                }else{
                    stack.push(edgePrevious);
                    stack.push(cur);
                }
            }
        }
        afterMerge = input;
        return afterMerge;
    }

    //build a new graph rather than deleting edges in the original one, better performance
    public DirectedPseudograph<EntityNode, EventEdge> mergeEdgeFallInTheRange2(double range) {
        DirectedPseudograph<EntityNode, EventEdge> merged = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        for (EntityNode n : input.vertexSet())
            merged.addVertex(n);

        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                if (a.getStartTime().compareTo(b.getStartTime()) == 0) {
                    return a.getEndTime().compareTo(b.getEndTime());
                }
                return a.getStartTime().compareTo(b.getStartTime());
            }
        };

        BigDecimal timeDiff = new BigDecimal(range);
        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(edgeSet);
        Collections.sort(edgeList, cmp);
        Iterator<EventEdge> iter = edgeList.iterator();
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> pairStacks = initializePairStack(edgeSet);

        while (iter.hasNext()) {
            EventEdge cur = iter.next();
            EntityNode source = cur.getSource();
            EntityNode target = cur.getSink();
            Stack<EventEdge> stack = pairStacks.get(cur.getEvent()).get(source).get(target);
            if (stack.isEmpty()) {
                stack.push(cur);
            } else {
                EventEdge edgePrevious = stack.pop();
                BigDecimal diff = cur.getStartTime().subtract(edgePrevious.endTime);
                if (diff.compareTo(timeDiff) <= 0) {
                    edgePrevious = edgePrevious.merge(cur);
                    stack.push(edgePrevious);
                } else {
                    stack.push(edgePrevious);
                    stack.push(cur);
                }
            }
        }

        for (String event : pairStacks.keySet()) {
            for (EntityNode source : pairStacks.get(event).keySet()) {
                for (EntityNode sink : pairStacks.get(event).get(source).keySet()) {
                    Stack<EventEdge> s = pairStacks.get(event).get(source).get(sink);
                    for (EventEdge e : s)
                        merged.addEdge(source, sink, e);
                }
            }
        }



        afterMerge = merged;
        return afterMerge;
    }

    // Fang's comment: merge all the edges, don't care about time window and event type
    private DirectedPseudograph<EntityNode, EventEdge> mergeWithoutConsideringTimeAndType(){
        Map<EntityNode, Map<EntityNode, EventEdge>> map = new HashMap<>();
        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge>  edgeList = new ArrayList<>(edgeSet);
        Collections.sort(edgeList, (a, b)->a.getEndTime().compareTo(b.getEndTime()));

        for(EventEdge e : edgeList){
            EntityNode source = e.getSource();
            EntityNode target = e.getSink();
            e.setEdgeEvent("NullAfterMerge");
            if(!map.containsKey((source))){
                map.put(source, new HashMap<EntityNode, EventEdge>());
            }
            if(map.get(source).containsKey(target)){
                EventEdge previous = map.get(source).get(target);
                previous = merge(previous, e);
                map.get(source).put(target, previous);
            }else{
                map.get(source).put(target, e);
            }
        }
        afterMerge = input;
        return afterMerge;

    }
    /* Fang's comment: for the edges between the pair of vertex, this method merges the edges having the same event type
        Don't care about time window.
     */
    private DirectedPseudograph<EntityNode, EventEdge> mergeConsiderType(){

        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(edgeSet);
        Collections.sort(edgeList,(a,b)->a.getStartTime().compareTo(b.getStartTime()));
        Iterator iter = edgeList.iterator();
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> pairStacks = initializePairStack(edgeSet);

        while(iter.hasNext()){
            EventEdge cur = (EventEdge)iter.next();
            EntityNode source = cur.getSource();
            EntityNode target = cur.getSink();
            Stack<EventEdge> stack = pairStacks.get(cur.getEvent()).get(source).get(target);
            if(stack.isEmpty()){
                stack.push(cur);
            }else{
                EventEdge edgePrevious = stack.pop();
                edgePrevious = merge(edgePrevious, cur);
                stack.push(edgePrevious);
            }
        }
        afterMerge = input;
        return afterMerge;
    }

    private EventEdge merge(EventEdge previous, EventEdge cur){
        previous = previous.merge(cur);
        input.removeEdge(cur);
        return previous;
    }

    private Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> initializePairStack(Set<EventEdge> edges){
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> map = new HashMap<>();

        for (EventEdge e: edges) {
            String event = e.getEvent();
            map.putIfAbsent(event, new HashMap<EntityNode, Map<EntityNode, Stack<EventEdge>>>());
            Map<EntityNode, Map<EntityNode, Stack<EventEdge>>> eventMap = map.get(event);
            EntityNode source = e.getSource();
            EntityNode target = e.getSink();
            eventMap.putIfAbsent(source, new HashMap<EntityNode, Stack<EventEdge>>());
            eventMap.get(source).putIfAbsent(target, new Stack<EventEdge>());
        }
        return map;
    }


    private DirectedPseudograph<EntityNode, EventEdge> getCPR(Map<EntityNode, Map<EntityNode, Deque<EventEdge>>> mapOfStacks){
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        for(EntityNode u:mapOfStacks.keySet()){
            Map<EntityNode, Deque<EventEdge>> cur = mapOfStacks.get(u);
            for(EntityNode v:cur.keySet()){
                res.addVertex(u);
                res.addVertex(v);
                while(!cur.get(v).isEmpty()){
                    EventEdge edge = cur.get(v).pop();
                    res.addEdge(u,v,edge);
                }
            }
        }
        return res;
    }

    private boolean backwardCheck(EventEdge p,EventEdge l, EntityNode u){
        Set<EventEdge> incoming = input.incomingEdgesOf(u);
        BigDecimal[] endTimes = {p.getEndTime(),l.getEndTime()};
        if(p.getEndTime() == null){
            System.out.println(p.getID());
            System.out.println(p.getEvent());
        }
        if(l.getEndTime() == null){
            System.out.println(l.getID());
            System.out.println(l.getEvent());
        }
        Arrays.sort(endTimes);
        for(EventEdge edge: incoming){
            BigDecimal[] timeWindow = edge.getInterval();
            if(isOverlap(timeWindow,endTimes)){
                return false;
            }
        }
        return true;
    }

    private boolean forwardCheck(EventEdge p, EventEdge l, EntityNode u, BigDecimal curTime){
        BigDecimal[] startTime = {p.getStartTime(), l.getStartTime()};
        Set<EventEdge> outgoing = input.outgoingEdgesOf(u);
        List<EventEdge> outgongCandidates = new ArrayList<>();

        for(EventEdge e:outgoing){
            if(e.getStartTime().compareTo(curTime)<0){
                outgongCandidates.add(e);
            }
        }

        Arrays.sort(startTime);
        for(EventEdge edge: outgongCandidates){
            BigDecimal[] timeWindow = edge.getInterval();
            if(isOverlap(timeWindow,startTime)){
                return false;
            }
        }
        return true;
    }

    private boolean isOverlap(BigDecimal[]a, BigDecimal[]b){
//        if(a[1].compareTo(b[0])>=0 && a[1].compareTo(b[1])<=0 || a[0].compareTo(b[0])>=0 && a[0].compareTo(b[1])<=0){
//            return true;
//        }
//        return false;
        if(a[1].compareTo(b[0])<0 || a[0].compareTo(b[1])>0){
            return false;
        }
        return true;
    }
}
