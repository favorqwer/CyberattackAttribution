package read_dot;

import org.jgrapht.ext.EdgeProvider;
import java.util.*;


public class DotEdgeProvider implements EdgeProvider<SimpleNode, SimpleEdge> {
    @Override
    public SimpleEdge buildEdge(SimpleNode from, SimpleNode to,String lable, Map<String, String>attributes){

        double weight = Double.parseDouble(attributes.get("label").split(" ")[1]);
        SimpleEdge edge = new SimpleEdge(from, to, weight);
        return edge;
    }
}
