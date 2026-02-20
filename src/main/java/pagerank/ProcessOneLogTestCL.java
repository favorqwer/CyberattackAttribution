package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by fang on 4/6/18.
 */
public class ProcessOneLogTestCL {

    public static void main(String[] args){
        String[] localIP = MetaConfig.localIP;
        String[] midRP = MetaConfig.midRP;
        String path = "/home/lcl/chmod.txt";
        String detection="/home/lcl/target";
//        String path = "input/test_logs/recvfrom.txt";
//        String detection = "/home/lcl/target.tar.bz2";

        double threshold = 0.1;


        String[] highRP = {};
        String[] lowRP = {"192.168.29.234:40402->208.118.235.20:80"};//"/media/lcl/LCL/bad.zip"};//"192.168.29.125:10289->192.168.29.234:22"}



//        String path = "input/logs_extended/cat_merge_goodbad.txt";
//        String path = "input/logs_extended/file_manipulation/3.txt";

//        String path = "input/logs_extended/ssh.txt";
//        String detection = "/home/lcl/mal.sh";
//        String[]localIP = {"192.168.29.234"};
//        double threshold = 0.0093;
//
//        String[] highRP = {};
//        String[] midRP = {"/dev/input/event6","/lib64/libpthread.so.0", "/lib64/libm.so.6", "/usr/lib64/python2.7/lib-dynload/_localemodule.so", "/lib64/libutil.so.1", "/lib64/libc.so.6", "/lib64/libdl.so.2", "/lib64/libpython2.7.so.1.0","/dev/input/event5","/dev/pts/1","/etc/nsswitch.conf","/proc/self/maps","/usr/lib/jvm/java-8-oracle/jre/lib/amd64/libverify.so","/usr/lib/jvm/java-8-oracle/jre/lib/ext/meta-index","/usr/lib/jvm/java-8-oracle/jre/lib/amd64/jvm.cfg","/etc/localtime","/usr/lib/jvm/java-8-oracle/jre/lib/meta-index","/usr/lib/jvm/java-8-oracle/jre/lib/rt.jar","/usr/lib/jvm/java-8-oracle/jre/lib/amd64/libjava.so","/usr/lib/jvm/java-8-oracle/jre/lib/amd64/libzip.so","/usr/share/locale/locale.alias","/dev/pts/0","/proc/filesystems","/etc/security/capability.conf","/etc/nsswitch.conf","/proc/self/loginuid","/dev/urandom","/lib/x86_64-linux-gnu/security/pam_env.so","/usr/lib/x86_64-linux-gnu/libk5crypto.so.3","/lib/x86_64-linux-gnu/security/pam_selinux.so","/lib/x86_64-linux-gnu/libutil.so.1","/lib/x86_64-linux-gnu/libexpat.so.1","/lib/x86_64-linux-gnu/libaudit.so.1","/lib/x86_64-linux-gnu/libpcre.so.3","/lib/x86_64-linux-gnu/security/pam_nologin.so","/lib/x86_64-linux-gnu/libgpg-error.so.0","/lib/x86_64-linux-gnu/libnsl.so.1","/lib/x86_64-linux-gnu/libcom_err.so.2","/lib/x86_64-linux-gnu/libresolv.so.2","/lib/x86_64-linux-gnu/libcap-ng.so.0","/lib/x86_64-linux-gnu/security/pam_permit.so","/lib/x86_64-linux-gnu/libtinfo.so.5","/lib/x86_64-linux-gnu/libkeyutils.so.1","/usr/lib/x86_64-linux-gnu/liblz4.so.1","/lib/x86_64-linux-gnu/librt.so.1","/lib/x86_64-linux-gnu/libgcrypt.so.20","/lib/x86_64-linux-gnu/libnss_systemd.so.2","/lib/x86_64-linux-gnu/security/pam_cap.so","/lib/x86_64-linux-gnu/security/pam_loginuid.so","/lib/x86_64-linux-gnu/libpam.so.0","/lib/x86_64-linux-gnu/libpam_misc.so.0","/usr/lib/x86_64-linux-gnu/libkrb5support.so.0","/usr/lib/x86_64-linux-gnu/libkrb5.so.3","/usr/lib/x86_64-linux-gnu/libgssapi_krb5.so.2","/lib/x86_64-linux-gnu/libcrypt.so.1","/lib/x86_64-linux-gnu/libnss_compat.so.2","/lib/x86_64-linux-gnu/libz.so.1","/usr/lib/x86_64-linux-gnu/libzstd.so.1","/usr/lib/x86_64-linux-gnu/libapt-pkg.so.5.0","/lib/x86_64-linux-gnu/security/pam_keyinit.so","/lib/x86_64-linux-gnu/security/pam_systemd.so","/usr/lib/python3.6/lib-dynload/_csv.cpython-36m-x86_64-linux-gnu.so","/lib/x86_64-linux-gnu/libudev.so.1","/lib/x86_64-linux-gnu/libgcc_s.so.1","/lib/x86_64-linux-gnu/security/pam_deny.so","/lib/x86_64-linux-gnu/liblzma.so.5","/lib/x86_64-linux-gnu/security/pam_gnome_keyring.so","/usr/lib/x86_64-linux-gnu/libcrypto.so.1.0.0","/lib/x86_64-linux-gnu/libsystemd.so.0","/lib/x86_64-linux-gnu/security/pam_umask.so","/lib/x86_64-linux-gnu/libm.so.6","/lib/x86_64-linux-gnu/libcap.so.2","/lib/x86_64-linux-gnu/libbz2.so.1.0","/lib/x86_64-linux-gnu/libpthread.so.0","/lib/x86_64-linux-gnu/libnss_files.so.2","/usr/lib/x86_64-linux-gnu/libstdc++.so.6","/lib/x86_64-linux-gnu/libnss_nis.so.2","/lib/x86_64-linux-gnu/libselinux.so.1","/lib/x86_64-linux-gnu/libdl.so.2","/lib/x86_64-linux-gnu/security/pam_mail.so","/lib/x86_64-linux-gnu/security/pam_limits.so","/lib/x86_64-linux-gnu/libc.so.6","/lib/x86_64-linux-gnu/security/pam_unix.so","/lib/x86_64-linux-gnu/security/pam_motd.so","/usr/lib/x86_64-linux-gnu/libapt-private.so.0.0","/lib/x86_64-linux-gnu/libwrap.so.0"};
//        String[] lowRP = {"192.168.29.223:58853->192.168.29.234:22"};//"/media/lcl/LCL/bad.zip"};//"192.168.29.125:10289->192.168.29.234:22"}



        String resultDir = "results/exp/";
        String suffix = "";
        boolean trackOrigin = false;


        String[] paths = path.split("/");
        process(resultDir, suffix, threshold, trackOrigin, path,localIP,detection,highRP,midRP,lowRP, paths[paths.length-1].split("\\.")[0],0,new HashSet<>());
    }

