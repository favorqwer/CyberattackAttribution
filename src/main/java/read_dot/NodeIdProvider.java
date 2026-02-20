package read_dot;

import org.jgrapht.ext.ComponentNameProvider;

public class NodeIdProvider implements ComponentNameProvider<SimpleNode> {
    @Override
    public String getName(SimpleNode simpleNode) {
        return simpleNode.id+"";
    }
}
