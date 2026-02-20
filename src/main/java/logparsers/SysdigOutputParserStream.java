package logparsers;

import pagerank.*;
import pagerank.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * Created by fang on 6/13/17.
 * the sysdig output format is:
 * %evt.num %evt.rawtime.s.%evt.rawtime.ns %evt.cpu %proc.name (%proc.pid) %evt.dir %evt.type %proc.ppid %evt.args latency=%evt.latency"
 */

public class SysdigOutputParserStream implements SysdigOutputParser {

    private HashMap<String, Process> processHashMap;               //key is pid+proc name
    private HashMap<String, FileEntity> fileHashMap ;               //key is file path
    private HashMap<String, NetworkEntity> networkHashMap;          //key is ip address and port

    private File record;

    private HashMap<String, PtoFEvent> pfmap;                        //key is event start time
    private HashMap<String, PtoPEvent> ppmap;                        //key is event start time
    private HashMap<String,FtoPEvent> fpmap;                        //key is the event start time
    private HashMap<String,PtoNEvent> pnmap;


    private HashMap<String,NtoPEvent> npmap;
    private Set<String> PtoF;                                       //include the system call type of Process to File dependency
    private Set<String> PtoP;                                       //include the system call type of Process to Process
    private Set<String> FtoP;                                       //include the system call  type of File to Process
    private Set<String> NtoP;
    private Set<String> PtoN;

    private Set<String> localIPS;
    private long uniqID;

    public SysdigOutputParserStream(String path, String[] localIP){
        try {
            record = new File(path);
            if(!record.exists()){
                throw new FileNotFoundException("The input file is not exist");
            }
        }
        catch(FileNotFoundException e){
            System.err.print("Message: "+ e.getMessage());
        }

        processHashMap = new HashMap<>();
        fileHashMap = new HashMap<>();
        networkHashMap = new HashMap<>();
        pfmap = new HashMap<>();
        ppmap = new HashMap<>();
        fpmap = new HashMap<>();
        pfmap = new HashMap<>();
        pnmap = new HashMap<>();
        npmap = new HashMap<>();
        PtoF = new HashSet<>();
        PtoP = new HashSet<>();
        FtoP = new HashSet<>();
        PtoN = new HashSet<>();
        NtoP = new HashSet<>();
        String[] ptopSystemCall = MetaConfig.ptopSystemCall;
        String[] ptofSystemCall = MetaConfig.ptofSystemCall;
        String[] ftopSystemCall = MetaConfig.ftopSystemCall;
        String[] ptonSystemCall = MetaConfig.ptonSystemCall;
        String[] ntopSystemCall = MetaConfig.ntopSystemCall;
        for(String str:ptopSystemCall){
            PtoP.add(str);
        }
        for(String str:ptofSystemCall){
            PtoF.add(str);
        }
        for(String str:ftopSystemCall){
            FtoP.add(str);
        }
        for(String str:ptonSystemCall){
            PtoN.add(str);
        }
        for(String str:ntopSystemCall){
            NtoP.add(str);
        }

        uniqID = 0;
        localIPS = new HashSet<>();
        for(String str:localIP){
            localIPS.add(str);
        }
    }

