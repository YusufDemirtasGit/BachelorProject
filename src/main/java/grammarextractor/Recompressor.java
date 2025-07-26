package grammarextractor;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Recompressor implementation based on "RePair in Compressed Space and Time" paper.
 * This class transforms an arbitrary grammar S into RePair(T) without decompressing.
 */
public class Recompressor {

    private static final int SENTINEL_START = 35; // '#'
    private static final int SENTINEL_END = 36;   // '$'
    private static final boolean DEBUG = false;

    /**
     * Container for compressed grammar with artificial terminals tracking
     */
    public static class InitializedGrammar {
        public final CompressedGrammar grammar;
        public final Set<Integer> artificialTerminals;

        public InitializedGrammar(CompressedGrammar grammar, Set<Integer> artificialTerminals) {
            this.grammar = grammar;
            this.artificialTerminals = artificialTerminals;
        }
    }

    /**
     * Main recompression method that applies RePair m times
     */
    public static Parser.ParsedGrammar recompressNTimes(Parser.ParsedGrammar originalGrammar,
                                                        int maxPasses,
                                                        boolean verbose) {
        // Step 1: Initialize with sentinels and convert to compressed format
        InitializedGrammar init = initializeWithSentinels(originalGrammar);
        CompressedGrammar workingGrammar = init.grammar;
        Set<Integer> artificialTerminals = new HashSet<>(init.artificialTerminals);

        // Track all introduced letters (new rules from RePair)
        List<Pair<Integer, Pair<Integer, Integer>>> repairRules = new ArrayList<>();

        if (verbose) {
            System.out.println("=== Initial Grammar with Sentinels ===");
            workingGrammar.print();
            System.out.println("|S0| = " + workingGrammar.getTotalSize());
        }

        // Track grammar sizes for analysis
        List<Integer> grammarSizes = new ArrayList<>();
        grammarSizes.add(workingGrammar.getTotalSize());

        // Verify initial decompression
        String initialDecompressed = Decompressor.decompress(workingGrammar.toParsedGrammar());
        if (verbose) {
            System.out.println("Initial decompressed: " + initialDecompressed);
        }

        // Main recompression loop
        for (int level = 1; level <= maxPasses; level++) {
            if (verbose) {
                System.out.printf("\n=== Recompression Level %d ===\n", level);
            }

            // Compute bigram frequencies
            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(workingGrammar, artificialTerminals);

            // Find most frequent bigram
            Pair<Integer, Integer> mostFrequent = getMostFrequentBigram(frequencies);
            if (mostFrequent == null || frequencies.get(mostFrequent) <= 1) {
                if (verbose) {
                    System.out.println("No more bigrams with frequency >= 2. Stopping.");
                }
                break;
            }

            int c1 = mostFrequent.first;
            int c2 = mostFrequent.second;
            int frequency = frequencies.get(mostFrequent);

            // Create new rule for this bigram
            int newRuleId = getNextRuleId(workingGrammar);
            repairRules.add(Pair.of(newRuleId, mostFrequent));

            if (verbose) {
                System.out.printf("Most frequent bigram: (%s, %s) with frequency %d -> R%d\n",
                        formatSymbol(c1), formatSymbol(c2), frequency, newRuleId);
            }

            // Transform grammar
            workingGrammar = transformCompressedGrammar(
                    workingGrammar, c1, c2, newRuleId, artificialTerminals
            );

            // The new rule becomes an artificial terminal for future passes
            artificialTerminals.add(newRuleId);

            // Track grammar size
            grammarSizes.add(workingGrammar.getTotalSize());

            if (verbose) {
                System.out.println("|S" + level + "| = " + workingGrammar.getTotalSize());

                // Verify correctness
                String decompressed = Decompressor.decompress(workingGrammar.toParsedGrammar());
                if (!decompressed.equals(initialDecompressed)) {
                    System.err.println("ERROR: Decompression mismatch at level " + level);
                    System.err.println("Expected: " + initialDecompressed);
                    System.err.println("Got: " + decompressed);
                    break;
                }
            }
        }

        // Create final RePair grammar
        Parser.ParsedGrammar repairGrammar = createRepairGrammar(
                workingGrammar, repairRules, artificialTerminals
        );

        if (verbose) {
            System.out.println("\n=== Final Statistics ===");
            System.out.println("Number of levels: " + (grammarSizes.size() - 1));
            System.out.println("Grammar sizes: " + grammarSizes);
            System.out.println("Total replacements: " + repairRules.size());
            System.out.println("Max |Sh|: " + Collections.max(grammarSizes));
        }

        return repairGrammar;
    }

