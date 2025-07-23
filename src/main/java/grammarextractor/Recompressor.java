package grammarextractor;

import java.util.*;

import static grammarextractor.Main.formatSymbol;
import static grammarextractor.RuleMetadata.computeAll;

public class Recompressor {
    public static void recompress(Parser.ParsedGrammar originalGrammar,
                                  Parser.ParsedGrammar initializedGrammar) {

        Map<Integer, List<Integer>> rules = initializedGrammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = initializedGrammar.metadata();
        List<Integer> sequence = initializedGrammar.sequence();

        // Step 1: Determine the max rule ID from the original grammar
        int maxRuleId = originalGrammar.grammarRules().keySet().stream()
                .max(Integer::compareTo)
                .orElse(255); // Assume terminal < 256

        int nextRuleId = maxRuleId + 1;

        // Step 2: Recompression loop
        while (true) {
            // Get most frequent bigram (non-repeating or repeating)
            Pair<Integer, Integer> bigram = getMostFrequentBigram(
                    computeBigramFrequencies(originalGrammar, initializedGrammar));

            if (bigram == null) break; // No more compressible bigrams

            int c1 = bigram.first;
            int c2 = bigram.second;
            int newRuleId = nextRuleId++;

            System.out.printf("🔁 Replacing bigram (%s, %s) → R%d%n",
                    formatSymbol(c1), formatSymbol(c2), newRuleId);

            // Step 3: Uncross the bigram using PopInLet and PopOutLet
            popInlet(c1, c2, rules, metadata);
            popOutLet(c1, c2, rules, metadata);

            // Step 4: Replace all (c1, c2) occurrences in-place with new rule ID
            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
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

            // Step 5: Add new rule RnewRuleId → c1 c2
            rules.put(newRuleId, List.of(c1, c2));

            // Step 6: Recompute metadata for new rule
            RuleMetadata newMeta = computeAll(new Parser.ParsedGrammar(rules, sequence, metadata)).get(newRuleId);
            metadata.put(newRuleId, newMeta);
        }

        System.out.println("✅ Recompression completed.");
    }

