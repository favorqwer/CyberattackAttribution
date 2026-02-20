package read_dot;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import pagerank.EventEdge;
import pagerank.Experiment;


import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class DotFilter_nocombine{
    public File resFolder;
    public File propertyFolder;



    DotFilter_nocombine(String resPath, String propertyFolder){
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
    public static void run_exp_topN(List<String> filterResFolder, String propertyPath, int ranks) throws Exception{
        List<String> sysrepResFolder = new LinkedList<>();
        for(String s: filterResFolder){
            if(s.indexOf("sysrep") != -1){
                sysrepResFolder.add(s);
            }
        }
        List<Integer> rankList = new ArrayList<>();
        for (int i = 0; i<ranks; i++){
            rankList.add(i);
        }
        for(String s: sysrepResFolder){
            DotFilter_nocombine exp = new DotFilter_nocombine(s, propertyPath);
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
            List<String> topCandidates = getCorrespondingFilePath(sysrep_filter_graph, rankList, s+"/1/");
            JSONObject jsonRes2Combin = new JSONObject();
             //Set<Set<String>> twoCombins = getGivenNCombination(sysrep_filter_graph, 2);
            //List<Set<String>> twoCombinsArray = new LinkedList<>(twoCombins);
            //Map<Integer, String> correspondingToProperty = exp.getCorrespondingPropertyFileForCombination(topCandidates);
            File propertyFile =  findPropertyFile(propertyPath, s);
//            for(int i=0; i<twoCombinsArray.size(); i++){
//                Set<String> combins = twoCombinsArray.get(i);
//                String point_combination = convertToPointCombin(combins);
//                JSONObject curCombinRes = new JSONObject();
//                Set<String> combinedEdges = getCombinedGraphEdges(combins);
//                Experiment experiment = new Experiment(new File(correspondingToProperty.get(i)));
//                String[] criticalEdges = experiment.getCriticalEdges();
//                Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
//                double missingRate = exp.calculateMissingRate(critialSet, combinedEdges);
//                double redundantRate=exp.calculateRedundantRate(critialSet, combinedEdges);
//                double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combinedEdges, cprgraphsize);
//                curCombinRes.put("2missing", missingRate);
//                curCombinRes.put("2redundant", redundantRate);
//                curCombinRes.put("2reductionCPR", reductionRateCPR);
//                curCombinRes.put("2EdgeNum", combinedEdges.size());
//                jsonRes2Combin.put(point_combination, curCombinRes);
//            }
            JSONObject curCombinRes = new JSONObject();
            Set<String> combineEdges = getCombinedGraphEdges(topCandidates);
            Experiment experiment = new Experiment(propertyFile);
            String[] criticalEdges = experiment.getCriticalEdges();
            Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
            double missingRate = exp.calculateMissingRate(critialSet, combineEdges);
            double redundantRate=exp.calculateRedundantRate(critialSet, combineEdges);
            double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combineEdges, cprgraphsize);
            String rankValue = String.valueOf(ranks);
            curCombinRes.put(rankValue+"missing", missingRate);
            curCombinRes.put(rankValue+"redundant", redundantRate);
            curCombinRes.put(rankValue+"reductionCPR", reductionRateCPR);
            curCombinRes.put(rankValue+"EdgeNum", combineEdges.size());
            List<Integer> trueFalsePositiveNegativeRes = exp.calculateTrueFalsePositiveNegative(critialSet, combineEdges, cprgraphsize);
            curCombinRes.put(rankValue+"TruePositive", trueFalsePositiveNegativeRes.get(0));
            curCombinRes.put(rankValue+"TrueNegative", trueFalsePositiveNegativeRes.get(1));
            curCombinRes.put(rankValue+"FalsePositive", trueFalsePositiveNegativeRes.get(2));
            curCombinRes.put(rankValue+"FalseNegative", trueFalsePositiveNegativeRes.get(3));
            jsonRes2Combin.put(rankValue, curCombinRes);
            File resFile = new File(s+"/" + "filter_res_"+ rankValue + "_combin.json");
            FileWriter fileWriter = new FileWriter(resFile);
            fileWriter.write(jsonRes2Combin.toJSONString());
            fileWriter.close();
        }
    }

    private static File findPropertyFile(String propertyPath, String sysrep_res_folder){
        Path sysrep_res_folder_path = Paths.get(sysrep_res_folder);
        Path parentFolder = sysrep_res_folder_path.getParent();
        String caseName = parentFolder.getFileName().toString().split("-")[1];
        File properFolder = new File(propertyPath);
        for(File property : properFolder.listFiles()){
            if(property.getName().indexOf(caseName)!=-1){
                return property;
            }
        }
        System.out.println("Doesn't find corresponding property file for "+sysrep_res_folder);
        return null;
    }

    private static Set<String> getCombinedGraphEdges(List<String> combins){
        Set<String> edges = new HashSet<>();
        for(String s: combins){
            ReadGraphFromDot reader = new ReadGraphFromDot();
            DirectedPseudograph<SimpleNode, SimpleEdge> graph = reader.readGraph(s);
            graph.edgeSet().stream().forEach(e -> edges.add(e.toString()));
        }
        return edges;
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

    public static void run_exp_randomN(List<String>folders, String propertyPath, int N) throws Exception{
        for(String s: folders) {
            DotFilter_nocombine exp = new DotFilter_nocombine(s, propertyPath);
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
                //Set<Set<String>> twoCombins = getGivenNCombination(sysrep_filter_graph, 2);
                Set<String> candidates = getRandomCaidiate(sysrep_filter_graph, N);
                List<Set<String>> twoCombinsArray = new LinkedList<>();
                twoCombinsArray.add(candidates);
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
                    String rankValue = String.valueOf(N);
                    curCombinRes.put(rankValue+"missing", missingRate);
                    curCombinRes.put(rankValue+"redundant", redundantRate);
                    curCombinRes.put(rankValue+"reductionCPR", reductionRateCPR);
                    curCombinRes.put(rankValue+"EdgeNum", combinedEdges.size());
                    List<Integer> trueFalsePositiveNegativeRes = exp.calculateTrueFalsePositiveNegative(critialSet, combinedEdges, cprgraphsize);
                    curCombinRes.put(rankValue+"TruePositive", trueFalsePositiveNegativeRes.get(0));
                    curCombinRes.put(rankValue+"TrueNegative", trueFalsePositiveNegativeRes.get(1));
                    curCombinRes.put(rankValue+"FalsePositive", trueFalsePositiveNegativeRes.get(2));
                    curCombinRes.put(rankValue+"FalseNegative", trueFalsePositiveNegativeRes.get(3));
                    jsonRes2Combin.put(rankValue, curCombinRes);
                }

                File resFile = new File(s + "/" + String.format("filter_res_%drandom", N)+String.valueOf(j)+".json");
                FileWriter fileWriter = new FileWriter(resFile);
                fileWriter.write(jsonRes2Combin.toJSONString());
                fileWriter.close();

            }
        }
    }

    private static Set<String> getRandomCaidiate(List<String> files, int number){
        Set<String> candidates = new HashSet<>();
        Collections.shuffle(files);
        for(int i=0;i<number*3 && i < files.size(); i++){
            candidates.add(files.get(i));
        }
        return candidates;
    }
    // 取得相应rank的所有路径 0: top1 1:top2 2:top3
    private static List<String> getCorrespondingFilePath(List<String>candidate_res, List<Integer>rank, String prefix){
        assert rank.size() <=3 && rank.size() > 0;
        assert candidate_res.size()>0;

        List<String> res = new LinkedList<>();

        // 获得三个可能的top1 or 2 or 3
        Set<String> candidates = new HashSet<>();
        for(Integer r : rank){
            int cur = r;
            for (int t = 0; t < 3; t++){
                candidates.add(String.valueOf(cur));
                cur += 3;
            }
        }
        for (String file_path: candidate_res){
            Path path = Paths.get(file_path);
            String fileName = path.getFileName().toString();
            for (String candidate : candidates){
                if (fileName.indexOf(candidate) != -1){
                    res.add(prefix+fileName);
                }
            }
        }
        return res;
    }

    private static List<Integer> calculateTrueFalsePositiveNegative(Set<String> criticaledges, Set<String> combine, int cprgraphsize){
        int truePositive = 0;
        int trueNegative = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        for(String edge : criticaledges){
            if (combine.contains(edge)){
                truePositive++;
            }else{
                falseNegative++;
            }
        }
        for(String edge : combine){
            if(!criticaledges.contains(edge)){
                falsePositive += 1;
            }
        }
        trueNegative = cprgraphsize-combine.size();
        List<Integer> res = new ArrayList<>();
        res.add(truePositive);
        res.add(trueNegative);
        res.add(falsePositive);
        res.add(falseNegative);
        return res;
    }

    public static Map<String, Double> readEntryPointsReputation(File rankFile) throws Exception{
        Map<String, Double> res = new HashMap<>();
        FileReader fileReader = new FileReader(rankFile);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        String line = bufferedReader.readLine();
        while(line!=null){
            if (line.startsWith("----")){
                line = bufferedReader.readLine();
                continue;
            }
            if(line.indexOf(":")!=-1) {
                String[] tokens = line.split(" ");
                res.put(tokens[0].substring(0, tokens[0].length()-1), Double.parseDouble(tokens[1]));
            }
            line = bufferedReader.readLine();
        }
        bufferedReader.close();
        return res;
    }
    // value size may be zero
    public static List<String> readEntryPointsForForward(File file) throws Exception{
        FileReader fileReader = new FileReader(file);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        String line = bufferedReader.readLine();
        List<String> res = new LinkedList<>();
        while(line!=null){
            if(!line.startsWith("Entry Points")){
                res.add(line);
            }
            line = bufferedReader.readLine();
        }
        bufferedReader.close();
        return res;
    }

    static List<Map.Entry<String, Double>> sortedEntryPointsNoCategory(Map<String, Double> reputations,  List<String> candidates){
        Map<String, Double> candidateScores = new HashMap<>();
        for(String s: candidates){
            candidateScores.put(s, reputations.get(s));
        }
        List<Map.Entry<String, Double>> res = new LinkedList<>(candidateScores.entrySet());
        Collections.sort(res, (a, b)->b.getValue().compareTo(a.getValue()));
        return res;
    }
    static List<String> getFilePathForEntryReputation(String path) throws Exception{
        File methodFolder = new File(path);
        List<String> res = new LinkedList<>();
        for(File caseRes : methodFolder.listFiles()){
            if (caseRes.isDirectory()){
                res.add(caseRes.getAbsolutePath().toString()+"\\start_rank.txt");
            }
        }
        return res;
    }

    static List<String> getFilePathForEntryUsedInForward(String path){
        File methodFolder = new File(path);
        List<String> sysrep = new LinkedList<>();
        for(File caseRes : methodFolder.listFiles()){
            if (caseRes.isDirectory()){
                sysrep.add(caseRes.getAbsolutePath().toString()+"\\sysrep\\1");
            }
        }
        List<String> res = new LinkedList<>();
        for(String s: sysrep){
            File resFolder = new File(s);
            for(File f : resFolder.listFiles()){
                if(f.getName().indexOf("starts")!=-1){
                    res.add(resFolder.getAbsolutePath()+"\\"+f.getName());
                }
            }
        }
        return res;
    }

    static void merge_filter(List<String> filterResFolder, String propertyPath, String methodPath) throws Exception{
        List<String> sysrepResFolder = new LinkedList<>();
        for(String s: filterResFolder){
            if(s.indexOf("sysrep") != -1){
                sysrepResFolder.add(s);
            }
        }

        List<String> entryPointsRep = getFilePathForEntryReputation(methodPath);
        System.out.println(entryPointsRep);
        List<String> entryPointsUsedInForward = getFilePathForEntryUsedInForward(methodPath);
        System.out.println(entryPointsUsedInForward);
        for(int i =0; i < sysrepResFolder.size(); i++){
            String json_log_path = getfilterResCorrespondingJsonLog(sysrepResFolder.get(i));
            JSONParser jsonParser = new JSONParser();
            FileReader fileReader = new FileReader(json_log_path);
            JSONObject json_log = (JSONObject) jsonParser.parse(fileReader);
            int cprgraphsize = Integer.valueOf((String) json_log.get("CPREdgeNumber"));
            DotFilter_nocombine exp = new DotFilter_nocombine(sysrepResFolder.get(i), propertyPath);
            File curCase = new File(sysrepResFolder.get(i));
            File reputationFile = new File(entryPointsRep.get(i));
            File entryUsedInFilter = new File(entryPointsUsedInForward.get(i));
            Map<String, Double> reputation =  readEntryPointsReputation(reputationFile);
            List<String>  topEntries =  readEntryPointsForForward(entryUsedInFilter);
            List<Map.Entry<String,  Double>> sortedEntries = sortedEntryPointsNoCategory(reputation, topEntries);
            JSONObject jsonRes = new JSONObject();
            List<File> filteredGraph = getFilteredGraphForCurrentCase(curCase);
            for(File f2 : filteredGraph){
                System.out.println(f2.getAbsolutePath());
            }
            Set<String> combins = new HashSet<>();
            for(int j=0;j<9;j++){
                if(j >= topEntries.size()){
                    jsonRes.put(j, new JSONObject());
                    continue;
                }
                Map.Entry<String, Double> jthEntry = sortedEntries.get(j);
                String entrySignature = jthEntry.getKey();
                int idx = topEntries.indexOf(entrySignature);
                File correspondFilteredGraph = filteredGraph.get(idx);
                combins.add(correspondFilteredGraph.getAbsolutePath());
                Set<String> combineEdges = getCombinedGraphEdges(combins);
                File propertyFile = findPropertyFile(propertyPath, sysrepResFolder.get(i));
                Experiment experiment = new Experiment(propertyFile);
                String[] criticalEdges = experiment.getCriticalEdges();
                Set<String> critialSet = exp.addCriticalEdgesToSet(criticalEdges);
                double missingRate = exp.calculateMissingRate(critialSet, combineEdges);
                double redundantRate=exp.calculateRedundantRate(critialSet, combineEdges);
                double reductionRateCPR = exp.calculateReductionRateComparedWithCPR(critialSet, combineEdges, cprgraphsize);
                System.out.println(missingRate);
                System.out.println(reductionRateCPR);
//                jsonRes.get(j)
//                jsonRes.get(j).a("missing", missin
                JSONObject curCombinRes = new JSONObject();
                String rankValue = String.valueOf(j+1);
                curCombinRes.put(rankValue+"missing", missingRate);
                curCombinRes.put(rankValue+"redundant", redundantRate);
                curCombinRes.put(rankValue+"reductionCPR", reductionRateCPR);
                curCombinRes.put(rankValue+"EdgeNum", combineEdges.size());
                List<Integer> trueFalsePositiveNegativeRes = exp.calculateTrueFalsePositiveNegative(critialSet, combineEdges, cprgraphsize);
                curCombinRes.put(rankValue+"TruePositive", trueFalsePositiveNegativeRes.get(0));
                curCombinRes.put(rankValue+"TrueNegative", trueFalsePositiveNegativeRes.get(1));
                curCombinRes.put(rankValue+"FalsePositive", trueFalsePositiveNegativeRes.get(2));
                curCombinRes.put(rankValue+"FalseNegative", trueFalsePositiveNegativeRes.get(3));
                jsonRes.put(rankValue, curCombinRes);
                File resFile = new File(sysrepResFolder.get(i)+"/" + "filter_res_"+ "merge" + "_combin.json");
                FileWriter fileWriter = new FileWriter(resFile);
                fileWriter.write(jsonRes.toJSONString());
                fileWriter.close();

            }
        }
    }

    private static List<File> getFilteredGraphForCurrentCase(File curCase){
        List<File> res = new LinkedList<>();
        for (File f : curCase.listFiles()){
            if(f.isDirectory()){
                for (File f2 : f.listFiles()){
                    if(f2.getName().endsWith("dot")){
                        res.add(f2);
                    }
                }
            }
        }
        return res;
    }

    public static void printOutAverageRank(List<String> filterResFolder, String propertyPath) throws Exception{
        List<String> sysrepResFolder = new LinkedList<>();
        for(String s: filterResFolder){
            if(s.indexOf("sysrep") != -1){
                sysrepResFolder.add(s);
            }
        }
        for(String s:sysrepResFolder){
            File sysrepFolder = new File(s);
            File parentFile = sysrepFolder.getParentFile();
            File propertyFile = findPropertyFile(propertyPath, s);
            Experiment experiment = new Experiment(propertyFile);
            File startRank = new File(parentFile.toString()+"/"+"start_rank.json");
            FileReader fileReader = new FileReader(startRank);
            JSONParser parser = new JSONParser();
            JSONObject jsonObj = (JSONObject) parser.parse(fileReader);
            JSONArray ips = (JSONArray) jsonObj.get("IP Start");
            List<String> ip = new LinkedList<>();
            for(int i=0; i < ips.size(); i++){
                ip.add((String)ips.get(i));
            }
            List<String> file = new LinkedList<>();
            JSONArray files = (JSONArray) jsonObj.get("File Start");
            for(int i=0;i < files.size(); i++){
                file.add((String) files.get(i));
            }
            List<String> process = new LinkedList<>();
            JSONArray processJson = (JSONArray) jsonObj.get("Process Start");
            for(int i=0; i < processJson.size(); i++){
                process.add((String)processJson.get(i));
            }
            String[] importantEntries = experiment.getEntries();
            double average = 0.0;
            for(String r:importantEntries){
                if(ip.indexOf(r)!=-1){
                    average += ip.indexOf(r) + 1;
                }
                if(file.indexOf((r))!=-1){
                    average += file.indexOf(r)+1;
                }
                if(process.indexOf(r)!=-1){
                    average += process.indexOf(r)+1;
                }
            }
            System.out.println(String.format("%s:%s", s, Double.toString(average/importantEntries.length)));
        }
    }


    public static void main(String[] args) throws Exception{
        String path = "D:\\timedata";
        String propertyPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\large_properties";
//        String path = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\test\\test_data-dataleak";
//        String propertyPath = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\input_11\\test\\properties";
        List<String> filterResFolders = DotFilter_nocombine.getChildrenFolder(path);
        List<String> randomFolder = DotFilter_nocombine.getfilterResRandomFolder(filterResFolders, "randomCategory");
        //List<String> filterResFolders = new ArrayList<>();
//        for(String p:filterResFolders){
//////            DotFilterExp exp = new DotFilterExp(p, propertyPath);
//////            exp.run_exp(p, propertyPath);
//////            exp.getfilterResCorrespondingJsonLog(p);
//////        }
//        get_one_point_res(filterResFolders, propertyPath);
        //run_exp_topN(filterResFolders, propertyPath, 3);
//        run_exp_randomN(randomFolder,propertyPath, 3);
//        DotFilterExp.run_exp_random_2(randomFolder, propertyPath);
//        DotFilterExp.run_exp_random_3(randomFolder, propertyPath);
//        run_exp_top3(randomFolder, propertyPath);
        printOutCriticalEdgeNum(propertyPath);
//        printCriticalEdgeNumber(propertyPath);
        //merge_filter(filterResFolders, propertyPath, path);
//        printOutAverageRank(filterResFolders, propertyPath);
    }


}

