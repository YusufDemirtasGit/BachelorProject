package grammarextractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.RepeatedTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case and property tests for the recompression algorithm.
 */
class AdvancedGrammarTests {

    // ── Helpers ────────────────────────────────────────────────────────────────

    static Parser.ParsedGrammar makeGrammar(String spec, int... seq) {
        return GrammarTests.makeGrammar(spec, seq);
    }

    /** Build a flat grammar from a plain string (no rules, just terminals in SEQ). */
    static Parser.ParsedGrammar fromString(String s) {
        int[] seq = new int[s.length()];
        for (int i = 0; i < s.length(); i++) seq[i] = s.charAt(i);
        return makeGrammar("", seq);
    }

    /**
     * Run up to maxPasses of the core recompression loop (without file I/O),
     * then decompress and strip the sentinels '#' and '$' added by initialization.
     */
    static String recompressAndDecompress(Parser.ParsedGrammar g, int maxPasses) {
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(g);
        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(init.grammar().grammarRules());
        List<Integer> seq  = new ArrayList<>(init.grammar().sequence());
        Set<Integer>  art  = new HashSet<>(init.artificialTerminals());
        Map<Integer, List<Integer>> artRules = new LinkedHashMap<>();
        int nextId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        for (int pass = 0; pass < maxPasses; pass++) {
            Map<Integer, RuleMetadata> meta = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(rules, seq, Collections.emptyMap()), art);
            Parser.ParsedGrammar wg = new Parser.ParsedGrammar(rules, seq, meta);

            Map<Pair<Integer,Integer>, Integer> freqs =
                Recompressor.computeBigramFrequencies(wg, art, false, null);
            if (freqs.isEmpty()) break;

            Pair<Integer,Integer> bg = Recompressor.getMostFrequentBigram(freqs, art);
            if (bg == null || freqs.getOrDefault(bg, 0) <= 1) break;

            Recompressor.uncrossBigrams(bg.first, bg.second, rules, meta, art);
            int newId = nextId++;
            Recompressor.replaceBigramInRules(bg.first, bg.second, newId, rules, art);
            artRules.put(newId, List.of(bg.first, bg.second));
            art.add(newId);
            Recompressor.removeRedundantRules(rules, seq);
        }

        Map<Integer, List<Integer>> combined = new LinkedHashMap<>(rules);
        combined.putAll(artRules);
        Map<Integer, RuleMetadata> finalMeta = RuleMetadata.computeAll(
            new Parser.ParsedGrammar(combined, seq, Collections.emptyMap()), art);
        String result = Decompressor.decompress(new Parser.ParsedGrammar(combined, seq, finalMeta));

        // Strip sentinels added by initializeWithSentinelsAndRootRule
        if (result.length() >= 2 && result.charAt(0) == '#' && result.charAt(result.length()-1) == '$')
            result = result.substring(1, result.length()-1);
        return result;
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    void singleTerminal() {
        // Single 'a' — no bigrams, no compression possible.
        Parser.ParsedGrammar g = fromString("a");
        assertEquals("a", Decompressor.decompress(g));
        assertEquals("a", recompressAndDecompress(g, 10));
    }

    @Test
    void allSameCharacter() {
        // "aaaaaaa" — the repeating bigram (a,a) should be found.
        String input = "aaaaaaa";
        Parser.ParsedGrammar g = fromString(input);
        assertEquals(input, recompressAndDecompress(g, 20));
    }

    @Test
    void alternatingPairs() {
        // "ababababab" — (a,b) is the dominant non-repeating bigram.
        String input = "ababababab";
        Parser.ParsedGrammar g = fromString(input);
        assertEquals(input, recompressAndDecompress(g, 10));
    }

    @Test
    void twoCharString() {
        // Only one bigram possible; must roundtrip correctly.
        String input = "ab";
        Parser.ParsedGrammar g = fromString(input);
        assertEquals(input, recompressAndDecompress(g, 5));
    }

    @Test
    void mixedRepeatingAndNonRepeating() {
        // "aabbaabb" — has both (a,a), (b,b) repeating and (a,b), (b,a) non-repeating.
        String input = "aabbaabb";
        assertEquals(input, recompressAndDecompress(fromString(input), 20));
    }

    // ── Roundtrip properties ───────────────────────────────────────────────────