    public static void main(String[] args)throws Exception{
        String[] localIP={"192.168.29.234"};
//        String[] localIP = {"129.22.104.132","129.22.4.31","129.22.104.25"};
        SysdigOutputParserStream test = new SysdigOutputParserStream("input/logs_extended/curl.txt",localIP);
        test.getEntities();

        HashMap<String,Process> pmap = test.getProcessHashMap();
        HashMap<String,FileEntity> fmap = test.getFileHashMap();
        HashMap<String,NetworkEntity> networkHashMap = test.getNetworkHashMap();
        HashMap<String,NtoPEvent> npmap = test.getNpmap();
        HashMap<String,PtoNEvent> pnmap = test.getPnmap();
        Set<String> ids = pmap.keySet();
        PrintWriter prWriter = new PrintWriter(String.format("%s.txt","testOutput"));
//        System.out.println("process:----------------------");
//        for(String id:ids){
//            Process tmp = pmap.get(id);
//            System.out.println(tmp.getTimeStap()+" "+tmp.getPid() +" "+tmp.getName());
//            //System.out.println(tmp2.getPath());
//        }
//        ids = fmap.keySet();
//        System.out.println("file: ----------------------");
//        for(String id:ids){
//            FileEntity tmp = fmap.get(id);
//            System.out.println(tmp.getPath()+" "+tmp.getTimeStap());
//        }
//        ids = networkHashMap.keySet();
//        for(String id:ids){
//            NetworkEntity tem =networkHashMap.get(id);
//            System.out.println(tem.getSrcAddress()+" "+tem.getDstAddress());
//        }


//        System.out.println("File to Process event:----------------------------------------");
//        HashMap<String, FtoPEvent> fpmap = test.getFpmap();
//        for(String key:fpmap.keySet()){
//            FtoPEvent fp = fpmap.get(key);
//            if(fp.getSink() == null){
//                System.out.println(key);
//                System.out.println(fp.getEvent());
//            }
//            if(fp.getSource() == null){
//                System.out.println(key);
//                System.out.println(fp.getEvent());
//            }
//            prWriter.println(fpmap.get(key).getSource().getPath()+"  "+fpmap.get(key).getSink().getPidAndName());
//            prWriter.println(fpmap.get(key).getStart());
//        }

        System.out.println("Process to File event:---------------------------");
//        HashMap<String,PtoFEvent> pfmap = test.getPfmap();
//        for(String key:pfmap.keySet()){
//            System.out.println(pfmap.get(key).getEndTime());
//            System.out.println(pfmap.get(key).getSource().getPidAndName()+" "+pfmap.get(key).getSink().getPath()+pfmap.get(key).getSize());
//            System.out.println(pfmap.get(key).getSource().getUniqID() +"sink uniq id:" +pfmap.get(key).getSink().getUniqID());
//        }
        System.out.println("Process to Process event:---------------------------");
//        HashMap<String, PtoPEvent> ppmap = test.getPpmap();
//        for(String key:ppmap.keySet()){
//            System.out.println(ppmap.get(key).getSource().getPidAndName()+" sink is"+ppmap.get(key).getSink().getPidAndName());
//            System.out.println(ppmap.get(key).getStartTime()+" end: "+ppmap.get(key).getInterval());
//            PtoPEvent pp = ppmap.get(key);
//            if(pp.getSource() == null){
//                System.out.println("PP source is null");
//            }
//            if(pp.getSink() == null){
//                System.out.println("PP sink is null");
//                System.out.println(pp.getEvent());
//            }
//            System.out.println(pp.getEndTime());
//        }
        System.out.println("Network to Process event: ------------------------");
        for(String key :npmap.keySet()){
           NtoPEvent np = npmap.get(key);
           if(np.getSource()== null){
               System.out.println("NPevent Source is null");
           }
           if(np.getSink() == null){
               System.out.println("NPevent Sink is null");
           }
            System.out.println(np.getSource().getSrcAddress().equals(localIP[0]));
            System.out.println("Find local IP");
            break;
        }
//        for(String key :pnmap.keySet()){
//            PtoNEvent np = pnmap.get(key);
//            if(np.getSource()== null){
//                System.out.println("NPevent Source is null");
//            }
//            if(np.getSink() == null){
//                System.out.println("NPevent Sink is null");
//            }
//            System.out.println(np.getSink().getSrcAddress().equals(localIP[0]));
//            System.out.println("Find localIP");
//        }
//        HashMap<String, NetworkEntity> nmap = test.getNetworkHashMap();
//        for(String key: nmap.keySet()){
//            if(nmap.get(key).getSrcAddress().equals(localIP[0])){
//                System.out.println("Find local to remote");
//            }
//            break;
//        }



    }

    public HashMap<String, PtoNEvent> getPnmap() {
        return pnmap;
    }

    public HashMap<String, PtoFEvent> getPfmap() {
        return pfmap;
    }

    public HashMap<String, PtoPEvent> getPpmap() {
        return ppmap;
    }

    public HashMap<String, FtoPEvent> getFpmap() {
        return fpmap;
    }

    public HashMap<String, NtoPEvent> getNpmap() {
        return npmap;
    }

    public HashMap<String,Process> getProcessHashMap(){
        return processHashMap;
    }

    public HashMap<String, FileEntity> getFileHashMap(){
        return fileHashMap;
    }

    public HashMap<String, NetworkEntity> getNetworkHashMap(){ return networkHashMap;}

