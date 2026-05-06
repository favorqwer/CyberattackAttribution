package pagerank.main;

import pagerank.config.GlobalConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Experiment - 实验配置类。
 *
 * 负责解析单个实验的 `.property` 配置文件，并提取当前仍在使用的参数。
 *
 * 配置示例：
 * <pre>
 * POI=/tmp/malicious_file.txt
 * highRP=192.168.1.100:44444,/tmp/mal
 * lowRP=/bin/ls,192.168.1.1:80
 * midRP=/lib64/libc.so.6
 * detectionSize=1024
 * entry=entry1,entry2
 * </pre>
 */
public class Experiment {
    Properties config;
    File configFile;
    File log;

    public String POI;
    public String[] highRP;
    public String[] lowRP;
    public String[] midRP;
    double detectionSize;
    public String[] entries;

    public Experiment(File logFile, File configFile) throws IOException {
        FileInputStream fi = new FileInputStream(configFile);
        log = logFile;
        this.configFile = configFile;
        config = new Properties();
        config.load(fi);
        digestConfig();
    }

    public Experiment(File configFile) throws IOException {
        FileInputStream fi = new FileInputStream(configFile);
        log = null;
        this.configFile = configFile;
        config = new Properties();
        config.load(fi);
        digestConfig();
    }

    /**
     * 解析当前实验仍使用的配置项：
     * `POI`、`highRP`、`lowRP`、`midRP`、`detectionSize`、`entry`。
     */
    private void digestConfig() {
        POI = config.getProperty("POI");

        String highRPString = config.getProperty("highRP", "");
        String[] parsedHighRP = highRPString.split(",");
        Set<String> highRPSet = new LinkedHashSet<>(Arrays.asList(parsedHighRP));
        if (POI != null && !POI.trim().isEmpty()) {
            highRPSet.add(POI.trim());
        }
        highRPSet.remove("");
        highRP = highRPSet.toArray(new String[0]);

        String lowRPString = config.getProperty("lowRP", "");
        lowRP = lowRPString.split(",");

        String[] defaultMidRP = GlobalConfig.getInstance().getMidRP();
        String midRPString = config.getProperty("midRP", "");
        String[] additionalMidRP = midRPString.split(",");
        List<String> tmp = new ArrayList<>();
        tmp.addAll(Arrays.asList(defaultMidRP));
        tmp.addAll(Arrays.asList(additionalMidRP));
        midRP = tmp.toArray(new String[0]);

        detectionSize = Double.parseDouble(config.getProperty("detectionSize", "0"));
        entries = config.getProperty("entry", "").split(",");
    }

    public Set<String> getInitial() {
        Set<String> res = new HashSet<>();
        for (String s : highRP) {
            res.add(s);
        }
        for (String s : lowRP) {
            res.add(s);
        }
        return res;
    }

    public List<String> getHighRP() {
        return Arrays.asList(highRP);
    }

    public List<String> getLowRP() {
        return Arrays.asList(lowRP);
    }

    public String[] getEntries() {
        String[] res = new String[entries.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = entries[i].trim();
        }
        return res;
    }
}
