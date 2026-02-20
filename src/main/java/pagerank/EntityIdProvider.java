package pagerank;

import org.jgrapht.ext.ComponentNameProvider;

/**
 * Created by fang on 3/21/18.
 */
public class EntityIdProvider implements ComponentNameProvider<EntityNode> {
    @Override
    public String getName(EntityNode e) {
//        System.out.println(e.getID());
        return "" + e.getID();
    }
}
