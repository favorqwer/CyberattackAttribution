package pagerank;

public class NtoPEvent extends Event {
    public static final String TYPE = "NtoP";

    private NetworkEntity source;
    private Process sink;
    private long size;
    private String event;
    // [新增 1]
    private double anomalyScore;

    public NtoPEvent() {}

    // [修改 1]
    //todo：是否要对应修改
    public NtoPEvent(String startS, String startMs,NetworkEntity source,Process sink,String event,long size,long id, double anomalyScore){
        super(TYPE,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.size = size;
        this.event = event;
        // [新增 2]
        this.anomalyScore = anomalyScore;
    }

    // [修改 2]
    //todo：是否要对应修改
    public NtoPEvent(String type, String startS, String startMs,NetworkEntity source,Process sink,String event,long id, double anomalyScore){
        super(type,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.size = 0;
        this.event = event;
        this.anomalyScore = anomalyScore;
    }

    // [修改 3] 从 PtoNEvent 转换时的构造函数
    public NtoPEvent(PtoNEvent a){
        super(TYPE,a.getStart().split("\\.")[0],
                a.getStart().split("\\.")[1],a.getUniqID());
        this.source = a.getSink();
        this.sink = a.getSource();
        this.size = a.getSize();
        this.event = a.getEvent();
        // [新增] 传递分数
        this.anomalyScore = a.getAnomalyScore();
    }

    public NetworkEntity getSource() {
        return source;
    }

    public Process getSink() {
        return sink;
    }

    public long getSize() {
        return size;
    }

    public String getEvent() {
        return event;
    }

    public void updateSize(long i){
        size +=i;
    }

    public void setSize(long size){
        this.size = size;
    }
    // [新增 4]
    public double getAnomalyScore() { return anomalyScore; }

    @Override
    public String toString() {
        return "NtoPEvent{" +
                "source=" + source +
                ", sink=" + sink +
                ", size=" + size +
                ", event='" + event + '\'' +
                ", anomalyScore=" + anomalyScore +
                '}';
    }
}
