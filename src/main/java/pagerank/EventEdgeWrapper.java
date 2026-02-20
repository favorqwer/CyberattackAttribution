package pagerank;

import org.apache.commons.math3.ml.clustering.Clusterable;

// Wrapper class
public class EventEdgeWrapper implements Clusterable {
    private double[] points;
    private EventEdge edge;

    public EventEdgeWrapper(EventEdge edge) {
        this.edge = edge;
        this.points = new double[] {edge.timeWeight, edge.amountWeight, edge.structureWeight};
    }

    public EventEdge getEventEdge() {
        return edge;
    }

    public double[] getPoint() {
        return points;
    }
}
