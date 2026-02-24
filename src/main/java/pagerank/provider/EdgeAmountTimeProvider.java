package pagerank.provider;

import pagerank.entity.EventEdge;

import java.util.function.Function;

/**
 * Created by fang on 4/22/18.
 */
public class EdgeAmountTimeProvider implements Function<EventEdge, String> {
    @Override
    public String apply(EventEdge e) {
        return e.getSize() + " " + e.getStartTime().toString() + "," + e.getEndTime().toString();
    }
}