    /**
     * Initialize grammar with sentinels and convert to compressed format
     */
    public static InitializedGrammar initializeWithSentinels(Parser.ParsedGrammar original) {
        Map<Integer, List<Integer>> oldRules = new HashMap<>(original.grammarRules());
        List<Integer> oldSequence = original.sequence();

        if (oldSequence.size() != 1 || oldSequence.get(0) < 256) {
            throw new IllegalArgumentException("Expected grammar with single root nonterminal");
        }

        int rootRule = oldSequence.get(0);
        int newRootId = getNextRuleId(oldRules);

        // Add sentinel rules
        int sentinelStartRule = newRootId + 1;
        int sentinelEndRule = newRootId + 2;

        Map<Integer, List<Integer>> newRules = new HashMap<>(oldRules);
        newRules.put(sentinelStartRule, Arrays.asList(SENTINEL_START, rootRule));
        newRules.put(sentinelEndRule, Arrays.asList(sentinelStartRule, SENTINEL_END));

        List<Integer> newSequence = Collections.singletonList(sentinelEndRule);

        // Convert to compressed format
        Parser.ParsedGrammar withSentinels =
                new Parser.ParsedGrammar(newRules, newSequence, Collections.emptyMap());

        Set<Integer> artificialTerminals = new HashSet<>();
        CompressedGrammar compressed =
                CompressedGrammar.fromParsedGrammar(withSentinels, artificialTerminals);

        return new InitializedGrammar(compressed, artificialTerminals);
    }

    /**
     * Transform compressed grammar by replacing bigram c1,c2 with new rule
     */
    private static CompressedGrammar transformCompressedGrammar(
            CompressedGrammar grammar,
            int c1, int c2, int newRuleId,
            Set<Integer> artificialTerminals
    ) {
        Map<Integer, CompressedGrammar.CompressedRule> rules =
                new HashMap<>(grammar.getRules());

        // Step 1: PopInLet - uncross occurrences
        popInLetCompressed(c1, c2, rules, grammar.getMetadata(), artificialTerminals);

        // Step 2: PopOutLet - remove crossed letters
        popOutLetCompressed(c1, c2, rules, grammar.getMetadata(), artificialTerminals);

        // Step 3: Add new rule
        CompressedGrammar.CompressedRule newRule = new CompressedGrammar.CompressedRule(
                null,
                Arrays.asList(
                        new CompressedGrammar.Block(c1, 1),
                        new CompressedGrammar.Block(c2, 1)
                ),
                null
        );
        rules.put(newRuleId, newRule);

        // Step 4: Replace all occurrences of c1,c2 with newRuleId
        replaceBigramCompressed(c1, c2, newRuleId, rules, artificialTerminals);

        // Create new compressed grammar and recompute metadata
        Map<Integer, List<Integer>> standardRules = new HashMap<>();
        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            standardRules.put(entry.getKey(), entry.getValue().toList());
        }

        Parser.ParsedGrammar temp = new Parser.ParsedGrammar(
                standardRules, grammar.getSequence(), Collections.emptyMap()
        );

