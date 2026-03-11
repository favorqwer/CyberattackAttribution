package pagerank.main;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;
import pagerank.algorithm.GetGraph;
import pagerank.config.MetaConfig;

import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONObject;

import java.io.*;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;

/**
 * ExperimentRunnerCmd - 实验运行命令行入口类
 *
 * 本类是整个DepImpact网络攻击溯源系统的主入口，负责：
 * 1. 解析命令行参数（日志路径、结果目录、日志文件名）
 * 2. 加载配置文件（.property文件，包含POI、highRP、lowRP等配置）
 * 3. 初始化图生成器并构建依赖图
 * 4. 为每个配置运行反向溯源分析实验
 * 5. 输出结果到指定目录
 *
 * 使用方式：
 *   java ExperimentRunnerCmd <日志路径> <结果路径> <日志文件名(多个用分号分隔)>
 *
 * 示例：
 *   java ExperimentRunnerCmd ./input/logs_fine ./output wget.txt
 *
 * 配置文件说明：
 * - 与日志同名的.property文件（如wget.txt对应的wget.backward文件）
 * - 以.backward结尾表示反向溯源分析
 * - 配置文件包含：POI（检测点）、highRP（高可疑/恶意实体）、lowRP（良性/可信实体）等
 */
public class ExperimentRunnerCmd {
    // 权重计算模式：clusterall, nonml, clusterlocal, fanout等
    // 参见ProcessOneLogCMD_19中的mode参数说明
    public static String mode;
    public static String do_split;
    // 从日志文件构建的依赖图（整个项目只构建一次，提高效率）
    DirectedPseudograph<EntityNode, EventEdge> graphFromLog;
    // 日志文件所在目录路径
    String PathToLogs;
    // 结果输出目录路径
    String PathToRes;
    // 日志文件名过滤器（用于筛选要处理的日志文件）
    FilenameFilter logNameFilter;

