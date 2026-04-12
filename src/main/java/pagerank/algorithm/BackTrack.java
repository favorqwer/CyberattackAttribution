package pagerank.algorithm;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
//import com.sun.org.apache.xpath.internal.SourceTree;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DirectedPseudograph;

import java.math.BigDecimal;
import java.util.*;

/**
 * BackTrack - 后向切片算法
 * 
 * 本类实现后向切片（Backtracking）算法，从检测点（POI - Point of Interest）开始，
 * 反向追踪所有可能导致该检测点的因果路径，生成一个大幅精简的子图。
 * 
 * 算法核心思想：
 * 1. 从POI（恶意事件/检测点）开始，作为后向遍历的起点
 * 2. 沿着依赖图的入边（incoming edges）反向遍历
 * 3. 使用时间阈值约束：只保留在检测点时间之前的操作
 * 4. 最终得到一个只包含"可能影响POI"的节点和边的子图
 * 
 * 为什么要做后向切片？
 * - 原始依赖图可能包含成千上万个节点和边
 * - 大部分节点/边与攻击无关（背景噪音）
 * - 后向切片可以将图规模缩减90%以上，大大提高后续分析的效率
 * 
 * 时间阈值机制：
 * - 每个节点维护一个时间阈值（timeThreshold）
 * - POI节点的时间阈值设为最后一次操作的时间
 * - 遍历过程中，父节点的时间阈值 = min(边结束时间, 子节点时间阈值)
 * - 只保留开始时间早于阈值的边
 * 
 */
public class BackTrack {
    // 原始完整依赖图
    DirectedPseudograph<EntityNode,EventEdge> originalGraph;
    // 图遍历工具
    IterateGraph iterateGraph;
    // 后向切片后的子图
    public DirectedPseudograph<EntityNode, EventEdge> afterBackTrack;
    // 记录每个节点的回溯步数信息
    private Map<String, Integer> stepInfo;
    // 循环检测器（用于处理图中的循环）
    private CycleDetector<EntityNode, EventEdge> cycleDetector;

    /**
     * 构造函数
     * 
     * @param input 原始依赖图
     */
    public BackTrack(DirectedPseudograph<EntityNode, EventEdge> input){
        // 深拷贝原始图，避免修改影响原始数据
        originalGraph = (DirectedPseudograph<EntityNode, EventEdge>)input.clone();
        // 创建图遍历工具
        iterateGraph = new IterateGraph(originalGraph);
        stepInfo = new HashMap<>();
        // 创建循环检测器
        cycleDetector = new CycleDetector<>(originalGraph);
    }
    /**
     * 根据给定的起始节点进行回溯，构建一个子图
     * 该方法通过时间阈值约束来确定需要包含在回溯图中的节点和边
     * 
     * @param str 起始节点的标识字符串
     * @return 包含回溯路径的有向图
     */
    /**
     * backTrackPOIEvent - 核心后向切片方法
     * 
     * 从给定的POI（检测点）开始，执行后向切片算法：
     * 1. 找到POI节点及其最后操作时间
     * 2. 使用BFS反向遍历图
     * 3. 根据时间阈值筛选合法的边
     * 4. 返回只包含相关节点和边的子图
     * 
     * @param str POI节点的signature（唯一标识）
     * @return 后向切片后的子图
     */
    // Recommended backtracking implementation that addresses previously known issues.
    public DirectedPseudograph<EntityNode, EventEdge> backTrackPOIEvent(String str){
        System.out.println("backTrackPOIEvent invoked: "+str);
        // 创建新的空图用于存储切片结果
        DirectedPseudograph<EntityNode, EventEdge> backTrack = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        
        // 获取POI节点
        EntityNode start = iterateGraph.getGraphVertex(str);
        // 获取该节点的最后操作时间（作为时间阈值上限）
        BigDecimal latestOPTime = iterateGraph.getLatestOperationTime(start);
        
        // 时间阈值映射：每个节点有一个时间阈值，表示该节点最晚的操作时间
        Map<EntityNode,BigDecimal> timeThresolds = new HashMap<>();
        timeThresolds.put(start, latestOPTime);
        
        // BFS队列和已访问节点集合
        Set<EntityNode> nodeInTheQueue = new HashSet<>();
        Queue<EntityNode> queue = new LinkedList<>();
        nodeInTheQueue.add(start);
        queue.offer(start);

        // 广度优先遍历，根据时间阈值筛选节点和边
        while(!queue.isEmpty()){
            // 取出当前节点
            EntityNode cur = queue.poll();
            // 将当前节点加入子图
            backTrack.addVertex(cur);
            
            // 获取当前节点的所有入边（指向当前节点的边）
            Set<EventEdge> incoming = originalGraph.incomingEdgesOf(cur);
            // 获取当前节点的时间阈值
            BigDecimal curThresold = timeThresolds.get(cur);
            
            // 遍历当前节点的所有入边
            for(EventEdge e:incoming){
                // 筛选条件：边的开始时间必须早于当前时间阈值
                // 如果边的开始时间晚于阈值，说明这条边发生在检测点之后，不可能影响检测点
                if(e.getStartTime().compareTo(curThresold)>0) continue;
                
                // 获取边的源节点（父节点）
                EntityNode source = e.getSource();
                backTrack.addVertex(source);
                // 初始化源节点时间阈值（如果不存在）
                timeThresolds.putIfAbsent(source, BigDecimal.ZERO);
                
                // 计算源节点的时间阈值：
                // 取边结束时间和当前节点时间阈值中的较小者
                // 因为父节点的操作必须在子节点操作之前（或同时）完成
                BigDecimal thresoldForSource = e.endTime.compareTo(curThresold)<0? e.endTime:curThresold;
                if(timeThresolds.get(source).compareTo(thresoldForSource) < 0){
                    timeThresolds.put(source, thresoldForSource);
                }
                
                // 将符合条件的边加入回溯图
                backTrack.addEdge(source, cur, e);
                
                // 如果源节点未被访问，加入队列继续反向遍历
                if(!nodeInTheQueue.contains(source)){
                    nodeInTheQueue.add(source);
                    queue.offer(source);
                }
            }
        }
        // 保存结果并返回
        afterBackTrack = backTrack;
        return backTrack;
    }

