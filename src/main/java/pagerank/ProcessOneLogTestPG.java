package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Test code by Peng Gao on 11/4/2018
 */
public class ProcessOneLogTestPG {

    public static void main(String[] args){
        String[] localIP = MetaConfig.localIP;

        // curl
        String[] highRP = {"192.168.29.234:40402->208.118.235.20:80"};
        String[] midRP = MetaConfig.midRP;
        String[] lowRP = {};
        String path = "input/logs_fine/curl.txt";
        String detection="/home/lcl/target";
        double threshold = 0.3;
        double detectionSize = 4202290;


//        // cat_merge_good
//        String[] highRP = {"/media/lcl/LCL/good.zip", "/media/lcl/LCL/good2.zip"};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {};
//        String path = "input/logs_fine/cat_merge_good.txt";
//        String detection="/home/lcl/target";
//        double threshold = 0.3075880759;
//        double detectionSize = 58083712;


//        // mal_script
//        String[] highRP = {"/home/lcl/mal.sh"};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {};
//        String path = "input/logs_fine/mal_script.txt";
//        String detection="/home/lcl/leaked.txt";
//        double threshold = 0.232;
//        double detectionSize = 30488;


//        // shell_wget_tar
//        String[] highRP = {};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {"192.168.29.234:54764->208.118.235.20:80"};
//        String path = "input/logs_fine/shell_wget_tar.txt";
//        String detection="/home/lcl/wget-1.19/INSTALL";
//        double threshold = 0.13;
//        double detectionSize = 15756;


//        // python_wget_tar
//        String[] highRP = {"192.168.29.234:52820->208.118.235.20:80"};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {};
//        String path = "input/logs_fine/python_wget_tar.txt";
//        String detection="/home/lcl/wget-1.19/INSTALL";
//        double threshold = 0.1368055556;
//        double detectionSize = 15756;


//        // command_injection log_vm0:step5
//        String[] highRP = {};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {"172.31.77.48:55486->172.31.71.251:44444"};
//        String path = "input/attack_cases/command_injection/log_vm0.txt";
//        String detection="/tmp/password_in_files.txt";
//        double threshold = 0.2;
//        double detectionSize = 20881;


//        // vpnfilter_step_1
//        String[] highRP = {"172.31.71.251:51926->172.31.77.48:80"};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {};
//        String path = "input/attacks_fine/vpnfilter_step1.txt";
//        String detection="/tmp/img";
//        double threshold = 0.2;
//        double detectionSize = 92433;


//        // vpnfilter_step_5
//        String[] highRP = {"172.31.71.251:51928->172.31.77.48:80"};
//        String[] midRP = MetaConfig.midRP;
//        String[] lowRP = {};
//        String path = "input/attacks_fine/vpnfilter_step5.txt";
//        String detection="/var/vpnfilter";
//        double threshold = 0.2;
//        double detectionSize = 296592;


        String resultDir = "results/";
        String suffix = "";
        boolean trackOrigin = false;
        String[] paths = path.split("/");


        Set<String> seedSources = new HashSet<>();
        for(String s: highRP){
            seedSources.add(s);
        }
        for(String s : lowRP){
            seedSources.add(s);
        }

//        process_old(resultDir, suffix, threshold, trackOrigin, path,localIP,detection,highRP,midRP,lowRP, paths[paths.length-1].split("\\.")[0]);
        process(resultDir, suffix, threshold, trackOrigin, path,localIP,detection,highRP,midRP,lowRP, paths[paths.length-1].split("\\.")[0], detectionSize, seedSources);
    }

