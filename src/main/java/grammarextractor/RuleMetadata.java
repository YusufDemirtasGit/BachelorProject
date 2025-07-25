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

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar,
                                                        Set<Integer> artificialTerminals) {
        boolean verbose = false;
        if (verbose) System.out.println("=== Starting Metadata Computation ===");

        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();

        Map<Integer, RuleMetadata> meta = new HashMap<>();
        Map<Integer, Integer> memoLen = new HashMap<>();
        Map<Integer, Integer> memoLeft = new HashMap<>();
        Map<Integer, Integer> memoRight = new HashMap<>();
        Map<Integer, Integer> memoSB = new HashMap<>();
        Map<Integer, Integer> memoLeftRun = new HashMap<>();
        Map<Integer, Integer> memoRightRun = new HashMap<>();

        // Compute vocc in O(n)
        Map<Integer, Integer> voccMemo = computeVoccAll(rules, sequence);

        // Compute metadata
        for (int ruleId : rules.keySet()) {
            if (verbose) System.out.println("\n--- Computing Metadata for Rule R" + ruleId + " ---");

            int vocc = voccMemo.getOrDefault(ruleId, 0);
            int length = computeLength(ruleId, rules, memoLen);
            int left = computeFirstTerminal(ruleId, rules, memoLeft, artificialTerminals);
            int right = computeLastTerminal(ruleId, rules, memoRight, artificialTerminals);
            boolean isSB = isSingleBlock(ruleId, rules, memoSB);
            int leftRun = computeLeftRun(ruleId, rules, memoLeftRun, memoLeft, memoLen, artificialTerminals);
            int rightRun = computeRightRun(ruleId, rules, memoRightRun, memoRight, memoLen, artificialTerminals);

            RuleMetadata ruleMeta = new RuleMetadata(vocc, length, left, right, isSB, leftRun, rightRun);
            meta.put(ruleId, ruleMeta);
        }

        if (verbose) System.out.println("=== Metadata Computation Completed ===");
        return meta;
    }

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar) {
        return computeAll(grammar, null);
    }

    // ---------------- O(n) vocc computation ----------------
    private static Map<Integer, Integer> computeVoccAll(Map<Integer, List<Integer>> rules, List<Integer> sequence) {
        Map<Integer, Integer> vocc = new HashMap<>();

        // 1. Initialize vocc from the sequence
        for (int sym : sequence) {
            if (sym >= 256) {
                vocc.merge(sym, 1, Integer::sum);
            }
        }

        // 2. Count references (child usage in RHS)
        Map<Integer, Map<Integer, Integer>> childCount = new HashMap<>();
        Map<Integer, Integer> indegree = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int parent = entry.getKey();
            for (int sym : entry.getValue()) {
                if (sym >= 256) {
                    childCount.computeIfAbsent(parent, k -> new HashMap<>())
                            .merge(sym, 1, Integer::sum);
                    indegree.merge(sym, 1, Integer::sum);
                }
            }
            indegree.putIfAbsent(parent, indegree.getOrDefault(parent, 0));
        }

        // 3. Compute topological order
        List<Integer> topoOrder = topologicalSort(rules, indegree);

        // 4. Propagate vocc
        for (int rule : topoOrder) {
            int v = vocc.getOrDefault(rule, 0);
            if (childCount.containsKey(rule)) {
                for (Map.Entry<Integer, Integer> child : childCount.get(rule).entrySet()) {
                    vocc.merge(child.getKey(), v * child.getValue(), Integer::sum);
                }
            }
        }

        return vocc;
    }

    private static List<Integer> topologicalSort(Map<Integer, List<Integer>> rules, Map<Integer, Integer> indegree) {
        Queue<Integer> q = new ArrayDeque<>();
        List<Integer> order = new ArrayList<>();

        for (int rule : rules.keySet()) {
            if (indegree.getOrDefault(rule, 0) == 0) {
                q.add(rule);
            }
        }

        while (!q.isEmpty()) {
            int r = q.poll();
            order.add(r);
            for (int sym : rules.getOrDefault(r, Collections.emptyList())) {
                if (sym >= 256 && indegree.containsKey(sym)) {
                    int newVal = indegree.get(sym) - 1;
                    indegree.put(sym, newVal);
                    if (newVal == 0) {
                        q.add(sym);
                    }
                }
            }
        }
        return order;
    }

    // ---------------- Other Metadata Methods ----------------
    private static boolean isTerminalOrArtificial(int sym, Set<Integer> artificialTerminals) {
        return sym < 256 || (artificialTerminals != null && artificialTerminals.contains(sym));
    }

    private static boolean isSingleBlock(int id, Map<Integer, List<Integer>> rules, Map<Integer, Integer> memoSB) {
        if (!rules.containsKey(id)) return false;
        List<Integer> rhs = rules.get(id);
        if (rhs == null || rhs.isEmpty()) return false;

        int base = getSingleBlockTerminal(rhs.get(0), rules, memoSB);
        if (base == -1) return false;
        for (int sym : rhs) {
            if (getSingleBlockTerminal(sym, rules, memoSB) != base) return false;
        }
        return true;
    }

    private static int getSingleBlockTerminal(int sym, Map<Integer, List<Integer>> rules, Map<Integer, Integer> memoSB) {
        if (sym < 256) return sym;
        if (memoSB.containsKey(sym)) return memoSB.get(sym);

        List<Integer> rhs = rules.get(sym);
        if (rhs == null || rhs.isEmpty()) {
            memoSB.put(sym, -1);
            return -1;
        }

        int base = getSingleBlockTerminal(rhs.get(0), rules, memoSB);
        if (base == -1) {
            memoSB.put(sym, -1);
            return -1;
        }

        for (int s : rhs) {
            if (getSingleBlockTerminal(s, rules, memoSB) != base) {
                memoSB.put(sym, -1);
                return -1;
            }
        }
        memoSB.put(sym, base);
        return base;
    }

    private static int computeLength(int id, Map<Integer, List<Integer>> rules,
                                     Map<Integer, Integer> memo) {
        return computeLengthVisited(id, rules, memo, new HashSet<>());
    }

    private static int computeLengthVisited(int id,
                                            Map<Integer, List<Integer>> rules,
                                            Map<Integer, Integer> memo,
                                            Set<Integer> seen) {
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) {
            memo.put(id, 0);
            return 0;
        }
        if (seen.contains(id)) {
            memo.put(id, 0);
            return 0;
        }

        seen.add(id);
        int len = 0;
        List<Integer> rhs = rules.get(id);
        for (int sym : rhs) {
            if (sym < 256) len++;
            else len += computeLengthVisited(sym, rules, memo, seen);
        }
        seen.remove(id);
        memo.put(id, len);
        return len;
    }

    private static int computeFirstTerminal(int id, Map<Integer, List<Integer>> rules,
                                            Map<Integer, Integer> memo,
                                            Set<Integer> artificialTerminals) {
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) {
            memo.put(id, -1);
            return -1;
        }
        for (int sym : rules.get(id)) {
            if (isTerminalOrArtificial(sym, artificialTerminals)) {
                memo.put(id, sym);
                return sym;
            } else {
                int inner = computeFirstTerminal(sym, rules, memo, artificialTerminals);
                if (inner != -1) {
                    memo.put(id, inner);
                    return inner;
                }
            }
        }
        memo.put(id, -1);
        return -1;
    }

    private static int computeLastTerminal(int id, Map<Integer, List<Integer>> rules,
                                           Map<Integer, Integer> memo,
                                           Set<Integer> artificialTerminals) {
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) {
            memo.put(id, -1);
            return -1;
        }
        List<Integer> rhs = rules.get(id);
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (isTerminalOrArtificial(sym, artificialTerminals)) {
                memo.put(id, sym);
                return sym;
            } else {
                int inner = computeLastTerminal(sym, rules, memo, artificialTerminals);
                if (inner != -1) {
                    memo.put(id, inner);
                    return inner;
                }
            }
        }
        memo.put(id, -1);
        return -1;
    }

    private static int computeLeftRun(int id, Map<Integer, List<Integer>> rules,
                                      Map<Integer, Integer> memoRun,
                                      Map<Integer, Integer> memoTerminal,
                                      Map<Integer, Integer> memoLength,
                                      Set<Integer> artificialTerminals) {
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        List<Integer> rhs = rules.get(id);
        if (rhs.isEmpty()) return 0;

        int base = computeFirstTerminal(id, rules, memoTerminal, artificialTerminals);
        if (base == -1) {
            memoRun.put(id, 0);
            return 0;
        }

        int run = 0;
        for (int sym : rhs) {
            if (isTerminalOrArtificial(sym, artificialTerminals)) {
                if (sym == base) run++;
                else break;
            } else {
                int subFirst = computeFirstTerminal(sym, rules, memoTerminal, artificialTerminals);
                if (subFirst != base) break;

                int subRun = computeLeftRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
                run += subRun;

                int subLen = computeLength(sym, rules, memoLength);
                if (subRun < subLen) break;
            }
        }

        memoRun.put(id, run);
        return run;
    }

    private static int computeRightRun(int id, Map<Integer, List<Integer>> rules,
                                       Map<Integer, Integer> memoRun,
                                       Map<Integer, Integer> memoTerminal,
                                       Map<Integer, Integer> memoLength,
                                       Set<Integer> artificialTerminals) {
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        List<Integer> rhs = rules.get(id);
        if (rhs.isEmpty()) return 0;

        int base = computeLastTerminal(id, rules, memoTerminal, artificialTerminals);
        if (base == -1) {
            memoRun.put(id, 0);
            return 0;
        }

        int run = 0;
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (isTerminalOrArtificial(sym, artificialTerminals)) {
                if (sym == base) run++;
                else break;
            } else {
                int subLast = computeLastTerminal(sym, rules, memoTerminal, artificialTerminals);
                if (subLast != base) break;

                int subRun = computeRightRun(sym, rules, memoRun, memoTerminal, memoLength, artificialTerminals);
                run += subRun;

                int subLen = computeLength(sym, rules, memoLength);
                if (subRun < subLen) break;
            }
        }

        memoRun.put(id, run);
        return run;
    }
}
