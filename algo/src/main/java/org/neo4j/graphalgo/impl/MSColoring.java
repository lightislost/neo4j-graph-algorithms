package org.neo4j.graphalgo.impl;

import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.IntIntScatterMap;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.ParallelUtil;
import org.neo4j.graphalgo.impl.msbfs.BfsSources;
import org.neo4j.graphalgo.impl.msbfs.MultiSourceBFS;
import org.neo4j.graphdb.Direction;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Multi Source Coloring is a parallel connected Components algorithm
 *
 * @author mknblch
 */
public class MSColoring {

    private final Graph graph;

    private final ExecutorService executorService;

    private final AtomicIntegerArray colors;

    private final int concurrency;

    public MSColoring(Graph graph, ExecutorService executorService, int concurrency) {
        this.graph = graph;
        this.executorService = executorService;
        colors = new AtomicIntegerArray(graph.nodeCount());
        this.concurrency = concurrency;
    }

    public AtomicIntegerArray getColors() {
        return colors;
    }

    public MSColoring compute() {
        // reset state so that each node has its own id as color
        reset();
        // start bfs from all sources (direction does not matter)
        new MultiSourceBFS(graph, graph, Direction.OUTGOING, this::nodeAction)
                .run(concurrency, executorService);
        return this;
    }

    public int getSetCount() {
        final IntIntMap map = new IntIntScatterMap();
        for (int i = graph.nodeCount() - 1; i >= 0; i--) {
            int color = colors.get(i);
            map.addTo(color, 1);
        }
        return map.size();
    }

    public Stream<Result> resultStream() {
        return IntStream.range(0, graph.nodeCount())
                .mapToObj(i -> new Result(graph.toOriginalNodeId(i), colors.get(i)));
    }

    private void reset() {
        ParallelUtil.iterateParallel(executorService,
                graph.nodeCount(),
                Runtime.getRuntime().availableProcessors() * 2, // TODO constant/getter anywhere!?
                offset -> colors.set(offset, offset));
    }

    private void nodeAction(int node, int depth, BfsSources bfsSources) {
        // evaluate highest color
        int bestColor = this.colors.get(node);
        while (bfsSources.hasNext()) {
            final int sourceColor = colors.get(bfsSources.next());
            bestColor = Math.max(bestColor, sourceColor);
        }
        // set color to target node
        setColor(node, bestColor);
        // reset iterator
        bfsSources.reset();
        // set highest color to all sources
        while (bfsSources.hasNext()) {
            final int source = bfsSources.next();
            setColor(source, bestColor);
        }
    }

    private void setColor(int node, int color) {
        /* loop until either current is higher or equal
           to color or color was successfully saved */
        int current;
        do {
            current = colors.get(node);
        } while (color >= current && !colors.compareAndSet(node, current, color));
    }

    public static class Result {

        public final long nodeId;

        public final long color;

        public Result(long nodeId, int color) {
            this.nodeId = nodeId;
            this.color = color;
        }
    }
}
