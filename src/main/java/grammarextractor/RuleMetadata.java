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

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();
        Map<Integer, RuleMetadata> meta = new HashMap<>();

        // Simplified vocc computation
        Map<Integer, Integer> allVocc = computeVocc(rules, sequence);

        Map<Integer, Integer> lenMemo = new HashMap<>();
        Map<Integer, Integer> leftTermMemo = new HashMap<>();
        Map<Integer, Integer> rightTermMemo = new HashMap<>();
        Map<Integer, Integer> sbMemo = new HashMap<>();
        Map<Integer, Integer> leftRunMemo = new HashMap<>();
        Map<Integer, Integer> rightRunMemo = new HashMap<>();

        for (int ruleId : rules.keySet()) {
            int vocc = allVocc.getOrDefault(ruleId, 0);
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
        if (!visited.add(id)) return 0;

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
            if (computeFirstTerminal(sym, rules, memoTerminal, artificialTerminals) != base) break;
            int subRun = computeLeftRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
            run += subRun;
            if (subRun < computeLength(sym, rules, memoLength, new HashSet<>())) break;
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
            if (computeLastTerminal(sym, rules, memoTerminal, artificialTerminals) != base) break;
            int subRun = computeRightRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
            run += subRun;
            if (subRun < computeLength(sym, rules, memoLength, new HashSet<>())) break;
        }
        memoRun.put(id, run);
        return run;
    }
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
