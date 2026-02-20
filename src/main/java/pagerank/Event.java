package pagerank; /**
 * Created by fang on 6/19/17.
 */
import java.io.Serializable;
import java.math.BigDecimal;
public class Event implements Serializable {
    private String type;
    private String startS;
    private String startMs;
    private String endS;
    private String endMs;
    private long ID;

    public Event(){}

    public Event(String type, String startS, String startMs,long ID){
        this.type = type;
        this.startS = startS;
        this.startMs = startMs;
        this.ID = ID;
    }

    public String getType(){
        return type;
    }

    public void setEndTime(String str){
        String[]times = str.split("\\.");
        endS = times[0];
        endMs = times[1];
    }

    public String getInterval(){
        String s = String.valueOf(startS)+"."+String.valueOf(startMs);
        String e = String.valueOf(endS)+"."+String.valueOf(endMs);
        BigDecimal start = new BigDecimal(s);
        BigDecimal end = new BigDecimal(e);
        return end.subtract(start).toString();
    }

    public String getStart(){
        return String.valueOf(startS)+"."+String.valueOf(startMs);
    }

    public String getEnd(){return String.valueOf(endS)+"."+String.valueOf(endMs);}

    public long getUniqID(){
        return ID;
    }


    public static void main(String[] args){
        long e= Long.parseLong("156985663353");
        long es = Long.parseLong("123698745");
        String s = "1569874563";
        String ms = "987456321";
        Event test = new Event("1",s,ms,5758);
        test.setEndTime("156985663353.123698745");
        String res = test.getInterval();
        BigDecimal a =new BigDecimal("156985663353.123698745");
        BigDecimal b = new BigDecimal("1569874563.987456321");
        System.out.println(res.toString());
        System.out.println("b-a = " + a.subtract(b).toString());
        System.out.println(res.equals(a.subtract(b)));

    }

    @Override
    public String toString() {
        return "Event{" +
                "type='" + type + '\'' +
                ", startS='" + startS + '\'' +
                ", startMs='" + startMs + '\'' +
                ", endS='" + endS + '\'' +
                ", endMs='" + endMs + '\'' +
                ", ID=" + ID +
                '}';
    }

    @Override
    public Event clone(){
        return new Event(type,startS,startMs,ID);
    }
}
