package pagerank;

/**
 * Created by fang on 6/13/17.
 */
public class NetworkEntity extends Entity {
    private String srcAddress;
    private String dstAddress;
    private String sPort;
    private String dPort;

    public NetworkEntity(double reputation, long id, int hopCount,String time1, String time2,String srcAddress,
                         String dstAddress,String sPort, String dPort,long uniqID){
        super(reputation,id,hopCount,time1,time2,uniqID);
        this.srcAddress = srcAddress;
        this.dstAddress = dstAddress;
        this.sPort = sPort;
        this.dPort =dPort;
    }

    public NetworkEntity() {}

    public String getSrcAddress(){
        return srcAddress;
    }

    public String getDstAddress(){
        return dstAddress;
    }

    public String getSrcAndDstIP(){ return srcAddress+":"+sPort+"->"+dstAddress+":"+dPort;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkEntity)) return false;

        NetworkEntity that = (NetworkEntity) o;

        if (srcAddress != null ? !srcAddress.equals(that.srcAddress) : that.srcAddress != null) return false;
        if (dstAddress != null ? !dstAddress.equals(that.dstAddress) : that.dstAddress != null) return false;
        if (sPort != null ? !sPort.equals(that.sPort) : that.sPort != null) return false;
        return dPort != null ? dPort.equals(that.dPort) : that.dPort == null;
    }

    @Override
    public int hashCode() {
        int result = srcAddress != null ? srcAddress.hashCode() : 0;
        result = 31 * result + (dstAddress != null ? dstAddress.hashCode() : 0);
        result = 31 * result + (sPort != null ? sPort.hashCode() : 0);
        result = 31 * result + (dPort != null ? dPort.hashCode() : 0);
        return result;
    }
}
