package pagerank.entity;

/**
 * EntityNode - 图节点类（实体节点）
 * 
 * 本类代表依赖图中的节点，对应系统中的一个实体。
 * 实体有三种类型：
 * 1. Process（进程）：系统进程，用Process对象表示
 * 2. File（文件）：文件路径，用FileEntity对象表示
 * 3. Network（网络）：网络连接，用NetworkEntity对象表示
 * 
 * 节点属性：
 * - ID: 实体唯一标识符
 * - signature: 实体签名（用于显示和查找）
 * - reputation: 恶意度分数（0.0-1.0，1.0表示可信，0.0表示可疑）
 * 
 * @author fang
 * @date 2018/3/21
 */
public class EntityNode{
    //Entity m;
    // 实体唯一标识符
    private long ID;
    // 文件实体（如果此节点是文件类型）
    private FileEntity f;
    // 网络实体（如果此节点是网络类型）
    private NetworkEntity n;
    // 进程实体（如果此节点是进程类型）
    private Process p;
    // 实体签名（用于显示和查找）
    private String signature;
    // 恶意度分数：0.0（可疑）~ 1.0（可信）
    public double reputation;

    /**
     * 构造函数 - 从文件实体创建节点
     * 
     * @param f 文件实体
     */
    public EntityNode(FileEntity f){
        this.f = f;
        this.ID = f.getUniqID();
        this.n = null;
        this.p = null;
        signature = f.getPath();
        reputation = f.getReputation();
    }

    /**
     * 构造函数 - 从进程实体创建节点
     * 
     * @param p 进程实体
     */
    public EntityNode(Process p){
        this.p = p;
        f = null;
        n = null;
        this.ID = p.getUniqID();
        signature = p.getPidAndName();
        reputation = p.getReputation();
    }

    /**
     * 构造函数 - 从网络实体创建节点
     * 
     * @param n 网络实体
     */
    public EntityNode(NetworkEntity n){
        this.n = n;
        f = null;
        p = null;
        this.ID  = n.getUniqID();
        signature = n.getSrcAndDstIP();
        reputation = n.getReputation();
    }

    /**
     * 拷贝构造函数
     * 
     * @param e 要拷贝的节点
     */
    public EntityNode(EntityNode e) {
        this.f = e.getF();
        this.n = e.getN();
        this.p = e.getP();
        this.ID = e.getID();
        this.signature = e.getSignature();
        this.reputation = e.reputation;

    }

    public EntityNode(EntityNode old, long id){
        this.f = old.getF();
        this.n = old.getN();
        this.p = old.getP();
        this.ID = id;
        this.signature = old.getSignature();
        this.reputation = old.reputation;
    }

    /*this is for the test case */
    public EntityNode(long id, double reputation, String signature){
        this.ID = id;
        this.reputation = reputation;
        this.signature = signature;
        f = null;
        n = null;
        p = null;
    }



    public long getID(){return ID;}

    public FileEntity getF() {
        return f;
    }

    public NetworkEntity getN() {
        return n;
    }

    public Process getP() {
        return p;
    }

    public String getSignature() {
        return signature;
    }

    public void setReputation(double r){
        reputation = r;
    }

    public double getReputation(){
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
