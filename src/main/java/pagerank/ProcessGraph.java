package pagerank; /**
 * Created by fang on 8/11/17.
 */
import org.jgrapht.ext.DOTExporter;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
public class ProcessGraph {
    private GetGraph getGraph;
    DirectedPseudograph<EntityNode,EventEdge> jGraph;
    public DirectedPseudograph<EntityNode, EventEdge> backTrack;
    DirectedPseudograph<EntityNode, EventEdge> afterCPR;
    DirectedPseudograph<EntityNode, EventEdge> graphForPR;
    DOTExporter<EntityNode,EventEdge> exporter;
    String input = null;
    String type = null;
    public BigDecimal POItime;
    EntityNode POIEvent;
    public ProcessGraph(String filePath,String[] localIP){
        getGraph = new GetGraph(filePath,localIP);
        getGraph.GenerateGraph();
        jGraph = getGraph.getJg();
        exporter = getGraph.getExporter();
        POItime = null;
    }

    private Map<Long, EntityNode> getNodeMap(DirectedPseudograph<EntityNode, EventEdge> graph){
        Map<Long, EntityNode> map = new HashMap<>();
        Set<EntityNode> vertexs = graph.vertexSet();
        for(EntityNode v : vertexs){
            map.put(v.getID(),v);
        }
        return map;
    }

    public void backTrack(String input, String type){
        backTrack = getGraph.backTrackPoi(input,type);
        this.input = input;
        this.type = type;
    }

    public void backTrack(String input){
        backTrack = getGraph.backTrackPoi(input);
        this.input = input;
    }


    public void backTrackWithHopCount(String input, String type, int count){
        getGraph.backTrackWithHopCount(input, type, count);
    }

    public void outputBackTrack(String input, String type) throws IOException{
        exporter.exportGraph(backTrack,new FileWriter(String.format("%s_backTrack.dot",input)));
    }

    public void outputGraph(String name, DirectedPseudograph<EntityNode, EventEdge> graph) throws IOException{
        exporter.exportGraph(graph,new FileWriter(String.format("%s_.dot", name)));
    }

    public void CPR() {
        //System.out.println("Im in CPR");
        DirectedPseudograph<EntityNode, EventEdge> copyOfBackTrack =backTrack;
        Set<EventEdge> set = copyOfBackTrack.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(set);
        edgeList =sortAccordingStartTime(edgeList);
        System.out.println("Finish sort List");
        Iterator iter = edgeList.iterator();
        Map<EntityNode,Map<EntityNode,Deque<EventEdge>>> mapOfStack = new HashMap<>();
        while(iter.hasNext()){
            EventEdge cur = (EventEdge)iter.next();
            EntityNode u = cur.getSource();
            EntityNode v = cur.getSink();
            if(mapOfStack.containsKey(u)){
                Map<EntityNode,Deque<EventEdge>> values = mapOfStack.get(u);
                if(values.containsKey(v)){
                    Deque<EventEdge> edgeStack = values.get(v);
                    if(edgeStack.isEmpty()){
                        System.out.println("Here is not correct");
                        break;
                    }
                    EventEdge earlyEdge = edgeStack.pop();

                    if(forwardCheck(earlyEdge,cur,v) && backwardCheck(earlyEdge,cur,u)){
                        earlyEdge.merge(cur);
                        edgeStack.push(earlyEdge);
                    }else{
                        edgeStack.push(cur);
                    }

                }else{
                    Deque<EventEdge> stack = new ArrayDeque<>();
                    stack.push(cur);
                    values.put(v,stack);
                }
            }else{
                Deque<EventEdge> stack = new ArrayDeque<>();
                stack.push(cur);
                Map<EntityNode,Deque<EventEdge>> value = new HashMap<>();
                value.put(v,stack);
                mapOfStack.put(u,value);
            }

        }
//        System.out.println("finish merge");
        getCPR(mapOfStack);
    }

