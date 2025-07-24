package grammarextractor;

import java.util.*;

import static grammarextractor.Main.formatSymbol;

public class RuleMetadata {
    private final int vocc;
    private final int length;
    private final int leftmostTerminal;
    private final int rightmostTerminal;
    private final boolean isSB;
    private final int leftRunLength;
    private final int rightRunLength;

    public RuleMetadata(int vocc, int length, int leftmostTerminal, int rightmostTerminal,
                        boolean isSB, int leftRunLength, int rightRunLength) {
        this.vocc = vocc;
        this.length = length;
        this.leftmostTerminal = leftmostTerminal;
        this.rightmostTerminal = rightmostTerminal;
        this.isSB = isSB;
        this.leftRunLength = leftRunLength;
        this.rightRunLength = rightRunLength;
    }

    // Getters
    public int getVocc() { return vocc; }
    public int getLength() { return length; }
    public int getLeftmostTerminal() { return leftmostTerminal; }
    public int getRightmostTerminal() { return rightmostTerminal; }
    public boolean isSingleBlock() { return isSB; }
    public int getLeftRunLength() { return leftRunLength; }
    public int getRightRunLength() { return rightRunLength; }

    /**
     * Computes all metadata for a given grammar efficiently.
     * This is the main entry point for metadata calculation.
     */
    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();
        Map<Integer, RuleMetadata> meta = new HashMap<>();

