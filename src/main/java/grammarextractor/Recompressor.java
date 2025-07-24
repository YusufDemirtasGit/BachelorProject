package grammarextractor;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static grammarextractor.Main.formatSymbol;


public class Recompressor {


    public static void recompressNTimes(Parser.ParsedGrammar originalGrammar, int maxPasses, boolean verbose) {
        // Step 1: Initialize grammar with sentinels and metadata
        InitializedGrammar init = initializeWithSentinelsAndRootRule(originalGrammar);
        Parser.ParsedGrammar initialized = init.grammar;
        Set<Integer> artificialTerminals = init.artificialTerminals;

        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(initialized, artificialTerminals);
        Parser.ParsedGrammar workingGrammar = new Parser.ParsedGrammar(
                initialized.grammarRules(),
                initialized.sequence(),
                metadata
        );

        Map<Integer, List<Integer>> rules = workingGrammar.grammarRules();
        List<Integer> sequence = workingGrammar.sequence();

        String before = Decompressor.decompress(workingGrammar);
        int originalLength = before.length();
        if (verbose) {
            System.out.println("=== 🧵 Decompressed BEFORE All Passes ===\n" + before);
        }

        for (int pass = 1; pass <= maxPasses; pass++) {
            if (verbose) {
                System.out.printf("\n=== 🔁 Recompression Pass %d ===\n", pass);
            }

            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(workingGrammar, artificialTerminals);

            Pair<Integer, Integer> bigram = getMostFrequentBigram(frequencies);
            if (bigram == null || frequencies.getOrDefault(bigram, 0) <= 1) {
                if (verbose) System.out.println("✅ No more compressible bigrams.");
                break;
            }

            int c1 = bigram.first;
            int c2 = bigram.second;
            int maxRuleId = rules.keySet().stream().max(Integer::compareTo).orElse(255);
            int newRuleId = maxRuleId + 1;

            if (verbose) {
                System.out.printf("📌 Most frequent bigram: (%s, %s) → R%d [%d occurrences]%n",
                        formatSymbol(c1), formatSymbol(c2), newRuleId, frequencies.get(bigram));
            }

            popInlet(c1, c2, rules, metadata, artificialTerminals);
            popOutLet(c1, c2, rules, metadata, artificialTerminals);
            replaceBigramInRules(c1, c2, newRuleId, rules, artificialTerminals);

            rules.put(newRuleId, List.of(c1, c2));
            artificialTerminals.add(newRuleId);
            if (verbose) System.out.printf("🔒 Marked R%d as artificial terminal\n", newRuleId);

            metadata = RuleMetadata.computeAll(
                    new Parser.ParsedGrammar(rules, sequence, metadata),
                    artificialTerminals
            );
            workingGrammar = new Parser.ParsedGrammar(rules, sequence, metadata);

            RuleMetadata newMeta = metadata.get(newRuleId);
            if (verbose && newMeta != null) {
                if(verbose)System.out.printf("🧠 Metadata for R%d → vocc=%d, length=%d, left=%s, right=%s, isSB=%b, runL=%d, runR=%d%n",
                        newRuleId, newMeta.getVocc(), newMeta.getLength(),
                        formatSymbol(newMeta.getLeftmostTerminal()),
                        formatSymbol(newMeta.getRightmostTerminal()),
                        newMeta.isSingleBlock(),
                        newMeta.getLeftRunLength(),
                        newMeta.getRightRunLength());
            }

            // Remove now redundant rules for extra space saving
            workingGrammar = finalOptimizeGrammar(rules, sequence, metadata, artificialTerminals);
            rules = workingGrammar.grammarRules();
            metadata = workingGrammar.metadata();
            workingGrammar = new Parser.ParsedGrammar(rules, sequence, metadata);

            String after = Decompressor.decompress(workingGrammar);
            if (verbose) {
                if(verbose)System.out.println("\n=== 🔁 Roundtrip Check ===");
            }
            if (before.equals(after)) {
                if (verbose) System.out.println("✔ Roundtrip OK.");
            } else {
                if(verbose)System.err.println("❌ Roundtrip mismatch detected!");
                int mismatch = -1;
                for (int i = 0; i < Math.min(before.length(), after.length()); i++) {
                    if (before.charAt(i) != after.charAt(i)) {
                        mismatch = i;
                        break;
                    }
                }
                if (mismatch >= 0) {
                    if(verbose)System.err.printf("🔍 First mismatch at index %d: '%c' vs '%c'%n",
                            mismatch, before.charAt(mismatch), after.charAt(mismatch));
                } else if (before.length() != after.length()) {
                    if(verbose)System.err.printf("🔍 Length mismatch: %d vs %d%n",
                            before.length(), after.length());
                }
                if (verbose) {
                    if(verbose)System.out.println("\n📦 Current Grammar State:");
                    Parser.printGrammar(workingGrammar);
                }
                break;
            }

            before = after;
        }

        int totalRules = rules.size();
        int rhsSymbols = rules.values().stream().mapToInt(List::size).sum() + sequence.size();
        double compressionRatio = (100.0 * (originalLength - rhsSymbols)) / originalLength;

        if (verbose) {
            if(verbose)System.out.println("\n=== 📦 Final Grammar After " + maxPasses + " Passes ===");
            Parser.printGrammar(workingGrammar);

            if(verbose)System.out.println("\n=== 📊 Compression Stats ===");
            if(verbose)System.out.println("Original length (decompressed): " + originalLength + " symbols");
            if(verbose)System.out.println("Final grammar rules: " + totalRules);
            if(verbose)System.out.println("Final total RHS symbols: " + rhsSymbols);
            if(verbose)System.out.printf("Space saved (approx.): %.2f%%\n", compressionRatio);
        }

        // =======================
        // Dump everything to output.txt
        // =======================
        try (FileWriter writer = new FileWriter("output.txt")) {
            writer.write("=== 📦 Final Grammar After " + maxPasses + " Passes ===\n");

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, List<Integer>> entry : workingGrammar.grammarRules().entrySet()) {
                sb.append("R").append(entry.getKey()).append(": ");
                String rhs = entry.getValue().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
                sb.append(rhs).append("\n");
            }
            sb.append("SEQ:");
            for (int i = 0; i < workingGrammar.sequence().size(); i++) {
                sb.append(workingGrammar.sequence().get(i));
                if (i != workingGrammar.sequence().size() - 1) sb.append(",");
            }
            sb.append("\n\n");

            writer.write(sb.toString());
            if (verbose) System.out.println("💾 Final grammar and stats written to output.txt");
        } catch (IOException e) {
            if(verbose)System.err.println("❌ Failed to write output.txt: " + e.getMessage());
        }
    }




    public static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            Parser.ParsedGrammar working,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> repFreqs =
                computeRepeatingFrequencies(working, artificialTerminals);
        Map<Pair<Integer, Integer>, Integer> nonRepFreqs =
                computeNonRepeatingFrequencies(working, artificialTerminals);

        Map<Pair<Integer, Integer>, Integer> merged = new HashMap<>(nonRepFreqs);
        for (Map.Entry<Pair<Integer, Integer>, Integer> e : repFreqs.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return merged;
    }


    public static Map<Pair<Integer, Integer>, Integer> computeNonRepeatingFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> bigramFreqs = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();
        boolean verbose = false;
        if (grammar.sequence().size() != 1 || grammar.sequence().get(0) < 256) {
            throw new IllegalStateException("Grammar sequence must be a single root nonterminal");
        }

        if(verbose)System.out.println("=== Starting Non-Repeating Bigram Frequency Computation ===");

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();

            if (artificialTerminals.contains(ruleId)) {
                if(verbose)System.out.printf("Skipping rule R%d (treated as artificial terminal)%n", ruleId);
                continue;
            }

            List<Integer> rhs = entry.getValue();
            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null || rhs.isEmpty()) {
                if(verbose) System.out.printf("Skipping rule R%d: no metadata or empty RHS%n", ruleId);
                continue;
            }

            if(verbose)System.out.printf("\n--- Processing Rule R%d ---\n", ruleId);
            if(verbose)System.out.println("RHS: " + rhs);
            int vocc = meta.getVocc();
            if(verbose)System.out.println("Virtual Occurrence Count (vocc): " + vocc);

            List<Integer> window = new ArrayList<>();

            // Process first symbol
            int firstSymbol = rhs.get(0);
            if (firstSymbol < 256) {
                // It's a terminal
                window.add(firstSymbol);
            } else if (artificialTerminals.contains(firstSymbol)) {
                // It's an artificial terminal - add it as-is
                window.add(firstSymbol);
            } else {
                // It's a regular non-terminal - get its rightmost terminal
                RuleMetadata firstMeta = metadata.get(firstSymbol);
                if (firstMeta != null && firstMeta.getRightmostTerminal() >= 0) {
                    window.add(firstMeta.getRightmostTerminal());
                }
            }

            // Process middle symbols
            if (rhs.size() > 2) {
                if(verbose)System.out.print("Middle symbols (w(X)): ");
                for (int i = 1; i < rhs.size() - 1; i++) {
                    int sym = rhs.get(i);
                    window.add(sym);
                    if(verbose)System.out.print(sym + " ");
                }
                if(verbose)System.out.println();
            }

            // Process last symbol (if different from first)
            if (rhs.size() > 1) {
                int lastSymbol = rhs.get(rhs.size() - 1);
                if (lastSymbol < 256) {
                    // It's a terminal
                    window.add(lastSymbol);
                } else if (artificialTerminals.contains(lastSymbol)) {
                    // It's an artificial terminal - add it as-is
                    window.add(lastSymbol);
                } else {
                    // It's a regular non-terminal - get its leftmost terminal
                    RuleMetadata lastMeta = metadata.get(lastSymbol);
                    if (lastMeta != null && lastMeta.getLeftmostTerminal() >= 0) {
                        window.add(lastMeta.getLeftmostTerminal());
                    }
                }
            }

            // Count all bigrams in the window
            if(verbose)System.out.println("Built window: " + window);
            for (int i = 0; i < window.size() - 1; i++) {
                int a = window.get(i);
                int b = window.get(i + 1);

                // Skip repeating bigrams (they're handled separately)
                if (a == b) {
                    if(verbose)System.out.printf("Skipping repeating bigram (%s,%s)%n",
                            formatSymbol(a), formatSymbol(b));
                    continue;
                }

                Pair<Integer, Integer> bigram = Pair.of(a, b);
                bigramFreqs.merge(bigram, vocc, Integer::sum);
                if(verbose)System.out.printf("Added bigram (%s,%s) with count += %d%n",
                        formatSymbol(a), formatSymbol(b), vocc);
            }
        }

        if(verbose)System.out.println("\n=== Completed Non-Repeating Bigram Frequency Computation ===");
        return bigramFreqs;
    }


    public static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals
    ) {
        Map<Pair<Integer, Integer>, Integer> freqMap = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();
        boolean verbose = false;
        if(verbose)System.out.println("=== Starting Repeating Bigram Frequency Computation ===");

        for (int ruleId : rules.keySet()) {
            if (artificialTerminals.contains(ruleId)) {
                if(verbose)System.out.printf("Skipping rule R%d (artificial terminal)%n", ruleId);
                continue;
            }

            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null) continue;

            int vocc = meta.getVocc();
            int len = meta.getLength();
            if (len < 2) continue;

            List<Integer> rhs = rules.get(ruleId);
            if (rhs == null || rhs.isEmpty()) continue;

            List<Integer> context = new ArrayList<>();

            // Build context considering artificial terminals
            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);

                if (sym < 256) {
                    // Terminal - add directly
                    context.add(sym);
                } else if (artificialTerminals.contains(sym)) {
                    // Artificial terminal - add as atomic
                    context.add(sym);
                } else {
                    // Regular non-terminal - expand based on position
                    RuleMetadata subMeta = metadata.get(sym);
                    if (subMeta == null) continue;

                    if (i == 0) {
                        // First symbol - add right run
                        int rlen = subMeta.getRightRunLength();
                        int term = subMeta.getRightmostTerminal();
                        if (term >= 0) {
                            for (int j = 0; j < rlen; j++) context.add(term);
                        }
                    } else if (i == rhs.size() - 1) {
                        // Last symbol - add left run
                        int llen = subMeta.getLeftRunLength();
                        int term = subMeta.getLeftmostTerminal();
                        if (term >= 0) {
                            for (int j = 0; j < llen; j++) context.add(term);
                        }
                    } else {
                        // Middle symbol - add entire length
                        int subLen = subMeta.getLength();
                        int subTerm = subMeta.getLeftmostTerminal();
                        if (subTerm >= 0) {
                            for (int j = 0; j < subLen; j++) {
                                context.add(subTerm);
                            }
                        }
                    }
                }
            }

            // Scan for repeating bigrams in context
            for (int i = 0; i < context.size(); ) {
                int c = context.get(i);
                int j = i + 1;
                while (j < context.size() && context.get(j) == c) j++;

                int d = j - i;
                if (d >= 2) {
                    // Check if this is not a prefix/suffix run
                    boolean isPrefix = (i == 0 && meta.getLeftRunLength() == d);
                    boolean isSuffix = (j == context.size() && meta.getRightRunLength() == d);
                    if (!isPrefix && !isSuffix) {
                        Pair<Integer, Integer> bigram = Pair.of(c, c);
                        int count = (d / 2) * vocc;
                        freqMap.merge(bigram, count, Integer::sum);
                        if(verbose)System.out.printf("Added repeating bigram (%s,%s) with count += %d%n",
                                formatSymbol(c), formatSymbol(c), count);
                    }
                }

                i = j;
            }
        }

        if(verbose)System.out.println("\n=== Completed Repeating Bigram Frequency Computation ===");
        return freqMap;
    }




    public static class InitializedGrammar {
        public final Parser.ParsedGrammar grammar;
        public final Set<Integer> artificialTerminals;

        public InitializedGrammar(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
            this.grammar = grammar;
            this.artificialTerminals = artificialTerminals;
        }
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
                new Parser.ParsedGrammar(newRules, newSeq, null),
                artificialTerminals
        );
    }
    public static Pair<Integer, Integer> getMostOccurringBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
        if (frequencies == null || frequencies.isEmpty()) {
            return null;
        }

        Pair<Integer, Integer> maxBigram = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                maxBigram = entry.getKey();
            }
        }

        return maxBigram;
    }
    public static void popInlet(int c1, int c2,
                                Map<Integer, List<Integer>> rules,
                                Map<Integer, RuleMetadata> metadata,
                                Set<Integer> artificialTerminals) {
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            if (artificialTerminals.contains(entry.getKey())) {
                continue; // Skip artificial terminals
            }

            List<Integer> rhs = entry.getValue();
            List<Integer> updated = new ArrayList<>();

            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);

                if (sym >= 256 && !artificialTerminals.contains(sym)) {
                    // It's a regular non-terminal (not artificial)
                    RuleMetadata meta = metadata.get(sym);
                    if (meta == null) {
                        updated.add(sym);
                        continue;
                    }

                    // Push c2 to the left if needed
                    if (i > 0 && meta.getLeftmostTerminal() == c2) {
                        updated.add(c2);
                    }

                    updated.add(sym);

                    // Push c1 to the right if needed
                    if (i < rhs.size() - 1 && meta.getRightmostTerminal() == c1) {
                        updated.add(c1);
                    }
                } else {
                    // It's a terminal or artificial terminal
                    updated.add(sym);
                }
            }

            entry.setValue(updated);
        }
    }

    public static void popOutLet(int c1, int c2,
                                 Map<Integer, List<Integer>> rules,
                                 Map<Integer, RuleMetadata> metadata,
                                 Set<Integer> artificialTerminals) {
        Set<Integer> nullRules = new HashSet<>();
        boolean verbose = false;
        // First pass: remove c2 from head and c1 from tail
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();

            // Skip artificial terminals - they should not be modified
            if (artificialTerminals.contains(ruleId)) {
                if(verbose)System.out.printf("Skipping artificial terminal R%d in popOutLet%n", ruleId);
                continue;
            }

            List<Integer> body = new ArrayList<>(entry.getValue());

            // Remove c2 from the start
            if (!body.isEmpty() && body.get(0) == c2) {
                body.remove(0);
                if(verbose)System.out.printf("Removed %s from start of R%d%n", formatSymbol(c2), ruleId);
            }

            // Remove c1 from the end
            if (!body.isEmpty() && body.get(body.size() - 1) == c1) {
                body.remove(body.size() - 1);
                if(verbose)System.out.printf("Removed %s from end of R%d%n", formatSymbol(c1), ruleId);
            }

            if (body.isEmpty()) {
                nullRules.add(ruleId);
                if(verbose)System.out.printf("Rule R%d became null%n", ruleId);
            } else {
                rules.put(ruleId, body);
            }
        }

        // Second pass: remove references to null rules in all remaining rules
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();

            // Skip null rules and artificial terminals
            if (nullRules.contains(ruleId) || artificialTerminals.contains(ruleId)) {
                continue;
            }

            List<Integer> original = entry.getValue();
            List<Integer> filtered = new ArrayList<>();
            boolean changed = false;

            for (int sym : original) {
                if (!nullRules.contains(sym)) {
                    filtered.add(sym);
                } else {
                    changed = true;
                    if(verbose)System.out.printf("Removed null rule R%d from R%d%n", sym, ruleId);
                }
            }

            if (changed) {
                rules.put(ruleId, filtered);
            }
        }

        // Third pass: remove null rules from the grammar
        for (int nullRule : nullRules) {
            rules.remove(nullRule);
            if(verbose)System.out.printf("Removed null rule R%d from grammar%n", nullRule);
        }

        // Important: Do NOT remove artificial terminals from the grammar
        // even if they would become null (which shouldn't happen anyway)
    }
    public static void replaceBigramInRules(
            int c1,
            int c2,
            int newRuleId,
            Map<Integer, List<Integer>> rules,
            Set<Integer> artificialTerminals
    ) {
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (artificialTerminals.contains(ruleId)) {
                continue; // do not touch artificial terminals
            }

            List<Integer> rhs = entry.getValue();
            List<Integer> updated = new ArrayList<>();

            for (int i = 0; i < rhs.size(); ) {
                if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                    updated.add(newRuleId);
                    i += 2;
                } else {
                    updated.add(rhs.get(i));
                    i++;
                }
            }

            entry.setValue(updated);
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
    private static boolean removeRedundantRules(Map<Integer, List<Integer>> rules, List<Integer> sequence) {
        boolean changed = false;
        Set<Integer> toRemove = new HashSet<>();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            if (rhs.size() == 1) {
                int replacement = rhs.get(0);

                // Replace in other rules
                for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
                    if (e.getKey() == ruleId) continue;
                    List<Integer> updated = new ArrayList<>();
                    boolean modified = false;
                    for (int sym : e.getValue()) {
                        if (sym == ruleId) {
                            updated.add(replacement);
                            modified = true;
                        } else {
                            updated.add(sym);
                        }
                    }
                    if (modified) {
                        e.setValue(updated);
                        changed = true;
                    }
                }

                // Replace in sequence
                List<Integer> updatedSeq = new ArrayList<>();
                boolean seqChanged = false;
                for (int sym : sequence) {
                    if (sym == ruleId) {
                        updatedSeq.add(replacement);
                        seqChanged = true;
                    } else {
                        updatedSeq.add(sym);
                    }
                }
                if (seqChanged) {
                    sequence.clear();
                    sequence.addAll(updatedSeq);
                    changed = true;
                }

                toRemove.add(ruleId);
            }
        }

        for (int id : toRemove) {
            rules.remove(id);
            changed = true;
        }

        return changed;
    }
    private static boolean reuseExistingBigrams(Map<Integer, List<Integer>> rules) {
        boolean changed = false;

        // 1. Build map of all existing 2-symbol rules
        Map<List<Integer>, Integer> bigramToRule = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            List<Integer> rhs = entry.getValue();
            if (rhs.size() == 2) {
                bigramToRule.put(rhs, entry.getKey());
            }
        }

        // 2. Replace bigrams in longer rules
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            if (rhs.size() <= 2) continue;

            List<Integer> updated = new ArrayList<>();
            int i = 0;
            boolean modified = false;
            while (i < rhs.size()) {
                if (i + 1 < rhs.size()) {
                    List<Integer> pair = List.of(rhs.get(i), rhs.get(i + 1));
                    Integer existingRule = bigramToRule.get(pair);
                    if (existingRule != null) {
                        updated.add(existingRule);
                        i += 2;
                        modified = true;
                        continue;
                    }
                }
                updated.add(rhs.get(i));
                i++;
            }

            if (modified) {
                rules.put(ruleId, updated);
                changed = true;
            }
        }

        return changed;
    }
    public static Parser.ParsedGrammar finalOptimizeGrammar(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence,
            Map<Integer, RuleMetadata> metadata,
            Set<Integer> artificialTerminals
    ) {
        boolean changed;
        do {
            changed = false;
            changed |= removeRedundantRules(rules, sequence);
            changed |= reuseExistingBigrams(rules);
        } while (changed);

        // 3. Recompute metadata
        Map<Integer, RuleMetadata> newMeta = RuleMetadata.computeAll(
                new Parser.ParsedGrammar(rules, sequence, metadata),
                artificialTerminals
        );
        return new Parser.ParsedGrammar(rules, sequence, newMeta);
    }


    private static RuleMetadata dummyMeta() {
        return new RuleMetadata(0, 0, -1, -1, false, 0, 0);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
