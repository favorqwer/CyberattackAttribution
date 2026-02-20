package pagerank;/*The function here include bfs and output methods*/


import net.bytebuddy.dynamic.loading.ClassInjector;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.ext.DOTExporter;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;

public class IterateGraph {
    DirectedPseudograph<EntityNode, EventEdge> inputgraph;
    DOTExporter<EntityNode, EventEdge> exporter;
    Map<String, EntityNode> indexOfNode;

    IterateGraph(DirectedPseudograph<EntityNode, EventEdge> graph){

        this.inputgraph = graph;
        exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),new EntityNameProvider(), new EventEdgeProvider(),new EntityAttributeProvider(),null);
        indexOfNode = new HashMap<>();
        for(EntityNode n : graph.vertexSet()){
            indexOfNode.put(n.getSignature(), n);
        }
    }

    public DirectedPseudograph<EntityNode, EventEdge> bfs(String input){
        EntityNode start = getGraphVertex(input);
        Queue<EntityNode> queue = new LinkedList<EntityNode>();
        if(start!=null){
            return bfs(start);
        }else{
            System.out.println("Your input doesn't exist in the graph");
        }
        return null;

    }

    private DirectedPseudograph<EntityNode, EventEdge> bfs(EntityNode start){
        Queue<EntityNode> queue = new LinkedList<>();
        DirectedPseudograph<EntityNode, EventEdge> newgraph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        queue.offer(start);
        Set<EntityNode> nodeInTheQueue = new HashSet<>();
        nodeInTheQueue.add(start);

        while(!queue.isEmpty()){
            EntityNode cur = queue.poll();
            newgraph.addVertex(cur);
            Set<EventEdge> inEdges = inputgraph.incomingEdgesOf(cur);
            for(EventEdge edge: inEdges){
                EntityNode source = edge.getSource();
                newgraph.addVertex(source);
                newgraph.addEdge(source,cur,edge);
                if(!nodeInTheQueue.contains(source)){
                    nodeInTheQueue.add(source);
                    queue.offer(source);
                }
            }
            Set<EventEdge> outEdges = inputgraph.outgoingEdgesOf(cur);
            for(EventEdge edge: outEdges){
                EntityNode target = edge.getSink();
                newgraph.addVertex(target);
                newgraph.addEdge(cur, target, edge);
                if(!nodeInTheQueue.contains(target)){
                    nodeInTheQueue.add(target);
                    queue.offer(target);
                }
            }
        }
        return newgraph;
    }


    /* input is file name  output is a new dot file*/
    public void exportGraph (String fileName){
        try {
            String dotName = String.format("%s.dot", fileName);
            exporter.exportGraph(inputgraph, new FileWriter(dotName));
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    public void exportGraph(DirectedPseudograph<EntityNode, EventEdge>graph, String fileName){
        try {
            exporter.exportGraph(graph, new FileWriter(String.format("%s.dot", fileName)));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @SuppressWarnings("duplicates")
    public void exportGraphBasedOnThreshold(DirectedPseudograph<EntityNode, EventEdge>graph, String POI, double change,
                                            boolean forward, String fileName){
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        EntityNode start = getGraphVertex(POI);
        assert start != null;
        Set<EntityNode> processedVertex = new HashSet<>();
        Queue<EntityNode> queue = new LinkedList<>();
        queue.offer(start);
        processedVertex.add(start);
        while(!queue.isEmpty()){
            EntityNode cur = queue.poll();
            res.addVertex(cur);
            if(forward){
                Set<EventEdge> edgeSet = graph.outgoingEdgesOf(cur);
                for(EventEdge edge: edgeSet){
                    EntityNode source = edge.getSource();
                    EntityNode target = edge.getSink();
                    if(satisfyChangeRate(source, target, change)){
                        res.addVertex(target);
                        res.addEdge(source, target, edge);
                        if(!processedVertex.contains(target)){
                            processedVertex.add(target);
                            queue.offer(target);
                        }
                    }
                }
            }else{
                Set<EventEdge> edgeSet = graph.incomingEdgesOf(cur);
                for(EventEdge edge: edgeSet){
                    EntityNode source = edge.getSource();
                    EntityNode target = edge.getSink();
                    if(satisfyChangeRate(target, source, change)){
                        res.addVertex(source);
                        res.addEdge(source, target, edge);
                        if(!processedVertex.contains(source)){
                            processedVertex.add(source);
                            queue.offer(source);
                        }
                    }
                }
            }
        }
        exportGraph(res, "limited_reputation_change"+fileName);
    }

    //if reputation change (node2-node1)/node1 <= change return true
    private boolean satisfyChangeRate(EntityNode node1, EntityNode node2, double change){
        double reputationDiff = node1.reputation - node2.reputation;
        if(node1.reputation == 0){
            return false;
        }

        if(reputationDiff/node1.reputation <= change){
            return true;
        }
        return false;
    }



    public EntityNode getGraphVertex(String input){
//        Set<EntityNode> vertexSet = inputgraph.vertexSet();
//        for(EntityNode n:vertexSet){
//            System.out.println(n.getSignature());
//            if(n.getSignature().equals(input)){
//                return n;
//            }
//        }
        if(indexOfNode.containsKey(input)){
            return indexOfNode.get(input);
        }
        System.out.println("Can't find the vertex");
        return null;
    }

    public void printVertexReputation(){
        Set<EntityNode> vertex = inputgraph.vertexSet();
        int count = 0;
        for(EntityNode v : vertex){
            System.out.print(String.valueOf(v.getReputation())+" ");
            count++;
            if((count +1)%20==0) System.out.println();
        }
    }

    public boolean findProcessNode(DirectedPseudograph<EntityNode, EventEdge>graph,String pname){
        Set<EntityNode> vertex = graph.vertexSet();
        for(EntityNode v:vertex){
            if(v.getP()!=null && v.getP().getName().equals((pname))){
                return true;
            }
        }
        return false;
    }

    public void exportGraphAmountAndTime(String file){
        DOTExporter<EntityNode, EventEdge> export =  new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),new EntityNameProvider(), new EdgeAmountTimeProvider(),new EntityAttributeProvider(),null);
        try {
            export.exportGraph(inputgraph, new FileWriter(file));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void printPathsOfSpecialVertex(String vertex)
    {
        EntityNode v1 = getGraphVertex(vertex);
        assert v1!=null;
        Set<EventEdge> outgoing = inputgraph.outgoingEdgesOf(v1);
        Set<EventEdge> incoming = inputgraph.incomingEdgesOf(v1);
        List<EventEdge> list = new ArrayList<>(outgoing);
        sortEdgesBasedOnWeight(list);
        List<EventEdge> list2 =  new ArrayList<>(incoming);
        sortEdgesBasedOnWeight(list2);
        try{
            FileWriter w = new FileWriter(new File(String.format("%s.txt",vertex)));
            for(int i=0; i< list.size(); i++){
                EventEdge edge = list.get(i);
                w.write("Target: " + edge.getSink().getSignature() + " Data: " + edge.getSize() + " Weight: "+ edge.weight+ System.lineSeparator());
            }
            for(int i=0;i< list2.size(); i++){
                EventEdge edge = list2.get(i);
                w.write("Source: "+ edge.getSource().getSignature()+" Data: " + edge.getSize()+" Weiget: "+edge.weight+System.lineSeparator());
            }
            w.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public BigDecimal getLatestOperationTime(String str){
        EntityNode vertex = getGraphVertex(str);
        assert vertex != null;
        Set<EventEdge> incoming = inputgraph.incomingEdgesOf(vertex);
        BigDecimal res = BigDecimal.ZERO;
        for(EventEdge e:incoming){
            if(res.compareTo(e.getStartTime())<0){
                res = e.getStartTime();
            }
        }
        return res;
    }

    public void OutputPaths(List<GraphPath<EntityNode, EventEdge>> paths){
        System.out.println("Paths size:" + paths.size());
        for(int i=0; i<paths.size(); i++){
            Graph<EntityNode, EventEdge> g = paths.get(i).getGraph();
            String fileName = String.format("Path %d.dot", i);
            try{
                exporter.exportGraph(g, new FileWriter(new File(fileName)));
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    private void sortEdgesBasedOnWeight(List<EventEdge> edges){
        Comparator<EventEdge> cp= new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                if(a.weight >= b.weight){
                    return 1;
                }else{
                    return 0;
                }
            }
        };

        Collections.sort(edges, cp);
    }
    // the largest end time of POI vertex
    public BigDecimal getLatestOperationTime(EntityNode node) {
        assert node != null;
        Set<EventEdge> edges = inputgraph.incomingEdgesOf(node);
        BigDecimal res = BigDecimal.ZERO;
        for (EventEdge e : edges) {
            if (res.compareTo(e.getEndTime()) < 0) {
                res = e.getEndTime();
            }
        }
        return res;
    }

    public List<DirectedPseudograph<EntityNode, EventEdge>> getHighWeightPaths(String s){
        EntityNode start = getGraphVertex(s);
        assert start != null;
        List<DirectedPseudograph<EntityNode, EventEdge>> paths = new ArrayList<DirectedPseudograph<EntityNode, EventEdge>>();
        for(int i=0; i<5; i++){
            DirectedPseudograph<EntityNode, EventEdge> path = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
            Queue<EntityNode> queue = new LinkedList<>();
            queue.offer(start);
            Set<EntityNode> visited = new HashSet<>();
            while(!queue.isEmpty()){
                EntityNode cur = queue.poll();
                visited.add(cur);
                path.addVertex(cur);
                Set<EventEdge> incoming = inputgraph.incomingEdgesOf(cur);
                if(incoming.size() > 0){
                    List<EventEdge> listOfIncoming = sortBasedOnWeight(incoming);
                    if(listOfIncoming.size() == 1){
                        EventEdge inc = listOfIncoming.get(0);
                        path.addVertex(inc.getSource());
                        path.addEdge(inc.getSource(), cur, inc);
                        if(!visited.contains(inc.getSource()))
                            queue.offer(inc.getSource());
                    }else{
                        EventEdge inc = listOfIncoming.get(0);
                        if(!visited.contains(inc.getSource()))
                            queue.offer(inc.getSource());
                        path.addVertex(inc.getSource());
                        path.addEdge(inc.getSource(), cur, inc);
                        inputgraph.removeEdge(inc);
                    }
                }
                Set<EventEdge> outgoing = inputgraph.outgoingEdgesOf(cur);
                if(outgoing.size() > 0){
                    List<EventEdge> listOfOutgoing = sortBasedOnWeight(outgoing);
                    if(listOfOutgoing.size() == 1){
                        EventEdge out = listOfOutgoing.get(0);
                        path.addVertex(out.getSink());
                        path.addEdge(cur, out.getSink(), out);
                        if(!visited.contains(out.getSink()))
                            queue.offer(out.getSink());

                    }else{
                        EventEdge out = listOfOutgoing.get(0);
                        queue.offer(out.getSink());
                        path.addVertex(out.getSink());
                        path.addEdge(cur, out.getSink(), out);
                        if(!visited.contains(out.getSink()))
                            queue.offer(out.getSink());
                        inputgraph.removeEdge(out);
                    }
                }
            }
            paths.add(path);
        }
        return paths;

    }

    public void printEdgesOfVertex(String s, String suffix){
        EntityNode vertex = getGraphVertex(s);
        assert  vertex != null;
        Set<EventEdge> incoming = inputgraph.incomingEdgesOf(vertex);
        List<EventEdge> list = sortBasedOnWeight(incoming);
        String fileName = "edgesOf"+s;
        Set<EventEdge> outgoing = inputgraph.outgoingEdgesOf(vertex);
        List<EventEdge> list2 = sortBasedOnWeight(outgoing);
        try {
            FileWriter writer = new FileWriter(s +"_"+suffix+"_edge_weights.txt");
            writer.write("Incoming: "+"\n");
            for (int i = 0; i < list.size(); i++) {
                String cur = outputEdge(list.get(i));
                writer.write(cur+"\n");
            }
            writer.write("Outgoing: "+"\n");
            for(int i=0; i< list2.size(); i++){
                String cur = outputEdge(list2.get(i));
                writer.write(cur+"\n");
            }
            writer.close();

        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void printEdgesOfVertexes(String s, String suffix, Map<Long, Double> time, Map<Long, Double> amount, Map<Long, Double> structure){
        EntityNode vertex = getGraphVertex(s);
        try{
            File file = new File(s+"_"+suffix+"_edge_weights.txt");
            FileWriter fileWriter = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            Set<EventEdge> incoming = inputgraph.incomingEdgesOf(vertex);
            printWriter.println("Incoming: ");
            for(EventEdge e : incoming){
                printWriter.println(e.toString());
                String weights = "TimeWeight: "+ Double.toString(time.get(e.id)) + "AmountWeight: "+ Double.toString(amount.get(e.id))+
                        "StructureWeight: "+ Double.toString(structure.get(e.id));
                printWriter.println(weights);
            }
            printWriter.println("Outgoing: ");
            Set<EventEdge> outgoing = inputgraph.outgoingEdgesOf(vertex);
            for(EventEdge e: outgoing){
                printWriter.println(e.toString());
                String weights = "TimeWeight: "+ Double.toString(time.get(e.id)) + "AmountWeight: "+ Double.toString(amount.get(e.id))+
                        "StructureWeight: "+ Double.toString(structure.get(e.id));
                printWriter.println(weights);
            }
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void printEdgesOfVertexes(List<String> list, String suffix){
        for(String v: list){
            printEdgesOfVertex(v, suffix);
        }
    }

    private String outputEdge(EventEdge e){
        return e.toString();
    }

    private List<EventEdge> sortBasedOnWeight(Set<EventEdge> edges){
        List<EventEdge> list = new ArrayList<>(edges);
        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                double diff = b.weight - a.weight;
                if (diff == 0){
                    return 0;
                }else if(diff > 0){
                    return 1;
                }else{
                    return -1;
                }
            }
        };

        Collections.sort(list, cmp);
        return list;
    }

    public double avergeEdgeWeight(){
        int nums = inputgraph.edgeSet().size();
        double sum = 0.0;
        for(EventEdge edge : inputgraph.edgeSet()){
            sum += edge.weight;
        }
        return sum/(nums*1.0);
    }
    /*this need to be tested*/
    public void filterGraphBasedOnAverageWeight(){
        double averageEdgeWeight = avergeEdgeWeight()/100.0;
        List<EventEdge> edges = new ArrayList<>(inputgraph.edgeSet());
        for(int i=0; i< edges.size(); i++){
            if(edges.get(i).weight < averageEdgeWeight){
                inputgraph.removeEdge(edges.get(i));
            }
        }
        List<EntityNode> list = new ArrayList<>(inputgraph.vertexSet());
        for(int i=0; i< list.size(); i++){
            EntityNode v = list.get(i);
            if(inputgraph.incomingEdgesOf(v).size() == 0 && inputgraph.outgoingEdgesOf(v).size() == 0){
                inputgraph.removeVertex(v);
            }
        }
    }

    public void filterGraphBasedOnVertexReputation(){
        List<EntityNode> vlist = new ArrayList<>(inputgraph.vertexSet());
        for(int i=0;i<vlist.size(); i++){
            EntityNode v = vlist.get(i);
            if(v.reputation == 0.0){
                List<EventEdge> inc = new ArrayList<>(inputgraph.incomingEdgesOf(v));
                for(int j=0; j< inc.size();j++){
                    inputgraph.removeEdge(inc.get(j));
                }
                List<EventEdge> out = new ArrayList<>(inputgraph.outgoingEdgesOf(v));
                for(int j=0; j<out.size(); j++){
                    inputgraph.removeEdge(out.get(j));
                }
                inputgraph.removeVertex(v);
            }
        }
    }

    public static void printReputation(DirectedPseudograph<EntityNode, EventEdge> graph, int step){
        try {
            FileWriter writer = new FileWriter(String.valueOf(step) + ".txt");
            PrintWriter pwriter = new PrintWriter(writer);
            for (EntityNode v : graph.vertexSet()) {
                pwriter.write(v.getSignature() + ": " + String.valueOf(v.reputation)+"\n");
            }
            pwriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void removeSingleVertex(){
        List<EntityNode> list = new ArrayList<>(inputgraph.vertexSet());
        for(int i=0; i< list.size(); i++){
            EntityNode v = list.get(i);
            if(inputgraph.incomingEdgesOf(v).size() == 0 && inputgraph.outgoingEdgesOf(v).size() == 0){
                inputgraph.removeVertex(v);
            }
        }
    }

    public static Map<String, Integer> countEdgeBasedOnNodeSignature(DirectedPseudograph<EntityNode, EventEdge> graph){
        Map<String, Integer> res = new HashMap<>();
        Set<EventEdge> edgeSet = graph.edgeSet();
        for(EventEdge edge : edgeSet){
            String key = convertEdgeToString(edge);
            if(!res.containsKey(key)){
                res.put(key, 1);
            }else{
                res.put(key, res.get(key)+1);
            }
        }
        return res;
    }

    public static Map<String, Integer> groupsEdges(List<DirectedPseudograph<EntityNode, EventEdge>> graphs){
        Map<String, Integer> edgeCount = new HashMap<>();
        for(DirectedPseudograph<EntityNode, EventEdge> graph: graphs){
            Set<EventEdge> edges = graph.edgeSet();
            for(EventEdge edge : edges){
                String key = convertEdgeToString(edge);
                if(!edgeCount.containsKey(key)){
                    edgeCount.put(key, 0);
                }
                edgeCount.put(key, edgeCount.get(key)+1);
            }
        }
        return edgeCount;
    }

    public static String convertEdgeToString(EventEdge edge){
        StringBuilder sb = new StringBuilder();
        sb.append(edge.getSource().getSignature());
        sb.append(" -> ");
        sb.append(edge.getSink().getSignature());
        return sb.toString();
    }

    public static Map<String, Double> getNodeReputation(DirectedPseudograph<EntityNode, EventEdge> graph){
        Map<String, Double> nodeReputation = new HashMap<>();
        for(EntityNode node: graph.vertexSet()){
            nodeReputation.put(node.getSignature(), node.reputation);
        }
        return nodeReputation;
    }

    public static void printEdgeByWeights(String fileName, DirectedPseudograph<EntityNode, EventEdge> graph){
        List<EventEdge> edgeList = new ArrayList<>(graph.edgeSet());
        Map<Double, List<EventEdge>> edgeMap = new HashMap<>();
        for(int i=0; i<=9; i++){
            double d = i/(10*1.0);
            edgeMap.put(d, new LinkedList<EventEdge>());
        }
        for(EventEdge edge: edgeList){
            if(edge.weight >=0.9){
                edgeMap.get(0.9).add(edge);
            }else if(edge.weight >= 0.8){
                edgeMap.get(0.8).add(edge);
            }else if(edge.weight >=0.7){
                edgeMap.get(0.7).add(edge);
            }else if(edge.weight >= 0.6){
                edgeMap.get(0.6).add(edge);
            }else if(edge.weight >= 0.5){
                edgeMap.get(0.5).add(edge);
            }else if(edge.weight >= 0.4){
                edgeMap.get(0.4).add(edge);
            }else if(edge.weight >= 0.3){
                edgeMap.get(0.3).add(edge);
            }else if(edge.weight >= 0.2){
                edgeMap.get(0.2).add(edge);
            }else if(edge.weight >= 0.1){
                edgeMap.get(0.1).add(edge);
            }else{
                edgeMap.get(0.0).add(edge);
            }
        }
        for(Double k : edgeMap.keySet()){
            printEdges(k, fileName, edgeMap.get(k));
        }
    }

    private static void printEdges(double wegihtLevel, String fileName, List<EventEdge> edges){
        try{
            System.out.println(edges);
            File file = new File(fileName+"_"+ Double.toString(wegihtLevel));
            FileWriter writer = new FileWriter(file);
            PrintWriter printWriter = new PrintWriter(writer);
            for(EventEdge edge: edges){
                printWriter.println(edge.getSource().getSignature()+"->"+edge.getSink().getSignature()+": " +Double.toString(edge.weight));
            }
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static void printVertexReputationLimitedByStepToFile(Map<String, Integer> stepInfo,
                                                          Map<String, Double> reputations, int step){
        try{
            File res = new File(String.format("%dStep_Node_reputation.txt",step));
            FileWriter fileWriter = new FileWriter(res);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            for(String node: stepInfo.keySet()){
                if(stepInfo.get(node) == step){
                    printWriter.println(node+": "+reputations.get(node).toString());
                }
            }
            printWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static Map<String, EntityNode> getSignatureNodeMap(DirectedPseudograph<EntityNode, EventEdge> graph){
        Map<String, EntityNode> map = new HashMap<>();
        graph.vertexSet().stream().forEach(v -> map.put(v.getSignature(), v));
        return map;
    }

    //Todo: add more libraries to Metaconfig
    public static List<String> getCandidateEntryPoint(DirectedPseudograph<EntityNode, EventEdge> graph){
        List<String> res = new LinkedList<>();
        Set<String> libraries = new HashSet<String>(Arrays.asList(MetaConfig.midRP));
        for (EntityNode v : graph.vertexSet()) {
            if(v.isNetworkNode()){
                res.add(v.getSignature());
            }else if(v.isProcessNode()){
                Set<EventEdge> incoming = graph.incomingEdgesOf(v);
                boolean sourceIsIP = false;
                boolean sourceIsProcess = false;
                for(EventEdge edge : incoming){
                    EntityNode node = edge.getSource();
                    if(node.isNetworkNode()){
                        sourceIsIP = true;
                        break;
                    }
                    if(node.isProcessNode()){
                        sourceIsProcess = true;
                        break;
                    }
                }
                if (!sourceIsIP && !sourceIsProcess) {
                    res.add(v.getSignature());
                }
            } else{
                if (!libraries.contains(v.getSignature()) && graph.incomingEdgesOf(v).size() == 0){
                    res.add(v.getSignature());
                }
            }
        }
        return res;
    }

    public static void sortedSignatureBasedOnRP(List<String> signatures, Map<String, Double>nodeReputation){
        Collections.sort(signatures, (a, b)->nodeReputation.get(b).compareTo(nodeReputation.get(a)));
    }

    public static void outputTopStarts(String resultDir, List<List<String>> starts, Map<String, Double> reputations){
        try{
            JSONObject start_rank = new JSONObject();
            File startsReputation = new File(resultDir+"start_rank.txt");
            FileWriter fileWriter = new FileWriter(startsReputation);
            PrintWriter printWriter = new PrintWriter(fileWriter);
            for(int i=0; i<starts.size(); i++){
                List<String> list = starts.get(i);
                JSONArray points = new JSONArray();
                printWriter.println("-----------------------------------------");
                for(String s: list){
                    points.add(s);
                    printWriter.println(s+": "+reputations.get(s));
                }
                if(i == 0){
                    start_rank.put("Process Start", points);
                }else if(i == 1){
                    start_rank.put("IP Start", points);
                }else{
                    start_rank.put("File Start", points);
                }
            }
            printWriter.close();
            File entryPointsJsonFile = new File(resultDir+"start_rank.json");
            FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
            jsonWriter.write(start_rank.toJSONString());
            jsonWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static List<List<String>> randomPickEntryStartsBasedOnCategory(List<List<String>> starts){
        List<List<String>> res = new ArrayList<>();
        for(List<String> list: starts){
            List<String> randomList = new ArrayList<>(list);
            for(int i=0; i<100; i++) {
                Collections.shuffle(randomList);
            }
            res.add(randomList);
        }
        return res;
    }

    public static List<String> getRandomStarts(DirectedPseudograph<EntityNode, EventEdge> graph, int number){
        List<String> res = new ArrayList<>();
        List<String> nodes = new LinkedList<>();
        for(EntityNode v: graph.vertexSet()){
            nodes.add(v.getSignature());
        }
        Collections.shuffle(nodes);
        for (int i = 0; i < number; i++){
            res.add(nodes.get(i));
        }
        return res;
    }

    public static List<String> getRandomStarts(List<String> nodes, int number){
        List<String> res= new ArrayList<>();
        for(int i=0;i<10; i++) {
            Collections.shuffle(nodes);
        }
        for (int i = 0; i < number; i++){
            res.add(nodes.get(i));
        }
        return res;
    }
}