    public static void process(String resultDir, String suffix, double threshold, boolean trackOrigin, String logfile, String[] IP, String detection, String[] highRP, String[] midRP, String[] lowRP, String filename, double detectionSize,Set<String> seedSources){
        //String resultDir =  "/home/lcl/results/exp/";
        //String suffix = "";
        OutputStream os = null;
        OutputStream weightfile = null;
        PrintWriter pw = null;
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
//            backTrack.exportGraph("backTrack");

            CausalityPreserve CPR1 = new CausalityPreserve(backTrack.afterBackTrack);
            CPR1.CPR(1);
            System.out.println("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size());
            os.write(("After CPR1 vertex number is: "+ CPR1.afterMerge.vertexSet().size() + " edge number: " + CPR1.afterMerge.edgeSet().size()+"\n").getBytes());

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
            infer.calculateWeights_ML(true,3);
//            infer.calculateWeights();
            pw = new PrintWriter("results/weights/"+filename);
            for(EventEdge e : infer.graph.edgeSet()){
                boolean outlier = infer.graph.incomingEdgesOf(e.getSink()).size()==1;
                pw.println(e.getSource().getSignature()+" -> "+e.getSink().getSignature()+","+
                        e.timeWeight+","+
                        e.amountWeight+","+
                        e.structureWeight+","+
                        outlier);
            }
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
//            String[] cmd = {"/bin/sh","-c","dot -T svg "+resultDir+"BackTrack_"+filename+suffix+".dot"
//                    + " > "+resultDir+"BackTrack_"+filename+suffix+".svg"};
//            rt.exec(cmd);
            String[] cmd = new String[] {"/bin/sh","-c","dot -T svg "+resultDir+"AfterCPR_"+filename+suffix+".dot"
                    + " > "+resultDir+"AfterCPR_"+filename+suffix+".svg"};
            rt.exec(cmd);
            cmd = new String[] {"/bin/sh", "-c","dot -T svg "+resultDir+"Weight_"+filename+suffix+".dot"
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

        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try{
                os.close();
                pw.close();
//                weightfile.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }
}