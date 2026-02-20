package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.io.*;
import java.util.*;

public class Classifier {
    DirectedPseudograph<EntityNode, EventEdge> graph;

    public Classifier(DirectedPseudograph<EntityNode, EventEdge> graph){
        this.graph = graph;
    }

    public List<double[]> createTrainingSet(Map<String,Set<String>> positive){

        Set<EventEdge> s = graph.edgeSet();
        List<double[]> l = new ArrayList<>();
        for(EventEdge e: s){
            double[] weights = new double[4];
            weights[0] = e.amountWeight;
            weights[1] = e.timeWeight;
            weights[2] = e.structureWeight;
            if(positive.containsKey(graph.getEdgeSource(e).getSignature()) &&
                    positive.get(graph.getEdgeSource(e).getSignature()).contains(graph.getEdgeTarget(e).getSignature()))
                weights[3] = 1;
            else weights[3] = 0;
            l.add(weights);
        }

        return l;
    }

    public Map<String, Set<String>> buildMapFromFile(String path) throws FileNotFoundException,Exception{

        Map<String, Set<String>> m = new HashMap<>();

        File f = new File(path);

        if(!f.exists()||!f.isFile()) throw new FileNotFoundException(path);


        FileReader in = new FileReader(f);
        Scanner sc = new Scanner(in);

        Set<String> sigs = new HashSet<>();
        for(EntityNode v : graph.vertexSet()) sigs.add(v.getSignature());

        while(sc.hasNextLine()){
            String line = sc.nextLine();
            String[] pair = line.split(",");
            if(pair.length!=2) throw new Exception("Can not parse input file: "+line);
            if(!sigs.contains(pair[0])) throw new Exception("Vertex not found: "+pair[0]);
            if(!sigs.contains(pair[1])) throw new Exception("Vertex not found: "+pair[1]);
            m.computeIfAbsent(pair[0],k -> new HashSet<>()).add(pair[1]);
        }
        in.close();

        return m;
    }

    public void printTrainingSetSVM(List<double[]> l, String path,boolean append) throws IOException {
        File f = new File(path);
        if(!f.exists()||!f.isFile())
            f.createNewFile();

        FileWriter fw = new FileWriter(f,append);
        for(double[] edge : l){
            fw.write(String.format("%d %d:%f %d:%f %d:%f\n",(int)edge[3],1,edge[0],2,edge[1],3,edge[2]));
        }
        fw.close();
    }




}
