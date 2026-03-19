package logparsers;

import logparsers.exceptions.UnknownEventException;
import logparsers.systemcalls.Fingerprint;
import logparsers.systemcalls.SystemCall;
import logparsers.systemcalls.SystemCallFactory;
import pagerank.config.GlobalConfig;
import pagerank.entity.Process;
import pagerank.entity.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SysdigOutputParserNoRegex implements SysdigOutputParser{
    private File log;
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

    private Map<Fingerprint,SystemCall> answering;

    private Map<EventKey, LogEntry> incompleteEvents; //key is timestamp:event:cwd
    private Map<String,PtoPEvent> backFlow; //key is pid+process
    private Map<String,PtoPEvent> forwardFlow; //key is pid+process
    private final ParserStats parserStats = new ParserStats();
    private long unseenStartEventCount = 0;
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

        registerSystemCalls();// 注册系统调用处理器
    }

    // registerSystemCalls()方法注册了所有系统调用处理器：
    private void registerSystemCalls(){
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        //FtoP
        SystemCallFactory f2pSystemCall = new SystemCallFactory("FtoP",FileEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addF2PEvent);
        for(String s : globalConfig.getFtopSystemCall()) {
            SystemCall systemCall = f2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }

        //PtoF
        SystemCallFactory p2fSystemCall = new SystemCallFactory("PtoF",FileEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2FEvent);
        for(String s : globalConfig.getPtofSystemCall()) {
            SystemCall systemCall = p2fSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }

        //NtoP
        SystemCallFactory n2pSystemCall = new SystemCallFactory("NtoP",NetworkEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addN2PEvent);
        for(String s : globalConfig.getNtopSystemCall()) {
            SystemCall systemCall = n2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }


        //PtoN
        SystemCallFactory p2nSystemCall = new SystemCallFactory("PtoN",NetworkEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2NEvent);
        for(String s : globalConfig.getPtonSystemCall()) {
            SystemCall systemCall = p2nSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }

        //execve
        SystemCall execve = new SystemCall("execve","PtoP",
                new Fingerprint("execve",FileEntity.class,null)).addAction((mStart,mEnd,eStart,eEnd)->{
            Process pStart = (Process)eStart[0];
            Process pEnd = (Process)eEnd[0];
            FileEntity f = (FileEntity)eStart[1];

            String timestampStart = mStart.get("timestamp");
            String timestampEnd = mEnd.get("timestamp");
            String cwd = mStart.get("cwd");
            String key =timestampStart + ":execve:" + cwd;

            String[] timestampsStart = timestampStart.split("\\.");
            String[] timestampsEnd = timestampEnd.split("\\.");

            String args = mEnd.get("args");
            if(!args.contains("res=0"))
                return;

            if(!f.getPath().equals("<NA>")){
                FtoPEvent fp = new FtoPEvent(timestampsStart[0],timestampsStart[1],
                        f,pEnd,"execve",0);

                fp.setEndTime(timestampStart);
                fpEvent.put(key,fp);
            }

            Matcher mParent = pParent.matcher(args);

            if(mParent.find()){
                String pidParent = mParent.group("parentPID");
                String nameParent = mParent.group("parent");
                String keyParent = pidParent+nameParent;
                Process parent = processes.computeIfAbsent(keyParent,
                        k -> new Process(repu, pidParent, nameParent, UID++));
                PtoPEvent forwardLink = new PtoPEvent(timestampsStart[0],timestampsStart[1],parent,pEnd,"execve");
                forwardLink.setEndTime(timestampEnd);

                ppEvent.put(key,forwardLink);
                forwardFlow.put(keyParent,forwardLink);

                PtoPEvent backLink = new PtoPEvent(timestampsStart[0], timestampsStart[1], pEnd,parent, "execve");
                backLink.setEndTime(timestampEnd);

                backFlow.put(mEnd.get("pid")+mEnd.get("process"),backLink);
                ppEvent.put(key+"back",backLink);
            }


        });
        answering.put(execve.fingerPrint,execve);

        //accept
        SystemCall accept = new SystemCall("accept","NtoP",new Fingerprint("accept",null,NetworkEntity.class)).addAction((mStart,mEnd,entitiesStart,entitiesEnd)->{
            Process p = (Process)entitiesEnd[0];
            NetworkEntity n = (NetworkEntity) entitiesEnd[1];

            String timestamp = mEnd.get("timestamp");
            String cwd = mEnd.get("cwd");

            String key = timestamp+":accept:"+cwd;

            String[] timestamps = timestamp.split("\\.");

            NtoPEvent np = new NtoPEvent(timestamps[0],timestamps[1],
                    n,p,mEnd.get("event"),0);
            np.setEndTime(timestamp);

            PtoNEvent pn = new PtoNEvent(timestamps[0],timestamps[1],
                    p,n,mEnd.get("event"),0);
            pn.setEndTime(timestamp);

            npEvent.put(key,np);
            pnEvent.put(key,pn);
        });
        answering.put(accept.fingerPrint,accept);

        //fcntl
        SystemCall fcntl = new SystemCall("fcntl","NtoP",new Fingerprint("fcntl",null,NetworkEntity.class)).addAction((mStart,mEnd,entitiesStart,entitiesEnd)->{
            String timestampStart = mStart.get("timestamp");
            String event = mStart.get("event");
            String cwd = mStart.get("cwd");
            String key = timestampStart+":"+event+":"+cwd;
            String[] timestampsStart = timestampStart.split("\\.");
            String timestampEnd = mEnd.get("timestamp");

            Process p = (Process)entitiesEnd[0];
            NetworkEntity n = (NetworkEntity)entitiesEnd[1];

            NtoPEvent np = new NtoPEvent(timestampsStart[0],timestampsStart[1],
                    n,p,mStart.get("event"),0);
            np.setEndTime(timestampEnd);
            npEvent.put(key,np);
        });
        answering.put(fcntl.fingerPrint,fcntl);

        //rename
        SystemCall rename = new SystemCall("rename", "PtoF", new Fingerprint("rename", null, null)).addAction((mStart, mEnd, entitiesStart, entitiesEnd)->{
            String timestampStart = mStart.get("timestamp");
            String timestampEnd = mEnd.get("timestamp");

            String[] timestampsStart = timestampStart.split("\\.");

            String event = mEnd.get("event");
            String args = mEnd.get("args");
            String cwd = mEnd.get("cwd");

            String oldPath = args.substring(args.indexOf("oldpath=")+8, args.lastIndexOf(" newpath"));
            String newPath = args.substring(args.indexOf("newpath=")+8, args.lastIndexOf(" "));

            if(oldPath.endsWith(")")) oldPath = oldPath.substring(oldPath.indexOf("(")+1, oldPath.length()-1);
            if(newPath.endsWith(")")) newPath = newPath.substring(newPath.indexOf("(")+1, newPath.length()-1);

            final String realOldPath = oldPath;
            final String realNewPath = newPath;

            Process p = (Process)entitiesStart[0];
            String key = timestampStart+":"+event+":"+cwd;

            FileEntity oldFile = files.computeIfAbsent(oldPath,
                    k -> new FileEntity(repu, realOldPath, UID++));
            FtoPEvent fp = new FtoPEvent(timestampsStart[0],timestampsStart[1],
                    oldFile, p, mStart.get("event"),0);
            fp.setEndTime(timestampEnd);
            fpEvent.put(key,fp);

            FileEntity newFile = files.computeIfAbsent(newPath,
                    k -> new FileEntity(repu, realNewPath, UID++));
            PtoFEvent pf = new PtoFEvent(timestampsStart[0],timestampsStart[1],
                    p, newFile, mStart.get("event"),0);
            pf.setEndTime(timestampEnd);
            pfEvent.put(key,pf);
        });
        answering.put(rename.fingerPrint, rename);
    }

    public void getEntities() throws IOException{
        System.out.println("Parsing...");
        long start = System.currentTimeMillis();
        try (BufferedReader logReader = new BufferedReader(new FileReader(log),1048576)) {
            String currentLine;
            while((currentLine = logReader.readLine())!=null){
                parserStats.totalLines++;

                long parseStart = System.nanoTime();
                LogEntry entry = parseLogEntryLine(currentLine);
                parserStats.parseLineNs += System.nanoTime() - parseStart;

                if(entry == null){
                    parserStats.invalidLines++;
                    continue;
                }

                if(entry.isStartEvent()){
                    long matchStart = System.nanoTime();
                    incompleteEvents.put(entry.toEventKey(), entry);
                    parserStats.incompleteMatchNs += System.nanoTime() - matchStart;
                    parserStats.startEvents++;
                }else{
                    parserStats.endEvents++;
                    try{
                        processEvent(entry);
                    }catch (Exception e){
                        parserStats.failedEndEvents++;
                    }
                }
            }
        }
        long end = System.currentTimeMillis();
        if (unseenStartEventCount > 0) {
            System.out.println("Event enter point not seen count: " + unseenStartEventCount);
        }
        parserStats.print();
        System.out.println("Parsing(in parser) time Cost:"+(end-start)/1000.0);
    }

    @Override
    public void afterBuilding() {}

    private void processEvent(LogEntry end) throws UnknownEventException {
        long matchStart = System.nanoTime();
        String startTimestamp = subtractLatencyNs(end.timestamp, end.latency);
        EventKey key = new EventKey(startTimestamp, end.event, end.cwd);
        LogEntry start = incompleteEvents.remove(key);
        boolean useDummyStart = (start == null);
        if (useDummyStart) {
            start = createDummyStartEntry(end, startTimestamp);
        }
        parserStats.incompleteMatchNs += System.nanoTime() - matchStart;

        long extractStart = System.nanoTime();
        Entity[] startEntites = extractEntities(start);
        Entity[] endEntities = extractEntities(end);
        parserStats.extractEntityNs += System.nanoTime() - extractStart;

        long dispatchStart = System.nanoTime();
        Map<String, String> startMap = start.toMap();
        Map<String, String> endMap = end.toMap();

        // Fingerprint = (系统调用名, 进入时的实体类型, 返回时的实体类型),它的作用是唯一标识一种事件语义。
        //例如：
        //open()：("open", Process, FileEntity)
        //read()：("read", Process, FileEntity)
        //accept()：("accept", null, NetworkEntity)
        //execve()：("execve", FileEntity, null)
        Fingerprint f = Fingerprint.toFingerPrint(startMap, endMap, startEntites, endEntities);
        // answering 是一个全局 Map，键是指纹（Fingerprint），值是该指纹对应的处理逻辑（SystemCall）
        SystemCall systemCall = answering.getOrDefault(f, null);
        if (systemCall == null) {
            throw new UnknownEventException("Unknown event: "+startMap.get("event"));
        }else{
            systemCall.react(startMap, endMap, startEntites, endEntities);
        }
        parserStats.dispatchNs += System.nanoTime() - dispatchStart;

        if(useDummyStart) {
            unseenStartEventCount++;
        }
    }

    public void updateP2PLinks(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        String endTime = mEnd.get("timestamp");
        if(backFlow.containsKey(mEnd.get("pid")+mEnd.get("process"))){
            PtoPEvent bf = backFlow.get(mEnd.get("pid")+mEnd.get("process"));
            if(compareTimestamp(bf.getEnd(), endTime)<0){
                bf.setEndTime(endTime);
            }
//            System.out.println(bf.getEnd());
        }
        if(forwardFlow.containsKey(mEnd.get("pid")+mEnd.get("process"))){
            PtoPEvent ff = forwardFlow.get(mEnd.get("pid")+mEnd.get("process"));
            if(compareTimestamp(ff.getEnd(), endTime)<0){
                ff.setEndTime(endTime);
            }
//            System.out.println(ff.getEnd());
        }
    }

    private void addP2FEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        Process p = (Process)entitiesStart[0];
        FileEntity f = (FileEntity)entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = buildEventKey(timestampStart, event, cwd);

        String[] timestampsStart = splitTimestamp(timestampStart);

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){

            PtoFEvent pf = new PtoFEvent(timestampsStart[0],timestampsStart[1],
                    p,f,mStart.get("event"),size);
            pf.setEndTime(timestampEnd);
            pfEvent.put(key,pf);
        }
    }

    private void addF2PEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        Process p = (Process)entitiesStart[0];
        FileEntity f = (FileEntity)entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = buildEventKey(timestampStart, event, cwd);

        String[] timestampsStart = splitTimestamp(timestampStart);

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){
            FtoPEvent fp = new FtoPEvent(timestampsStart[0],timestampsStart[1],
                    f,p,mStart.get("event"),size);
            fp.setEndTime(timestampEnd);
            fpEvent.put(key,fp);
        }
    }

    private void addP2NEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        Process p = (Process)entitiesStart[0];
        NetworkEntity n = (NetworkEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = buildEventKey(timestampStart, event, cwd);

        String[] timestampsStart = splitTimestamp(timestampStart);

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){
            PtoNEvent pn = new PtoNEvent(timestampsStart[0],timestampsStart[1],
                    p,n,mStart.get("event"),size);
            pn.setEndTime(timestampEnd);
            pnEvent.put(key,pn);
        }
    }

    private void addN2PEvent(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        String args = mEnd.get("args");

        Process p = (Process)entitiesStart[0];
        NetworkEntity n = (NetworkEntity) entitiesStart[1];

        String timestampStart = mStart.get("timestamp");
        String event = mStart.get("event");
        String cwd = mStart.get("cwd");
        String key = buildEventKey(timestampStart, event, cwd);
        String[] timestampsStart = splitTimestamp(timestampStart);

        String timestampEnd = mEnd.get("timestamp");

        long size = Utils.extractSize(args);
        if(size!=-1L){
            NtoPEvent np = new NtoPEvent(timestampsStart[0],timestampsStart[1],
                    n,p,mStart.get("event"),size);
            np.setEndTime(timestampEnd);
            npEvent.put(key,np);
        }

    }

    //todo: support IPv6
    private Entity[] extractEntities(LogEntry m){
        Entity[] res = new Entity[2];

        // ==================== 第一部分：提取进程实体（永远有）================
        String pid = m.pid;
        String process = m.process;
        String processKey = pid+process;
        res[0] = processes.computeIfAbsent(processKey,
                key -> new Process(repu, pid, process, UID++));
        // 至此，res[0] 一定是 Process 对象，且全局唯一（同一个 pid+name 的进程只会创建一个节点）



        // ==================== 第二部分：从 args 中提取文件或网络实体 ================
        String args = m.args;
        Map<String, String> file_socket = Utils.extractFileandSocket(args);
        String process_file = Utils.extractProcessFile(args);

        // ------------------- 情况1：是普通文件操作（open/write/read/close/mmap...）----------------
        if(file_socket.containsKey("path")) {
            String path = file_socket.get("path");
            res[1] = files.computeIfAbsent(path, key -> new FileEntity(repu, path, UID++));

        // ------------------- 情况2：是 execve/clone/vfork 等，参数带 filename= ----------------
        }else if(process_file != null) {
            res[1] = files.computeIfAbsent(process_file,
                    key -> new FileEntity(repu, process_file, UID++));

        // ------------------- 情况3：是网络操作（connect/sendto/recvfrom/accept...）-------------
        }else if(file_socket.containsKey("sip") && file_socket.containsKey("sport") &&
                file_socket.containsKey("dip") && file_socket.containsKey("dport")) {
            String sourceIP = file_socket.get("sip");
            String sourcePort = file_socket.get("sport");
            String desIP = file_socket.get("dip");
            String desPort = file_socket.get("dport");

            res[1] = networks.computeIfAbsent(sourceIP+":"+sourcePort+"->"+ desIP+":"+desPort,
                    key -> new NetworkEntity(repu, sourceIP, desIP, sourcePort, desPort, UID++));

        // ------------------- 情况4：什么都没匹配上（比如 ioctl、futex、close fd=3）-----------
        }else res[1] = null;
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

    private String buildEventKey(String timestamp, String event, String cwd) {
        return timestamp + ":" + event + ":" + cwd;
    }

    private LogEntry parseLogEntryLine(String line) {
        String[] fields = splitFirstNine(line);
        if (fields == null) {
            return null;
        }
        return new LogEntry(
                line,
                fields[0],
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                fields[5],
                fields[6],
                fields[7],
                fields[8],
                false
        );
    }

    private LogEntry createDummyStartEntry(LogEntry end, String startTimestamp) {
        return new LogEntry(
                end.raw,
                startTimestamp,
                end.cpu,
                end.process,
                end.pid,
                ">",
                end.event,
                end.cwd,
                end.latency,
                "!dummy!",
                true
        );
    }

    private String subtractLatencyNs(String timestamp, String latencyNs) {
        long endNs = toEpochNano(timestamp);
        long latency = Long.parseLong(latencyNs);
        return fromEpochNano(endNs - latency);
    }

    private String[] splitFirstNine(String entry) {
        String[] result = new String[9];
        int from = 0;
        for (int i = 0; i < 8; i++) {
            int delimiterIndex = entry.indexOf(";;", from);
            if (delimiterIndex < 0) {
                return null;
            }
            result[i] = entry.substring(from, delimiterIndex);
            from = delimiterIndex + 2;
        }
        int argsEnd = entry.indexOf(";;", from);
        if (argsEnd < 0) {
            argsEnd = entry.length();
        }
        result[8] = entry.substring(from, argsEnd);
        return result;
    }

    private int compareTimestamp(String left, String right) {
        return Long.compare(toEpochNano(left), toEpochNano(right));
    }

    private long toEpochNano(String timestamp) {
        int dot = timestamp.indexOf('.');
        if (dot < 0) {
            return Long.parseLong(timestamp) * 1_000_000_000L;
        }

        long second = Long.parseLong(timestamp.substring(0, dot));
        String nanosPart = timestamp.substring(dot + 1);
        long nanos;
        if (nanosPart.length() >= 9) {
            nanos = Long.parseLong(nanosPart.substring(0, 9));
        } else {
            nanos = Long.parseLong(nanosPart) * POW10[9 - nanosPart.length()];
        }
        return second * 1_000_000_000L + nanos;
    }

    private String fromEpochNano(long epochNano) {
        long second = Math.floorDiv(epochNano, 1_000_000_000L);
        long nanos = Math.floorMod(epochNano, 1_000_000_000L);
        if (nanos == 0) {
            return Long.toString(second);
        }
        String nanoText = String.format("%09d", nanos);
        int end = nanoText.length();
        while (end > 0 && nanoText.charAt(end - 1) == '0') {
            end--;
        }
        return second + "." + nanoText.substring(0, end);
    }

    private String[] splitTimestamp(String timestamp) {
        int dot = timestamp.indexOf('.');
        if (dot < 0) {
            return new String[]{timestamp, "0"};
        }
        return new String[]{timestamp.substring(0, dot), timestamp.substring(dot + 1)};
    }

    private static final long[] POW10 = new long[]{
            1L,
            10L,
            100L,
            1_000L,
            10_000L,
            100_000L,
            1_000_000L,
            10_000_000L,
            100_000_000L,
            1_000_000_000L
    };

    private static final class EventKey {
        private final String timestamp;
        private final String event;
        private final String cwd;

        private EventKey(String timestamp, String event, String cwd) {
            this.timestamp = timestamp;
            this.event = event;
            this.cwd = cwd;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EventKey)) {
                return false;
            }
            EventKey other = (EventKey) obj;
            return Objects.equals(timestamp, other.timestamp)
                    && Objects.equals(event, other.event)
                    && Objects.equals(cwd, other.cwd);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, event, cwd);
        }
    }

    private static final class LogEntry {
        private final String raw;
        private final String timestamp;
        private final String cpu;
        private final String process;
        private final String pid;
        private final String direction;
        private final String event;
        private final String cwd;
        private final String latency;
        private final String args;
        private final boolean dummyStart;
        private Map<String, String> cacheMap;

        private LogEntry(String raw, String timestamp, String cpu, String process, String pid,
                         String direction, String event, String cwd, String latency, String args,
                         boolean dummyStart) {
            this.raw = raw;
            this.timestamp = timestamp;
            this.cpu = cpu;
            this.process = process;
            this.pid = pid;
            this.direction = direction;
            this.event = event;
            this.cwd = cwd;
            this.latency = latency;
            this.args = args;
            this.dummyStart = dummyStart;
        }

        private boolean isStartEvent() {
            return ">".equals(direction);
        }

        private EventKey toEventKey() {
            return new EventKey(timestamp, event, cwd);
        }

        private Map<String, String> toMap() {
            if (cacheMap == null) {
                cacheMap = new HashMap<>(10);
                cacheMap.put("raw", raw);
                cacheMap.put("timestamp", timestamp);
                cacheMap.put("cpu", cpu);
                cacheMap.put("process", process);
                cacheMap.put("pid", pid);
                cacheMap.put("direction", direction);
                cacheMap.put("event", event);
                cacheMap.put("cwd", cwd);
                cacheMap.put("latency", latency);
                cacheMap.put("args", args);
            }
            return cacheMap;
        }
    }

    private static final class ParserStats {
        private long totalLines;
        private long invalidLines;
        private long startEvents;
        private long endEvents;
        private long failedEndEvents;
        private long parseLineNs;
        private long incompleteMatchNs;
        private long extractEntityNs;
        private long dispatchNs;

        private void print() {
            double toMs = 1_000_000.0;
            System.out.println(
                    "Parser stats | lines=" + totalLines
                            + ", invalid=" + invalidLines
                            + ", start=" + startEvents
                            + ", end=" + endEvents
                            + ", failedEnd=" + failedEndEvents
            );
            System.out.println(
                    "Parser stage(ms) | parseLine=" + String.format("%.3f", parseLineNs / toMs)
                            + ", match=" + String.format("%.3f", incompleteMatchNs / toMs)
                            + ", extractEntity=" + String.format("%.3f", extractEntityNs / toMs)
                            + ", dispatch=" + String.format("%.3f", dispatchNs / toMs)
            );
        }
    }

}
