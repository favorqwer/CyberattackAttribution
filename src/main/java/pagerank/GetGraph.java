package pagerank; /**
 * Created by fang on 7/3/17.
 * This class inclueds bfs , bfs with hopcount, backtrack to iterate the graph
 */

import org.jgrapht.ext.DOTExporter;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

public class GetGraph {

    public DirectedPseudograph<EntityNode, EventEdge> jg;
    private String filePath;
    private String[] localIP;
    private HashMap<Long, EntityNode> entityNodeMap;
    private ProcessTheOriginalParserOutput sysdigProcess;
    private DOTExporter<EntityNode, EventEdge> exporter;
    public EntityNode POIEvent;
    private IterateGraph iter;

    public GetGraph(String path, String[] localIP) {
        filePath = path;
        // 深拷贝本地IP数组（避免外部修改影响内部数据）
        this.localIP = new String[localIP.length];
        for (int i = 0; i < localIP.length; i++) {
            this.localIP[i] = localIP[i];
        }
        POIEvent = null;
        // 创建有向伪图（允许自环和平行边）
        jg = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        // 创建实体节点映射表（用于快速查找节点）
        entityNodeMap = new HashMap<Long, EntityNode>();
        // 创建原始数据解析器
        sysdigProcess = new ProcessTheOriginalParserOutput(path, localIP);
        //sysdigProcess.reverseSourceAndSink();
        // 创建DOT格式导出器（用于图形化显示）
        exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),
                new EntityNameProvider(), new EventEdgeProvider());
    }

    public DirectedPseudograph<EntityNode, EventEdge> getJg() {
        if (jg == null) {
            GenerateGraph();
            sysdigProcess.getParser().afterBuilding();
        }
        return jg;
    }

    public DOTExporter<EntityNode, EventEdge> getExporter() {
        return exporter;
    }

    public DirectedPseudograph<EntityNode, EventEdge> getOriginalGraph() {
        if (jg == null) GenerateGraph();

        return jg;
    }

    public void GenerateGraph() {//从解析的原始数据中提取各种事件类型，构建完整的图结构。
        Map<String, NtoPEvent> networkProcessMap = sysdigProcess.getNetworkProcessMap();
        Map<String, PtoNEvent> processNetworkMap = sysdigProcess.getProcessNetworkMap();
        Map<String, PtoFEvent> processFileMap = sysdigProcess.getProcessFileMap();
        Map<String, FtoPEvent> fileProcessMap = sysdigProcess.getFileProcessMap();
        Map<String, PtoPEvent> processProcessMap = sysdigProcess.getProcessProcessMap();
        addFileToProcessEvent(fileProcessMap);
        addNetworkToProcessEvent(networkProcessMap);
        addProcessToFileEvent(processFileMap);
        addProcessToProcessEvent(processProcessMap);
        addProcessToNetworkEvent(processNetworkMap);
        assignEdgeId();
//        文件到进程事件- 文件访问相关的边
//        网络到进程事件- 网络连接接收的边
//        进程到文件事件- 文件写入/读取的边
//        进程到进程事件- 进程间通信的边
//        进程到网络事件- 网络连接发起的边
//        分配边ID- 为所有边分配唯一标识符
    }

    private void assignEdgeId() {
        long edgeID = 1;
        for (EventEdge e : jg.edgeSet()) {
            e.id = edgeID;
            edgeID++;
        }
    }

    public void exportGraph() throws Exception {
        DOTExporter<EntityNode, EventEdge> exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),
                new EntityNameProvider(), new EventEdgeProvider());
        GenerateGraph();
        exporter.exportGraph(jg, new FileWriter("dot_output.dot"));
    }

    private void addProcessToFileEvent(Map<String, PtoFEvent> pfmap) {
        Set<String> keys = pfmap.keySet();
        for (String key : keys) {
            EntityNode source = null;
            EntityNode sink = null;
            if (entityNodeMap.containsKey(pfmap.get(key).getSource().getUniqID())) {
                source = entityNodeMap.get(pfmap.get(key).getSource().getUniqID());
            } else {
                source = new EntityNode(pfmap.get(key).getSource());
                entityNodeMap.put(source.getID(), source);
            }

            if (entityNodeMap.containsKey(pfmap.get(key).getSink().getUniqID())) {
                sink = entityNodeMap.get(pfmap.get(key).getSink().getUniqID());
            } else {
                sink = new EntityNode(pfmap.get(key).getSink());
                entityNodeMap.put(sink.getID(), sink);
            }

            jg.addVertex(source);
            jg.addVertex(sink);
            EventEdge edge = new EventEdge(pfmap.get(key));
            jg.addEdge(source, sink, edge);
        }
    }

    private void addFileToProcessEvent(Map<String, FtoPEvent> fpmap) {
        Set<String> keys = fpmap.keySet();
        for (String key : keys) {
            EntityNode source = null;
            EntityNode sink = null;
            if (entityNodeMap.containsKey(fpmap.get(key).getSource().getUniqID())) {
                source = entityNodeMap.get(fpmap.get(key).getSource().getUniqID());
            } else {
                source = new EntityNode(fpmap.get(key).getSource());
                entityNodeMap.put(source.getID(), source);
            }

            if (entityNodeMap.containsKey(fpmap.get(key).getSink().getUniqID())) {
                sink = entityNodeMap.get(fpmap.get(key).getSink().getUniqID());
            } else {
                sink = new EntityNode(fpmap.get(key).getSink());
                entityNodeMap.put(sink.getID(), sink);
            }

            jg.addVertex(source);
            jg.addVertex(sink);
            EventEdge edge = new EventEdge(fpmap.get(key));
            jg.addEdge(source, sink, edge);
        }


    }

    private void addProcessToProcessEvent(Map<String, PtoPEvent> ppmap) {
        Set<String> keys = ppmap.keySet();
        for (String key : keys) {
            EntityNode source = null;
            EntityNode sink = null;
            if (entityNodeMap.containsKey(ppmap.get(key).getSource().getUniqID())) {
                source = entityNodeMap.get(ppmap.get(key).getSource().getUniqID());
            } else {
                source = new EntityNode(ppmap.get(key).getSource());
                entityNodeMap.put(source.getID(), source);
            }

            if (entityNodeMap.containsKey(ppmap.get(key).getSink().getUniqID())) {
                sink = entityNodeMap.get(ppmap.get(key).getSink().getUniqID());
            } else {
                sink = new EntityNode(ppmap.get(key).getSink());
                entityNodeMap.put(sink.getID(), sink);
            }

            jg.addVertex(source);
            jg.addVertex(sink);
            EventEdge edge = new EventEdge(ppmap.get(key));
            jg.addEdge(source, sink, edge);
        }

    }

    private void addNetworkToProcessEvent(Map<String, NtoPEvent> npmap) {
        Set<String> keys = npmap.keySet();
//        System.out.println(keys.size());
        for (String key : keys) {
            EntityNode source = null;
            EntityNode sink = null;
            if (entityNodeMap.containsKey(npmap.get(key).getSource().getUniqID())) {
                source = entityNodeMap.get(npmap.get(key).getSource().getUniqID());
            } else {
                source = new EntityNode(npmap.get(key).getSource());
                entityNodeMap.put(source.getID(), source);
            }

            if (entityNodeMap.containsKey(npmap.get(key).getSink().getUniqID())) {
                sink = entityNodeMap.get(npmap.get(key).getSink().getUniqID());
            } else {
                sink = new EntityNode(npmap.get(key).getSink());
                entityNodeMap.put(sink.getID(), sink);
            }

            jg.addVertex(source);
            jg.addVertex(sink);
            EventEdge edge = new EventEdge(npmap.get(key));
            jg.addEdge(source, sink, edge);
        }

    }

    private void addProcessToNetworkEvent(Map<String, PtoNEvent> map) {
        Set<String> keys = map.keySet();
        for (String key : keys) {
            EntityNode source = null;
            EntityNode sink = null;
            if (entityNodeMap.containsKey(map.get(key).getSource().getUniqID())) {
                source = entityNodeMap.get(map.get(key).getSource().getUniqID());
            } else {
                source = new EntityNode(map.get(key).getSource());
                entityNodeMap.put(source.getID(), source);
            }

            if (entityNodeMap.containsKey(map.get(key).getSink().getUniqID())) {
                sink = entityNodeMap.get(map.get(key).getSink().getUniqID());
            } else {
                sink = new EntityNode(map.get(key).getSink());
                entityNodeMap.put(sink.getID(), sink);
            }

            jg.addVertex(source);
            jg.addVertex(sink);
            EventEdge edge = new EventEdge(map.get(key));
            jg.addEdge(source, sink, edge);
        }

    }

    /*   input should be file path, process name + pid or source IP:source Port-> destination ip:Port
     *    type : the legal input for this parameter only includes "Network", "File", "Process"
     * */
    public void bfs(String input, String type) throws IOException {
        EntityNode start = getGraphVertex(input, type);
        Queue<EntityNode> queue = new LinkedList<EntityNode>();
        if (start != null) {
            bfs(start, queue, input);
        } else {
            System.out.println("Your input doesn't exist in the graph");
        }
    }

    private void bfs(EntityNode start, Queue<EntityNode> queue, String input) throws IOException {
        DirectedPseudograph<EntityNode, EventEdge> subgraph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        DirectedPseudograph<EntityNode, EventEdge> graph = jg;
        Set<EntityNode> set = new HashSet<>();
        Set<EventEdge> edgeSet = new HashSet<>();
        queue.offer(start);
        while (!queue.isEmpty()) {
            EntityNode cur = queue.poll();
            Set<EventEdge> outgoing = jg.outgoingEdgesOf(cur);
            Set<EventEdge> incoming = jg.incomingEdgesOf(cur);
            EntityNode subcur = new EntityNode(cur);
            subgraph.addVertex(subcur);
            for (EventEdge o : outgoing) {
                if (set.add(jg.getEdgeTarget(o))) {
                    queue.offer(graph.getEdgeTarget(o));
                }
                EntityNode target = graph.getEdgeTarget(o);
                if (edgeSet.add(o)) {
                    //EventEdge newEdge = new EventEdge(subcur, newTarget, o.getID(),o.getStartTime(),o.getEndTime());
                    subgraph.addVertex(target);
                    subgraph.addEdge(subcur, target, o);

                }
            }
            for (EventEdge i : incoming) {
                if (set.add(jg.getEdgeSource(i))) {
                    queue.offer(graph.getEdgeSource(i));
                }
                EntityNode newSource = graph.getEdgeSource(i);
                if (edgeSet.add(i)) {
                    //EventEdge newEdge = new EventEdge(newSource, subcur, i.getID(),i.getStartTime(),i.getEndTime());
                    subgraph.addVertex(newSource);
                    subgraph.addEdge(newSource, subcur, i);
                }
            }

        }
        DOTExporter<EntityNode, EventEdge> exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),
                new EntityNameProvider(), new EventEdgeProvider());

        exporter.exportGraph(subgraph, new FileWriter(String.format("%s_dot_output.dot", input)));
    }

    /*   input should be file path, process name + pid or source IP:source Port-> destination ip:Port
     *    type : the legal input for this parameter only includes "Network", "File", "Process"
     *    hopCount : used to control the step of BFS
     * */
    public void bfsWithHopCount(String input, String type, int hopCount) throws IOException {
        EntityNode start = getGraphVertex(input, type);
        Queue<EntityNode> queue = new LinkedList<EntityNode>();
        if (start != null) {
            bfs(start, queue, input, hopCount);
        } else {
            System.out.println("Your input doesn't exist in the graph");
        }
    }

    public DirectedPseudograph<EntityNode, EventEdge> backTrackPoi(String input, String type) {
        EntityNode start = getGraphVertex(input, type);
        return backTrackPoi(start);
    }

    public DirectedPseudograph<EntityNode, EventEdge> backTrackPoi(String input) {
        EntityNode start = getGraphVertex(input);
        return backTrackPoi(start);
    }

    private DirectedPseudograph<EntityNode, EventEdge> backTrackPoi(EntityNode node) {
        EntityNode start = node;
        POIEvent = start;
        DirectedPseudograph<EntityNode, EventEdge> subgraph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        if (start == null) {
            System.out.println("Can't find the input node in the graph");
        }
        Queue<EventEdge> queue = new LinkedList<>();
        Map<EntityNode, EventEdge> map = new HashMap<>();
        getPoiEvents(start, map);
        for (EventEdge e : map.values()) {
            queue.offer(e);
        }
        Set<EntityNode> vertexSet = new HashSet<>();
        Set<EventEdge> edgeSet = new HashSet<>();
        Set<EventEdge> removeDuplicate = new HashSet<>();
        int level = 0;
        while (!queue.isEmpty()) {
            Queue<EventEdge> nextStep = new LinkedList<>();

            while (!queue.isEmpty()) {
                EventEdge edge = queue.poll();
                EntityNode target = jg.getEdgeTarget(edge);
                EntityNode source = jg.getEdgeSource(edge);
                Set<EventEdge> incoming = jg.incomingEdgesOf(source);
                EventEdge poi = map.get(source);
                for (EventEdge e : incoming) {
                    if (e.getEndTime().compareTo(edge.getEndTime()) < 0 && removeDuplicate.add(e)) {     // here need to check!!!!
                        nextStep.offer(e);
                    }
                }
                if (vertexSet.add(source)) {
                    subgraph.addVertex(source);
                    //getPoiEvents(source,nextMap);

                }
                if (vertexSet.add(target)) {
                    subgraph.addVertex(target);
                }
                if (edgeSet.add(edge)) {
                    subgraph.addEdge(source, target, edge);
                }
            }
            queue = nextStep;
            level++;
            System.out.println("BackTrackPoi test level step:" + String.valueOf(level));
        }
        try {
            exporter.exportGraph(subgraph, new FileWriter("backTrackpoi.dot"));
        } catch (IOException e) {
            System.out.println("IO exception");
        }
        return subgraph;

    }

    public void backTrackWithHopCount(String input, String type, int hopcount) {
        EntityNode start = getGraphVertex(input, type);
        DirectedPseudograph<EntityNode, EventEdge> subgraph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        if (start == null) {
            System.out.println("The input doesn't exist in the graph");
        }

        Queue<EventEdge> queue = new LinkedList<>();
        Map<EntityNode, EventEdge> map = new HashMap<>();
        getPoiEvents(start, map);
        for (EventEdge e : map.values()) {
            queue.offer(e);
        }
        int level = 1;
        while (!queue.isEmpty() && hopcount >= 1) {
            Queue<EventEdge> nextStep = new LinkedList<>();
            Set<EntityNode> vertexSet = new HashSet<>();
            Set<EventEdge> edgeSet = new HashSet<>();
            Set<EventEdge> removeDuplicate = new HashSet<>();
//            Map<EntityNode, EventEdge> nextMap = new HashMap<>();
            while (!queue.isEmpty()) {
                EventEdge edge = queue.poll();
                EntityNode target = jg.getEdgeTarget(edge);
                EntityNode source = jg.getEdgeSource(edge);
                Set<EventEdge> incoming = jg.incomingEdgesOf(source);
                EventEdge poi = map.get(source);
                for (EventEdge e : incoming) {
                    if (e.getStartTime().compareTo(edge.getEndTime()) < 0 && removeDuplicate.add(e)) {
                        nextStep.offer(e);
                    }
                }
                if (vertexSet.add(source)) {
                    subgraph.addVertex(source);
                    //getPoiEvents(source,nextMap);

                }
                if (vertexSet.add(target)) {
                    subgraph.addVertex(target);
                }
                if (edgeSet.add(edge)) {
                    subgraph.addEdge(source, target, edge);
                }


            }
            queue = nextStep;
            hopcount--;
            try {
                exporter.exportGraph(subgraph, new FileWriter(String.format("backTrack_%s_%d_ouput.dot", "test", level)));
            } catch (IOException e) {
                System.out.println("IO Exception");
            }
            level++;
        }
    }


    private void getPoiEvents(EntityNode node, Map<EntityNode, EventEdge> map) {
        Set<EventEdge> incoming = new HashSet<>(jg.incomingEdgesOf(node));
        Set<EntityNode> vertexSet = new HashSet<>();
        for (EventEdge i : incoming) {
            EntityNode source = jg.getEdgeSource(i);
            if (vertexSet.add(source)) {
                Set<EventEdge> outgoing = jg.outgoingEdgesOf(source);
                List<EventEdge> edgesBetweenSourceAndTarget = getEdgesBetweenSourceAndTarget(outgoing, incoming);
                if (edgesBetweenSourceAndTarget.size() == 0) {
                    System.out.println("Edges Between Source and Target is not correct");
                }
                List<EventEdge> sortedEdges = sortAccordingStartTime(edgesBetweenSourceAndTarget);
                if (sortedEdges.size() == 0) {
                    System.out.println("sorted edges is not correct");
                }
                map.put(source, sortedEdges.get(sortedEdges.size() - 1));
            }
        }
    }

    private List<EventEdge> getEdgesBetweenSourceAndTarget(Set<EventEdge> sourceOutgoing, Set<EventEdge> incoming) {
        List<EventEdge> res = new ArrayList<>();
        for (EventEdge edge : sourceOutgoing) {
            if (incoming.contains(edge)) {
                res.add(edge);
            }
        }
        return res;
    }


    private void bfs(EntityNode start, Queue<EntityNode> queue, String input, int hopCount) throws IOException {
        DirectedPseudograph<EntityNode, EventEdge> subgraph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        DirectedPseudograph<EntityNode, EventEdge> graph = jg;
        Set<EntityNode> set = new HashSet<>();
        Set<EventEdge> edgeSet = new HashSet<>();
        DOTExporter<EntityNode, EventEdge> exporter = new DOTExporter<EntityNode, EventEdge>(new EntityIdProvider(),
                new EntityNameProvider(), new EventEdgeProvider());
        queue.offer(start);
        int level = 0;
        while (!queue.isEmpty() && hopCount >= 1) {
            level++;
            Queue<EntityNode> nextStep = new LinkedList<>();
            //Set<EventEdge> removeDuplicate = new HashSet<>();
            while (!queue.isEmpty()) {
                EntityNode cur = queue.poll();

                Set<EventEdge> outgoing = jg.outgoingEdgesOf(cur);
                Set<EventEdge> incoming = jg.incomingEdgesOf(cur);
                EntityNode subcur = new EntityNode(cur);
                subgraph.addVertex(subcur);
                for (EventEdge o : outgoing) {
                    if (set.add(jg.getEdgeTarget(o))) {
                        nextStep.offer(graph.getEdgeTarget(o));
                    }
                    EntityNode target = graph.getEdgeTarget(o);
                    if (edgeSet.add(o)) {
                        subgraph.addVertex(target);
                        subgraph.addEdge(subcur, target, o);

                    }
                }
                for (EventEdge i : incoming) {
                    if (set.add(jg.getEdgeSource(i))) {
                        nextStep.offer(graph.getEdgeSource(i));
                    }
                    EntityNode source = graph.getEdgeSource(i);
                    if (edgeSet.add(i)) {
                        subgraph.addVertex(source);
                        subgraph.addEdge(source, subcur, i);
                    }
                }
            }
            exporter.exportGraph(subgraph, new FileWriter(String.format("%s_%d_dot_output.dot", "bfs", level)));
            queue = nextStep;
            hopCount--;
        }

    }

    private EntityNode getGraphVertex(String input, String type) {
        EntityNode start = null;
        Queue<EntityNode> queue = new LinkedList<EntityNode>();
        if (type.equals("Network")) {
            for (Long key : entityNodeMap.keySet()) {
                EntityNode e = entityNodeMap.get(key);
                if (e.getN() != null) {
                    if (e.getN().getSrcAddress().equals(input)) {
                        start = e;
                    }
                }

            }
        } else if (type.equals("File")) {
            for (Long key : entityNodeMap.keySet()) {
                EntityNode e = entityNodeMap.get(key);
                if (e.getF() != null) {
                    if (e.getF().getPath().equals(input)) {
                        start = e;
                    }
                }
            }
        } else {
            for (Long key : entityNodeMap.keySet()) {
                EntityNode e = entityNodeMap.get(key);
                if (e.getP() != null) {
                    if (e.getP().getName().equals(input)) {
                        start = e;
                        break;
                    }
                }
            }
        }
        if (start != null) {
            return start;
        } else {
            System.out.println("Your input doesn't exist in the graph");
            return null;
        }

    }

    private EntityNode getGraphVertex(String input) {
        for (EntityNode e : entityNodeMap.values()) {
            if (e.getSignature().equals(input)) {
                return e;
            }
        }
        return null;
    }

    private List<EventEdge> sortAccordingStartTime(Set<EventEdge> set) {
        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                return a.getStartTime().compareTo(b.getStartTime());
            }
        };
        List<EventEdge> res = new LinkedList<>(set);
        res.sort(cmp);
        return res;
    }

    private List<EventEdge> sortAccordingStartTime(List<EventEdge> list) {
        Comparator<EventEdge> cmp = new Comparator<EventEdge>() {
            @Override
            public int compare(EventEdge a, EventEdge b) {
                return a.getStartTime().compareTo(b.getStartTime());
            }
        };
        list.sort(cmp);
        return list;
    }

    private boolean backwardCheck(EventEdge p, EventEdge l, EntityNode u) {
        Set<EventEdge> incoming = jg.incomingEdgesOf(u);
        BigDecimal[] endTimes = {p.getEndTime(), l.getEndTime()};
        Arrays.sort(endTimes);
        for (EventEdge edge : incoming) {
            BigDecimal[] timeWindow = edge.getInterval();
            if (isOverlap(timeWindow, endTimes)) {
                return false;
            }
        }
        return true;
    }

    private boolean forwardCheck(EventEdge p, EventEdge l, EntityNode u) {
        BigDecimal[] startTime = {p.getStartTime(), l.getStartTime()};
        Set<EventEdge> outgoing = jg.outgoingEdgesOf(u);
        Arrays.sort(startTime);
        for (EventEdge edge : outgoing) {
            BigDecimal[] timeWindow = edge.getInterval();
            if (isOverlap(timeWindow, startTime)) {
                return false;
            }
        }
        return true;
    }

    private boolean isOverlap(BigDecimal[] a, BigDecimal[] b) {
        if (a[1].compareTo(b[0]) >= 0 && a[1].compareTo(b[1]) <= 0 || a[0].compareTo(b[0]) >= 0 && a[0].compareTo(b[1]) <= 0) {
            return true;
        }
        return false;
    }

    private void testGetVertex() {
        System.out.println("All process in graph");
        for (Long key : entityNodeMap.keySet()) {
            if (entityNodeMap.get(key).getP() != null) {
                System.out.println(entityNodeMap.get(key).getP().getName());
            }
        }


    }

    public EntityNode getPOIEvent() {
        return POIEvent;
    }

    public void exportGraph(String file) {
        if (jg == null) GenerateGraph();
        iter = new IterateGraph(jg);
        iter.exportGraph(file);
    }


    public static void main(String[] args) throws Exception {
        //String[] localIP={"129.22.21.193"};
        String[] localIP = {"10.0.2.15"};
        GetGraph test = new GetGraph("/home/fang/thesis2/Data/Expdata2/aptgetInstallUnrar.txt", localIP);
        test.GenerateGraph();
        IterateGraph iterateGraph = new IterateGraph(test.jg);
        iterateGraph.bfs("/usr/bin/unrar-nonfree.dpkg-new");
        //test.backTrackWithHopCount("/usr/bin/nodejs.dpkg-new","File",5);
        //test.bfsWithHopCount("129.22.151.208","Network",2);

    }

}
