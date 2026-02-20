package read_dot;

import org.jgrapht.ext.ComponentNameProvider;

public class ExportEdgeProvider implements ComponentNameProvider<SimpleEdge> {


    @Override
    public String getName(SimpleEdge eventEdge) {
        return  0+" "+ eventEdge.weight;                   //no weights
    }
}