    public void getEntities() throws IOException {
        Scanner sc = new Scanner(record);
        int numLines = 0;
        while (sc.hasNextLine()) {
            numLines++;
            String s = sc.nextLine();
            String[] parts = s.split(" ");
            double reput = 0.0;
            int hopCount = 0;
            long id = 0;        //unique event id identifyer
            String pid = null, timestamp1 = null, timestamp2 = null, path = null, name = null;
            String srcIP = null, srcPort = null, destIP = null, destPort = null;
            String event = null, direction = null, latency = null, cpu = null;
            String pid2 = null, name2 = null, cwd = null, args = null, ip = null;
            long size = 0;
            for (int i = 0; i < parts.length; i++) {
                if (i == 0) id = Long.valueOf(parts[i]);
                if (i == 1) {
                    String[] times = parts[i].split("\\.");
                    timestamp1 = times[0];
                    timestamp2 = times[1];
                }
                if (i == 2) cpu = parts[i];
                if (i == 3) name = parts[i];
                if (i == 4) pid = parts[i].substring(1, parts[i].length() - 1);
                if (i == 5) direction = parts[i];
                if (i == 6) event = parts[i];
                if (i >= 7) {
                    if (parts[i].startsWith("fd")) {
                        int index = 0;
                        while (index < parts[i].length()) {
                            if (parts[i].charAt(index) == '>') {
                                if (parts[i].charAt(index - 1) == 'f') {
                                    path = parts[i].substring(index + 1, parts[i].length() - 1);
                                    break;
                                }
                                if (parts[i].charAt(index - 1) == 't' || parts[i].charAt(index - 1) == 'u') {
                                    if (parts[i].charAt(index - 2) == '6' || parts[i].charAt(index - 2) == '4') {
                                        String interNet = parts[i].substring(index + 1, parts[i].length() - 1);
                                        if (interNet != null) {
                                            String[] portsAndIp = getIPandPorts(interNet);             //0:src ip 1: src port 2:dest ip 3:dest port
                                            srcIP = portsAndIp[0];
                                            srcPort = portsAndIp[1];
                                            destIP = portsAndIp[2];
                                            destPort = portsAndIp[3];
                                            ip = interNet;
                                            break;
                                        }
                                    }
                                }
                            }
                            index++;
                        }
                    }
                    if (parts[i].startsWith("size")) {
                        if (parts[i].length() >= 6) {
                            String sub = parts[i].substring(5);
                            boolean allDigit = true;
                            for (int v = 0; v < sub.length(); v++) {
                                if (!Character.isDigit(sub.charAt(v))) {
                                    allDigit = false;
                                }
                            }
                            if (allDigit) {
                                size = Long.parseLong(sub);
                            }
                        }
                    }
                    if (parts[i].startsWith("latency")) {
                        int beginIndex = parts[i].indexOf("=");
                        latency = parts[i].substring(beginIndex + 1);
                        //System.out.println("latency is: "+latency);
                    }
                    if (event.equals("clone") && parts[i].startsWith("res")) {
                        String childProcess = parts[i].substring(4);
                        String[] childAndName = childProcess.split("\\(");
                        if (childAndName.length >= 2) {
                            pid2 = childAndName[0];
                            name2 = childAndName[1].substring(0, childAndName[1].length() - 1);
                        }

                    }
                    if (event.equals("execve") && parts[i].startsWith("ptid")) {
                        String parentProcess = parts[i].substring(5);
                        String[] ptidAndName = parentProcess.split("\\(");
                        pid2 = ptidAndName[0];
                        name2 = ptidAndName[1].substring(0, ptidAndName[1].length() - 1);
                    }
                }
            }
            if (!processHashMap.containsKey(pid + name)) {
                Process proc = new Process(reput, id, hopCount, pid, null, null, null, timestamp1,
                        timestamp2, name, uniqID++);
                processHashMap.put(proc.getPidAndName(), proc);
            }
            if (pid2 != null && name2 != null) {
                if (!processHashMap.containsKey(pid2 + name2)) {
                    Process child = new Process(reput, id, hopCount, pid2, null, null, null, timestamp1,
                            timestamp2, name2, uniqID++);
                    processHashMap.put(child.getPidAndName(), child);
                }
            }
            if (path != null) {
                if (!fileHashMap.containsKey(path)) {
                    FileEntity fileTarget = new FileEntity(reput, id, hopCount, timestamp1,
                            timestamp2, null, null, path, uniqID++);
                    fileHashMap.put(fileTarget.getPath(), fileTarget);
                }
            }
            if (srcIP != null && destIP != null) {
                if (!networkHashMap.containsKey(srcIP + ":" + srcPort + "->" + destIP + ":" + destPort)) {
                    NetworkEntity networkEntity = new NetworkEntity(reput, id, hopCount, timestamp1, timestamp2,
                            srcIP, destIP, srcPort, destPort, uniqID++);
                    networkHashMap.put(networkEntity.getSrcAndDstIP(), networkEntity);
                }
            }

        /* This part is to set up event*/
            if (PtoF.contains(event)) {
                if (direction.equals(">") && path != null) {
                    addProcessToFileEvent(pid, name, timestamp1, timestamp2, path, event, size, id);
                } else if (direction.equals("<") && latency != null) {
                    processToFileSetEnd(timestamp1, timestamp2, latency, id);
                }

            }
            if (PtoP.contains(event)) {
                if(direction.equals("<") && pid2!=null && name2!= null && !latency.equals("0")){
                    Process source = processHashMap.get(pid+name);
                    Process sink = processHashMap.get(pid2+name2);
                    String endTime = timestamp1+"."+timestamp2;
                    BigDecimal end = new BigDecimal(endTime);
                    BigDecimal duration = new BigDecimal(latency);
                    BigDecimal startTime = end.subtract(duration);
                    String start = startTime.toString();
                    String startS = start.split("\\.")[0];
                    String startMs = start.split("\\.")[1];
                    PtoPEvent pp = new PtoPEvent("Process To Process",startS,startMs,source,sink,event,id);
                    ppmap.put(start,pp);
                    pp.setEndTime(endTime);

                }
            }

            if ((event.equals("read") || event.equals("readv") || event.equals("recvfrom")) && direction.equals(">")) {
                if (path != null && pid != null && name != null) {
                    addFileToProcessEvent(pid, name, timestamp1, timestamp2, cpu, path, event, size, id);
                }

            }

            if (PtoN.contains(event) && ip != null && localIPS.contains(srcIP)) {
                if (direction.equals(">")) {
                    addProcessToNextworkEvent(srcIP, srcPort, destIP, destPort, pid, name,
                            timestamp1, timestamp2, size, event, cpu, id);
                }
            }

            if (NtoP.contains(event) && ip != null) {
                if (direction.equals(">")) {
                    addNetworkToProcessEvent(srcIP, srcPort, destIP, destPort, pid, name, timestamp1, timestamp2,
                            size, event, cpu, id);
                }
            }
            
//            if(event.equals("recvfrom") && ip!=null && localIPS.contains(srcIP)){
//                if (direction.equals(">")) {
//                    addNetworkToProcessEvent(srcIP, srcPort, destIP, destPort, pid, name, timestamp1, timestamp2,
//                            size, event, cpu, id);
//                }
//            }

            if (direction.equals("<") && latency != null) {
                BigDecimal duration = new BigDecimal(latency);
                duration = duration.scaleByPowerOfTen(-9);
                String end = timestamp1 + "." + timestamp2;
                BigDecimal endTime = new BigDecimal(end);
                BigDecimal startTime = endTime.subtract(duration);
                String start = startTime.toString();
                String startS = start.split("\\.")[0];
                String startMs = start.split("\\.")[1];
                //String key = pid + name + startS + startMs + cpu;
                String key = pid+name+startS+startMs;
                if (npmap.containsKey(key)) {
                    setNetworkToProcessEnd(npmap.get(key), timestamp1, timestamp2);
                } else if (fpmap.containsKey(key)) {
                    setFileToProcessEnd(fpmap.get(key), timestamp1, timestamp2);
                } else if (pnmap.containsKey(key)) {
                    setProcessToNetworkEnd(pnmap.get(key), timestamp1, timestamp2);
                }


            }
            if (event.equals("execve") && direction.equals("<")) {
                Process newProcss = processHashMap.get(pid + name);
                Process parent = processHashMap.get(pid2 + name2);
                String endTime = timestamp1 + "." + timestamp2;
                BigDecimal end = new BigDecimal(endTime);
                BigDecimal duration = new BigDecimal(latency);
                duration = duration.scaleByPowerOfTen(-9);
                BigDecimal start = end.subtract(duration);
                String startTime = start.toString();
                String startS = startTime.split("\\.")[0];
                String startMs = startTime.split("\\.")[1];
                PtoPEvent pp = new PtoPEvent("Process To Process", startS, startMs, parent, newProcss, event, id);
                pp.setEndTime(endTime);
                ppmap.put(timestamp1 + "." + startMs, pp);

            }


        }
    }

