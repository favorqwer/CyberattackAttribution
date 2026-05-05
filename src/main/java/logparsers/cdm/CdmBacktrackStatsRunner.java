package logparsers.cdm;

import org.jgrapht.graph.DirectedPseudograph;
import pagerank.algorithm.BackTrack;
import pagerank.algorithm.IterateGraph;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CdmBacktrackStatsRunner {
    private CdmBacktrackStatsRunner() {
    }

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            positional.add(arg);
        }
        if (positional.size() < 2 || positional.size() > 3) {
            System.err.println("Usage: CdmBacktrackStatsRunner <manifest.cdm> <poi-signature> [output-prefix]");
            System.exit(1);
        }

        Path manifest = Path.of(positional.get(0));
        String poi = positional.get(1);
        Path outputPrefix = positional.size() == 3 ? Path.of(positional.get(2)) : null;

        long started = System.currentTimeMillis();
        DirectedPseudograph<EntityNode, EventEdge> original = CdmGraphBuilder.build(manifest);
        long buildElapsedMs = System.currentTimeMillis() - started;

        BackTrack backTrack = new BackTrack(original);
        long sliceStarted = System.currentTimeMillis();
        DirectedPseudograph<EntityNode, EventEdge> sliced = backTrack.backTrackPOIEvent(poi);
        long sliceElapsedMs = System.currentTimeMillis() - sliceStarted;

        if (outputPrefix != null) {
            Path parent = outputPrefix.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            new IterateGraph(sliced).exportGraph(outputPrefix.toString());
        }

        long totalElapsedMs = System.currentTimeMillis() - started;
        System.out.println("CDM real-data backtrack stats completed");
        System.out.println("Manifest: " + manifest.toAbsolutePath());
        System.out.println("POI: " + poi);
        System.out.println("Original vertices: " + original.vertexSet().size());
        System.out.println("Original edges: " + original.edgeSet().size());
        System.out.println("BackTrack vertices: " + sliced.vertexSet().size());
        System.out.println("BackTrack edges: " + sliced.edgeSet().size());
        System.out.println("Build seconds: " + buildElapsedMs / 1000.0);
        System.out.println("BackTrack seconds: " + sliceElapsedMs / 1000.0);
        System.out.println("Elapsed seconds: " + totalElapsedMs / 1000.0);
        if (outputPrefix != null) {
            System.out.println("DOT: " + outputPrefix.toAbsolutePath() + ".dot");
        }
    }
}
