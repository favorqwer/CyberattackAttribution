package read_dot;

public class SimpleEdge {
    SimpleNode from;
    SimpleNode to;
    double weight;

    public SimpleEdge(SimpleNode from, SimpleNode to, double weight){
        this.from = from;
        this.to = to;
        this.weight = weight;
    }

    public SimpleEdge(SimpleNode from, SimpleNode to){
        this.from = from;
        this.to = to;
        this.weight = 0.0;
    }

    @Override
    public String toString() {
        return from.toString()+"=>"+to.toString();
    }
}
