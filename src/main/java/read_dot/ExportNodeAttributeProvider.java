package read_dot;

import org.jgrapht.ext.ComponentAttributeProvider;

import java.util.HashMap;
import java.util.Map;

public class ExportNodeAttributeProvider implements ComponentAttributeProvider<SimpleNode> {
    @Override
    public Map<String, String> getComponentAttributes(SimpleNode e){
        HashMap<String, String> map = new HashMap<>();
        map.put("shape",e.shape);
        return map;
    }
}
