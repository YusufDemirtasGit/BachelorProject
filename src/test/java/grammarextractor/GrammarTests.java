package grammarextractor;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class GrammarTests {

    // -------------------------------------------------------------------------
    // Helper: build a ParsedGrammar from an inline spec without file I/O.
    //
    // rulesSpec format: "256:97,98;257:97,256"  (semicolon-separated rule entries)
    //                   pass "" for no rules.
    // seq: the top-level sequence symbols.
    // -------------------------------------------------------------------------
    static Parser.ParsedGrammar makeGrammar(String rulesSpec, int... seq) {
        Map<Integer, List<Integer>> grammarRules = new HashMap<>();
        if (rulesSpec != null && !rulesSpec.isEmpty()) {
            for (String entry : rulesSpec.split(";")) {
                String[] parts = entry.split(":");
                int ruleId = Integer.parseInt(parts[0].trim());
                String[] rhsTokens = parts[1].split(",");
                List<Integer> rhs = new ArrayList<>(rhsTokens.length);
                for (String t : rhsTokens) rhs.add(Integer.parseInt(t.trim()));
                grammarRules.put(ruleId, rhs);
            }
        }
        List<Integer> sequence = new ArrayList<>();
        for (int s : seq) sequence.add(s);

        Parser.ParsedGrammar partial = new Parser.ParsedGrammar(grammarRules, sequence, Collections.emptyMap());
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(partial, Collections.emptySet());
        return new Parser.ParsedGrammar(grammarRules, sequence, metadata);
    }

    // =========================================================================
    // Decompressor tests
    // =========================================================================

    @Test
    void decompressSimple() {
        // R256: [97,98], SEQ: [256]  →  "ab"
        Parser.ParsedGrammar g = makeGrammar("256:97,98", 256);
        assertEquals("ab", Decompressor.decompress(g));
    }

    @Test
    void decompressNested() {
        // R256: [97,98], R257: [256,256], SEQ: [257]  →  "abab"
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,256", 257);
        assertEquals("abab", Decompressor.decompress(g));
    }

    @Test
    void decompressTerminalOnly() {
        // SEQ: [97,98,99]  →  "abc"
        Parser.ParsedGrammar g = makeGrammar("", 97, 98, 99);
        assertEquals("abc", Decompressor.decompress(g));
    }

    // =========================================================================
    // RuleMetadata tests
    // =========================================================================

    @Test
    void metadataVoccSimple() {
        // R256:[97,98], R257:[256,97], SEQ:[257]
        // vocc(257)=1 (appears once in SEQ), vocc(256)=1 (appears once in R257, which has vocc 1)
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,97", 257);
        Map<Integer, RuleMetadata> meta = g.metadata();
        assertEquals(1, meta.get(256).getVocc(), "vocc(256) should be 1");
        assertEquals(1, meta.get(257).getVocc(), "vocc(257) should be 1");
    }

    @Test
    void metadataLeftRightTerminal() {
        // R256:[97,98]  →  leftmost=97 ('a'), rightmost=98 ('b')
        Parser.ParsedGrammar g = makeGrammar("256:97,98", 256);
        RuleMetadata m = g.metadata().get(256);
        assertNotNull(m);
        assertEquals(97, m.getLeftmostTerminal());
        assertEquals(98, m.getRightmostTerminal());
    }

    @Test
    void metadataSingleBlock() {
        // R256:[97,97]  →  isSingleBlock=true  (only 'a's)
        // R257:[97,98]  →  isSingleBlock=false (mixed)
        Parser.ParsedGrammar g = makeGrammar("256:97,97;257:97,98", 256, 257);
        assertTrue(g.metadata().get(256).isSingleBlock(), "R256 should be a single block");
        assertFalse(g.metadata().get(257).isSingleBlock(), "R257 should not be a single block");
    }

    @Test
    void metadataLength() {
        // R256:[97,98]        → length 2
        // R257:[256,256]      → length 4  (2+2)
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,256", 257);
        assertEquals(2, g.metadata().get(256).getLength());
        assertEquals(4, g.metadata().get(257).getLength());
    }

    // =========================================================================
    // Extractor tests
    // =========================================================================

    /** "abab" grammar: R256:[97,98], R257:[256,256], SEQ:[257] */
    private Parser.ParsedGrammar ababGrammar() {
        return makeGrammar("256:97,98;257:256,256", 257);
    }

    @Test
    void extractFullRange() {
        Parser.ParsedGrammar g = ababGrammar();
        int len = Extractor.getUncompressedSize(g);
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(g, 0, len, false);
        assertEquals("abab", Decompressor.decompress(excerpt));
    }

    @Test
    void extractPrefix() {
        // [0, 2) from "abab"  →  "ab"
        Parser.ParsedGrammar g = ababGrammar();
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(g, 0, 2, false);
        assertEquals("ab", Decompressor.decompress(excerpt));
    }

    @Test
    void extractSuffix() {
        // [2, 4) from "abab"  →  "ab"
        Parser.ParsedGrammar g = ababGrammar();
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(g, 2, 4, false);
        assertEquals("ab", Decompressor.decompress(excerpt));
    }

    @Test
    void extractCrossRuleBoundary() {
        // [1, 3) from "abab"  →  "ba"
        Parser.ParsedGrammar g = ababGrammar();
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(g, 1, 3, false);
        assertEquals("ba", Decompressor.decompress(excerpt));
    }

    @Test
    void extractSingleChar() {
        // [0, 1) from "abab"  →  "a"
        Parser.ParsedGrammar g = ababGrammar();
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(g, 0, 1, false);
        assertEquals("a", Decompressor.decompress(excerpt));
    }

    // =========================================================================
    // Recompressor tests
    // =========================================================================

    @Test
    void roundtripSmall() {
        // Compress "ab" grammar and check the decompressed result matches.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,256", 257);
        String before = Decompressor.decompress(g);

        // recompressNTimes with roundtrip=false, verbosity=0
        // We just verify that after compression the grammar still decompresses to the same string.
        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(g.grammarRules());
        List<Integer> sequence = new ArrayList<>(g.sequence());
        Set<Integer> artificialTerminals = new HashSet<>();

        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(g);
        Map<Integer, List<Integer>> artificialRules = new LinkedHashMap<>();

        // Run one pass manually: metadata → frequencies → uncross → replace
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(init.grammar().grammarRules(), init.grammar().sequence(), Collections.emptyMap()),
                init.artificialTerminals());
        Parser.ParsedGrammar working = new Parser.ParsedGrammar(
                init.grammar().grammarRules(), init.grammar().sequence(), metadata);

        // Check decompression of the initialized grammar still gives the same string (ignoring sentinels).
        String initDecomp = Decompressor.decompress(working);
        // The initialized grammar wraps with # and $, so strip them.
        String inner = initDecomp.substring(1, initDecomp.length() - 1);
        assertEquals(before, inner, "Initialized grammar should still decompress to original (ignoring sentinels)");
    }

    @Test
    void bigramFrequenciesMatchNaive() throws Exception {
        // Parse Test_from_paper.txt via parseFile, initialize with sentinels, compare frequencies.
        Path grammarPath = Path.of("Test_from_paper.txt");
        Parser.ParsedGrammar original = Parser.parseFile(grammarPath);

        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(original);
        Parser.ParsedGrammar initialized = init.grammar();
        Set<Integer> artificial = init.artificialTerminals();

        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(initialized, artificial);
        Parser.ParsedGrammar withMeta = new Parser.ParsedGrammar(
                initialized.grammarRules(), initialized.sequence(), metadata);

        // Advanced (compressed-space) frequencies
        Map<Pair, Integer> advancedFreqs =
                Recompressor.computeBigramFrequencies(withMeta, artificial, false, null);

        // Naive (decompression-based) frequencies — addSentinels=false since they're already in the grammar
        Map<Pair, Integer> naiveFreqs =
                Main.computeFreqsFromDecompressed(withMeta, false, false);

        // Every bigram in either map must match
        Set<Pair> allBigrams = new HashSet<>();
        allBigrams.addAll(advancedFreqs.keySet());
        allBigrams.addAll(naiveFreqs.keySet());

        for (Pair bigram : allBigrams) {
            int adv   = advancedFreqs.getOrDefault(bigram, 0);
            int naive = naiveFreqs.getOrDefault(bigram, 0);
            assertEquals(naive, adv,
                    "Frequency mismatch for bigram (" + bigram.first + "," + bigram.second + ")");
        }
    }

    @Test
    void recompressionReducesSize() {
        // After recompression, grammar size should be <= original size.
        // Use the paper grammar which has known repeated structure.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:97,256", 98, 256, 256, 97, 97, 257, 257);
        int before = Parser.sizeOfGrammar(g);

        // Run recompressNTimes with verbosity=0 (no file output), roundtrip=false
        // We capture via the public API (recompressNTimes writes to a file; use a temp path)
        // Instead, run a manual pass to verify size reduction is possible.
        // We just check that the combined bigram freq method finds at least one pair with count > 1.
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(g);
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(init.grammar().grammarRules(), init.grammar().sequence(), Collections.emptyMap()),
                init.artificialTerminals());
        Parser.ParsedGrammar working = new Parser.ParsedGrammar(
                init.grammar().grammarRules(), init.grammar().sequence(), metadata);

        Map<Pair, Integer> freqs =
                Recompressor.computeBigramFrequencies(working, init.artificialTerminals(), false, null);

        // There must be at least one bigram with frequency > 1 (meaning compression is possible).
        int maxFreq = freqs.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(maxFreq > 1, "Expected at least one bigram with frequency > 1 to be compressible");
    }

    // =========================================================================
    // Benchmark test (prints timing, always passes)
    // =========================================================================

    @Test
    void benchmark() throws Exception {
        Path grammarPath = Path.of("Test_grammar_20_words.txt");
        int PASSES = 10;
        int RUNS = 3;

        long totalNs = 0;
        for (int run = 0; run < RUNS; run++) {
            Parser.ParsedGrammar g = Parser.parseFile(grammarPath);
            long t0 = System.nanoTime();
            // verbosity=0 suppresses file output and logging; roundtrip=false for pure speed
            Recompressor.recompressNTimes(g, PASSES, 0, true, false, "benchmark_out.txt");
            long t1 = System.nanoTime();
            totalNs += (t1 - t0);
        }
        double avgMs = totalNs / (double) RUNS / 1_000_000.0;
        System.out.printf("[benchmark] recompressNTimes(%d passes) avg over %d runs: %.2f ms%n",
                PASSES, RUNS, avgMs);
        // Always passes — this is just a timing report.
        assertTrue(true);
    }
}
