package grammarextractor;

import java.util.*;

public class RuleMetadata {
    private final int vocc;
    private final int length;
    private final int lambda;
    private final int pho;
    private final boolean isSB;

    public RuleMetadata(int vocc, int length, int lambda, int pho, boolean isSB) {
        this.vocc = vocc;
        this.length = length;
        this.lambda = lambda;
        this.pho = pho;
        this.isSB = isSB;
    }

    public int getVocc() { return vocc; }
    public int getLength() { return length; }
    public int getLambda() { return lambda; }
    public int getPho() { return pho; }
    public boolean isSingleBlock() { return isSB; }

    public static Map<Integer, RuleMetadata> computeAll(
            Map<Integer, List<Integer>> rules,
            Map<Integer, Set<Integer>> reverseUsageMap,
            List<Integer> sequence
    ) {
        System.out.println("=== Starting Metadata Computation ===");

        Map<Integer, RuleMetadata> meta = new HashMap<>();
        Map<Integer, Integer> memoLen = new HashMap<>();
        Map<Integer, Integer> memoLam = new HashMap<>();
        Map<Integer, Integer> memoPho = new HashMap<>();
        Map<Integer, Integer> memoSB = new HashMap<>();  // maps ruleId to terminal if single-block, else -1

        Map<Integer, Integer> totalUsage = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int lhs = entry.getKey();
            List<Integer> rhs = entry.getValue();
            System.out.println("Scanning RHS of Rule R" + lhs + ": " + rhs);
            for (int sym : rhs) {
                if (sym >= 256) {
                    totalUsage.merge(sym, 1, Integer::sum);
                }
            }
        }

        if (sequence != null) {
            for (int sym : sequence) {
                if (sym >= 256) {
                    totalUsage.merge(sym, 1, Integer::sum);
                }
            }
        }

        for (int ruleId : rules.keySet()) {
            System.out.println("\n--- Computing Metadata for Rule R" + ruleId + " ---");

            int vocc = totalUsage.getOrDefault(ruleId, 0);
            System.out.println("Virtual Occurrence Count (vocc): " + vocc);

            int length = computeLength(ruleId, rules, memoLen);
            System.out.println("Expansion Length: " + length);

            int lambda = computeFirstTerminal(ruleId, rules, memoLam);
            System.out.println("Lambda (first terminal): " + (lambda == -1 ? "None" : lambda));

            int pho = computeLastTerminal(ruleId, rules, memoPho);
            System.out.println("Pho (last terminal): " + (pho == -1 ? "None" : pho));

            boolean isSB = isSingleBlock(ruleId, rules, memoSB);
            if (isSB) {
                System.out.println("Single-block detected (transitively): all symbols reduce to the same terminal.");
            }

            RuleMetadata ruleMeta = new RuleMetadata(vocc, length, lambda, pho, isSB);
            meta.put(ruleId, ruleMeta);
        }

        System.out.println("=== Metadata Computation Completed ===");
        return meta;
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
        if (memo.containsKey(id)) {
            System.out.println("Memo hit: Length of R" + id + " = " + memo.get(id));
            return memo.get(id);
        }
        if (!rules.containsKey(id)) {
            System.out.println("Rule R" + id + " not found. Returning length 0.");
            memo.put(id, 0);
            return 0;
        }
        if (seen.contains(id)) {
            System.out.println("Cycle detected in rule R" + id + ", skipping.");
            memo.put(id, 0);
            return 0;
        }

        seen.add(id);
        int len = 0;
        List<Integer> rhs = rules.get(id);
        System.out.println("Computing expansion length for R" + id + ": " + rhs);
        for (int sym : rhs) {
            if (sym < 256) {
                len++;
            } else {
                len += computeLengthVisited(sym, rules, memo, seen);
            }
        }
        seen.remove(id);
        memo.put(id, len);
        System.out.println("Computed Length for R" + id + ": " + len);
        return len;
    }

    private static int computeFirstTerminal(int id, Map<Integer, List<Integer>> rules,
                                            Map<Integer, Integer> memo) {
        if (memo.containsKey(id)) {
            System.out.println("Memo hit: Lambda of R" + id + " = " + memo.get(id));
            return memo.get(id);
        }
        if (!rules.containsKey(id)) {
            System.out.println("Rule R" + id + " not found. Lambda = -1");
            memo.put(id, -1);
            return -1;
        }
        for (int sym : rules.get(id)) {
            if (sym < 256) {
                memo.put(id, sym);
                return sym;
            } else {
                int inner = computeFirstTerminal(sym, rules, memo);
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
                                           Map<Integer, Integer> memo) {
        if (memo.containsKey(id)) {
            System.out.println("Memo hit: Pho of R" + id + " = " + memo.get(id));
            return memo.get(id);
        }
        if (!rules.containsKey(id)) {
            System.out.println("Rule R" + id + " not found. Pho = -1");
            memo.put(id, -1);
            return -1;
        }

        List<Integer> rhs = rules.get(id);
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (sym < 256) {
                memo.put(id, sym);
                return sym;
            } else {
                int inner = computeLastTerminal(sym, rules, memo);
                if (inner != -1) {
                    memo.put(id, inner);
                    return inner;
                }
            }
        }
        memo.put(id, -1);
        return -1;
    }
}
