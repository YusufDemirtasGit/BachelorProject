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
        Map<Integer, List<Integer>> grammarRules = new HashMap<>();
        List<Integer> sequence = new ArrayList<>();
        List<String> ruleLines = new ArrayList<>();

        try (Scanner scanner = new Scanner(inputFile)) {
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

        // 2. Compute metadata
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(partialGrammar);

        // 3. Return full grammar
        return new ParsedGrammar(grammarRules, sequence, metadata);
    }

    private static Map<Integer, Set<Integer>> buildReverseUsageMap(Map<Integer, List<Integer>> rules) {
        Map<Integer, Set<Integer>> reverse = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int user = entry.getKey();
            for (int symbol : entry.getValue()) {
                if (symbol >= 256) {
                    reverse.computeIfAbsent(symbol, k -> new HashSet<>()).add(user);
                }
            }
        }
        return reverse;
    }

    public static void printGrammar(ParsedGrammar grammar) {
        for (Map.Entry<Integer, List<Integer>> entry : grammar.grammarRules().entrySet()) {
            int ruleId = entry.getKey();
            String rhs = entry.getValue().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            System.out.println("R" + ruleId + ": " + rhs);
        }

        System.out.print("SEQ:");
        for (int i = 0; i < grammar.sequence().size(); i++) {
            System.out.print(grammar.sequence().get(i));
            if (i != grammar.sequence().size() - 1) System.out.print(",");
        }
        System.out.println();
    }
}
