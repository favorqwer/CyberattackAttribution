package pagerank; /**
 * Created by fang on 3/26/18.
 */
import org.jgrapht.ext.EdgeProvider;

import java.math.BigDecimal;
import java.util.*;


public class EventEdgeProvider2 implements EdgeProvider<EntityNode, EventEdge> {
    //private BigDecimal val = new BigDecimal(43333);  // for test case of professor
    private BigDecimal val = new BigDecimal(44444);
    private long edgeID = 10;
    @Override
    public EventEdge buildEdge(EntityNode from, EntityNode to,String lable, Map<String, String>attributes){
        BigDecimal starttime = new BigDecimal(attributes.get("starttime"));
        starttime = val.subtract(starttime);
        BigDecimal endtime = new BigDecimal(attributes.get("endtime"));
        endtime = val.subtract(endtime);
        long id = attributes.containsKey("id")? Long.parseLong(attributes.get("id")):++edgeID;
        //assert  starttime.compareTo(endtime)<0;
        long size = Long.parseLong(attributes.get("amount"));
        assert size >=0;
        String type = attributes.get("type");

        EventEdge edge = new EventEdge(type,starttime, endtime, size,from, to, id);
        return edge;
    }
}
