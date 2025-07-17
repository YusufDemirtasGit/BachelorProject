package grammarextractor;

import java.util.*;

public class Recompressor {

    public static void recompress(Parser.ParsedGrammar grammar) {
        Map<Integer, Parser.GrammarRule<Integer, Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();

        boolean changed;
        do {
            Map<Integer, Map<Integer, Integer>> usageMatrix = buildUsageMatrix(rules);
            Map<Integer, Set<Integer>> reverseUsageMap = buildReverseUsageMap(usageMatrix);

            popInlet(rules, reverseUsageMap);

            RuleMetadata.recomputeLengths(rules);
            Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(rules, reverseUsageMap);

            Map<Pair<Integer, Integer>, Integer> bigramFreqs = computeBigramFrequencies(rules, sequence, metadata);
            changed = applyBigramReplacement(grammar, rules, sequence, bigramFreqs);

            RuleMetadata.recomputeLengths(rules);

        } while (changed);

        pruneUnusedRules(grammar.grammarRules(), grammar.sequence());
    }

    public static void pruneUnusedRules(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules, List<Integer> sequence) {
        Set<Integer> reachable = new HashSet<>();
        for (int symbol : sequence) {
            dfs(symbol, rules, reachable);
        }
        rules.keySet().removeIf(ruleId -> !reachable.contains(ruleId));
    }

    private static void dfs(int symbol, Map<Integer, Parser.GrammarRule<Integer, Integer>> rules, Set<Integer> visited) {
        if (symbol < 256 || visited.contains(symbol)) return;
        visited.add(symbol);
        Parser.GrammarRule<Integer, Integer> rule = rules.get(symbol);
        if (rule != null) {
            for (int rhsSym : rule.rhs) {
                dfs(rhsSym, rules, visited);
            }
        }
    }

