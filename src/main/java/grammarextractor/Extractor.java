package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Extractor {

    public static Parser.ParsedGrammar extractExcerpt(Parser.ParsedGrammar parsedInput, int start, int end,boolean normalize) {
        if (start < 0 || start > end || end > getUncompressedSize(parsedInput)) {
            throw new IllegalArgumentException("Invalid excerpt range.");
        }
        long startTime = System.nanoTime();
        List<Integer> excerptSequence = new ArrayList<>();
        Map<Integer, List<Integer>> excerptRules = new HashMap<>();
        Map<Integer, List<Integer>> allRules = parsedInput.grammarRules();

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
            if (totalTraversedBeforeStart == start &&
                    totalTraversedBeforeEnd + getSymbolLength(parsedInput, symbol) == end) {
                excerptSequence.add(symbol);
            } else {
                // Slice inside a single symbol
                processSymbol(symbol, parsedInput, excerptRules, excerptSequence,
                        start - totalTraversedBeforeStart, end - totalTraversedBeforeStart);
            }
        } else {
            // Process start boundary
            int startSymbol = parsedInput.sequence().get(startIndex);
            if (totalTraversedBeforeStart == start) {
                excerptSequence.add(startSymbol);
            } else {
                processSymbol(startSymbol, parsedInput, excerptRules, excerptSequence,
                        start - totalTraversedBeforeStart,
                        getSymbolLength(parsedInput, startSymbol)); // suffix of start symbol
            }

            // Fully included middle symbols
            for (int i = startIndex + 1; i < endIndex; i++) {
                excerptSequence.add(parsedInput.sequence().get(i));
            }

            // Process end boundary
            int endSymbol = parsedInput.sequence().get(endIndex);
            if (totalTraversedBeforeEnd + getSymbolLength(parsedInput, endSymbol) == end) {
                excerptSequence.add(endSymbol);
            } else {
                processSymbol(endSymbol, parsedInput, excerptRules, excerptSequence,
                        0,
                        end - totalTraversedBeforeEnd); // prefix of end symbol
            }
        }

        // Build usage graph + copy reachable rules
        Parser.ParsedGrammar incomplete =
                new Parser.ParsedGrammar(excerptRules, excerptSequence, Collections.emptyMap());
        Map<Integer, Set<Integer>> usage = buildUsageGraph(allRules);
        copyReachableRules(excerptSequence, allRules, excerptRules, usage);

        // Compute metadata on the excerpt
        Map<Integer, RuleMetadata> computedMeta =
                RuleMetadata.computeAll(incomplete, Collections.emptySet());

        Parser.ParsedGrammar unnormalized =
                new Parser.ParsedGrammar(excerptRules, excerptSequence, computedMeta);

        long endTime = System.nanoTime();
        System.out.println("Time required in total: " + (endTime - startTime) / 1_000_000 + "ms");
        // Normalize ids and recompute metadata on the normalized grammar
        if(normalize) return normalizeRuleIds(unnormalized);
        else return unnormalized;
    }


    private static void processSymbol(
            int symbol,
            Parser.ParsedGrammar input,
            Map<Integer, List<Integer>> excerptRules,
            List<Integer> out,
            int from,
            int to
    ) {
        if (from >= to) return;

        int symLen = getSymbolLength(input, symbol);

        // If the requested slice exactly covers this symbol, emit it as-is.
        if (from == 0 && to == symLen) {
            out.add(symbol);
            return;
        }

        // Terminal (length = 1): include iff slice overlaps [0,1)
        if (symbol < 256) {
            if (to > 0 && from < 1) out.add(symbol);
            return;
        }

        // Nonterminal: recursively slice left/right children but keep whole children if fully covered.
        List<Integer> rhs = input.grammarRules().get(symbol);
        int left = rhs.get(0);
        int right = rhs.get(1);
        int leftLen = getSymbolLength(input, left);
        int rightLen = getSymbolLength(input, right); // used for exact-coverage checks

        // Entire slice inside left child
        if (to <= leftLen) {
            if (from == 0 && to == leftLen) {
                out.add(left);
            } else {
                processSymbol(left, input, excerptRules, out, from, to);
            }
            return;
        }

        // Entire slice inside right child
        if (from >= leftLen) {
            int rFrom = from - leftLen;
            int rTo = to - leftLen;
            if (rFrom == 0 && rTo == rightLen) {
                out.add(right);
            } else {
                processSymbol(right, input, excerptRules, out, rFrom, rTo);
            }
            return;
        }

        // Slice spans both children
        if (from == 0) {
            out.add(left); // left fully covered
        } else {
            processSymbol(left, input, excerptRules, out, from, leftLen);
        }

        if (to == leftLen + rightLen) {
            out.add(right); // right fully covered
        } else {
            processSymbol(right, input, excerptRules, out, 0, to - leftLen);
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

    public static Parser.ParsedGrammar normalizeRuleIds(Parser.ParsedGrammar g) {
        Map<Integer, List<Integer>> rules = g.grammarRules();
        List<Integer> seq = g.sequence();

        // Build usage graph of the *current* (excerpt) rules
        Map<Integer, Set<Integer>> usage = buildUsageGraph(rules);

        // Assign new ids in the order rules are *reached* from the sequence
        Map<Integer, Integer> idMap = new HashMap<>();
        int nextId = 256;

        Deque<Integer> stack = new ArrayDeque<>();
        for (int s : seq) if (s >= 256 && !idMap.containsKey(s)) stack.push(s);

        while (!stack.isEmpty()) {
            int cur = stack.pop();
            if (idMap.containsKey(cur)) continue;
            idMap.put(cur, nextId++);

            for (int dep : usage.getOrDefault(cur, Collections.emptySet())) {
                if (dep >= 256 && !idMap.containsKey(dep)) stack.push(dep);
            }
        }

        // Ensure any isolated rules (not reached from seq) are also mapped deterministically
        List<Integer> leftover = new ArrayList<>(rules.keySet());
        Collections.sort(leftover);
        for (int rid : leftover) {
            if (rid >= 256 && !idMap.containsKey(rid)) {
                idMap.put(rid, nextId++);
            }
        }

        // Remap RHS and sequence
        Map<Integer, List<Integer>> newRules = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : rules.entrySet()) {
            int oldId = e.getKey();
            int newId = oldId < 256 ? oldId : idMap.get(oldId);
            List<Integer> remappedRhs = new ArrayList<>(e.getValue().size());
            for (int sym : e.getValue()) {
                remappedRhs.add(sym < 256 ? sym : idMap.get(sym));
            }
            newRules.put(newId, remappedRhs);
        }

        List<Integer> newSeq = new ArrayList<>(seq.size());
        for (int sym : seq) newSeq.add(sym < 256 ? sym : idMap.get(sym));

        // Recompute metadata on the normalized grammar
        Parser.ParsedGrammar normalizedNoMeta =
                new Parser.ParsedGrammar(newRules, newSeq, Collections.emptyMap());
        Map<Integer, RuleMetadata> newMeta =
                RuleMetadata.computeAll(normalizedNoMeta, Collections.emptySet());

        return new Parser.ParsedGrammar(newRules, newSeq, newMeta);
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
