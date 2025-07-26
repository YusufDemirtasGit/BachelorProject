package grammarextractor;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static grammarextractor.Main.formatSymbol;


public class Recompressor {


    public static void recompressNTimes(Parser.ParsedGrammar originalGrammar, int maxPasses, boolean verbose, boolean initializeGrammar) {
        if (verbose) {
            System.out.println("=== 🚀 Starting recompression ===");
            System.out.println("Max passes: " + maxPasses);
            System.out.println("Original grammar:");
            Parser.printGrammar(originalGrammar);
            System.out.println("================================\n");
        }

        Parser.ParsedGrammar initialized;
        Set<Integer> artificialTerminals = new HashSet<>();
        Map<Integer, List<Integer>> artificialRules = new LinkedHashMap<>();

        if (initializeGrammar) {
            // 0) Initialize with sentinels
            if (verbose) System.out.println("🔧 Initializing grammar with sentinels...");
            InitializedGrammar init = initializeWithSentinelsAndRootRule(originalGrammar);
            initialized = init.grammar;
            artificialTerminals.addAll(init.artificialTerminals);
            if (verbose) {
                System.out.println("✅ Initialization complete. Starting grammar:");
                Parser.printGrammar(new Parser.ParsedGrammar(initialized.grammarRules(), initialized.sequence(), Collections.emptyMap()));
                System.out.println();
            }
        } else {
            if (verbose) System.out.println("🔧 Skipping grammar initialization...");
            initialized = originalGrammar;
        }

        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(initialized.grammarRules());
        List<Integer> sequence = new ArrayList<>(initialized.sequence());

        // Initialize next rule ID (just once)
        int initialMaxId = Math.max(
                rules.keySet().stream().max(Integer::compareTo).orElse(255),
                artificialRules.keySet().stream().max(Integer::compareTo).orElse(255)
        ) + 1;
        AtomicInteger nextRuleId = new AtomicInteger(initialMaxId);
        if (verbose) System.out.println("📍 Initial nextRuleId = " + initialMaxId);

        // Compute initial metadata
        if (verbose) System.out.println("📊 Computing initial metadata...");
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                artificialTerminals
        );
        if (verbose) {
            RuleMetadata.printMetadata(metadata);
            System.out.println("================================");
        }

        // Roundtrip baseline
        String before = Decompressor.decompress(buildCombinedGrammar(rules, artificialRules, sequence, metadata));
        int originalLength = before.length();
        if (verbose) {
            System.out.println("=== 🧵 Decompressed BEFORE All Passes ===\n" + before);
            System.out.println("Original length: " + originalLength + " symbols");
        }

