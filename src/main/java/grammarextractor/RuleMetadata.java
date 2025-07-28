package grammarextractor;

import java.util.*;

import static grammarextractor.Main.formatSymbol;

public class RuleMetadata {
    // Block represents a maximal run of a single symbol (e.g., a³ for "aaa")
    public static class Block {
        public final int symbol;
        public final int runLength;

        public Block(int symbol, int runLength) {
            this.symbol = symbol;
            this.runLength = runLength;
        }

        @Override
        public String toString() {
            return formatSymbol(symbol) + "^" + runLength;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Block)) return false;
            Block other = (Block) o;
            return symbol == other.symbol && runLength == other.runLength;
        }
    }

    private final int vocc;          // v_occ(X) - number of occurrences in derivation tree
    private final int length;        // |val(X)| - total length of derived string
    private final Block leftmostBlock;   // λ(X) - leftmost block in val(X)
    private final Block rightmostBlock;  // ρ(X) - rightmost block in val(X)
    private final boolean isSB;      // isSB(X) - true if val(X) consists of a single block

    public RuleMetadata(int vocc, int length, Block leftmostBlock, Block rightmostBlock, boolean isSB) {
        this.vocc = vocc;
        this.length = length;
        this.leftmostBlock = leftmostBlock;
        this.rightmostBlock = rightmostBlock;
        this.isSB = isSB;
    }

    // Getters
    public int getVocc() { return vocc; }
    public int getLength() { return length; }
    public Block getLeftmostBlock() { return leftmostBlock; }
    public Block getRightmostBlock() { return rightmostBlock; }
    public boolean isSingleBlock() { return isSB; }

    // For compatibility with existing code
    public int getLeftmostTerminal() {
        return leftmostBlock != null ? leftmostBlock.symbol : -1;
    }
    public int getRightmostTerminal() {
        return rightmostBlock != null ? rightmostBlock.symbol : -1;
    }
    public int getLeftRunLength() {
        return leftmostBlock != null ? leftmostBlock.runLength : 0;
    }
    public int getRightRunLength() {
        return rightmostBlock != null ? rightmostBlock.runLength : 0;
    }

    /**
     * Compute metadata for all rules in the grammar.
     * Following the paper's approach more closely.
     */
    public static Map<Integer, RuleMetadata> computeAll(
            Parser.ParsedGrammar grammar,
            Set<Integer> artificialTerminals) {

        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        List<Integer> sequence = grammar.sequence();
        Map<Integer, RuleMetadata> meta = new HashMap<>();

        // 1. Compute vocc for all rules (unchanged)
        Map<Integer, Integer> allVocc = computeVocc(rules, sequence);

        // 2. Compute metadata bottom-up
        // We need to process in topological order (already guaranteed by rule numbering)
        for (int ruleId : rules.keySet()) {
            RuleMetadata ruleMeta = computeRuleMetadata(
                    ruleId, rules, allVocc, meta, artificialTerminals
            );
            meta.put(ruleId, ruleMeta);
        }

        return meta;
    }

    /**
     * Compute metadata for a single rule.
     */
    private static RuleMetadata computeRuleMetadata(
            int ruleId,
            Map<Integer, List<Integer>> rules,
            Map<Integer, Integer> voccMap,
            Map<Integer, RuleMetadata> computedMeta,
            Set<Integer> artificialTerminals) {

        int vocc = voccMap.getOrDefault(ruleId, 0);

        // For terminals and artificial terminals, create simple metadata
        if (isTerminalOrArtificial(ruleId, artificialTerminals)) {
            Block singleBlock = new Block(ruleId, 1);
            return new RuleMetadata(vocc, 1, singleBlock, singleBlock, true);
        }

        List<Integer> rhs = rules.get(ruleId);
        if (rhs == null || rhs.isEmpty()) {
            return new RuleMetadata(vocc, 0, null, null, false);
        }

        // Compute the full expansion to get blocks
        List<Block> expansion = computeBlockExpansion(rhs, computedMeta, rules, artificialTerminals);

        if (expansion.isEmpty()) {
            return new RuleMetadata(vocc, 0, null, null, false);
        }

        // Compute length
        int length = 0;
        for (Block block : expansion) {
            length += block.runLength;
        }

        // Get leftmost and rightmost blocks
        Block leftmost = expansion.get(0);
        Block rightmost = expansion.get(expansion.size() - 1);

        // Check if single block
        boolean isSB = (expansion.size() == 1);

        return new RuleMetadata(vocc, length, leftmost, rightmost, isSB);
    }

    /**
     * Compute the block expansion of a rule's RHS.
     * This expands variables and merges adjacent runs of the same symbol into blocks.
     */
    private static List<Block> computeBlockExpansion(
            List<Integer> rhs,
            Map<Integer, RuleMetadata> computedMeta,
            Map<Integer, List<Integer>> rules,
            Set<Integer> artificialTerminals) {

        List<Block> result = new ArrayList<>();

        for (int sym : rhs) {
            if (isTerminalOrArtificial(sym, artificialTerminals)) {
                // Terminal or artificial terminal - single block of length 1
                addBlock(result, new Block(sym, 1));
            } else {
                // Variable - get its expansion
                RuleMetadata symMeta = computedMeta.get(sym);
                if (symMeta == null || symMeta.getLength() == 0) {
                    continue;
                }

                if (symMeta.isSingleBlock()) {
                    // Single block variable - add its block
                    addBlock(result, symMeta.getLeftmostBlock());
                } else {
                    // Multi-block variable - need to expand it
                    List<Block> symExpansion = computeBlockExpansion(
                            rules.get(sym), computedMeta, rules, artificialTerminals
                    );
                    for (Block block : symExpansion) {
                        addBlock(result, block);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Add a block to the result, merging with the last block if they have the same symbol.
     */
    private static void addBlock(List<Block> blocks, Block newBlock) {
        if (blocks.isEmpty()) {
            blocks.add(newBlock);
            return;
        }

        Block lastBlock = blocks.get(blocks.size() - 1);
        if (lastBlock.symbol == newBlock.symbol) {
            // Merge blocks
            blocks.set(blocks.size() - 1,
                    new Block(lastBlock.symbol, lastBlock.runLength + newBlock.runLength));
        } else {
            blocks.add(newBlock);
        }
    }

    /**
     * Compute vocc (unchanged from original)
     */
    private static Map<Integer, Integer> computeVocc(
            Map<Integer, List<Integer>> rules,
            List<Integer> sequence) {

        Map<Integer, Integer> vocc = new HashMap<>();
        Map<Integer, Integer> indeg = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> mult = new HashMap<>();

        // Initialize
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
                    "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s%n",
                    ruleId,
                    meta.getVocc(),
                    meta.getLength(),
                    meta.getLeftmostBlock() != null ? meta.getLeftmostBlock() : "None",
                    meta.getRightmostBlock() != null ? meta.getRightmostBlock() : "None",
                    meta.isSingleBlock()
            );
        }
        System.out.println("========================\n");
    }
}