    public static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            Parser.ParsedGrammar original,
            Parser.ParsedGrammar initializedWithMetadata
    ) {
        // Step 1: Compute both frequency types
        Map<Pair<Integer, Integer>, Integer> repeatingFreqs =
                computeRepeatingFrequencies(initializedWithMetadata);

        Map<Pair<Integer, Integer>, Integer> nonRepeatingFreqs =
                computeNonRepeatingFrequencies(initializedWithMetadata);

        // Step 2: Merge them
        Map<Pair<Integer, Integer>, Integer> merged = new HashMap<>(nonRepeatingFreqs);
        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : repeatingFreqs.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        // Step 3: Identify and remove start and end bigrams involving # and $
        List<Integer> seq = original.sequence();
        if (seq != null && !seq.isEmpty()) {
            Map<Integer, RuleMetadata> meta = original.metadata();

            int firstSymbol = seq.getFirst();
            int lastSymbol = seq.getLast();

            int hash = (int) '#';
            int dollar = (int) '$';

            int lambda = (firstSymbol < 256)
                    ? firstSymbol
                    : meta.getOrDefault(firstSymbol, dummyMeta()).getLeftmostTerminal();

            int rho = (lastSymbol < 256)
                    ? lastSymbol
                    : meta.getOrDefault(lastSymbol, dummyMeta()).getRightmostTerminal();

            Pair<Integer, Integer> startBigram = Pair.of(hash, lambda);
            Pair<Integer, Integer> endBigram = Pair.of(rho, dollar);

            if (merged.containsKey(startBigram)) {
                merged.computeIfPresent(startBigram, (k, v) -> v > 1 ? v - 1 : null);
                System.out.printf("✅ Removed start bigram (%c,%c)%n",
                        (char) hash, (char) lambda);
            }

            if (merged.containsKey(endBigram)) {
                merged.computeIfPresent(endBigram, (k, v) -> v > 1 ? v - 1 : null);
                System.out.printf("✅ Removed end bigram (%c,%c)%n",
                        (char) rho, (char) dollar);
            }
        }

        return merged;
    }


    public static Map<Pair<Integer, Integer>, Integer> computeNonRepeatingFrequencies(Parser.ParsedGrammar grammar) {
        Map<Pair<Integer, Integer>, Integer> bigramFreqs = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();
        if (grammar.sequence().size() != 1 || grammar.sequence().get(0) < 256) {
            throw new IllegalStateException("Grammar sequence must be a single root nonterminal (use initializeWithSentinelsAndRootRule)");
        }

        System.out.println("=== Starting Non-Repeating Bigram Frequency Computation ===");

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null || rhs.isEmpty()) {
                System.out.printf("Skipping rule R%d: no metadata or empty RHS%n", ruleId);
                continue;
            }

            System.out.printf("\n--- Processing Rule R%d ---\n", ruleId);
            System.out.println("RHS: " + rhs);
            int vocc = meta.getVocc();
            System.out.println("Virtual Occurrence Count (vocc): " + vocc);
            List<Integer> window = new ArrayList<>();

            // === 1. Add rightmost terminal of first symbol
            int firstSymbol = rhs.get(0);
            if (firstSymbol >= 256) {
                RuleMetadata firstMeta = metadata.getOrDefault(firstSymbol, dummyMeta());
                int rightmost = firstMeta.getRightmostTerminal();
                System.out.printf("Rightmost terminal of first symbol R%d: %s%n", firstSymbol,
                        rightmost >= 0 ? rightmost : "None");
                if (rightmost >= 0) {
                    window.add(rightmost);
                }
            }
            else window.add(firstSymbol);

            // === 2. Add middle symbols
            if (rhs.size() > 2) {
                System.out.print("Middle symbols (w(X)): ");
                for (int i = 1; i < rhs.size() - 1; i++) {
                    int sym = rhs.get(i);
                    window.add(sym);
                    System.out.print(sym + " ");
                }
                System.out.println();
            }

            // === 3. Add leftmost terminal of last symbol
            int lastSymbol = rhs.get(rhs.size() - 1);
            if (lastSymbol >= 256) {
                RuleMetadata lastMeta = metadata.getOrDefault(lastSymbol, dummyMeta());
                int leftmost = lastMeta.getLeftmostTerminal();
                System.out.printf("Leftmost terminal of last symbol R%d: %s%n", lastSymbol,
                        leftmost >= 0 ? leftmost : "None");
                if (leftmost >= 0) {
                    window.add(leftmost);
                }
            }
            else window.add(lastSymbol);

            // === 4. Count terminal-terminal bigrams in the window
            System.out.println("Built window: " + window);
            for (int i = 0; i < window.size()-1; i++) {
                int a = window.get(i);
                int b = window.get(i + 1);
                if (a < 256 && b < 256 && a != b) {
                    Pair<Integer, Integer> bigram = Pair.of(a, b);
                    bigramFreqs.merge(bigram, vocc, Integer::sum);
                    System.out.printf("Added bigram (%c,%c) with count += %d%n", a, b, vocc);
                } else {
                    if (a >= 256 || b >= 256) {
                        System.out.printf("Skipping non-terminal bigram (%d,%d)%n", a, b);
                    } else if (a == b) {
                        System.out.printf("Skipping repeating bigram (%c,%c)%n", a, b);
                    }
                }
            }
        }

        System.out.println("\n=== Completed Non-Repeating Bigram Frequency Computation ===");
        return bigramFreqs;
    }
    public static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(Parser.ParsedGrammar grammar) {
        Map<Pair<Integer, Integer>, Integer> repeatingFreqs = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();
        Set<String> assignedBlocks = new HashSet<>();

        System.out.println("=== Starting Repeating Bigram Frequency Computation (Safe Mode) ===");

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null || rhs.isEmpty()) continue;

            int vocc = meta.getVocc();
            int ruleLen = meta.getLength();
            if (vocc == 0 || ruleLen == 0) continue;

            List<Integer> middle = new ArrayList<>();
            int rightSym = -1, rightLen = 0;
            int leftSym = -1, leftLen = 0;

            // Right run of the first symbol
            int first = rhs.get(0);
            if (first >= 256) {
                RuleMetadata firstMeta = metadata.getOrDefault(first, dummyMeta());
                if (!firstMeta.isSingleBlock()) {
                    rightSym = firstMeta.getRightmostTerminal();
                    rightLen = firstMeta.getRightRunLength();
                }
            } else {
                rightSym = first;
                rightLen = 1;
            }

            // Left run of the last symbol
            int last = rhs.get(rhs.size() - 1);
            if (last >= 256) {
                RuleMetadata lastMeta = metadata.getOrDefault(last, dummyMeta());
                if (!lastMeta.isSingleBlock()) {
                    leftSym = lastMeta.getLeftmostTerminal();
                    leftLen = lastMeta.getLeftRunLength();
                }
            } else {
                leftSym = last;
                leftLen = 1;
            }

            // Collect middle symbols between first and last
            for (int i = 1; i < rhs.size() - 1; i++) {
                int sym = rhs.get(i);
                if (sym < 256) middle.add(sym);
            }

            // Consider full generated block
            if (rightSym != -1 && rightSym == leftSym) {
                int runChar = rightSym;
                int totalLen = rightLen + middle.size() + leftLen;
                if (totalLen >= 2) {
                    int leftContext = (middle.isEmpty() && rightLen > 0) ? -1 : (middle.isEmpty() ? leftSym : middle.get(0));
                    int rightContext = (middle.isEmpty() && leftLen > 0) ? -1 : (middle.isEmpty() ? rightSym : middle.get(middle.size() - 1));
                    String runKey = runChar + "|" + totalLen + "|" + leftContext + "|" + rightContext;
                    if (assignedBlocks.contains(runKey)) {
                        System.out.printf("⚠ Skipped duplicate run: %s%n", runKey);
                        continue;
                    }

                    boolean witness = false;
                    if (rightLen <= 1 && middle.size() + leftLen + 1 < ruleLen) {
                        witness = true;
                    } else if (leftLen <= 1 && middle.size() + rightLen + 1 < ruleLen) {
                        witness = true;
                    }

                    if (witness) {
                        int freq = (totalLen / 2) * vocc;
                        assignedBlocks.add(runKey);
                        Pair<Integer, Integer> bigram = Pair.of(runChar, runChar);
                        repeatingFreqs.merge(bigram, freq, Integer::sum);
                        System.out.printf("✅ Counted (%c,%c) += %d [run %d × vocc %d] from R%d%n",
                                (char) runChar, (char) runChar, freq, totalLen, vocc, ruleId);
                    }
                }
            }
        }

        System.out.println("=== Completed Repeating Bigram Frequency Computation ===");
        return repeatingFreqs;
    }


    public static Parser.ParsedGrammar initializeWithSentinelsAndRootRule(Parser.ParsedGrammar original) {
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

        return new Parser.ParsedGrammar(newRules, newSeq, null); // metadata will be recomputed later
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
    public static void popInlet(int c1, int c2, Map<Integer, List<Integer>> rules, Map<Integer, RuleMetadata> metadata) {

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            List<Integer> rhs = entry.getValue();
            List<Integer> updated = new ArrayList<>();

            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);

                if (sym >= 256) { // It's a nonterminal (variable)
                    RuleMetadata meta = metadata.get(sym);
                    if (meta == null) {
                        updated.add(sym);
                        continue;
                    }

                    // Push c2 to the left if sym is not the first symbol and starts with c2
                    if (i > 0 && meta.getLeftmostTerminal() == c2) {
                        updated.add(c2);
                    }

                    updated.add(sym);

                    // Push c1 to the right if sym is not the last symbol and ends with c1
                    if (i < rhs.size() - 1 && meta.getRightmostTerminal() == c1) {
                        updated.add(c1);
                    }
                } else {
                    // It's a terminal, just add it
                    updated.add(sym);
                }
            }

            entry.setValue(updated);
        }
    }

    public static void popOutLet(int c1, int c2,
                                 Map<Integer, List<Integer>> rules,
                                 Map<Integer, RuleMetadata> metadata) {
        Set<Integer> nullRules = new HashSet<>();

        // First pass: remove c2 from head and c1 from tail
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> body = new ArrayList<>(entry.getValue());

            // Remove c2 from the start
            if (!body.isEmpty() && body.get(0) == c2) {
                body.remove(0);
            }

            // Remove c1 from the end
            if (!body.isEmpty() && body.get(body.size() - 1) == c1) {
                body.remove(body.size() - 1);
            }

            if (body.isEmpty()) {
                nullRules.add(ruleId);
            } else {
                rules.put(ruleId, body);
            }
        }

        // Second pass: remove references to null rules in all remaining rules
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (nullRules.contains(ruleId)) continue;

            List<Integer> original = entry.getValue();
            List<Integer> filtered = new ArrayList<>();
            for (int sym : original) {
                if (!nullRules.contains(sym)) {
                    filtered.add(sym);
                }
            }
            rules.put(ruleId, filtered);
        }

        // Third pass: remove null rules from the grammar
        for (int nullRule : nullRules) {
            rules.remove(nullRule);
        }
    }
    public static Pair<Integer, Integer> getMostFrequentBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
        Pair<Integer, Integer> mostFrequent = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            int count = entry.getValue();
            if (count > maxCount) {
                maxCount = count;
                mostFrequent = entry.getKey();
            }
        }

        return mostFrequent;
    }


    private static RuleMetadata dummyMeta() {
        return new RuleMetadata(0, 0, -1, -1, false, 0, 0);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
