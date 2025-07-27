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

    public int getVocc() { return vocc; }
    public int getLength() { return length; }
    public int getLeftmostTerminal() { return leftmostTerminal; }
    public int getRightmostTerminal() { return rightmostTerminal; }
    public boolean isSingleBlock() { return isSB; }
    public int getLeftRunLength() { return leftRunLength; }
    public int getRightRunLength() { return rightRunLength; }

    /**
     * Compute metadata for all rules in the grammar.
     */
    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();
        Map<Integer, RuleMetadata> meta = new HashMap<>();

        // Compute vocc for all rules
        Map<Integer, Integer> allVocc = computeVocc(rules, sequence);

        // Memoization maps
        Map<Integer, Integer> lenMemo = new HashMap<>();
        Map<Integer, Integer> leftTermMemo = new HashMap<>();
        Map<Integer, Integer> rightTermMemo = new HashMap<>();
        Map<Integer, Integer> sbMemo = new HashMap<>();
        Map<Integer, Integer> leftRunMemo = new HashMap<>();
        Map<Integer, Integer> rightRunMemo = new HashMap<>();

        // Precompute lengths for all rules
        for (int ruleId : rules.keySet()) {
            computeLength(ruleId, rules, lenMemo, new HashSet<>());
        }

        // Compute all metadata for each rule
        for (int ruleId : rules.keySet()) {
            int vocc = allVocc.getOrDefault(ruleId, 0);
            int length = lenMemo.getOrDefault(ruleId, 0);
            int leftTerm = computeFirstTerminal(ruleId, rules, leftTermMemo, artificialTerminals);
            int rightTerm = computeLastTerminal(ruleId, rules, rightTermMemo, artificialTerminals);
            boolean isSB = isSingleBlock(ruleId, rules, sbMemo, leftTermMemo, rightTermMemo, artificialTerminals);
            int leftRun = computeLeftRun(ruleId, rules, leftRunMemo, leftTermMemo, lenMemo, artificialTerminals, new HashSet<>());
            int rightRun = computeRightRun(ruleId, rules, rightRunMemo, rightTermMemo, lenMemo, artificialTerminals, new HashSet<>());

            meta.put(ruleId, new RuleMetadata(vocc, length, leftTerm, rightTerm, isSB, leftRun, rightRun));
        }

        return meta;
    }

    /**
     * Computes vocc by propagating counts from SEQ through all rules.
     */
    private static Map<Integer, Integer> computeVocc(Map<Integer, List<Integer>> rules, List<Integer> sequence) {
        Map<Integer, Integer> vocc = new HashMap<>();
        Map<Integer, Integer> indeg = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> mult = new HashMap<>();

        for (int id : rules.keySet()) {
            vocc.put(id, 0);
            indeg.put(id, 0);
            mult.put(id, new HashMap<>());
        }

        // Build multiplicities and indegrees
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            int parent = e.getKey();
            Map<Integer, Integer> mm = mult.get(parent);
            for (int child : e.getValue()) {
                if (rules.containsKey(child)) {
                    mm.merge(child, 1, Integer::sum);
                    indeg.put(child, indeg.get(child) + 1);
                }
            }
        }

        // Seed counts from SEQ
        for (int sym : sequence) {
            if (rules.containsKey(sym)) {
                vocc.put(sym, vocc.get(sym) + 1);
            }
        }

        // Topological propagation
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int id : rules.keySet()) {
            if (indeg.get(id) == 0) q.add(id);
        }

        while (!q.isEmpty()) {
            int p = q.remove();
            int count = vocc.get(p);
            for (Map.Entry<Integer, Integer> me : mult.get(p).entrySet()) {
                int child = me.getKey();
                int mul = me.getValue();
                if (count != 0) {
                    vocc.put(child, vocc.get(child) + count * mul);
                }
                indeg.put(child, indeg.get(child) - mul);
                if (indeg.get(child) == 0) q.add(child);
            }
        }

        return vocc;
    }

    private static boolean isTerminalOrArtificial(int sym, Set<Integer> artificialTerminals) {
        return sym < 256 || (artificialTerminals != null && artificialTerminals.contains(sym));
    }

    private static int computeLength(int id, Map<Integer, List<Integer>> rules,
                                     Map<Integer, Integer> memo, Set<Integer> visited) {
        if (id < 256) return 1;
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) return 0;
        if (!visited.add(id)) return 0; // cycle guard

        int len = 0;
        for (int sym : rules.get(id)) len += computeLength(sym, rules, memo, visited);
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

    private static int computeLeftRun(
            int id,
            Map<Integer, List<Integer>> rules,
            Map<Integer, Integer> memoLeftRun,
            Map<Integer, Integer> memoFirstTerminal,
            Map<Integer, Integer> memoLength,
            Set<Integer> artificialTerminals,
            Set<Integer> visited
    ) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return 1;
        if (memoLeftRun.containsKey(id)) return memoLeftRun.get(id);
        if (!rules.containsKey(id)) return 0;
        if (!visited.add(id)) return 0; // cycle guard

        int base = computeFirstTerminal(id, rules, memoFirstTerminal, artificialTerminals);
        if (base == -1) {
            memoLeftRun.put(id, 0);
            visited.remove(id);
            return 0;
        }

        int run = 0;
        for (int sym : rules.get(id)) {
            if (computeFirstTerminal(sym, rules, memoFirstTerminal, artificialTerminals) != base) break;

            int subRun = computeLeftRun(sym, rules, memoLeftRun, memoFirstTerminal, memoLength, artificialTerminals, visited);
            int symLen = memoLength.getOrDefault(sym, 1);

            run += subRun;
            if (subRun < symLen) break;
        }

        visited.remove(id);
        memoLeftRun.put(id, run);
        return run;
    }

    private static int computeRightRun(
            int id,
            Map<Integer, List<Integer>> rules,
            Map<Integer, Integer> memoRightRun,
            Map<Integer, Integer> memoLastTerminal,
            Map<Integer, Integer> memoLength,
            Set<Integer> artificialTerminals,
            Set<Integer> visited
    ) {
        if (isTerminalOrArtificial(id, artificialTerminals)) return 1;
        if (memoRightRun.containsKey(id)) return memoRightRun.get(id);
        if (!rules.containsKey(id)) return 0;
        if (!visited.add(id)) return 0; // cycle guard

        int base = computeLastTerminal(id, rules, memoLastTerminal, artificialTerminals);
        if (base == -1) {
            memoRightRun.put(id, 0);
            visited.remove(id);
            return 0;
        }

        int run = 0;
        List<Integer> rhs = rules.get(id);
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (computeLastTerminal(sym, rules, memoLastTerminal, artificialTerminals) != base) break;

            int subRun = computeRightRun(sym, rules, memoRightRun, memoLastTerminal, memoLength, artificialTerminals, visited);
            int symLen = memoLength.getOrDefault(sym, 1);

            run += subRun;
            if (subRun < symLen) break;
        }

        visited.remove(id);
        memoRightRun.put(id, run);
        return run;
    }

    /**
     * Print metadata for debugging.
     */
    public static void printMetadata(Map<Integer, RuleMetadata> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            System.out.println("No metadata available.");
            return;
        }

        System.out.println("=== 📊 Rule Metadata ===");
        for (Map.Entry<Integer, RuleMetadata> entry : metadata.entrySet()) {
            int ruleId = entry.getKey();
            RuleMetadata meta = entry.getValue();
            System.out.printf(
                    "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s, leftRun=%d, rightRun=%d%n",
                    ruleId,
                    meta.getVocc(),
                    meta.getLength(),
                    meta.getLeftmostTerminal() == -1 ? "None" : formatSymbol(meta.getLeftmostTerminal()),
                    meta.getRightmostTerminal() == -1 ? "None" : formatSymbol(meta.getRightmostTerminal()),
                    meta.isSingleBlock(),
                    meta.getLeftRunLength(),
                    meta.getRightRunLength()
            );
        }
        System.out.println("========================\n");
    }
}
