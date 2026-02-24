package pagerank.provider;

import pagerank.entity.EntityNode;

import java.util.function.Function;

/**
 * Created by fang on 3/21/18.
 */
public class EntityIdProvider implements Function<EntityNode, String> {
    @Override
    public String apply(EntityNode e) {
        // System.out.println(e.getID());
        return "" + e.getID();
    }
}
