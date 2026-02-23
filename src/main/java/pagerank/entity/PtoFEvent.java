package pagerank.entity;

/**
 * Created by fang on 6/19/17.
 */
public class PtoFEvent extends Event {
    public static final String TYPE = "PtoF";

    private Process source;
    private FileEntity sink;
    private String event;
    private long size;

    public PtoFEvent() {}

    public PtoFEvent(String startS, String startMs,Process source, FileEntity sink,
                     String event, long amount,long id){
        super(TYPE,startS, startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        this.size = amount;
    }


    public PtoFEvent(String type,String startS, String startMs,Process source, FileEntity sink,
                     String event, long amount,long id){
        super(type,startS, startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        this.size = amount;
    }

    public void updateAmount(int i){
        size += i;
    }

    public String getEvent(){
        return event;
    }

    public Process getSource(){
        return source;
    }

    public FileEntity getSink(){
        return sink;
    }

    public long getSize(){
        return size;
    }

}
