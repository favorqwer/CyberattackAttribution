package pagerank.main;

import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ExperimentRunnerCmd {
    private static final String INTERACTIVE_FLAG = "--interactive";
    private static final String INTERACTIVE_SHORT_FLAG = "-i";

    public static String mode;
    public static String cprMode;
    public static double cprTimeWindow;

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
            printUsage();
            System.exit(-1);
        }

        try {
            String logPath = args[0];
            String resPath = args[1];
            String[] logs;
            if (isInteractiveFlag(args[2])) {
                logs = new String[]{selectLogInteractively(logPath)};
            } else {
                logs = args[2].split(";");
            }

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
        cprMode = resolveCprMode();
        cprTimeWindow = resolveCprTimeWindow();
        String[] localIP = GlobalConfig.getInstance().getLocalIP();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        File resDir = makeResDir(PathToRes);
        File[] logs = getLogs(PathToLogs);

        for (File log : logs) {
            try {
                runSingleLog(log, resDir, localIP, originalOut, originalErr);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private void runSingleLog(File log, File resDir, String[] localIP, PrintStream originalOut,
                              PrintStream originalErr) throws IOException {
        List<Experiment> experimentsBackward = new ArrayList<>();
        String logBaseName = getBaseName(log.getName());

        try {
            GetGraph generator = new GetGraph(log.getPath(), localIP);
            generator.GenerateGraph();
            graphFromLog = generator.getJg();
        } catch (Exception e) {
            throw new IOException("Failed to generate graph for log: " + log.getAbsolutePath(), e);
        }

        File[] propertyFiles = log.getParentFile().listFiles((dir, name) ->
                isMatchingPropertyFile(name, logBaseName));

        if (propertyFiles != null) {
            for (File propertyFile : propertyFiles) {
                experimentsBackward.add(new Experiment(log, propertyFile));
            }
        }

        JSONArray summaryExperiments = new JSONArray();
        JSONArray entryPointExperiments = new JSONArray();

        for (Experiment e : experimentsBackward) {
            File oneRes = new File(resDir, getBaseName(e.log.getName()));
            if (!oneRes.exists()) {
                oneRes.mkdir();
            }

            JSONObject jsonLog = new JSONObject();
            jsonLog.put("Case", e.log.getName());
            jsonLog.put("Scenario", getBaseName(e.configFile.getName()));
            jsonLog.put("Mode", mode);
            jsonLog.put("CPRMode", cprMode);
            jsonLog.put("CPRTimeWindow", cprTimeWindow);
            JSONObject entryPointsLog = new JSONObject();
            entryPointsLog.put("Case", e.log.getName());
            entryPointsLog.put("Scenario", getBaseName(e.configFile.getName()));

            File logFile = new File(oneRes.getAbsolutePath() + "/" + getBaseName(e.log.getName()) + ".log");
            try (PrintStream logStream = new PrintStream(new FileOutputStream(logFile, true))) {
                LogStream ls = new LogStream(originalOut, logStream);
                LogStream lse = new LogStream(originalErr, logStream);
                System.setOut(ls);
                System.setErr(lse);

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
                        cprMode,
                        cprTimeWindow,
                        jsonLog,
                        entryPointsLog,
                        e.getEntries()
                );
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }

            Timestamp currentTimestamp = getTimeStamp();
            jsonLog.put("Timestamp", currentTimestamp.toString());
            entryPointsLog.put("Timestamp", currentTimestamp.toString());
            summaryExperiments.add(jsonLog);
            entryPointExperiments.add(entryPointsLog);
        }

        File oneRes = new File(resDir, getBaseName(log.getName()));
        if (!oneRes.exists()) {
            oneRes.mkdir();
        }
        JSONObject summaryRoot = new JSONObject();
        summaryRoot.put("Case", log.getName());
        summaryRoot.put("Experiments", summaryExperiments);
        File summaryFile = new File(oneRes, "summary.json");
        try (FileWriter writer = new FileWriter(summaryFile)) {
            writer.write(summaryRoot.toJSONString());
        }

        JSONObject entryPointsRoot = new JSONObject();
        entryPointsRoot.put("Case", log.getName());
        entryPointsRoot.put("Experiments", entryPointExperiments);
        File entryPointsFile = new File(oneRes, "entry_points.json");
        try (FileWriter writer = new FileWriter(entryPointsFile)) {
            writer.write(entryPointsRoot.toJSONString());
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

    private String resolveCprMode() {
        String configuredCprMode = GlobalConfig.getInstance().getCprMode();
        System.out.println("CPR mode: " + configuredCprMode);
        return configuredCprMode.trim();
    }

    private double resolveCprTimeWindow() {
        double configuredCprTimeWindow = GlobalConfig.getInstance().getCprTimeWindow();
        System.out.println("CPR time window: " + configuredCprTimeWindow + "(s)");
        return configuredCprTimeWindow;
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
        File[] logs = logDir.listFiles(logNameFilter);
        if (logs == null || logs.length == 0) {
            throw new FileNotFoundException("No matching log file found in directory: " + logDir.getAbsolutePath());
        }
        return logs;
    }

    private static boolean isInteractiveFlag(String arg) {
        return INTERACTIVE_FLAG.equalsIgnoreCase(arg) || INTERACTIVE_SHORT_FLAG.equalsIgnoreCase(arg);
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  ExperimentRunnerCmd <log path> <result path> <log names,...>");
        System.err.println("  ExperimentRunnerCmd <log path> <result path> " + INTERACTIVE_FLAG);
    }

    private static String selectLogInteractively(String logPath) throws IOException {
        File[] selectableLogs = getSelectableLogs(logPath);
        if (selectableLogs.length == 0) {
            throw new FileNotFoundException("No traceable log files found in directory: " + new File(logPath).getAbsolutePath());
        }

        System.out.println("Available logs:");
        for (int i = 0; i < selectableLogs.length; i++) {
            System.out.printf(Locale.ROOT, "%d. %s%n", i + 1, selectableLogs[i].getName());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("Enter a log number or file name: ");
            String input = reader.readLine();
            if (input == null) {
                throw new IOException("No input received from console.");
            }

            String trimmedInput = input.trim();
            if (trimmedInput.isEmpty()) {
                System.out.println("Input cannot be empty. Please try again.");
                continue;
            }

            Integer selectedIndex = tryParsePositiveInt(trimmedInput);
            if (selectedIndex != null && selectedIndex >= 1 && selectedIndex <= selectableLogs.length) {
                return selectableLogs[selectedIndex - 1].getName();
            }

            for (File selectableLog : selectableLogs) {
                if (selectableLog.getName().equals(trimmedInput)) {
                    return selectableLog.getName();
                }
            }

            System.out.println("Invalid selection. Please enter a listed number or file name.");
        }
    }

    private static File[] getSelectableLogs(String pathToLogs) throws FileNotFoundException {
        File logDir = new File(pathToLogs);
        if (!logDir.exists() || !logDir.isDirectory()) {
            throw new FileNotFoundException("Invalid directory: " + logDir);
        }

        File[] logFiles = logDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (logFiles == null) {
            return new File[0];
        }

        return Arrays.stream(logFiles)
                .filter(File::isFile)
                .filter(ExperimentRunnerCmd::hasMatchingPropertyFile)
                .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                .toArray(File[]::new);
    }

    private static boolean hasMatchingPropertyFile(File logFile) {
        File parentDir = logFile.getParentFile();
        if (parentDir == null) {
            return false;
        }

        String logBaseName = getBaseName(logFile.getName());
        File[] propertyFiles = parentDir.listFiles((dir, name) -> isMatchingPropertyFile(name, logBaseName));
        return propertyFiles != null && propertyFiles.length > 0;
    }

    private static Integer tryParsePositiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String getBaseName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    private static boolean isMatchingPropertyFile(String propertyFileName, String logBaseName) {
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
