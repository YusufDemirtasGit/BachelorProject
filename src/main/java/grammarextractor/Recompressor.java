//package grammarextractor;
//
//import java.util.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Recompressor template: sentinel insertion and first-pass bigram frequency counting.
// */
//public class Recompressor {
//    private Parser.ParsedGrammar grammar;
//    private Map<Integer, RuleMetadata> metadata;
//    private Map<Integer, Set<Integer>> reverseUsageMap;
//    private final boolean verbose;
//    private final AtomicInteger ruleCounter;
//
//    public Recompressor(Parser.ParsedGrammar inputGrammar, boolean verbose) {
//        this.grammar = inputGrammar;
//        this.verbose = verbose;
//        this.reverseUsageMap = buildReverseUsageMap(grammar.grammarRules());
//        this.metadata = RuleMetadata.computeAll(grammar.grammarRules(), reverseUsageMap);
//        this.ruleCounter = new AtomicInteger(getMaxRuleId(grammar.grammarRules()) + 1);
//    }
//
//    /**
//     * Top-level recompression driver (first-pass).
//     */
//    public void runRecompression() {
//        addSentinels();
//        Map<Pair<Integer, Integer>, Integer> freqs =
//                computeBigramFrequenciesCompressed(grammar.grammarRules(), metadata);
//
//        if (verbose) {
//            System.out.println("--- Bigram Frequencies First Pass ---");
//            for (Map.Entry<Pair<Integer, Integer>, Integer> e : freqs.entrySet()) {
//                System.out.println(
//                        "Bigram " + formatBigram(e.getKey()) + ": " + e.getValue()
//                );
//            }
//        }
//    }
//
//    /**
//     * Step 0: Add sentinel symbols '#' and '$' around the main sequence.
//     */
//    private void addSentinels() {
//        List<Integer> sequence = grammar.sequence();
//        if (sequence.isEmpty()) {
//            throw new IllegalStateException("Cannot add sentinels to empty sequence");
//        }
//
//        int startVar;
//        Map<Integer, List<Integer>> rules = new HashMap<>(grammar.grammarRules());
//
//        if (sequence.size() == 1) {
//            startVar = sequence.get(0);
//        } else {
//            startVar = ruleCounter.getAndIncrement();
//            rules.put(startVar, new ArrayList<>(sequence));
//        }
//
//        int idXSharp = ruleCounter.getAndIncrement();
//        int idXDollar = ruleCounter.getAndIncrement();
//
//        rules.put(idXSharp, Arrays.asList((int) '#', startVar));
//        rules.put(idXDollar, Arrays.asList(idXSharp, (int) '$'));
//
//        grammar = new Parser.ParsedGrammar(rules,
//                Collections.singletonList(idXDollar), grammar.metadata());
//
//        if (verbose) {
//            System.out.println("Added sentinel rule: X" + idXSharp + " -> '#' R" + startVar);
//            System.out.println("Added sentinel rule: X" + idXDollar + " -> R" + idXSharp + " '$'");
//        }
//    }
//
//    /**
//     * First-pass bigram frequency counting (naïve scan of RHS for testing).
//     */
//    private Map<Pair<Integer, Integer>, Integer> computeBigramFrequenciesCompressed(
//            Map<Integer, List<Integer>> rules,
//            Map<Integer, RuleMetadata> metadata
//    ) {
//        Map<Pair<Integer, Integer>, Integer> freqMap = new HashMap<>();
//        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
//            List<Integer> rhs = entry.getValue();
//            for (int i = 0; i < rhs.size() - 1; i++) {
//                Pair<Integer, Integer> bigram = new Pair<>(rhs.get(i), rhs.get(i + 1));
//                freqMap.merge(bigram, 1, Integer::sum);
//            }
//        }
//        return freqMap;
//    }
//
//    /**
//     * Build reverse usage map: which rules refer to each variable.
//     */
//    private Map<Integer, Set<Integer>> buildReverseUsageMap(Map<Integer, List<Integer>> rules) {
//        Map<Integer, Set<Integer>> reverse = new HashMap<>();
//        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
//            int user = entry.getKey();
//            for (int sym : entry.getValue()) {
//                if (sym >= 256) {
//                    reverse.computeIfAbsent(sym, k -> new HashSet<>()).add(user);
//                }
//            }
//        }
//        for (int sym : grammar.sequence()) {
//            if (sym >= 256) {
//                reverse.computeIfAbsent(sym, k -> new HashSet<>()).add(-1);
//            }
//        }
//        return reverse;
//    }
//
//    private int getMaxRuleId(Map<Integer, List<Integer>> rules) {
//        return rules.keySet().stream().mapToInt(i -> i).max().orElse(255);
//    }
//
//    private String formatBigram(Pair<Integer, Integer> bigram) {
//        return "(" + formatSymbol(bigram.first) + "," + formatSymbol(bigram.second) + ")";
//    }
//
//    private String formatSymbol(int sym) {
//        if (sym < 256) {
//            return "'" + (char) sym + "'";
//        } else {
//            return "R" + sym;
//        }
//    }
//}