    // Recommended backtracking implementation that addresses previously known issues.
    public DirectedPseudograph<EntityNode, EventEdge> backTrackPOIEventWithStep(String str){
        System.out.println("backTrackPOIEvent invoked: "+str);
        DirectedPseudograph<EntityNode, EventEdge> backTrack = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        EntityNode start = iterateGraph.getGraphVertex(str);
        BigDecimal latestOPTime = iterateGraph.getLatestOperationTime(start);
        Map<EntityNode,BigDecimal> timeThresolds = new HashMap<>();
        timeThresolds.put(start, latestOPTime);
        Set<EntityNode> nodeInTheQueue = new HashSet<>();
        Queue<EntityNode> queue = new LinkedList<>();
        nodeInTheQueue.add(start);
        queue.offer(start);
        int step = 1;
        stepInfo.put(start.getSignature(), step);

        while(!queue.isEmpty()){
            EntityNode cur = queue.poll();
            backTrack.addVertex(cur);
            Set<EventEdge> incoming = originalGraph.incomingEdgesOf(cur);
            BigDecimal curThresold = timeThresolds.get(cur);
            for(EventEdge e:incoming){
                if(e.getStartTime().compareTo(curThresold)>0) continue;
                EntityNode source = e.getSource();
                backTrack.addVertex(source);
                if(!stepInfo.containsKey(source.getSignature())){
                    stepInfo.put(source.getSignature(), stepInfo.get(cur.getSignature())+1);
                }else{
                    Set<EntityNode> cycleContainsSource = cycleDetector.findCyclesContainingVertex(source);
                    if(!cycleContainsSource.contains(cur)){
                        step = Math.max(stepInfo.get(source.getSignature()), stepInfo.get(cur.getSignature())+1);
                        stepInfo.put(source.getSignature(), step);
                    }
                }
                timeThresolds.putIfAbsent(source, BigDecimal.ZERO);
                BigDecimal thresoldForSource = e.endTime.compareTo(curThresold)<0? e.endTime:curThresold;
                if(timeThresolds.get(source).compareTo(thresoldForSource) < 0){
                    timeThresolds.put(source, thresoldForSource);
                }
                backTrack.addEdge(source, cur, e);
                if(!nodeInTheQueue.contains(source)){
                    nodeInTheQueue.add(source);
                    queue.offer(source);
                }
            }
        }
        afterBackTrack = backTrack;
        return backTrack;
    }

    // This method follows the paper's procedure more strictly.
    public DirectedPseudograph<EntityNode, EventEdge> backTrackPOIEvent2(String str){

        DirectedPseudograph<EntityNode, EventEdge> backTrack = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        EntityNode start = iterateGraph.getGraphVertex(str);
        BigDecimal latestOPTime = iterateGraph.getLatestOperationTime(start);
        Map<EntityNode, BigDecimal> timeThresold = new HashMap<>();
        timeThresold.put(start, latestOPTime);
        List<EventEdge> edgeList = new LinkedList<>(originalGraph.edgeSet());
        Collections.sort(edgeList, (a, b)->b.getStartTime().compareTo(a.getStartTime()));
        backTrack.addVertex(start);

        for(EventEdge e: edgeList){
            EntityNode source = e.getSource();
            EntityNode target = e.getSink();
            if(backTrack.containsVertex(target)){
                BigDecimal targetThresold = timeThresold.get(target);
                if(e.getStartTime().compareTo(targetThresold)<0) {
                    if (!backTrack.containsVertex(source)) {
                        backTrack.addVertex(source);
                        BigDecimal thresoldForObject = e.getEndTime().compareTo(targetThresold)<0? e.getEndTime():targetThresold;
                        timeThresold.put(source, thresoldForObject);
                    }
                    backTrack.addEdge(source,target,e);
                }
            }
        }
        afterBackTrack = backTrack;
        return backTrack;

    }

    public void printGraph(){
        assert afterBackTrack != null;
        IterateGraph iter = new IterateGraph(afterBackTrack);
        iter.exportGraph("backtrackTest");
    }

    public void exportGraph(String file){
        assert afterBackTrack != null;
        IterateGraph iter = new IterateGraph(afterBackTrack);
        iter.exportGraph(file);
    }

    public Map<String, Integer> getBackwardStepInfo(){
        return Collections.unmodifiableMap(stepInfo);
    }


}
