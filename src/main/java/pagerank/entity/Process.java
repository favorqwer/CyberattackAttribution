package pagerank.entity;

/**
 * the key for differentiate processes is pid(or proc.name?) and timestamp.
 */
public class Process extends Entity {
    private String pid;
    private String name;

    public Process(){}

    public Process(double reputation, String pid, String name, long uniqID){
        super(reputation, uniqID);
        this.pid = pid;
        this.name = name;
    }

    public String getPid(){
        return pid;
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
        return name != null ? name.equals(process.name) : process.name == null;
    }

    @Override
    public int hashCode() {
        int result = pid != null ? pid.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        return result;
    }
}
