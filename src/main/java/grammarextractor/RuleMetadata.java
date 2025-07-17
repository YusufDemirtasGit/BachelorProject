package grammarextractor;

import java.util.*;

public class RuleMetadata {

    public int vocc;
    public int first;
    public int last;

    public RuleMetadata() {
        this.vocc = 1;
        this.first = -1;
        this.last = -1;
    }

    public static Map<Integer, RuleMetadata> computeAll(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
                                                        Map<Integer, Set<Integer>> reverseUsageMap) {
        Map<Integer, RuleMetadata> result = new HashMap<>();

        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();

            RuleMetadata metadata = new RuleMetadata();

            // Find first symbol by scanning from left to right
            metadata.first = getFirstSymbol(rule.rhs, rules);
            // Find last symbol by scanning from right to left
            metadata.last = getLastSymbol(rule.rhs, rules);

            // Compute usage count
            metadata.vocc = reverseUsageMap.getOrDefault(ruleId, Collections.emptySet()).size();
            if (metadata.vocc == 0) metadata.vocc = 1;

            result.put(ruleId, metadata);
        }

        return result;
    }

    private static int getFirstSymbol(List<Integer> rhs, Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        for (int sym : rhs) {
            if (sym < 256) return sym;
            Parser.GrammarRule<Integer, Integer> nested = rules.get(sym);
            if (nested != null) {
                int result = getFirstSymbol(nested.rhs, rules);
                if (result != -1) return result;
            }
        }
        return -1;
    }

    private static int getLastSymbol(List<Integer> rhs, Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (sym < 256) return sym;
            Parser.GrammarRule<Integer, Integer> nested = rules.get(sym);
            if (nested != null) {
                int result = getLastSymbol(nested.rhs, rules);
                if (result != -1) return result;
            }
        }
        return -1;
    }
}
