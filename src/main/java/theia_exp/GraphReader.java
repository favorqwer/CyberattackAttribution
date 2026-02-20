package theia_exp;

import org.jgrapht.ext.DOTImporter;
import org.jgrapht.graph.DirectedPseudograph;
import org.json.simple.JSONObject;
import pagerank.EntityNode;
import pagerank.EventEdge;
import pagerank.MetaConfig;
import read_dot.DotEntityProvider;
import read_dot.SimpleNode;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import pagerank.ProcessOneLogCMD_19;

public class GraphReader {
    DirectedPseudograph<EntityNode, EventEdge> graph;
    public DirectedPseudograph<EntityNode, EventEdge> readGraph(String file){

        DOTImporter<EntityNode, EventEdge> importer = new DOTImporter<EntityNode, EventEdge>(new EntityNodeProvider(), new SimpleEdgeProvider());
        graph = new DirectedPseudograph<EntityNode, EventEdge>(EventEdge.class);
        try {
            importer.importGraph(graph, new File(file));
        }catch (Exception e){
            e.printStackTrace();
        }

        return graph;
    }

    public static void main(String[] args) {
        String path = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\test\\vpnfilter\\vpnfilter_test.dot";
        String resultDir = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\test\\res";
        String suffix = "theia";
        double threshold = 0.0;
        boolean trackOrigin = true;
        String logfile = "C:\\Users\\fang2\\OneDrive\\Desktop\\reptracker\\reptracker\\test\\test.log";
        String[] IP = new String[0];
        String detection = "";
        String [] higtRP = {"/home/admin/clean"};
        String[] midRP = MetaConfig.midRP;
        String[] lowRP = new String[0];
        String fileName = "theia";
        double dectionSize = 166784;
        String[] criticalEdges = new String[0];
        Set<String> seedSources = new HashSet<>();
        seedSources.add(higtRP[0]);
        String mode = "clusterall";
        JSONObject jsonObject = new JSONObject();
        String[] entries = new String[0];
        GraphReader test = new GraphReader();
        DirectedPseudograph<EntityNode, EventEdge> graph = test.readGraph(path);
        Set<EventEdge> edges = graph.edgeSet();
        for(EventEdge e : edges) {
            System.out.println(e.id);
        }
//        ProcessOneLogCMD_19.run_exp_backward_without_backtrack(graph, resultDir, suffix, threshold, trackOrigin, logfile,
//                IP, detection, higtRP, midRP,lowRP, fileName, dectionSize, seedSources,criticalEdges, mode,jsonObject,entries);

    }
}