    public static void process(String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection,String[] highRP,String[] midRP,String[] lowRP, String filename,double detectionSize, Set<String> seedSources){
        //String resultDir =  "/home/lcl/results/exp/";
        //String suffix = "";
        OutputStream os = null;
        OutputStream weightfile = null;
        try{
            os = new FileOutputStream(resultDir+filename+suffix+"_stats");
            GetGraph getGraph = new GetGraph(logfile, IP);
            getGraph.GenerateGraph();
            DirectedPseudograph<EntityNode, EventEdge> orignal = getGraph.getJg();
            System.out.println("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size());
            os.write(("Original vertex number:" + orignal.vertexSet().size() + " edge number : " + orignal.edgeSet().size()+"\n").getBytes());

            BackTrack backTrack = new BackTrack(orignal);
            backTrack.backTrackPOIEvent(detection);
            System.out.println("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size());
            os.write(("After Backtrack vertex number is: "+ backTrack.afterBackTrack.vertexSet().size() + " edge number: " + backTrack.afterBackTrack.edgeSet().size()+"\n").getBytes());

            IterateGraph out = new IterateGraph(backTrack.afterBackTrack);
            out.exportGraph(resultDir+"BackTrack_"+filename+suffix);
            //backTrack.exportGraph("backTrack");

//            // If we run cpr1 and cpr2 together, the cpr1 result will effect cpr2, need more investigation
//            CausalityPreserve CPR1 = new CausalityPreserve(backTrack.afterBackTrack);
//            CPR1.CPR(1);
//            System.out.println("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size());
//            os.write(("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size()+"\n").getBytes());

            CausalityPreserve CPR = new CausalityPreserve(backTrack.afterBackTrack);
            CPR.CPR(2);
            System.out.println("After CPR2 vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size());
            os.write(("After CPR2 vertex number is: "+ CPR.afterMerge.vertexSet().size() + " edge number: " + CPR.afterMerge.edgeSet().size()+"\n").getBytes());

            out = new IterateGraph(CPR.afterMerge);
            out.exportGraph(resultDir+"AfterCPR_"+filename+suffix);
            GraphSplit split = new GraphSplit(CPR.afterMerge);
            split.splitGraph();
            System.out.println("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size());

            InferenceReputation infer = new InferenceReputation(split.inputGraph);

            os.write(("After Split vertex number is: "+ split.inputGraph.vertexSet().size() + " edge number: " + split.inputGraph.edgeSet().size()+"\n").getBytes());
//            weightfile = new FileOutputStream(resultDir+"weights_"+filename+suffix);
//            for(EventEdge e: infer.graph.edgeSet()){
//                weightfile.write((String.valueOf(e.weight)+",").getBytes());
//            }

            infer.setDetectionSize(detectionSize);
            infer.setSeedSources(seedSources);   // set structure weight for source
//            infer.calculateWeights();
//            infer.calculateWeights_ML(true);
//            infer.calculateWeights_Individual(true, "timeWeight");
//            infer.calculateWeights_Individual(true, "amountWeight");
//            infer.calculateWeights_Individual(true, "structureWeight");
            //infer.calculateWeights_Fanout(true);
            //System.out.println("OTSU: "+infer.OTSUThreshold());


            //infer.normalizeWeightsAfterFiltering();
            infer.initialReputation(highRP,midRP,lowRP);
            infer.PageRankIteration2(highRP,midRP,lowRP,detection);
            //infer.PageRankIteration(detection);
            //infer.fixReputation(highRP);

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
//            infer.extractSuspects(0.5);
//            infer.exportGraph(resultDir+"Suspect_"+filename+suffix);
//        iterGraph.filterGraphBasedOnVertexReputation();
//        iterGraph.removeSingleVertex();
//        iterGraph.exportGraph("FilteredInstallMongodb");
//        List<DirectedPseudograph<EntityNode, EventEdge>> paths = iterGraph.getHighWeightPaths(detection);
//        for(int i=0; i< paths.size();i++){
//            IterateGraph iter = new IterateGraph(paths.get(i));
//            String fileName = String.valueOf(i) + "path";
//            iter.exportGraph(fileName);
//        }
            //iterGraph.printEdgesOfVertex("11035dpkg");
//        infer.checkWeightsAfterCalculation();
//        infer.exportGraph("UnrarReputation");
            Runtime rt = Runtime.getRuntime();
            String[] cmd = {"/bin/sh","-c","dot -T svg "+resultDir+"AfterCPR_"+filename+suffix+".dot"
                    + " > "+resultDir+"AfterCPR_"+filename+suffix+".svg"};
            rt.exec(cmd);
            cmd = new String[]{"/bin/sh", "-c","dot -T svg "+resultDir+"Weight_"+filename+suffix+".dot"
                    + " > "+resultDir+"Weight_"+filename+suffix+".svg"};
            rt.exec(cmd);

            double avg = infer.getAvgWeight();
            for(double i=0.0; i<=2.5; i+=0.05){
                try{
                    infer.filterGraphBasedOnAverageWeight(i*avg);
                    infer.removeIsolatedIslands(detection);
                    infer.exportGraph(String.format(resultDir+"Filter_%.2f_"+filename+suffix,i));
                    System.out.println(String.format("%.2f - After Filter vertex number is: ",i)+ infer.graph.vertexSet().size() + " edge number: " + infer.graph.edgeSet().size());
                    os.write(String.format("\n %.2f - After Filter vertex number is: %d edge number: %d",i,infer.graph.vertexSet().size(),infer.graph.edgeSet().size()).getBytes());
                    cmd = new String[] {"/bin/sh","-c","dot -T svg "+String.format(resultDir+"Filter_%.2f_"+filename+suffix,i)+".dot"
                            + " > "+String.format(resultDir+"Filter_%.2f_"+filename+suffix,i)+".svg"};
                    rt.exec(cmd);

                }catch (Exception e){
                    System.out.println(String.format("\n %.2f - After Filter vertex number is: -1 edge number: -1",i));
                    os.write(String.format("\n %.2f - After Filter vertex number is: -1 edge number: -1",i).getBytes());
                    continue;
                }

            }



//            cmd = new String[]{"/bin/sh", "-c","dot -T svg "+resultDir+"Suspect_"+filename+suffix+".dot"
//                    + " > "+resultDir+"Suspect_"+filename+suffix+".svg"};
//            rt.exec(cmd);
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
}