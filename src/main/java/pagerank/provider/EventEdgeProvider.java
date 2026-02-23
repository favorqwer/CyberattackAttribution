package pagerank.provider;
import pagerank.entity.EventEdge;

import org.jgrapht.ext.ComponentNameProvider;

/**
 * Created by fang on 3/21/18.
 */
public class EventEdgeProvider implements ComponentNameProvider<EventEdge> {


    @Override
    public String getName(EventEdge eventEdge) {
        return  eventEdge.id+" "+ eventEdge.weight;                   //no weights
    }
}
