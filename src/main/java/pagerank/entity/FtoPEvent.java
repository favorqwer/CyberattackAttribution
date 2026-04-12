package pagerank.entity;

public class FtoPEvent extends Event{
    public static final String TYPE = "FtoP";

    private FileEntity source;
    private Process sink;
    private long size;
    private String event;

    public FtoPEvent() {}

    public FtoPEvent(String startS, String startMs,
                     FileEntity source,Process sink,String event,long size){
        super(TYPE,startS,startMs);
        this.source = source;
        this.sink = sink;
        this.size = size;
        this.event = event;
    }

    public FtoPEvent(String type, String startS, String startMs,
                     FileEntity source,Process sink,String event){
        super(type,startS,startMs);
        this.source = source;
        this.sink = sink;
        this.size = 0;
        this.event = event;
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


}
