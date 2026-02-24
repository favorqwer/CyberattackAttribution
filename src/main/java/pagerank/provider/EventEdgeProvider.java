package pagerank.provider;

import pagerank.entity.EventEdge;

import java.util.function.Function;

/**
 * Created by fang on 3/21/18.
 */
public class EventEdgeProvider implements Function<EventEdge, String> {

    @Override
    public String apply(EventEdge eventEdge) {
        return eventEdge.id + " " + eventEdge.weight; // no weights
    }
}
