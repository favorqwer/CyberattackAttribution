package pagerank.algorithm;

import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import org.jgrapht.graph.DirectedPseudograph;

import java.math.BigDecimal;
import java.util.*;

/**
 * CausalityPreserve (CPR) - 因果保持压缩算法
 * 
 * 本类实现因果保持压缩算法，用于在保持因果关系的前提下压缩图规模。
 * 
 * 压缩原理：
 * 在短时间内（例如10秒内），同一个进程对同一个文件/网络的连续操作可以被合并为一条边。
 * 例如：
 * - 进程P连续写文件F 10次 -> 合并为1条边，数据量累加
 * - 进程P连续发送网络数据包5次 -> 合并为1条边，数据量累加
 * 
 * 合并条件：
 * 1. 相同的事件类型（如都是write）
 * 2. 相同的主体和客体（源节点和目标节点相同）
 * 3. 时间间隔在指定窗口内（默认10秒）
 * 4. 合并后的边保留最早开始时间和最晚结束时间
 * 
 * 为什么要做因果保持压缩？
 * 1. 减少图规模，提高后续分析效率
 * 2. 保留关键的因果依赖关系
 * 3. 消除时间噪声（连续的重复操作）
 * 
 * 三种压缩模式：
 * 1. mergeConsiderTimeAndType: 考虑时间和事件类型
 * 2. mergeConsiderType: 只考虑事件类型
 * 3. mergeWithoutConsideringTimeAndType: 不考虑时间和类型（完全合并）
 */
public class CausalityPreserve {
    public static final String MODE_STANDARD_CPR = "standard_cpr";
    public static final String MODE_CAUSAL_STRICT = "causal_strict";
    public static final String MODE_FULL_MERGE = "full_merge";
    public static final String MODE_ENDPOINT_AGGREGATION = "endpoint_aggregation";
    public static final String MODE_WINDOWED_SEQUENCE = "windowed_sequence";
    public static final String MODE_NO_MERGE = "no_merge";
    public static final String MODE_PCAR = "pcar";
    public static final String MODE_FD = "fd";
    public static final String MODE_SD = "sd";

    private static final double PCAR_HOT_WINDOW_SECONDS = 5.0d;
    private static final int PCAR_HOT_EVENT_THRESHOLD = 20;

    // 输入的依赖图（后向切片后的子图）
    DirectedPseudograph<EntityNode, EventEdge> input;
    // 图遍历工具
    IterateGraph graphIter;
    // 压缩后的图
    public DirectedPseudograph<EntityNode, EventEdge> afterMerge;

    /**
     * 构造函数
     * 
     * @param input 输入的依赖图
     */
    public CausalityPreserve(DirectedPseudograph<EntityNode, EventEdge> input) {
        // 深拷贝输入图，避免修改原始数据
        this.input = (DirectedPseudograph<EntityNode, EventEdge>) input.clone();
    }

