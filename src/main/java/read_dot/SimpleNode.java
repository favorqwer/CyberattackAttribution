package read_dot;

public class SimpleNode {
    long id;
    double reputation;
    String signature;
    String shape;

    public SimpleNode(long id, double reputation, String signature, String shape){
        this.id = id;
        this.reputation = reputation;
        this.signature = signature;
        this.shape = shape;
    }

    @Override
    public String toString() {
        return signature;
    }
}
