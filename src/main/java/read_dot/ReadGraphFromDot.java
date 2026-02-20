package read_dot;

import org.jgrapht.ext.DOTExporter;
import org.jgrapht.ext.DOTImporter;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class ReadGraphFromDot {
    DirectedPseudograph<SimpleNode, SimpleEdge> graph;

    public DirectedPseudograph<SimpleNode, SimpleEdge> readGraph(String file){

        DOTImporter<SimpleNode, SimpleEdge> importer = new DOTImporter<SimpleNode, SimpleEdge>(new DotEntityProvider(), new DotEdgeProvider());
        graph = new DirectedPseudograph<SimpleNode, SimpleEdge>(SimpleEdge.class);
        try {
            importer.importGraph(graph, new File(file));
        }catch (Exception e){
            e.printStackTrace();
        }
        return graph;
    }

    public void exportGraph(String file){
        DOTExporter<SimpleNode,SimpleEdge> exporter = new DOTExporter<SimpleNode, SimpleEdge>(new NodeIdProvider(),new NodeNameProvider(), new ExportEdgeProvider(),new ExportNodeAttributeProvider(),null);
        try {
            exporter.exportGraph(graph, new FileWriter(String.format("%s.dot", file)));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void filter(String file){
        Runtime rt = Runtime.getRuntime();
        File f = new File(file);
        File filter_dir = new File(f.getParent()+"/filter");
        if(!filter_dir.exists())
            filter_dir.mkdir();
        PrintWriter pw = null;
        try{
            pw = new PrintWriter(filter_dir+"/stats");
            pw.println(String.format("0- v:%d e:%d",graph.vertexSet().size(),graph.edgeSet().size()));
            for(int i=1; i<=99; i += 1){
                List<SimpleEdge> edgeList = new ArrayList<>(graph.edgeSet());
                List<SimpleNode> nodeList = new ArrayList<>(graph.vertexSet());
                for(SimpleEdge e : edgeList){
                    if(e.weight<i*0.01){
                        graph.removeEdge(e);
                    }
                }

                for(SimpleNode n : nodeList){
                    if(graph.incomingEdgesOf(n).isEmpty()&&graph.outgoingEdgesOf(n).isEmpty()){
                        graph.removeVertex(n);
                    }
                }

                pw.println(String.format("%d- v:%d e:%d",i,graph.vertexSet().size(),graph.edgeSet().size()));

                if(graph.edgeSet().size()<100){
                    String filtered = filter_dir.getAbsolutePath()+"/filter_"+i;
                    exportGraph(filtered);
                    String[] cmd = new String[] {"/bin/sh","-c","dot -T svg "+filtered+".dot"
                            + " > "+filtered+".svg"};
                    try{
                        rt.exec(cmd);
                    }catch (IOException e){}
                }
            }
        }catch (IOException e){

        }finally{
            pw.close();
        }
    }

    public void outputNodes(String caseName) {
        File f = new File("/home/lcl/work/nodes");
        if(!f.exists())
            f.mkdirs();
        try(PrintWriter pw = new PrintWriter(f.getAbsolutePath()+"/"+caseName+".node")){
            for(SimpleNode n : graph.vertexSet()) {
                pw.println(String.format("%s,%f",n.signature,n.reputation));
            }

        }catch (IOException e){e.printStackTrace();}
    }

    public static void main(String[] args){
        String file = "/home/lcl/work/res_analysis/res_attacks_ml/logs-vpn-filter-step5/Weight_vpn-filter.dot";
        ReadGraphFromDot reader = new ReadGraphFromDot();
        reader.readGraph(file);
//        reader.filter(file);
        reader.outputNodes("vpn_filter_5");
//        test.exportGraph(file+"2");
//        System.out.println(original.edgeSet());
//        test.graphIterator.exportGraphAmountAndTime("original");
//        GraphSplit split = new GraphSplit(original);
//        split.splitGraph();
//        split.outputGraph("splitTest");
//        InferenceReputation testReputation = new InferenceReputation(split.inputGraph);
//        try {
//            testReputation.calculateWeights();
//            testReputation.PageRankIteration2();
//            test.graphIterator.exportGraph("SampleWeights");
//        }catch (Exception e){
//            e.printStackTrace();
//        }

//        testReputation.printReputation();

//        try {
//            testReputation.inferRuputation();
//            testReputation.printWeights();
//        }catch (Exception e){
//            e.printStackTrace();
//        }
//        IterateGraph iterateGraph = new IterateGraph(split.inputGraph);
//        iterateGraph.exportGraph("testSample");
    }

}
