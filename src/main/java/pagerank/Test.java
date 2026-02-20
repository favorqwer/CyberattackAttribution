package pagerank;
import java.util.*;

/**
 * Created by fang on 3/23/18.
 */
public class Test {

    List<List<String>> curlCaseEdgeSignatures; // critical edges for the curl case; format <source, sink, event>

    Test() {
        // Critical edges for the curl case
        // Note that process signature contains pid
        curlCaseEdgeSignatures = new ArrayList<>();
        curlCaseEdgeSignatures.add(Arrays.asList(new String[] {"192.168.29.234:40402->208.118.235.20:80", "curl", "recvfrom"}));
        curlCaseEdgeSignatures.add(Arrays.asList(new String[] {"curl", "/home/lcl/target.tar.bz2", "write"}));
        curlCaseEdgeSignatures.add(Arrays.asList(new String[] {"/home/lcl/target.tar.bz2", "cp", "read"}));
        curlCaseEdgeSignatures.add(Arrays.asList(new String[] {"cp", "/home/lcl/target", "write"}));
    }

    public List<EventEdge> findCriticalEdgesForCase(List<List<String>> edgeSignatures, List<EventEdge> allEdges) {
        // Find the critical edges that are related to the case from all edges by checking the signatures

        List<EventEdge> criticalEdges = new ArrayList<>();
        for (List<String> currEdgeSignature: edgeSignatures) {
            String sourceSignature;
            String sinkSignature;
            String eventSignature;
            for (EventEdge edge: allEdges) {
                sourceSignature = edge.getSource().getSignature();
                sinkSignature = edge.getSink().getSignature();
                eventSignature = edge.getEvent();
                if (edge.getType().equals("PtoF") || edge.getType().equals("PtoN")) {
                    // source is P
                    sourceSignature = edge.getSource().getP().getName();
                }
                if (edge.getType().equals("FtoP") || edge.getType().equals("NtoP")) {
                    // sink is P
                    sinkSignature = edge.getSink().getP().getName();
                }

                if (sourceSignature.equals(currEdgeSignature.get(0)) && sinkSignature.equals(currEdgeSignature.get(1)) && eventSignature.equals(currEdgeSignature.get(2))) {
                    criticalEdges.add(edge);
                }
            }
        }
        return criticalEdges;
    }


    public static void main(String[] args){
        long  current = System.currentTimeMillis();
        System.out.println(current);
        int a = 0;
        for(int i=0; i<100000000; i++){
            a+=1;
        }
        long end = System.currentTimeMillis();
        System.out.println(end);
        double timeCost = (end - current)*1.0/1000.0;
        System.out.println("Time cost is: "+timeCost);

/*
        // Critical edges for the curl case
        // Note: this is only for testing purpose; the code is not necessary for the implementation of the method
        Test testCase = new Test();
        List<EventEdge> criticalEdges = testCase.findCriticalEdgesForCase(testCase.curlCaseEdgeSignatures, allEdges);
        List<EventEdge> nonCriticalEdges = new ArrayList<>(allEdges);
        nonCriticalEdges.removeAll(criticalEdges);
        System.out.println();
        System.out.println("Ground truth:");
        System.out.println("Critical edge");
        for (EventEdge edge: criticalEdges) {
            printEdgeWeights(edge);
        }
        System.out.println("Noncritical edge");
        for (EventEdge edge: nonCriticalEdges) {
            printEdgeWeights(edge);
        }
*/
    }
}
