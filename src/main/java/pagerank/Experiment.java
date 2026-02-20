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

public class Experiment {
    Properties config;
    File configFile;
    File log;
    public String POI;
    public String[] highRP;
    public String[] lowRP;
    public String[] midRP;
    double threshold;
    double detectionSize;
    boolean trackOrigin;
    String[] criticalEdges;
    public String[] criticalNodes;
    public String[] importantProcessStarts;
    public String[] importantFileStarts;
    public String[] importantIPStarts;
    public String[] entries;
    public String pathToDot;
    File dotFile;

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

    public void setPathToDot(String s) {
        pathToDot = s;
        dotFile = new File(pathToDot);
    }

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
