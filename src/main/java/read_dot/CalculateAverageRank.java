package read_dot;

import org.jgrapht.graph.DirectedPseudograph;
import pagerank.Experiment;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class CalculateAverageRank {
    public static String findCorrespondingProperty(String caseName, String propertyFolder) throws Exception{
        Map<String, String> res = new HashMap<>();
        File folder = new File(propertyFolder);
        for(File f : folder.listFiles()){
            String caseName2 = caseName.split("-")[1];
            if(f.getAbsolutePath().indexOf(caseName2)!=-1){
                return f.getAbsolutePath();
            }
        }
        return "Not find the corresponding property file";
    }

    public static void main(String[] args) throws Exception{
        String caseFolder = "D:\\cluster_all_plus_filter";
        String propertyFolder = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\large_properties";
        File folder = new File(caseFolder);
        Map<String, String> resFolderToProperty = new HashMap<>();
        for (File f: folder.listFiles()){
            if(f.isDirectory()) {
                resFolderToProperty.put(f.getAbsolutePath(),findCorrespondingProperty(f.getAbsolutePath(), propertyFolder));
            }
        }
        String[] category = {"IP Start", "File Start", "Process Start"};
        JSONObject averageRanking = new JSONObject();
        for(String k : resFolderToProperty.keySet()){
            int rankSum = 0;
            Experiment exp = new Experiment(new File(resFolderToProperty.get(k)));
            String[] entries = exp.getEntries();
            ReadGraphFromDot readGraphFromDot = new ReadGraphFromDot();
            String caseName = k.split("-")[1];
            //DirectedPseudograph<SimpleNode, SimpleEdge> graphAfterCPR =  readGraphFromDot.readGraph(k+"/"+"AfterCPR_"+caseName+".dot");
            List<String> vertex = new LinkedList<>();
            File candidiates = new File(k+"/"+caseName+"_entry_points.json");
            FileReader fileReader = new FileReader(candidiates);
            JSONParser jsonParser = new JSONParser();
            JSONObject candidateJson = (JSONObject) jsonParser.parse(fileReader);
            JSONArray entryPoints  = (JSONArray) candidateJson.get("EntryPoints");
            fileReader.close();
            int time = 100;
            List<Integer> res = new LinkedList<>();
            for(int i = 0; i < entryPoints.size(); i++){
                vertex.add(entryPoints.get(i).toString());
            }
            for (int i = 0; i < time; i++){
                Collections.shuffle(vertex);
                for(String entry : entries){
                    int index = vertex.indexOf(entry);
                    res.add(index);
                }
            }
            int sum = 0;
            for(Integer t : res){
                sum += t;
            }
            double average = sum/(res.size()*1.0);
            System.out.println(k+" Random Rank: ");
            System.out.println(average);
            JSONObject resJson = new JSONObject();
            resJson.put("Average Random Rank", average);
            resJson.put("Random Times", time);
            File rankRes = new File(k+"/"+"RandomRankAverage_"+caseName+".json");
            FileWriter fileWriter = new FileWriter(rankRes);
            fileWriter.write(resJson.toJSONString());
            fileWriter.close();
        }
    }
}
