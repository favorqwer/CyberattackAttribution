package pagerank.main;

import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;
import org.jgrapht.graph.DirectedPseudograph;
import pagerank.algorithm.IterateGraph;
import pagerank.algorithm.LLMGraphFilter;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;

public class LLMFilterRunner {

    public static void main(String[] args) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        initGraphvizEngineQuietly();

        if (args.length != 3) {
            System.err.println("Usage: LLMFilterRunner <snapshot directory> <output directory> <snapshot file name>");
            System.exit(-1);
        }

        String snapshotDir = args[0];
        String outputDir = args[1];
        String snapshotFileName = args[2];

        File snapshotFile = new File(snapshotDir, snapshotFileName);
        if (!snapshotFile.exists() || !snapshotFile.isFile()) {
            System.err.println("Snapshot file does not exist: " + snapshotFile.getAbsolutePath());
            System.exit(-1);
        }

        File outputFolder = new File(outputDir);
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            System.err.println("Failed to create output directory: " + outputFolder.getAbsolutePath());
            System.exit(-1);
        }

        LLMFilterSnapshotIO.SnapshotData snapshot = LLMFilterSnapshotIO.readSnapshot(snapshotFile);

        String baseName = stripCaseGraphsSuffix(getBaseName(snapshotFile.getName()));
        String llmLogPath = new File(outputFolder, "llm_interaction_" + baseName + ".log").getAbsolutePath();

        DirectedPseudograph<EntityNode, EventEdge> inputGraph = snapshot.graph;
        IterateGraph inputOut = new IterateGraph(inputGraph, snapshot.poiEvent, snapshot.entryPoints);
        String inputPath = new File(outputFolder, "llm_input_graph_" + baseName).getAbsolutePath();
        inputOut.exportGraph(inputPath);

        try {
            ProcessOneLogCMD_19.DotToSvg(inputPath + ".dot", inputPath + ".svg");
        } catch (Exception e) {
            System.err.println("Failed to render input graph SVG: " + e.getMessage());
        }

        LLMGraphFilter llmFilter = new LLMGraphFilter();
        DirectedPseudograph<EntityNode, EventEdge> filteredGraph = llmFilter.filterGraph(
                inputGraph,
                snapshot.entryPoints,
                snapshot.poiEvent,
                llmLogPath
        );

        IterateGraph filteredOut = new IterateGraph(filteredGraph, snapshot.poiEvent, snapshot.entryPoints);
        String filteredPath = new File(outputFolder, "llm_filtered_graph_" + baseName).getAbsolutePath();
        filteredOut.exportGraph(filteredPath);

        try {
            ProcessOneLogCMD_19.DotToSvg(filteredPath + ".dot", filteredPath + ".svg");
        } catch (Exception e) {
            System.err.println("Failed to render filtered graph SVG: " + e.getMessage());
        }

        System.out.println("LLMFilterRunner completed.");
        System.out.println("Snapshot: " + snapshotFile.getAbsolutePath());
        System.out.println("Output directory: " + outputFolder.getAbsolutePath());
        System.out.println("Input graph vertices/edges: " + inputGraph.vertexSet().size() + "/" + inputGraph.edgeSet().size());
        System.out.println("Filtered graph vertices/edges: " + filteredGraph.vertexSet().size() + "/" + filteredGraph.edgeSet().size());
    }

    private static String getBaseName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    private static String stripCaseGraphsSuffix(String fileName) {
        return fileName.replaceFirst("_[^_]+_graphs$", "");
    }

    private static void initGraphvizEngineQuietly() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            PrintStream nullStream = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });

            System.setOut(nullStream);
            System.setErr(nullStream);
            Graphviz.useEngine(new GraphvizV8Engine());
        } catch (Throwable t) {
            // Keep startup quiet when Graphviz initialization emits warnings.
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}