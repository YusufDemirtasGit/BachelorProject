package grammarextractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class Parser {

    public record ParsedGrammar(Map<Integer, List<Integer>> grammarRules,
                                List<Integer> sequence,
                                Map<Integer, RuleMetadata> metadata) {}

    public static ParsedGrammar parseFile(Path inputFile) throws IOException {
        long startTime = System.nanoTime();
        Map<Integer, List<Integer>> grammarRules = new HashMap<>();
        List<Integer> sequence = new ArrayList<>();
        List<String> ruleLines = new ArrayList<>();

        try (Scanner scanner = new Scanner(inputFile.toFile())) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.startsWith("R")) {
                    ruleLines.add(line);
                } else if (line.startsWith("SEQ:")) {
                    for (String token : line.substring(4).split(",")) {
                        sequence.add(Integer.parseInt(token.trim()));
                    }
                }
            }
        }

        for (String ruleLine : ruleLines) {
            String[] split = ruleLine.substring(1).split(":");
            int ruleId = Integer.parseInt(split[0].trim());
            String[] rhsTokens = split[1].split(",");
            List<Integer> rhs = new ArrayList<>();
            for (String token : rhsTokens) {
                rhs.add(Integer.parseInt(token.trim()));
            }
            grammarRules.put(ruleId, rhs);
        }

        // 1. Build a grammar with empty metadata
        ParsedGrammar partialGrammar = new ParsedGrammar(grammarRules, sequence, Collections.emptyMap());

        // 2. Compute metadata (empty set for artificial terminals)
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(partialGrammar, Collections.emptySet());

        long endTime   = System.nanoTime();
        long totalTime = endTime - startTime;
        System.out.println("Time required for parsing in total" + ":" +totalTime/1000000 + "ms");


        // 3. Return full grammar
        return new ParsedGrammar(grammarRules, sequence, metadata);
    }

    /**
     * Compute the size of a grammar.
     * Size = total number of symbols in all rule RHSs + total number of symbols in the sequence.
     */
    public static int sizeOfGrammar(ParsedGrammar grammar) {
        int rhsCount = grammar.grammarRules().values().stream()
                .mapToInt(List::size)
                .sum();
        int seqCount = grammar.sequence().size();
        return rhsCount + seqCount;
    }


    /** New: return a printable string for a grammar (mirrors printGrammar output). */
    public static String grammarToString(ParsedGrammar grammar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<Integer>> entry : grammar.grammarRules().entrySet()) {
            int ruleId = entry.getKey();
            String rhs = entry.getValue().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            sb.append("R").append(ruleId).append(": ").append(rhs).append('\n');
        }

        sb.append("SEQ:");
        for (int i = 0; i < grammar.sequence().size(); i++) {
            sb.append(grammar.sequence().get(i));
            if (i != grammar.sequence().size() - 1) sb.append(",");
        }
        sb.append('\n');
        return sb.toString();
    }


    public static void printGrammar(ParsedGrammar grammar) {
        System.out.print(grammarToString(grammar));
    }
    public static final class RuleEditor {

        private RuleEditor() {}

        /** Insert a single symbol at the given index. */
        public static void insert(Map<Integer, List<Integer>> rules, int ruleId, int index, int symbol) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            if (index < 0 || index > rhs.size()) {
                throw new IndexOutOfBoundsException("index=" + index + " size=" + rhs.size());
            }
            rhs.add(index, symbol);
        }

        /** Insert many symbols starting at index. */
        public static void insertAll(Map<Integer, List<Integer>> rules, int ruleId, int index, Collection<Integer> symbols) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            if (index < 0 || index > rhs.size()) {
                throw new IndexOutOfBoundsException("index=" + index + " size=" + rhs.size());
            }
            rhs.addAll(index, symbols);
        }

        /** Append at the end. */
        public static void append(Map<Integer, List<Integer>> rules, int ruleId, int symbol) {
            getRhsOrThrow(rules, ruleId).add(symbol);
        }

        /** Prepend at the beginning. */
        public static void prepend(Map<Integer, List<Integer>> rules, int ruleId, int symbol) {
            getRhsOrThrow(rules, ruleId).add(0, symbol);
        }

        /** Replace the symbol at a specific position. */
        public static void set(Map<Integer, List<Integer>> rules, int ruleId, int index, int newSymbol) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            rhs.set(checkIndex(index, rhs.size()), newSymbol);
        }

        /** Delete the element at index. */
        public static int deleteAt(Map<Integer, List<Integer>> rules, int ruleId, int index) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            return rhs.remove(checkIndex(index, rhs.size()));
        }

        /** Delete a half-open range [fromInclusive, toExclusive). */
        public static void deleteRange(Map<Integer, List<Integer>> rules, int ruleId, int fromInclusive, int toExclusive) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            if (fromInclusive < 0 || toExclusive > rhs.size() || fromInclusive > toExclusive) {
                throw new IndexOutOfBoundsException(
                        "range=[" + fromInclusive + "," + toExclusive + ") size=" + rhs.size());
            }
            rhs.subList(fromInclusive, toExclusive).clear();
        }

        /** Remove the first occurrence of symbol; returns true if something was removed. */
        public static boolean removeFirst(Map<Integer, List<Integer>> rules, int ruleId, int symbol) {
            return getRhsOrThrow(rules, ruleId).remove((Integer) symbol);
        }

        /** Remove all occurrences of symbol; returns how many were removed. */
        public static int removeAll(Map<Integer, List<Integer>> rules, int ruleId, int symbol) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            int before = rhs.size();
            rhs.removeIf(s -> s == symbol);
            return before - rhs.size();
        }

        /** Swap two positions. */
        public static void swap(Map<Integer, List<Integer>> rules, int ruleId, int i, int j) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            int size = rhs.size();
            i = checkIndex(i, size);
            j = checkIndex(j, size);
            Collections.swap(rhs, i, j);
        }

        /** Move an element from 'from' to 'to' (indices after removal/insertion semantics). */
        public static void move(Map<Integer, List<Integer>> rules, int ruleId, int from, int to) {
            List<Integer> rhs = getRhsOrThrow(rules, ruleId);
            int size = rhs.size();
            from = checkIndex(from, size);
            if (to < 0 || to >= size) {
                throw new IndexOutOfBoundsException("to=" + to + " size=" + size);
            }
            int val = rhs.remove(from);
            rhs.add(to, val);
        }

        /** Read-only view of an RHS (avoid external mutation). */
        public static List<Integer> view(Map<Integer, List<Integer>> rules, int ruleId) {
            return Collections.unmodifiableList(getRhsOrThrow(rules, ruleId));
        }

        private static List<Integer> getRhsOrThrow(Map<Integer, List<Integer>> rules, int ruleId) {
            List<Integer> rhs = rules.get(ruleId);
            if (rhs == null) {
                throw new IllegalArgumentException("Unknown rule id: " + ruleId);
            }
            return rhs;
        }

        private static int checkIndex(int index, int size) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
            }
            return index;
        }
    }

}
