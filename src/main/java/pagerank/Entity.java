package pagerank;

import java.io.Serializable;

/**
 * Created by fang on 5/15/17.
 * the design of timestap: timestap1 seconds from epoch  timestap2: microseconds... because the joda time accuracy
 * isn't enough and the format of output of sysdig file!
 */

public class Entity implements Serializable {
    private double reputation;                            
    private long id;                                     //event number
    private int hopCount;
    private long timestamp1;
    private long timestamp2;
    private long uniqID;

    public Entity(){}

    public Entity(long id,int hopCount,long uniqID){
        this.id = id;
        this.hopCount = hopCount;
        reputation = 0.0;
        this.uniqID =uniqID;
    }

    public Entity(double reputation,long id,int hopCount,String time1,String time2,long uniqID){
        this.reputation = reputation;
        this.id = id;
        this.hopCount = hopCount;
        timestamp1 = Long.valueOf(time1);                    //seconds
        timestamp2 = Long.valueOf(time2);
        this.uniqID = uniqID;
    }

    public double getReputation(){
        return reputation;
    }

    public long getID(){
        return id;
    }

    public int getHopCount(){
        return hopCount;
    }

    public void setReputation(double r){
        reputation = r;
    }

    public void setHopCount(int h){
        hopCount = h;
    }

    public void setId(int i){
        id = i;
    }

    public String getTimeStamp(){
        String s;
        s = String.valueOf(timestamp1)+"."+String.valueOf(timestamp2);
        return s;

    }

    public long getUniqID(){
        return uniqID;
    }
    /*test*/
    public static void main(String[] args){
        Entity test = new Entity(2.0,0,15,"1152654","493685052",5);
        System.out.println("HopCount: "+test.getHopCount());
        System.out.println("id: "+test.getID());
        System.out.println("repuattion: "+test.getReputation());
        System.out.println("timestap: "+ test.getTimeStamp());
    }
}

