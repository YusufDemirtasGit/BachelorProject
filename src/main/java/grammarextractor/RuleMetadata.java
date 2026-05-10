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

    public static Map<Integer, RuleMetadata> computeAll(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence,
            Set<Integer> artificialTerminals) {
        return computeAllImpl(rules, sequence, artificialTerminals);
    }

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        return computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals);
    }

    /**
     * Computes all metadata for every rule in one shared topological sort + two linear passes:
     *   1. Bottom-up (leaves first): len, leftTerm, rightTerm, isSB, leftRun, rightRun
     *   2. Top-down  (roots  first): vocc
     *
     * Uses primitive int[] arrays indexed by rule ID to eliminate HashMap boxing overhead.
     */
    private static Map<Integer, RuleMetadata> computeAllImpl(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence,
            Set<Integer> artificialTerminals) {

        if (rules.isEmpty()) {
            return new HashMap<>();
        }

        // ── Determine array size ────────────────────────────────────────────
        int maxId = 255;
        for (int id : rules.keySet()) if (id > maxId) maxId = id;
        if (artificialTerminals != null) {
            for (int id : artificialTerminals) if (id > maxId) maxId = id;
        }
        final int N = maxId + 1;

        // ── Allocate primitive arrays (indexed by symbol id 0..N-1) ────────
        final int[] len   = new int[N];   // expansion length
        final int[] lTerm = new int[N];   // leftmost terminal (-1 = none)
        final int[] rTerm = new int[N];   // rightmost terminal (-1 = none)
        final int[] sb    = new int[N];   // isSingleBlock: 1=true, 0=false
        final int[] lRun  = new int[N];   // left run length
        final int[] rRun  = new int[N];   // right run length
        final int[] vocc  = new int[N];   // virtual occurrence count

        // Initialize terminals 0–255
        for (int t = 0; t < 256; t++) {
            len[t] = 1; lTerm[t] = t; rTerm[t] = t; sb[t] = 1; lRun[t] = 1; rRun[t] = 1;
        }
        // Default rule-range lTerm/rTerm to -1 (will be filled bottom-up)
        Arrays.fill(lTerm, 256, N, -1);
        Arrays.fill(rTerm, 256, N, -1);

        // Initialize artificial terminals (opaque leaf symbols)
        if (artificialTerminals != null) {
            for (int id : artificialTerminals) {
                if (id < N) {
                    len[id] = 1; lTerm[id] = id; rTerm[id] = id;
                    sb[id] = 1; lRun[id] = 1; rRun[id] = 1;
                }
            }
        }

        // ── Topological sort (Kahn's algorithm) ────────────────────────────
        // inDeg[v] = number of rules in `rules` that reference v in their RHS
        final int[] inDeg = new int[N];
        for (List<Integer> rhs : rules.values()) {
            for (int sym : rhs) {
                if (sym < N && rules.containsKey(sym)) inDeg[sym]++;
            }
        }
        final Deque<Integer> queue = new ArrayDeque<>();
        for (int id : rules.keySet()) {
            if (inDeg[id] == 0) queue.add(id);
        }
        final int[] order = new int[rules.size()]; // top-down (roots first)
        int cnt = 0;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            order[cnt++] = u;
            final List<Integer> rhs = rules.get(u);
            if (rhs == null) continue;
            for (int v : rhs) {
                if (v < N && rules.containsKey(v) && --inDeg[v] == 0) queue.add(v);
            }
        }
        if (cnt != rules.size()) {
            System.err.println("Warning: Cycle detected in grammar rules. Metadata may be incomplete.");
        }

        // ── Bottom-up structural pass (reverse order = leaves first) ────────
        for (int i = cnt - 1; i >= 0; i--) {
            final int id = order[i];
            final List<Integer> rhs = rules.get(id);
            if (rhs == null || rhs.isEmpty()) continue;

            // expansion length
            int tLen = 0;
            for (int sym : rhs) tLen += len[sym];
            len[id] = tLen;

            // leftmost terminal (first child with lTerm != -1)
            int lT = -1;
            for (int sym : rhs) { int v = lTerm[sym]; if (v != -1) { lT = v; break; } }
            lTerm[id] = lT;

            // rightmost terminal (last child with rTerm != -1)
            int rT = -1;
            for (int j = rhs.size() - 1; j >= 0; j--) { int v = rTerm[rhs.get(j)]; if (v != -1) { rT = v; break; } }
            rTerm[id] = rT;

            // isSingleBlock: leftTerm == rightTerm AND every child is a single block of the same terminal
            boolean single = (lT != -1) && (lT == rT);
            if (single) {
                for (int sym : rhs) {
                    if (sb[sym] == 0 || lTerm[sym] != lT) { single = false; break; }
                }
            }
            sb[id] = single ? 1 : 0;

            // leftRun: walk RHS left-to-right while child's leftTerm matches; stop when a child's run < its length
            if (lT == -1) {
                lRun[id] = 0;
            } else {
                int run = 0;
                for (int sym : rhs) {
                    if (lTerm[sym] != lT) break;
                    int symLR = lRun[sym];
                    run += symLR;
                    if (symLR < len[sym]) break;
                }
                lRun[id] = run;
            }

            // rightRun: symmetric, walk right-to-left
            if (rT == -1) {
                rRun[id] = 0;
            } else {
                int run = 0;
                for (int j = rhs.size() - 1; j >= 0; j--) {
                    int sym = rhs.get(j);
                    if (rTerm[sym] != rT) break;
                    int symRR = rRun[sym];
                    run += symRR;
                    if (symRR < len[sym]) break;
                }
                rRun[id] = run;
            }
        }

        // ── Vocc: seed from sequence, propagate top-down ────────────────────
        for (int sym : sequence) {
            if (sym < N && rules.containsKey(sym)) vocc[sym]++;
        }
        for (int i = 0; i < cnt; i++) {
            int u = order[i];
            int vU = vocc[u];
            if (vU == 0) continue;
            final List<Integer> rhs = rules.get(u);
            if (rhs == null) continue;
            for (int v : rhs) {
                if (v < N && rules.containsKey(v)) vocc[v] += vU;
            }
        }

        // ── Build result map ─────────────────────────────────────────────────
        final Map<Integer, RuleMetadata> meta = new HashMap<>((int)(rules.size() * 1.4) + 1);
        for (int id : rules.keySet()) {
            meta.put(id, new RuleMetadata(vocc[id], len[id], lTerm[id], rTerm[id],
                                          sb[id] == 1, lRun[id], rRun[id]));
        }
        return meta;
    }

    /**
     * Print metadata for debugging.
     */
    public static void printMetadata(Map<Integer, RuleMetadata> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            System.out.println("No metadata available.");
            return;
        }

        System.out.println("===  Rule Metadata ===");
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

    public static String metadataToString(Map<Integer, RuleMetadata> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "No metadata available.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===  Rule Metadata ===\n");
        for (Map.Entry<Integer, RuleMetadata> entry : metadata.entrySet()) {
            int ruleId = entry.getKey();
            RuleMetadata meta = entry.getValue();
            sb.append(String.format(
                    "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s, leftRun=%d, rightRun=%d%n",
                    ruleId,
                    meta.getVocc(),
                    meta.getLength(),
                    meta.getLeftmostTerminal() == -1 ? "None" : formatSymbol(meta.getLeftmostTerminal()),
                    meta.getRightmostTerminal() == -1 ? "None" : formatSymbol(meta.getRightmostTerminal()),
                    meta.isSingleBlock(),
                    meta.getLeftRunLength(),
                    meta.getRightRunLength()
            ));
        }
        sb.append("========================\n\n");
        return sb.toString();
    }
}
