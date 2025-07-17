package grammarextractor;

import java.util.*;

import static grammarextractor.Parser.ParsedGrammar.getLength;

public class Recompressor {

    public static void recompress(Parser.ParsedGrammar grammar) {
        Map<Integer, Parser.GrammarRule<Integer, Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();

        boolean changed;
        do {
            Map<Integer, Map<Integer, Integer>> usageMatrix = buildUsageMatrix(rules);
            Map<Integer, Set<Integer>> reverseUsageMap = buildReverseUsageMap(usageMatrix);
            Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(rules, reverseUsageMap);

            popInlet(rules, reverseUsageMap);

            Map<Pair<Integer, Integer>, Integer> bigramFreqs = computeBigramFrequencies(rules, sequence, metadata);
            changed = applyBigramReplacement(grammar, rules, sequence, bigramFreqs);
        } while (changed);
    }

    public static void popInlet(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
                                Map<Integer, Set<Integer>> reverseUsageMap) {
        System.out.println("\n========== Starting PopInlet Pass ==========");

        int nextRuleId = Collections.max(rules.keySet()) + 1;
        List<Integer> ruleIds = new ArrayList<>(rules.keySet());

        for (Integer targetId : ruleIds) {
            Parser.GrammarRule<Integer, Integer> targetRule = rules.get(targetId);
            if (targetRule == null) continue;

            Set<Integer> parents = reverseUsageMap.getOrDefault(targetId, Set.of());
            if (parents.isEmpty()) {
                System.out.println("? Rule R" + targetId + " is not used by any other rule. Skipping.");
                continue;
            }

            System.out.println("\n? Checking target rule: R" + targetId);
            System.out.println("  ? Used by rules: " + parents);

            Integer inletCandidate = null;
            boolean valid = true;

            for (int parentId : parents) {
                Parser.GrammarRule<Integer, Integer> parent = rules.get(parentId);
                if (parent == null) continue;

                List<Integer> rhs = parent.rhs;
                if (!rhs.isEmpty() && rhs.get(rhs.size() - 1).equals(targetId)) {
                    int preceding = rhs.get(rhs.size() - 2);
                    if (inletCandidate == null) {
                        inletCandidate = preceding;
                    } else if (!inletCandidate.equals(preceding)) {
                        System.out.println("  ✘ Inconsistent inlet: " + preceding + " ≠ " + inletCandidate);
                        valid = false;
                        break;
                    }
                } else {
                    System.out.println("  ✘ Rule R" + parentId + " does not use R" + targetId + " at RHS end.");
                    valid = false;
                    break;
                }
            }

            if (!valid || inletCandidate == null) {
                System.out.println("  ✘ No unique inlet found. Skipping R" + targetId);
                continue;
            }

            System.out.println("  ? Inlet confirmed: " + inletCandidate + " ? performing pop into R" + targetId);

            // Create new rule: inletCandidate + original RHS of targetRule
            List<Integer> newRHS = new ArrayList<>();
            newRHS.add(inletCandidate);
            newRHS.addAll(targetRule.rhs);
            int newLen = Parser.ParsedGrammar.getLength(rules, inletCandidate) + targetRule.length;

            Parser.GrammarRule<Integer, Integer> newRule = new Parser.GrammarRule<>(targetRule.lhs, newRHS, newLen);
            int newRuleId = nextRuleId++;
            rules.put(newRuleId, newRule);
            System.out.println("    ? Created new rule R" + newRuleId + ": " + newRule.rhs);

            // Update parents to use the new rule instead of (inletCandidate,targetRule)
            for (int parentId : parents) {
                Parser.GrammarRule<Integer, Integer> parent = rules.get(parentId);
                if (parent == null) continue;

                List<Integer> newParentRHS = new ArrayList<>(parent.rhs);
                int size = newParentRHS.size();
                if (size >= 2 && newParentRHS.get(size - 2).equals(inletCandidate) && newParentRHS.get(size - 1).equals(targetId)) {
                    newParentRHS.remove(size - 1); // remove targetId
                    newParentRHS.remove(size - 2); // remove inletCandidate
                    newParentRHS.add(newRuleId);   // replace with new rule

                    int newParentLength = 0;
                    for (int sym : newParentRHS) {
                        newParentLength += Parser.ParsedGrammar.getLength(rules, sym);
                    }

                    rules.put(parentId, new Parser.GrammarRule<>(parent.lhs, newParentRHS, newParentLength));
                    System.out.println("    ? Updated R" + parentId + " to: " + newParentRHS);
                }
            }

            rules.remove(targetId);
            System.out.println("    ? Removed old rule R" + targetId);
        }

        System.out.println("\n========== PopInlet Pass Complete ==========");
    }


    public static void popOutlet(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
                                 Map<Integer, Set<Integer>> reverseUsageMap) {
        System.out.println("\n========== Starting PopOutlet Pass ==========");

        boolean changed = false;
        int maxRuleId = rules.keySet().stream().max(Integer::compare).orElse(255);

        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();

            if (rule.rhs.isEmpty()) continue;
            int firstSymbol = rule.rhs.get(0);

            // Try to outlet from the first symbol (if nonterminal and has only one user)
            if (firstSymbol >= 256 && reverseUsageMap.getOrDefault(firstSymbol, Set.of()).size() == 1) {
                Parser.GrammarRule<Integer, Integer> pulledRule = rules.get(firstSymbol);
                if (pulledRule == null) continue;

                List<Integer> newRhs = new ArrayList<>(pulledRule.rhs);
                newRhs.addAll(rule.rhs.subList(1, rule.rhs.size()));

                rule.rhs = newRhs;
                rule.length = pulledRule.length + getLength(rule.rhs.subList(1, rule.rhs.size()), rules);

                changed = true;
                System.out.printf("? PopOutlet on R%d: pulled %d from front%n", ruleId, firstSymbol);
            }

            // Try to outlet from the last symbol (if nonterminal and has only one user)
            if (!rule.rhs.isEmpty()) {
                int lastIdx = rule.rhs.size() - 1;
                int lastSymbol = rule.rhs.get(lastIdx);

                if (lastSymbol >= 256 && reverseUsageMap.getOrDefault(lastSymbol, Set.of()).size() == 1) {
                    Parser.GrammarRule<Integer, Integer> pulledRule = rules.get(lastSymbol);
                    if (pulledRule == null) continue;

                    List<Integer> newRhs = new ArrayList<>(rule.rhs.subList(0, lastIdx));
                    newRhs.addAll(pulledRule.rhs);

                    rule.rhs = newRhs;
                    rule.length = getLength(rule.rhs, rules);

                    changed = true;
                    System.out.printf("? PopOutlet on R%d: pulled %d from back%n", ruleId, lastSymbol);
                }
            }
        }

        if (!changed) {
            System.out.println("? No PopOutlet rules applied.");
        }

        System.out.println("========== PopOutlet Pass Complete ==========");
    }
    private static int getLength(List<Integer> rhs, Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        int total = 0;
        for (int symbol : rhs) {
            total += (symbol < 256) ? 1 : rules.get(symbol).length;
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
                rule.length = newRHS.stream().mapToInt(sym -> Parser.ParsedGrammar.getLength(rules, sym)).sum();
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
            if (rule.rhs.size() < 2) continue;

            for (int i = 0; i < rule.rhs.size() - 1; i++) {
                int x = rule.rhs.get(i);
                int y = rule.rhs.get(i + 1);
                freqs.merge(new Pair<>(x, y), meta.vocc, Integer::sum);
            }
        }

        return freqs;
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
}
