package read_dot;

import net.bytebuddy.dynamic.scaffold.MethodGraph;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import pagerank.Experiment;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.stream.Collectors;

public class DotFilterExp{
    public File resFolder;
    public File propertyFolder;



    DotFilterExp(String resPath, String propertyFolder){
        resFolder = new File(resPath);
        this.propertyFolder = new File(propertyFolder);
        ReadGraphFromDot dotreader = new ReadGraphFromDot();
        try{
            if(!resFolder.isDirectory()){
                throw new Exception("Input resPath is not a folder path");
            }
            if(!this.propertyFolder.isDirectory()){
                throw new Exception("Input propertyFolder is not a folder path");
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }



    public List<File> findFilteredGraph(String path) {
        File folder = new File(path);
        if (!folder.isDirectory()){
            System.out.println("The input path is not a folder");
        }
        List<File> CPR = new LinkedList<>();
        for(File f: Objects.requireNonNull(folder.listFiles())){
            if (f.isDirectory()){
                List<File> subCPR = findFilteredGraph(f.getAbsolutePath());
                CPR.addAll(subCPR);
            }else{
                if(f.getAbsolutePath().indexOf("filtered")!=-1){
                    CPR.add(f);
                }
            }
        }
        return CPR;
    }


    public Map<String, String> getCorrespondingPropertyFile(List<File> cpr_graphs){
        List<File> cprParentFolder = cpr_graphs.stream().map(f -> f.getParentFile()).collect(Collectors.toList());
        List<String> caseName = new ArrayList<>(cprParentFolder.size());
        Map<String, String> pathMap = new HashMap<>();
        for(int i=0; i < cprParentFolder.size(); i++){
            File parent = cprParentFolder.get(i);
            String name = parent.getAbsolutePath().split("-")[1];
            for(File property : propertyFolder.listFiles()){
                if(property.getName().indexOf("property")!=-1) {
                    String caseProperty = property.getName().split("_")[1];
                    if (name.indexOf(caseProperty) != -1) {
                        pathMap.put(cpr_graphs.get(i).getAbsolutePath(), property.getAbsolutePath());
                    }
                }
            }
        }
        return pathMap;
    }

    public void run_exp(String path, String propertyPath) throws Exception{
        String json_log_path = getfilterResCorrespondingJsonLog(path);
        JSONParser jsonParser = new JSONParser();
        FileReader fileReader = new FileReader(json_log_path);
        JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
        int cprgraphsize = Integer.valueOf((String)json_log.get("CPREdgeNumber"));
        DotFilterExp exp = new DotFilterExp(path, propertyPath);
        JSONObject jsonRes = new JSONObject();
        List<File> filteredRes = exp.findFilteredGraph(path);
        Map<String, String> correspondingToProperty = getCorrespondingPropertyFile(filteredRes);
        for(String filterGraph: correspondingToProperty.keySet()){
            JSONObject curCaseRes = new JSONObject();
            ReadGraphFromDot reader = new ReadGraphFromDot();
            DirectedPseudograph<SimpleNode, SimpleEdge> graph = reader.readGraph(filterGraph);
            System.out.println(correspondingToProperty.get(filterGraph));
            Experiment experiment = new Experiment(new File(correspondingToProperty.get(filterGraph)));
            String[] criticalEdges = experiment.getCriticalEdges();
            Set<String> critialSet = addCriticalEdgesToSet(criticalEdges);
            Set<String> edgesInFilterGraph = graph.edgeSet().stream().map(e -> e.toString()).collect(Collectors.toSet());
            double missingRate = calculateMissingRate(critialSet, edgesInFilterGraph);
            double redundantRate=calculateRedundantRate(critialSet, edgesInFilterGraph);
            double reductionRateCPR = calculateReductionRateComparedWithCPR(critialSet, edgesInFilterGraph, cprgraphsize);
            curCaseRes.put("missing", missingRate);
            curCaseRes.put("redundant", redundantRate);
            curCaseRes.put("reductionCPR", reductionRateCPR);
            curCaseRes.put("EdgeNumber", edgesInFilterGraph.size());
            jsonRes.put(filterGraph, curCaseRes);
        }
        File resFile = new File(path+"/"+"filter_res.json");
        FileWriter fileWriter = new FileWriter(resFile);
        fileWriter.write(jsonRes.toJSONString());
        fileWriter.close();

    }
    public double calculateReductionRateComparedWithCPR(Set<String> critical, Set<String>edge, int totalAfterCPR){
        return (totalAfterCPR-edge.size())*1.0/(totalAfterCPR);
    }
    public double calculateMissingRate(Set<String> critical, Set<String>edge){
        double res = 0.0;
        for(String s : critical){
            if(!edge.contains(s)){
                res += 1.0;
            }
        }
        return res/critical.size();
    }

    public double calculateReductionRate(Set<String> critical, Set<String>edge){
        return 0.0;
    }

    public double calculateRedundantRate(Set<String> critical, Set<String> edges){
        double res = 0.0;
        for(String s : edges){
            if(!critical.contains(s)){
                res += 1.0;
            }
        }
        return res/critical.size();
    }

    public Set<String> addCriticalEdgesToSet(String[] edges) throws Exception{
        Set<String> set = new HashSet<>();
        for (int i = 0; i < edges.length; i++){
            String[] tokens = edges[i].split(",");
            String from = tokens[0].trim();
            String to = tokens[1].trim();
            String edge = from+"=>"+to;
            set.add(edge);
        }
        return set;
    }
    // input the folder for method for res
    //return: a list of children folder
    public static List<String> getChildrenFolder(String folderPath){
        File folder = new File(folderPath);
        List<String> res = new LinkedList<>();
        if(!folder.isDirectory()){
            return res;
        }

        String name = folder.getName();
        if(name.equals("random") || name.equals("randomCategory") || name.equals("sysrep")){
            res.add(folderPath);
        }else{
            for(File f: folder.listFiles()){
                res.addAll(getChildrenFolder(f.getAbsolutePath()));
            }
        }
        return res;
    }

    public static String getfilterResCorrespondingJsonLog(String filterResFolder){
        File folder_obj = new File(filterResFolder);
        File parent_folder = folder_obj.getParentFile();
        for(File f : parent_folder.listFiles()){
            if(f.isFile() && f.toString().indexOf("json_log")!= -1){
                return f.getAbsolutePath().toString();
            }
        }
        return "Doesnot find json log";
    }
    public static void run_exp_top2(List<String> filterResFolder, String propertyPath) throws Exception{
        List<String> sysrepResFolder = new LinkedList<>();
        for(String s: filterResFolder){
            if(s.indexOf("sysrep") != -1){
                sysrepResFolder.add(s);
            }
        }
        for(String s: sysrepResFolder){
            DotFilterExp exp = new DotFilterExp(s, propertyPath);
            String json_log_path = getfilterResCorrespondingJsonLog(s);
            JSONParser jsonParser = new JSONParser();
            FileReader fileReader = new FileReader(json_log_path);
            JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
            int cprgraphsize = Integer.valueOf((String)json_log.get("CPREdgeNumber"));
            File sysrep_folder = new File(s+"/"+1);
            List<String> sysrep_filter_graph = new LinkedList<>();
            for(File f: sysrep_folder.listFiles()){
                if(f.toString().indexOf("dot")== -1) continue;
                sysrep_filter_graph.add(f.getAbsolutePath().toString());
            }
            JSONObject jsonRes2Combin = new JSONObject();
            Set<Set<String>> twoCombins = getGivenNCombination(sysrep_filter_graph, 2);
            List<Set<String>> twoCombinsArray = new LinkedList<>(twoCombins);
            Map<Integer, String> correspondingToProperty = exp.getCorrespondingPropertyFileForCombination(twoCombinsArray);
            for(int i=0; i<twoCombinsArray.size(); i++){
                Set<String> combins = twoCombinsArray.get(i);
                String point_combination = convertToPointCombin(combins);
                JSONObject curCombinRes = new JSONObject();
                Set<String> combinedEdges = getCombinedGraphEdges(combins);
                Experiment experiment = new Experiment(new File(correspondingToProperty.get(i)));
                String[] criticalEdges = experiment.getCriticalEdges();
                Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
                double missingRate = exp.calculateMissingRate(critialSet, combinedEdges);
                double redundantRate=exp.calculateRedundantRate(critialSet, combinedEdges);
                double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combinedEdges, cprgraphsize);
                curCombinRes.put("2missing", missingRate);
                curCombinRes.put("2redundant", redundantRate);
                curCombinRes.put("2reductionCPR", reductionRateCPR);
                curCombinRes.put("2EdgeNum", combinedEdges.size());
                jsonRes2Combin.put(point_combination, curCombinRes);
            }

            File resFile = new File(s+"/"+"filter_res2combin.json");
            FileWriter fileWriter = new FileWriter(resFile);
            fileWriter.write(jsonRes2Combin.toJSONString());
            fileWriter.close();

        }
    }

    public static void run_exp_top3(List<String> filterResFolder, String propertyPath) throws Exception{
        List<String> sysrepResFolder = new LinkedList<>();
        for(String s: filterResFolder){
            if(s.indexOf("sysrep") != -1){
                sysrepResFolder.add(s);
            }
        }
        for(String s: sysrepResFolder){
            DotFilterExp exp = new DotFilterExp(s, propertyPath);
            String json_log_path = getfilterResCorrespondingJsonLog(s);
            JSONParser jsonParser = new JSONParser();
            FileReader fileReader = new FileReader(json_log_path);
            JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
            int cprgraphsize = Integer.valueOf((String)json_log.get("CPREdgeNumber"));
            File sysrep_folder = new File(s+"/"+1);
            List<String> sysrep_filter_graph = new LinkedList<>();
            for(File f: sysrep_folder.listFiles()){
                if(f.toString().indexOf("dot")== -1) continue;
                sysrep_filter_graph.add(f.getAbsolutePath().toString());
            }
            JSONObject jsonRes2Combin = new JSONObject();
            Set<Set<String>> threeCombins = getGivenNCombination(sysrep_filter_graph, 3);
            List<Set<String>> threeCombinsArray = new LinkedList<>(threeCombins);
            Map<Integer, String> correspondingToProperty = exp.getCorrespondingPropertyFileForCombination(threeCombinsArray);
            for(int i=0; i<threeCombinsArray.size(); i++){
                Set<String> combins = threeCombinsArray.get(i);
                String point_combination = convertToPointCombin(combins);
                JSONObject curCombinRes = new JSONObject();
                Set<String> combinedEdges = getCombinedGraphEdges(combins);
                Experiment experiment = new Experiment(new File(correspondingToProperty.get(i)));
                String[] criticalEdges = experiment.getCriticalEdges();
                Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
                double missingRate = exp.calculateMissingRate(critialSet, combinedEdges);
                double redundantRate=exp.calculateRedundantRate(critialSet, combinedEdges);
                double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combinedEdges, cprgraphsize);
                curCombinRes.put("3missing", missingRate);
                curCombinRes.put("3redundant", redundantRate);
                curCombinRes.put("3reductionCPR", reductionRateCPR);
                curCombinRes.put("3EdgeNum", combinedEdges.size());
                jsonRes2Combin.put(point_combination, curCombinRes);
            }
            File resFile = new File(s+"/"+"filter_res3combin.json");
            FileWriter fileWriter = new FileWriter(resFile);
            fileWriter.write(jsonRes2Combin.toJSONString());
            fileWriter.close();

        }
    }
    private static Set<String> getCombinedGraphEdges(Set<String> combins){
        Set<String> edges = new HashSet<>();
        for(String s: combins){
            ReadGraphFromDot reader = new ReadGraphFromDot();
            DirectedPseudograph<SimpleNode, SimpleEdge> graph = reader.readGraph(s);
            graph.edgeSet().stream().forEach(e -> edges.add(e.toString()));
        }
        return edges;
    }

