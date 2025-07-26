package grammarextractor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Grammar representation that matches the paper's special CFG format.
 * Each rule has form: X → X' wX X" where wX is run-length encoded.
 */
public class CompressedGrammar {
    private final Map<Integer, CompressedRule> rules;
    private final List<Integer> sequence;
    private final Map<Integer, RuleMetadata> metadata;

    public CompressedGrammar(Map<Integer, CompressedRule> rules,
                             List<Integer> sequence,
                             Map<Integer, RuleMetadata> metadata) {
        this.rules = new HashMap<>(rules);
        this.sequence = new ArrayList<>(sequence);
        this.metadata = new HashMap<>(metadata);
    }

    /**
     * Compressed rule representation: X → X' wX X"
     */
    public static class CompressedRule {
        private final Integer leftVariable;   // X' (can be null)
        private final List<Block> middleBlocks; // wX run-length encoded
        private final Integer rightVariable;  // X" (can be null)

        public CompressedRule(Integer leftVariable,
                              List<Block> middleBlocks,
                              Integer rightVariable) {
            this.leftVariable = leftVariable;
            this.middleBlocks = new ArrayList<>(middleBlocks);
            this.rightVariable = rightVariable;
        }

        // Getters
        public Integer getLeftVariable() { return leftVariable; }
        public List<Block> getMiddleBlocks() { return new ArrayList<>(middleBlocks); }
        public Integer getRightVariable() { return rightVariable; }

        /**
         * Get the size of this rule (|Dh(X)|rle in the paper)
         */
        public int getSize() {
            int size = middleBlocks.size();
            if (leftVariable != null) size++;
            if (rightVariable != null) size++;
            return size;
        }

        /**
         * Convert to standard list representation for compatibility
         */
        public List<Integer> toList() {
            List<Integer> result = new ArrayList<>();
            if (leftVariable != null) {
                result.add(leftVariable);
            }
            for (Block block : middleBlocks) {
                for (int i = 0; i < block.count; i++) {
                    result.add(block.symbol);
                }
            }
            if (rightVariable != null) {
                result.add(rightVariable);
            }
            return result;
        }

        /**
         * Create from standard list representation
         */
        public static CompressedRule fromList(List<Integer> rhs) {
            if (rhs.isEmpty()) {
                return new CompressedRule(null, Collections.emptyList(), null);
            }

            Integer leftVar = null;
            Integer rightVar = null;
            List<Block> blocks = new ArrayList<>();

            int start = 0;
            int end = rhs.size();

            // Check for left variable
            if (!rhs.isEmpty() && rhs.get(0) >= 256) {
                leftVar = rhs.get(0);
                start = 1;
            }

            // Check for right variable
            if (end > start && rhs.get(end - 1) >= 256) {
                rightVar = rhs.get(end - 1);
                end--;
            }

            // Run-length encode the middle part
            int i = start;
            while (i < end) {
                int symbol = rhs.get(i);
                int count = 1;
                while (i + count < end && rhs.get(i + count) == symbol) {
                    count++;
                }
                blocks.add(new Block(symbol, count));
                i += count;
            }

            return new CompressedRule(leftVar, blocks, rightVar);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (leftVariable != null) {
                sb.append("R").append(leftVariable).append(" ");
            }
            for (Block block : middleBlocks) {
                sb.append(block).append(" ");
            }
            if (rightVariable != null) {
                sb.append("R").append(rightVariable);
            }
            return sb.toString().trim();
        }
    }

    /**
     * Run-length encoded block
     */
    public static class Block {
        public final int symbol;
        public final int count;

        public Block(int symbol, int count) {
            this.symbol = symbol;
            this.count = count;
        }

        @Override
        public String toString() {
            if (count == 1) {
                return formatSymbol(symbol);
            } else {
                return formatSymbol(symbol) + "^" + count;
            }
        }

        private String formatSymbol(int sym) {
            if (sym < 32) return "\\x" + Integer.toHexString(sym);
            if (sym < 127) return String.valueOf((char) sym);
            return "R" + sym;
        }
    }

    // Conversion methods

    /**
     * Convert from standard ParsedGrammar to CompressedGrammar
     */
    public static CompressedGrammar fromParsedGrammar(Parser.ParsedGrammar parsed,
                                                      Set<Integer> artificialTerminals) {
        Map<Integer, CompressedRule> compressedRules = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : parsed.grammarRules().entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            compressedRules.put(ruleId, CompressedRule.fromList(rhs));
        }

        // Recompute metadata for compressed format
        Map<Integer, RuleMetadata> metadata =
                computeMetadataForCompressedGrammar(compressedRules, parsed.sequence(), artificialTerminals);

        return new CompressedGrammar(compressedRules, parsed.sequence(), metadata);
    }

    /**
     * Convert back to standard ParsedGrammar
     */
    public Parser.ParsedGrammar toParsedGrammar() {
        Map<Integer, List<Integer>> standardRules = new HashMap<>();

        for (Map.Entry<Integer, CompressedRule> entry : rules.entrySet()) {
            standardRules.put(entry.getKey(), entry.getValue().toList());
        }

        return new Parser.ParsedGrammar(standardRules, sequence, metadata);
    }

    /**
     * Compute total size |Sh| as defined in the paper
     */
    public int getTotalSize() {
        return rules.values().stream()
                .mapToInt(CompressedRule::getSize)
                .sum();
    }

    // Getters
    public Map<Integer, CompressedRule> getRules() { return new HashMap<>(rules); }
    public List<Integer> getSequence() { return new ArrayList<>(sequence); }
    public Map<Integer, RuleMetadata> getMetadata() { return new HashMap<>(metadata); }
    public CompressedRule getRule(int ruleId) { return rules.get(ruleId); }

    /**
     * Compute metadata for compressed grammar
     */
    private static Map<Integer, RuleMetadata> computeMetadataForCompressedGrammar(
            Map<Integer, CompressedRule> rules,
            List<Integer> sequence,
            Set<Integer> artificialTerminals
    ) {
        // Convert to standard format temporarily for metadata computation
        Map<Integer, List<Integer>> standardRules = new HashMap<>();
        for (Map.Entry<Integer, CompressedRule> entry : rules.entrySet()) {
            standardRules.put(entry.getKey(), entry.getValue().toList());
        }

        Parser.ParsedGrammar temp = new Parser.ParsedGrammar(standardRules, sequence, Collections.emptyMap());
        return RuleMetadata.computeAll(temp, artificialTerminals);
    }

    /**
     * Print grammar in readable format
     */
    public void print() {
        System.out.println("=== Compressed Grammar ===");
        for (Map.Entry<Integer, CompressedRule> entry : rules.entrySet()) {
            System.out.printf("R%d: %s\n", entry.getKey(), entry.getValue());
        }
        System.out.println("SEQ: " + sequence);
        System.out.println("Total size |S|: " + getTotalSize());
    }
}