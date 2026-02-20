package pagerank;

import org.jgrapht.graph.DirectedPseudograph;

import java.util.*;


public class EigenTrust {
    private DirectedPseudograph graph;
    private Set<EntityNode> preTrust;
    private Set<EntityNode> roots;
    IterateGraph graphiterator;

    public EigenTrust(DirectedPseudograph graph){
        this.graph = graph;
        graphiterator = new IterateGraph(graph);
        preTrust = new HashSet<>();
        roots = new HashSet<>();
    }

    public EigenTrust(DirectedPseudograph graph, List<String> nodes){
        this(graph);
        setPreTrust(nodes);
    }

    private boolean setPreTrust(List<String> nodes){
        if(preTrust==null)
            return false;
        Set<String> trustLabels = new HashSet<>(nodes);
        Set<EntityNode> vertices = graph.vertexSet();
        for(EntityNode v : vertices){
            if(trustLabels.contains(v.getSignature())){
                preTrust.add(v);
                System.out.println("pre-trust: "+v.getSignature());

            }
        }
        return true;
    }

    public void initTrust(){
        assert graph!=null;
        Set<EntityNode> vertices = graph.vertexSet();
        double denom = preTrust.size() == 0 ? vertices.size() : preTrust.size();
        for(EntityNode v : vertices){
            if(graph.incomingEdgesOf(v).size()==0 || preTrust.contains(v))
                v.setReputation(1/denom);
            else
                v.setReputation(0);
            System.out.printf("initial trust of %s: %f\n",v.getSignature(),v.reputation);
        }
    }

    public void initLocalTrust(){
        assert graph!=null;
        Set<EntityNode> vertices = graph.vertexSet();
        for(EntityNode v : vertices){
            Set<EventEdge> incoming = graph.incomingEdgesOf(v);
            double total = 0;
            for(EventEdge e : incoming){
                e.weight = e.getSize();
                total += e.weight;
            }
            if(total<1e-8)
                roots.add(v);
            else{
                for(EventEdge e : incoming)
                    e.weight /= total;
            }
        }
    }

    public void EigenTrustIteration(){
        double dampingFactor = 0.85;
        assert graph!=null;
        Set<EntityNode> vertexSet = graph.vertexSet();
        double fluctuation = 1.0;
        int iterTime = 0;
        System.out.println();
        while(fluctuation >= 1e-8){
            Map<EntityNode, Double> preReputation = getReputation();
            double culmativediff = 0.0;
            iterTime++;
            for(EntityNode v: vertexSet){
                double rep = 0.0;
                if(roots.contains(v)) {
                    for (EntityNode vPre : preTrust)
                        rep += preReputation.get(vPre);
                    rep /= preTrust.size()==0?1:preTrust.size();
                }else{
                    Set<EventEdge> edges = graph.incomingEdgesOf(v);
                    for(EventEdge edge: edges){
                        EntityNode source = edge.getSource();
                        rep += preReputation.get(source)*edge.weight;
                    }
                }
                rep = rep*dampingFactor + (1-dampingFactor) * (preTrust.contains(v)?(preTrust.size()==0?0:1/preTrust.size()):0);
                culmativediff += Math.abs(rep-preReputation.get(v));
                v.setReputation(rep);
            }
            fluctuation = culmativediff;
        }
        System.out.println(String.format("After %d times iteration, the reputation of each vertex is stable", iterTime));
    }

    private Map<EntityNode, Double> getReputation(){
        Set<EntityNode> vertexSet = graph.vertexSet();
        Map<EntityNode, Double> map = new HashMap<>();
        for(EntityNode node:vertexSet){
            map.put(node, node.getReputation());
        }
        return map;
    }

    public void exportGraph(String path){
        graphiterator.exportGraph(path);
    }







}
