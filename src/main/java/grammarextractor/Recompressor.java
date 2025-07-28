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
        int initialMaxId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;
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
        int counter = 1;
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


            if (verbose) System.out.println("📊 Updating metadata...");
            metadata = RuleMetadata.computeAll(
                    new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                    artificialTerminals
            );


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

            // 6) Roundtrip check
            if (verbose) System.out.println("🔍 Performing roundtrip check...");
            String after = Decompressor.decompress(buildCombinedGrammar(rules, artificialRules, sequence, metadata));
            if (!before.equals(after)) {
                if (verbose) {
                    System.out.println("Roundtrip mismatch detected! Stopping at pass " + pass);
                }
                break;
            }
            before = after;

            if (verbose) {
                System.out.println("✅ Roundtrip check passed for pass " + pass + ".");
            }
            counter++;
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
            System.out.println("\n=== 📦 Final Grammar After " + counter + " Passes ===");
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


    /**
     * Helper to build a temporary grammar that merges main rules and artificial rules.
     */
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
     * Updated to properly handle blocks as defined in the paper.
     */
    private static List<Integer> buildContext(
            List<Integer> rhs,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        if (rhs.isEmpty()) return new ArrayList<>();

        List<Integer> context = new ArrayList<>();

        // Process first element - get its rightmost block ρ(X')
        int firstElement = rhs.get(0);
        if (firstElement < 256 || artificialTerminals.contains(firstElement)) {
            // Terminal or artificial terminal - single occurrence
            context.add(firstElement);
        } else {
            // Non-terminal - get its rightmost block
            RuleMetadata firstMeta = metadata.get(firstElement);
            if (firstMeta != null && firstMeta.getRightmostBlock() != null) {
                RuleMetadata.Block rightBlock = firstMeta.getRightmostBlock();
                // Add the entire rightmost block
                for (int i = 0; i < rightBlock.runLength; i++) {
                    context.add(rightBlock.symbol);
                }
            }
        }

        // Process middle elements (if rhs has more than 2 elements) - these go as-is
        for (int i = 1; i < rhs.size() - 1; i++) {
            context.add(rhs.get(i));
        }

        // Process last element - get its leftmost block λ(X`)
        if (rhs.size() > 1) {
            int lastElement = rhs.get(rhs.size() - 1);
            if (lastElement < 256 || artificialTerminals.contains(lastElement)) {
                // Terminal or artificial terminal - single occurrence
                context.add(lastElement);
            } else {
                // Non-terminal - get its leftmost block
                RuleMetadata lastMeta = metadata.get(lastElement);
                if (lastMeta != null && lastMeta.getLeftmostBlock() != null) {
                    RuleMetadata.Block leftBlock = lastMeta.getLeftmostBlock();
                    // Add the entire leftmost block
                    for (int i = 0; i < leftBlock.runLength; i++) {
                        context.add(leftBlock.symbol);
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
                    System.out.println("added non-repeating block " + c1 + c2 + " " + vocc + " times");
                }
            }
        }

        return bigramFreqs;
    }

    /**
     * Computes frequencies of repeating bigrams (c,c) for the grammar.
     * <p>
     * Based on Lemma 1 of the paper "RePair in Compressed Space and Time":
     * - We detect blocks c^d (d >= 2) in ρ(X') · w_X · λ(X`).
     * - A block is counted only if X "witnesses" its maximality.
     * - Prefix/suffix blocks of val(X) are ignored if they belong to child variables
     * that are single-block nonterminals.
     */
    /**
     * Computes frequencies of repeating bigrams (c,c) for the grammar.
     * Based on Lemma 1 of the paper - properly handles witness checking.
     */
    public static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> freqMap = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int Y = entry.getKey();
            if (artificialTerminals.contains(Y)) continue;

            RuleMetadata yMeta = metadata.get(Y);
            if (yMeta == null) continue;

            int vocc = yMeta.getVocc();
            List<Integer> rhs = entry.getValue();
            if (rhs == null || rhs.isEmpty()) continue;

            // Build context = ρ(X') · w_Y · λ(X`)
            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);

            if (context.isEmpty()) continue;

            // Check if first/last children are terminal or single-block
            int X1 = rhs.get(0);
            int X2 = rhs.get(rhs.size() - 1);
            boolean leftIsTerminalOrSingleBlock = isTerminalOrSingleBlock(X1, metadata, artificialTerminals);
            boolean rightIsTerminalOrSingleBlock = isTerminalOrSingleBlock(X2, metadata, artificialTerminals);

            // Scan context for runs of c^d (d >= 2)
            int i = 0;
            while (i < context.size()) {
                int c = context.get(i);
                int j = i + 1;
                while (j < context.size() && context.get(j) == c) j++;
                int d = j - i; // run length

                if (d >= 2) {
                    // Check if this is a prefix or suffix run that should be ignored
                    boolean isPrefixRun = (i == 0 && leftIsTerminalOrSingleBlock);
                    boolean isSuffixRun = (j == context.size() && rightIsTerminalOrSingleBlock);

                    // Only count if Y witnesses the maximality of this block
                    if (!isPrefixRun && !isSuffixRun) {
                        // For a repeating bigram cc, a block c^d contributes floor(d/2) occurrences
                        freqMap.merge(Pair.of(c, c), (d / 2) * vocc, Integer::sum);
                    }
                }
                i = j;
            }
        }

        return freqMap;
    }

    private static boolean isTerminalOrSingleBlock(
            int symbol,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        if (symbol < 256 || artificialTerminals.contains(symbol)) {
            return true;
        }
        RuleMetadata m = metadata.get(symbol);
        return m != null && m.isSingleBlock();
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
     * Make the bigram (c1,c2) explicit inside every RHS so that the later replacement
     * can be done by a pure linear scan (no implicit crossings through nonterminals).
     * <p>
     * - If c1 != c2 (non-repeating):
     * • Strip leading c2 and trailing c1 terminals (or artificial terminals).
     * • For each variable Y on RHS, if leftmostTerminal(Y)==c2, expand it to c2 Y
     * (unless Y is first). If rightmostTerminal(Y)==c1, expand it to Y c1
     * (unless Y is last). If both hold and Y is in the middle, expand to c2 Y c1.
     * <p>
     * - If c1 == c2 (repeating):
     * • Delete explicit runs of c at the beginning and end of each RHS.
     * • Using the context (ρ(X′) · w_X · λ(X)), if the run of c starts at the very
     * beginning, push that run (>=2) to the left of the first variable.
     * If the run of c ends at the very end, push it (>=2) to the right of the last variable.
     * <p>
     * Finally, remove empty rules and their references.
     */
    static void uncrossBigrams(
            int c1,
            int c2,
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        if (c1 == c2) {
            uncrossRepeating(c1, rules, metadata, artificialTerminals);
        } else {
            uncrossNonRepeating(c1, c2, rules, metadata, artificialTerminals);
        }
        deleteEmptyRulesAndRewire(rules);
    }

    /* --------------------------- non-repeating case --------------------------- */

    /**
     * Uncross repeating bigrams (c,c) in the grammar.
     * Updated to properly compute push amounts from context.
     */
    private static void uncrossRepeating(
            int c,
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            int X = e.getKey();
            if (artificialTerminals.contains(X)) continue;

            List<Integer> rhs = new ArrayList<>(e.getValue());
            if (rhs.isEmpty()) continue;

            // 1) Strip leading/trailing explicit runs of c
            int start = 0;
            while (start < rhs.size() && rhs.get(start) == c) start++;
            int end = rhs.size() - 1;
            while (end >= start && rhs.get(end) == c) end--;

            rhs = (start <= end) ? new ArrayList<>(rhs.subList(start, end + 1)) : new ArrayList<>();
            if (rhs.isEmpty()) {
                e.setValue(rhs);
                continue;
            }

            // 2) Build context to determine how many c's to push
            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);

            // Count prefix run of c in the context
            int prefixRun = 0;
            while (prefixRun < context.size() && context.get(prefixRun) == c) {
                prefixRun++;
            }

            // Count suffix run of c in the context
            int suffixRun = 0;
            for (int j = context.size() - 1; j >= 0 && context.get(j) == c; j--) {
                suffixRun++;
            }

            // Only push if run length >= 2 and target is a variable
            boolean pushLeft = prefixRun >= 2 &&
                    !rhs.isEmpty() &&
                    isVariable(rhs.get(0), rules, artificialTerminals);

            boolean pushRight = suffixRun >= 2 &&
                    !rhs.isEmpty() &&
                    isVariable(rhs.get(rhs.size() - 1), rules, artificialTerminals);

            List<Integer> result = new ArrayList<>();

            // Push prefix run if needed
            if (pushLeft) {
                for (int k = 0; k < prefixRun; k++) {
                    result.add(c);
                }
            }

            // Add the middle content
            result.addAll(rhs);

            // Push suffix run if needed
            if (pushRight) {
                for (int k = 0; k < suffixRun; k++) {
                    result.add(c);
                }
            }

            e.setValue(result);
        }
    }




    /* ---------------------------- repeating case ----------------------------- */

    /**
     * Uncross non-repeating bigrams (c1,c2) where c1 != c2.
     * Updated to use block-aware metadata.
     */
    private static void uncrossNonRepeating(
            int c1,
            int c2,
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            int X = e.getKey();
            if (artificialTerminals.contains(X)) continue;

            List<Integer> rhs = new ArrayList<>(e.getValue());
            if (rhs.isEmpty()) continue;

            // 1) Strip explicit boundary terminals
            if (!rhs.isEmpty() && isTerminalOrArtificial(rhs.get(0), artificialTerminals) && rhs.get(0) == c2) {
                rhs.remove(0);
            }
            if (!rhs.isEmpty() && isTerminalOrArtificial(rhs.get(rhs.size() - 1), artificialTerminals) && rhs.get(rhs.size() - 1) == c1) {
                rhs.remove(rhs.size() - 1);
            }

            if (rhs.isEmpty()) {
                e.setValue(rhs);
                continue;
            }

            // 2) Expand variables with c2 at left boundary or c1 at right boundary
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);

                if (!isVariable(sym, rules, artificialTerminals)) {
                    out.add(sym);
                    continue;
                }

                RuleMetadata yMeta = metadata.get(sym);
                if (yMeta == null) {
                    out.add(sym);
                    continue;
                }

                // Check if leftmost/rightmost blocks contain c1/c2
                boolean hasLeft = false;
                boolean hasRight = false;

                if (yMeta.getLeftmostBlock() != null) {
                    hasLeft = (yMeta.getLeftmostBlock().symbol == c2);
                }
                if (yMeta.getRightmostBlock() != null) {
                    hasRight = (yMeta.getRightmostBlock().symbol == c1);
                }

                boolean isFirst = (i == 0);
                boolean isLast = (i == rhs.size() - 1);

                if (hasLeft && !isFirst) out.add(c2);
                out.add(sym);
                if (hasRight && !isLast) out.add(c1);
            }

            e.setValue(out);
        }
    }

    /* ---------------------- cleanup: remove empty rules ----------------------- */

    private static void deleteEmptyRulesAndRewire(Map<Integer, List<Integer>> rules) {
        boolean changed = true;

        while (changed) {
            // 1. Find rules that are empty (no RHS symbols)
            Set<Integer> emptyRules = rules.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            if (emptyRules.isEmpty()) {
                break; // No empty rules left, stop
            }

            changed = false;

            // 2. Remove references to empty rules from all other RHSs
            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
                int ruleId = entry.getKey();
                if (emptyRules.contains(ruleId)) continue; // Will be removed anyway

                List<Integer> rhs = entry.getValue();
                List<Integer> updated = new ArrayList<>();

                for (int sym : rhs) {
                    if (!emptyRules.contains(sym)) {
                        updated.add(sym);
                    } else {
                        changed = true;
                    }
                }
                entry.setValue(updated);
            }

            // 3. Remove the empty rules themselves
            for (int id : emptyRules) {
                rules.remove(id);
                changed = true;
            }
        }
    }

    /* ---------------------------- small utilities ---------------------------- */

    private static boolean isVariable(
            int sym,
            Map<Integer, List<Integer>> rules,
            Set<Integer> artificialTerminals
    ) {
        return sym > 255 && rules.containsKey(sym) && !artificialTerminals.contains(sym);
    }

    private static boolean isTerminalOrArtificial(int sym, Set<Integer> artificialTerminals) {
        return sym <= 255 || artificialTerminals.contains(sym);
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
        return new RuleMetadata(0, 0, new RuleMetadata.Block(0,0), new RuleMetadata.Block(0,0), false);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
