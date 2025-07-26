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

    // Create new metadata with updated vocc
    public RuleMetadata withVocc(int newVocc) {
        return new RuleMetadata(newVocc, length, leftmostTerminal, rightmostTerminal,
                isSB, leftRunLength, rightRunLength);
    }

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar,
                                                        Set<Integer> artificialTerminals) {
        if (artificialTerminals == null) {
            artificialTerminals = Collections.emptySet();
        }

        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();

        Map<Integer, RuleMetadata> meta = new HashMap<>();

        // First compute vocc for all rules
        Map<Integer, Integer> voccMap = computeVoccAll(rules, sequence);

        // Then compute other metadata in topological order
        List<Integer> topoOrder = getTopologicalOrder(rules);

        for (int ruleId : topoOrder) {
            if (!rules.containsKey(ruleId)) continue;

            List<Integer> rhs = rules.get(ruleId);
            int vocc = voccMap.getOrDefault(ruleId, 0);

            // Compute metadata based on RHS and already computed children
            RuleMetadata ruleMeta = computeRuleMetadata(
                    ruleId, rhs, vocc, meta, artificialTerminals
            );

            meta.put(ruleId, ruleMeta);
        }

        return meta;
    }

    private static RuleMetadata computeRuleMetadata(
            int ruleId,
            List<Integer> rhs,
            int vocc,
            Map<Integer, RuleMetadata> computedMeta,
            Set<Integer> artificialTerminals
    ) {
        if (rhs.isEmpty()) {
            return new RuleMetadata(vocc, 0, -1, -1, false, 0, 0);
        }

        // Calculate length
        int length = 0;
        for (int sym : rhs) {
            if (sym < 256) {
                length++;
            } else if (artificialTerminals.contains(sym)) {
                // Artificial terminals count as 1
                length++;
            } else if (computedMeta.containsKey(sym)) {
                length += computedMeta.get(sym).getLength();
            }
        }

        // Find leftmost terminal
        int leftmost = -1;
        for (int sym : rhs) {
            if (sym < 256 || artificialTerminals.contains(sym)) {
                leftmost = sym;
                break;
            } else if (computedMeta.containsKey(sym)) {
                leftmost = computedMeta.get(sym).getLeftmostTerminal();
                if (leftmost != -1) break;
            }
        }

        // Find rightmost terminal
        int rightmost = -1;
        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);
            if (sym < 256 || artificialTerminals.contains(sym)) {
                rightmost = sym;
                break;
            } else if (computedMeta.containsKey(sym)) {
                rightmost = computedMeta.get(sym).getRightmostTerminal();
                if (rightmost != -1) break;
            }
        }

        // Check if single block
        boolean isSB = checkSingleBlock(rhs, computedMeta, artificialTerminals);

        // Compute run lengths
        int leftRun = 0, rightRun = 0;
        if (isSB && leftmost != -1) {
            leftRun = computeLeftRunLength(rhs, leftmost, computedMeta, artificialTerminals);
            rightRun = computeRightRunLength(rhs, rightmost, computedMeta, artificialTerminals);
        }

        return new RuleMetadata(vocc, length, leftmost, rightmost, isSB, leftRun, rightRun);
    }

    private static boolean checkSingleBlock(
            List<Integer> rhs,
            Map<Integer, RuleMetadata> meta,
            Set<Integer> artificialTerminals
    ) {
        if (rhs.isEmpty()) return false;

        Integer baseTerminal = null;

        for (int sym : rhs) {
            if (sym < 256 || artificialTerminals.contains(sym)) {
                if (baseTerminal == null) {
                    baseTerminal = sym;
                } else if (!baseTerminal.equals(sym)) {
                    return false;
                }
            } else if (meta.containsKey(sym)) {
                RuleMetadata symMeta = meta.get(sym);
                if (!symMeta.isSingleBlock()) return false;

                int symBase = symMeta.getLeftmostTerminal();
                if (baseTerminal == null) {
                    baseTerminal = symBase;
                } else if (baseTerminal != symBase) {
                    return false;
                }
            }
        }

        return baseTerminal != null;
    }

    private static int computeLeftRunLength(
            List<Integer> rhs,
            int baseTerminal,
            Map<Integer, RuleMetadata> meta,
            Set<Integer> artificialTerminals
    ) {
        int run = 0;

        for (int sym : rhs) {
            if (sym < 256 || artificialTerminals.contains(sym)) {
                if (sym == baseTerminal) {
                    run++;
                } else {
                    break;
                }
            } else if (meta.containsKey(sym)) {
                RuleMetadata symMeta = meta.get(sym);
                if (symMeta.getLeftmostTerminal() != baseTerminal) break;

                int symLeftRun = symMeta.getLeftRunLength();
                run += symLeftRun;

                // If the symbol doesn't consist entirely of the base terminal, stop
                if (symLeftRun < symMeta.getLength()) break;
            }
        }

        return run;
    }

    private static int computeRightRunLength(
            List<Integer> rhs,
            int baseTerminal,
            Map<Integer, RuleMetadata> meta,
            Set<Integer> artificialTerminals
    ) {
        int run = 0;

        for (int i = rhs.size() - 1; i >= 0; i--) {
            int sym = rhs.get(i);

            if (sym < 256 || artificialTerminals.contains(sym)) {
                if (sym == baseTerminal) {
                    run++;
                } else {
                    break;
                }
            } else if (meta.containsKey(sym)) {
                RuleMetadata symMeta = meta.get(sym);
                if (symMeta.getRightmostTerminal() != baseTerminal) break;

                int symRightRun = symMeta.getRightRunLength();
                run += symRightRun;

                // If the symbol doesn't consist entirely of the base terminal, stop
                if (symRightRun < symMeta.getLength()) break;
            }
        }

        return run;
    }

    private static Map<Integer, Integer> computeVoccAll(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence
    ) {
        Map<Integer, Integer> vocc = new HashMap<>();

        // Initialize vocc from sequence
        for (int sym : sequence) {
            if (sym >= 256) {
                vocc.merge(sym, 1, Integer::sum);
            }
        }

        // Build dependency graph
        Map<Integer, Set<Integer>> dependencies = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int parent = entry.getKey();
            dependencies.putIfAbsent(parent, new HashSet<>());

            for (int sym : entry.getValue()) {
                if (sym >= 256) {
                    dependencies.get(parent).add(sym);
                }
            }
        }

        // Propagate vocc in topological order
        List<Integer> topoOrder = getTopologicalOrder(rules);

        for (int rule : topoOrder) {
            int ruleVocc = vocc.getOrDefault(rule, 0);
            if (ruleVocc == 0) continue;

            List<Integer> rhs = rules.get(rule);
            if (rhs == null) continue;

            for (int sym : rhs) {
                if (sym >= 256) {
                    vocc.merge(sym, ruleVocc, Integer::sum);
                }
            }
        }

        return vocc;
    }

    private static List<Integer> getTopologicalOrder(Map<Integer, List<Integer>> rules) {
        // Compute in-degrees
        Map<Integer, Integer> indegree = new HashMap<>();
        Set<Integer> allRules = new HashSet<>(rules.keySet());

        for (int rule : allRules) {
            indegree.putIfAbsent(rule, 0);
        }

        for (List<Integer> rhs : rules.values()) {
            for (int sym : rhs) {
                if (sym >= 256 && allRules.contains(sym)) {
                    indegree.merge(sym, 1, Integer::sum);
                }
            }
        }

        // Topological sort
        Queue<Integer> queue = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<Integer> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            int rule = queue.poll();
            order.add(rule);

            List<Integer> rhs = rules.get(rule);
            if (rhs != null) {
                for (int sym : rhs) {
                    if (sym >= 256 && indegree.containsKey(sym)) {
                        int newDegree = indegree.get(sym) - 1;
                        indegree.put(sym, newDegree);
                        if (newDegree == 0) {
                            queue.add(sym);
                        }
                    }
                }
            }
        }

        if (order.size() != allRules.size()) {
            throw new IllegalStateException("Cycle detected in grammar rules");
        }

        return order;
    }

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar) {
        return computeAll(grammar, Collections.emptySet());
    }
}