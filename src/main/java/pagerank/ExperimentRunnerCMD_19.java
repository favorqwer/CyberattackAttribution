package pagerank;

import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONObject;
import theia_exp.GraphReader;

import java.io.*;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;
import java.sql.*;
/*
Specify the path to log files and result directories. Either a list of log file names or a FileNameFormatter is acceptable.
The directory of log must also contains configuration files with the same name as the corresponding log but ends with ".property".
 */
public class ExperimentRunnerCMD_19 {
    //public static String mode;
    DirectedPseudograph<EntityNode, EventEdge> graphFromLog;

    public static void main(String[] args){
        for(String arg : args)
            System.out.println(arg);

//        if(args.length!=4){
//            System.err.println("Usage: ExperimentRunnerCMD_19 <log path> <result path> <log names,...> <mode>");
//            System.exit(-1);
//        }
        try{
//            String logPath = args[0];
//            String resPath = args[1];
//            String[] logs = args[2].split(";");
//            mode = args[3];
//            String logPath = "/home/pxf109/sysrep_exp_env/vpnfilter";
//            String resPath = "/home/pxf109/sysrep_exp_env/temp_text";
//            String logPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\vpnfilter";
//            String resPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\results\\vpnfilter_filtertest";
            String logPath = "/home/pxf109/TheiaQuery/theia-case1";
            String resPath = "/home/pxf109/TheiaQuery/theia-case1-res";
             //String[] logs = {"vpnfilter.txt"};
            String dotPath = "/home/pxf109/TheiaQuery/theia-case1/case1_test.dot";

            ExperimentRunnerCMD_19 er = new ExperimentRunnerCMD_19( resPath,logPath, dotPath);
            //er.run2("nodoze");
            er.run_with_backwardgraph("clusterall");
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    String PathToLogs;
    String PathToRes;
    FilenameFilter logNameFilter;
    String backWardDotPath;

    public ExperimentRunnerCMD_19(String pathToLogs, String pathToRes, FilenameFilter logNameFilter){
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        this.logNameFilter = logNameFilter;
    }

    public ExperimentRunnerCMD_19(String pathTologs, String pathToRes, String[] lognames){
        PathToLogs = pathTologs;
        PathToRes = pathToRes;
        Set<String> logset = new HashSet<>();
        for(String s: lognames) logset.add(s);
        logNameFilter = (dir,name) -> logset.contains(name);
    }

    public ExperimentRunnerCMD_19(String pathToLogs, String pathToRes, FilenameFilter logNameFilter, String[] exclusion){
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        Set<String> exclusionSet = new HashSet<>();
        for(String s: exclusion) exclusionSet.add(s);
        this.logNameFilter = (dir, name) -> logNameFilter.accept(dir,name) && !exclusionSet.contains(name);
    }

    public ExperimentRunnerCMD_19(String pathToRes, String pathToLogs, String dotPath) {
        PathToRes = pathToRes;
        backWardDotPath = dotPath;
        this.PathToLogs = pathToLogs;
    }

    //run Reptracker directly on the graph after backtrack, no parsing
    public void run_with_backwardgraph (String mode) throws FileNotFoundException {
        File resDir = makeResDir(PathToRes);
        List<Experiment> experiments_backward = new ArrayList<>();
        PrintStream logStream;
        try {
            List<File> propertyFiles = getPropertyFilesWithoutLog(PathToLogs);
            for (File f : propertyFiles) {
                if(f.getName().indexOf("backward")!=-1){
                    Experiment exp = new Experiment(f);
                    exp.setPathToDot(backWardDotPath);
                    experiments_backward.add(exp);
                }
            }

            for(Experiment e: experiments_backward){
                File oneRes = new File(resDir + "/" + e.configFile.getParentFile().getName() + "-" + e.configFile.getName().split("_")[1]);
                if (!oneRes.exists())
                    oneRes.mkdir();
                System.out.println(e.pathToDot);
                System.out.println(e.dotFile.getName());
                File logFile = new File(oneRes.getAbsolutePath() + "/" + e.dotFile.getName().split("\\.")[0] + ".log");
                logStream = new PrintStream(new FileOutputStream(logFile, true));
                logging_without_parsing(e, logFile);
                LogStream ls = new LogStream(System.out,logStream);
                LogStream lse = new LogStream(System.err,logStream);
                System.setOut(ls);
                System.setErr(lse);
                JSONObject jsonLog = new JSONObject();
                jsonLog.put("Case", e.dotFile.getName());
                jsonLog.put("Mode", mode);
                GraphReader graphReader = new GraphReader();
                DirectedPseudograph<EntityNode, EventEdge> backtrack = graphReader.readGraph(backWardDotPath);
                ProcessOneLogCMD_19.run_exp_backward_without_backtrack(backtrack,oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.dotFile.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.dotFile.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, mode, jsonLog, e.getEntries());

//                ProcessOneLogCMD_19.process_backward(oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, "nonml");
                logStream.close();
                Timestamp currentTimestamp= ExperimentRunnerCMD_19.getTimeStamp();
                jsonLog.put("Timestamp", currentTimestamp.toString());
                File entryPointsJsonFile = new File(oneRes.getAbsolutePath()+"/"+e.configFile.getName().split("\\.")[0]+"_json_log.json");
                FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
                jsonWriter.write(jsonLog.toJSONString());
                jsonWriter.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //Fang: for benign cases: avoid to parse log several times
    public void run2(String mode) throws FileNotFoundException{
        File resDir = makeResDir(PathToRes);
        File[] logs = getLogs(PathToLogs);
        File log = logs[0];
        List<Experiment> experiments_backward = new ArrayList<>();
        PrintStream logStream;
        try(FileOutputStream fo = new FileOutputStream(this.PathToRes+"/build_time.log")){
            long start = System.currentTimeMillis();
            GetGraph generator = new GetGraph(logs[0].getPath(), MetaConfig.localIP);
            long end = System.currentTimeMillis();
            fo.write(("parsing time:"+(end-start)/1000.0).getBytes());
            start = System.currentTimeMillis();
            generator.GenerateGraph();
            end = System.currentTimeMillis();
            fo.write(("building time:"+(end-start)/1000.0).getBytes());
            graphFromLog = generator.getJg();
        }catch(IOException e){}

        try{
            List<File> propertyFiles = getPropertyFiles(log);
            for(File propertyFile:propertyFiles){
                String propertyName = propertyFile.getName();
                if(propertyName.indexOf("backward")!=-1){
                    experiments_backward.add(new Experiment(log, propertyFile));
                }
            }

            for(Experiment e: experiments_backward){
                File oneRes = new File(resDir + "/" + e.configFile.getParentFile().getName() + "-" + e.configFile.getName().split("_")[1]);
                if (!oneRes.exists())
                    oneRes.mkdir();

                File logFile = new File(oneRes.getAbsolutePath() + "/" + e.log.getName().split("\\.")[0] + ".log");
                logStream = new PrintStream(new FileOutputStream(logFile, true));
                logging(e, logFile);
                LogStream ls = new LogStream(System.out,logStream);
                LogStream lse = new LogStream(System.err,logStream);
                System.setOut(ls);
                System.setErr(lse);
                JSONObject jsonLog = new JSONObject();
                jsonLog.put("Case", e.log.getName());
                jsonLog.put("Mode", mode);
                ProcessOneLogCMD_19.run_exp_backward(graphFromLog,oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, mode, jsonLog, e.getEntries());

//                ProcessOneLogCMD_19.process_backward(oneRes.getAbsolutePath() + "/", "", e.threshold, e.trackOrigin, e.log.getAbsolutePath(), MetaConfig.localIP, e.POI, e.highRP, e.midRP, e.lowRP, e.log.getName().split("\\.")[0], e.detectionSize, e.getInitial(), e.criticalEdges, "nonml");
                logStream.close();
                Timestamp currentTimestamp= ExperimentRunnerCMD_19.getTimeStamp();
                jsonLog.put("Timestamp", currentTimestamp.toString());
                File entryPointsJsonFile = new File(oneRes.getAbsolutePath()+"/"+e.configFile.getName().split("\\.")[0]+"_json_log.json");
                FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
                jsonWriter.write(jsonLog.toJSONString());
                jsonWriter.close();
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }


    private void logging(Experiment e, File logFile) throws IOException{
        Logger logger = Logger.getLogger(e.configFile.getName());
        FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath());
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new LogFormatter());
        logger.addHandler(fileHandler);

        logger.info("#############Configurations#############");
        logger.info("Log File: "+e.log.getAbsolutePath());
        logger.info("Config File: "+e.configFile.getAbsolutePath());
        logger.info("POI: "+e.POI);
        logger.info("Local IP: "+formatArray(MetaConfig.localIP));
        logger.info("High RP: "+formatArray(e.highRP));
        logger.info("Low RP: "+formatArray(e.lowRP));
        logger.info("Threshold: "+String.valueOf(e.threshold));
        logger.info("Track Origin: "+String.valueOf(e.trackOrigin));
        logger.info("Detection size: "+String.valueOf(e.detectionSize));
        logger.info("Seeds: "+formatArray(e.getInitial().toArray(new String[e.getInitial().size()])));
        logger.info("##############Sysdig Parser#############");
        logger.info("P2P: "+formatArray(MetaConfig.ptopSystemCall));
        logger.info("P2F: "+formatArray(MetaConfig.ptofSystemCall));
        logger.info("F2P: "+formatArray(MetaConfig.ftopSystemCall));
        logger.info("P2N: "+formatArray(MetaConfig.ptonSystemCall));
        logger.info("N2P: "+formatArray(MetaConfig.ntopSystemCall));
        logger.info("########################################");
        logger.info("Mid RP: "+formatArray(e.midRP));
        logger.info("########################################");
    }

    private void logging_without_parsing(Experiment e, File logFile) throws IOException{
        Logger logger = Logger.getLogger(e.configFile.getName());
        FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath());
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(new LogFormatter());
        logger.addHandler(fileHandler);

        logger.info("#############Configurations#############");
        logger.info("Config File: "+e.configFile.getAbsolutePath());
        logger.info("POI: "+e.POI);
        logger.info("Local IP: "+formatArray(MetaConfig.localIP));
        logger.info("High RP: "+formatArray(e.highRP));
        logger.info("Low RP: "+formatArray(e.lowRP));
        logger.info("Threshold: "+String.valueOf(e.threshold));
        logger.info("Track Origin: "+String.valueOf(e.trackOrigin));
        logger.info("Detection size: "+String.valueOf(e.detectionSize));
        logger.info("Seeds: "+formatArray(e.getInitial().toArray(new String[e.getInitial().size()])));
        logger.info("##############Sysdig Parser#############");
        logger.info("P2P: "+formatArray(MetaConfig.ptopSystemCall));
        logger.info("P2F: "+formatArray(MetaConfig.ptofSystemCall));
        logger.info("F2P: "+formatArray(MetaConfig.ftopSystemCall));
        logger.info("P2N: "+formatArray(MetaConfig.ptonSystemCall));
        logger.info("N2P: "+formatArray(MetaConfig.ntopSystemCall));
        logger.info("########################################");
        logger.info("Mid RP: "+formatArray(e.midRP));
        logger.info("########################################");
    }

    private String formatArray(String[] array){
        return String.join(",",array);
    }

    private File makeResDir(String pathToRes){
        File resDir = new File(PathToRes);
        if(!resDir.exists() || !resDir.isDirectory())
            resDir.mkdir();
        else System.out.println("Result Directory "+pathToRes+" already exists, overwriting!");
        return resDir;
    }

    private File[] getLogs(String pathToLogs) throws FileNotFoundException{
        File logDir = new File(pathToLogs);
        if(!logDir.exists() || !logDir.isDirectory())
            throw new FileNotFoundException("Invalid directory: "+logDir);
        return logDir.listFiles(logNameFilter);
    }

    class LogFormatter extends Formatter {
        // Create a DateFormat to format the logger timestamp.
        private DateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");

        public String format(LogRecord record) {
            StringBuilder builder = new StringBuilder();
            builder.append(df.format(new java.util.Date(record.getMillis()))).append(" - ");
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
    public static Timestamp getTimeStamp(){
        Calendar calendar = Calendar.getInstance();
        Timestamp currentTimestamp = new java.sql.Timestamp(calendar.getTime().getTime());
        return currentTimestamp;
    }

    public List<File> getPropertyFiles(File logFile){
        System.out.println(logFile.getName());
        File[] files = logFile.getParentFile().listFiles();
        List<File> propertyFiles = new ArrayList<>();
        String caseName = logFile.getName().split("\\.")[0];
        for(File f : files){
            String name = f.getName();
            if(name.indexOf(caseName)!= -1 && name.indexOf("property")!=-1){
                propertyFiles.add(f);
            }
        }
        return propertyFiles;
    }

    public List<File> getPropertyFilesWithoutLog(String pathToLogs) {
        List<File> propertyFiles = new ArrayList<>();
        try {
            File logFolder = new File(pathToLogs);
            File[] files = logFolder.listFiles();
            for (File f : files) {
                String name = f.getName();
                if(name.indexOf("property") != -1) {
                    propertyFiles.add(f);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return propertyFiles;
    }
}
