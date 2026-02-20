package read_dot;

import org.jgrapht.ext.VertexProvider;

import java.util.Map;

public class DotEntityProvider  implements VertexProvider<SimpleNode>{
    @Override
    public SimpleNode buildVertex(String label, Map<String, String> attributes){
        long id = Long.parseLong(label);
        String[] nameAndRep = attributes.get("label").split(" \\[");
        String name = nameAndRep[0];
//        System.out.println(nameAndRep[1]);
        double reputation = Double.parseDouble(nameAndRep[1].substring(0,nameAndRep[1].length()-1));
        String type = attributes.get("shape");

        SimpleNode node = new SimpleNode(id, reputation, name, type);
        return node;
    }
}
