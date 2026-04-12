package pagerank.entity;

import java.io.Serializable;

/**
 * the design of timestap: timestap1 seconds from epoch  timestap2: microseconds... because the joda time accuracy
 * isn't enough and the format of output of sysdig file!
 */

public class Entity implements Serializable {
    private double reputation;
    private long uniqID;

    public Entity(){}

    public Entity(double reputation, long uniqID){
        this.reputation = reputation;
        this.uniqID = uniqID;
    }

    public double getReputation(){
        return reputation;
    }

    public void setReputation(double r){
        reputation = r;
    }

    public long getUniqID(){
        return uniqID;
    }
}

