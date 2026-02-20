package pagerank;

import java.math.BigDecimal;

public class EventEdge{
    private EntityNode source;
    private EntityNode sink;
    public long id;
    public BigDecimal startTime;
    public BigDecimal endTime;
    private String type;
    private String event;
    private long size;
    public double weight;
    public double timeWeight;
    public double amountWeight;
    public double structureWeight;
    // [新增 1] 异常相关字段
    public double anomalyWeight; // 用于 LDA 计算后的权重
    public double anomalyScore;  // 原始日志中的异常分数

    EventEdge(PtoFEvent pf){
        source = new EntityNode(pf.getSource());
        sink = new EntityNode(pf.getSink());
        id = pf.getUniqID();
        startTime = new BigDecimal(pf.getStart());
        endTime = new BigDecimal(pf.getEnd());
        type = pf.getType();
        event = pf.getEvent();
        size = pf.getSize();
        anomalyScore = pf.getAnomalyScore();
    }

    EventEdge(PtoPEvent pp){
        source = new EntityNode(pp.getSource());
        sink = new EntityNode(pp.getSink());
        id = pp.getUniqID();
        startTime = new BigDecimal(pp.getStart());
        endTime = new BigDecimal(pp.getEnd());
        type = pp.getType();
        event = pp.getEvent();
        size = 0;
        anomalyScore = pp.getAnomalyScore();
    }

    EventEdge(FtoPEvent fp){
        source = new EntityNode(fp.getSource());
        sink = new EntityNode(fp.getSink());
        id = fp.getUniqID();
        startTime = new BigDecimal(fp.getStart());
        //System.out.println(fp.getEnd());
        try {
            endTime = new BigDecimal(fp.getEnd());
        }catch (Exception e){
            System.out.println(fp.getUniqID());
            System.out.println(fp.getSource());
            System.out.println(fp.getSink());
        }

        type = fp.getType();
        event = fp.getEvent();
        size = fp.getSize();
        anomalyScore = fp.getAnomalyScore();
    }

    EventEdge(NtoPEvent np){
        source = new EntityNode(np.getSource());
        sink = new EntityNode(np.getSink());
        id = np.getUniqID();
        startTime = new BigDecimal(np.getStart());
        try {
            endTime = new BigDecimal(np.getEnd());
        }catch (Exception e){
            System.out.println(np.getSource());
            System.out.println(np.getSink());
            System.out.println(np.getUniqID());
        }
        type = np.getType();
        event = np.getEvent();
        size = np.getSize();
        anomalyScore = np.getAnomalyScore();
    }

    EventEdge(PtoNEvent pn){
        source = new EntityNode(pn.getSource());
        sink = new EntityNode(pn.getSink());
        id = pn.getUniqID();
        startTime = new BigDecimal(pn.getStart());
        try {
            endTime = new BigDecimal(pn.getEnd());
        }catch (Exception e){
            System.out.println(pn.getUniqID());
            System.out.println(pn.getSource());
            System.out.println(pn.getEvent());
            System.out.println(pn.getSink());
        }
        type = pn.getType();
        event = pn.getEvent();
        size = pn.getSize();
        anomalyScore = pn.getAnomalyScore();
    }

    public EventEdge(EventEdge edge){
        this.source = edge.getSource();
        this.sink = edge.getSink();
        this.id = edge.getID();
        this.startTime = edge.getStartTime();
        this.endTime = edge.getEndTime();
        this.type = edge.getType();
        this.size = edge.getSize();
        this.weight = edge.weight;
        // [新增] 复制异常字段
        this.anomalyScore = edge.anomalyScore;
        this.anomalyWeight = edge.anomalyWeight;
    }

    public EventEdge(String type, BigDecimal starttime, BigDecimal endtime, long amount, EntityNode from, EntityNode to, long id){
        source = from;
        sink = to;
        this.type = type;
        this.size = amount;
        this.startTime = starttime;
        this.endTime = endtime;
        this.id = id;
        // 默认构造，异常分数为0
        this.anomalyScore = 0.0;
    }
    @Deprecated
    public EventEdge(EventEdge edge, long id){                   //for split edge
        this.source = edge.getSource();
        this.sink = edge.getSink();
        this.id = id;
        this.startTime = edge.getStartTime();
        this.endTime = edge.getEndTime();
        this.type = edge.getType();
        this.size = edge.getSize();
        // [新增]
        this.anomalyScore = edge.anomalyScore;
    }

    public EventEdge(EventEdge edge, EntityNode from, EntityNode to, long id){
        this.source = from;
        this.sink = to;
        this.id = id;
        this.startTime = edge.getStartTime();
        this.endTime = edge.getEndTime();
        this.type = edge.getType();
        this.size = edge.getSize();
        this.event = edge.getEvent();
        // [新增]
        this.anomalyScore = edge.anomalyScore;
    }

    public EventEdge merge(EventEdge e2){
        this.endTime = e2.endTime;
        this.size += e2.size;
        // 当多条日志合并为一条边时，我们认为只要其中包含高异常行为，该边就具备高异常性
        this.anomalyScore = Math.max(this.anomalyScore, e2.anomalyScore);
        return this;
    }

    public void printInfo(){
        System.out.println("id: "+this.id+" Source:"+this.source.getSignature()+" Target:"+this.getSink().getSignature()+" End time:"+
                this.endTime.toString()+" Size:"+ this.size);
    }

    public void setEdgeEvent(String event){
        this.event = event;
    }

    public long getID(){return id;}

    public void setId(long id){
        this.id = id;
    }

    public EntityNode getSource(){return source;}

    public EntityNode getSink(){ return sink;}

    BigDecimal getStartTime(){
        return startTime;
    }

    public BigDecimal getEndTime(){
        return endTime;
    }

    BigDecimal[] getInterval(){
        BigDecimal[] res = {startTime,endTime};
        return res;
    }

    String getType(){
        return type;
    }

    public String getEvent(){return event;}

    public boolean eventIsNull(){
        return event == null;
    }

    public long getSize(){return size;}

    public BigDecimal getDuration(){
        return endTime.subtract(startTime);
    }

    // [新增] Getter 方法，供 BackwardPropagate_pf 调用
    public double getAnomalyScore() {
        return anomalyScore;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventEdge)) return false;

        EventEdge eventEdge = (EventEdge) o;

        if (id != eventEdge.id) return false;
        if (!source.equals(eventEdge.source)) return false;
        if (!sink.equals(eventEdge.sink)) return false;
        if (!startTime.equals(eventEdge.startTime)) return false;
        return endTime.equals(eventEdge.endTime);
    }

    @Override
    public int hashCode() {
        int result = source.hashCode();
        result = 31 * result + sink.hashCode();
//        result = 31 * result + (int) (id ^ (id >>> 32));
        result = 31 * result + startTime.hashCode();
//        result = 31 * result + endTime.hashCode();
        return result;
    }

//    public String printInfo(){
//        return this.source.getSignature() + " to "+ this.sink.getSignature()+" "+this.getSize();
//    }


    @Override
    public String toString() {
        return "EventEdge{" +
                "source=" + source.getSignature() +
                ", sink=" + sink.getSignature() +
                ", id=" + id +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", type='" + type + '\'' +
                ", event='" + event + '\'' +
                ", size=" + size +
                ", weight=" + weight +
                ", timeWeight=" + timeWeight +
                ", amountWeight=" + amountWeight +
                ", structureWeight=" + structureWeight +
                ", anomalyWeight=" + anomalyWeight +
                ", anomalyScore=" + anomalyScore +   // [新增]
                '}';
    }
}