    @RepeatedTest(3)
    void roundtripMultiplePasses() {
        // A grammar that benefits from several passes of compression.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:97,256", 98, 256, 256, 97, 97, 257, 257);
        String before = Decompressor.decompress(g);
        assertEquals(before, recompressAndDecompress(g, 15));
    }

    @Test
    void roundtripNestedGrammar() {
        // Deeply nested: R258 = R257 R257; R257 = R256 R256; R256 = a b.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,256;258:257,257", 258);
        String before = Decompressor.decompress(g);  // "abababab"
        assertEquals("abababab", before);
        assertEquals(before, recompressAndDecompress(g, 10));
    }

    // ── Metadata properties ────────────────────────────────────────────────────

    @Test
    void voccAlwaysPositiveForReachableRules() {
        // Every rule reachable from SEQ must have vocc > 0.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,97;258:257,256", 258);
        Map<Integer, RuleMetadata> meta = g.metadata();
        for (int rid : List.of(256, 257, 258)) {
            assertTrue(meta.get(rid).getVocc() > 0,
                "vocc of reachable rule R" + rid + " must be > 0");
        }
    }

    @Test
    void lengthEqualsChildrenLengthSum() {
        // R256:[a,b] has length 2; R257:[R256,R256] has length 4.
        Parser.ParsedGrammar g = makeGrammar("256:97,98;257:256,256", 257);
        Map<Integer, RuleMetadata> meta = g.metadata();
        int l256 = meta.get(256).getLength();
        int l257 = meta.get(257).getLength();
        assertEquals(2, l256);
        assertEquals(l256 + l256, l257, "R257's length must equal sum of its two children");
    }

    @Test
    void singleBlockImpliesUniformTerminals() {
        // If isSingleBlock(X), then leftmost(X) == rightmost(X).
        Parser.ParsedGrammar g = makeGrammar(
            "256:97,97;257:256,97;258:97,98;259:257,257", 256, 257, 258, 259);
        Map<Integer, RuleMetadata> meta = g.metadata();
        for (Map.Entry<Integer, RuleMetadata> e : meta.entrySet()) {
            RuleMetadata m = e.getValue();
            if (m.isSingleBlock()) {
                assertEquals(m.getLeftmostTerminal(), m.getRightmostTerminal(),
                    "R" + e.getKey() + " isSingleBlock but leftmost != rightmost");
            }
        }
    }

    @Test
    void leftRunNeverExceedsLength() {
        // leftRunLength <= length for every rule.
        Parser.ParsedGrammar g = makeGrammar(
            "256:97,97;257:97,98;258:256,257", 258);
        Map<Integer, RuleMetadata> meta = g.metadata();
        for (Map.Entry<Integer, RuleMetadata> e : meta.entrySet()) {
            RuleMetadata m = e.getValue();
            assertTrue(m.getLeftRunLength()  <= m.getLength(), "leftRun > length for R" + e.getKey());
            assertTrue(m.getRightRunLength() <= m.getLength(), "rightRun > length for R" + e.getKey());
        }
    }

    // ── Excerpt after recompression ────────────────────────────────────────────

    @Test
    void excerptFromInitializedGrammar() {
        // The Extractor is designed for binary SLP grammars.
        // initializeWithSentinelsAndRootRule produces an all-binary grammar, so extraction works correctly.
        // (After recompression, uncross can create rules with 3+ symbols which Extractor does not support.)
        String input = "ababababab";
        Parser.ParsedGrammar orig = fromString(input);

        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(orig);
        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(init.grammar().grammarRules());
        List<Integer> seq = new ArrayList<>(init.grammar().sequence());
        Map<Integer, RuleMetadata> meta = RuleMetadata.computeAll(
            new Parser.ParsedGrammar(rules, seq, Collections.emptyMap()), new HashSet<>());
        Parser.ParsedGrammar initialized = new Parser.ParsedGrammar(rules, seq, meta);

        // Full string is "#ababababab$"; total length = 12
        String full = Decompressor.decompress(initialized);
        assertEquals("#" + input + "$", full);

        // Extract [3, 8) from "#ababababab$"  →  "ababa"  =  input.substring(2, 7)
        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(initialized, 3, 8, false);
        assertEquals(input.substring(2, 7), Decompressor.decompress(excerpt));
    }
}
