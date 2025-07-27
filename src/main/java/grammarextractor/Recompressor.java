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

            removeRedundantRules(rules, sequence);
            if (verbose) {
                System.out.println("After normalization (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }


            if (verbose) System.out.println("📊 Updating metadata...");
            metadata = RuleMetadata.computeAll(
                    new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                    artificialTerminals
            );


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
        removeRedundantRules(rules, sequence);
        if (verbose) System.out.println("📊 Updating metadata...");
        metadata = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                artificialTerminals
        );
        if (verbose) {
            System.out.println("Updated metadata:");
            RuleMetadata.printMetadata(metadata);
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

        // Process first element
        int firstElement = rhs.get(0);
        if (firstElement < 256 || artificialTerminals.contains(firstElement)) {
            // Terminal or artificial terminal - take as is
            context.add(firstElement);
        } else {
            // Non-terminal - get its rightmost run
            RuleMetadata firstMeta = metadata.get(firstElement);
            if (firstMeta != null) {
                int rightTerminal = firstMeta.getRightmostTerminal();
                int rightRunLen = firstMeta.getRightRunLength();
                for (int i = 0; i < rightRunLen; i++) {
                    context.add(rightTerminal);
                }
            }
        }

        // Process middle elements (if rhs has more than 2 elements)
        for (int i = 1; i < rhs.size() - 1; i++) {
            context.add(rhs.get(i));
        }

        // Process last element (if different from first)
        if (rhs.size() > 1) {
            int lastElement = rhs.get(rhs.size() - 1);
            if (lastElement < 256 || artificialTerminals.contains(lastElement)) {
                // Terminal or artificial terminal - take as is
                context.add(lastElement);
            } else {
                // Non-terminal - get its leftmost run
                RuleMetadata lastMeta = metadata.get(lastElement);
                if (lastMeta != null) {
                    int leftTerminal = lastMeta.getLeftmostTerminal();
                    int leftRunLen = lastMeta.getLeftRunLength();
                    for (int i = 0; i < leftRunLen; i++) {
                        context.add(leftTerminal);
                    }
                }
            }
        }

        return context;
    }


    /**
     * Computes frequencies of non-repeating bigrams (c1 != c2) for the grammar.
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
            System.out.println("non repeating context for rule " + ruleId + ":");
            System.out.println(context);
            // Count all adjacent non-repeating pairs
            for (int i = 0; i < context.size() - 1; i++) {
                int c1 = context.get(i);
                int c2 = context.get(i + 1);
                if (c1 != c2) {
                    bigramFreqs.merge(Pair.of(c1, c2), vocc, Integer::sum);
                    System.out.println("added repeating block " + c1 + c2 + " " + vocc + " times");
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
            boolean leftIsSB = rhs.getFirst() < 256;
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
                else {
                    leftIsTerminal = true;
                }
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
                else {
                    rightIsTerminal = true;
                }
            }

            // Build the context ρ(X') · w_X · λ(X`)
            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);
            System.out.println("repeating current context for rule " + X + ":");
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
                    boolean isPrefix = (i == 0 && leftIsSB) || (i==0 && c == rhs.getFirst() && leftIsTerminal) ;


                    // Suffix check: Ignore run if it's the last block and belongs to a single-block right child
                    boolean isSuffix = (j == context.size() && rightIsSB )|| (j==context.size() && c==rhs.getLast() && rightIsTerminal);

                    if (!isPrefix && !isSuffix && !rhsSingleBlock ) {
                        freqMap.merge(Pair.of(c, c), (d / 2) * vocc, Integer::sum);
                        System.out.println("added repeating block " + c + c + " " + (d/2)*vocc + " times");
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
    public static void uncrossBigrams(
            int c1, int c2,
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        boolean isRepeating = (c1 == c2);
        Set<Integer> nullRules = new HashSet<>();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();

            // Skip artificial terminal rules
            if (artificialTerminals.contains(ruleId)) continue;

            List<Integer> rhs = entry.getValue();
            List<Integer> newRhs = new ArrayList<>();

            // First, perform PopOutLet on the current rule
            int startIdx = 0;
            int endIdx = rhs.size();

            // Remove from beginning
            if (!rhs.isEmpty()) {
                if (isRepeating) {
                    // Remove run of c from beginning
                    while (startIdx < endIdx && rhs.get(startIdx) == c1) {
                        startIdx++;
                    }
                } else {
                    // Remove c2 from beginning
                    if (rhs.get(startIdx) == c2) {
                        startIdx++;
                    }
                }
            }

            // Remove from end
            if (startIdx < endIdx) {
                if (isRepeating) {
                    // Remove run of c from end
                    while (endIdx > startIdx && rhs.get(endIdx - 1) == c1) {
                        endIdx--;
                    }
                } else {
                    // Remove c1 from end
                    if (rhs.get(endIdx - 1) == c1) {
                        endIdx--;
                    }
                }
            }

            // Check if rule becomes null
            if (startIdx >= endIdx) {
                nullRules.add(ruleId);
                continue;
            }

            // Process remaining symbols with PopInLet
            for (int i = startIdx; i < endIdx; i++) {
                int symbol = rhs.get(i);

                // For non-first position, check if we need to pop from left
                if (i > startIdx && symbol >= 256 && !artificialTerminals.contains(symbol)) {
                    RuleMetadata childMeta = metadata.get(symbol);
                    if (childMeta != null) {
                        if (isRepeating && childMeta.getLeftmostTerminal() == c1) {
                            // Pop entire left run
                            int runLength = childMeta.getLeftRunLength();
                            for (int j = 0; j < runLength; j++) {
                                newRhs.add(c1);
                            }
                        } else if (!isRepeating && childMeta.getLeftmostTerminal() == c2) {
                            // Pop single c2
                            newRhs.add(c2);
                        }
                    }
                }

                // Add the symbol itself (unless it's a null reference)
                if (!nullRules.contains(symbol)) {
                    newRhs.add(symbol);
                }

                // For non-last position, check if we need to pop from right
                if (i < endIdx - 1 && symbol >= 256 && !artificialTerminals.contains(symbol)) {
                    RuleMetadata childMeta = metadata.get(symbol);
                    if (childMeta != null) {
                        if (isRepeating && childMeta.getRightmostTerminal() == c1) {
                            // Pop entire right run
                            int runLength = childMeta.getRightRunLength();
                            for (int j = 0; j < runLength; j++) {
                                newRhs.add(c1);
                            }
                        } else if (!isRepeating && childMeta.getRightmostTerminal() == c1) {
                            // Pop single c1
                            newRhs.add(c1);
                        }
                    }
                }
            }

            // Update the rule
            entry.setValue(newRhs);
        }

        // Remove null rules and their references in a second pass
        for (int nullRule : nullRules) {
            rules.remove(nullRule);

            // Remove references to null rules
            for (List<Integer> rhs : rules.values()) {
                rhs.removeIf(s -> s == nullRule);
            }
        }
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

                    }
                    else{
                        out.add(s);
                        i++;
                    }


                }
            } else {
                // -------- Repeating: replace runs of c with Rule new tiles ----------
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



    public static void removeRedundantRules(Map<Integer, List<Integer>> rules, List<Integer> sequence) {
        // Step 1: Identify all unit (size-1) rules
        List<Integer> unitRules = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            if (entry.getValue().size() == 1) {
                unitRules.add(entry.getKey());
            }
        }

        if (unitRules.isEmpty()) {
            return; // Nothing to do
        }

        // Step 2: Resolve transitive dependencies among unit rules
        Map<Integer, Integer> representative = new HashMap<>();
        for (int ruleId : unitRules) {
            representative.put(ruleId, resolveRepresentative(ruleId, rules));
        }

        // Step 3: Replace occurrences of unit rules with their representative
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);
                if (representative.containsKey(sym)) {
                    Parser.RuleEditor.set(rules, ruleId, i, representative.get(sym));
                }
            }
        }

        // Update sequence
        for (int i = 0; i < sequence.size(); i++) {
            int sym = sequence.get(i);
            if (representative.containsKey(sym)) {
                sequence.set(i, representative.get(sym));
            }
        }

        // Step 4: Remove the original unit rules
        for (int ruleId : unitRules) {
            rules.remove(ruleId);
        }
    }

    /**
     * Follow chain of size-1 rules until we reach a terminal or a non-unit rule.
     */
    private static int resolveRepresentative(int ruleId, Map<Integer, List<Integer>> rules) {
        int current = ruleId;
        Set<Integer> seen = new HashSet<>();
        while (rules.containsKey(current) && rules.get(current).size() == 1) {
            if (!seen.add(current)) break; // avoid cycles
            current = rules.get(current).get(0);
        }
        return current;
    }









    private static RuleMetadata dummyMeta() {
        return new RuleMetadata(0, 0, -1, -1, false, 0, 0);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
