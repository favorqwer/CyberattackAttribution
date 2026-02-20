package pagerank;

import org.jgrapht.ext.ComponentAttributeProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by fang on 3/26/18.
 */
public class EntityAttributeProvider implements ComponentAttributeProvider<EntityNode> {
    @Override
    public Map<String, String> getComponentAttributes(EntityNode e){
        HashMap<String, String> map = new HashMap<>();
        if(e.attributes == null) {
            if (e.getP() != null) {
                map.put("shape", "box");
            }
            if (e.getF() != null) {
                map.put("shape", "ellipse");
            }
            if (e.getN() != null) {
                map.put("shape", "parallelogram");
            }
        }else{
            String type = e.attributes.get("type");
            if(type.equals("Process")){
                map.put("shape", "box");
            }else if(type.equals("File")){
                map.put("shape","ellipse");
            }else{
                map.put("shape","parallelogram");
            }
        }
        return map;
    }
}
