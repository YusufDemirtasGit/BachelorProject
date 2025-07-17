package grammarextractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Parser {
    public static class GrammarRule<A, B> {
        public A lhs;
        public List<B> rhs;
        public int length;

        public GrammarRule(A lhs, List<B> rhs, int length) {
            this.lhs = lhs;
            this.rhs = rhs;
            this.length = length;
        }

        @Override
        public String toString() {
            return "R" + lhs + ": " + rhs.stream().map(Object::toString).collect(Collectors.joining(","));
        }
    }

    public record ParsedGrammar(Map<Integer, GrammarRule<Integer, Integer>> grammarRules, List<Integer> sequence) {
        public static int getLength(Map<Integer, Parser.GrammarRule<Integer, Integer>> rules, int symbol) {
            if (symbol < 256) return 1; // Terminal symbols are of length 1
            Parser.GrammarRule<Integer, Integer> rule = rules.get(symbol);
            if (rule == null) {
                System.err.println("Tried to get length of undefined rule R" + symbol);
                return 1;
            }
            return rule.length;
        }
    }

    public static ParsedGrammar parseFile(Path inputFile) throws IOException {
        Map<Integer, GrammarRule<Integer, Integer>> GrammarRules = new HashMap<>();
        List<Integer> ParsedSequence = new ArrayList<>();
        List<String> ruleLines = new ArrayList<>();

        try (Scanner scanner = new Scanner(inputFile)) {
            while (scanner.hasNextLine()) {
                String inputStream = scanner.nextLine().trim();

                if (inputStream.startsWith("R")) {
                    ruleLines.add(inputStream); // Store rule lines for delayed processing
                } else if (inputStream.startsWith("SEQ:")) {
                    String[] sequence = inputStream.substring(4).split(",");
                    for (String sequenceItem : sequence) {
                        ParsedSequence.add(Integer.parseInt(sequenceItem.trim()));
                    }
                }
            }
        }

        // First pass: store rules with placeholder lengths
        for (String ruleLine : ruleLines) {
            String[] split = ruleLine.substring(1).split(":");
            int ruleName = Integer.parseInt(split[0]);
            String[] rhsParts = split[1].split(",");
            List<Integer> rhs = new ArrayList<>();

            for (String part : rhsParts) {
                rhs.add(Integer.parseInt(part.trim()));
            }

            // Temporarily set length to -1; it will be calculated in second pass
            GrammarRules.put(ruleName, new GrammarRule<>(-1, rhs, -1));
        }

        // Second pass: calculate length recursively
        for (Map.Entry<Integer, GrammarRule<Integer, Integer>> entry : GrammarRules.entrySet()) {
            GrammarRule<Integer, Integer> rule = entry.getValue();
            int totalLength = 0;

            for (int symbol : rule.rhs) {
                if (symbol < 256) {
                    totalLength += 1;
                } else {
                    GrammarRule<Integer, Integer> subRule = GrammarRules.get(symbol);
                    if (subRule != null) {
                        totalLength += subRule.length;
                    } else {
                        System.err.println("Undefined rule referenced: R" + symbol);
                        totalLength += 1; // fallback
                    }
                }
            }

            rule.length = totalLength;
            rule.lhs = entry.getKey(); // ensure lhs matches rule ID
        }

        return new ParsedGrammar(GrammarRules, ParsedSequence);
    }


    public static void printGrammar(Parser.ParsedGrammar grammar) {
        for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> entry : grammar.grammarRules().entrySet()) {
            int ruleId = entry.getKey();
            Parser.GrammarRule<Integer, Integer> rule = entry.getValue();

            String rhsString = rule.rhs.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            System.out.println("R" + ruleId + ": " + rhsString);
        }
    }

}
