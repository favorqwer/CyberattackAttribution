package logparsers;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import logparsers.exceptions.EventStartUnseenException;
import logparsers.exceptions.UnknownEventException;
import logparsers.systemcalls.Fingerprint;
import logparsers.systemcalls.SystemCall;
import logparsers.systemcalls.SystemCallFactory;
import org.jetbrains.annotations.NotNull;
import org.mapdb.*;
import pagerank.Process;
import pagerank.*;

import java.io.*;
import java.math.BigDecimal;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SysdigOutputParserMapDB implements SysdigOutputParser{
    public static void main(String[] args){
        try {

            SysdigOutputParserMapDB parser = new SysdigOutputParserMapDB("input/attacks_bad/command_injection_step3.txt",MetaConfig.localIP);
            parser.getEntities();

        }catch (Exception e){
            System.out.println(e.getMessage());
        }
    }

    private DB db;

    private File log;
    private Set<String> localIP;
    private long UID;
    private double repu = 0.0;
    private int hops = 0;

    private Map<String, FileEntity> files;
    private Map<String, Process> processes;
    private Map<String, NetworkEntity> networks;

    private Map<String, PtoFEvent> pfEvent; //keys are all timestamps:event:cwd
    private Map<String, PtoNEvent> pnEvent;
    private Map<String, PtoPEvent> ppEvent;
    private Map<String, NtoPEvent> npEvent;
    private Map<String, FtoPEvent> fpEvent;

    private Map<Fingerprint,SystemCall> answering;

    private Map<String,Map<String, String>> incompleteEvents; //key is timestamp:event:cwd
    private Map<String,PtoPEvent> backFlow; //key is pid+process
    private Map<String,PtoPEvent> forwardFlow; //key is pid+process

//    private static final Pattern pSysdigEntry = Pattern.compile(
//                    "(?<timestamp>\\d+\\.\\d+) " +
//                    "(?<cpu>\\d+) " +
//                    "(?<process>.+?) " +
//                    "\\((?<pid>\\d+)\\) " +
//                    "(?<direction>>|<) " +
//                    "(?<event>.+?) " +
//                    "cwd=(?<cwd>.+?) " +
//                    "(?<args>.*?) " +
//                    "latency=(?<latency>\\d+)"
//    );
    private static final Pattern pParent = Pattern.compile("ptid=(?<parentPID>\\d+)\\((?<parent>.+?)\\)");

    public SysdigOutputParserMapDB(String pathToLog, String[] localIP) {
        this.db = DBMaker.memoryDB().allocateStartSize(1024 * 1024 * 1024)
                .allocateIncrement(512 * 1024 * 1024).make();

        Kryo kryo = new Kryo();

        kryo.register(FileEntity.class);
        kryo.register(Process.class);
        kryo.register(NetworkEntity.class);

        kryo.register(PtoFEvent.class);
        kryo.register(FtoPEvent.class);
        kryo.register(PtoNEvent.class);
        kryo.register(NtoPEvent.class);
        kryo.register(PtoPEvent.class);

        KryoSerializer serializer = new KryoSerializer(kryo);

        files = db.hashMap("file:"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        processes = db.hashMap("processes"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        networks = db.hashMap("networks"+pathToLog, Serializer.STRING, serializer).createOrOpen();

        pfEvent = db.hashMap("pfEvent"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        pnEvent = db.hashMap("pnEvent"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        ppEvent = db.hashMap("ppEvent"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        npEvent = db.hashMap("npEvent"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        fpEvent = db.hashMap("fpEvent"+pathToLog, Serializer.STRING, serializer).createOrOpen();

        incompleteEvents = new HashMap<>();
        backFlow = db.hashMap("backFlow"+pathToLog, Serializer.STRING, serializer).createOrOpen();
        forwardFlow = db.hashMap("forwardFlow"+pathToLog, Serializer.STRING, serializer).createOrOpen();

        answering = new HashMap<>();

        log = new File(pathToLog);
        UID = 0;
        this.localIP = new HashSet<>();
        this.localIP.addAll(Arrays.asList(localIP));

        registerSystemCalls();
    }

    private void registerSystemCalls(){
        //FtoP
        SystemCallFactory f2pSystemCall = new SystemCallFactory("FtoP",FileEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addF2PEvent);
        for(String s : MetaConfig.ftopSystemCall) {
            SystemCall systemCall = f2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }

        //PtoF
        SystemCallFactory p2fSystemCall = new SystemCallFactory("PtoF",FileEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2FEvent);
        for(String s : MetaConfig.ptofSystemCall) {
            SystemCall systemCall = p2fSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }

        //NtoP
        SystemCallFactory n2pSystemCall = new SystemCallFactory("NtoP",NetworkEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addN2PEvent);
        for(String s : MetaConfig.ntopSystemCall) {
            SystemCall systemCall = n2pSystemCall.getSystemCall(s);
            answering.put(systemCall.fingerPrint,systemCall);
        }


        //PtoN
        SystemCallFactory p2nSystemCall = new SystemCallFactory("PtoN",NetworkEntity.class,null)
                .addAction(this::updateP2PLinks)
                .addAction(this::addP2NEvent);
        for(String s : MetaConfig.ptonSystemCall) {
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
                        f,pEnd,"execve",0,0);

                fp.setEndTime(timestampStart);
                fpEvent.put(key,fp);
            }

            Matcher mParent = pParent.matcher(args);

            if(mParent.find()){
                String pidParent = mParent.group("parentPID");
                String nameParent = mParent.group("parent");
                String keyParent = pidParent+nameParent;
                Process parent = processes.computeIfAbsent(keyParent,k ->new Process(repu, -1, hops, pidParent,
                        null, null, null,
                        timestampsStart[0], timestampsStart[1],
                        nameParent, UID++));
//                        if(!processes.containsKey(processKey))
//                            throw new ParentNotSeenException(m.get("id")+" "+m.get("event")
//                                    +":"+pid+" "+process);

                PtoPEvent forwardLink = new PtoPEvent(timestampsStart[0],timestampsStart[1],parent,pEnd,"execve",
                        0);
                forwardLink.setEndTime(timestampEnd);

                ppEvent.put(key,forwardLink);
                forwardFlow.put(keyParent,forwardLink);

                PtoPEvent backLink = new PtoPEvent(timestampsStart[0], timestampsStart[1], pEnd,parent, "execve",
                        0);
                backLink.setEndTime(timestampEnd);

//                        System.out.println(String.format("%s %s -> %s %s",
//                                backLink.getSource().getPid(),backLink.getSource().getName(),
//                                backLink.getSink().getPid(),backLink.getSink().getName()
//                        ));

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
                    n,p,mEnd.get("event"),0,0);
            np.setEndTime(timestamp);

            PtoNEvent pn = new PtoNEvent(timestamps[0],timestamps[1],
                    p,n,mEnd.get("event"),0,0);
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
                    n,p,mStart.get("event"),0,0);
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

            FileEntity oldFile = files.computeIfAbsent(oldPath ,k -> new FileEntity(repu, 0L, hops, timestampsStart[0],
                    timestampsStart[1], null, null, realOldPath, UID++));
            FtoPEvent fp = new FtoPEvent(timestampsStart[0],timestampsStart[1],
                    oldFile, p, mStart.get("event"),0,0);
            fp.setEndTime(timestampEnd);
            fpEvent.put(key,fp);

            FileEntity newFile = files.computeIfAbsent(newPath ,k -> new FileEntity(repu, 0L, hops, timestampsStart[0],
                    timestampsStart[1], null, null, realNewPath, UID++));
            PtoFEvent pf = new PtoFEvent(timestampsStart[0],timestampsStart[1],
                    p, newFile, mStart.get("event"),0,0);
            pf.setEndTime(timestampEnd);
            pfEvent.put(key,pf);
        });
        answering.put(rename.fingerPrint, rename);
    }

    public void getEntities() throws IOException{
        System.out.println("Parsing...");
        long start = System.currentTimeMillis();
        //dependencyGraph = new DirectedPseudograph<pagerank.EntityNode, pagerank.EventEdge>(pagerank.EventEdge.class);
        BufferedReader logReader = new BufferedReader(new FileReader(log),1048576);
        String currentLine;
        while((currentLine = logReader.readLine())!=null){
            Map matcher = Utils.parseEntry(currentLine);
            if(!matcher.isEmpty()){
                if(matcher.get("direction").equals(">")){
                    incompleteEvents.put(matcher.get("timestamp")+":"+matcher.get("event")+":"+matcher.get("cwd"),matcher);
                }else{
                    try{
                        processEvent(matcher);
                    }catch (Exception e){
//                        e.printStackTrace();
//                        System.out.println(e.getMessage());
                    }
                }
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("Parsing(in parser) time Cost:"+(end-start)/1000.0);
    }

    @Override
    public void afterBuilding() {
        db.close();
    }

    private void processEvent(Map<String, String> end) throws EventStartUnseenException, UnknownEventException {
        String startTimestamp = new BigDecimal(end.get("timestamp"))
                .subtract(new BigDecimal(end.get("latency")).scaleByPowerOfTen(-9))
                .toString();
        String key = startTimestamp+":"+end.get("event")+":"+end.get("cwd");
        Map start;
        if (!incompleteEvents.containsKey(key)){
            String dummyEntry = String.format("%s %s %s %s (%s) %s %s cwd=%s !dummy!  latency=%s",
                    "0",startTimestamp,end.get("cpu"),
                    end.get("process"),end.get("pid"),">",end.get("event"),
                    end.get("cwd"),end.get("latency"));
            start = Utils.parseEntry(dummyEntry);

        }else{
            start = incompleteEvents.remove(key);
        }

        Entity[] startEntites = extractEntities(start);
        Entity[] endEntities = extractEntities(end);

        Fingerprint f = Fingerprint.toFingerPrint(start, end, startEntites, endEntities);
        SystemCall systemCall = answering.getOrDefault(f, null);
        if (systemCall == null) {
            throw new UnknownEventException("Unknown event: "+start.get("event"));
        }else{
            systemCall.react(start, end, startEntites, endEntities);
        }
        if(start.get("args").equals("!dummy!"))
            System.out.println("Event enter point not seen: "+end.get("raw"));
    }

    public void updateP2PLinks(Map<String, String> mStart, Map<String, String> mEnd, Entity[] entitiesStart, Entity[] entitiesEnd){
        String endTime = mEnd.get("timestamp");
        if(backFlow.containsKey(mEnd.get("pid")+mEnd.get("process"))){
            PtoPEvent bf = backFlow.get(mEnd.get("pid")+mEnd.get("process"));
            if(new BigDecimal(bf.getEnd()).compareTo(new BigDecimal(endTime))<0){
                bf.setEndTime(endTime);
            }
//            System.out.println(bf.getEnd());
        }
        if(forwardFlow.containsKey(mEnd.get("pid")+mEnd.get("process"))){
            PtoPEvent ff = forwardFlow.get(mEnd.get("pid")+mEnd.get("process"));
            if(new BigDecimal(ff.getEnd()).compareTo(new BigDecimal(endTime))<0){
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
        String key = timestampStart+":"+event+":"+cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){

            PtoFEvent pf = new PtoFEvent(timestampsStart[0],timestampsStart[1],
                    p,f,mStart.get("event"),size,0);
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
        String key = timestampStart+":"+event+":"+cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){
            FtoPEvent fp = new FtoPEvent(timestampsStart[0],timestampsStart[1],
                    f,p,mStart.get("event"),size,0);
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
        String key = timestampStart+":"+event+":"+cwd;

        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        String args = mEnd.get("args");
        long size = Utils.extractSize(args);
        if(size!=-1L){
            PtoNEvent pn = new PtoNEvent(timestampsStart[0],timestampsStart[1],
                    p,n,mStart.get("event"),size,0);
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
        String key = timestampStart+":"+event+":"+cwd;
        String[] timestampsStart = timestampStart.split("\\.");

        String timestampEnd = mEnd.get("timestamp");

        long size = Utils.extractSize(args);
        if(size!=-1L){
            NtoPEvent np = new NtoPEvent(timestampsStart[0],timestampsStart[1],
                    n,p,mStart.get("event"),size,0);
            np.setEndTime(timestampEnd);
            npEvent.put(key,np);
        }

    }

    //todo: support IPv6
    private Entity[] extractEntities(Map<String, String> m){
        Entity[] res = new Entity[2];

        //extract process
        long id = 0L;
        String pid = m.get("pid");
        String process = m.get("process");
        String processKey = pid+process;
        String[] timestamp = m.get("timestamp").split("\\.");
        res[0] = processes.computeIfAbsent(processKey,key ->new Process(repu, id, hops, pid,
                null, null, null, timestamp[0], timestamp[1], process, UID++));

        String args = m.get("args");

        //extract files involved
        Map<String, String> file_socket = Utils.extractFileandSocket(args);
        String process_file = Utils.extractProcessFile(args);

        if(file_socket.containsKey("path")) {
            String path = file_socket.get("path");
            res[1] = files.computeIfAbsent(path ,key -> new FileEntity(repu, id, hops, timestamp[0],
                    timestamp[1], null, null, path, UID++));
        }else if(process_file != null) {
            res[1] = files.computeIfAbsent(process_file,
                    key -> new FileEntity(repu, id, hops, timestamp[0],
                            timestamp[1], null, null, process_file, UID++));
        }else if(file_socket.containsKey("sip") && file_socket.containsKey("sport") &&
                file_socket.containsKey("dip") && file_socket.containsKey("dport")) {
            String sourceIP = file_socket.get("sip");
            String sourcePort = file_socket.get("sport");
            String desIP = file_socket.get("dip");
            String desPort = file_socket.get("dport");

            res[1] = networks.computeIfAbsent(sourceIP+":"+sourcePort+"->"+ desIP+":"+desPort,
                    key -> new NetworkEntity(repu, id, hops, timestamp[0], timestamp[1], sourceIP, desIP, sourcePort, desPort, UID++));
        }else res[1] = null;

        return res;
    }

    class KryoSerializer implements Serializer {
        Kryo kryo;

        KryoSerializer(Kryo kryo) {
            this.kryo = kryo;
        }

        @Override
        public void serialize(@NotNull DataOutput2 dataOutput2, @NotNull Object o) throws IOException {
            ByteArrayOutputStream objStream = new ByteArrayOutputStream();
            Output output = new Output(objStream);
            kryo.writeClassAndObject(output, o);
            output.close();
            dataOutput2.write(objStream.toByteArray());
            dataOutput2.close();
        }

        @Override
        public Object deserialize(@NotNull DataInput2 dataInput2, int i) throws IOException {
            int pos = dataInput2.getPos();
            return kryo.readClassAndObject(new Input(Arrays.copyOfRange(dataInput2.internalByteArray(), pos, pos+i)));
        }
    }

    public Map<String, PtoFEvent> getPfmap() {
        return this.pfEvent;
    }

    public Map<String, PtoNEvent> getPnmap() {
        return this.pnEvent;
    }

    public Map<String, PtoPEvent> getPpmap() {
        return this.ppEvent;
    }

    public Map<String, NtoPEvent> getNpmap() {
        return this.npEvent;
    }

    public Map<String, FtoPEvent> getFpmap() {
        return this.fpEvent;
    }

}