    @Override
    public void afterBuilding() {

    }


    private String[] getIPandPorts(String str){
        String[] res = new String[4];
        String[] srcAndDest = str.split("->");
        if(srcAndDest.length<2){
            System.out.println(str.length());
            System.out.println(str);
            throw new ArrayIndexOutOfBoundsException("wired form of IP");
        }
        String[] src = srcAndDest[0].split(":");
        String[] dest = srcAndDest[1].split(":");
        res[0] = src[0];
        res[1] = src[1];
        res[2] = dest[0];
        res[3] = dest[1];
        return res;
    }

    private void addProcessToFileEvent(String pid, String name, String timestamp1, String timestamp2,String path,
                                       String event, long size,long id){
        Process p = processHashMap.get(pid+name);
        FileEntity f = fileHashMap.get(path);
        String start = timestamp1+"."+timestamp2;
        PtoFEvent pf = new PtoFEvent("ProcessToFile",timestamp1,timestamp2,p,f,event,size,id);
        pfmap.put(start,pf);
    }

    private void processToFileSetEnd(String timestamp1, String timestamp2, String latency,long id){
        String end = timestamp1+"."+timestamp2;
        BigDecimal duration =new BigDecimal(latency);
        duration = duration.scaleByPowerOfTen(-9);
        BigDecimal endTime = new BigDecimal(end);
        BigDecimal startTime = endTime.subtract(duration);

        String key = startTime.toString();
        if(pfmap.containsKey(key)){
            pfmap.get(key).setEndTime(end);
        }else{
//            System.out.println(id);
//            System.out.println("process file map does not has the key");
            return;
        }
    }

