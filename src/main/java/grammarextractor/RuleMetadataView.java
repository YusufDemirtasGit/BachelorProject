package grammarextractor;

/**
 * Array-backed view of per-rule metadata. Replaces {@code Map<Integer, RuleMetadata>} on the hot
 * path: queries are pure int[] indexed loads (no HashMap lookup, no autoboxing, no per-rule
 * object allocation).
 *
 * <p>Returned by {@link RuleMetadata#computeAllView}. For legacy callers that need the boxed
 * {@code Map<Integer, RuleMetadata>}, use {@link RuleMetadata#viewToMap(RuleMetadataView, int[])}.
 *
 * <p>All accessors take a raw rule id and assume {@code 0 <= id < size()}. {@code has(id)} is the
 * canonical "is this a real rule?" check (returns false for artificial terminals and gaps in the
 * id range). For symbols outside {@code [0, size())} the result is undefined — callers must
 * range-check first.
 */
public final class RuleMetadataView {
    private final int N;
    private final int[] len;
    private final int[] lTerm;
    private final int[] rTerm;
    private final int[] sb;
    private final int[] lRun;
    private final int[] rRun;
    private final int[] vocc;
    private final boolean[] isRule;
    private final int[] ruleIds;

    RuleMetadataView(int N,
                     int[] len, int[] lTerm, int[] rTerm,
                     int[] sb, int[] lRun, int[] rRun,
                     int[] vocc, boolean[] isRule, int[] ruleIds) {
        this.N = N;
        this.len = len;
        this.lTerm = lTerm;
        this.rTerm = rTerm;
        this.sb = sb;
        this.lRun = lRun;
        this.rRun = rRun;
        this.vocc = vocc;
        this.isRule = isRule;
        this.ruleIds = ruleIds;
    }

    public int size() { return N; }

    public boolean has(int id) {
        return id >= 0 && id < N && isRule[id];
    }

    public int getLength(int id)             { return len[id]; }
    public int getLeftmostTerminal(int id)   { return lTerm[id]; }
    public int getRightmostTerminal(int id)  { return rTerm[id]; }
    public boolean isSingleBlock(int id)     { return sb[id] == 1; }
    public int getLeftRunLength(int id)      { return lRun[id]; }
    public int getRightRunLength(int id)     { return rRun[id]; }
    public int getVocc(int id)               { return vocc[id]; }

    /** Returns the rule IDs in topological key order (insertion order from the source rules map). */
    int[] ruleIds() {
        return ruleIds;
    }

    public int ruleCount() {
        return ruleIds.length;
    }
}