    public static Map<Integer, Map<Integer, Integer>> buildUsageMatrix(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        Map<Integer, Map<Integer, Integer>> usageMatrix = new HashMap<>();
        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int userRule = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();
            Map<Integer, Integer> usage = new HashMap<>();
            for (int sym : rule.rhs) {
                if (sym >= 256) {
                    usage.put(sym, usage.getOrDefault(sym, 0) + 1);
                }
            }
            usageMatrix.put(userRule, usage);
            System.out.println("UsageMatrix Entry: R" + userRule + " uses: " + usage);
        }
        return usageMatrix;
    }

    public static Map<Integer, Set<Integer>> buildReverseUsageMap(Map<Integer, Map<Integer, Integer>> usageMatrix) {
        Map<Integer, Set<Integer>> reverseMap = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> userEntry : usageMatrix.entrySet()) {
            int user = userEntry.getKey();
            for (int used : userEntry.getValue().keySet()) {
                reverseMap.computeIfAbsent(used, k -> new HashSet<>()).add(user);
            }
        }
        System.out.println("Reverse usage map constructed: " + reverseMap);
        return reverseMap;
    }

    public static void popInlet(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
                                Map<Integer, Set<Integer>> reverseUsageMap) {
        boolean verbose = true;
        if (verbose) System.out.println("\n========== Starting PopInlet Pass ==========");

        int nextRuleId = Collections.max(rules.keySet()) + 1;
        List<Integer> ruleIds = new ArrayList<>(rules.keySet());

        for (Integer targetId : ruleIds) {
            Parser.GrammarRule<Integer, Integer> targetRule = rules.get(targetId);
            if (targetRule == null) continue;

            Set<Integer> parents = reverseUsageMap.getOrDefault(targetId, Set.of());
            if (parents.isEmpty()) {
                if (verbose) System.out.println("⏭ Rule R" + targetId + " is unused. Skipping.");
                continue;
            }

            if (verbose) {
                System.out.println("\n? Checking target rule: R" + targetId);
                System.out.println("  ? Used by rules: " + parents);
            }

            Integer inletCandidate = null;
            boolean valid = true;

            for (int parentId : parents) {
                Parser.GrammarRule<Integer, Integer> parent = rules.get(parentId);
                if (parent == null) continue;

                List<Integer> rhs = parent.rhs;
                if (rhs.size() >= 2 && rhs.get(rhs.size() - 1).equals(targetId)) {
                    int preceding = rhs.get(rhs.size() - 2);
                    if (inletCandidate == null) {
                        inletCandidate = preceding;
                    } else if (!inletCandidate.equals(preceding)) {
                        if (verbose) System.out.println("  ✘ Inconsistent inlet: " + preceding + " ≠ " + inletCandidate);
                        valid = false;
                        break;
                    }
                } else {
                    if (verbose) System.out.println("  ✘ Rule R" + parentId + " does not end with R" + targetId);
                    valid = false;
                    break;
                }
            }

            if (!valid || inletCandidate == null) {
                if (verbose) System.out.println("  ✘ No unique inlet found. Skipping R" + targetId);
                continue;
            }

            List<Integer> newRHS = new ArrayList<>();
            newRHS.add(inletCandidate);
            newRHS.addAll(targetRule.rhs);

            int newRuleId = nextRuleId++;
            int newLen = computeLength(newRHS, rules);

            Parser.GrammarRule<Integer, Integer> newRule = new Parser.GrammarRule<>(newRuleId, newRHS, newLen);
            rules.put(newRuleId, newRule);

            if (verbose) System.out.println("  ✅ Created R" + newRuleId + ": " + newRHS);

            for (int parentId : parents) {
                Parser.GrammarRule<Integer, Integer> parent = rules.get(parentId);
                if (parent == null) continue;

                List<Integer> rhs = parent.rhs;
                if (rhs.size() >= 2 &&
                        rhs.get(rhs.size() - 2).equals(inletCandidate) &&
                        rhs.get(rhs.size() - 1).equals(targetId)) {

                    List<Integer> updatedRHS = new ArrayList<>(rhs.subList(0, rhs.size() - 2));
                    updatedRHS.add(newRuleId);

                    int newParentLen = computeLength(updatedRHS, rules);
                    rules.put(parentId, new Parser.GrammarRule<>(parentId, updatedRHS, newParentLen));

                    if (verbose) System.out.println("    ↪ Updated R" + parentId + ": " + updatedRHS);
                }
            }

            rules.remove(targetId);
            if (verbose) System.out.println("    ❌ Removed R" + targetId);
        }

        if (verbose) System.out.println("========== PopInlet Pass Complete ==========\n");
    }
    public static void popOutlet(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
                                 Map<Integer, Set<Integer>> reverseUsageMap) {
        boolean verbose = true; // Set to true to enable detailed debug logging

        if (verbose) System.out.println("\n========== Starting PopOutlet Pass ==========");

        boolean changed = false;

        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();
            List<Integer> rhs = rule.rhs;

            if (rhs.isEmpty()) continue;

            // -------- Attempt Pop from Front --------
            int first = rhs.getFirst();
            if (first >= 256 && reverseUsageMap.getOrDefault(first, Set.of()).size() == 1) {
                Parser.GrammarRule<Integer, Integer> pulled = rules.get(first);
                if (pulled != null) {
                    List<Integer> newRHS = new ArrayList<>(pulled.rhs);
                    newRHS.addAll(rhs.subList(1, rhs.size()));

                    rule.rhs = newRHS;
                    rule.length = computeLength(newRHS, rules);
                    changed = true;

                    if (verbose)
                        System.out.printf("? PopOutlet on R%d: pulled R%d from front → %s%n", ruleId, first, newRHS);
                }
            }

            // -------- Attempt Pop from Back --------
            int lastIdx = rhs.size() - 1;
            int last = rhs.get(lastIdx);
            if (last >= 256 && reverseUsageMap.getOrDefault(last, Set.of()).size() == 1) {
                Parser.GrammarRule<Integer, Integer> pulled = rules.get(last);
                if (pulled != null) {
                    List<Integer> newRHS = new ArrayList<>(rhs.subList(0, lastIdx));
                    newRHS.addAll(pulled.rhs);

                    rule.rhs = newRHS;
                    rule.length = computeLength(newRHS, rules);
                    changed = true;

                    if (verbose)
                        System.out.printf("? PopOutlet on R%d: pulled R%d from back → %s%n", ruleId, last, newRHS);
                }
            }
        }

        if (!changed && verbose) {
            System.out.println("? No PopOutlet rules applied.");
        }

        if (verbose) System.out.println("========== PopOutlet Pass Complete ==========\n");
    }

    private static int computeLength(List<Integer> rhs, Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        int total = 0;
        for (int symbol : rhs) {
            if (symbol < 256) {
                total++;
            } else {
                Parser.GrammarRule<Integer, Integer> rule = rules.get(symbol);
                if (rule != null) {
                    total += rule.length;
                } else {
                    System.err.println("Tried to get length of undefined rule R" + symbol);
                    total++;
                }
            }
        }
        return total;
    }

    private static boolean applyBigramReplacement(
            Parser.ParsedGrammar grammar,
            Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
            List<Integer> sequence,
            Map<Pair<Integer, Integer>, Integer> bigramFreqs) {

        if (bigramFreqs.isEmpty()) return false;

        Map.Entry<Pair<Integer, Integer>, Integer> mostFrequentEntry = Collections.max(
                bigramFreqs.entrySet(), Comparator.comparingInt(Map.Entry::getValue)
        );

        if (mostFrequentEntry.getValue() <= 1) return false;

        Pair<Integer, Integer> mostFrequent = mostFrequentEntry.getKey();
        int a = mostFrequent.getLeft();
        int b = mostFrequent.getRight();

        int nextRuleId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();
            List<Integer> newRHS = new ArrayList<>();
            for (int i = 0; i < rule.rhs.size(); ) {
                if (i < rule.rhs.size() - 1 && rule.rhs.get(i) == a && rule.rhs.get(i + 1) == b) {
                    newRHS.add(nextRuleId);
                    i += 2;
                } else {
                    newRHS.add(rule.rhs.get(i));
                    i++;
                }
            }
            if (!newRHS.equals(rule.rhs)) {
                rule.rhs = newRHS;
                rule.length = computeLength(newRHS, rules);
            }
        }

        List<Integer> newSequence = new ArrayList<>();
        for (int i = 0; i < sequence.size(); ) {
            if (i < sequence.size() - 1 && sequence.get(i) == a && sequence.get(i + 1) == b) {
                newSequence.add(nextRuleId);
                i += 2;
            } else {
                newSequence.add(sequence.get(i));
                i++;
            }
        }

        rules.put(nextRuleId, new Parser.GrammarRule<>(nextRuleId, List.of(a, b), 2));
        grammar.sequence().clear();
        grammar.sequence().addAll(newSequence);

        System.out.printf("Replaced bigram (%d, %d) with new rule %d\n", a, b, nextRuleId);
        return true;
    }

    private static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
            List<Integer> sequence,
            Map<Integer, RuleMetadata> metadataMap) {

        Map<Pair<Integer, Integer>, Integer> freqs = new HashMap<>();

        for (int i = 0; i < sequence.size() - 1; i++) {
            int a = sequence.get(i);
            int b = sequence.get(i + 1);
            freqs.merge(new Pair<>(a, b), 1, Integer::sum);
        }

        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();
            RuleMetadata meta = metadataMap.get(ruleId);
            if (rule.rhs.size() < 2 || meta == null) continue;

            for (int i = 0; i < rule.rhs.size() - 1; i++) {
                int x = rule.rhs.get(i);
                int y = rule.rhs.get(i + 1);
                freqs.merge(new Pair<>(x, y), meta.vocc, Integer::sum);
            }
        }

        return freqs;
    }
}