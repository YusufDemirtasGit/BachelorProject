package grammarextractor;

import java.util.*;

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

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar) {
        System.out.println("=== Starting Metadata Computation ===");

        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();

        Map<Integer, RuleMetadata> meta = new HashMap<>();
        Map<Integer, Integer> memoLen = new HashMap<>();
        Map<Integer, Integer> memoLeft = new HashMap<>();
        Map<Integer, Integer> memoRight = new HashMap<>();
        Map<Integer, Integer> memoSB = new HashMap<>();
        Map<Integer, Integer> memoLeftRun = new HashMap<>();
        Map<Integer, Integer> memoRightRun = new HashMap<>();

        // Count references
        Map<Integer, Integer> directUsage = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            for (int sym : entry.getValue()) {
                if (sym >= 256) {
                    directUsage.merge(sym, 1, Integer::sum);
                }
            }
        }
        if (sequence != null) {
            for (int sym : sequence) {
                if (sym >= 256) {
                    directUsage.merge(sym, 1, Integer::sum);
                }
            }
        }

        // Compute vocc
        Map<Integer, Integer> voccMemo = new HashMap<>();
        for (int sym : sequence) {
            if (sym >= 256) {
                computeVocc(sym, rules, directUsage, voccMemo, sequence);
            }
        }
        for (int ruleId : rules.keySet()) {
            if (!voccMemo.containsKey(ruleId)) {
                computeVocc(ruleId, rules, directUsage, voccMemo, sequence);
            }
        }

        // Compute metadata
        for (int ruleId : rules.keySet()) {
            System.out.println("\n--- Computing Metadata for Rule R" + ruleId + " ---");

            int vocc = voccMemo.getOrDefault(ruleId, 0);
            System.out.println("Virtual Occurrence Count (vocc): " + vocc);

            int length = computeLength(ruleId, rules, memoLen);
            System.out.println("Expansion Length: " + length);

            int left = computeFirstTerminal(ruleId, rules, memoLeft);
            System.out.println("Leftmost Terminal: " + (left == -1 ? "None" : left));

            int right = computeLastTerminal(ruleId, rules, memoRight);
            System.out.println("Rightmost Terminal: " + (right == -1 ? "None" : right));

            boolean isSB = isSingleBlock(ruleId, rules, memoSB);
            if (isSB) {
                System.out.println("Single-block detected (transitively): all symbols reduce to the same terminal.");
            }

            int leftRun = computeLeftRun(ruleId, rules, memoLeftRun, memoLeft, memoLen);
            System.out.println("Left Run Length: " + leftRun);

            int rightRun = computeRightRun(ruleId, rules, memoRightRun, memoRight, memoLen);
            System.out.println("Right Run Length: " + rightRun);

            RuleMetadata ruleMeta = new RuleMetadata(vocc, length, left, right, isSB, leftRun, rightRun);
            meta.put(ruleId, ruleMeta);
        }

        System.out.println("=== Metadata Computation Completed ===");
        return meta;
    }

    private static int computeVocc(int ruleId,
                                   Map<Integer, List<Integer>> rules,
                                   Map<Integer, Integer> directUsage,
                                   Map<Integer, Integer> memo,
                                   List<Integer> sequence) {
        if (memo.containsKey(ruleId)) {
            return memo.get(ruleId);
        }

        int voccValue = 0;
        for (int sym : sequence) {
            if (sym == ruleId) {
                voccValue++;
            }
        }

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int parentRule = entry.getKey();
            List<Integer> rhs = entry.getValue();
            int countInParent = 0;
            for (int sym : rhs) {
                if (sym == ruleId) {
                    countInParent++;
                }
            }
            if (countInParent > 0) {
                int parentVocc = computeVocc(parentRule, rules, directUsage, memo, sequence);
                voccValue += countInParent * parentVocc;
            }
        }

        memo.put(ruleId, voccValue);
        return voccValue;
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
            if (sym < 256) {
                len++;
            } else {
                len += computeLengthVisited(sym, rules, memo, seen);
            }
        }
        seen.remove(id);
        memo.put(id, len);
        return len;
    }

    private static int computeFirstTerminal(int id, Map<Integer, List<Integer>> rules,
                                            Map<Integer, Integer> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) {
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
        if (memo.containsKey(id)) return memo.get(id);
        if (!rules.containsKey(id)) {
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

    private static int computeLeftRun(int id, Map<Integer, List<Integer>> rules,
                                      Map<Integer, Integer> memoRun,
                                      Map<Integer, Integer> memoTerminal,
                                      Map<Integer, Integer> memoLength) {
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        List<Integer> rhs = rules.get(id);
        if (rhs.isEmpty()) return 0;

        int base = computeFirstTerminal(id, rules, memoTerminal);
        if (base == -1) {
            memoRun.put(id, 0);
            return 0;
        }

        int run = 0;
        for (int sym : rhs) {
            if (sym < 256) {
                if (sym == base) run++;
                else break;
            } else {
                int subFirst = computeFirstTerminal(sym, rules, memoTerminal);
                if (subFirst != base) break;

                int subRun = computeLeftRun(sym, rules, memoRun, memoTerminal, memoLength);
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
                                       Map<Integer, Integer> memoLength) {
        if (memoRun.containsKey(id)) return memoRun.get(id);
        if (!rules.containsKey(id)) return 0;

        List<Integer> rhs = rules.get(id);
        if (rhs.isEmpty()) return 0;

        int base = computeLastTerminal(id, rules, memoTerminal);
        if (base == -1) {
            memoRun.put(id, 0);
            return 0;
        }

        int run = 0;
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (sym < 256) {
                if (sym == base) run++;
                else break;
            } else {
                int subLast = computeLastTerminal(sym, rules, memoTerminal);
                if (subLast != base) break;

                int subRun = computeRightRun(sym, rules, memoRun, memoTerminal, memoLength);
                run += subRun;

                int subLen = computeLength(sym, rules, memoLength);
                if (subRun < subLen) break;
            }
        }

        memoRun.put(id, run);
        return run;
    }
}
