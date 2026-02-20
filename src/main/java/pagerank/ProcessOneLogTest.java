package pagerank;

import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.util.*;

/**
 * Created by fang on 4/6/18.
 */
@SuppressWarnings("Duplicates")
public class ProcessOneLogTest {
    static OutputStream os = null;
    public static int topStarts = 3;     //parameter for choosing top N starts for forward analysis
    public static void process(String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection,String[] highRP,String[] midRP,String[] lowRP, String filename,double detectionSize, Set<String> seedSources,String[] criticalEdges, String mode, String do_split){
        //String resultDir =  "/home/lcl/results/exp/";
        //String suffix = "";
        // @param mode: "nonml","clusterall","localcluster","localtime","localamount","localstruct"

        OutputStream weightfile = null;
        try{
            os = new FileOutputStream(resultDir+filename+suffix+"_stats");
            GetGraph getGraph = new GetGraph(logfile, IP);
            long startTime = System.currentTimeMillis();
            getGraph.GenerateGraph();
            DirectedPseudograph<EntityNode, EventEdge> orignal = getGraph.getJg();
            System.out.println("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size());
            os.write(("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size()+"\n").getBytes());
            long endTime = System.currentTimeMillis();
            double timeCost = getTimeCost(startTime, endTime);
            System.out.println("Build Original Graph time cost is: "+ timeCost);
            os.write(("Build Original Graph time cost is: "+ timeCost+"\n").getBytes());

            run_exp(orignal, resultDir, suffix, threshold, trackOrigin, logfile, IP, detection,highRP,midRP,lowRP,filename, detectionSize,seedSources,criticalEdges, mode, do_split);

        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try{
//                weightfile.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    public static void process_backward(String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection,String[] highRP,String[] midRP,String[] lowRP, String filename,double detectionSize, Set<String> seedSources,String[] criticalEdges, String mode){
        OutputStream weightfile = null;
        try{
            os = new FileOutputStream(resultDir+filename+suffix+"_stats");
            GetGraph getGraph = new GetGraph(logfile, IP);
            long startTime = System.currentTimeMillis();
            getGraph.GenerateGraph();
            DirectedPseudograph<EntityNode, EventEdge> orignal = getGraph.getJg();
            System.out.println("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size());
            os.write(("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size()+"\n").getBytes());
            long endTime = System.currentTimeMillis();
            double timeCost = getTimeCost(startTime, endTime);
            System.out.println("Build Original Graph time cost is: "+ timeCost);
            os.write(("Build Original Graph time cost is: "+ timeCost+"\n").getBytes());

            run_exp_backward_test(orignal, resultDir, suffix, threshold, trackOrigin, logfile, IP, detection,highRP,midRP,lowRP,filename, detectionSize,seedSources,criticalEdges, mode);

        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try{
//                weightfile.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    //backtrack + backward propagate
    public static void run_exp_backward_test(DirectedPseudograph<EntityNode, EventEdge> orignal,String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection,String[] highRP,String[] midRP,String[] lowRP, String filename,double detectionSize, Set<String> seedSources,String[] criticalEdges, String mode){
        OutputStream weightfile = null;
        try{
            os = new FileOutputStream(resultDir+filename+suffix+"_stats");
            long start = System.currentTimeMillis();
            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            long end = System.currentTimeMillis();
            double timeCost = getTimeCost(start, end);
            System.out.println("BackTrack time cost is: "+timeCost);
            os.write(("BackTrack time cost is: "+timeCost+"\n").getBytes());
            System.out.println("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size()+"\n").getBytes());

            IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            out.exportGraph(resultDir+"BackTrack_"+filename+suffix);
//            backTrack.exportGraph("backTrack");

//            // If we run cpr1 and cpr2 together, the cpr1 result will effect cpr2, need more investigation
//            CausalityPreserve CPR1 = new CausalityPreserve(backTrack.afterBackTrack);
//            CPR1.CPR(1);
//            System.out.println("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size());
//            os.write(("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size()+"\n").getBytes());
            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            //CPR.CPR(2);
            start = System.currentTimeMillis();
            double timeWindow = 10.0;
            CPR.mergeEdgeFallInTheRange2(timeWindow);
            System.out.println("The size of time window is :" + timeWindow+"(s)");
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Edge Merge cost is: "+timeCost);
            os.write(("Edge Merge cost is: "+timeCost+"\n").getBytes());
            System.out.println("After CPR vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size()+"\n").getBytes());

            out = new IterateGraph(CPR.afterMerge);
            out.exportGraph(resultDir+"AfterCPR_"+filename+suffix);
//            GraphSplit split = new GraphSplit(CPR.afterMerge);
//            start = System.currentTimeMillis();
//            split.splitGraph();
//            end = System.currentTimeMillis();
//            timeCost = getTimeCost(start, end);
//            System.out.println("Node split time cost is: "+timeCost);
//            os.write(("Node split time cost is: "+timeCost+"\n").getBytes());
//            System.out.println("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());

            BackwardPropagate infer = new BackwardPropagate(CPR.afterMerge);

            //os.write(("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size()+"\n").getBytes());
//            weightfile = new FileOutputStream(resultDir+"weights_"+filename+suffix);
//            for(EventEdge e: infer.graph.edgeSet()){
//                weightfile.write((String.valueOf(e.weight)+",").getBytes());
//            }

            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
            start = System.currentTimeMillis();
            switch (mode){
                case "nonml":
                    infer.calculateWeights();
                    break;
                case "clusterall":
                    infer.calculateWeights_ML_dec(true,1);
                    break;
                case "nonoutlier":
                    infer.calculateWeights_ML_dec(true,2);
                    break;
                case "clusterlocal":
                    infer.calculateWeights_ML_dec(true,3);
                    break;
                case "localtime":
                    infer.calculateWeights_Individual(true,"timeWeight");
                    break;
                case "localamount":
                    infer.calculateWeights_Individual(true,"amountWeight");
                    break;
                case "localstruct":
                    infer.calculateWeights_Individual(true,"structureWeight");
                    break;
                case "fanout":
                    infer.calculateWeights_Fanout(true);
                    break;
                default:
                    System.out.println("Unknown mode: "+mode);
            }
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            String timeCostInfo = String.format("Weight Calculation (%s) time cost is: ", mode)+timeCost+"\n";
            System.out.println(timeCostInfo);
            os.write(timeCostInfo.getBytes());

//            infer.calculateWeights_ML(true);
//            infer.calculateWeights_Individual(true, "timeWeight");
//            infer.calculateWeights();
            //System.out.println("OTSU: "+infer.OTSUThreshold());



            infer.initialReputation(highRP, lowRP);
            start = System.currentTimeMillis();
            infer.PageRankIterationBackward(highRP,midRP,lowRP,detection);
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            timeCostInfo = "Propagation time cost is: "+timeCost+"\n";
            System.out.println(timeCostInfo);
            os.write(timeCostInfo.getBytes());

            infer.exportGraph(resultDir+"Weight_"+filename+suffix);
            List<List<String>> forwardStarts = infer.getForwardStarts();
            Map<String, Double> nodeReputation = IterateGraph.getNodeReputation(infer.graph);
            IterateGraph.outputTopStarts(resultDir, forwardStarts, nodeReputation);
            int startsNum = 0;
            for(List<String> starts : forwardStarts){
                for(int r = 0; r < topStarts && r <starts.size(); r++){
                    DirectedPseudograph<EntityNode, EventEdge> backresFilteredByForwad = infer.
                            combineBackwardAndForwardForGivenStart(starts.get(r), orignal);
                    out = new IterateGraph(backresFilteredByForwad);
                    out.exportGraph(resultDir+"filtered_by_forward_"+String.valueOf(startsNum)+filename+suffix);
                    startsNum++;
                }
            }
//            DirectedPseudograph<EntityNode, EventEdge> backresFilteredByForwad = infer.
//                    combineBackwardAndForwardForGivenStarts(forwardStarts, orignal);
//            out = new IterateGraph(backresFilteredByForwad);
//            out.exportGraph(resultDir+"BackTrack_Filtered_By_Forward"+filename+suffix);
            List<String> entryPoints = IterateGraph.getCandidateEntryPoint(infer.graph);
            JSONObject entryJson = new JSONObject();
            entryJson.put("EntryPointsNumber", entryPoints.size());
            JSONArray ponintsJson = new JSONArray();
            entryPoints.stream().forEach(s->ponintsJson.add(s));
            entryJson.put("EntryPoints", ponintsJson);
            File entryPointsJsonFile = new File(resultDir+filename+suffix+"entry_points.json");
            FileWriter jsonWriter = new FileWriter(entryPointsJsonFile);
            jsonWriter.write(entryJson.toJSONString());
            jsonWriter.close();


            // Debug part: need to remove
            IterateGraph.printEdgeByWeights(filename, infer.graph);
            IterateGraph debugger = new IterateGraph(infer.graph);
            List<String> nodes = new ArrayList<>();
            nodes.add("7009python3");
            nodes.add("7047python3");



            Runtime rt = Runtime.getRuntime();
            String[] cmd = new String[] {"/bin/sh","-c","dot -T svg "+resultDir+"AfterCPR_"+filename+suffix+".dot"
                    + " > "+resultDir+"AfterCPR_"+filename+suffix+".svg"};
            rt.exec(cmd);
            cmd = new String[] {"/bin/sh", "-c","dot -T svg "+resultDir+"Weight_"+filename+suffix+".dot"
                    + " > "+resultDir+"Weight_"+filename+suffix+".svg"};
            rt.exec(cmd);

        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try{
                os.close();
//                weightfile.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }



    public static void run_exp(DirectedPseudograph<EntityNode, EventEdge> orignal,
                               String resultDir,
                               String suffix,
                               double threshold,
                               boolean trackOrigin,
                               String logfile,
                               String[] IP,
                               String detection,
                               String[] highRP,
                               String[] midRP,
                               String[] lowRP,
                               String filename,
                               double detectionSize,
                               Set<String> seedSources,
                               String[] criticalEdges,
                               String mode,
                               String do_split){
        OutputStream weightfile = null;
        try{
            os = new FileOutputStream(resultDir+filename+suffix+"_stats");
            long start = System.currentTimeMillis();
            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            long end = System.currentTimeMillis();
            double timeCost = getTimeCost(start, end);
            System.out.println("BackTrack time cost is: "+timeCost);
            os.write(("BackTrack time cost is: "+timeCost+"\n").getBytes());
            System.out.println("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size()+"\n").getBytes());

            IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            out.exportGraph(resultDir+"BackTrack_"+filename+suffix);
//            backTrack.exportGraph("backTrack");

//            // If we run cpr1 and cpr2 together, the cpr1 result will effect cpr2, need more investigation
//            CausalityPreserve CPR1 = new CausalityPreserve(backTrack.afterBackTrack);
//            CPR1.CPR(1);
//            System.out.println("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size());
//            os.write(("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size()+"\n").getBytes());
            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            //CPR.CPR(2);
            start = System.currentTimeMillis();
            double timeWindow = 10.0;
            CPR.mergeEdgeFallInTheRange2(timeWindow);
            System.out.println("The size of time window is :" + timeWindow+"(s)");
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Edge Merge cost is: "+timeCost);
            os.write(("Edge Merge cost is: "+timeCost+"\n").getBytes());
            System.out.println("After CPR vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size()+"\n").getBytes());

            out = new IterateGraph(CPR.afterMerge);
            out.exportGraph(resultDir+"AfterCPR_"+filename+suffix);
            GraphSplit split = new GraphSplit(CPR.afterMerge);
            start = System.currentTimeMillis();
            if(do_split.equals("split")) {
                split.splitGraph();
            }
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            System.out.println("Node split time cost is: "+timeCost);
            os.write(("Node split time cost is: "+timeCost+"\n").getBytes());
            System.out.println("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());

            InferenceReputation infer = new InferenceReputation(split.inputGraph);

            os.write(("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size()+"\n").getBytes());
//            weightfile = new FileOutputStream(resultDir+"weights_"+filename+suffix);
//            for(EventEdge e: infer.graph.edgeSet()){
//                weightfile.write((String.valueOf(e.weight)+",").getBytes());
//            }

            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
            start = System.currentTimeMillis();
            switch (mode){
                case "nonml":
                    infer.calculateWeights();
                    break;
                case "clusterall":
                    infer.calculateWeights_ML(true,1);
                    break;
                case "nonoutlier":
                    infer.calculateWeights_ML(true,2);
                    break;
                case "clusterlocal":
                    infer.calculateWeights_ML(true,3);
                    break;
                case "localtime":
                    infer.calculateWeights_Individual(true,"timeWeight");
                    break;
                case "localamount":
                    infer.calculateWeights_Individual(true,"amountWeight");
                    break;
                case "localstruct":
                    infer.calculateWeights_Individual(true,"structureWeight");
                    break;
                case "fanout":
                    //infer.calculateWeights_Fanout(true);
                    break;
                default:
                    System.out.println("Unknown mode: "+mode);
            }
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            String timeCostInfo = String.format("Weight Calculation (%s) time cost is: ", mode)+timeCost+"\n";
            System.out.println(timeCostInfo);
            os.write(timeCostInfo.getBytes());

//            infer.calculateWeights_ML(true);
//            infer.calculateWeights_Individual(true, "timeWeight");
//            infer.calculateWeights();
            //System.out.println("OTSU: "+infer.OTSUThreshold());



            infer.initialReputation(highRP,midRP,lowRP);
            start = System.currentTimeMillis();
            infer.PageRankIteration2(highRP,midRP,lowRP,detection);
            end = System.currentTimeMillis();
            timeCost = getTimeCost(start, end);
            timeCostInfo = "Propagation time cost is: "+timeCost+"\n";
            System.out.println(timeCostInfo);
            os.write(timeCostInfo.getBytes());

//            System.out.println("After Filter vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());
//            os.write(("After Filter vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size()).getBytes());

//        //infer.onlyPrintHighestWeights(detection);
            infer.exportGraph(resultDir+"Weight_"+filename+suffix);
//        IterateGraph iterGraph = new IterateGraph(infer.graph);
            for(EntityNode v : infer.graph.vertexSet())
                if(v.getSignature().equals(detection)){
                    os.write(("POI Reputation: "+v.getReputation()).getBytes());
                    break;
                }


            Runtime rt = Runtime.getRuntime();
//            String[] cmd = {"/bin/sh","-c","dot -T svg "+resultDir+"BackTrack_"+filename+suffix+".dot"
//                    + " > "+resultDir+"BackTrack_"+filename+suffix+".svg"};
//            rt.exec(cmd);
            String[] cmd = new String[] {"/bin/sh","-c","dot -T svg "+resultDir+"AfterCPR_"+filename+suffix+".dot"
                    + " > "+resultDir+"AfterCPR_"+filename+suffix+".svg"};
            rt.exec(cmd);
            cmd = new String[] {"/bin/sh", "-c","dot -T svg "+resultDir+"Weight_"+filename+suffix+".dot"
                    + " > "+resultDir+"Weight_"+filename+suffix+".svg"};
            rt.exec(cmd);

//            double avg = infer.getAvgWeight();
//            double minCritical = 1.0;
//            Set<String> criticalSet = new HashSet<>(Arrays.asList(criticalEdges));
//            PriorityQueue<EventEdge> pq = new PriorityQueue<>(Comparator.comparing(e -> e.weight));
//
//            Map<String,Double> weightMap = new HashMap<>();
//            for(EventEdge e : infer.graph.edgeSet()){
//                pq.offer(e);
//                weightMap.put(e.getSource().getSignature()+","+e.getSink().getSignature(),e.weight);
//            }
//
//            List<Double> criticalWeights = new ArrayList<>();
//
//            for(String criticalEdge : criticalEdges){
//                if(!weightMap.containsKey(criticalEdge)){
//                    System.out.println("no key:"+criticalEdge);
//                }
//                double weight1 = weightMap.getOrDefault(criticalEdge,0.0);
//                System.out.println(criticalEdge+":"+weight1);
//                String back = criticalEdge.split(",")[1]+","+criticalEdge.split(",")[0];
//                double weight2 = weightMap.getOrDefault(back,0.0);
//                System.out.println(back+":"+weight2);
//                criticalWeights.add(Math.max(weight1,weight2)/avg);
//                minCritical = Math.min(minCritical,Math.max(weight1,weight2));
//
//            }
//
//            PrintWriter pw = new PrintWriter(resultDir+"/critical_weights");
//            for(double d : criticalWeights)
//                pw.println(d);
//            pw.close();
//
//            double startToMiss = minCritical/avg;
//
//            if(1-minCritical>1e-8){
//                infer.filterGraphBasedOnAverageWeight(minCritical);
//                infer.removeIsolatedIslands(detection);
//            }
//            infer.exportGraph(resultDir+"Filter_"+filename+suffix);
//
//            System.out.println(String.format("critical edge number: %d",criticalEdges.length));
//            os.write(String.format("\ncritical edge number: %d",criticalEdges.length).getBytes());
//
//            System.out.println(String.format("minimum critical weight: %.3f",minCritical));
//            os.write(String.format("\nminimum critical weight: %.3f",minCritical).getBytes());
//
//            System.out.println(String.format("start to miss: %.3f",startToMiss));
//            os.write(String.format("\nstart to miss: %.3f",startToMiss).getBytes());
//
//            System.out.println("After Filter vertex number is: "+ infer.graph.vertexSet().size() + " edge number: " + infer.graph.edgeSet().size());
//            os.write(String.format("\nAfter Filter vertex number is: %d edge number: %d",infer.graph.vertexSet().size(),infer.graph.edgeSet().size()).getBytes());
//            cmd = new String[] {"/bin/sh","-c","dot -T svg "+resultDir+"Filter_"+filename+suffix+".dot"
//                    + " > "+resultDir+"Filter_"+filename+suffix+".svg"};
//            rt.exec(cmd);
//
//            for(double i=0.0; i<=2.5; i+=0.05){
//                while(pq.size()>0&&pq.peek().weight<i*avg) pq.poll();
//                System.out.println(String.format("%.2f - After Filter edge number is: ",i)+ pq.size());
//                os.write(String.format("\n %.2f - After Filter edge number is: %d",i,pq.size()).getBytes());
//            }


        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try{
                os.close();
//                weightfile.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }




    public static double getTimeCost(long start, long end){
        return (end-start)*1.0/1000.0;
    }
}
