package pagerank.provider;

import pagerank.entity.EntityNode;
import pagerank.entity.Process;

import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Created by fang on 3/26/18.
 */
public class EntityAttributeProvider implements Function<EntityNode, Map<String, Attribute>> {
    @Override
    public Map<String, Attribute> apply(EntityNode e) {
        HashMap<String, Attribute> map = new HashMap<>();
        if (e.attributes == null) {
            if (e.getP() != null) {
                map.put("shape", DefaultAttribute.createAttribute("box"));
            }
            if (e.getF() != null) {
                map.put("shape", DefaultAttribute.createAttribute("ellipse"));
            }
            if (e.getN() != null) {
                map.put("shape", DefaultAttribute.createAttribute("parallelogram"));
            }
        } else {
            String type = e.attributes.get("type");
            if (type.equals("Process")) {
                map.put("shape", DefaultAttribute.createAttribute("box"));
            } else if (type.equals("File")) {
                map.put("shape", DefaultAttribute.createAttribute("ellipse"));
            } else {
                map.put("shape", DefaultAttribute.createAttribute("parallelogram"));
            }
        }
        return map;
    }
}
