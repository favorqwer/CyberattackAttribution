package pagerank.main;

import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONObject;
import pagerank.algorithm.GetGraph;
import pagerank.config.GlobalConfig;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExperimentRunnerCmd {
    public static String mode;

    DirectedPseudograph<EntityNode, EventEdge> graphFromLog;
    String PathToLogs;
    String PathToRes;
    FilenameFilter logNameFilter;

    public static void main(String[] args) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            PrintStream nullStream = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });

            System.setOut(nullStream);
            System.setErr(nullStream);
            Graphviz.useEngine(new GraphvizV8Engine());
        } catch (Throwable t) {
            // Keep startup quiet when Graphviz initialization emits warnings.
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        for (String arg : args) {
            System.out.println(arg);
        }

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
        for (String s : lognames) {
            logset.add(s);
        }
        logNameFilter = (dir, name) -> logset.contains(name);
    }

    public ExperimentRunnerCmd(String pathToLogs, String pathToRes, FilenameFilter logNameFilter, String[] exclusion) {
        PathToLogs = pathToLogs;
        PathToRes = pathToRes;
        Set<String> exclusionSet = new HashSet<>();
        for (String s : exclusion) {
            exclusionSet.add(s);
        }
        this.logNameFilter = (dir, name) -> logNameFilter.accept(dir, name) && !exclusionSet.contains(name);
    }

    // Fang: for benign cases: avoid parsing the log several times.
    public void run2() throws FileNotFoundException {
        mode = resolveMode();
        String[] localIP = GlobalConfig.getInstance().getLocalIP();

        File resDir = makeResDir(PathToRes);
        File[] logs = getLogs(PathToLogs);
        File log = logs[0];

        List<Experiment> experimentsBackward = new ArrayList<>();
        PrintStream logStream;

        try {
            GetGraph generator = new GetGraph(logs[0].getPath(), localIP);
            generator.GenerateGraph();
            graphFromLog = generator.getJg();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            String logBaseName = getBaseName(log.getName());
            File[] propertyFiles = log.getParentFile().listFiles((dir, name) ->
                    isMatchingPropertyFile(name, logBaseName));

            if (propertyFiles != null) {
                for (File propertyFile : propertyFiles) {
                    experimentsBackward.add(new Experiment(log, propertyFile));
                }
            }

            for (Experiment e : experimentsBackward) {
                File oneRes = new File(resDir + "/" + e.configFile.getParentFile().getName() + "-" + getBaseName(e.configFile.getName()));
                if (!oneRes.exists()) {
                    oneRes.mkdir();
                }

                File logFile = new File(oneRes.getAbsolutePath() + "/" + getBaseName(e.log.getName()) + ".log");
                logStream = new PrintStream(new FileOutputStream(logFile, true));
                LogStream ls = new LogStream(System.out, logStream);
                LogStream lse = new LogStream(System.err, logStream);
                System.setOut(ls);
                System.setErr(lse);

                JSONObject jsonLog = new JSONObject();
                jsonLog.put("Case", e.log.getName());
                jsonLog.put("Mode", mode);

                ProcessOneLogCMD_19.run_exp_backward(
                        graphFromLog,
                        oneRes.getAbsolutePath() + "/",
                        "",
                        e.threshold,
                        e.trackOrigin,
                        e.log.getAbsolutePath(),
                        localIP,
                        e.POI,
                        e.highRP,
                        e.midRP,
                        e.lowRP,
                        getBaseName(e.log.getName()),
                        e.detectionSize,
                        e.getInitial(),
                        e.criticalEdges,
                        mode,
                        jsonLog,
                        e.getEntries()
                );
                logStream.close();

                Timestamp currentTimestamp = getTimeStamp();
                jsonLog.put("Timestamp", currentTimestamp.toString());
                File entryPointsJsonFile = new File(oneRes.getAbsolutePath() + "/" + getBaseName(e.configFile.getName()) + "_json_log.json");
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
        return new Timestamp(calendar.getTime().getTime());
    }

    private String resolveMode() {
        String configuredMode = GlobalConfig.getInstance().getWeightMode();
        System.out.println("Weight mode: " + configuredMode);
        return configuredMode.trim();
    }

    private File makeResDir(String pathToRes) {
        File resDir = new File(PathToRes);
        if (!resDir.exists() || !resDir.isDirectory()) {
            resDir.mkdir();
        } else {
            System.out.println("Result Directory " + pathToRes + " already exists, overwriting!");
        }
        return resDir;
    }

    private File[] getLogs(String pathToLogs) throws FileNotFoundException {
        File logDir = new File(pathToLogs);
        if (!logDir.exists() || !logDir.isDirectory()) {
            throw new FileNotFoundException("Invalid directory: " + logDir);
        }
        return logDir.listFiles(logNameFilter);
    }

    private String getBaseName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    private boolean isMatchingPropertyFile(String propertyFileName, String logBaseName) {
        return propertyFileName.endsWith(".property")
                && (propertyFileName.equals(logBaseName + ".property")
                || propertyFileName.startsWith(logBaseName + ":"));
    }

    public class LogStream extends PrintStream {
        PrintStream out;

        public LogStream(PrintStream out1, PrintStream out2) {
            super(out1);
            this.out = out2;
        }

        public void write(byte[] buf, int off, int len) {
            try {
                super.write(buf, off, len);
                out.write(buf, off, len);
            } catch (Exception e) {
                // Ignore logging failures to avoid breaking the pipeline.
            }
        }

        public void flush() {
            super.flush();
            out.flush();
        }
    }
}
