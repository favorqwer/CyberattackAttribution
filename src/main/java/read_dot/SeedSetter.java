package read_dot;
import org.jgrapht.graph.DirectedPseudograph;
import pagerank.Experiment;

import java.util.*;
public class SeedSetter {
    Random random = new Random();
    //Set library reputation
    public void setSeedReputation(DirectedPseudograph<SimpleNode, SimpleEdge> graph, Experiment exp){
        List<String> library = new LinkedList<>();
        Set<SimpleNode> nodes = graph.vertexSet();
        List<String> highRP = exp.getHighRP();
        List<String> lowRP = exp.getLowRP();
        for(SimpleNode v : nodes){
            //Set reputation for high or low Seed
            if(highRP.contains(v.signature) || lowRP.contains(v.signature)){
                if (highRP.contains(v.signature)){
                    v.reputation = 1.0;
                }
                if(lowRP.contains(v.signature)){
                    v.reputation = 0.0;
                }
                continue;
            }
            // Set reputation for library
            if(graph.incomingEdgesOf(v).size() == 0){
                v.reputation = getLibraryReputation("uniform");
            }
        }
    }

    private double getLibraryReputation(String distribution){

        if (distribution.equals("uniform")){
            return uniformReputation();
        }
        return 0.0;
    }
    // five category: 0.3, 0.4, 0.5, 0.6, 0.7
    private double uniformReputation(){
        int rand = random.nextInt(100);
        if (rand <= 19){
            return 0.3;
        }else if(rand <= 39){
            return 0.4;
        }else if(rand <= 59){
            return 0.5;
        }else if(rand <= 79){
            return 0.6;
        }else{
            return 0.7;
        }
    }
}