    /**
     * 主方法 - 程序入口点
     *
     * @param args 命令行参数，包含三个必需参数：
     *              args[0]: 日志文件所在目录路径
     *              args[1]: 结果输出目录路径
     *              args[2]: 要处理的日志文件名（多个用分号分隔，如"wget.txt;curl.txt"）
     *
     * 处理流程：
     * 1. 初始化Graphviz引擎（用于后续图可视化）
     * 2. 解析命令行参数
     * 3. 创建ExperimentRunnerCmd实例并调用run2()执行实验
     */
    public static void main(String[] args) {
        // 关闭 GraalVM "解释模式" 的性能警告
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");

// 1. 备份原本的输出流 (为了待会儿恢复)
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            // 2. 制造一个“黑洞”流，吃掉所有输出
            PrintStream nullStream = new PrintStream(new OutputStream() {
                public void write(int b) {}
            });

            // 3. 将 stdout 和 stderr 全部重定向到黑洞
            System.setOut(nullStream);
            System.setErr(nullStream);

            // 4. 核心步骤：在这里强制初始化 Graphviz V8 引擎
            // 此时产生的所有 INFO 日志和 JVM Stack Guard 警告都会被黑洞吞掉
            Graphviz.useEngine(new GraphvizV8Engine());

        } catch (Throwable t) {
            // 忽略初始化过程中的任何错误，保持静默
        } finally {
            // 5. 【关键】立即恢复原本的输出流
            // 确保你程序后续正常的业务输出（如果有）能显示出来
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        // 打印命令行参数（用于调试）
        for (String arg : args)
            System.out.println(arg);

        // 检查参数数量
        if (args.length != 3) {
            System.err.println("Usage: ExperimentRunnerCmd <log path> <result path> <log names,...>");
            System.exit(-1);
        }
        try {
            // 解析命令行参数
            String logPath = args[0];       // 日志目录路径
            String resPath = args[1];       // 结果输出目录路径
            String[] logs = args[2].split(";");  // 日志文件名数组（支持多个）
            
            // 创建实验运行器实例
            ExperimentRunnerCmd er = new ExperimentRunnerCmd(logPath, resPath, logs);
            // 执行实验主流程
            er.run2();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 构造函数 - 使用FilenameFilter过滤日志文件
     * @param pathToLogs 日志目录路径
     * @param pathToRes 结果输出目录路径
     * @param logNameFilter 日志文件名过滤器
     */
    public ExperimentRunnerCmd(String pathToLogs, String pathToRes, FilenameFilter logNameFilter) {
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        this.logNameFilter = logNameFilter;
    }

    /**
     * 构造函数 - 根据日志文件名数组过滤
     * @param pathTologs 日志目录路径
     * @param pathToRes 结果输出目录路径
     * @param lognames 日志文件名数组
     */
    public ExperimentRunnerCmd(String pathTologs, String pathToRes, String[] lognames) {
        PathToLogs = pathTologs;
        PathToRes = pathToRes;
        Set<String> logset = new HashSet<>();
        for (String s : lognames) logset.add(s);
        // 创建过滤器：只接受指定名称的日志文件
        logNameFilter = (dir, name) -> logset.contains(name);
    }

    /**
     * 构造函数 - 根据日志文件名数组过滤，同时排除某些文件
     * @param pathToLogs 日志目录路径
     * @param pathToRes 结果输出目录路径
     * @param logNameFilter 基础过滤器
     * @param exclusion 要排除的日志文件名数组
     */
    public ExperimentRunnerCmd(String pathToLogs, String pathToRes, FilenameFilter logNameFilter, String[] exclusion) {
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        Set<String> exclusionSet = new HashSet<>();
        for (String s : exclusion) exclusionSet.add(s);
        this.logNameFilter = (dir, name) -> logNameFilter.accept(dir, name) && !exclusionSet.contains(name);
    }

    /**
     * run2 - 主实验执行方法
     * 
     * 这是核心执行流程，主要步骤如下：
     * 1. 创建结果目录
     * 2. 获取日志文件列表
     * 3. 一次性构建完整依赖图（GetGraph），避免重复解析日志
     * 4. 查找所有配置文件（.property或.backward文件）
     * 5. 对每个配置文件创建Experiment对象并运行反向溯源分析
     * 6. 保存JSON格式的实验结果
     * 
     * @throws FileNotFoundException 如果日志目录不存在则抛出异常
     */
    //Fang: for benign cases: avoid to parse log several times
    public void run2() throws FileNotFoundException {
        mode = resolveMode();
        
        // 1. 创建结果输出目录
        File resDir = makeResDir(PathToRes);
        
        // 2. 获取日志目录下的所有日志文件
        File[] logs = getLogs(PathToLogs);
        File log = logs[0];
        
        // 实验列表（虽然声明了但主要使用experiments_backward）
        List<Experiment> experiments = new ArrayList<>();
        // 反向溯源实验列表
        List<Experiment> experiments_backward = new ArrayList<>();
        PrintStream logStream;

        try {
            // 3. 【关键】一次性构建完整依赖图
            // GetGraph负责解析Sysdig日志，构建进程-文件-网络实体之间的依赖图
            // 这个图会被所有实验共享，避免重复解析日志文件
            GetGraph generator = new GetGraph(logs[0].getPath(), MetaConfig.localIP);
            generator.GenerateGraph();  // 生成图结构
            graphFromLog = generator.getJg();  // 获取生成的图
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            // 4. 查找配置文件
            // 在日志所在目录查找与日志同名的.property文件
            // 例如：wget.txt 对应 wget.backward.property
            File[] propertyFiles = log.getParentFile().listFiles((dir, name) ->
                    (name.split("\\.")[0].equals(log.getName().split("\\.")[0]) || name.startsWith(log.getName().split("\\.")[0] + ":")) && name.endsWith(".property"));
            
            // 5. 遍历所有配置文件，创建反向实验
            for (File propertyFile : propertyFiles) {
                String propertyName = propertyFile.getName();
                // 以"backward"结尾的配置文件表示反向溯源分析
                if (propertyName.indexOf("backward") != -1) {
                    experiments_backward.add(new Experiment(log, propertyFile));// 创建反向实验
                }
            }

            // 6. 依次运行每个反向实验
            for (Experiment e : experiments_backward) {
                // 为每个实验创建独立的输出目录
                // 目录命名格式：<配置文件父目录名>-<配置文件名(无后缀)>
                File oneRes = new File(resDir + "/" + e.configFile.getParentFile().getName() + "-" + e.configFile.getName().split("\\.")[0]);
                if (!oneRes.exists())
                    oneRes.mkdir();

                // 创建日志文件，记录实验过程
                File logFile = new File(oneRes.getAbsolutePath() + "/" + e.log.getName().split("\\.")[0] + ".log");
                logStream = new PrintStream(new FileOutputStream(logFile, true));
               // logging(e, logFile);
                // 同时输出到控制台和日志文件
                LogStream ls = new LogStream(System.out, logStream);
                LogStream lse = new LogStream(System.err, logStream);
                System.setOut(ls);
                System.setErr(lse);
                
                // 创建JSON对象记录实验结果
                JSONObject jsonLog = new JSONObject();
                jsonLog.put("Case", e.log.getName());
                jsonLog.put("Mode", mode);
                
                // 7. 【核心】执行反向溯源分析实验
                // 参数说明：
                // - graphFromLog: 之前构建的完整依赖图
                // - oneRes.getAbsolutePath(): 结果输出目录
                // - e.threshold: 阈值
                // - e.trackOrigin: 是否追踪源头
                // - e.log.getAbsolutePath(): 日志文件路径
                // - MetaConfig.localIP: 本地IP地址列表
                // - e.POI: Point of Interest，检测点/恶意事件
                // - e.highRP: 高可疑实体列表（已知恶意/可疑，如POI、可疑IP、临时文件，reputation=1.0）
                // - e.midRP: 背景噪音实体列表（系统库等）
                // - e.lowRP: 低可疑实体列表（已知良性/可信，如正常系统进程，reputation=0.0）
                // - e.detectionSize: 检测到的数据量
                // - e.getInitial(): 初始种子源
                // - e.criticalEdges: 关键边（用于评估）
                // - mode: 权重计算模式
                // - jsonLog: JSON日志对象
                // - e.getEntries(): 预定义的重要入口点
                ProcessOneLogCMD_19.run_exp_backward(graphFromLog, oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, mode, jsonLog, e.getEntries());

//                ProcessOneLogCMD_19.process_backward(oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, "nonml");
                logStream.close();

                // 8. 保存JSON格式的实验结果
                Timestamp currentTimestamp = getTimeStamp();
                jsonLog.put("Timestamp", currentTimestamp.toString());
                File entryPointsJsonFile = new File(oneRes.getAbsolutePath() + "/" + e.configFile.getName().split("\\.")[0] + "_json_log.json");
                FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
                jsonWriter.write(jsonLog.toJSONString());
                jsonWriter.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Timestamp getTimeStamp() {
        Calendar calendar = Calendar.getInstance();
        Timestamp currentTimestamp = new java.sql.Timestamp(calendar.getTime().getTime());
        return currentTimestamp;
    }

    private String resolveMode() {
        String configuredMode = System.getProperty("depimpact.mode");
        if (configuredMode == null || configuredMode.trim().isEmpty()) {
            configuredMode = System.getenv("DEPIMPACT_MODE");
        }
        if (configuredMode == null || configuredMode.trim().isEmpty()) {
            configuredMode = "adaptivefusion";
        }
        System.out.println("Weight mode: " + configuredMode);
        return configuredMode.trim();
    }

    private void logging(Experiment e, File logFile) throws IOException {
        Logger logger = Logger.getLogger(e.configFile.getName());
        FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath());
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new LogFormatter());
        logger.addHandler(fileHandler);

        logger.info("#############Configurations#############");
        logger.info("Log File: " + e.log.getAbsolutePath());
        logger.info("Config File: " + e.configFile.getAbsolutePath());
        logger.info("POI: " + e.POI);
        logger.info("Local IP: " + formatArray(MetaConfig.localIP));
        logger.info("High RP: " + formatArray(e.highRP));
        logger.info("Low RP: " + formatArray(e.lowRP));
        logger.info("Threshold: " + String.valueOf(e.threshold));
        logger.info("Track Origin: " + String.valueOf(e.trackOrigin));
        logger.info("Detection size: " + String.valueOf(e.detectionSize));
        logger.info("Seeds: " + formatArray(e.getInitial().toArray(new String[e.getInitial().size()])));
        logger.info("##############Sysdig Parser#############");
        logger.info("P2P: " + formatArray(MetaConfig.ptopSystemCall));
        logger.info("P2F: " + formatArray(MetaConfig.ptofSystemCall));
        logger.info("F2P: " + formatArray(MetaConfig.ftopSystemCall));
        logger.info("P2N: " + formatArray(MetaConfig.ptonSystemCall));
        logger.info("N2P: " + formatArray(MetaConfig.ntopSystemCall));
        logger.info("########################################");
        logger.info("Mid RP: " + formatArray(e.midRP));
        logger.info("########################################");
    }

    private String formatArray(String[] array) {
        return String.join(",", array);
    }

    private File makeResDir(String pathToRes) {
        File resDir = new File(PathToRes);
        if (!resDir.exists() || !resDir.isDirectory())
            resDir.mkdir();
        else System.out.println("Result Directory " + pathToRes + " already exists, overwriting!");
        return resDir;
    }

    private File[] getLogs(String pathToLogs) throws FileNotFoundException {
        File logDir = new File(pathToLogs);
        if (!logDir.exists() || !logDir.isDirectory())
            throw new FileNotFoundException("Invalid directory: " + logDir);
        return logDir.listFiles(logNameFilter);
    }

    public class LogFormatter extends Formatter {
        // Create a DateFormat to format the logger timestamp.
        private DateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");

        public String format(LogRecord record) {
            StringBuilder builder = new StringBuilder();
            builder.append(df.format(new Date(record.getMillis()))).append(" - ");
            builder.append(formatMessage(record));
            builder.append("\n");
            return builder.toString();
        }
    }

    public class LogStream extends PrintStream {
        PrintStream out;

        public LogStream(PrintStream out1, PrintStream out2) {
            super(out1);
            this.out = out2;
        }

        public void write(byte buf[], int off, int len) {
            try {
                super.write(buf, off, len);
                out.write(buf, off, len);
            } catch (Exception e) {
            }
        }

        public void flush() {
            super.flush();
            out.flush();
        }

    }
}
