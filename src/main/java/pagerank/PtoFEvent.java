package pagerank;

public class PtoFEvent extends Event {
    public static final String TYPE = "PtoF";

    private Process source;
    private FileEntity sink;
    private String event;
    private long size;

    // [新增 1] 异常分数字段
    private double anomalyScore;

    public PtoFEvent() {}
    // [修改 1] 构造函数增加 anomalyScore 参数
    public PtoFEvent(String startS, String startMs,Process source, FileEntity sink,
                     String event, long amount,long id, double anomalyScore){
        super(TYPE,startS, startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        this.size = amount;
        // [新增 2] 赋值
        this.anomalyScore = anomalyScore;
    }


    // [修改 2] 另一个构造函数也增加参数 (如果有用到)
    public PtoFEvent(String type,String startS, String startMs,Process source, FileEntity sink,
                     String event, long amount,long id, double anomalyScore){
        super(type,startS, startMs,id);
        this.source = source;
        this.sink = sink;
        this.event = event;
        this.size = amount;
        this.anomalyScore = anomalyScore;
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

    // [新增 3] Getter 方法
    public double getAnomalyScore() {
        return anomalyScore;
    }

}
