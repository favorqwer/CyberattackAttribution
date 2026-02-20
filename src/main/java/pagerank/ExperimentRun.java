package pagerank; /**
 * Created by fang on 3/4/18.
 */
//import org.junit.Test;

import java.io.*;
import java.util.*;

public class ExperimentRun {
    private File foler;
    private Map<String, String> nameToFile;     // key is filename val is File fullPath
    private String fileOfPoiFile;
    private File POIfile;
    private Map<String, String> Pois;     // key is fileName val is Poi event(fileName, IP...)
    public String[] localIP;
    private Map<String, List<Integer>> results;


    ExperimentRun(String folderPath, String fileOfPoi){
        foler = new File(folderPath);
        fileOfPoiFile = fileOfPoi;
        POIfile = new File(fileOfPoiFile);
        nameToFile = new HashMap<>();
        Pois = new HashMap<>();
        results = new HashMap<>();
        File[] files = foler.listFiles();
        for(File f : files){
            nameToFile.put(f.getName(), f.getAbsolutePath());
        }
        try {
            BufferedReader reader = new BufferedReader(new FileReader(POIfile));
            String line = reader.readLine();
            while(line != null){
                String[] strs = line.split(":");
                Pois.put(strs[0], strs[1]);
                line = reader.readLine();
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void printFileNames(){
        for(String s: nameToFile.keySet()){
            System.out.println(nameToFile.get(s));
        }
    }

    public void printPois(){
        for(String s : Pois.keySet()){
            System.out.println(s);
        }
    }

    public void readLocalIP(String[] s){
        localIP = s;
    }

    public void generateOrigianlGraph(){
        if(localIP == null || localIP.length == 0){
            System.out.println("Need ip input");
        }
        for(String s : nameToFile.keySet()){
            GetGraph getGraph = new GetGraph(nameToFile.get(s), localIP);
            String filePath = nameToFile.get(s);
            getGraph.GenerateGraph();
            if(!results.containsKey(filePath)) {
                results.put(nameToFile.get(s), new ArrayList<Integer>());
            }
            results.get(filePath).add(getGraph.jg.vertexSet().size());
            results.get(filePath).add(getGraph.jg.edgeSet().size());
            String POISignature = Pois.get(s);
            BackTrack backTrack = new BackTrack(getGraph.getOriginalGraph());
            backTrack.backTrackPOIEvent(POISignature);
            results.get(filePath).add(backTrack.afterBackTrack.vertexSet().size());
            results.get(filePath).add(backTrack.afterBackTrack.edgeSet().size());
            CausalityPreserve reduction = new CausalityPreserve(backTrack.afterBackTrack);
            reduction.CPR(1);
            results.get(filePath).add(reduction.afterMerge.vertexSet().size());
            results.get(filePath).add(reduction.afterMerge.edgeSet().size());
            GraphSplit split = new GraphSplit(reduction.afterMerge);
            split.splitGraph();
            results.get(filePath).add(split.inputGraph.vertexSet().size());
            results.get(filePath).add(split.inputGraph.edgeSet().size());

        }
        System.out.println(results);
    }

    public void outputResult() throws IOException{
        PrintWriter printWriter = new PrintWriter("result.txt", "UTF-8");
        for(String s : results.keySet()){
            List<Integer> list = results.get(s);
            String result = String.format("%s, %s, %d, %d, %s, %d, %d,%s, %d, %d,%s, %d, %d",s,"original size",list.get(0),list.get(1),
                    "after backtrack", list.get(2),list.get(3), "after CPR", list.get(4), list.get(5),"after rebuild logical",
                    list.get(6), list.get(7));
            printWriter.println(result);
        }
        printWriter.close();

    }




    public static void main(String[] args){
        String path = "/home/fang/thesis2/Data/Expdata2";
        String pathOfPois = "/home/fang/thesis2/Data/Poievents.txt";
        String[] locapIPS = {"10.0.2.15"};
        ExperimentRun expRun = new ExperimentRun(path,pathOfPois);
        expRun.readLocalIP(locapIPS);
        expRun.generateOrigianlGraph();
        try {
            expRun.outputResult();
        }catch ( Exception e){
            e.printStackTrace();
        }

//        expRun.printFileNames();
//        System.out.println("......................");
//        expRun.printPois();
    }
}
