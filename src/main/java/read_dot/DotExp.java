package read_dot;
import org.jgrapht.graph.DirectedPseudograph;
import pagerank.EntityNode;
import pagerank.EventEdge;
import pagerank.Experiment;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.util.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.swing.text.html.HTMLDocument;

/*read dot graph with final wegiht, set initial reputation for seed, propagate reputation*/
public class DotExp {
    File dotFile;                                  // graph
    File propertyFile;
    DirectedPseudograph<SimpleNode, SimpleEdge> graph;
    ReadGraphFromDot readDot;
    SeedSetter seedSetter;
    Experiment exp;

    DotExp(String dotFile, String propertyFile){
        this.dotFile = new File(dotFile);
        this.propertyFile = new File(propertyFile);
        readDot = new ReadGraphFromDot();
        seedSetter = new SeedSetter();
        graph = readDot.readGraph(dotFile);
        clearInitialReputation();
        String logName = "curl";

        File logFile = new File(logName);
        try {
            exp = new Experiment(logFile, this.propertyFile);
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public void startExp(){
        seedSetter.setSeedReputation(graph, exp);
        PageRankIteration2(exp.highRP, exp.midRP, exp.lowRP, exp.POI);
        outputRes();
    }

    public void outputRes(){
        String caseName = getCaseName();
        Double reputaion = null;
        Integer libraryCount = 0;
        Integer highAndLowSeed = 0;
        Set<SimpleNode> vertexSet = graph.vertexSet();
        List<String> high = exp.getHighRP();
        for (SimpleNode v : vertexSet){
            if(v.signature.equals(exp.POI)){
                reputaion = v.reputation;
            }
            if(graph.incomingEdgesOf(v).size() == 0){
                libraryCount++;
            }
        }
        for(String s : exp.highRP){
            if (!s.equals("")){
                highAndLowSeed++;
            }
        }
        for(String s : exp.lowRP){
            if(!s.equals("")){
                highAndLowSeed++;
            }
        }
        JSONObject resJson = new JSONObject();
        resJson.put("Case", caseName);
        resJson.put("POI_Reputation", reputaion);
        resJson.put("Library_Count", libraryCount);
        resJson.put("Special_Seed", highAndLowSeed);
        String jsonFileName = caseName+".json";
        try{
            FileWriter file = new FileWriter(jsonFileName);
            file.write(resJson.toJSONString());
            file.close();
        }catch (Exception e){
            e.printStackTrace();
        }

    }



    /*The graph reading from dot may have initial reputation, clear it*/
    private void clearInitialReputation(){
        for (SimpleNode v : graph.vertexSet()){
            v.reputation = 0.0;
        }
    }


    public void PageRankIteration2(String[] highRP,String[] midRP, String[] lowRP,String detection){
        double alarmlevel = 0.85;
        Set<SimpleNode> vertexSet = graph.vertexSet();
        Set<String> sources = new HashSet<>(Arrays.asList(highRP));
        sources.addAll(Arrays.asList(lowRP));
        sources.addAll(Arrays.asList(midRP));
        double fluctuation = 1.0;
        int iterTime = 0;
        while(fluctuation>=1e-5){
            double culmativediff = 0.0;
            iterTime++;
            Map<Long, Double> preReputation = getReputation();
            for(SimpleNode v: vertexSet){
                if(v.signature.equals(detection))
                    System.out.println(v.reputation);
                if(sources.contains(v.signature)) continue;
                Set<SimpleEdge> edges = graph.incomingEdgesOf(v);
                double rep = 0.0;
                // edges == 0, v is a seed
                if(edges.size() == 0)
                    rep = v.reputation;
                for(SimpleEdge edge: edges){
                    SimpleNode source = edge.from;
                    rep += (preReputation.get(source.id)* edge.weight);
                }
//                rep = rep*alarmlevel+0.5*(1-alarmlevel);
                culmativediff += Math.abs(rep-preReputation.get(v.id));
                v.reputation = rep;
            }
            fluctuation = culmativediff;
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }
    private Map<Long, Double> getReputation(){
        Set<SimpleNode> vertexSet = graph.vertexSet();
        Map<Long, Double> map = new HashMap<>();
        for(SimpleNode node:vertexSet){
            map.put(node.id, node.reputation);
        }
        return map;
    }

    private String getCaseName(){
        String caseName = dotFile.getName();
        System.out.println(caseName);
        String[] arr = caseName.split("\\.");
        return arr[0];
    }

    public static void main(String[] args){
        String dotFile = "C:\\Users\\fang2\\Desktop\\reptrack_ccs2\\reptracker\\input\\Exp_using_dot\\dot_files\\ThreeFileRW_ggg.dot";
        String pro = "C:\\Users\\fang2\\Desktop\\reptrack_ccs2\\reptracker\\input\\Exp_using_dot\\properties_for_all\\ThreeFileRW_ggg.property";
        DotExp exp = new DotExp(dotFile, pro);
        exp.startExp();

        // this file map dot to property
//        String json_file = "C:\\Users\\fang2\\Desktop\\reptrack_ccs2\\reptracker\\input\\Exp_using_dot\\dot_to_property.json";
//        try{
//            Reader reader = new FileReader(json_file);
//            JSONParser jsonParser = new JSONParser();
//            Object object = jsonParser.parse(reader);
//            JSONObject jsonObject = (JSONObject) object;
//            Iterator<String> iter = jsonObject.keySet().iterator();
//            while (iter.hasNext()){
//                String key = iter.next();
//                String val = jsonObject.get(key).toString();
//                DotExp exp = new DotExp(key, val);
//                exp.startExp();
//
//            }
//        }catch (Exception e){
//            e.printStackTrace();
//        }
    }
}
