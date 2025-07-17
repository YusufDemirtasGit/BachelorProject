package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Extractor {

    public static Parser.ParsedGrammar extractExcerpt(Parser.ParsedGrammar parsedInput, int start, int end) throws IllegalArgumentException {
        int uncompressedSize = getUncompressedSize(parsedInput);
        if (end > uncompressedSize) {
            throw new IllegalArgumentException("Start and end must define a valid range within the uncompressed sequence.");
        } else if (start < 0) {
            throw new IllegalArgumentException("Start must be greater than zero.");
        } else if (start > end) {
            throw new IllegalArgumentException("Start must be less than or equal to end.");
        }

        List<Integer> excerptSequence = new ArrayList<>();
        Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules = new HashMap<>();

        int totalTraversed = 0;
        int startIndex = 0;
        int endIndex = 0;

        // Find which symbol has the start point in it
        while (startIndex < parsedInput.sequence().size() &&
                totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex)) <= start) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex));
            startIndex++;
        }
        int totalTraversedBeforeStart = totalTraversed;

        // Find symbol at end boundary
        totalTraversed = 0;
        while (endIndex < parsedInput.sequence().size() &&
                totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex)) < end) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex));
            endIndex++;
        }

        int totalTraversedBeforeEnd = totalTraversed;
        int endSymbolLength = getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex));

        // Case where start & end are within the same symbol
        if (startIndex == endIndex) {
            int symbol = parsedInput.sequence().get(startIndex);
            if (totalTraversedBeforeStart == start && totalTraversedBeforeEnd + endSymbolLength == end) {
                excerptSequence.add(symbol);
            } else {
                processExcerptSymbol(symbol, parsedInput, excerptRules, excerptSequence, start - totalTraversedBeforeStart, end - totalTraversedBeforeStart);
            }
        } else {
            // Process start symbol
            int startSymbol = parsedInput.sequence().get(startIndex);
            if (totalTraversedBeforeStart == start) {
                excerptSequence.add(startSymbol);
            } else {
                processStart(startSymbol, parsedInput, excerptRules, excerptSequence, start - totalTraversedBeforeStart, getSymbolLength(parsedInput, startSymbol));
            }

            // Add all fully included symbols in between
            for (int i = startIndex + 1; i < endIndex; i++) {
                int symbol = parsedInput.sequence().get(i);
                excerptSequence.add(symbol);
            }

            // Process end symbol
            int endSymbol = parsedInput.sequence().get(endIndex);
            if (totalTraversedBeforeEnd + endSymbolLength == end) {
                excerptSequence.add(endSymbol);
            } else {
                processEnd(endSymbol, parsedInput, excerptRules, excerptSequence, end - totalTraversedBeforeEnd);
            }
        }

        // Compute the usage graph once from the full grammar
        Map<Integer, Set<Integer>> usageGraph = buildUsageGraph(parsedInput.grammarRules());

        // Copy all reachable rules for the sequence
        copyReachableRules(excerptSequence, parsedInput.grammarRules(), excerptRules, usageGraph);

        return new Parser.ParsedGrammar(excerptRules, excerptSequence);
    }

    private static void processStart(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules,
                                     List<Integer> excerptSequence, int from, int to) {
        if (symbol < 256) {
            excerptSequence.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.rhs.get(0));

        if (from < leftSize) {
            processStart(rule.rhs.get(0), parsedInput, excerptRules, excerptSequence, from, Math.min(to, leftSize));
            if (to > leftSize) {
                excerptSequence.add(rule.rhs.get(1));
            }
        } else {
            processStart(rule.rhs.get(1), parsedInput, excerptRules, excerptSequence, from - leftSize, to - leftSize);
        }
    }

    private static void processEnd(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules,
                                   List<Integer> excerptSequence, int to) {
        if (symbol < 256) {
            excerptSequence.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.rhs.get(0));

        if (to < leftSize) {
            processEnd(rule.rhs.get(0), parsedInput, excerptRules, excerptSequence, to);
        } else if (to == leftSize) {
            excerptSequence.add(rule.rhs.get(0));
        } else {
            excerptSequence.add(rule.rhs.get(0));
            processEnd(rule.rhs.get(1), parsedInput, excerptRules, excerptSequence, to - leftSize);
        }
    }

    private static void processExcerptSymbol(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules,
                                             List<Integer> excerptSeq, int from, int to) {
        if (symbol < 256) {
            excerptSeq.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.rhs.get(0));

        if (from < leftSize) {
            processExcerptSymbol(rule.rhs.get(0), parsedInput, excerptRules, excerptSeq, from, Math.min(to, leftSize));
        }
        if (to > leftSize) {
            processExcerptSymbol(rule.rhs.get(1), parsedInput, excerptRules, excerptSeq, from - leftSize, to - leftSize);
        }
    }

    public static Map<Integer, Set<Integer>> buildUsageGraph(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : rules.entrySet()) {
            int from = entry.getKey();
            Set<Integer> deps = new HashSet<>();
            for (int symbol : entry.getValue().rhs) {
                if (symbol >= 256) {
                    deps.add(symbol);
                }
            }
            graph.put(from, deps);
        }
        return graph;
    }

    private static void copyReachableRules(List<Integer> sequence,
                                           Map<Integer, Parser.GrammarRule<Integer, Integer>> allRules,
                                           Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules,
                                           Map<Integer, Set<Integer>> usageGraph) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>(sequence);

        while (!stack.isEmpty()) {
            int current = stack.pop();
            if (current < 256 || visited.contains(current)) continue;

            Parser.GrammarRule<Integer, Integer> rule = allRules.get(current);
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

    private static int getSymbolLength(Parser.ParsedGrammar parsedInput, int symbol) {
        return (symbol < 256) ? 1 : parsedInput.grammarRules().get(symbol).length;
    }

    public static int getUncompressedSize(Parser.ParsedGrammar parsedInput) {
        int total = 0;
        for (int symbol : parsedInput.sequence()) {
            total += getSymbolLength(parsedInput, symbol);
        }
        return total;
    }

    public static void writeGrammarToFile(Parser.ParsedGrammar grammarData, String outputFile) throws IOException {
        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(outputFile))) {
            for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> ruleEntry : grammarData.grammarRules().entrySet()) {
                int ruleNum = ruleEntry.getKey();
                Parser.GrammarRule<Integer, Integer> ruleData = ruleEntry.getValue();
                fileWriter.write("R" + ruleNum + ":" +
                        ruleData.rhs.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""));
                fileWriter.newLine();
            }

            fileWriter.write("SEQ:");
            for (int i = 0; i < grammarData.sequence().size(); i++) {
                fileWriter.write(String.valueOf(grammarData.sequence().get(i)));
                if (i != grammarData.sequence().size() - 1) {
                    fileWriter.write(",");
                }
            }
            fileWriter.newLine();
        }
    }
}
