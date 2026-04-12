package pagerank.provider;

import pagerank.entity.EventEdge;

import java.util.function.Function;

public class EventEdgeProvider implements Function<EventEdge, String> {

    @Override
    public String apply(EventEdge eventEdge) {
        String syscall = eventEdge.getEvent();
        if (syscall == null || syscall.trim().isEmpty()) {
            syscall = "unknown";
        }
        return syscall;
    }
}
