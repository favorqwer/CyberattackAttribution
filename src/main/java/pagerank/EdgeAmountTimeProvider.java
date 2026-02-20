package pagerank;

import org.jgrapht.ext.ComponentNameProvider;

/**
 * Created by fang on 4/22/18.
 */
public class EdgeAmountTimeProvider implements ComponentNameProvider<EventEdge> {
    @Override
    public String getName(EventEdge e){
        return e.getSize()+" "+ e.getStartTime().toString()+","+ e.getEndTime().toString();
    }
}
