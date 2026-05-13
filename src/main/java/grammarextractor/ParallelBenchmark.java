package grammarextractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class ParallelBenchmark {

    private record Config(String name, boolean parallel, int threads) {}

    // Build a ParsedGrammar from raw bytes (one symbol per byte). Pair with init=true so
    // initializeWithSentinelsAndRootRule turns this into the standard binary-rule chain.
    private static Parser.ParsedGrammar rawTextToGrammar(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<Integer> sequence = new ArrayList<>(bytes.length);
        for (byte b : bytes) sequence.add(b & 0xFF);
        return new Parser.ParsedGrammar(new HashMap<>(), sequence, Collections.emptyMap());
    }

    public static void main(String[] args) throws Exception {
        String inputPath = args.length > 0 ? args[0] : "einstein.en.txt";
        int passes      = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int warmups     = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int runs        = args.length > 3 ? Integer.parseInt(args[3]) : 2;

        Path file = Path.of(inputPath);

        System.out.println("=== Parallel vs Serial Benchmark ===");
        System.out.println("Input:           " + inputPath);
        System.out.println("Passes per run:  " + passes);
        System.out.println("Warmup runs:     " + warmups);
        System.out.println("Measured runs:   " + runs);
        System.out.println("Cores available: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        Config[] configs = {
                new Config("serial",      false, 0),
                new Config("parallel-2",   true, 2),
                new Config("parallel-6",   true, 6),
                new Config("parallel-10",  true, 10),
        };

        double[] avgMsByConfig = new double[configs.length];

        for (int ci = 0; ci < configs.length; ci++) {
            Config c = configs[ci];
            Recompressor.shutdownParallelPool();

            for (int w = 0; w < warmups; w++) {
                Parser.ParsedGrammar g = rawTextToGrammar(file);
                Recompressor.recompressNTimes(
                        g, passes, 0, true, false,
                        "bench_" + c.name + "_warm.txt",
                        c.parallel, c.threads);
            }

            long totalNs = 0;
            for (int r = 0; r < runs; r++) {
                System.gc();
                Parser.ParsedGrammar g = rawTextToGrammar(file);
                long t0 = System.nanoTime();
                Recompressor.recompressNTimes(
                        g, passes, 0, true, false,
                        "bench_" + c.name + ".txt",
                        c.parallel, c.threads);
                long t1 = System.nanoTime();
                long ns = t1 - t0;
                totalNs += ns;
                System.out.printf("  [%s] run %d: %.2f ms%n", c.name, r + 1, ns / 1e6);
            }
            double avgMs = totalNs / (double) runs / 1e6;
            avgMsByConfig[ci] = avgMs;
            System.out.printf("[%s] AVG: %.2f ms%n%n", c.name, avgMs);
        }

        Recompressor.shutdownParallelPool();

        System.out.println("=== Summary ===");
        double serialMs = avgMsByConfig[0];
        for (int i = 0; i < configs.length; i++) {
            double avg = avgMsByConfig[i];
            double speedup = serialMs / avg;
            System.out.printf("%-12s  %10.2f ms   speedup %.2fx%n",
                    configs[i].name, avg, speedup);
        }
    }
}