        // === EFFICIENCY IMPROVEMENT: Pre-build reverse dependency graph ===
        // This map tracks which rules use a given non-terminal.
        // Key: Non-terminal ID, Value: Set of rules that use the key in their RHS.
        Map<Integer, Set<Integer>> usesOf = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int parentRuleId = entry.getKey();
            for (int sym : entry.getValue()) {
                if (sym >= 256) { // It's a non-terminal
                    usesOf.computeIfAbsent(sym, k -> new HashSet<>()).add(parentRuleId);
                }
            }
        }

        // Memoization tables for recursive calculations
        Map<Integer, Integer> voccMemo = new HashMap<>();
        Map<Integer, Integer> lenMemo = new HashMap<>();
        Map<Integer, Integer> leftTermMemo = new HashMap<>();
        Map<Integer, Integer> rightTermMemo = new HashMap<>();
        Map<Integer, Integer> sbMemo = new HashMap<>();
        Map<Integer, Integer> leftRunMemo = new HashMap<>();
        Map<Integer, Integer> rightRunMemo = new HashMap<>();

        // --- Metadata Calculation ---
        // Iterate through all rules to ensure metadata is computed for every non-terminal.
        for (int ruleId : rules.keySet()) {
            int vocc = computeVocc(ruleId, rules, sequence, usesOf, voccMemo);
            int length = computeLength(ruleId, rules, lenMemo, new HashSet<>());
            int leftTerm = computeFirstTerminal(ruleId, rules, leftTermMemo, artificialTerminals);
            int rightTerm = computeLastTerminal(ruleId, rules, rightTermMemo, artificialTerminals);
            boolean isSB = isSingleBlock(ruleId, rules, sbMemo, leftTermMemo, rightTermMemo, artificialTerminals);
            int leftRun = computeLeftRun(ruleId, rules, leftRunMemo, leftTermMemo, lenMemo, artificialTerminals);
            int rightRun = computeRightRun(ruleId, rules, rightRunMemo, rightTermMemo, lenMemo, artificialTerminals);

            meta.put(ruleId, new RuleMetadata(vocc, length, leftTerm, rightTerm, isSB, leftRun, rightRun));
        }

        return meta;
    }

    // Overloaded version for convenience when no artificial terminals are present.
    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar) {
        return computeAll(grammar, Collections.emptySet());
    }

    private static boolean isTerminalOrArtificial(int sym, Set<Integer> artificialTerminals) {
        return sym < 256 || (artificialTerminals != null && artificialTerminals.contains(sym));
    }

    /**
     * Efficiently computes the virtual occurrence count (vocc) using a reverse dependency graph.
     */
    private static int computeVocc(int ruleId,
                                   Map<Integer, List<Integer>> rules,
                                   List<Integer> sequence,
                                   Map<Integer, Set<Integer>> usesOf,
                                   Map<Integer, Integer> memo) {
        if (memo.containsKey(ruleId)) {
            return memo.get(ruleId);
        }

        // 1. Count occurrences in the main sequence
        int voccValue = 0;
        if (sequence != null) {
            for (int sym : sequence) {
                if (sym == ruleId) {
                    voccValue++;
                }
            }
        }

        // 2. Add occurrences from parent rules that use this rule
        Set<Integer> parentRules = usesOf.get(ruleId);
        if (parentRules != null) {
            for (int parentId : parentRules) {
                // Count how many times this rule (ruleId) appears in its parent's RHS
                int countInParent = 0;
                for (int sym : rules.get(parentId)) {
                    if (sym == ruleId) {
                        countInParent++;
                    }
                }
                // Recurse on the parent and add to the total
                voccValue += countInParent * computeVocc(parentId, rules, sequence, usesOf, memo);
            }
        }

        memo.put(ruleId, voccValue);
        return voccValue;
    }

    // The remaining metadata methods were logically sound and remain largely unchanged.
    // They already use memoization effectively.

    private static int computeLength(int id, Map<Integer, List<Integer>> rules,
                                     Map<Integer, Integer> memo, Set<Integer> visited) {
        if (id < 256) return 1;
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) return 0;
        if (!visited.add(id)) return 0; // Cycle detected

        int len = 0;
        for (int sym : rules.get(id)) {
            len += computeLength(sym, rules, memo, visited);
        }

        visited.remove(id);
        memo.put(id, len);
        return len;
    }

    private static int computeFirstTerminal(int id, Map<Integer, List<Integer>> rules,
                                            Map<Integer, Integer> memo, Set<Integer> artificialTerminals) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return id;
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) return -1;

        for (int sym : rules.get(id)) {
            int first = computeFirstTerminal(sym, rules, memo, artificialTerminals);
            if (first != -1) {
                memo.put(id, first);
                return first;
            }
        }

        memo.put(id, -1);
        return -1;
    }

    private static int computeLastTerminal(int id, Map<Integer, List<Integer>> rules,
                                           Map<Integer, Integer> memo, Set<Integer> artificialTerminals) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return id;
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) return -1;

        List<Integer> rhs = rules.get(id);
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int last = computeLastTerminal(rhs.get(i), rules, memo, artificialTerminals);
            if (last != -1) {
                memo.put(id, last);
                return last;
            }
        }

        memo.put(id, -1);
        return -1;
    }

    private static boolean isSingleBlock(int id, Map<Integer, List<Integer>> rules, Map<Integer, Integer> memoSB,
                                         Map<Integer, Integer> memoLeft, Map<Integer, Integer> memoRight,
                                         Set<Integer> artificialTerminals) {
        if (id < 256) return true;
        if (memoSB.containsKey(id)) return memoSB.get(id) == 1;
        if (!rules.containsKey(id)) return false;

        int leftTerm = computeFirstTerminal(id, rules, memoLeft, artificialTerminals);
        int rightTerm = computeLastTerminal(id, rules, memoRight, artificialTerminals);

        if (leftTerm == -1 || leftTerm != rightTerm) {
            memoSB.put(id, 0);
            return false;
        }

        for (int sym : rules.get(id)) {
            if (!isSingleBlock(sym, rules, memoSB, memoLeft, memoRight, artificialTerminals)) {
                memoSB.put(id, 0);
                return false;
            }
            if (computeFirstTerminal(sym, rules, memoLeft, artificialTerminals) != leftTerm) {
                memoSB.put(id, 0);
                return false;
            }
        }

        memoSB.put(id, 1);
        return true;
    }

    private static int computeLeftRun(int id, Map<Integer, List<Integer>> rules,
                                      Map<Integer, Integer> memoRun, Map<Integer, Integer> memoTerminal,
                                      Map<Integer, Integer> memoLength, Set<Integer> artificialTerminals) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return 1;
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        int base = computeFirstTerminal(id, rules, memoTerminal, artificialTerminals);
        if (base == -1) return 0;

        int run = 0;
        for (int sym : rules.get(id)) {
            if (computeFirstTerminal(sym, rules, memoTerminal, artificialTerminals) != base) {
                break;
            }
            int subRun = computeLeftRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
            run += subRun;
            if (subRun < computeLength(sym, rules, memoLength, new HashSet<>())) {
                break; // The sub-rule was not a solid block of the base terminal
            }
        }
        memoRun.put(id, run);
        return run;
    }

    private static int computeRightRun(int id, Map<Integer, List<Integer>> rules,
                                       Map<Integer, Integer> memoRun, Map<Integer, Integer> memoTerminal,
                                       Map<Integer, Integer> memoLength, Set<Integer> artificialTerminals) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return 1;
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        int base = computeLastTerminal(id, rules, memoTerminal, artificialTerminals);
        if (base == -1) return 0;

        int run = 0;
        List<Integer> rhs = rules.get(id);
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (computeLastTerminal(sym, rules, memoTerminal, artificialTerminals) != base) {
                break;
            }
            int subRun = computeRightRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
            run += subRun;
            if (subRun < computeLength(sym, rules, memoLength, new HashSet<>())) {
                break; // The sub-rule was not a solid block
            }
        }
        memoRun.put(id, run);
        return run;
    }
}