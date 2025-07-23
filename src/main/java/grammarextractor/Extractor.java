package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Extractor {

    public static Parser.ParsedGrammar extractExcerpt(Parser.ParsedGrammar parsedInput, int start, int end) {
        if (start < 0 || start > end || end > getUncompressedSize(parsedInput)) {
            throw new IllegalArgumentException("Invalid excerpt range.");
        }

        List<Integer> excerptSequence = new ArrayList<>();
        Map<Integer, List<Integer>> excerptRules = new HashMap<>();
        Map<Integer, List<Integer>> allRules = parsedInput.grammarRules();
        Map<Integer, RuleMetadata> metadata = parsedInput.metadata();

        int totalTraversed = 0;
        int startIndex = 0;
        int endIndex = 0;

        // Find start boundary symbol
        while (startIndex < parsedInput.sequence().size() &&
                totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex)) <= start) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex));
            startIndex++;
        }
        int totalTraversedBeforeStart = totalTraversed;

        // Find end boundary symbol
        totalTraversed = 0;
        while (endIndex < parsedInput.sequence().size() &&
                totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex)) < end) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex));
            endIndex++;
        }
        int totalTraversedBeforeEnd = totalTraversed;

        // Case: start and end within same symbol
        if (startIndex == endIndex) {
            int symbol = parsedInput.sequence().get(startIndex);
            if (totalTraversedBeforeStart == start && totalTraversedBeforeEnd + getSymbolLength(parsedInput, symbol) == end) {
                excerptSequence.add(symbol);
            } else {
                processExcerptSymbol(symbol, parsedInput, excerptRules, excerptSequence,
                        start - totalTraversedBeforeStart, end - totalTraversedBeforeStart);
            }
        } else {
            // Process start
            int startSymbol = parsedInput.sequence().get(startIndex);
            if (totalTraversedBeforeStart == start) {
                excerptSequence.add(startSymbol);
            } else {
                processStart(startSymbol, parsedInput, excerptRules, excerptSequence,
                        start - totalTraversedBeforeStart,
                        getSymbolLength(parsedInput, startSymbol));
            }

            // Fully included middle symbols
            for (int i = startIndex + 1; i < endIndex; i++) {
                excerptSequence.add(parsedInput.sequence().get(i));
            }

            // Process end
            int endSymbol = parsedInput.sequence().get(endIndex);
            if (totalTraversedBeforeEnd + getSymbolLength(parsedInput, endSymbol) == end) {
                excerptSequence.add(endSymbol);
            } else {
                processEnd(endSymbol, parsedInput, excerptRules, excerptSequence,
                        end - totalTraversedBeforeEnd);
            }
        }

        // Build usage graph + copy reachable rules
        Map<Integer, Set<Integer>> usageGraph = buildUsageGraph(allRules);
        copyReachableRules(excerptSequence, allRules, excerptRules, usageGraph);

        Parser.ParsedGrammar incomplete = new Parser.ParsedGrammar(excerptRules, excerptSequence, Collections.emptyMap());
        Map<Integer, RuleMetadata> computedMeta = RuleMetadata.computeAll(incomplete);
        return new Parser.ParsedGrammar(excerptRules, excerptSequence, computedMeta);
    }

    private static void processStart(int symbol, Parser.ParsedGrammar input, Map<Integer, List<Integer>> excerptRules,
                                     List<Integer> excerptSeq, int from, int to) {
        if (symbol < 256) {
            excerptSeq.add(symbol);
            return;
        }

        List<Integer> rhs = input.grammarRules().get(symbol);
        int left = rhs.get(0);
        int leftLen = getSymbolLength(input, left);

        if (from < leftLen) {
            processStart(left, input, excerptRules, excerptSeq, from, Math.min(to, leftLen));
            if (to > leftLen) {
                excerptSeq.add(rhs.get(1));
            }
        } else {
            processStart(rhs.get(1), input, excerptRules, excerptSeq, from - leftLen, to - leftLen);
        }
    }

    private static void processEnd(int symbol, Parser.ParsedGrammar input, Map<Integer, List<Integer>> excerptRules,
                                   List<Integer> excerptSeq, int to) {
        if (symbol < 256) {
            excerptSeq.add(symbol);
            return;
        }

        List<Integer> rhs = input.grammarRules().get(symbol);
        int left = rhs.get(0);
        int leftLen = getSymbolLength(input, left);

        if (to < leftLen) {
            processEnd(left, input, excerptRules, excerptSeq, to);
        } else if (to == leftLen) {
            excerptSeq.add(left);
        } else {
            excerptSeq.add(left);
            processEnd(rhs.get(1), input, excerptRules, excerptSeq, to - leftLen);
        }
    }

    private static void processExcerptSymbol(int symbol, Parser.ParsedGrammar input, Map<Integer, List<Integer>> excerptRules,
                                             List<Integer> excerptSeq, int from, int to) {
        if (symbol < 256) {
            excerptSeq.add(symbol);
            return;
        }

        List<Integer> rhs = input.grammarRules().get(symbol);
        int left = rhs.get(0);
        int leftLen = getSymbolLength(input, left);

        if (from < leftLen) {
            processExcerptSymbol(left, input, excerptRules, excerptSeq, from, Math.min(to, leftLen));
        }
        if (to > leftLen) {
            processExcerptSymbol(rhs.get(1), input, excerptRules, excerptSeq, from - leftLen, to - leftLen);
        }
    }

    public static Map<Integer, Set<Integer>> buildUsageGraph(Map<Integer, List<Integer>> rules) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int from = entry.getKey();
            Set<Integer> deps = new HashSet<>();
            for (int symbol : entry.getValue()) {
                if (symbol >= 256) deps.add(symbol);
            }
            graph.put(from, deps);
        }
        return graph;
    }


    private static void copyReachableRules(List<Integer> sequence,
                                           Map<Integer, List<Integer>> allRules,
                                           Map<Integer, List<Integer>> excerptRules,
                                           Map<Integer, Set<Integer>> usageGraph) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>(sequence);

        while (!stack.isEmpty()) {
            int current = stack.pop();
            if (current < 256 || visited.contains(current)) continue;

            List<Integer> rule = allRules.get(current);
            if (rule != null) {
                excerptRules.put(current, rule);
                visited.add(current);
                for (int dep : usageGraph.getOrDefault(current, Set.of())) {
                    if (!visited.contains(dep)) {
                        stack.push(dep);
                    }
                }
            }
        }
    }

    public static int getUncompressedSize(Parser.ParsedGrammar parsedInput) {
        int total = 0;
        for (int symbol : parsedInput.sequence()) {
            total += getSymbolLength(parsedInput, symbol);
        }
        return total;
    }

    private static int getSymbolLength(Parser.ParsedGrammar parsedInput, int symbol) {
        if (symbol < 256) return 1;
        RuleMetadata meta = parsedInput.metadata().get(symbol);
        return meta != null ? meta.getLength() : 1;
    }

    public static void writeGrammarToFile(Parser.ParsedGrammar grammarData, String outputFile) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (Map.Entry<Integer, List<Integer>> ruleEntry : grammarData.grammarRules().entrySet()) {
                int ruleId = ruleEntry.getKey();
                List<Integer> rhs = ruleEntry.getValue();
                writer.write("R" + ruleId + ":" +
                        rhs.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""));
                writer.newLine();
            }

            writer.write("SEQ:");
            for (int i = 0; i < grammarData.sequence().size(); i++) {
                writer.write(grammarData.sequence().get(i).toString());
                if (i != grammarData.sequence().size() - 1) writer.write(",");
            }
            writer.newLine();
        }
    }
}