    public Map<Integer, String> getCorrespondingPropertyFileForCombination(List<Set<String>>combins){
        Map<Integer, String> map = new HashMap<>();
        for(int i=0; i<combins.size(); i++){
            List<String> cases = new ArrayList<>(combins.get(i));
            String caseName = cases.get(0);
            String name = caseName.split("-")[1].split("\\\\")[0];
            for(File property : propertyFolder.listFiles()){
                if(property.getName().indexOf("property")!=-1) {
                    String caseProperty = property.getName().split("_")[1];
                    if (name.indexOf(caseProperty) != -1) {
                        map.put(i, property.getAbsolutePath());
                    }
                }
            }
        }
        return map;
    }

    public static String convertToPointCombin(Set<String> combins){
        StringBuilder sb = new StringBuilder();
        for(String s : combins){
            String[] tokens = s.split("\\\\");
            for(Character c : tokens[tokens.length-1].toCharArray()){
                if(Character.isDigit(c)){
                    if (sb.length() == 0){
                        sb.append(c);
                    }else{
                        sb.append("-");
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
    public static Set<Set<String>> getGivenNCombination(List<String> filterres, int n){
        assert filterres.size() >= n;
        Set<Set<String>> res = new HashSet<>();
        Set<String> tmp = new HashSet<>();
        generateCombine(filterres, res, tmp, n);
        return res;
    }
    private static void generateCombine(List<String> filterRes, Set<Set<String>> combinations, Set<String> cur, int size){
        if(cur.size() == size){
            Set<String> oneRes = new HashSet<>(cur);
            combinations.add(oneRes);
            return;
        }
        for(String s : filterRes){
            if(!cur.contains(s)){
                cur.add(s);
                generateCombine(filterRes, combinations, cur, size);
                cur.remove(s);
            }
        }
    }

    public static void get_one_point_res(List<String> filterResFolder, String propertyPath) throws Exception{
        for(String p:filterResFolder){
            DotFilterExp exp = new DotFilterExp(p, propertyPath);
            exp.run_exp(p, propertyPath);
            exp.getfilterResCorrespondingJsonLog(p);
        }
    }

    public static void printOutCriticalEdgeNum(String properPath) throws Exception{
        File properFiles = new File(properPath);
        for(File property : properFiles.listFiles()){
            Experiment exp = new Experiment(property);
            System.out.println(property.toString());
            String[] edges = exp.getCriticalEdges();
            for(String s : edges){
                System.out.println(s);
            }
            System.out.println(exp.getCriticalEdges().length);
        }
    }

    public static void printCriticalEdgeNumber(String propertyPath) throws Exception{
        File propertyFolder = new File(propertyPath);
        JSONObject criticalEdge = new JSONObject();
        for(File f : propertyFolder.listFiles()){
            if(f.getName().indexOf("property")!=-1) {
                Experiment exp = new Experiment(f);
                String caseName = f.getName().split("_")[1];
                String[] criticalEdges = exp.getCriticalEdges();
                criticalEdge.put(caseName, criticalEdges.length);
            }
        }
        File resJson = new File(propertyPath+"/"+"criticalEdgeNum.json");
        FileWriter fileWriter = new FileWriter(resJson);
        fileWriter.write(criticalEdge.toJSONString());
        fileWriter.close();
    }
    public static List<String> getfilterResRandomFolder(List<String> input, String suffix){
        List<String> folders = new ArrayList<>();
        for(String s : input){
            if(s.indexOf(suffix) != -1){
                folders.add(s);
            }
        }

        return folders;
    }

    public static void run_exp_random_2(List<String>folders, String propertyPath) throws Exception{
        for(String s: folders) {
            DotFilterExp exp = new DotFilterExp(s, propertyPath);
            String json_log_path = getfilterResCorrespondingJsonLog(s);
            JSONParser jsonParser = new JSONParser();
            FileReader fileReader = new FileReader(json_log_path);
            JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
            int cprgraphsize = Integer.valueOf((String) json_log.get("CPREdgeNumber"));
            for (int j = 0; j < 20; j++) {
                File sysrep_folder = new File(s + "/" + String.valueOf(j));
                List<String> sysrep_filter_graph = new LinkedList<>();
                for (File f : sysrep_folder.listFiles()) {
                    if (f.toString().indexOf("dot") == -1) continue;
                    sysrep_filter_graph.add(f.getAbsolutePath().toString());
                }
                JSONObject jsonRes2Combin = new JSONObject();
                Set<Set<String>> twoCombins = getGivenNCombination(sysrep_filter_graph, 2);
                List<Set<String>> twoCombinsArray = new LinkedList<>(twoCombins);
                Map<Integer, String> correspondingToProperty = exp.getCorrespondingPropertyFileForCombination(twoCombinsArray);
                for (int i = 0; i < twoCombinsArray.size(); i++) {
                    Set<String> combins = twoCombinsArray.get(i);
                    String point_combination = convertToPointCombin(combins);
                    JSONObject curCombinRes = new JSONObject();
                    Set<String> combinedEdges = getCombinedGraphEdges(combins);
                    Experiment experiment = new Experiment(new File(correspondingToProperty.get(i)));
                    String[] criticalEdges = experiment.getCriticalEdges();
                    Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
                    double missingRate = exp.calculateMissingRate(critialSet, combinedEdges);
                    double redundantRate = exp.calculateRedundantRate(critialSet, combinedEdges);
                    double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combinedEdges, cprgraphsize);
                    curCombinRes.put("2missing", missingRate);
                    curCombinRes.put("2redundant", redundantRate);
                    curCombinRes.put("2reductionCPR", reductionRateCPR);
                    curCombinRes.put("2EdgeNum", combinedEdges.size());
                    jsonRes2Combin.put(point_combination, curCombinRes);
                }

                File resFile = new File(s + "/" + "filter_res2combin_"+String.valueOf(j)+".json");
                FileWriter fileWriter = new FileWriter(resFile);
                fileWriter.write(jsonRes2Combin.toJSONString());
                fileWriter.close();

            }
        }
    }

    public static void run_exp_random_3(List<String>folders, String propertyPath) throws Exception {
        for (String s : folders) {
            DotFilterExp exp = new DotFilterExp(s, propertyPath);
            String json_log_path = getfilterResCorrespondingJsonLog(s);
            JSONParser jsonParser = new JSONParser();
            FileReader fileReader = new FileReader(json_log_path);
            JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
            int cprgraphsize = Integer.valueOf((String) json_log.get("CPREdgeNumber"));
            for (int j = 0; j < 20; j++) {
                File sysrep_folder = new File(s + "/" + String.valueOf(j));
                List<String> sysrep_filter_graph = new LinkedList<>();
                for (File f : sysrep_folder.listFiles()) {
                    if (f.toString().indexOf("dot") == -1) continue;
                    sysrep_filter_graph.add(f.getAbsolutePath().toString());
                }
                JSONObject jsonRes2Combin = new JSONObject();
                Set<Set<String>> twoCombins = getGivenNCombination(sysrep_filter_graph, 3);
                List<Set<String>> twoCombinsArray = new LinkedList<>(twoCombins);
                Map<Integer, String> correspondingToProperty = exp.getCorrespondingPropertyFileForCombination(twoCombinsArray);
                for (int i = 0; i < twoCombinsArray.size(); i++) {
                    Set<String> combins = twoCombinsArray.get(i);
                    String point_combination = convertToPointCombin(combins);
                    JSONObject curCombinRes = new JSONObject();
                    Set<String> combinedEdges = getCombinedGraphEdges(combins);
                    Experiment experiment = new Experiment(new File(correspondingToProperty.get(i)));
                    String[] criticalEdges = experiment.getCriticalEdges();
                    Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
                    double missingRate = exp.calculateMissingRate(critialSet, combinedEdges);
                    double redundantRate = exp.calculateRedundantRate(critialSet, combinedEdges);
                    double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combinedEdges, cprgraphsize);
                    curCombinRes.put("3missing", missingRate);
                    curCombinRes.put("3redundant", redundantRate);
                    curCombinRes.put("3reductionCPR", reductionRateCPR);
                    curCombinRes.put("3EdgeNum", combinedEdges.size());
                    jsonRes2Combin.put(point_combination, curCombinRes);
                }

                File resFile = new File(s + "/" + "filter_res3combin_" + String.valueOf(j) + ".json");
                FileWriter fileWriter = new FileWriter(resFile);
                fileWriter.write(jsonRes2Combin.toJSONString());
                fileWriter.close();

            }
        }
    }

    public static void main(String[] args) throws Exception{
        String path = "D:\\cluster_all_plus_filter";
        String propertyPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\large_properties";
//        String path = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\test\\test_data-dataleak";
//        String propertyPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\test\\properties";
       List<String> filterResFolders = DotFilterExp.getChildrenFolder(path);
        List<String> randomFolder = DotFilterExp.getfilterResRandomFolder(filterResFolders, "randomCategory");
        //List<String> filterResFolders = new ArrayList<>();
//        for(String p:filterResFolders){
//////            DotFilterExp exp = new DotFilterExp(p, propertyPath);
//////            exp.run_exp(p, propertyPath);
//////            exp.getfilterResCorrespondingJsonLog(p);
//////        }
       get_one_point_res(filterResFolders, propertyPath);
       run_exp_top2(filterResFolders, propertyPath);
       run_exp_top3(filterResFolders, propertyPath);
//        DotFilterExp.run_exp_random_2(randomFolder, propertyPath);
//        DotFilterExp.run_exp_random_3(randomFolder, propertyPath);
//        run_exp_top3(randomFolder, propertyPath);
//        printOutCriticalEdgeNum(propertyPath);
//        printCriticalEdgeNumber(propertyPath);
    }


}
