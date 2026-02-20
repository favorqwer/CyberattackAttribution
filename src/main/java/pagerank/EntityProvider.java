package pagerank;

import org.jgrapht.ext.VertexProvider;
import java.util.*;

/**
 * Created by fang on 3/26/18.
 * in order to read dot file to generate test case(graph)
 */
public class EntityProvider  implements VertexProvider<EntityNode>{
    @Override
    public EntityNode buildVertex(String label, Map<String, String> attributes){
        long id = Long.parseLong(label);
        String name = attributes.get("name");
        String type = attributes.get("type");
        //System.out.println(type);
        double reputation = 0;
        if(attributes.get("reputation")!= null){
            reputation = Double.parseDouble(attributes.get("reputation"));
        }


        EntityNode node = new EntityNode(id, reputation, attributes);
        return node;
    }
}
