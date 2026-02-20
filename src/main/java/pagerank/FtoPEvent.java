package pagerank;

public class FtoPEvent extends Event{
    public static final String TYPE = "FtoP";

    private FileEntity source;
    private Process sink;
    private long size;
    private String event;
    // [新增 1]
    private double anomalyScore;

    public FtoPEvent() {}
    // [修改 1]
    public FtoPEvent(String startS, String startMs,
                     FileEntity source,Process sink,String event,long size,long id, double anomalyScore){
        super(TYPE,startS,startMs, id);
        this.source = source;
        this.sink = sink;
        this.size = size;
        this.event = event;
        // [新增 2]
        this.anomalyScore = anomalyScore;
    }

    // [修改 2]
    public FtoPEvent(String type, String startS, String startMs,
                     FileEntity source,Process sink,String event,long id, double anomalyScore){
        super(type,startS,startMs, id);
        this.source = source;
        this.sink = sink;
        this.size = 0;
        this.event = event;
        this.anomalyScore = anomalyScore;
    }

    public FileEntity getSource(){
        return source;
    }

    public Process getSink(){
        return sink;
    }

    public String getEvent(){
        return event;
    }

    public void updateSize(long i){
        size +=i;
    }

    public long getSize(){
        return size;
    }

    // [新增 3]
    public double getAnomalyScore() {
        return anomalyScore;
    }


}
