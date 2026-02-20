package logparsers.systemcalls;

import pagerank.Entity;

import java.util.Map;

public interface Action {
    void apply(Map<String, String> begin, Map<String, String> end, Entity[] startEntities, Entity[] endEntities);

}
