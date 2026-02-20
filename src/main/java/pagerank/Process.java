package pagerank;

/**
 * Created by fang on 6/12/17.
 * the key for differentiate processes is pid(or proc.name?) and timestamp.
 */
public class Process extends Entity {
    private String pid;
    private String uid;
    private String groupID;
    private String location;
    private String name;

    public Process(){}

    public Process(double reputation,long id,int hopCount,String pid, String uid, String groupID, String location,
                   String time1, String stime,String name,long uniqID){
        super(reputation,id,hopCount,time1,stime,uniqID);
        this.pid = pid;
        this.uid = uid;                                //can't get now
        this.groupID = groupID;                        //can't get now
        this.location = location;
        this.name = name;

    }

    public Process(long id,int hopCount,String pid, String uid, String location,long uniqID){
        super(id,hopCount,uniqID);
        if(pid.startsWith("=")){
            pid = pid.substring(1);
        }
        this.pid = pid;
        this.uid = uid;
        this.location = location;
        groupID = null;
    }

    public String getPid(){
        return pid;
    }

    public String getUid(){
        return uid;
    }

    public String getGroupID(){
        return groupID;
    }

    public String getLocation(){
        return location;
    }

    public String getName(){return name;}

    public String getPidAndName(){
        return pid+name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Process)) return false;

        Process process = (Process) o;

        if (pid != null ? !pid.equals(process.pid) : process.pid != null) return false;
        if (uid != null ? !uid.equals(process.uid) : process.uid != null) return false;
        return name != null ? name.equals(process.name) : process.name == null;
    }

    @Override
    public int hashCode() {
        int result = pid != null ? pid.hashCode() : 0;
        result = 31 * result + (uid != null ? uid.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        return result;
    }

    /* test */
    public static void main(String[] args){
        String uid = "fang1";
        String groupId = "fang2";
        String location = "fang3";
        long id = Long.parseLong("1234");
        Process test = new Process(2.0,0,0,"1123",uid,groupId,location,"1123","234","java",12);

        //Process test2 = new Process(14,3,5341,uid,location);
        System.out.println(test.getPidAndName());
        //System.out.println(test2.getUid());
        //System.out.println(test.getGroupID());
        //System.out.println(test2.getLocation());
    }
}