        // Main recompression loop
        for (int pass = 1; pass <= maxPasses; pass++) {
            if (verbose) {
                System.out.println("\n\n================================");
                System.out.println("=== 🔁 Recompression Pass " + pass + " ===");
                System.out.println("================================");
            }

            if (verbose) {
                System.out.println("📊 Current metadata before pass " + pass + ":");
                RuleMetadata.printMetadata(metadata);
                System.out.println("\n📜 Current grammar (excluding artificial rules):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }

            // Compute bigram frequencies
            if (verbose) System.out.println("\n🔍 Computing bigram frequencies...");
            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(new Parser.ParsedGrammar(rules, sequence, metadata), artificialTerminals);
            if (verbose) {
                System.out.println("Bigram frequencies computed: " + frequencies);
            }

            if (frequencies.isEmpty()) {
                if (verbose) System.out.println("✅ No bigrams found. Stopping recompression.");
                break;
            }

            Pair<Integer, Integer> bigram = getMostFrequentBigram(frequencies);
            if (bigram == null || frequencies.getOrDefault(bigram, 0) <= 1) {
                if (verbose) System.out.println("✅ No more compressible bigrams (all <= 1 occurrence).");
                break;
            }

            int c1 = bigram.first;
            int c2 = bigram.second;
            int count = frequencies.get(bigram);

            // Allocate new rule ID
            int newRuleId = nextRuleId.getAndIncrement();
            if (verbose) {
                System.out.printf("📌 Most frequent bigram: (%s, %s) → R%d [%d occurrences]%n",
                        formatSymbol(c1), formatSymbol(c2), newRuleId, count);
                System.out.println("Next available rule ID updated to: " + nextRuleId.get());
            }

            // 1) Uncross bigram
            if (verbose) System.out.println("🔄 Uncrossing bigram in main rules...");
            uncrossBigrams(c1, c2, rules, metadata, artificialTerminals);
            if (verbose) {
                System.out.println("After uncrossing (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }

            // 2) Replace explicit bigram
            if (verbose) System.out.println("📝 Replacing explicit bigram occurrences with R" + newRuleId + "...");
            replaceBigramInRules(c1, c2, newRuleId, rules, artificialTerminals);
            if (verbose) {
                System.out.println("After replacement (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }

            // 3) Store new artificial rule
            artificialRules.put(newRuleId, List.of(c1, c2));
            artificialTerminals.add(newRuleId);
            if (verbose) System.out.printf("🔒 Stored R%d as artificial rule (c1=%s, c2=%s)%n",
                    newRuleId, formatSymbol(c1), formatSymbol(c2));

            if (verbose) {
                System.out.println("After normalization (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }
            removeRedundantRules(rules, sequence);

            // 5) Update metadata
            if (verbose) System.out.println("📊 Updating metadata...");
            metadata = RuleMetadata.computeAll(
                    new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                    artificialTerminals
            );
            if (verbose) {
                System.out.println("Updated metadata:");
                RuleMetadata.printMetadata(metadata);
            }

            // 6) Roundtrip check
            if (verbose) System.out.println("🔍 Performing roundtrip check...");
            String after = Decompressor.decompress(buildCombinedGrammar(rules, artificialRules, sequence, metadata));
            if (!before.equals(after)) {
                if (verbose) {
                    System.err.println("❌ Roundtrip mismatch detected! Stopping at pass " + pass);
                }
                break;
            }
            before = after;

            if (verbose) {
                System.out.println("✅ Roundtrip check passed for pass " + pass + ".");
            }
        }

        // Final grammar = main rules + artificial rules
        if (verbose) System.out.println("\n=== 🏁 Finalizing Grammar (adding artificial rules) ===");
        Map<Integer, List<Integer>> finalRules = new LinkedHashMap<>(rules);
        finalRules.putAll(artificialRules);
        Parser.ParsedGrammar finalGrammar = new Parser.ParsedGrammar(finalRules, sequence, metadata);

        // Stats
        int totalRules = finalRules.size();
        int rhsSymbols = finalRules.values().stream().mapToInt(List::size).sum() + sequence.size();
        double compressionRatio = (100.0 * (originalLength - rhsSymbols)) / originalLength;

        if (verbose) {
            System.out.println("\n=== 📦 Final Grammar After " + maxPasses + " Passes ===");
            Parser.printGrammar(finalGrammar);

            System.out.println("\n=== 📊 Compression Stats ===");
            System.out.println("Original length (decompressed): " + originalLength + " symbols");
            System.out.println("Final grammar rules: " + totalRules);
            System.out.println("Final total RHS symbols: " + rhsSymbols);
            System.out.printf("Space saved (approx.): %.2f%%%n", compressionRatio);
        }

        // Dump final grammar
        try (FileWriter writer = new FileWriter("output.txt")) {
            writer.write("=== 📦 Final Grammar After " + maxPasses + " Passes ===\n");

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, List<Integer>> entry : finalGrammar.grammarRules().entrySet()) {
                sb.append("R").append(entry.getKey()).append(": ");
                String rhs = entry.getValue().stream().map(Object::toString).collect(Collectors.joining(","));
                sb.append(rhs).append("\n");
            }
            sb.append("SEQ:");
            for (int i = 0; i < finalGrammar.sequence().size(); i++) {
                sb.append(finalGrammar.sequence().get(i));
                if (i != finalGrammar.sequence().size() - 1) sb.append(",");
            }
            sb.append("\n\n");

            writer.write(sb.toString());
            if (verbose) System.out.println("💾 Final grammar and stats written to output.txt");
        } catch (IOException e) {
            if (verbose) System.err.println("❌ Failed to write output.txt: " + e.getMessage());
        }
    }




    /** Helper to build a temporary grammar that merges main rules and artificial rules. */
    private static Parser.ParsedGrammar buildCombinedGrammar(
            Map<Integer, List<Integer>> rules,
            Map<Integer, List<Integer>> artificialRules,
            List<Integer> sequence,
            Map<Integer, RuleMetadata> metadata
    ) {
        Map<Integer, List<Integer>> merged = new LinkedHashMap<>(rules);
        merged.putAll(artificialRules);
        return new Parser.ParsedGrammar(merged, sequence, metadata);
    }


    /**
     * Builds the context ρ(X′) · w_X · λ(X`) for a given rule RHS.
     * Artificial terminals are treated as atomic symbols.
     * Non-uniform (multi-block) nonterminals insert a sentinel (-1) to break bigram runs.
     */
    private static List<Integer> buildContext(
            List<Integer> rhs,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        if (rhs.isEmpty()) return new ArrayList<>();

        List<Integer> context = new ArrayList<>();

        // Identify left child, middle, and right child based on paper's model
        Integer leftChild = null;
        Integer rightChild = null;
        List<Integer> middle = new ArrayList<>();

        // First element could be left child X'
        if (!rhs.isEmpty() && rhs.get(0) >= 256 && !artificialTerminals.contains(rhs.get(0))) {
            leftChild = rhs.get(0);
        }

        // Last element could be right child X` (if different from first)
        if (rhs.size() > 1 && rhs.get(rhs.size()-1) >= 256 && !artificialTerminals.contains(rhs.get(rhs.size()-1))) {
            rightChild = rhs.get(rhs.size()-1);
        }

        // Determine middle range
        int startMiddle = (leftChild != null) ? 1 : 0;
        int endMiddle = (rightChild != null && rhs.size() > 1) ? rhs.size()-1 : rhs.size();

        // Build context: ρ(X') + w + λ(X`)

        // Add ρ(X') if left child exists
        if (leftChild != null) {
            RuleMetadata leftMeta = metadata.get(leftChild);
            if (leftMeta != null) {
                int rightTerminal = leftMeta.getRightmostTerminal();
                int rightRunLen = leftMeta.getRightRunLength();
                for (int i = 0; i < rightRunLen; i++) {
                    context.add(rightTerminal);
                }
            }
        }

        // Add middle part w
        for (int i = startMiddle; i < endMiddle; i++) {
            context.add(rhs.get(i));
        }

        // Add λ(X`) if right child exists
        if (rightChild != null) {
            RuleMetadata rightMeta = metadata.get(rightChild);
            if (rightMeta != null) {
                int leftTerminal = rightMeta.getLeftmostTerminal();
                int leftRunLen = rightMeta.getLeftRunLength();
                for (int i = 0; i < leftRunLen; i++) {
                    context.add(leftTerminal);
                }
            }
        }

        return context;
    }


    /**
     * Computes frequencies of non-repeating bigrams (c1 != c2) for the grammar.
     * Aligns with Lemma 1 of the paper.
     */
    public static Map<Pair<Integer, Integer>, Integer> computeNonRepeatingFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> bigramFreqs = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) continue; // Skip artificial rules

            RuleMetadata xMeta = metadata.get(ruleId);
            if (xMeta == null) continue;
            int vocc = xMeta.getVocc();

            List<Integer> rhs = entry.getValue();
            if (rhs == null || rhs.isEmpty()) continue;

            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);

            // Count all adjacent non-repeating pairs
            for (int i = 0; i < context.size() - 1; i++) {
                int c1 = context.get(i);
                int c2 = context.get(i + 1);
                if (c1 != c2) {
                    bigramFreqs.merge(Pair.of(c1, c2), vocc, Integer::sum);
                }
            }
        }

        return bigramFreqs;
    }

    /**
     * Computes frequencies of repeating bigrams (c,c) for the grammar.
     *
     * Based on Lemma 1 of the paper "RePair in Compressed Space and Time":
     * - We detect blocks c^d (d >= 2) in ρ(X') · w_X · λ(X`).
     * - A block is counted only if X "witnesses" its maximality.
     * - Prefix/suffix blocks of val(X) are ignored if they belong to child variables
     *   that are single-block nonterminals.
     */
    public static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> freqMap = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int X = entry.getKey();
            if (artificialTerminals.contains(X)) continue;

            RuleMetadata xMeta = metadata.get(X);

            boolean rhsSingleBlock = xMeta.isSingleBlock();

            if (xMeta == null) continue;

            int vocc = xMeta.getVocc();
            List<Integer> rhs = entry.getValue();
            if (rhs == null || rhs.isEmpty()) continue;

            // Determine if the leftmost or rightmost symbol of rhs is a single-block nonterminal
            boolean leftIsSB = false;
            boolean leftIsTerminal = false;
            int rightmostTerminal = -1;
            if (!rhs.isEmpty()) {
                int first = rhs.get(0);
                if (first >= 256 && !artificialTerminals.contains(first)) {
                    RuleMetadata leftMeta = metadata.get(first);
                    if (leftMeta != null) {
                        leftIsSB = leftMeta.isSingleBlock();

                    }
                }
                else;
                leftIsTerminal = true;

            }
            boolean rightIsTerminal = false;
            boolean rightIsSB = false;
            int leftmostTerminal = -1;
            if (!rhs.isEmpty()) {
                int last = rhs.get(rhs.size() - 1);
                if (last >= 256 && !artificialTerminals.contains(last)) {
                    RuleMetadata rightMeta = metadata.get(last);
                    if (rightMeta != null) {
                        rightIsSB = rightMeta.isSingleBlock();
                    }
                }
                else;
                rightIsTerminal = true;

            }

            // Build the context ρ(X') · w_X · λ(X`)
            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);
            System.out.println("current context");
            System.out.println(context);

            // Scan the context for runs of c^d (d >= 2)
            int i = 0;
            while (i < context.size()) {
                int c = context.get(i);
                int j = i + 1;
                while (j < context.size() && context.get(j) == c) j++;
                int d = j - i; // run length

                if (c >= 0 && d >= 2) {

                    // Prefix check: Ignore run if it's the first block and belongs to a single-block left child
                    boolean isPrefix = (i == 0 && leftIsSB) || (i==0 && c == rhs.getFirst() && rightIsSB) ;


                    // Suffix check: Ignore run if it's the last block and belongs to a single-block right child
                    boolean isSuffix = (j == context.size() && rightIsSB )|| (j==context.size() && c==rhs.getLast() && rightIsTerminal);

                    if (!isPrefix && !isSuffix && !rhsSingleBlock ) {
                        freqMap.merge(Pair.of(c, c), (d / 2) * vocc, Integer::sum);
                    }
                }
                i = j;
            }
        }

