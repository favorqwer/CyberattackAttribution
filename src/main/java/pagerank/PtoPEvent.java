package pagerank;


public class PtoPEvent extends Event {
    public static final String TYPE = "PtoP";

    private Process source;
    private Process sink;
    private String event;
    // [新增 1]
    private double anomalyScore;

    public PtoPEvent() {}

    // [修改 1]
    public PtoPEvent(String startS, String startMs ,Process source,Process sink, String event,long id, double anomalyScore){
        super(TYPE,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        // [新增 2]
        this.anomalyScore = anomalyScore;
    }

    // [修改 2]
    public PtoPEvent(String type,String startS, String startMs ,Process source,Process sink, String event,long id, double anomalyScore){
        super(type,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        this.anomalyScore = anomalyScore;
    }

    public Process getSource(){
        return source;
    }

    public Process getSink(){
        return sink;
    }

    public String getEvent(){
        return event;
    }

    public void setSink(Process sink){
        this.sink = sink;
    }

    public void setSource(Process source) { this.source = source; }
    // [新增 3]
    public double getAnomalyScore() { return anomalyScore; }

}
