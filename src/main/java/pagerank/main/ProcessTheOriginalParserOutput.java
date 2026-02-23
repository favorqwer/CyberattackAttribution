package pagerank.main;
import pagerank.entity.Event;
import pagerank.entity.FtoPEvent;
import pagerank.entity.NtoPEvent;
import pagerank.entity.PtoFEvent;
import pagerank.entity.PtoNEvent;
import pagerank.entity.PtoPEvent;
import pagerank.algorithm.GetGraph;

import logparsers.SysdigOutputParser;
import logparsers.SysdigOutputParserNoRegex;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ProcessTheOriginalParserOutput - 原始日志解析结果处理类
 * 
 * 本类是日志解析器（SysdigOutputParser）和图构建器（GetGraph）之间的桥梁。
 * 负责：
 * 1. 调用日志解析器解析Sysdig格式的日志文件
 * 2. 获取5种事件类型的映射表
 * 3. 处理网络事件的方向（根据信息流方向）
 * 
 * 输出的5种事件映射表：
 * - processFileMap: 进程->文件事件（PtoF）
 * - processNetworkMap: 进程->网络事件（PtoN）
 * - processProcessMap: 进程->进程事件（PtoP）
 * - networkProcessMap: 网络->进程事件（NtoP）
 * - fileProcessMap: 文件->进程事件（FtoP）
 * 
 * @author fang
 * @date 2017/7/28
 */
public class ProcessTheOriginalParserOutput {
    // 进程->文件事件映射表（PtoF）
    private Map<String, PtoFEvent> processFileMap;
    // 进程->网络事件映射表（根据信息流方向）
    private Map<String, PtoNEvent> processNetworkMap;  // this event direction is decided by the information flow direction
    // 进程->进程事件映射表（PtoP）
    private Map<String, PtoPEvent> processProcessMap;
    // 网络->进程事件映射表（NtoP，根据信息流方向）
    private Map<String, NtoPEvent> networkProcessMap;  // this event direction is decided by the information flow direction
    // 文件->进程事件映射表（FtoP）
    private Map<String, FtoPEvent> fileProcessMap;
    // 进程->网络映射表（根据IP地址方向：本地->远程）
    private Map<String, PtoNEvent> pnmap;              // this one is just according to ip direction(local->remote)
    // 网络->进程映射表（根据IP地址方向：远程->本地）
    private Map<String, NtoPEvent> npmap;              // this one is just according to ip direction(remote -> local)
    // 日志解析器
    private SysdigOutputParser parser;

    /**
     * 构造函数
     * 
     * @param logFilePath 日志文件路径
     * @param localIP 本地IP地址数组
     */
    public ProcessTheOriginalParserOutput(String logFilePath, String[]localIP){
        // 创建Sysdig日志解析器
        parser = new SysdigOutputParserNoRegex(logFilePath,localIP);
        try {
            parser.getEntities();  // 开始解析日志文件
        }catch (IOException e){
            System.out.println("The log file doesn't exist");
        }
        // 获取各种事件映射表
        processFileMap = parser.getPfmap();   // PtoF事件
        pnmap = parser.getPnmap();            // 进程->网络（按IP方向）
        processProcessMap = parser.getPpmap(); // PtoP事件
        npmap = parser.getNpmap();            // 网络->进程（按IP方向）
        fileProcessMap = parser.getFpmap();   // FtoP事件
        
        // 初始化信息流方向的映射表（稍后可能通过reverseSourceAndSink调整）
        processNetworkMap = new HashMap<>();
        networkProcessMap = new HashMap<>();
    }
    public Map<String, PtoFEvent> getProcessFileMap() {
        return processFileMap;
    }

    public Map<String, PtoNEvent> getProcessNetworkMap() {
        return pnmap;
    }

    public Map<String, PtoPEvent> getProcessProcessMap() {
        return processProcessMap;
    }

    public Map<String, NtoPEvent> getNetworkProcessMap() {
        return npmap;
    }