        return CompressedGrammar.fromParsedGrammar(temp, artificialTerminals);
    }

    /**
     * PopInLet operation for compressed grammar
     */
    private static void popInLetCompressed(
            int c1, int c2,
            Map<Integer, CompressedGrammar.CompressedRule> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        Map<Integer, CompressedGrammar.CompressedRule> updates = new HashMap<>();

        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) continue;

            CompressedGrammar.CompressedRule rule = entry.getValue();
            boolean needsUpdate = false;

            List<CompressedGrammar.Block> newMiddle =
                    new ArrayList<>(rule.getMiddleBlocks());

            // Check left variable
            Integer leftVar = rule.getLeftVariable();
            if (leftVar != null && !artificialTerminals.contains(leftVar)) {
                RuleMetadata leftMeta = metadata.get(leftVar);
                if (leftMeta != null && leftMeta.getRightmostTerminal() == c1) {
                    // Need to pop c1 from left variable
                    newMiddle.add(0, new CompressedGrammar.Block(c1, 1));
                    needsUpdate = true;
                }
            }

            // Check right variable
            Integer rightVar = rule.getRightVariable();
            if (rightVar != null && !artificialTerminals.contains(rightVar)) {
                RuleMetadata rightMeta = metadata.get(rightVar);
                if (rightMeta != null && rightMeta.getLeftmostTerminal() == c2) {
                    // Need to pop c2 from right variable
                    newMiddle.add(new CompressedGrammar.Block(c2, 1));
                    needsUpdate = true;
                }
            }

            if (needsUpdate) {
                // Merge adjacent blocks if necessary
                newMiddle = mergeAdjacentBlocks(newMiddle);
                updates.put(ruleId, new CompressedGrammar.CompressedRule(
                        leftVar, newMiddle, rightVar
                ));
            }
        }

        rules.putAll(updates);
    }

    /**
     * PopOutLet operation for compressed grammar
     */
    private static void popOutLetCompressed(
            int c1, int c2,
            Map<Integer, CompressedGrammar.CompressedRule> rules,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        Map<Integer, CompressedGrammar.CompressedRule> updates = new HashMap<>();
        Set<Integer> nullRules = new HashSet<>();

        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) continue;

            CompressedGrammar.CompressedRule rule = entry.getValue();
            List<CompressedGrammar.Block> middle =
                    new ArrayList<>(rule.getMiddleBlocks());

            boolean updated = false;

            // Remove c2 from start of middle blocks
            if (!middle.isEmpty() && middle.get(0).symbol == c2) {
                CompressedGrammar.Block first = middle.get(0);
                if (first.count == 1) {
                    middle.remove(0);
                } else {
                    middle.set(0, new CompressedGrammar.Block(c2, first.count - 1));
                }
                updated = true;
            }

            // Remove c1 from end of middle blocks
            if (!middle.isEmpty() && middle.get(middle.size() - 1).symbol == c1) {
                CompressedGrammar.Block last = middle.get(middle.size() - 1);
                if (last.count == 1) {
                    middle.remove(middle.size() - 1);
                } else {
                    middle.set(middle.size() - 1,
                            new CompressedGrammar.Block(c1, last.count - 1));
                }
                updated = true;
            }

            // Check if rule becomes null
            if (rule.getLeftVariable() == null &&
                    rule.getRightVariable() == null &&
                    middle.isEmpty()) {
                nullRules.add(ruleId);
            } else if (updated) {
                updates.put(ruleId, new CompressedGrammar.CompressedRule(
                        rule.getLeftVariable(), middle, rule.getRightVariable()
                ));
            }
        }

        // Apply updates
        rules.putAll(updates);

        // Remove null rules
        for (int nullRule : nullRules) {
            rules.remove(nullRule);
        }

        // Remove references to null rules
        if (!nullRules.isEmpty()) {
            removeNullReferences(rules, nullRules);
        }
    }

    /**
     * Replace bigram in compressed grammar
     */
    private static void replaceBigramCompressed(
            int c1, int c2, int newRuleId,
            Map<Integer, CompressedGrammar.CompressedRule> rules,
            Set<Integer> artificialTerminals
    ) {
        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            if (artificialTerminals.contains(entry.getKey())) continue;

            CompressedGrammar.CompressedRule rule = entry.getValue();
            List<CompressedGrammar.Block> newBlocks = new ArrayList<>();

            for (CompressedGrammar.Block block : rule.getMiddleBlocks()) {
                if (block.symbol == c1) {
                    // Process c1 block for c1,c2 bigrams
                    newBlocks.addAll(processBlockForBigram(block, c1, c2, newRuleId));
                } else {
                    newBlocks.add(block);
                }
            }

            // Check for c1,c2 crossing block boundaries
            for (int i = 0; i < newBlocks.size() - 1; i++) {
                if (newBlocks.get(i).symbol == c1 &&
                        newBlocks.get(i + 1).symbol == c2) {
                    // Replace crossing bigram
                    CompressedGrammar.Block b1 = newBlocks.get(i);
                    CompressedGrammar.Block b2 = newBlocks.get(i + 1);

                    newBlocks.remove(i + 1);
                    newBlocks.remove(i);

                    List<CompressedGrammar.Block> replacement = new ArrayList<>();
                    if (b1.count > 1) {
                        replacement.add(new CompressedGrammar.Block(c1, b1.count - 1));
                    }
                    replacement.add(new CompressedGrammar.Block(newRuleId, 1));
                    if (b2.count > 1) {
                        replacement.add(new CompressedGrammar.Block(c2, b2.count - 1));
                    }

                    newBlocks.addAll(i, replacement);
                    i--; // Recheck from current position
                }
            }

            entry.setValue(new CompressedGrammar.CompressedRule(
                    rule.getLeftVariable(), newBlocks, rule.getRightVariable()
            ));
        }
    }

    /**
     * Process a block for bigram replacement
     */
    private static List<CompressedGrammar.Block> processBlockForBigram(
            CompressedGrammar.Block block, int c1, int c2, int newRuleId
    ) {
        List<CompressedGrammar.Block> result = new ArrayList<>();

        if (c1 == c2) {
            // Repeating bigram
            int count = block.count;
            int pairs = count / 2;
            int remainder = count % 2;

            if (pairs > 0) {
                result.add(new CompressedGrammar.Block(newRuleId, pairs));
            }
            if (remainder > 0) {
                result.add(new CompressedGrammar.Block(c1, remainder));
            }
        } else {
            // Non-repeating - just return the block
            result.add(block);
        }

        return result;
    }

    /**
     * Merge adjacent blocks with same symbol
     */
    private static List<CompressedGrammar.Block> mergeAdjacentBlocks(
            List<CompressedGrammar.Block> blocks
    ) {
        if (blocks.size() <= 1) return blocks;

        List<CompressedGrammar.Block> merged = new ArrayList<>();
        CompressedGrammar.Block current = blocks.get(0);

        for (int i = 1; i < blocks.size(); i++) {
            CompressedGrammar.Block next = blocks.get(i);
            if (current.symbol == next.symbol) {
                current = new CompressedGrammar.Block(
                        current.symbol, current.count + next.count
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Remove references to null rules
     */
    private static void removeNullReferences(
            Map<Integer, CompressedGrammar.CompressedRule> rules,
            Set<Integer> nullRules
    ) {
        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            CompressedGrammar.CompressedRule rule = entry.getValue();

            Integer newLeft = rule.getLeftVariable();
            Integer newRight = rule.getRightVariable();

            if (newLeft != null && nullRules.contains(newLeft)) {
                newLeft = null;
            }
            if (newRight != null && nullRules.contains(newRight)) {
                newRight = null;
            }

            if (newLeft != rule.getLeftVariable() || newRight != rule.getRightVariable()) {
                entry.setValue(new CompressedGrammar.CompressedRule(
                        newLeft, rule.getMiddleBlocks(), newRight
                ));
            }
        }
    }

    // Include all the frequency computation methods from the previous artifact
    // (computeBigramFrequencies, computeNonRepeatingFrequencies, etc.)
    // ... [Previous frequency computation code here] ...

    /**
     * Create final RePair grammar
     */
    private static Parser.ParsedGrammar createRepairGrammar(
            CompressedGrammar finalGrammar,
            List<Pair<Integer, Pair<Integer, Integer>>> repairRules,
            Set<Integer> artificialTerminals
    ) {
        Map<Integer, List<Integer>> rules = new HashMap<>();

        // Add all RePair rules (the artificial terminals)
        for (Pair<Integer, Pair<Integer, Integer>> rule : repairRules) {
            int ruleId = rule.first;
            int c1 = rule.second.first;
            int c2 = rule.second.second;
            rules.put(ruleId, Arrays.asList(c1, c2));
        }

        // Add the final sequence as a start rule
        Parser.ParsedGrammar temp = finalGrammar.toParsedGrammar();
        String finalString = Decompressor.decompress(temp);

        // Remove sentinels
        if (finalString.startsWith("#") && finalString.endsWith("$")) {
            finalString = finalString.substring(1, finalString.length() - 1);
        }

        // Create start rule
        int startRule = getNextRuleId(rules);
        rules.put(startRule, parseStringToSymbols(finalString));

        return new Parser.ParsedGrammar(
                rules,
                Collections.singletonList(startRule),
                Collections.emptyMap()
        );
    }

    private static List<Integer> parseStringToSymbols(String str) {
        List<Integer> symbols = new ArrayList<>();
        for (char c : str.toCharArray()) {
            symbols.add((int) c);
        }
        return symbols;
    }

    private static int getNextRuleId(CompressedGrammar grammar) {
        return grammar.getRules().keySet().stream()
                .max(Integer::compareTo)
                .orElse(255) + 1;
    }

    private static int getNextRuleId(Map<Integer, ?> rules) {
        return rules.keySet().stream()
                .max(Integer::compareTo)
                .orElse(255) + 1;
    }

    private static String formatSymbol(int sym) {
        if (sym == SENTINEL_START) return "'#'";
        if (sym == SENTINEL_END) return "'$'";
        if (sym < 32) return "'\\x" + Integer.toHexString(sym) + "'";
        if (sym < 127) return "'" + (char) sym + "'";
        return "R" + sym;
    }
    // Add these methods to the Recompressor class:

    /**
     * Compute frequencies of all bigrams in the grammar
     * Based on paper Section 3.2
     */
    public static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            CompressedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> frequencies = new HashMap<>();

        // Compute non-repeating bigram frequencies
        Map<Pair<Integer, Integer>, Integer> nonRepeating =
                computeNonRepeatingFrequencies(grammar, artificialTerminals);

        // Compute repeating bigram frequencies
        Map<Pair<Integer, Integer>, Integer> repeating =
                computeRepeatingFrequencies(grammar, artificialTerminals);

        // Merge results
        frequencies.putAll(nonRepeating);
        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : repeating.entrySet()) {
            frequencies.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        return frequencies;
    }

    /**
     * Compute frequencies of non-repeating bigrams (c1 != c2)
     */
    private static Map<Pair<Integer, Integer>, Integer> computeNonRepeatingFrequencies(
            CompressedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> frequencies = new HashMap<>();
        Map<Integer, CompressedGrammar.CompressedRule> rules = grammar.getRules();
        Map<Integer, RuleMetadata> metadata = grammar.getMetadata();

        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) continue;

            CompressedGrammar.CompressedRule rule = entry.getValue();
            RuleMetadata ruleMeta = metadata.get(ruleId);
            if (ruleMeta == null) continue;

            int vocc = ruleMeta.getVocc();
            if (vocc == 0) continue;

            // Build window: ρ(X')wXλ(X")
            List<Integer> window = buildWindowFromCompressed(rule, metadata, artificialTerminals);

            // Count bigrams in window
            for (int i = 0; i < window.size() - 1; i++) {
                int c1 = window.get(i);
                int c2 = window.get(i + 1);

                // Skip repeating bigrams
                if (c1 == c2) continue;

                Pair<Integer, Integer> bigram = Pair.of(c1, c2);
                frequencies.merge(bigram, vocc, Integer::sum);
            }
        }

        return frequencies;
    }

    /**
     * Compute frequencies of repeating bigrams (c,c)
     */
    private static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(
            CompressedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> frequencies = new HashMap<>();
        Map<Integer, CompressedGrammar.CompressedRule> rules = grammar.getRules();
        Map<Integer, RuleMetadata> metadata = grammar.getMetadata();

        for (Map.Entry<Integer, CompressedGrammar.CompressedRule> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) continue;

            CompressedGrammar.CompressedRule rule = entry.getValue();
            RuleMetadata ruleMeta = metadata.get(ruleId);
            if (ruleMeta == null) continue;

            int vocc = ruleMeta.getVocc();
            if (vocc == 0) continue;

            // Find blocks in the rule's extended context
            List<ExtendedBlock> blocks = findExtendedBlocks(rule, ruleMeta, metadata, artificialTerminals);

            for (ExtendedBlock block : blocks) {
                if (block.length >= 2) {
                    Pair<Integer, Integer> bigram = Pair.of(block.symbol, block.symbol);
                    int count = (block.length / 2) * vocc;
                    frequencies.merge(bigram, count, Integer::sum);
                }
            }
        }

        return frequencies;
    }

    /**
     * Build window from compressed rule
     */
    private static List<Integer> buildWindowFromCompressed(
            CompressedGrammar.CompressedRule rule,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        List<Integer> window = new ArrayList<>();

        // Add ρ(X')
        if (rule.getLeftVariable() != null && !artificialTerminals.contains(rule.getLeftVariable())) {
            RuleMetadata leftMeta = metadata.get(rule.getLeftVariable());
            if (leftMeta != null && leftMeta.getRightmostTerminal() >= 0) {
                window.add(leftMeta.getRightmostTerminal());
            }
        }

        // Add wX (expanded from blocks)
        for (CompressedGrammar.Block block : rule.getMiddleBlocks()) {
            for (int i = 0; i < block.count; i++) {
                window.add(block.symbol);
            }
        }

        // Add λ(X")
        if (rule.getRightVariable() != null && !artificialTerminals.contains(rule.getRightVariable())) {
            RuleMetadata rightMeta = metadata.get(rule.getRightVariable());
            if (rightMeta != null && rightMeta.getLeftmostTerminal() >= 0) {
                window.add(rightMeta.getLeftmostTerminal());
            }
        }

        return window;
    }

    /**
     * Find extended blocks for repeating bigram detection
     */
    private static List<ExtendedBlock> findExtendedBlocks(
            CompressedGrammar.CompressedRule rule,
            RuleMetadata ruleMeta,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        List<ExtendedBlock> blocks = new ArrayList<>();

        // Build extended context
        List<Integer> context = new ArrayList<>();

        // Add right run of left variable
        if (rule.getLeftVariable() != null && !artificialTerminals.contains(rule.getLeftVariable())) {
            RuleMetadata leftMeta = metadata.get(rule.getLeftVariable());
            if (leftMeta != null) {
                int terminal = leftMeta.getRightmostTerminal();
                int runLength = leftMeta.getRightRunLength();
                for (int i = 0; i < runLength; i++) {
                    context.add(terminal);
                }
            }
        }

        // Add middle blocks
        for (CompressedGrammar.Block block : rule.getMiddleBlocks()) {
            for (int i = 0; i < block.count; i++) {
                context.add(block.symbol);
            }
        }

        // Add left run of right variable
        if (rule.getRightVariable() != null && !artificialTerminals.contains(rule.getRightVariable())) {
            RuleMetadata rightMeta = metadata.get(rule.getRightVariable());
            if (rightMeta != null) {
                int terminal = rightMeta.getLeftmostTerminal();
                int runLength = rightMeta.getLeftRunLength();
                for (int i = 0; i < runLength; i++) {
                    context.add(terminal);
                }
            }
        }

        // Find maximal blocks
        int i = 0;
        while (i < context.size()) {
            int symbol = context.get(i);
            int j = i + 1;
            while (j < context.size() && context.get(j) == symbol) {
                j++;
            }

            int blockLength = j - i;

            // Check if this is not a prefix/suffix run
            boolean isPrefix = (i == 0 && ruleMeta.getLeftRunLength() == blockLength);
            boolean isSuffix = (j == context.size() && ruleMeta.getRightRunLength() == blockLength);

            if (!isPrefix && !isSuffix) {
                blocks.add(new ExtendedBlock(symbol, blockLength));
            }

            i = j;
        }

        return blocks;
    }

    /**
     * Get the most frequent bigram
     */
    private static Pair<Integer, Integer> getMostFrequentBigram(
            Map<Pair<Integer, Integer>, Integer> frequencies
    ) {
        Pair<Integer, Integer> mostFrequent = null;
        int maxFreq = 0;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            int freq = entry.getValue();
            if (freq > maxFreq) {
                maxFreq = freq;
                mostFrequent = entry.getKey();
            } else if (freq == maxFreq && mostFrequent != null) {
                // Tie-breaking: prefer lexicographically smaller
                Pair<Integer, Integer> candidate = entry.getKey();
                if (candidate.first < mostFrequent.first ||
                        (candidate.first == mostFrequent.first && candidate.second < mostFrequent.second)) {
                    mostFrequent = candidate;
                }
            }
        }

        return mostFrequent;
    }

    /**
     * Helper class for extended blocks
     */
    private static class ExtendedBlock {
        final int symbol;
        final int length;

        ExtendedBlock(int symbol, int length) {
            this.symbol = symbol;
            this.length = length;
        }
    }
}