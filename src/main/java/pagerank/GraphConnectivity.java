package pagerank;

import org.jgrapht.alg.ConnectivityInspector;
import org.jgrapht.alg.KosarajuStrongConnectivityInspector;
import org.jgrapht.alg.interfaces.StrongConnectivityAlgorithm;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

/**
 * Created by fang on 5/7/18.
 */
public class GraphConnectivity {
    private DirectedPseudograph<EntityNode, EventEdge> graph;
    FileWriter writer;
    GraphConnectivity(DirectedPseudograph<EntityNode, EventEdge> graph){
        this.graph = graph;

    }

    public void testStrongConn(String name) {
        StrongConnectivityAlgorithm<EntityNode, EventEdge> scAlg =
                new KosarajuStrongConnectivityInspector<EntityNode, EventEdge>(graph);
        List<Set<EntityNode>> stronglyConnetedSet =
                scAlg.stronglyConnectedSets();
        try {
            writer = new FileWriter(new File(name));
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Strongly connected components:");
        for (int i = 0; i < stronglyConnetedSet.size(); i++) {
            try {
                writer.write(i + " " + System.lineSeparator());
                for (EntityNode node : stronglyConnetedSet.get(i)) {
                    writer.write(node.toString() + System.lineSeparator());
                }
                writer.write(System.lineSeparator());
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        try {
            writer.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void testWeakConnectivity(String name){
        ConnectivityInspector<EntityNode, EventEdge> connectivityInspector = new ConnectivityInspector<>(graph);
        List<Set<EntityNode>> weaklyConnected = connectivityInspector.connectedSets();
        try {
            File file = new File(name);
            if(!file.exists()){
                file.createNewFile();
            }
            writer = new FileWriter(file);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Weakly connected component size: "+ weaklyConnected.size());
        for (int i = 0; i < weaklyConnected.size(); i++) {
            try {
                writer.write(i + " " + System.lineSeparator());
                for (EntityNode node : weaklyConnected.get(i)) {
                    writer.write(node.toString() + System.lineSeparator());
                }
                writer.write(System.lineSeparator());
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        try {
            writer.close();
        }catch (Exception e){
            e.printStackTrace();
        }

    }
}