    private void addProcessToProcessEvent(String pid, String name, String timestamp1, String timestamp2,String event,long id){
        Process source = processHashMap.get(pid+name);
        String start = timestamp1+"."+timestamp2;
        PtoPEvent pp = new PtoPEvent("Process To Process",timestamp1,timestamp2,source,null,event,id);
        ppmap.put(start,pp);

    }

    private void setProcessToProcessEventSinkAndEnd(String pid,String name,String timestamp1,String timestamp2,String latency){
        Process sink = processHashMap.get(pid+name);
        BigDecimal duration = new BigDecimal(latency);
        duration = duration.scaleByPowerOfTen(-9);
        String end = timestamp1+"."+timestamp2;
        BigDecimal endTime = new BigDecimal(end);
        BigDecimal startTime = endTime.subtract(duration);
        String key = startTime.toString();
        PtoPEvent event = null;
        if(ppmap.containsKey(key)){
            event = ppmap.get(key);
        }
        if(event!=null) {
            event.setSink(sink);
            event.setEndTime(end);
        }else{
//            System.out.println("Here is some pp event no start time");
        }
    }

    private void addFileToProcessEvent(String pid, String name,String timestamp1,String timestamp2,String cpu,
                                       String path, String event,long size,long id){
        Process sink = processHashMap.get(pid+name);
        if(sink == null){
            System.out.println("Pid and name not in Dict: "+pid+" "+name);
        }
        FileEntity source = fileHashMap.get(path);
        if(source == null){
            System.out.println("Path not int file Dict: "+ path);
        }
        FtoPEvent fp = new FtoPEvent("FileToProcess",timestamp1,timestamp2,source,sink,event,id);
        fp.updateSize(size);
        fpmap.put(pid + name + timestamp1 + timestamp2, fp);

    }

    private void addNetworkToProcessEvent(String srcIP,String srcP,String dstIp,String dstP,String pid,String name,
                                          String timestamp1,String timestamp2,long size,String event,String cpu,long id){
        NetworkEntity source = networkHashMap.get(srcIP+":"+srcP+"->"+dstIp+":"+dstP);
        Process sink = processHashMap.get(pid+name);
        NtoPEvent np = new NtoPEvent("NetworkToProcess",timestamp1,timestamp2,source,sink,event,id);
        np.updateSize(size);
        String key = pid+name+timestamp1+timestamp2;
        npmap.put(key,np);
    }

    private void setNetworkToProcessEnd(NtoPEvent np, String timestamp1,String timestamp2){
        String end = timestamp1+"."+timestamp2;
        np.setEndTime(end);
    }

    private void setFileToProcessEnd(FtoPEvent fp,String timestamp1,String timestamp2){
        String end = timestamp1+"."+timestamp2;
        fp.setEndTime(end);
    }

    private void addProcessToNextworkEvent(String srcIP,String srcP,String dstIp,String dstP,String pid,String name,
                                           String timestamp1,String timestamp2,long size,String event,String cpu,long id){
        Process source = processHashMap.get(pid+name);
        NetworkEntity sink = networkHashMap.get(srcIP+":"+srcP+"->"+dstIp+":"+dstP);
        PtoNEvent pn = new PtoNEvent("Process To Network", timestamp1, timestamp2,source,sink,event,id);
        pn.updateSize(size);
        String key = pid+name+timestamp1+timestamp2;
        pnmap.put(key,pn);
    }

    private void setProcessToNetworkEnd(PtoNEvent pn,String timestamp1,String timestamp2){
        pn.setEndTime(timestamp1+"."+timestamp2);
    }

}
