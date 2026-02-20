package pagerank;

import java.util.Map;

/**
 * Created by fang on 3/21/18.
 */
public class EntityNode{
    //Entity m;
    private long ID;
    private FileEntity f;
    private NetworkEntity n;
    private Process p;
    private String signature;
    double reputation;
    Map<String, String>attributes;

    public EntityNode(FileEntity f){
        this.f = f;
        this.ID = f.getUniqID();
        this.n = null;
        this.p = null;
        signature = f.getPath();
        reputation = f.getReputation();
    }

    public EntityNode(Process p){
        this.p = p;
        f = null;
        n = null;
        this.ID = p.getUniqID();
        signature = p.getPidAndName();
        reputation = p.getReputation();
    }

    public EntityNode(NetworkEntity n){
        this.n = n;
        f = null;
        p = null;
        this.ID  = n.getUniqID();
        signature = n.getSrcAndDstIP();
        reputation = n.getReputation();
    }

    EntityNode(EntityNode e) {
        this.f = e.getF();
        this.n = e.getN();
        this.p = e.getP();
        this.ID = e.getID();
        this.signature = e.getSignature();
        this.reputation = e.reputation;

    }

    EntityNode(EntityNode old, long id){
        this.f = old.getF();
        this.n = old.getN();
        this.p = old.getP();
        this.ID = id;
        this.signature = old.getSignature();
        this.reputation = old.reputation;
    }

    /*this is for the test case */
    public EntityNode(long id, double reputation, Map<String, String>attributes){
        this.ID = id;
        this.reputation = reputation;
        f = null;
        p = null;
        n = null;
        this.attributes = attributes;
        signature = attributes.get("name");
    }

    EntityNode(long id, double reputation, String signature){
        this.ID = id;
        this.reputation = reputation;
        this.signature = signature;
        f = null;
        n = null;
        p = null;
    }



    long getID(){return ID;}

    FileEntity getF() {
        return f;
    }

    NetworkEntity getN() {
        return n;
    }

    Process getP() {
        return p;
    }

    String getSignature() {
        return signature;
    }

    void setReputation(double r){
        reputation = r;
    }

    double getReputation(){
        return reputation;
    }

    public boolean isFileNode(){
        return f != null;
    }

    public boolean isNetworkNode(){
        return n != null;
    }

    public boolean isProcessNode() { return p != null; }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityNode)) return false;

        EntityNode that = (EntityNode) o;

        if (ID != that.ID) return false;
        if (f != null ? !f.equals(that.f) : that.f != null) return false;
        if (n != null ? !n.equals(that.n) : that.n != null) return false;
        if (p != null ? !p.equals(that.p) : that.p != null) return false;
        return signature.equals(that.signature);
    }

    @Override
    public int hashCode() {
        int result = (int) (ID ^ (ID >>> 32));
        result = 31 * result + (f != null ? f.hashCode() : 0);
        result = 31 * result + (n != null ? n.hashCode() : 0);
        result = 31 * result + (p != null ? p.hashCode() : 0);
        result = 31 * result + signature.hashCode();
        return result;
    }
    @Override
    public String toString(){
        return this.getID()+" "+this.getSignature();
    }
}