    public Map<String, FtoPEvent> getFileProcessMap() {
        return fileProcessMap;
    }

    public Map<String, PtoNEvent> getPnmap() {
        return pnmap;
    }

    public Map<String, NtoPEvent> getNpmap() {
        return npmap;
    }

    public SysdigOutputParser getParser() {return parser;}

    /*reverse the network and process event according to the information flow*/
    public void reverseSourceAndSink(){
        Set<String> keys = pnmap.keySet();
        for(String key: keys){
            PtoNEvent event = pnmap.get(key);
            String eventType = event.getEvent();
            if(eventType.equals("recvmsg") || eventType.equals("read") || eventType.equals("recvfrom")){
                NtoPEvent reverse = new NtoPEvent(event);
                //pnmap.remove(key);
                networkProcessMap.put(key,reverse);
            }else{
                processNetworkMap.put(key,pnmap.get(key));
            }
        }
        keys = npmap.keySet();
        for(String key: keys){
            NtoPEvent event = npmap.get(key);
            String eventType = event.getEvent();
            if(eventType.equals("read") || eventType.equals("recvmsg")){
                PtoNEvent reverse = new PtoNEvent(event);
                //npmap.remove(key);
                processNetworkMap.put(key, reverse);
            }else{
                networkProcessMap.put(key, npmap.get(key));
            }
        }
        //System.out.println(pnmap.keySet().size());
//        Iterator<Map.Entry<String,PtoNEvent>> it = pnmap.entrySet().iterator();
//        while(it.hasNext()){
//            if(it.next().getValue()==null){
//                System.out.println("Event is NUll");
//            }
//            if(it.next().getValue().getEvent() == null){
//                System.out.println("Event type is null");
//            }
//            Map.Entry<String, PtoNEvent> cur = it.next();
//            PtoNEvent event = cur.getValue();
//            String eventType = event.getEvent();
//            String key = cur.getKey();
//            System.out.println(eventType);
//            if(eventType.equals("recvmsg") || eventType.equals("read")){
//                NtoPEvent reverse = new NtoPEvent(event);
//                //pnmap.remove(key);
//                //it.remove();
//                networkProcessMap.put(key,reverse);
//            }else{
//                processNetworkMap.put(key,pnmap.get(key));
//            }
//        }
//
//        Iterator<Map.Entry<String, NtoPEvent>> it2 = npmap.entrySet().iterator();
//        while(it2.hasNext()){
//            Map.Entry<String, NtoPEvent> cur = it2.next();
//            NtoPEvent event = cur.getValue();
//            String key = cur.getKey();
//            String eventType = event.getEvent();
//            if(eventType.equals("read") || eventType.equals("recvmsg")){
//                PtoNEvent reverse = new PtoNEvent(event);
//                //it2.remove();
//                processNetworkMap.put(key, reverse);
//            }else{
//                networkProcessMap.put(key, npmap.get(key));
//            }
//        }
    }

    public static  void main(String[] args) throws Exception{
        String[] localIP={"10.0.2.15"};
        ProcessTheOriginalParserOutput test = new ProcessTheOriginalParserOutput("pipInstall.txt",localIP);

        test.reverseSourceAndSink();
//        Map<String, PtoNEvent> pnmap = test.getPnmap();
//        System.out.println("-----------------------------");
//        for(String key:pnmap.keySet()){
//            System.out.println(pnmap.get(key).getUniqID());
//            System.out.println(pnmap.get(key).getEvent());
//        }
        System.out.println("--------------------------");
        Map<String, PtoNEvent> map = test.getProcessNetworkMap();
        System.out.println(map.size());
        for(String key:map.keySet()){
            System.out.println(map.get(key).getSink().getSrcAddress().equals(localIP[0]));
            System.out.println("Find the local ip");
        }
        Map<String, NtoPEvent> map2 = test.getNetworkProcessMap();
        for(String key:map2.keySet()){
            System.out.println(map2.get(key).getSource().getSrcAddress().equals(localIP[0]));
            System.out.println("Find the local ip");
        }
    }


}
