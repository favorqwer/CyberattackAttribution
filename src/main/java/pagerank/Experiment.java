package pagerank;

import org.apache.commons.math3.analysis.function.Exp;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.*;

/**
 * Experiment - 实验配置类
 * 
 * 本类负责解析配置文件（.property文件），提取溯源分析所需的各种参数。
 * 
 * 配置文件格式示例（wget.backward文件）：
 * <pre>
 * POI=/tmp/malicious_file.txt          # 检测点（恶意文件/网络连接）
 * highRP=/bin/ls,192.168.1.1:80       # 高可信实体（正常进程/可信IP）
 * lowRP=192.168.1.100:44444           # 低可信实体（可疑IP/临时文件）
 * midRP=/lib64/libc.so.6              # 中可信实体（可选，扩展白名单）
 * detectionSize=1024                  # 检测到的数据量
 * threshold=0                         # 阈值
 * trackOrigin=false                   # 是否追踪源头
 * criticalEdge=edge1;edge2            # 关键边（用于评估）
 * entry=entry1,entry2                 # 预定义的入口点
 * </pre>
 * 
 * 配置项说明：
 * - POI: Point of Interest，检测点/恶意事件，是溯源分析的起点
 * - highRP: 高可信实体列表，声誉分数初始化为1.0
 * - lowRP: 低可信实体列表，声誉分数初始化为0.0  
 * - midRP: 中可信实体列表，声誉分数初始化为0.5
 * - detectionSize: 检测到的数据量（字节），用于计算数据量权重
 * - threshold: 阈值参数
 * - trackOrigin: 是否追踪源头
 * - criticalEdge: 关键边，用于评估溯源结果的准确性
 * - entry: 预定义的入口点（用于验证）
 * 
 * @author fang
 */
public class Experiment {
    // 配置属性对象
    Properties config;
    // 配置文件对象
    File configFile;
    // 日志文件对象
    File log;
    // POI检测点（恶意事件）
    public String POI;
    // 高可信实体数组
    public String[] highRP;
    // 低可信实体数组
    public String[] lowRP;
    // 中可信实体数组
    public String[] midRP;
    // 阈值
    double threshold;
    // 检测数据量
    double detectionSize;
    // 是否追踪源头
    boolean trackOrigin;
    // 关键边数组（用于评估）
    String[] criticalEdges;
    // 关键节点数组
    public String[] criticalNodes;
    // 重要进程入口
    public String[] importantProcessStarts;
    // 重要文件入口
    public String[] importantFileStarts;
    // 重要IP入口
    public String[] importantIPStarts;
    // 预定义入口点
    public String[] entries;
    // DOT文件路径
    public String pathToDot;
    // DOT文件对象
    File dotFile;

    /**
     * 构造函数 - 从日志文件和配置文件创建实验
     * 
     * @param logFile 日志文件
     * @param configFile 配置文件（.property）
     * @throws IOException 如果文件读取失败
     */
    public Experiment(File logFile, File configFile) throws IOException {
        FileInputStream fi = new FileInputStream(configFile);
        log = logFile;
        this.configFile = configFile;
        config = new Properties();
        config.load(fi);
        digestConfig();
    }

    /**
     * 构造函数 - 仅从配置文件创建实验
     * 
     * @param configFile 配置文件
     * @throws IOException 如果文件读取失败
     */
    public Experiment(File configFile) throws IOException {
        FileInputStream fi = new FileInputStream(configFile);
        log = null;
        this.configFile = configFile;
        config = new Properties();
        config.load(fi);
        digestConfig();
    }

    public void setPathToDot(String s) {
        pathToDot = s;
        dotFile = new File(pathToDot);
    }

    /**
     * digestConfig - 解析配置文件方法
     * 
     * 从配置文件中读取并解析各个配置项：
     * - POI: 检测点
     * - highRP: 高可信实体（逗号分隔）
     * - lowRP: 低可信实体
     * - midRP: 中可信实体（合并默认配置和自定义配置）
     * - threshold: 阈值
     * - trackOrigin: 是否追踪源头
     * - detectionSize: 检测数据量
     * - criticalEdge: 关键边（分号分隔）
     * - criticalNodes: 关键节点
     * - importantFileStarts: 重要文件入口
     * - importantProcessStarts: 重要进程入口
     * - importantIPStarts: 重要IP入口
     * - entry: 预定义入口点
     */
    private void digestConfig(){
        POI = config.getProperty("POI");

        String highRPString = config.getProperty("highRP","");
        highRP = highRPString.split(",");

        String lowRPString = config.getProperty("lowRP","");
        lowRP = lowRPString.split(",");

        String[] defaultMidRP = MetaConfig.midRP;
        String midRPString = config.getProperty("midRP","");
        String[] additionalMidRP = midRPString.split(",");
        List<String> _ = new ArrayList<>();
        _.addAll(Arrays.asList(defaultMidRP));
        _.addAll(Arrays.asList(additionalMidRP));
        midRP = _.toArray(new String[_.size()]);

        threshold = Double.parseDouble(config.getProperty("threshold","0"));

        trackOrigin = Boolean.parseBoolean(config.getProperty("trackOrigin","false"));

        detectionSize = Double.parseDouble(config.getProperty("detectionSize", "0"));

        criticalEdges = config.getProperty("criticalEdge","").split(";");

        criticalNodes = config.getProperty("criticalNodes","").split(",");

        importantFileStarts = config.getProperty("importantFileStarts", "").split(",");

        importantProcessStarts = config.getProperty("importantProcessStarts", "").split(",");

        importantIPStarts = config.getProperty("importantIPStarts", "").split(",");

        entries = config.getProperty("entry", "").split(",");
    }

    // get signature of seed sources
    public Set<String> getInitial(){
        Set<String> res = new HashSet<>();
        for(String s: highRP){
            res.add(s);
        }
        for(String s : lowRP){
            res.add(s);
        }
        return res;
    }

    public List<String> getHighRP(){
        List<String> res = Arrays.asList(highRP);
        return res;
    }

    public List<String> getLowRP(){
        List<String> res = Arrays.asList(lowRP);
        return res;
    }

    public String[] getCriticalEdges(){
        String[] copyOfCriticalEdges = Arrays.copyOf(criticalEdges, criticalEdges.length);
        return copyOfCriticalEdges;
    }

    public String[] getEntries(){
        String[] res = new String[entries.length];
        for(int i = 0; i < res.length; i++){
            res[i] = entries[i].trim();
        }
        return res;
    }

}
