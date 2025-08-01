package grammarextractor;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static grammarextractor.Main.formatSymbol;


public class Recompressor {


    public static void recompressNTimes(Parser.ParsedGrammar originalGrammar, int maxPasses, boolean verbose, boolean initializeGrammar, boolean roundtrip,String output) {
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

        int initialMaxId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;
        AtomicInteger nextRuleId = new AtomicInteger(initialMaxId);

        if (verbose) System.out.println("📍 Initial nextRuleId = " + initialMaxId);

        if (verbose) System.out.println("📊 Computing initial metadata...");
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()),
                artificialTerminals
        );
        if (verbose) {
            RuleMetadata.printMetadata(metadata);
            System.out.println("================================");
        }

        String before = null;
        int originalLength = 0;
        if (roundtrip || verbose) {
            before = Decompressor.decompress(buildCombinedGrammar(rules, artificialRules, sequence, metadata));
            originalLength = before.length();
            if (verbose) {
                System.out.println("=== 🧵 Decompressed BEFORE All Passes ===\n" + before);
                System.out.println("Original length: " + originalLength + " symbols");
            }
        }

        int counter = 1;
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

            if (verbose) System.out.println("\n🔍 Computing bigram frequencies...");
            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(new Parser.ParsedGrammar(rules, sequence, metadata), artificialTerminals, verbose);
            if (verbose) {
                System.out.println("Bigram frequencies computed: " + frequencies);
            }

            if (frequencies.isEmpty()) {
                if (verbose) System.out.println("✅ No bigrams found. Stopping recompression.");
                break;
            }

            Pair<Integer, Integer> bigram = getMostFrequentBigram(frequencies, artificialTerminals);
            if (bigram == null || frequencies.getOrDefault(bigram, 0) <= 1) {
                System.out.println("✅ No more compressible bigrams (all <= 1 occurrence).");
                break;
            }

            int c1 = bigram.first;
            int c2 = bigram.second;
            int count = frequencies.get(bigram);

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

            if (verbose) System.out.println("🔄 Uncrossing bigram in main rules...");
            uncrossBigrams(c1, c2, rules, metadata, artificialTerminals);
            if (verbose) {
                System.out.println("After uncrossing (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }

            if (verbose) System.out.println("📝 Replacing explicit bigram occurrences with R" + newRuleId + "...");
            replaceBigramInRules(c1, c2, newRuleId, rules, artificialTerminals);
            if (verbose) {
                System.out.println("After replacement (main rules only):");
                Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
            }

            artificialRules.put(newRuleId, List.of(c1, c2));
            artificialTerminals.add(newRuleId);
                System.out.printf("🔒 Stored R%d as artificial rule (c1=%s, c2=%s)%n", newRuleId, formatSymbol(c1), formatSymbol(c2));


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

            if (roundtrip) {
                if (verbose) System.out.println("🔍 Performing roundtrip check...");
                String after = Decompressor.decompress(buildCombinedGrammar(rules, artificialRules, sequence, metadata));
                if (!before.equals(after)) {
                    System.out.println("Roundtrip mismatch detected! Stopping at pass " + pass);
                    break;
                }
                before = after;
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




        if (verbose) System.out.println("\n=== 🏁 Finalizing Grammar (adding artificial rules) ===");
        Map<Integer, List<Integer>> finalRules = new LinkedHashMap<>(rules);
        finalRules.putAll(artificialRules);
        Parser.ParsedGrammar finalGrammar = new Parser.ParsedGrammar(finalRules, sequence, metadata);
        finalGrammar = normalizeGrammar(finalGrammar);

        if (roundtrip) {
            if (verbose) System.out.println("🔍 Performing final roundtrip comparison...");
            String finalResult = Decompressor.decompress(finalGrammar);
            if (before != null && !finalResult.equals(before)) {
                System.out.println("⚠️ Final roundtrip mismatch detected after all passes!");
            } else {
                System.out.println("✅ Final roundtrip result matches original input.");
            }
        }




        if (verbose) {
            System.out.println("\n=== 📦 Final Grammar After " + counter + " Passes ===");
            Parser.printGrammar(finalGrammar);

        }

        try (FileWriter writer = new FileWriter(output)) {

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
            if (verbose) System.out.println("💾 Final grammar and stats written to "+ output );
        } catch (IOException e) {
            if (verbose) System.err.println("❌ Failed to write to "+output+ ":" + e.getMessage());
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
    public static Parser.ParsedGrammar normalizeGrammar(Parser.ParsedGrammar grammar) {
        Map<Integer, List<Integer>> originalRules = new LinkedHashMap<>(grammar.grammarRules());
        List<Integer> originalSequence = new ArrayList<>(grammar.sequence());

        // Step 1: Expand single-rule sequence
        if (originalSequence.size() == 1) {
            int only = originalSequence.get(0);
            if (only >= 256 && originalRules.containsKey(only)) {
                // Replace sequence with RHS of that rule
                originalSequence = new ArrayList<>(originalRules.get(only));
                // Remove the rule from the grammar
                originalRules.remove(only);
            }
        }

        // Step 2: Create new rule ID mapping starting from 256
        Map<Integer, Integer> idMapping = new HashMap<>();
        int nextId = 256;

        for (Integer oldId : originalRules.keySet()) {
            idMapping.put(oldId, nextId++);
        }

        // Step 3: Rewrite rules using new IDs
        Map<Integer, List<Integer>> normalizedRules = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : originalRules.entrySet()) {
            int newId = idMapping.get(entry.getKey());

            List<Integer> newRhs = new ArrayList<>();
            for (int symbol : entry.getValue()) {
                if (symbol >= 256 && idMapping.containsKey(symbol)) {
                    newRhs.add(idMapping.get(symbol));
                } else {
                    newRhs.add(symbol);
                }
            }

            normalizedRules.put(newId, newRhs);
        }

        // Step 4: Rewrite the sequence
        List<Integer> normalizedSequence = new ArrayList<>();
        for (int symbol : originalSequence) {
            if (symbol >= 256 && idMapping.containsKey(symbol)) {
                normalizedSequence.add(idMapping.get(symbol));
            } else {
                normalizedSequence.add(symbol);
            }
        }

        // Step 5: Recompute metadata
        Parser.ParsedGrammar normalizedPartial = new Parser.ParsedGrammar(normalizedRules, normalizedSequence, Collections.emptyMap());
        Map<Integer, RuleMetadata> newMetadata = RuleMetadata.computeAll(normalizedPartial, Collections.emptySet());

        return new Parser.ParsedGrammar(normalizedRules, normalizedSequence, newMetadata);
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
        int firstElement = rhs.getFirst();
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
            Set<Integer> artificialTerminals,
            boolean verbose
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
            if(verbose)System.out.println("non repeating context for rule " + ruleId + ":");
            if(verbose)System.out.println(context);
            // Count all adjacent non-repeating pairs
            for (int i = 0; i < context.size() - 1; i++) {
                int c1 = context.get(i);
                int c2 = context.get(i + 1);
                if (c1 != c2) {
                    bigramFreqs.merge(Pair.of(c1, c2), vocc, Integer::sum);
                    if(verbose)System.out.println("added non-repeating block " + c1 + c2 + " " + vocc + " times");
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
            Set<Integer> artificialTerminals,
            boolean verbose
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

            // Build context = right run of X1 + w(Y) + left run of X2
            List<Integer> context = buildContext(rhs, metadata, artificialTerminals);
            if(verbose)System.out.println("repeating context for rule " + Y + ":" + context);

            // Determine X1 (first) and X2 (last)
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
                    boolean isPrefixRun = (i == 0 && leftIsTerminalOrSingleBlock);
                    boolean isSuffixRun = (j == context.size() && rightIsTerminalOrSingleBlock);

                    if (!isPrefixRun && !isSuffixRun) {
                        freqMap.merge(Pair.of(c, c), (d / 2) * vocc, Integer::sum);
                        if(verbose)System.out.println("added repeating block " + c + " " + (d / 2) * vocc + " times");
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
            Set<Integer> artificialTerminals,
            boolean verbose
    ) {
        Map<Pair<Integer, Integer>, Integer> nonRep = computeNonRepeatingFrequencies(grammar, artificialTerminals,verbose);
        Map<Pair<Integer, Integer>, Integer> rep = computeRepeatingFrequencies(grammar, artificialTerminals,verbose);

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
     *
     *  - If c1 != c2 (non-repeating):
     *      • Strip leading c2 and trailing c1 terminals (or artificial terminals).
     *      • For each variable Y on RHS, if leftmostTerminal(Y)==c2, expand it to c2 Y
     *        (unless Y is first). If rightmostTerminal(Y)==c1, expand it to Y c1
     *        (unless Y is last). If both hold and Y is in the middle, expand to c2 Y c1.
     *
     *  - If c1 == c2 (repeating):
     *      • Delete explicit runs of c at the beginning and end of each RHS.
     *      • Using the context (ρ(X′) · w_X · λ(X)), if the run of c starts at the very
     *        beginning, push that run (>=2) to the left of the first variable.
     *        If the run of c ends at the very end, push it (>=2) to the right of the last variable.
     *
     *  Finally, remove empty rules and their references.
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

//    private static void uncrossNonRepeating(
//            int c1,
//            int c2,
//            Map<Integer, List<Integer>> rules,
//            Map<Integer, RuleMetadata> metadata,
//            Set<Integer> artificialTerminals
//    ) {
//        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
//            int X = e.getKey();
//            if (artificialTerminals.contains(X)) continue;
//
//            List<Integer> rhs = new ArrayList<>(e.getValue());
//            if (rhs.isEmpty()) continue;
//
//            // 1) Strip explicit boundary terminals
//            if (!rhs.isEmpty() && isTerminalOrArtificial(rhs.get(0), artificialTerminals) && rhs.get(0) == c2) {
//                rhs.remove(0);
//            }
//            if (!rhs.isEmpty() && isTerminalOrArtificial(rhs.get(rhs.size() - 1), artificialTerminals) && rhs.get(rhs.size() - 1) == c1) {
//                rhs.remove(rhs.size() - 1);
//            }
//
//            if (rhs.isEmpty()) {
//                e.setValue(rhs);
//                continue;
//            }
//
//            // 2) Expand variables with c2 at left boundary or c1 at right boundary of their expansions
//            List<Integer> out = new ArrayList<>();
//            for (int i = 0; i < rhs.size(); i++) {
//                int sym = rhs.get(i);
//
//                if (!isVariable(sym, rules, artificialTerminals)) {
//                    out.add(sym);
//                    continue;
//                }
//
//                RuleMetadata yMeta = metadata.getOrDefault(sym, dummyMeta());
//                int l = yMeta.getLeftmostTerminal();
//                int r = yMeta.getRightmostTerminal();
//
//                boolean hasLeft = (l == c2);
//                boolean hasRight = (r == c1);
//
//                boolean isFirst = (i == 0);
//                boolean isLast  = (i == rhs.size() - 1);
//
//                if (hasLeft && !isFirst) out.add(c2);
//                out.add(sym);
//                if (hasRight && !isLast) out.add(c1);
//            }
//
//            e.setValue(out);
//        }
//    }
private static void uncrossNonRepeating(
        int c1,
        int c2,
        Map<Integer, List<Integer>> rules,
        Map<Integer, RuleMetadata> metadata,
        Set<Integer> artificialTerminals
) {
    for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
        int ruleId = e.getKey();
        if (artificialTerminals.contains(ruleId)) continue;

        List<Integer> originalRhs = e.getValue();
        if (originalRhs.isEmpty()) continue;

        List<Integer> newRhs = new ArrayList<>();

        for (int i = 0; i < originalRhs.size(); i++) {
            int sym = originalRhs.get(i);
            boolean isFirstPos = (i == 0);
            boolean isLastPos = (i == originalRhs.size() - 1);

            // --- PopOutLet Decision ---
            // Decide if this symbol should be popped out and skipped.
            // This only applies if it's a boundary terminal.
            if (!isVariable(sym, rules, artificialTerminals)) {
                if ((isFirstPos && sym == c2) || (isLastPos && sym == c1)) {
                    continue; // Skip this symbol.
                }
            }

            // If we are here, the symbol itself is kept.
            // Now, decide if we need to pop anything IN around it.

            // --- PopinLet (Left Side) ---
            if (isVariable(sym, rules, artificialTerminals)) {
                RuleMetadata meta = metadata.get(sym);
                if (meta != null && meta.getLeftmostTerminal() == c2 && !isFirstPos) {
                    newRhs.add(c2);
                }
            }

            // --- Add the symbol itself ---
            newRhs.add(sym);

            // --- PopinLet (Right Side) ---
            if (isVariable(sym, rules, artificialTerminals)) {
                RuleMetadata meta = metadata.get(sym);
                if (meta != null && meta.getRightmostTerminal() == c1 && !isLastPos) {
                    newRhs.add(c1);
                }
            }
        }
        e.setValue(newRhs);
    }
}

    /* ---------------------------- repeating case ----------------------------- */

    private static void uncrossRepeating(
            int c,
            Map<Integer, List<Integer>> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            int ruleId = e.getKey();
            if (artificialTerminals.contains(ruleId)) continue;

            List<Integer> rhs = new ArrayList<>(e.getValue());
            if (rhs.isEmpty()) continue;

            // --- LEFT TRIM ---
            boolean leftWasFirst = true;
            while (!rhs.isEmpty()) {
                int first = rhs.get(0);
                if (first == c) {
                    rhs.remove(0); // explicit terminal
                } else if (isSingleBlockOf(first, c, metadata, artificialTerminals)) {
                    rhs.remove(0); // implicit terminal (via SingleBlock)
                } else {
                    break;
                }
            }

            // --- RIGHT TRIM ---
            boolean rightWasLast = true;
            while (!rhs.isEmpty()) {
                int last = rhs.get(rhs.size() - 1);
                if (last == c) {
                    rhs.remove(rhs.size() - 1); // explicit terminal
                } else if (isSingleBlockOf(last, c, metadata, artificialTerminals)) {
                    rhs.remove(rhs.size() - 1); // implicit terminal (via SingleBlock)
                } else {
                    break;
                }
            }

            // --- Optional POPIN logic ---
            // Apply context-based re-expansion if the deleted content belonged to a neighbor.
            // This is your "R cr" and "cl R" rule based on position

            List<Integer> newRhs = new ArrayList<>();

            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);
                boolean isFirst = (i == 0);
                boolean isLast = (i == rhs.size() - 1);

                // Pop-in on left
                if (isVariable(sym, rules, artificialTerminals)) {
                    RuleMetadata m = metadata.get(sym);
                    if (m != null && m.getLeftmostTerminal() == c && !isFirst) {
                        for (int j = 0; j < m.getLeftRunLength(); j++) {
                            newRhs.add(c);
                        }
                    }
                }


                newRhs.add(sym);

                // Pop-in on right
                if (isVariable(sym, rules, artificialTerminals)) {
                    RuleMetadata m = metadata.get(sym);
                    if (m != null && m.getRightmostTerminal() == c && !isLast) {
                        for (int j = 0; j < m.getRightRunLength(); j++) {
                            newRhs.add(c);
                        }
                    }
                }
            }

            e.setValue(newRhs);
        }
    }

    private static boolean isSingleBlockOf(
            int symbol,
            int targetTerminal,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        if (symbol < 256 || artificialTerminals.contains(symbol)) {
            return symbol == targetTerminal;
        }
        RuleMetadata m = metadata.get(symbol);
        return m != null && m.isSingleBlock()
                && m.getLeftmostTerminal() == targetTerminal
                && m.getRightmostTerminal() == targetTerminal;
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
     * Replaces occurrences of the target bigram on rule RHSs, assuming the bigram
     * has already been uncrossed and is explicit on the RHS.
     *
     * - Skips rules that are artificial terminals, as their bodies are never modified.
     * - Non-repeating (c1 != c2): Replaces all adjacent (c1, c2) pairs.
     * - Repeating (c1 == c2): Replaces every explicit run c^d with floor(d/2) instances of
     * the new rule and a leftover c if d is odd.
     */
    public static void replaceBigramInRules(
            int c1,
            int c2,
            int newRuleId,
            Map<Integer, List<Integer>> rules,
            Set<Integer> artificialTerminals
    ) {
        final boolean repeating = (c1 == c2);

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            final int ruleId = entry.getKey();

            // Never modify the definition of an artificial terminal.
            if (artificialTerminals.contains(ruleId)) {
                continue;
            }

            final List<Integer> rhs = entry.getValue();
            if (rhs.isEmpty()) {
                continue;
            }

            final List<Integer> out = new ArrayList<>(rhs.size());

            if (!repeating) {
                // -------- Non-repeating: replace (c1, c2) pairs ----------
                for (int i = 0; i < rhs.size(); ) {
                    // Check for an adjacent (c1, c2) pair starting at the current position.
                    if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                        out.add(newRuleId);
                        i += 2; // Advance index past both symbols of the replaced pair.
                    } else {
                        out.add(rhs.get(i));
                        i++; // Advance index by one.
                    }
                }
            } else {
                // -------- Repeating: replace runs of c with the new rule ID ----------
                final int c = c1;
                for (int i = 0; i < rhs.size(); ) {
                    int s = rhs.get(i);

                    // If this position does not start a run of c, just copy the symbol.
                    if (s != c) {
                        out.add(s);
                        i++;
                        continue;
                    }

                    // Find the end of the maximal run of c's starting at i.
                    int j = i + 1;
                    while (j < rhs.size() && rhs.get(j) == c) {
                        j++;
                    }
                    int d = j - i; // The length of the run (c^d).

                    // Replace the run using the new rule ID for every pair of c's.
                    if (d >= 2) {
                        int numNewRules = d / 2;
                        int leftover = d % 2;

                        for (int t = 0; t < numNewRules; t++) {
                            out.add(newRuleId);
                        }
                        if (leftover == 1) {
                            out.add(c);
                        }
                    } else {
                        // The run has length 1, so no replacement is possible.
                        out.add(c);
                    }
                    i = j; // Continue scanning after the run.
                }
            }
            // Update the rule with the new, modified right-hand side.
            entry.setValue(out);
        }
    }

//    public static Pair<Integer, Integer> getMostFrequentBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
//        Pair<Integer, Integer> mostFrequent = null;
//        int maxCount = -1;
//
//        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
//            Pair<Integer, Integer> bigram = entry.getKey();
//            int count = entry.getValue();
//
//            if (count > maxCount ||
//                    (count == maxCount && mostFrequent != null &&
//                            (bigram.first < mostFrequent.first ||
//                                    (bigram.first.equals(mostFrequent.first) && bigram.second < mostFrequent.second)))) {
//                maxCount = count;
//                mostFrequent = bigram;
//            }
//        }
//
//        return mostFrequent;
//    }

    public static Pair<Integer, Integer> getMostFrequentBigram(
            Map<Pair<Integer, Integer>, Integer> frequencies,
            Set<Integer> artificialTerminals
    ) {
        Pair<Integer, Integer> bestBigram = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            Pair<Integer, Integer> bigram = entry.getKey();
            int count = entry.getValue();

            if (count < maxCount) continue;

            boolean isRepeating = bigram.first.equals(bigram.second);
            boolean hasArtificial = artificialTerminals.contains(bigram.first) || artificialTerminals.contains(bigram.second);

            if (count > maxCount) {
                // New highest frequency
                maxCount = count;
                bestBigram = bigram;
            } else {
                // Tie on frequency — apply priority rules
                boolean currentIsRepeating = bestBigram.first.equals(bestBigram.second);
                boolean currentHasArtificial = artificialTerminals.contains(bestBigram.first) || artificialTerminals.contains(bestBigram.second);

                if (isRepeating && !currentIsRepeating) {
                    bestBigram = bigram;
                } else if (!isRepeating && currentIsRepeating) {
                    // keep current
                } else if (hasArtificial && !currentHasArtificial) {
                    bestBigram = bigram;
                } else if (!hasArtificial && currentHasArtificial) {
                    // keep current
                } else {
                    // fallback: lexicographical order
                    if (bigram.first < bestBigram.first ||
                            (bigram.first.equals(bestBigram.first) && bigram.second < bestBigram.second)) {
                        bestBigram = bigram;
                    }
                }
            }
        }

        return bestBigram;
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
