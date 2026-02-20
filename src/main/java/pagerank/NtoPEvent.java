package pagerank;

/**
 * Created by fang on 6/28/17.
 */
public class NtoPEvent extends Event {
    public static final String TYPE = "NtoP";

    private NetworkEntity source;
    private Process sink;
    private long size;
    private String event;

    public NtoPEvent() {}

    public NtoPEvent(String startS, String startMs,NetworkEntity source,Process sink,String event,long size,long id){
        super(TYPE,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.size = size;
        this.event = event;
    }

    public NtoPEvent(String type, String startS, String startMs,NetworkEntity source,Process sink,String event,long id){
        super(type,startS,startMs,id);
        this.source = source;
        this.sink = sink;
        this.size = 0;
        this.event = event;
    }

    public NtoPEvent(PtoNEvent a){
        super(TYPE,a.getStart().split("\\.")[0],
                a.getStart().split("\\.")[1],a.getUniqID());
        this.source = a.getSink();
        this.sink = a.getSource();
        this.size = a.getSize();
        this.event = a.getEvent();
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

    @Override
    public String toString() {
        return "NtoPEvent{" +
                "source=" + source +
                ", sink=" + sink +
                ", size=" + size +
                ", event='" + event + '\'' +
                '}';
    }
}
