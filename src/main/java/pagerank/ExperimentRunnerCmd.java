package pagerank;

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

/*
Specify the path to log files and result directories. Either a list of log file names or a FileNameFormatter is acceptable.
The directory of log must also contains configuration files with the same name as the corresponding log but ends with ".property".
 */
public class ExperimentRunnerCmd {
    public static String mode;
    public static String do_split;
    DirectedPseudograph<EntityNode, EventEdge> graphFromLog;
    String PathToLogs;
    String PathToRes;
    FilenameFilter logNameFilter;

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

        for (String arg : args)
            System.out.println(arg);

        if (args.length != 3) {
            System.err.println("Usage: ExperimentRunnerCmd <log path> <result path> <log names,...>");
            System.exit(-1);
        }
        try {
            String logPath = args[0];
            String resPath = args[1];
            String[] logs = args[2].split(";");
            ExperimentRunnerCmd er = new ExperimentRunnerCmd(logPath, resPath, logs);
            er.run2();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public ExperimentRunnerCmd(String pathToLogs, String pathToRes, FilenameFilter logNameFilter) {
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        this.logNameFilter = logNameFilter;
    }

    public ExperimentRunnerCmd(String pathTologs, String pathToRes, String[] lognames) {
        PathToLogs = pathTologs;
        PathToRes = pathToRes;
        Set<String> logset = new HashSet<>();
        for (String s : lognames) logset.add(s);
        logNameFilter = (dir, name) -> logset.contains(name);
    }

    public ExperimentRunnerCmd(String pathToLogs, String pathToRes, FilenameFilter logNameFilter, String[] exclusion) {
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        Set<String> exclusionSet = new HashSet<>();
        for (String s : exclusion) exclusionSet.add(s);
        this.logNameFilter = (dir, name) -> logNameFilter.accept(dir, name) && !exclusionSet.contains(name);
    }

    //Fang: for benign cases: avoid to parse log several times
    public void run2() throws FileNotFoundException {
        //todo 此处固定mode
        mode = "clusterall";
        File resDir = makeResDir(PathToRes);
        File[] logs = getLogs(PathToLogs);
        File log = logs[0];
        List<Experiment> experiments = new ArrayList<>();
        List<Experiment> experiments_backward = new ArrayList<>();
        PrintStream logStream;

        try {
            GetGraph generator = new GetGraph(logs[0].getPath(), MetaConfig.localIP);
            generator.GenerateGraph();  // 生成图结构
            graphFromLog = generator.getJg();  // 获取生成的图
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            File[] propertyFiles = log.getParentFile().listFiles((dir, name) ->
                    (name.split("\\.")[0].equals(log.getName().split("\\.")[0]) || name.startsWith(log.getName().split("\\.")[0] + ":")) && name.endsWith(".property"));
            for (File propertyFile : propertyFiles) {
                String propertyName = propertyFile.getName();
                if (propertyName.indexOf("backward") != -1) {
                    experiments_backward.add(new Experiment(log, propertyFile));// 创建反向实验
                }
            }

            for (Experiment e : experiments_backward) {
                File oneRes = new File(resDir + "/" + e.configFile.getParentFile().getName() + "-" + e.configFile.getName().split("\\.")[0]);
                if (!oneRes.exists())
                    oneRes.mkdir();

                File logFile = new File(oneRes.getAbsolutePath() + "/" + e.log.getName().split("\\.")[0] + ".log");
                logStream = new PrintStream(new FileOutputStream(logFile, true));
               // logging(e, logFile);
                LogStream ls = new LogStream(System.out, logStream);
                LogStream lse = new LogStream(System.err, logStream);
                System.setOut(ls);
                System.setErr(lse);
                // 执行反向分析实验
                JSONObject jsonLog = new JSONObject();
                jsonLog.put("Case", e.log.getName());
                jsonLog.put("Mode", mode);
                ProcessOneLogCMD_19.run_exp_backward(graphFromLog, oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, mode, jsonLog, e.getEntries());

//                ProcessOneLogCMD_19.process_backward(oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, "nonml");
                logStream.close();

                // 保存JSON格式的日志结果
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

    class LogFormatter extends Formatter {
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

    class LogStream extends PrintStream {
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
