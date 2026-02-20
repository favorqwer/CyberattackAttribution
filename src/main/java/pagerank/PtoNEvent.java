package pagerank;

public class PtoNEvent extends Event {
    public static final String TYPE = "PtoN";

    private Process source;
    private NetworkEntity sink;
    private long size;
    private String event;
    // [新增 1]
    private double anomalyScore;

    public Process getSource() {
        return source;
    }

    public NetworkEntity getSink() {
        return sink;
    }

    public long getSize() {
        return size;
    }

    public String getEvent() {
        return event;
    }
    // [新增 2]
    public double getAnomalyScore() { return anomalyScore; }

    public PtoNEvent() {}

    // [修改 1]
    //todo：是否要对应修改
    public PtoNEvent(String startS, String startMs,Process source,NetworkEntity sink,String event,long size, long id, double anomalyScore) {
        super(TYPE, startS, startMs, id);
        this.source = source;
        this.sink = sink;
        this.size = size;
        this.event = event;
        // [新增 3]
        this.anomalyScore = anomalyScore;
    }

    // [修改 2]
    //todo：是否要对应修改
    public PtoNEvent(String type, String startS, String startMs,Process source,NetworkEntity sink,String event,long id, double anomalyScore) {
        super(type, startS, startMs, id);
        this.source = source;
        this.sink = sink;
        this.size = 0;
        this.event = event;
        this.anomalyScore = anomalyScore;
    }

    // [修改 3] 从 NtoPEvent 转换
    public PtoNEvent(NtoPEvent a){
        super(TYPE,a.getStart().split("\\.")[0],
                a.getStart().split("\\.")[1],a.getUniqID());
        this.source =a.getSink();
        this.sink = a.getSource();
        this.size = a.getSize();
        this.event = a.getEvent();
        // [新增 4] 传递分数
        this.anomalyScore = a.getAnomalyScore();
    }

    public void updateSize(long i){
        size+=i;
    }


}