    private void getCPR(Map<EntityNode, Map<EntityNode, Deque<EventEdge>>> mapOfStacks){
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        for(EntityNode u: mapOfStacks.keySet()){
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
        afterCPR = res;
//        try {
//            exporter.exportGraph(res, new FileWriter("BackTrack+CPR.dot"));
//        }catch(IOException e){
//            System.out.println("CPR IO Exception");
//        }
    }

    public void ouptGraphAfterCPR() throws IOException{
        if(afterCPR == null){
            System.out.println("You need Run CPR firstly");
        }

        exporter.exportGraph(afterCPR, new FileWriter(String.format("%s_afterCPR.dot", input)));
    }

    private List<EventEdge> sortAccordingStartTime(List<EventEdge> list){
        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                return a.getStartTime().compareTo(b.getStartTime());
            }
        };
        list.sort(cmp);
        return list;
    }

    private boolean backwardCheck(EventEdge p,EventEdge l, EntityNode u){
        Set<EventEdge> incoming = backTrack.incomingEdgesOf(u);
        BigDecimal[] endTimes = {p.getEndTime(),l.getEndTime()};
        Arrays.sort(endTimes);
        for(EventEdge edge:incoming){
            BigDecimal[] timeWindow = edge.getInterval();
            if(isOverlap(timeWindow,endTimes)){
                return false;
            }
        }
        return true;
    }

    private boolean forwardCheck(EventEdge p, EventEdge l, EntityNode u){
        BigDecimal[] startTime = {p.getStartTime(), l.getStartTime()};
        Set<EventEdge> outgoing = backTrack.outgoingEdgesOf(u);
        Arrays.sort(startTime);
        for(EventEdge edge:outgoing){
            BigDecimal[] timeWindow = edge.getInterval();
            if(isOverlap(timeWindow,startTime)){
                return false;
            }
        }
        return true;
    }

    private boolean isOverlap(BigDecimal[]a, BigDecimal[]b){
        if(a[1].compareTo(b[0])>=0 && a[1].compareTo(b[1])<=0 || a[0].compareTo(b[0])>=0 && a[0].compareTo(b[1])<=0){
            return true;
        }
        return false;
    }

    private void EdgeSourcetest(){
        Set<EventEdge> set = backTrack.edgeSet();
        Set<EntityNode> nodes = new HashSet<>();
        for(EventEdge edge:set){
            nodes.add(edge.getSink());
            nodes.add(edge.getSource());
        }
    }

    public int getNumOfEdgesInOriginalGraph(){
        return jGraph.edgeSet().size();
    }

    public int getNumOfEdges(){
        if(afterCPR == null){
            System.out.println("You need Run getCPR firstly.");
            return -1;
        }else{
            return afterCPR.edgeSet().size();
        }
    }

    public void filterByFile(String[] str){
        DirectedPseudograph<EntityNode, EventEdge> graphAfterFilter =
                new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        //Map<EntityNode, Map<EntityNode, List<>>> map = new HashMap<>(); //NEED LIST OF EDGE
        Map<EntityNode, Map<EntityNode,List<EventEdge>>> map = new HashMap<>();
        for(EventEdge e: afterCPR.edgeSet()){
            EntityNode source = e.getSource();
            EntityNode sink = e.getSink();
            boolean flag1=false;
            boolean flag2=false;
            for(String s:str){
                if(source.getSignature().startsWith(s)){
                    flag1 = true;
                }
                if(sink.getSignature().startsWith(s)){
                    flag2 = true;
                }
            }
            if(flag1  || flag2) continue;
            if(!map.containsKey(source)){
                Map<EntityNode,List<EventEdge>> sub = new HashMap<>();
                map.put(source,sub);
            }
            Map<EntityNode, List<EventEdge>> sub = map.get(source);
            if(!sub.containsKey(sink)){
                List<EventEdge> edgeList = new ArrayList<>();
                sub.put(sink,edgeList);
            }
            sub.get(sink).add(e);
        }
        for(EntityNode source: map.keySet()){
            Map<EntityNode,List<EventEdge>> sub = map.get(source);
            for(EntityNode sink:sub.keySet()){
                graphAfterFilter.addVertex(source);
                graphAfterFilter.addVertex(sink);
                List<EventEdge> edges = sub.get(sink);
                for(EventEdge e:edges){
                    graphAfterFilter.addEdge(source,sink,e);
                }
            }
        }
        try {
            outputGraph("filterResult", graphAfterFilter);
        }catch (IOException e){
            System.out.println("The filter output is not normal");
        }
    }

    public void splitNodeOfGraph(){
        if(afterCPR == null){
            System.out.println("Run CPR first");
            return;
        }
        if(input == null) {
            System.out.println("Lack the parameter to find start for split");
            return;
        }
//        if(type == null){
//            System.out.println("Last the parameter to find start for split");
//        }
        System.out.println("Before split: "+ afterCPR.vertexSet().size());
        EntityNode start = getNode(afterCPR);
        Map<EntityNode,Map<EntityNode,TreeMap<BigDecimal, EventEdge>>> dictgraph = getDictGraph(afterCPR);
        long curMaxEdgeID = getMaxEdgeID(afterCPR);
        long curMaxVertexID = getMaxVertexID(afterCPR);
        DirectedPseudograph<EntityNode, EventEdge> aftersplit = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        Queue<EntityNode> queue = new LinkedList<>();
        queue.offer(start);
        Map<EntityNode,TreeMap<BigDecimal, EntityNode>> origianlToSplit = new HashMap<>();
        while(!queue.isEmpty()){
            int size = queue.size();
            for(;size>0;size--){
                EntityNode cur = queue.poll();
                Set<EventEdge> incoming = afterCPR.incomingEdgesOf(cur);
            }
        }


    }

    public Map<EntityNode, Map<EntityNode, TreeMap<BigDecimal,EventEdge>>> getDictGraph(DirectedPseudograph<EntityNode, EventEdge> graph){
        Set<EntityNode> vertexSet = graph.vertexSet();
        Map<EntityNode,Map<EntityNode, TreeMap<BigDecimal, EventEdge>>> dict = new HashMap<>();
        for(EntityNode v: vertexSet){
            Map<EntityNode, TreeMap<BigDecimal, EventEdge>> map = new HashMap<>();
            dict.put(v, map);
        }

        for(EntityNode v: vertexSet){
            Set<EventEdge> inEdges = graph.incomingEdgesOf(v);
            for(EventEdge e:inEdges){
                EntityNode source = e.getSource();
                Map<EntityNode, TreeMap<BigDecimal, EventEdge>> sortedEdges = dict.get(v);
                if(!sortedEdges.containsKey(source)){
                    sortedEdges.put(source, new TreeMap<BigDecimal, EventEdge>());
                }
                sortedEdges.get(source).put(e.getEndTime(),e);
            }
        }
        return dict;
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



    private Set<EntityNode> getSourceNodes(Set<EventEdge> incomingEdgesSet){
        Set<EntityNode> sourceNodes = new HashSet<>();
        for(EventEdge e:incomingEdgesSet){
            sourceNodes.add(e.getSource());
        }
        return sourceNodes;
    }

    private Set<EventEdge>  getEdgesBetweenTwoVertex(EntityNode cur, EntityNode s, DirectedPseudograph<EntityNode,EventEdge>afterCPR){
        Set<EventEdge> outEdges = afterCPR.outgoingEdgesOf(s);
        Set<EventEdge> res = new HashSet<>();
        for(EventEdge e : outEdges){
            if(e.getSink().equals(cur)){
                res.add(e);
            }
        }
        return res;
    }

    private boolean needSplit(EntityNode v){
        if(afterCPR == null || v == null){
            throw new IllegalArgumentException("empty parameter of needSplit");
        }

        //Set<EventEdge> incomingEdges = afterCPR.incomingEdgesOf(v);
        Set<EventEdge> outgoingEdges = afterCPR.outgoingEdgesOf(v);
        if(outgoingEdges == null || outgoingEdges.size() == 0){
            return false;
        }
        Map<EntityNode, Integer> counts = new HashMap<>();
        for(EventEdge e: outgoingEdges){
            counts.put(e.getSink(), counts.getOrDefault(e.getSink(),0)+1);
            if(counts.get(e.getSink()) > 1){
                return true;
            }
        }
        return false;
    }


    private EntityNode getNode(DirectedPseudograph<EntityNode, EventEdge> afterCPR){
        Set<EntityNode> vertexSet = afterCPR.vertexSet();
        EntityNode start = null;
        for(EntityNode node : vertexSet){
            if(node.getSignature().equals(input)){
                start = node;
                break;
            }
        }
        if(start != null){
            return start;
        }

        System.out.println("Can't find the node in the graph after CPR");
        return null;
    }

    public EntityNode getPOIEvent(){
        if(afterCPR == null){
            System.out.println("Please run CPR firstly");
        }
        EntityNode node = getNode(afterCPR);
        POIEvent = node;
        return node;
    }

    public void updatePOItime(){
        if(afterCPR == null){
            System.out.println("Please run CPR firstly");
        }
        if(POIEvent == null){
            getPOIEvent();
        }
        Set<EventEdge> poiIncoming = afterCPR.incomingEdgesOf(POIEvent);
        BigDecimal t = BigDecimal.ZERO;
        for(EventEdge e:poiIncoming){
            if(e.getEndTime().compareTo(t)>0){                   //get the latest operation of poi event
                t = e.getEndTime();
            }
        }
        POItime = t;
    }

    private long getMaxEdgeID(){
        if(afterCPR == null){
            throw new IllegalArgumentException("afterCPR is null");
        }
        Set<EventEdge> edgeSet = afterCPR.edgeSet();
        long max = Long.MIN_VALUE;
        for(EventEdge e: edgeSet){
            max = Math.max(max, e.getID());
        }
        return max;
    }

    public void testSplit(){
        if(backTrack == null){
            System.out.println(" To test split please Run BarckTrack first");
            return;
        }
        if(input == null) {
            System.out.println("Lack the parameter to find start for split");
            return;
        }
//        if(type == null){
//            System.out.println("Last the parameter to find start for split");
//        }
        Map<Long, EntityNode> nodeMap = getNodeMap(backTrack);
        for (long l : nodeMap.keySet()){
            System.out.println(l);
        }
        System.out.println("Before split: "+ backTrack.vertexSet().size());
        EntityNode start = getNode(backTrack);
        Set<EntityNode> setOfVertex = new HashSet<>(backTrack.vertexSet());
        HashMap<Long, EntityNode> enmap = new HashMap<>();
        long maxVertexID = getMaxVertexID(backTrack);
        long maxEdgeID = getMaxEdgeID(backTrack);
        //DirectedPseudograph<EntityNode, EventEdge> afterSplit = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);


        for(EntityNode v : setOfVertex){
            enmap.put(v.getID(), v);
            Set<EventEdge> outgoingEdges = backTrack.outgoingEdgesOf(v);
            Set<EventEdge> incomingEdges = backTrack.incomingEdgesOf(v);
            if(needSplitForTest(v)){
                for(EventEdge outEdge : outgoingEdges){
                    EntityNode splitedVertex = new EntityNode(v, maxVertexID++);                           // split vertex
                    nodeMap.put(splitedVertex.getID(), splitedVertex);
                    backTrack.addVertex(splitedVertex);
                    EventEdge newOutedge = new EventEdge(outEdge,++maxEdgeID);                   //need update Edge id

                    Set<EntityNode>nodeSets = backTrack.vertexSet();
                    if(!nodeSets.contains(splitedVertex)){
                        System.out.println("nodeSets doesn't contain splitedVertex");
                    }

                    if(!nodeSets.contains((outEdge.getSink()))){
                        System.out.println();
                        System.out.println(outEdge.getSink().getID());
                        System.out.println(newOutedge.getSink().getSignature());
                        System.out.println("nodeSet doesn't contain edge sink");
                    }
                    backTrack.addEdge(splitedVertex, nodeMap.get(outEdge.getSink().getID()),newOutedge);

//                    if(!backTrack.addEdge(splitedVertex,newOutedge.getSink(),newOutedge)){      //add edge
//                        System.out.println("split fail");
//                        break;
//                    }
                    for(EventEdge inEdge : incomingEdges){
                        if(inEdge.getStartTime().compareTo(newOutedge.getEndTime())>=1){
                            EventEdge newInEdge = new EventEdge(inEdge,++maxEdgeID);        //need update Edge id
                            backTrack.addEdge(inEdge.getSource(),splitedVertex,newInEdge);
                        }
                    }
                }
                backTrack.removeVertex(v);

            }


        }
        System.out.println("After split: "+ backTrack.vertexSet().size());
    }
    private boolean needSplitForTest(EntityNode v){
        if(backTrack == null || v == null){
            throw new IllegalArgumentException("empty parameter of needSplit");
        }

        //Set<EventEdge> incomingEdges = afterCPR.incomingEdgesOf(v);
        Set<EventEdge> outgoingEdges = backTrack.outgoingEdgesOf(v);
        if(outgoingEdges == null || outgoingEdges.size() == 0){
            return false;
        }
        Map<EntityNode, Integer> counts = new HashMap<>();
        for(EventEdge e: outgoingEdges){
            counts.put(e.getSink(), counts.getOrDefault(e.getSink(),0)+1);
            if(counts.get(e.getSink()) > 1){
                return true;
            }
        }
        return false;
    }

    public void inferReputation(){
        HashMap<Long, HashMap<Long, Double>> weights = new HashMap<>();
        Set<EntityNode> vertexSet = afterCPR.vertexSet();
        if(POItime == null) updatePOItime();

        for(EntityNode v: vertexSet) {
            HashMap<Long, Double> map = new HashMap<>();
            weights.put(v.getID(),map);
            for (EntityNode v2 : vertexSet) {
                weights.get(v.getID()).put(v2.getID(),0.0);
            }
        }

        for(EntityNode v : vertexSet){
            Set<EventEdge> edges = afterCPR.incomingEdgesOf(v);
            double[] timeweights = new double[edges.size()];
            long[] amountweights = new long[edges.size()];
            double timetotal = 0.0;
            long amounttotal = 0;
            for(EventEdge edge : edges){
                timetotal += timeWeight(edge);
                amounttotal += amountWeight(edge);
            }
            double wtotal = 0.0;
            for(EventEdge edge: edges){
                EntityNode from = edge.getSource();
                wtotal += (timeWeight(edge)/timetotal)*(amountWeight(edge)/amounttotal);
            }

            for(EventEdge edge: edges){
                EntityNode from = edge.getSource();
                double w = (timeWeight(edge)/timetotal)*(amountWeight(edge)/amounttotal)/wtotal;
                weights.get(v.getID()).put(from.getID(), w);
            }

        }

        for(Long a : weights.keySet()){
            Map<Long, Double> map = weights.get(a);
            for(Long b: map.keySet()){
                if(map.get(b)!=0.0){
                    System.out.println(b);
                }
            }
        }

    }

    private double timeWeight(EventEdge edge){
        if(edge.getEndTime().equals(POItime)){
            return 1;
        }else{
            BigDecimal diff = POItime.subtract(edge.getEndTime());
            return 1/ diff.doubleValue();
        }
    }



    private long amountWeight(EventEdge edge){
        return edge.getSize();
    }

    public int getOrigianlGraphVertexNumber(){
        return jGraph.vertexSet().size();
    }

    public int getOrigianlGraphEdgeNumber(){
        return jGraph.edgeSet().size();
    }

    public static void main (String[] args){
        //String[] localIP={"10.59.13.209"};

        //String[] localIP = {"10.0.2.15"};
        //String[] localIP = {"10.128.0.3"};
        /**the parameter of server**/
//        String[] localIP = {"10.59.13.220"}; // ip of server
//        ProcessGraph test = new ProcessGraph("server.txt",localIP);
//        test.backTrack("/dev/pts/8","File");

        /** the parameter of node-js **/
        String[] localIP={"192.168.122.1"};
        ProcessGraph test = new ProcessGraph("DataForSplit.txt", localIP);
        test.backTrack("/home/fang/thesis2/code_about_data/test_output.txt","File");
        test.backTrackWithHopCount("/home/fang/thesis2/code_about_data/test_output.txt","File",2);
        test.getDictGraph(test.backTrack);
        IterateGraph iterateGraph = new IterateGraph(test.backTrack);
//        try {
//            iterateGraph.bfsWithHopCount("/home/fang/thesis2/code_about_data/test_output.txt","File",3);
////            test.outputBackTrack("OriginalSplit", "File");
//        }catch (IOException e){
//            e.printStackTrace();
//        }
//        try {
//            test.CPR();
//
//        }catch (IOException e){
//            System.out.println("CPR IO Exception");
//        }
//        test.inferReputation();
//        test.updatePOItime();
//        System.out.println(test.POItime);

//        System.out.println("Number Of Edges After CPR AND backTrack:--------------");
//        System.out.println(test.getNumOfEdges());
//        System.out.println("Number of Edges Of Original Graph");
//        System.out.println(test.getNumOfEdgesInOriginalGraph());
//        String[] pathsFilter ={"/lib/x86"};
//        test.filterByFile(pathsFilter);
//        test.testSplit();

    }



}
