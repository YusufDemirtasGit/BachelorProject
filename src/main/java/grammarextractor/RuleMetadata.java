package grammarextractor;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class RuleMetadata {
    public int vocc;

    public RuleMetadata(int vocc) {
        this.vocc = vocc;
    }

    public static Map<Integer, RuleMetadata> computeAll(
            Map<Integer, Parser.GrammarRule<Integer, Integer>> rules,
            Map<Integer, Set<Integer>> reverseUsageMap) {

        return rules.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            int vocc = reverseUsageMap.getOrDefault(entry.getKey(), Set.of()).size();
                            return new RuleMetadata(vocc);
                        }
                )
        );
    }

    // ✅ Utility to recompute rule lengths
    public static void recomputeLengths(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();
            int length = 0;
            for (int sym : rule.rhs) {
                if (sym < 256) {
                    length += 1;
                } else {
                    Parser.GrammarRule<Integer, Integer> sub = rules.get(sym);
                    if (sub == null) {
                        System.err.println("Warning: Missing rule R" + sym + " while recomputing length of R" + entry.getKey());
                        length += 1;
                    } else {
                        length += sub.length;
                    }
                }
            }
            rule.length = length;
        }
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