        return freqMap;
    }



    /**
     * Merges non-repeating and repeating frequencies into one map.
     */
    public static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> nonRep = computeNonRepeatingFrequencies(grammar, artificialTerminals);
        Map<Pair<Integer, Integer>, Integer> rep = computeRepeatingFrequencies(grammar, artificialTerminals);

        Map<Pair<Integer, Integer>, Integer> merged = new HashMap<>(nonRep);
        for (Map.Entry<Pair<Integer, Integer>, Integer> e : rep.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return merged;
    }




    public record InitializedGrammar(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
    }

    public static InitializedGrammar initializeWithSentinelsAndRootRule(Parser.ParsedGrammar original) {
        Map<Integer, List<Integer>> oldRules = original.grammarRules();
        List<Integer> originalSeq = original.sequence();

        if (originalSeq == null || originalSeq.isEmpty()) {
            throw new IllegalArgumentException("Original sequence is empty.");
        }

        Map<Integer, List<Integer>> newRules = new HashMap<>(oldRules);

        // Add sentinels: '#' = 35, '$' = 36
        List<Integer> extended = new ArrayList<>();
        extended.add(35); // #
        extended.addAll(originalSeq);
        extended.add(36); // $

        // Start assigning new rule IDs
        int maxRuleId = oldRules.keySet().stream().max(Integer::compareTo).orElse(255);
        int nextRuleId = maxRuleId + 1;

        // Convert extended sequence to binary rules
        int current = extended.get(0);
        for (int i = 1; i < extended.size(); i++) {
            int next = extended.get(i);
            List<Integer> rhs = List.of(current, next);
            newRules.put(nextRuleId, rhs);
            current = nextRuleId;
            nextRuleId++;
        }

        int rootRule = current;
        List<Integer> newSeq = List.of(rootRule);

        // No artificial terminals at initialization - they will be added during recompression
        Set<Integer> artificialTerminals = new HashSet<>();

        return new InitializedGrammar(
                new Parser.ParsedGrammar(newRules, newSeq, Collections.emptyMap()),
                artificialTerminals
        );
    }



    /**
     * Replace occurrences of the target bigram on rule RHSs, assuming the bigram
     * has already been uncrossed and is explicit on the RHS (no decompression needed).
     *
     * - Skips rules that are artificial terminals (their bodies are never touched).
     * - Treats artificial terminals that appear *inside* RHSs as atomic symbols.
     * - Non-repeating (c1 != c2): simple adjacent-pair replacement.
     * - Repeating (c1 == c2): tile every explicit run c^d as floor(d/2) * Rnew plus (d%2) * c.
     */
    public static void replaceBigramInRules(
            int c1,
            int c2,
            int newRuleId,
            Map<Integer, List<Integer>> rules,
            Set<Integer> artificialTerminals
    ) {
        final boolean repeating = (c1 == c2);
        final int c = c1; // if repeating

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            final int ruleId = entry.getKey();

            // Never touch artificial terminal rules
            if (artificialTerminals.contains(ruleId)) {
                continue;
            }

            final List<Integer> rhs = entry.getValue();
            final List<Integer> out = new ArrayList<>(rhs.size());

            if (!repeating) {
                // -------- Non-repeating: replace (c1, c2) pairs ----------
                for (int i = 0; i < rhs.size(); ) {
                    int s = rhs.get(i);

                    // Artificial terminals & terminals are atomic — just copy
                    if (artificialTerminals.contains(s) || s < 256) {
                        // But still check if an actual (c1, c2) pair starts here
                        if (i + 1 < rhs.size() && s == c1 && rhs.get(i + 1) == c2) {
                            out.add(newRuleId);
                            i += 2;
                        } else {
                            out.add(s);
                            i++;
                        }
                        continue;
                    }

                    // Nonterminal that's not artificial: still treat it atomically here,
                    // because uncrossing guaranteed the bigram is explicit on RHS.
                    if (i + 1 < rhs.size() && s == c1 && rhs.get(i + 1) == c2) {
                        out.add(newRuleId);
                        i += 2;
                    } else {
                        out.add(s);
                        i++;
                    }
                }
            } else {
                // -------- Repeating: replace runs of c with Rnew tiles ----------
                for (int i = 0; i < rhs.size(); ) {
                    int s = rhs.get(i);

                    // If this position does not start a run of c, just copy atomically.
                    if (s != c) {
                        out.add(s);
                        i++;
                        continue;
                    }

                    // Count the maximal explicit run c^d starting here.
                    int j = i + 1;
                    while (j < rhs.size() && rhs.get(j) == c) j++;
                    int d = j - i;

                    if (d >= 2) {
                        int tiles = d / 2;     // number of (c,c) we can pack
                        int leftover = d % 2;  // 1 if odd length

                        for (int t = 0; t < tiles; t++) {
                            out.add(newRuleId);
                        }
                        if (leftover == 1) {
                            out.add(c);
                        }
                    } else {
                        // d == 1, no replacement possible
                        out.add(c);
                    }

                    i = j;
                }
            }

            entry.setValue(out);
        }
    }

    public static Pair<Integer, Integer> getMostFrequentBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
        Pair<Integer, Integer> mostFrequent = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            Pair<Integer, Integer> bigram = entry.getKey();
            int count = entry.getValue();

            if (count > maxCount ||
                    (count == maxCount && mostFrequent != null &&
                            (bigram.first < mostFrequent.first ||
                                    (bigram.first.equals(mostFrequent.first) && bigram.second < mostFrequent.second)))) {
                maxCount = count;
                mostFrequent = bigram;
            }
        }

        return mostFrequent;
    }


    public static void uncrossBigrams(
            final int c1, // c'
            final int c2, // c`
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        final boolean repeating = (c1 == c2);
        final int c = c1;

        // We will write back directly into "rules".
        // Collect rules that become NULL to clean up references afterwards.
        final Set<Integer> nullRules = new HashSet<>();

        // ---------- PASS: PopInLet + PopOutLet (per rule, immediately trimmed) ----------
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            final int ruleId = e.getKey();

            // Artificial-terminal rules are left untouched.
            if (artificialTerminals.contains(ruleId)) continue;

            final List<Integer> rhs = e.getValue();
            if (rhs == null || rhs.isEmpty()) {
                nullRules.add(ruleId);
                e.setValue(Collections.emptyList());
                continue;
            }

            // 1) POP-INLET: build a new RHS with popped letters/runs inserted
            final List<Integer> newRhs = new ArrayList<>(rhs.size() + 4); // small headroom

            for (int i = 0, n = rhs.size(); i < n; i++) {
                int sym = rhs.get(i);

                // Terminals & artificial terminals are atomic
                if (sym < 256 || artificialTerminals.contains(sym)) {
                    newRhs.add(sym);
                    continue;
                }

                // Non-artificial nonterminal
                RuleMetadata subMeta = metadata.get(sym);
                if (subMeta == null) {
                    // Unknown meta => treat atomically
                    newRhs.add(sym);
                    continue;
                }

                if (!repeating) {
                    // Non-repeating case: insert c2 before Y if Y starts with c2 and Y is not first
                    if (i > 0 && subMeta.getLeftmostTerminal() == c2) {
                        newRhs.add(c2);
                    }

                    newRhs.add(sym);

                    // Insert c1 after Y if Y ends with c1 and Y is not last
                    if (i < n - 1 && subMeta.getRightmostTerminal() == c1) {
                        newRhs.add(c1);
                    }

                } else {
                    // Repeating case: pop entire runs of 'c'
                    if (i > 0 && subMeta.getLeftmostTerminal() == c) {
                        int len = subMeta.getLeftRunLength();
                        for (int t = 0; t < len; t++) newRhs.add(c);
                    }

                    newRhs.add(sym);

                    if (i < n - 1 && subMeta.getRightmostTerminal() == c) {
                        int len = subMeta.getRightRunLength();
                        for (int t = 0; t < len; t++) newRhs.add(c);
                    }
                }
            }

            // 2) POP-OUTLET: trim boundary symbols (efficiently, via indices)
            int start = 0, end = newRhs.size(); // [start, end)

            if (!repeating) {
                // delete leading c2
                if (start < end && newRhs.get(start) == c2) start++;
                // delete trailing c1
                if (start < end && newRhs.get(end - 1) == c1) end--;
            } else {
                // delete all leading c
                while (start < end && newRhs.get(start) == c) start++;
                // delete all trailing c
                while (start < end && newRhs.get(end - 1) == c) end--;
            }

            if (start >= end) {
                nullRules.add(ruleId);
                e.setValue(Collections.emptyList());
            } else {
                // copy trimmed slice once
                e.setValue(new ArrayList<>(newRhs.subList(start, end)));
            }
        }

        // ---------- PASS: Remove NULL RULES everywhere ----------
        if (!nullRules.isEmpty()) {
            for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
                int ruleId = e.getKey();
                if (artificialTerminals.contains(ruleId)) continue;

                List<Integer> body = e.getValue();
                if (body == null || body.isEmpty()) continue;

                // filter out null-rule references
                List<Integer> filtered = null; // lazy alloc
                for (int i = 0; i < body.size(); i++) {
                    int sym = body.get(i);
                    if (nullRules.contains(sym)) {
                        if (filtered == null) {
                            filtered = new ArrayList<>(body.size());
                            // copy everything so far
                            for (int j = 0; j < i; j++) filtered.add(body.get(j));
                        }
                        // skip sym
                    } else if (filtered != null) {
                        filtered.add(sym);
                    }
                }

                if (filtered != null) {
                    e.setValue(filtered);
                }
            }

            // Finally, delete null rules from the map
            for (int nr : nullRules) {
                rules.remove(nr);
            }
        }
    }
    /**
     * Removes redundant rules of the form Rk: X (where RHS has only 1 symbol),
     * by replacing Rk with X in all other rules and then deleting Rk.
     */
    public static void removeRedundantRules(Map<Integer, List<Integer>> rules, List<Integer> sequence) {
        boolean changed;
        do {
            changed = false;
            Set<Integer> toRemove = new HashSet<>();

            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
                int ruleId = entry.getKey();
                List<Integer> rhs = entry.getValue();

                if (rhs.size() == 1) { // Single-leaf rule
                    int replacement = rhs.get(0);
                    // Replace ruleId with replacement everywhere
                    for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
                        if (e.getKey() == ruleId) continue;
                        List<Integer> newRhs = new ArrayList<>();
                        for (int sym : e.getValue()) {
                            newRhs.add((sym == ruleId) ? replacement : sym);
                        }
                        e.setValue(newRhs);
                    }
                    // Also update sequence
                    for (int i = 0; i < sequence.size(); i++) {
                        if (sequence.get(i) == ruleId) {
                            sequence.set(i, replacement);
                        }
                    }
                    toRemove.add(ruleId);
                    changed = true;
                }
            }
            // Remove redundant rules
            for (int r : toRemove) rules.remove(r);
        } while (changed);
    }







    private static RuleMetadata dummyMeta() {
        return new RuleMetadata(0, 0, -1, -1, false, 0, 0);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
