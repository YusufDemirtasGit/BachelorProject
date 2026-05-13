package grammarextractor;

import java.util.*;

import static grammarextractor.Main.formatSymbol;

public class RuleMetadata {

    // Per-level parallel work pays off only when each level is "fat" enough that the cost of
    // submitting tasks is amortized. Below this many rules in a level, just run that level serial.
    private static final int LEVEL_PARALLEL_THRESHOLD = 4000;

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
        RuleMetadataView v = computeAllImpl(rules, sequence, artificialTerminals, null, null);
        if (v == null) return new HashMap<>();
        return viewToMap(v);
    }

    public static Map<Integer, RuleMetadata> computeAll(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        return computeAll(grammar.grammarRules(), grammar.sequence(), artificialTerminals);
    }

    /** Hot-path entry: returns the array-backed view directly, skipping the Map (phase 6) build. */
    public static RuleMetadataView computeAllView(Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals) {
        return computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals, null, null);
    }

    public static RuleMetadataView computeAllView(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence,
            Set<Integer> artificialTerminals) {
        return computeAllImpl(rules, sequence, artificialTerminals, null, null);
    }

    /** Hot-path entry with a thread pool: phases 4 (bottom-up) and 5 (top-down) parallelize by topo level. */
    public static RuleMetadataView computeAllView(
            Parser.ParsedGrammar grammar, Set<Integer> artificialTerminals, java.util.concurrent.ForkJoinPool pool) {
        return computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals, null, pool);
    }

    /**
     * Profiling entry point: same as {@link #computeAll(Parser.ParsedGrammar, Set)} but emits a
     * per-phase timing string to {@code log} for each internal phase. Use only for diagnostics —
     * the null-check overhead is negligible but the {@code System.nanoTime} calls add up.
     */
    public static Map<Integer, RuleMetadata> computeAllProfiled(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals,
            java.util.function.Consumer<String> log) {
        RuleMetadataView v = computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals, log, null);
        if (v == null) return new HashMap<>();
        long t5 = System.nanoTime();
        Map<Integer, RuleMetadata> m = viewToMap(v);
        long t6 = System.nanoTime();
        log.accept(String.format("  phase 6 (build result map):     %8.2f ms", (t6 - t5) / 1e6));
        return m;
    }

    /** View-returning profiled entry: skips phase 6 entirely, logs phases 1–5. */
    public static RuleMetadataView computeAllViewProfiled(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals,
            java.util.function.Consumer<String> log) {
        return computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals, log, null);
    }

    /** Profiled view-returning entry with a pool: also exercises the level-parallel paths. */
    public static RuleMetadataView computeAllViewProfiled(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals,
            java.util.function.Consumer<String> log,
            java.util.concurrent.ForkJoinPool pool) {
        return computeAllImpl(grammar.grammarRules(), grammar.sequence(), artificialTerminals, log, pool);
    }

    // Per-rule vocc propagation. Reads vocc[u] (frozen — u is at an already-completed level)
    // and atomically adds it to every rule-child's vocc slot. Across threads in the same level,
    // multiple parents can target the same child, so the add must be atomic.
    private static void voccPropagateOneRule(
            int u,
            List<Integer>[] rhsArr,
            java.util.concurrent.atomic.AtomicIntegerArray vocc,
            boolean[] isRule,
            int N
    ) {
        int vU = vocc.get(u);
        if (vU == 0) return;
        final List<Integer> rhs = rhsArr[u];
        if (rhs == null) return;
        for (int v : rhs) {
            if (v < N && isRule[v]) vocc.addAndGet(v, vU);
        }
    }

    // Per-rule structural body extracted so both the serial and the level-parallel path call
    // the same code. Writes to len/lTerm/rTerm/sb/lRun/rRun for `id` only; reads its children.
    // Children are at strictly greater topological levels and were processed in the previous level
    // iteration, so within a level there are no read-after-write conflicts across threads.
    private static void structuralPassOneRule(
            int id,
            List<Integer>[] rhsArr,
            int[] len, int[] lTerm, int[] rTerm,
            int[] sb, int[] lRun, int[] rRun
    ) {
        final List<Integer> rhs = rhsArr[id];
        if (rhs == null || rhs.isEmpty()) return;

        int lT = -1;
        for (int sym : rhs) { int v = lTerm[sym]; if (v != -1) { lT = v; break; } }
        lTerm[id] = lT;

        int rT = -1;
        for (int j = rhs.size() - 1; j >= 0; j--) { int v = rTerm[rhs.get(j)]; if (v != -1) { rT = v; break; } }
        rTerm[id] = rT;

        int tLen = 0;
        boolean single = (lT != -1) && (lT == rT);
        int lRunAcc = 0;
        boolean lRunDone = (lT == -1);
        for (int sym : rhs) {
            final int symLen = len[sym];
            tLen += symLen;
            if (single && (sb[sym] == 0 || lTerm[sym] != lT)) single = false;
            if (!lRunDone) {
                if (lTerm[sym] != lT) {
                    lRunDone = true;
                } else {
                    final int symLR = lRun[sym];
                    lRunAcc += symLR;
                    if (symLR < symLen) lRunDone = true;
                }
            }
        }
        len[id]  = tLen;
        sb[id]   = single ? 1 : 0;
        lRun[id] = lRunAcc;

        if (rT != -1) {
            int run = 0;
            for (int j = rhs.size() - 1; j >= 0; j--) {
                final int sym = rhs.get(j);
                if (rTerm[sym] != rT) break;
                final int symRR = rRun[sym];
                run += symRR;
                if (symRR < len[sym]) break;
            }
            rRun[id] = run;
        }
    }

    /**
     * Builds a boxed {@code Map<Integer, RuleMetadata>} from an array-backed view. This is the
     * old "phase 6" of {@code computeAll} — kept available for legacy callers (tests, UI) that
     * still want the Map shape. Hot paths should use the view directly.
     */
    public static Map<Integer, RuleMetadata> viewToMap(RuleMetadataView view) {
        int[] ids = view.ruleIds();
        Map<Integer, RuleMetadata> meta = new HashMap<>((int)(ids.length * 1.4) + 1);
        for (int id : ids) {
            meta.put(id, new RuleMetadata(
                    view.getVocc(id), view.getLength(id),
                    view.getLeftmostTerminal(id), view.getRightmostTerminal(id),
                    view.isSingleBlock(id),
                    view.getLeftRunLength(id), view.getRightRunLength(id)));
        }
        return meta;
    }

    /**
     * Computes all metadata for every rule in one shared topological sort + two linear passes:
     *   1. Bottom-up (leaves first): len, leftTerm, rightTerm, isSB, leftRun, rightRun
     *   2. Top-down  (roots  first): vocc
     *
     * Uses primitive int[] arrays indexed by rule ID to eliminate HashMap boxing overhead.
     * Returns an array-backed view; no per-rule {@link RuleMetadata} object allocation here.
     *
     * When {@code log != null}, emits a per-phase timing line at each phase boundary.
     */
    private static RuleMetadataView computeAllImpl(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence,
            Set<Integer> artificialTerminals,
            java.util.function.Consumer<String> log,
            java.util.concurrent.ForkJoinPool pool) {

        final boolean prof = (log != null);
        long t0 = prof ? System.nanoTime() : 0L;

        if (rules.isEmpty()) {
            return null;
        }

        // ── Determine array size ────────────────────────────────────────────
        int maxId = 255;
        for (int id : rules.keySet()) if (id > maxId) maxId = id;
        if (artificialTerminals != null) {
            for (int id : artificialTerminals) if (id > maxId) maxId = id;
        }
        final int N = maxId + 1;
        long t1 = prof ? System.nanoTime() : 0L;
        if (prof) log.accept(String.format("  phase 1 (maxId scan):           %8.2f ms   [N=%d, rules=%d]", (t1 - t0) / 1e6, N, rules.size()));

        // ── Allocate primitive arrays (indexed by symbol id 0..N-1) ────────
        final int[] len   = new int[N];   // expansion length
        final int[] lTerm = new int[N];   // leftmost terminal (-1 = none)
        final int[] rTerm = new int[N];   // rightmost terminal (-1 = none)
        final int[] sb    = new int[N];   // isSingleBlock: 1=true, 0=false
        final int[] lRun  = new int[N];   // left run length
        final int[] rRun  = new int[N];   // right run length
        final int[] vocc  = new int[N];   // virtual occurrence count
        // isRule[id] replaces rules.containsKey(id) in all inner loops, avoiding HashMap boxing.
        // rhsArr[id] replaces rules.get(id). ruleIds[] avoids rules.keySet() unboxing.
        final boolean[] isRule = new boolean[N];
        @SuppressWarnings("unchecked")
        final List<Integer>[] rhsArr = new List[N];
        final int[] ruleIds = new int[rules.size()];
        int ruleCount = 0;
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            final int id = e.getKey();
            isRule[id] = true;
            rhsArr[id] = e.getValue();
            ruleIds[ruleCount++] = id;
        }

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
                    isRule[id] = false; // artificial terminals are not proper rules
                    len[id] = 1; lTerm[id] = id; rTerm[id] = id;
                    sb[id] = 1; lRun[id] = 1; rRun[id] = 1;
                }
            }
        }
        long t2 = prof ? System.nanoTime() : 0L;
        if (prof) log.accept(String.format("  phase 2 (allocate + init):      %8.2f ms", (t2 - t1) / 1e6));

        // ── Topological sort (Kahn's algorithm) ────────────────────────────
        // inDeg[v] = number of rules in `rules` that reference v in their RHS
        final int[] inDeg = new int[N];
        for (int id = 256; id < N; id++) {
            final List<Integer> rhs = rhsArr[id];
            if (rhs == null) continue;
            for (int sym : rhs) {
                if (sym < N && isRule[sym]) inDeg[sym]++;
            }
        }
        // Primitive int[] stack: any valid topological order (DFS or BFS) is correct here.
        // Also compute level[id] for parallel phase 4/5: roots have level 0, leaves have max level.
        // A child's level is one greater than the deepest parent that reaches it.
        final int[] stack = new int[ruleCount];
        int top = 0;
        for (int id : ruleIds) {
            if (inDeg[id] == 0) stack[top++] = id;
        }
        final int[] order = new int[ruleCount]; // top-down (roots first)
        final int[] level = new int[N];          // depth-from-roots; 0 = root
        int cnt = 0;
        int maxLevel = 0;
        while (top > 0) {
            int u = stack[--top];
            order[cnt++] = u;
            final int lu = level[u];
            final List<Integer> rhs = rhsArr[u];
            if (rhs == null) continue;
            for (int v : rhs) {
                if (v < N && isRule[v]) {
                    int nl = lu + 1;
                    if (nl > level[v]) level[v] = nl;
                    if (nl > maxLevel) maxLevel = nl;
                    if (--inDeg[v] == 0) stack[top++] = v;
                }
            }
        }
        if (cnt != ruleCount) {
            System.err.println("Warning: Cycle detected in grammar rules. Metadata may be incomplete.");
        }

        // Bucket rules by level for parallel processing within each level.
        final int[] levelStarts = new int[maxLevel + 2];
        for (int id : ruleIds) levelStarts[level[id] + 1]++;
        for (int i = 1; i <= maxLevel + 1; i++) levelStarts[i] += levelStarts[i - 1];
        final int[] byLevel = new int[ruleCount];
        final int[] writePos = Arrays.copyOf(levelStarts, levelStarts.length);
        for (int id : ruleIds) byLevel[writePos[level[id]]++] = id;

        // Find the widest level. If no level reaches the parallel threshold, parallel dispatch
        // cannot help and forces atomic ops with no upside — fall back to serial unconditionally.
        // (RePair-style binary-rule chains produce narrow DAGs where this is the common case.)
        int maxLevelWidth = 0;
        for (int L = 0; L <= maxLevel; L++) {
            int width = levelStarts[L + 1] - levelStarts[L];
            if (width > maxLevelWidth) maxLevelWidth = width;
        }
        final java.util.concurrent.ForkJoinPool effPool =
                (pool != null && maxLevelWidth >= LEVEL_PARALLEL_THRESHOLD) ? pool : null;

        long t3 = prof ? System.nanoTime() : 0L;
        if (prof) log.accept(String.format("  phase 3 (topo sort + levels):   %8.2f ms   [maxLevel=%d, widest=%d, parallel=%s]", (t3 - t2) / 1e6, maxLevel, maxLevelWidth, effPool != null));

        // ── Bottom-up structural pass (deepest level first) ─────────────────
        // Rules at level L only depend on rules at levels > L (their children), so each level
        // can be processed in any order — including in parallel — once the level above is done.
        // When effPool is null (the DAG is too narrow to benefit), this degenerates to a single
        // serial walk equivalent to iterating `order[]` in reverse.
        for (int L = maxLevel; L >= 0; L--) {
            final int s = levelStarts[L];
            final int e = levelStarts[L + 1];
            if (s >= e) continue;
            if (effPool != null && (e - s) >= LEVEL_PARALLEL_THRESHOLD) {
                final int p = effPool.getParallelism();
                java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>(p);
                for (int t = 0; t < p; t++) {
                    final int cs = s + (int)((long)(e - s) * t / p);
                    final int ce = s + (int)((long)(e - s) * (t + 1) / p);
                    if (cs >= ce) continue;
                    fs.add(effPool.submit(() -> {
                        for (int k = cs; k < ce; k++) {
                            structuralPassOneRule(byLevel[k], rhsArr, len, lTerm, rTerm, sb, lRun, rRun);
                        }
                    }));
                }
                try { for (java.util.concurrent.Future<?> f : fs) f.get(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Phase 4 interrupted", ie);
                } catch (java.util.concurrent.ExecutionException ee) {
                    throw new RuntimeException("Phase 4 task failed", ee.getCause());
                }
            } else {
                for (int k = s; k < e; k++) {
                    structuralPassOneRule(byLevel[k], rhsArr, len, lTerm, rTerm, sb, lRun, rRun);
                }
            }
        }
        long t4 = prof ? System.nanoTime() : 0L;
        if (prof) log.accept(String.format("  phase 4 (bottom-up structural): %8.2f ms", (t4 - t3) / 1e6));

        // ── Vocc: seed from sequence, propagate top-down ────────────────────
        // Multiple rules at the same topological level can write to the same child's vocc,
        // so the parallel path uses AtomicIntegerArray for those writes. The serial path uses
        // a plain int[] directly (no atomic overhead). Sequential barriers between levels
        // ensure all writes from level L are visible before level L+1 starts reading.
        final boolean parallelPhase5 = (effPool != null);
        final java.util.concurrent.atomic.AtomicIntegerArray voccA =
                parallelPhase5 ? new java.util.concurrent.atomic.AtomicIntegerArray(N) : null;

        if (parallelPhase5) {
            for (int sym : sequence) {
                if (sym < N && isRule[sym]) voccA.incrementAndGet(sym);
            }
            for (int L = 0; L <= maxLevel; L++) {
                final int s = levelStarts[L];
                final int e = levelStarts[L + 1];
                if (s >= e) continue;
                if ((e - s) >= LEVEL_PARALLEL_THRESHOLD) {
                    final int p = effPool.getParallelism();
                    java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>(p);
                    for (int t = 0; t < p; t++) {
                        final int cs = s + (int)((long)(e - s) * t / p);
                        final int ce = s + (int)((long)(e - s) * (t + 1) / p);
                        if (cs >= ce) continue;
                        fs.add(effPool.submit(() -> {
                            for (int k = cs; k < ce; k++) {
                                voccPropagateOneRule(byLevel[k], rhsArr, voccA, isRule, N);
                            }
                        }));
                    }
                    try { for (java.util.concurrent.Future<?> f : fs) f.get(); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Phase 5 interrupted", ie);
                    } catch (java.util.concurrent.ExecutionException ee) {
                        throw new RuntimeException("Phase 5 task failed", ee.getCause());
                    }
                } else {
                    for (int k = s; k < e; k++) {
                        voccPropagateOneRule(byLevel[k], rhsArr, voccA, isRule, N);
                    }
                }
            }
            // Snapshot atomic array into the plain int[] backing the view.
            for (int i = 0; i < N; i++) vocc[i] = voccA.get(i);
        } else {
            for (int sym : sequence) {
                if (sym < N && isRule[sym]) vocc[sym]++;
            }
            for (int i = 0; i < cnt; i++) {
                int u = order[i];
                int vU = vocc[u];
                if (vU == 0) continue;
                final List<Integer> rhs = rhsArr[u];
                if (rhs == null) continue;
                for (int v : rhs) {
                    if (v < N && isRule[v]) vocc[v] += vU;
                }
            }
        }

        long t5 = prof ? System.nanoTime() : 0L;
        if (prof) {
            log.accept(String.format("  phase 5 (vocc top-down):        %8.2f ms", (t5 - t4) / 1e6));
            log.accept(String.format("  TOTAL (view, phases 1–5):       %8.2f ms", (t5 - t0) / 1e6));
        }

        // Trim ruleIds to actual count (computed slot 'ruleCount' equals rules.size() but we used
        // a pre-sized array; defensively pass the slice).
        final int[] idsExact;
        if (ruleCount == ruleIds.length) {
            idsExact = ruleIds;
        } else {
            idsExact = Arrays.copyOf(ruleIds, ruleCount);
        }
        return new RuleMetadataView(N, len, lTerm, rTerm, sb, lRun, rRun, vocc, isRule, idsExact);
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