    /**
     * 四种CPR模式统一入口。
     *
     * @param mode CPR模式名称
     * @param windowSeconds 时间窗口（仅windowed_sequence模式生效）
     * @return 压缩后的图
     */
    public DirectedPseudograph<EntityNode, EventEdge> applyMode(String mode, double windowSeconds) {
        String normalized = mode == null ? MODE_WINDOWED_SEQUENCE : mode.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case MODE_STANDARD_CPR:
            case MODE_CAUSAL_STRICT:
                return applyCausalStrictMode();
            case MODE_FULL_MERGE:
            case MODE_ENDPOINT_AGGREGATION:
                return applyEndpointAggregationMode();
            case MODE_WINDOWED_SEQUENCE:
                return applyWindowedSequenceMode(windowSeconds);
            case MODE_NO_MERGE:
                return applyNoMergeMode();
            case MODE_PCAR:
                return applyPcarMode(windowSeconds, PCAR_HOT_WINDOW_SECONDS, PCAR_HOT_EVENT_THRESHOLD);
            case MODE_FD:
                return applyFdMode();
            case MODE_SD:
                return applySdMode();
            default:
                throw new IllegalArgumentException(
                        "Unsupported cpr_mode: " + mode
                                + ". Supported values: "
                                + MODE_STANDARD_CPR + ", "
                                + MODE_CAUSAL_STRICT + ", "
                                + MODE_FULL_MERGE + ", "
                                + MODE_ENDPOINT_AGGREGATION + ", "
                                + MODE_WINDOWED_SEQUENCE + ", "
                                + MODE_NO_MERGE + ", "
                                + MODE_PCAR + ", "
                                + MODE_FD + ", "
                                + MODE_SD);
        }
    }

    private DirectedPseudograph<EntityNode, EventEdge> applyFdMode() {
        return applyDependencePreservingReduction(false);
    }

    private DirectedPseudograph<EntityNode, EventEdge> applySdMode() {
        return applyDependencePreservingReduction(true);
    }

    /**
     * FD/SD 的系统内实现：
     * 1) FD: 按时间顺序处理事件，若当前图中已存在 u->v 路径，则丢弃该边（REO* 风格冗余消除）。
     * 2) SD: 在 FD 基础上再做源依赖过滤，若 Src(u) ⊆ Src(v) 则丢弃该边。
     */
    // Paper-aligned FD/SD reduction adapted to this project:
    // FD = REO + RNO + 2-node CCO; SD = FD + source-set filtering.
    // The internal decision logic uses lightweight version states, while the
    // exported graph keeps the original EntityNode/EventEdge shape so downstream
    // weighting and provenance analysis continue to work unchanged.
    private DirectedPseudograph<EntityNode, EventEdge> applyDependencePreservingReduction(boolean sourceDependence) {
        DirectedPseudograph<EntityNode, EventEdge> reduced = new DirectedPseudograph<>(EventEdge.class);
        for (EntityNode n : input.vertexSet()) {
            reduced.addVertex(n);
        }

        List<EventEdge> orderedEdges = new ArrayList<>(input.edgeSet());
        orderedEdges.sort((a, b) -> {
            int startCmp = a.getStartTime().compareTo(b.getStartTime());
            if (startCmp != 0) {
                return startCmp;
            }
            int endCmp = a.getEndTime().compareTo(b.getEndTime());
            if (endCmp != 0) {
                return endCmp;
            }
            return Long.compare(a.getID(), b.getID());
        });

        Map<EntityNode, VersionState> latestVersions = initializeVersionStates(input.vertexSet());
        Map<EntityNode, Set<EntityNode>> sourceSets = sourceDependence
                ? initializeSourceSets(input)
                : Collections.emptyMap();

        for (EventEdge edge : orderedEdges) {
            EntityNode source = edge.getSource();
            EntityNode sink = edge.getSink();
            VersionState sourceVersion = latestVersions.get(source);
            VersionState sinkVersion = latestVersions.get(sink);
            boolean reducible = isDependenceReductionEligible(edge);

            if (source.equals(sink)) {
                reduced.addEdge(source, sink, cloneEdge(edge));
                continue;
            }

            if (reducible && formsTwoNodeCycle(sourceVersion, sinkVersion)) {
                continue;
            }

            if (reducible) {
                RelationKey relationKey = new RelationKey(sink, edge.getType());
                EventEdge representative = sourceVersion.getReducibleEdge(relationKey);
                if (representative != null) {
                    representative.merge(edge);
                    continue;
                }
            }

            if (sourceDependence && reducible) {
                Set<EntityNode> sourceAncestors = sourceSets.computeIfAbsent(source, k -> new HashSet<>());
                Set<EntityNode> sinkAncestors = sourceSets.computeIfAbsent(sink, k -> new HashSet<>());
                if (sinkAncestors.containsAll(sourceAncestors)) {
                    continue;
                }
            }

            VersionState targetVersion = advanceTargetVersion(sinkVersion, edge);
            latestVersions.put(sink, targetVersion);

            EventEdge keptEdge = cloneEdge(edge);
            reduced.addEdge(source, sink, keptEdge);
            sourceVersion.addOutgoing(targetVersion, sink);

            if (reducible) {
                sourceVersion.registerReducibleEdge(new RelationKey(sink, edge.getType()), keptEdge);
            }

            if (sourceDependence) {
                Set<EntityNode> sourceAncestors = sourceSets.computeIfAbsent(source, k -> new HashSet<>());
                Set<EntityNode> sinkAncestors = sourceSets.computeIfAbsent(sink, k -> new HashSet<>());
                sinkAncestors.addAll(sourceAncestors);
            }
        }

        afterMerge = reduced;
        return afterMerge;
    }

    private Map<EntityNode, Set<EntityNode>> initializeSourceSets(
            DirectedPseudograph<EntityNode, EventEdge> graph) {
        Map<EntityNode, Set<EntityNode>> sourceSets = new HashMap<>();
        for (EntityNode node : graph.vertexSet()) {
            Set<EntityNode> seeds = new HashSet<>();
            if (graph.incomingEdgesOf(node).isEmpty()) {
                seeds.add(node);
            }
            sourceSets.put(node, seeds);
        }
        return sourceSets;
    }

    private Map<EntityNode, VersionState> initializeVersionStates(Set<EntityNode> nodes) {
        Map<EntityNode, VersionState> latestVersions = new HashMap<>();
        for (EntityNode node : nodes) {
            latestVersions.put(node, new VersionState(node, BigDecimal.ZERO));
        }
        return latestVersions;
    }

    private boolean isDependenceReductionEligible(EventEdge edge) {
        return !(edge.getSource().isProcessNode() && edge.getSink().isProcessNode());
    }

    private boolean formsTwoNodeCycle(VersionState sourceVersion, VersionState sinkVersion) {
        return sinkVersion.directlyTargets(sourceVersion.entity);
    }

    private VersionState advanceTargetVersion(VersionState currentVersion, EventEdge edge) {
        if (!currentVersion.hasDescendants()) {
            currentVersion.extendTo(edge.getEndTime());
            return currentVersion;
        }

        VersionState nextVersion = new VersionState(currentVersion.entity, edge.getEndTime());
        currentVersion.addVersionSuccessor(nextVersion);
        return nextVersion;
    }

    private EventEdge cloneEdge(EventEdge edge) {
        return new EventEdge(edge, edge.getSource(), edge.getSink(), edge.getID());
    }

    private DirectedPseudograph<EntityNode, EventEdge> applyNoMergeMode() {
        afterMerge = input;
        return afterMerge;
    }

    /*
     * This merge method preserves the causal relationship among different event
     * types, so it needs to consider both the time window and the event type.
     */
    private DirectedPseudograph<EntityNode, EventEdge> applyCausalStrictMode() {
        DirectedPseudograph<EntityNode, EventEdge> merged = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
        for (EntityNode n : input.vertexSet()) {
            merged.addVertex(n);
        }

        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new LinkedList<>(edgeSet);
        Collections.sort(edgeList, (a, b) -> a.getStartTime().compareTo(b.getStartTime()));
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
                if (backwardCheck(edgePrevious, cur, source)
                        && forwardCheck(edgePrevious, cur, target)) {
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
                    for (EventEdge e : s) {
                        merged.addEdge(source, sink, e);
                    }
                }
            }
        }

        afterMerge = merged;
        return afterMerge;
    }

    /**
     * mergeEdgeFallInTheRange2 - 基于时间窗口的边合并方法（优化版本）
     * 
     * 这是实际使用的方法，通过时间窗口控制边的合并。
     * 算法流程：
     * 1. 将所有边按开始时间排序
     * 2. 对每对(源节点, 目标节点, 事件类型)，维护一个栈
     * 3. 依次处理边：如果与栈顶边的间隔小于指定时间窗口，则合并；否则新建一条边
     * 4. 最后将合并后的边添加到新图中
     * 
     * 优化点：
     * - 不修改原始图，而是在新图中添加合并后的边
     * - 使用栈结构维护待合并的边
     * 
     * @param range 时间窗口大小（单位：秒），默认10秒
     * @return 合并后的新图
     */
    // build a new graph rather than deleting edges in the original one, better
    // performance
    public DirectedPseudograph<EntityNode, EventEdge> applyWindowedSequenceMode(double range) {
        DirectedPseudograph<EntityNode, EventEdge> merged = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
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

    /**
     * PCAR (Process-centric Causality Approximation Reduction)
     *
     * 实现策略：
     * 1. 先执行标准CPR，保证基础因果保真。
     * 2. 在CPR结果中检测hot process（默认5秒内>=20条事件）。
     * 3. 对hot process突发区间内、且与hot process直接相连的边，执行近似窗口聚合。
     *
     * 该策略会在iBurst场景下进一步压缩边数，同时把近似误差局限在hot process邻域。
     */
    private DirectedPseudograph<EntityNode, EventEdge> applyPcarMode(double ignoredMergeWindowSeconds,
            double hotWindowSeconds,
            int hotEventThreshold) {
        DirectedPseudograph<EntityNode, EventEdge> cprGraph = applyCausalStrictMode();
        Map<EntityNode, List<TimeInterval>> burstIntervals = detectHotProcessBursts(cprGraph, hotWindowSeconds,
                hotEventThreshold);
        if (burstIntervals.isEmpty()) {
            afterMerge = cprGraph;
            return afterMerge;
        }

        List<BurstTask> tasks = new ArrayList<>();
        for (Map.Entry<EntityNode, List<TimeInterval>> entry : burstIntervals.entrySet()) {
            for (TimeInterval interval : entry.getValue()) {
                tasks.add(new BurstTask(entry.getKey(), interval));
            }
        }
        tasks.sort((a, b) -> a.interval.start.compareTo(b.interval.start));

        for (BurstTask task : tasks) {
            applyPcarForBurst(cprGraph, task.hotProcess, task.interval);
        }

        afterMerge = cprGraph;
        return afterMerge;
    }

    // Merge all edges without considering the time window or event type.
    private DirectedPseudograph<EntityNode, EventEdge> applyEndpointAggregationMode() {
        Map<EntityNode, Map<EntityNode, EventEdge>> map = new HashMap<>();
        Set<EventEdge> edgeSet = input.edgeSet();
        List<EventEdge> edgeList = new ArrayList<>(edgeSet);
        Collections.sort(edgeList, (a, b) -> a.getEndTime().compareTo(b.getEndTime()));

        for (EventEdge e : edgeList) {
            EntityNode source = e.getSource();
            EntityNode target = e.getSink();
            e.setEdgeEvent("NullAfterMerge");
            if (!map.containsKey((source))) {
                map.put(source, new HashMap<EntityNode, EventEdge>());
            }
            if (map.get(source).containsKey(target)) {
                EventEdge previous = map.get(source).get(target);
                previous = merge(previous, e);
                map.get(source).put(target, previous);
            } else {
                map.get(source).put(target, e);
            }
        }
        afterMerge = input;
        return afterMerge;

    }

    private EventEdge merge(EventEdge previous, EventEdge cur) {
        previous = previous.merge(cur);
        input.removeEdge(cur);
        return previous;
    }

    private Map<EntityNode, List<TimeInterval>> detectHotProcessBursts(
            DirectedPseudograph<EntityNode, EventEdge> graph,
            double hotWindowSeconds,
            int hotEventThreshold) {
        Map<EntityNode, List<EventEdge>> incidentByProcess = new HashMap<>();
        for (EventEdge edge : graph.edgeSet()) {
            if (edge.getSource().isProcessNode()) {
                incidentByProcess.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
            }
            if (edge.getSink().isProcessNode()) {
                incidentByProcess.computeIfAbsent(edge.getSink(), k -> new ArrayList<>()).add(edge);
            }
        }

        BigDecimal window = new BigDecimal(hotWindowSeconds);
        Map<EntityNode, List<TimeInterval>> burstIntervals = new HashMap<>();

        for (Map.Entry<EntityNode, List<EventEdge>> entry : incidentByProcess.entrySet()) {
            List<EventEdge> edges = entry.getValue();
            edges.sort(Comparator.comparing(EventEdge::getStartTime));
            Deque<EventEdge> queue = new ArrayDeque<>();
            List<TimeInterval> intervals = new ArrayList<>();

            for (EventEdge current : edges) {
                queue.addLast(current);
                while (!queue.isEmpty()) {
                    BigDecimal span = current.getStartTime().subtract(queue.peekFirst().getStartTime());
                    if (span.compareTo(window) > 0) {
                        queue.removeFirst();
                    } else {
                        break;
                    }
                }

                if (queue.size() >= hotEventThreshold) {
                    BigDecimal intervalStart = queue.peekFirst().getStartTime();
                    BigDecimal intervalEnd = current.getStartTime();
                    appendOrMergeInterval(intervals, new TimeInterval(intervalStart, intervalEnd));
                }
            }

            if (!intervals.isEmpty()) {
                burstIntervals.put(entry.getKey(), intervals);
            }
        }

        return burstIntervals;
    }

    private void applyPcarForBurst(DirectedPseudograph<EntityNode, EventEdge> graph,
            EntityNode hotProcess,
            TimeInterval burstInterval) {
        Set<EntityNode> egoNet = buildEgoNet(graph, hotProcess, burstInterval);
        if (egoNet.isEmpty()) {
            return;
        }

        List<EventEdge> stream = collectEgoStream(graph, egoNet, burstInterval);
        if (stream.isEmpty()) {
            return;
        }

        BigDecimal inDeadline = null;
        BigDecimal outDeadline = null;
        Map<AggregableKey, Deque<EventEdge>> stacks = new HashMap<>();

        for (EventEdge edge : stream) {
            if (!graph.containsEdge(edge)) {
                continue;
            }

            boolean srcIn = egoNet.contains(edge.getSource());
            boolean dstIn = egoNet.contains(edge.getSink());

            if (!srcIn && dstIn) {
                inDeadline = edge.getEndTime();
                continue;
            }
            if (srcIn && !dstIn) {
                outDeadline = edge.getEndTime();
                continue;
            }
            if (!srcIn) {
                continue;
            }

            clearStateForPcarEdge(graph, hotProcess, edge, inDeadline, outDeadline, stacks);
        }
    }

    private Set<EntityNode> buildEgoNet(DirectedPseudograph<EntityNode, EventEdge> graph,
            EntityNode hotProcess,
            TimeInterval interval) {
        Set<EntityNode> egoNet = new HashSet<>();
        egoNet.add(hotProcess);

        for (EventEdge edge : graph.edgeSet()) {
            if (!overlaps(edge, interval)) {
                continue;
            }
            if (edge.getSource().equals(hotProcess)) {
                egoNet.add(edge.getSink());
            }
            if (edge.getSink().equals(hotProcess)) {
                egoNet.add(edge.getSource());
            }
        }

        return egoNet;
    }

    private List<EventEdge> collectEgoStream(DirectedPseudograph<EntityNode, EventEdge> graph,
            Set<EntityNode> egoNet,
            TimeInterval interval) {
        List<EventEdge> stream = new ArrayList<>();
        for (EventEdge edge : graph.edgeSet()) {
            if (!overlaps(edge, interval)) {
                continue;
            }
            if (egoNet.contains(edge.getSource()) || egoNet.contains(edge.getSink())) {
                stream.add(edge);
            }
        }
        stream.sort((a, b) -> {
            int startCmp = a.getStartTime().compareTo(b.getStartTime());
            if (startCmp != 0) {
                return startCmp;
            }
            return a.getEndTime().compareTo(b.getEndTime());
        });
        return stream;
    }

    private void clearStateForPcarEdge(DirectedPseudograph<EntityNode, EventEdge> graph,
            EntityNode hotProcess,
            EventEdge edge,
            BigDecimal inDeadline,
            BigDecimal outDeadline,
            Map<AggregableKey, Deque<EventEdge>> stacks) {
        AggregableKey key = new AggregableKey(edge.getSource(), edge.getSink(), edge.getEvent());
        Deque<EventEdge> stack = stacks.computeIfAbsent(key, k -> new ArrayDeque<>());

        if (stack.isEmpty()) {
            stack.push(edge);
            return;
        }

        EventEdge previous = stack.pop();
        boolean mergeAllowed;
        if (edge.getSource().equals(hotProcess)) {
            mergeAllowed = pcarCheck(graph, previous, edge, inDeadline, true);
        } else if (edge.getSink().equals(hotProcess)) {
            mergeAllowed = pcarCheck(graph, previous, edge, outDeadline, false);
        } else {
            mergeAllowed = true;
        }

        if (mergeAllowed) {
            previous.merge(edge);
            graph.removeEdge(edge);
            stack.push(previous);
            return;
        }

        stack.push(previous);
        stack.push(edge);
    }

    private boolean pcarCheck(DirectedPseudograph<EntityNode, EventEdge> graph,
            EventEdge previous,
            EventEdge current,
            BigDecimal deadline,
            boolean isOutDirection) {
        if (deadline != null && deadline.compareTo(previous.getEndTime()) > 0) {
            return false;
        }

        if (isOutDirection) {
            return forwardCheck(graph, previous, current, previous.getSink());
        }
        return backwardCheck(graph, previous, current, previous.getSource());
    }

    private boolean overlaps(EventEdge edge, TimeInterval interval) {
        return edge.getEndTime().compareTo(interval.start) >= 0
                && edge.getStartTime().compareTo(interval.end) <= 0;
    }

    private void appendOrMergeInterval(List<TimeInterval> intervals, TimeInterval candidate) {
        if (intervals.isEmpty()) {
            intervals.add(candidate);
            return;
        }

        TimeInterval last = intervals.get(intervals.size() - 1);
        if (candidate.start.compareTo(last.end) <= 0) {
            if (candidate.end.compareTo(last.end) > 0) {
                last.end = candidate.end;
            }
        } else {
            intervals.add(candidate);
        }
    }

    private static final class TimeInterval {
        private final BigDecimal start;
        private BigDecimal end;

        private TimeInterval(BigDecimal start, BigDecimal end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class BurstTask {
        private final EntityNode hotProcess;
        private final TimeInterval interval;

        private BurstTask(EntityNode hotProcess, TimeInterval interval) {
            this.hotProcess = hotProcess;
            this.interval = interval;
        }
    }

    private static final class AggregableKey {
        private final EntityNode source;
        private final EntityNode sink;
        private final String event;

        private AggregableKey(EntityNode source, EntityNode sink, String event) {
            this.source = source;
            this.sink = sink;
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AggregableKey)) {
                return false;
            }
            AggregableKey that = (AggregableKey) o;
            return Objects.equals(source, that.source)
                    && Objects.equals(sink, that.sink)
                    && Objects.equals(event, that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, sink, event);
        }
    }

    private static final class RelationKey {
        private final EntityNode sink;
        private final String type;

        private RelationKey(EntityNode sink, String type) {
            this.sink = sink;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RelationKey)) {
                return false;
            }
            RelationKey that = (RelationKey) o;
            return Objects.equals(sink, that.sink) && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sink, type);
        }
    }

    private static final class VersionState {
        private final EntityNode entity;
        private final BigDecimal start;
        private BigDecimal end;
        private final Set<VersionState> descendants = new HashSet<>();
        private final Set<EntityNode> directTargets = new HashSet<>();
        private final Map<RelationKey, EventEdge> reducibleEdges = new HashMap<>();

        private VersionState(EntityNode entity, BigDecimal timepoint) {
            this.entity = entity;
            this.start = timepoint;
            this.end = timepoint;
        }

        private boolean hasDescendants() {
            return !descendants.isEmpty();
        }

        private void extendTo(BigDecimal timestamp) {
            if (timestamp.compareTo(end) > 0) {
                end = timestamp;
            }
        }

        private void addVersionSuccessor(VersionState nextVersion) {
            descendants.add(nextVersion);
        }

        private void addOutgoing(VersionState targetVersion, EntityNode targetEntity) {
            descendants.add(targetVersion);
            directTargets.add(targetEntity);
        }

        private boolean directlyTargets(EntityNode targetEntity) {
            return directTargets.contains(targetEntity);
        }

        private EventEdge getReducibleEdge(RelationKey key) {
            return reducibleEdges.get(key);
        }

        private void registerReducibleEdge(RelationKey key, EventEdge edge) {
            reducibleEdges.putIfAbsent(key, edge);
        }
    }

    private Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> initializePairStack(Set<EventEdge> edges) {
        Map<String, Map<EntityNode, Map<EntityNode, Stack<EventEdge>>>> map = new HashMap<>();

        for (EventEdge e : edges) {
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

    private DirectedPseudograph<EntityNode, EventEdge> getCPR(
            Map<EntityNode, Map<EntityNode, Deque<EventEdge>>> mapOfStacks) {
        DirectedPseudograph<EntityNode, EventEdge> res = new DirectedPseudograph<EntityNode, EventEdge>(
                EventEdge.class);
        for (EntityNode u : mapOfStacks.keySet()) {
            Map<EntityNode, Deque<EventEdge>> cur = mapOfStacks.get(u);
            for (EntityNode v : cur.keySet()) {
                res.addVertex(u);
                res.addVertex(v);
                while (!cur.get(v).isEmpty()) {
                    EventEdge edge = cur.get(v).pop();
                    res.addEdge(u, v, edge);
                }
            }
        }
        return res;
    }

    private boolean backwardCheck(EventEdge p, EventEdge l, EntityNode u) {
        return backwardCheck(input, p, l, u);
    }

    private boolean backwardCheck(DirectedPseudograph<EntityNode, EventEdge> graph,
            EventEdge p,
            EventEdge l,
            EntityNode u) {
        BigDecimal gapStart = p.getEndTime();
        BigDecimal gapEnd = l.getEndTime();
        if (gapEnd.compareTo(gapStart) <= 0) {
            return true;
        }

        Set<EventEdge> incoming = graph.incomingEdgesOf(u);
        for (EventEdge edge : incoming) {
            if (edge == p || edge == l) {
                continue;
            }
            if (isBetween(edge.getStartTime(), gapStart, gapEnd)
                    || isBetween(edge.getEndTime(), gapStart, gapEnd)
                    || (edge.getStartTime().compareTo(gapStart) <= 0
                            && edge.getEndTime().compareTo(gapEnd) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private boolean forwardCheck(EventEdge p, EventEdge l, EntityNode v) {
        return forwardCheck(input, p, l, v);
    }

    private boolean forwardCheck(DirectedPseudograph<EntityNode, EventEdge> graph,
            EventEdge p,
            EventEdge l,
            EntityNode v) {
        BigDecimal gapStart = p.getStartTime();
        BigDecimal gapEnd = l.getStartTime();
        if (gapEnd.compareTo(gapStart) <= 0) {
            return true;
        }

        Set<EventEdge> outgoing = graph.outgoingEdgesOf(v);
        for (EventEdge edge : outgoing) {
            if (edge == p || edge == l) {
                continue;
            }
            if (isBetween(edge.getStartTime(), gapStart, gapEnd)
                    || isBetween(edge.getEndTime(), gapStart, gapEnd)
                    || (edge.getStartTime().compareTo(gapStart) <= 0
                            && edge.getEndTime().compareTo(gapEnd) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBetween(BigDecimal time, BigDecimal startExclusive, BigDecimal endExclusive) {
        return time.compareTo(startExclusive) > 0 && time.compareTo(endExclusive) < 0;
    }

    private boolean isOverlap(BigDecimal[] a, BigDecimal[] b) {
        // if(a[1].compareTo(b[0])>=0 && a[1].compareTo(b[1])<=0 ||
        // a[0].compareTo(b[0])>=0 && a[0].compareTo(b[1])<=0){
        // return true;
        // }
        // return false;
        if (a[1].compareTo(b[0]) < 0 || a[0].compareTo(b[1]) > 0) {
            return false;
        }
        return true;
    }
}
