package logparsers;

import logparsers.exceptions.UnknownEventException;
import logparsers.systemcalls.Fingerprint;
import logparsers.systemcalls.SystemCall;
import logparsers.systemcalls.SystemCallFactory;
import pagerank.Process;
import pagerank.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SysdigOutputParserNoRegex implements SysdigOutputParser {
    public static void main(String[] args) {
        try {
            SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("data/attack_log/cmd-inject.log", MetaConfig.localIP);
            parser.getEntities();

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private File log;
    private Set<String> localIP;
    private long UID;
    private double repu = 0.0;
    private int hops = 0;

    private HashMap<String, FileEntity> files;
    private HashMap<String, Process> processes;
    private HashMap<String, NetworkEntity> networks;

    private HashMap<String, PtoFEvent> pfEvent; //keys are all timestamps:event:cwd
    private HashMap<String, PtoNEvent> pnEvent;
    private HashMap<String, PtoPEvent> ppEvent;
    private HashMap<String, NtoPEvent> npEvent;
    private HashMap<String, FtoPEvent> fpEvent;

    private Map<Fingerprint, SystemCall> answering;

    private Map<String, Map<String, String>> incompleteEvents; //key is timestamp:event:cwd
    private Map<String, PtoPEvent> backFlow; //key is pid+process
    private Map<String, PtoPEvent> forwardFlow; //key is pid+process
    private static final Pattern pParent = Pattern.compile("ptid=(?<parentPID>\\d+)\\((?<parent>.+?)\\)");

    public SysdigOutputParserNoRegex(String pathToLog, String[] localIP) {
        //localIP：本地主机的 IP 地址数组。用于区分内部通信与外部通信（如识别入站/出站流量）
        // 初始化各种事件存储映射
        files = new HashMap<>();           // 文件实体映射
        processes = new HashMap<>();        // 进程实体映射
        networks = new HashMap<>();         // 网络实体映射

        pfEvent = new HashMap<>();          // 进程→文件事件
        pnEvent = new HashMap<>();          // 进程→网络事件
        ppEvent = new HashMap<>();          // 进程间事件
        npEvent = new HashMap<>();          // 网络→进程事件
        fpEvent = new HashMap<>();          // 文件→进程事件

        incompleteEvents = new HashMap<>(); // 未完成事件
        backFlow = new HashMap<>();         // 反向进程流
        forwardFlow = new HashMap<>();      // 正向进程流
        answering = new HashMap<>();        // 系统调用处理器映射

        log = new File(pathToLog);
        UID = 0;// 实体唯一ID计数器
        this.localIP = new HashSet<>();
        this.localIP.addAll(Arrays.asList(localIP));

        registerSystemCalls();// 注册系统调用处理器
    }

    // [新增] 辅助方法：从 Map 中提取异常分数
    // 假设 Utils 解析出的 Map 中包含 "anomaly_score" 字段
    private double extractAnomalyScore(Map<String, String> m) {
        if (m == null) return 0.0;
        try {
            // 优先尝试获取 "anomaly_score"
            if (m.containsKey("anomaly_score")) {
                return Double.parseDouble(m.get("anomaly_score"));
            }
            // 也可以根据需要检查 args 是否包含类似 "score=0.9" 的字样并解析
            // 目前默认返回 0.0
            return 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // registerSystemCalls()方法注册了所有系统调用处理器：
    private void registerSystemCalls() {
        //FtoP
        SystemCallFactory f2pSystemCall = new SystemCallFactory("FtoP", FileEntity.class, null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addF2PEvent);
        for (String s : MetaConfig.ftopSystemCall) {
            SystemCall systemCall = f2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint, systemCall);
        }

        //PtoF
        SystemCallFactory p2fSystemCall = new SystemCallFactory("PtoF", FileEntity.class, null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2FEvent);
        for (String s : MetaConfig.ptofSystemCall) {
            SystemCall systemCall = p2fSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint, systemCall);
        }

        //NtoP
        SystemCallFactory n2pSystemCall = new SystemCallFactory("NtoP", NetworkEntity.class, null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addN2PEvent);
        for (String s : MetaConfig.ntopSystemCall) {
            SystemCall systemCall = n2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint, systemCall);
        }

        //PtoN
        SystemCallFactory p2nSystemCall = new SystemCallFactory("PtoN", NetworkEntity.class, null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2NEvent);
        for (String s : MetaConfig.ptonSystemCall) {
            SystemCall systemCall = p2nSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint, systemCall);
        }

        //execve
        SystemCall execve = new SystemCall("execve", "PtoP",
                new Fingerprint("execve", FileEntity.class, null)).addAction((mStart, mEnd, eStart, eEnd) -> {
            Process pStart = (Process) eStart[0];
            Process pEnd = (Process) eEnd[0];
            FileEntity f = (FileEntity) eStart[1];

            String timestampStart = mStart.get("timestamp");
            String timestampEnd = mEnd.get("timestamp");
            String cwd = mStart.get("cwd");
            String key = timestampStart + ":execve:" + cwd;

            String[] timestampsStart = timestampStart.split("\\.");
            String[] timestampsEnd = timestampEnd.split("\\.");
            // [修改] 提取异常分数 (使用开始事件的分数)
            double anomalyScore = Math.max(
                    extractAnomalyScore(mStart),
                    extractAnomalyScore(mEnd)
            );

            String args = mEnd.get("args");
            if (!args.contains("res=0"))
                return;

            if (!f.getPath().equals("<NA>")) {
                // [修改] 传入 anomalyScore
                FtoPEvent fp = new FtoPEvent(timestampsStart[0], timestampsStart[1],
                        f, pEnd, "execve", 0, 0, anomalyScore);

                fp.setEndTime(timestampStart);
                fpEvent.put(key, fp);
            }

            Matcher mParent = pParent.matcher(args);

            if (mParent.find()) {
                String pidParent = mParent.group("parentPID");
                String nameParent = mParent.group("parent");
                String keyParent = pidParent + nameParent;
                Process parent = processes.computeIfAbsent(keyParent, k -> new Process(repu, -1, hops, pidParent,
                        null, null, null,
                        timestampsStart[0], timestampsStart[1],
                        nameParent, UID++));
                // [修改] 传入 anomalyScore
                PtoPEvent forwardLink = new PtoPEvent(timestampsStart[0], timestampsStart[1], parent, pEnd, "execve",
                        0, anomalyScore);
                forwardLink.setEndTime(timestampEnd);

                ppEvent.put(key, forwardLink);
                forwardFlow.put(keyParent, forwardLink);

                // [修改] 传入 anomalyScore
                PtoPEvent backLink = new PtoPEvent(timestampsStart[0], timestampsStart[1], pEnd, parent, "execve",
                        0, anomalyScore);
                backLink.setEndTime(timestampEnd);

                backFlow.put(mEnd.get("pid") + mEnd.get("process"), backLink);
                ppEvent.put(key + "back", backLink);
            }


        });
        answering.put(execve.fingerPrint, execve);

        //accept
        SystemCall accept = new SystemCall("accept", "NtoP", new Fingerprint("accept", null, NetworkEntity.class)).addAction((mStart, mEnd, entitiesStart, entitiesEnd) -> {
            Process p = (Process) entitiesEnd[0];
            NetworkEntity n = (NetworkEntity) entitiesEnd[1];

            String timestamp = mEnd.get("timestamp");
            String cwd = mEnd.get("cwd");

            String key = timestamp + ":accept:" + cwd;

            String[] timestamps = timestamp.split("\\.");

            // [修改] 提取异常分数 (accept 通常看 mEnd，因为是返回时才建立连接，这里取 mStart 或 mEnd 均可，保持一致取 mStart)
            double anomalyScore = Math.max(
                    extractAnomalyScore(mStart),
                    extractAnomalyScore(mEnd)
            );

            // [修改] 传入 anomalyScore
            NtoPEvent np = new NtoPEvent(timestamps[0], timestamps[1],
                    n, p, mEnd.get("event"), 0, 0, anomalyScore);
            np.setEndTime(timestamp);

            // [修改] 传入 anomalyScore
            PtoNEvent pn = new PtoNEvent(timestamps[0], timestamps[1],
                    p, n, mEnd.get("event"), 0, 0, anomalyScore);
            pn.setEndTime(timestamp);

            npEvent.put(key, np);
            pnEvent.put(key, pn);
        });
        answering.put(accept.fingerPrint, accept);

        //fcntl
        SystemCall fcntl = new SystemCall("fcntl", "NtoP", new Fingerprint("fcntl", null, NetworkEntity.class)).addAction((mStart, mEnd, entitiesStart, entitiesEnd) -> {
            String timestampStart = mStart.get("timestamp");
            String event = mStart.get("event");
            String cwd = mStart.get("cwd");
            String key = timestampStart + ":" + event + ":" + cwd;
            String[] timestampsStart = timestampStart.split("\\.");
            String timestampEnd = mEnd.get("timestamp");

            Process p = (Process) entitiesEnd[0];
            NetworkEntity n = (NetworkEntity) entitiesEnd[1];

            // [修改] 提取异常分数
            double anomalyScore = Math.max(
                    extractAnomalyScore(mStart),
                    extractAnomalyScore(mEnd)
            );

            // [修改] 传入 anomalyScore
            NtoPEvent np = new NtoPEvent(timestampsStart[0], timestampsStart[1],
                    n, p, mStart.get("event"), 0, 0, anomalyScore);
            np.setEndTime(timestampEnd);
            npEvent.put(key, np);
        });
        answering.put(fcntl.fingerPrint, fcntl);

        //rename
        SystemCall rename = new SystemCall("rename", "PtoF", new Fingerprint("rename", null, null)).addAction((mStart, mEnd, entitiesStart, entitiesEnd) -> {
            String timestampStart = mStart.get("timestamp");
            String timestampEnd = mEnd.get("timestamp");

            String[] timestampsStart = timestampStart.split("\\.");

            String event = mEnd.get("event");
            String args = mEnd.get("args");
            String cwd = mEnd.get("cwd");

            String oldPath = args.substring(args.indexOf("oldpath=") + 8, args.lastIndexOf(" newpath"));
            String newPath = args.substring(args.indexOf("newpath=") + 8, args.lastIndexOf(" "));

            if (oldPath.endsWith(")")) oldPath = oldPath.substring(oldPath.indexOf("(") + 1, oldPath.length() - 1);
            if (newPath.endsWith(")")) newPath = newPath.substring(newPath.indexOf("(") + 1, newPath.length() - 1);

            final String realOldPath = oldPath;
            final String realNewPath = newPath;

            Process p = (Process) entitiesStart[0];
            String key = timestampStart + ":" + event + ":" + cwd;

            // [修改] 提取异常分数
            double anomalyScore = Math.max(
                    extractAnomalyScore(mStart),
                    extractAnomalyScore(mEnd)
            );

            FileEntity oldFile = files.computeIfAbsent(oldPath, k -> new FileEntity(repu, 0L, hops, timestampsStart[0],
                    timestampsStart[1], null, null, realOldPath, UID++));
            // [修改] 传入 anomalyScore
            FtoPEvent fp = new FtoPEvent(timestampsStart[0], timestampsStart[1],
                    oldFile, p, mStart.get("event"), 0, 0, anomalyScore);
            fp.setEndTime(timestampEnd);
            fpEvent.put(key, fp);

            FileEntity newFile = files.computeIfAbsent(newPath, k -> new FileEntity(repu, 0L, hops, timestampsStart[0],
                    timestampsStart[1], null, null, realNewPath, UID++));
            // [修改] 传入 anomalyScore
            PtoFEvent pf = new PtoFEvent(timestampsStart[0], timestampsStart[1],
                    p, newFile, mStart.get("event"), 0, 0, anomalyScore);
            pf.setEndTime(timestampEnd);
            pfEvent.put(key, pf);
        });
        answering.put(rename.fingerPrint, rename);
    }

    public void getEntities() throws IOException {
        System.out.println("Parsing...");
        long start = System.currentTimeMillis();
        //dependencyGraph = new DirectedPseudograph<pagerank.EntityNode, pagerank.EventEdge>(pagerank.EventEdge.class);
        BufferedReader logReader = new BufferedReader(new FileReader(log), 1048576);
        String currentLine;
        while ((currentLine = logReader.readLine()) != null) {
            // 解析日志行
            //todo修改调用的utils函数
            Map matcher = Utils.parseEntryWithoutIDNew(currentLine);
            if (!matcher.isEmpty()) {
                if (matcher.get("direction").equals(">")) {
                    // 开始事件，存入未完成事件映射
                    incompleteEvents.put(matcher.get("timestamp") + ":" + matcher.get("event") + ":" + matcher.get("cwd"), matcher);
                } else {
                    try {
                        // 结束事件，处理完整事件
                        processEvent(matcher);
                    } catch (Exception e) {
//                        e.printStackTrace();
//                        System.out.println(e.getMessage());
                    }
                }
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("Parsing(in parser) time Cost:" + (end - start) / 1000.0);
    }

    @Override
    public void afterBuilding() {
    }

    private void processEvent(Map<String, String> end) throws UnknownEventException {
        String startTimestamp = new BigDecimal(end.get("timestamp"))
                .subtract(new BigDecimal(end.get("latency")).scaleByPowerOfTen(-9))
                .toString();
        String key = startTimestamp + ":" + end.get("event") + ":" + end.get("cwd");
        Map start;
        if (!incompleteEvents.containsKey(key)){
            // 严格按照你提供的 13 个字段顺序拼接：
            // 0:evt.time, 1:evt.cpu, 2:proc.name, 3:proc.pid, 4:evt.dir, 5:evt.type, 6:proc.cwd,
            // 7:evt.latency, 8:evt.args, 9:proc.cmdline, 10:proc.pcmdline, 11:extracted_object, 12:anomaly_score

            String dummyEntry = String.format("%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s;;%s",
                    startTimestamp,           // 0: evt.time
                    end.get("cpu"),           // 1: evt.cpu
                    end.get("process"),       // 2: proc.name
                    end.get("pid"),           // 3: proc.pid
                    ">",                      // 4: evt.dir (设为开始方向)
                    end.get("event"),         // 5: evt.type
                    end.get("cwd"),           // 6: proc.cwd
                    "0",                      // 7: evt.latency (伪造事件延迟为0)
                    "!dummy!",                // 8: evt.args
                    end.get("process"),       // 9: proc.cmdline (占位)
                    "none",                   // 10: proc.pcmdline (占位)
                    "none",                   // 11: extracted_object (占位)
                    "0.0"                     // 12: anomaly_score (伪造事件权重为0)
            );
            //todo修改调用的utils函数
            start = Utils.parseEntryWithoutIDNew(dummyEntry);
        } else {
            start = incompleteEvents.remove(key);
        }

        Entity[] startEntites = extractEntities(start);
        Entity[] endEntities = extractEntities(end);

        // Fingerprint = (系统调用名, 进入时的实体类型, 返回时的实体类型),它的作用是唯一标识一种事件语义。
        //例如：
        //open()：("open", Process, FileEntity)
        //read()：("read", Process, FileEntity)
        //accept()：("accept", null, NetworkEntity)
        //execve()：("execve", FileEntity, null)
        Fingerprint f = Fingerprint.toFingerPrint(start, end, startEntites, endEntities);
        // answering 是一个全局 Map，键是指纹（Fingerprint），值是该指纹对应的处理逻辑（SystemCall）
        SystemCall systemCall = answering.getOrDefault(f, null);
        if (systemCall == null) {
            throw new UnknownEventException("Unknown event: " + start.get("event"));
        } else {
            systemCall.react(start, end, startEntites, endEntities);
        }
        //todo：莫名的错误输出
//        if (start.get("args").equals("!dummy!"))
//            System.out.println("Event enter point not seen: " + end.get("raw"));
    }

    public void updateP2PLinks(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd) {
        String endTime = mEnd.get("timestamp");
        if (backFlow.containsKey(mEnd.get("pid") + mEnd.get("process"))) {
            PtoPEvent bf = backFlow.get(mEnd.get("pid") + mEnd.get("process"));
            if (new BigDecimal(bf.getEnd()).compareTo(new BigDecimal(endTime)) < 0) {
                bf.setEndTime(endTime);
            }
//            System.out.println(bf.getEnd());
        }
        if (forwardFlow.containsKey(mEnd.get("pid") + mEnd.get("process"))) {
            PtoPEvent ff = forwardFlow.get(mEnd.get("pid") + mEnd.get("process"));
            if (new BigDecimal(ff.getEnd()).compareTo(new BigDecimal(endTime)) < 0) {
                ff.setEndTime(endTime);
            }
//            System.out.println(ff.getEnd());
        }
    }

    private void addP2FEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd) {
        Process p = (Process) entitiesStart[0];
        FileEntity f = (FileEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = timestampStart + ":" + event + ":" + cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);

        // [修改] 提取异常分数
        double anomalyScore = Math.max(
                extractAnomalyScore(mStart),
                extractAnomalyScore(mEnd)
        );

        if (size != -1L) {
            // [修改] 传入 anomalyScore
            PtoFEvent pf = new PtoFEvent(timestampsStart[0], timestampsStart[1],
                    p, f, mStart.get("event"), size, 0, anomalyScore);
            pf.setEndTime(timestampEnd);
            pfEvent.put(key, pf);
        }
    }

    private void addF2PEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd) {
        Process p = (Process) entitiesStart[0];
        FileEntity f = (FileEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = timestampStart + ":" + event + ":" + cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        // [修改] 提取异常分数
        double anomalyScore = Math.max(
                extractAnomalyScore(mStart),
                extractAnomalyScore(mEnd)
        );
        if (size != -1L) {
            // [修改] 传入 anomalyScore
            FtoPEvent fp = new FtoPEvent(timestampsStart[0], timestampsStart[1],
                    f, p, mStart.get("event"), size, 0, anomalyScore);
            fp.setEndTime(timestampEnd);
            fpEvent.put(key, fp);
        }
    }

    private void addP2NEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd) {
        Process p = (Process) entitiesStart[0];
        NetworkEntity n = (NetworkEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = timestampStart + ":" + event + ":" + cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        // [修改] 提取异常分数
        double anomalyScore = Math.max(
                extractAnomalyScore(mStart),
                extractAnomalyScore(mEnd)
        );
        if (size != -1L) {
            // [修改] 传入 anomalyScore
            PtoNEvent pn = new PtoNEvent(timestampsStart[0], timestampsStart[1],
                    p, n, mStart.get("event"), size, 0, anomalyScore);
            pn.setEndTime(timestampEnd);
            pnEvent.put(key, pn);
        }
    }

    private void addN2PEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd) {
        String args = mEnd.get("args");

        Process p = (Process) entitiesStart[0];
        NetworkEntity n = (NetworkEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = timestampStart + ":" + event + ":" + cwd;
        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        long size = Utils.extractSize(args);
        // [修改] 提取异常分数
        double anomalyScore = Math.max(
                extractAnomalyScore(mStart),
                extractAnomalyScore(mEnd)
        );
        if (size != -1L) {
            // [修改] 传入 anomalyScore
            NtoPEvent np = new NtoPEvent(timestampsStart[0], timestampsStart[1],
                    n, p, mStart.get("event"), size, 0, anomalyScore);
            np.setEndTime(timestampEnd);
            npEvent.put(key, np);
        }

    }

    //todo: support IPv6
    private Entity[] extractEntities(Map<String, String> m) {
        Entity[] res = new Entity[2];

        // ==================== 第一部分：提取进程实体（永远有）================
        long id = 0L;
        String pid = m.get("pid");
        String process = m.get("process");
        String processKey = pid + process;
        String[] timestamp = m.get("timestamp").split("\\.");
        res[0] = processes.computeIfAbsent(processKey, key -> new Process(repu, id, hops, pid,
                null, null, null, timestamp[0], timestamp[1], process, UID++));
        // 至此，res[0] 一定是 Process 对象，且全局唯一（同一个 pid+name 的进程只会创建一个节点）


        // ==================== 第二部分：从 args 中提取文件或网络实体 ================
        String args = m.get("args");
        Map<String, String> file_socket = Utils.extractFileandSocket(args);
        String process_file = Utils.extractProcessFile(args);

        // ------------------- 情况1：是普通文件操作（open/write/read/close/mmap...）----------------
        if (file_socket.containsKey("path")) {
            String path = file_socket.get("path");
            res[1] = files.computeIfAbsent(path, key -> new FileEntity(repu, id, hops, timestamp[0],
                    timestamp[1], null, null, path, UID++));

            // ------------------- 情况2：是 execve/clone/vfork 等，参数带 filename= ----------------
        } else if (process_file != null) {
            res[1] = files.computeIfAbsent(process_file,
                    key -> new FileEntity(repu, id, hops, timestamp[0],
                            timestamp[1], null, null, process_file, UID++));

            // ------------------- 情况3：是网络操作（connect/sendto/recvfrom/accept...）-------------
        } else if (file_socket.containsKey("sip") && file_socket.containsKey("sport") &&
                file_socket.containsKey("dip") && file_socket.containsKey("dport")) {
            String sourceIP = file_socket.get("sip");
            String sourcePort = file_socket.get("sport");
            String desIP = file_socket.get("dip");
            String desPort = file_socket.get("dport");

            res[1] = networks.computeIfAbsent(sourceIP + ":" + sourcePort + "->" + desIP + ":" + desPort,
                    key -> new NetworkEntity(repu, id, hops, timestamp[0], timestamp[1], sourceIP, desIP, sourcePort, desPort, UID++));

            // ------------------- 情况4：什么都没匹配上（比如 ioctl、futex、close fd=3）-----------
        } else res[1] = null;
        return res;

        // 进程唯一性用 pid + processName 作为 key → 同一个进程不同时间出现也视为同一个节点
        // 文件唯一性用文件完整路径作为 key → 同一个文件多次读写也是同一个节点
        // 网络连接唯一性用完整四元组 srcIP:srcPort->dstIP:dstPort 作为 key → 保证一次 TCP/UDP 连接只有一个网络节点
    }

    public HashMap<String, PtoFEvent> getPfmap() {
        return this.pfEvent;
    }

    public HashMap<String, PtoNEvent> getPnmap() {
        return this.pnEvent;
    }

    public HashMap<String, PtoPEvent> getPpmap() {
        return this.ppEvent;
    }

    public HashMap<String, NtoPEvent> getNpmap() {
        return this.npEvent;
    }

    public HashMap<String, FtoPEvent> getFpmap() {
        return this.fpEvent;
    }

}
