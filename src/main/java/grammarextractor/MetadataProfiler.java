package grammarextractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Profiles the per-phase cost of {@link RuleMetadata#computeAllProfiled}. We measure on the
 * post-initialization grammar (largest rule count, closest to what dominates a real run) and
 * then on a grammar after a few recompression passes to see how the profile shifts as the
 * grammar shrinks.
 */
public class MetadataProfiler {

    public static void main(String[] args) throws IOException {
        String inputPath = args.length > 0 ? args[0] : "einstein.en.txt";
        int warmups      = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int runs         = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        Path file = Path.of(inputPath);
        System.out.println("=== RuleMetadata.computeAll phase profile ===");
        System.out.println("Input:        " + inputPath);
        System.out.println("Warmup runs:  " + warmups);
        System.out.println("Measured:     " + runs);
        System.out.println();

        Parser.ParsedGrammar initGrammar = buildInitGrammar(file);
        Set<Integer> artTerm = Collections.emptySet();

        System.out.println("── (A) computeAll → Map  (with phase 6) ──");
        profileMap(initGrammar, artTerm, warmups, runs);

        System.out.println("── (B) computeAllView → no Map, no pool ──");
        profileView(initGrammar, artTerm, warmups, runs, null);

        for (int threads : new int[]{2, 6, 10}) {
            Recompressor.shutdownParallelPool();
            java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(threads);
            System.out.println("── (C) computeAllView → no Map, pool=" + threads + " threads ──");
            profileView(initGrammar, artTerm, warmups, runs, pool);
            pool.shutdown();
        }
    }

    private static void profileMap(Parser.ParsedGrammar grammar, java.util.Set<Integer> artTerm,
                                   int warmups, int runs) {
        for (int w = 0; w < warmups; w++) RuleMetadata.computeAll(grammar, artTerm);
        long totalNs = 0;
        for (int r = 0; r < runs; r++) {
            System.out.printf("Run %d:%n", r + 1);
            long t0 = System.nanoTime();
            RuleMetadata.computeAllProfiled(grammar, artTerm, System.out::println);
            long t1 = System.nanoTime();
            totalNs += (t1 - t0);
            System.out.println();
        }
        System.out.printf("AVG total: %.2f ms over %d runs%n%n", totalNs / (double) runs / 1e6, runs);
    }

    private static void profileView(Parser.ParsedGrammar grammar, java.util.Set<Integer> artTerm,
                                    int warmups, int runs,
                                    java.util.concurrent.ForkJoinPool pool) {
        for (int w = 0; w < warmups; w++) RuleMetadata.computeAllViewProfiled(grammar, artTerm, s -> {}, pool);
        long totalNs = 0;
        for (int r = 0; r < runs; r++) {
            System.out.printf("Run %d:%n", r + 1);
            long t0 = System.nanoTime();
            RuleMetadata.computeAllViewProfiled(grammar, artTerm, System.out::println, pool);
            long t1 = System.nanoTime();
            totalNs += (t1 - t0);
            System.out.println();
        }
        System.out.printf("AVG total: %.2f ms over %d runs%n%n", totalNs / (double) runs / 1e6, runs);
    }

    private static Parser.ParsedGrammar buildInitGrammar(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<Integer> sequence = new ArrayList<>(bytes.length);
        for (byte b : bytes) sequence.add(b & 0xFF);
        Parser.ParsedGrammar raw = new Parser.ParsedGrammar(new HashMap<>(), sequence, Collections.emptyMap());
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(raw);
        // Initialized grammar has empty metadata — that's what computeAll will fill in.
        Map<Integer, List<Integer>> rules = new HashMap<>(init.grammar().grammarRules());
        List<Integer> seq = new ArrayList<>(init.grammar().sequence());
        return new Parser.ParsedGrammar(rules, seq, Collections.emptyMap());
    }
}
