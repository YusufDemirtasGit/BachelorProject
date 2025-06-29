package grammarextractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;

public class Parser {
    static class GrammarRule<A, B> {
        public A lhs;
        public B rhs;
        public int length;

        public GrammarRule(A lhs, B rhs, int length) {
            this.lhs = lhs;
            this.rhs = rhs;
            this.length = length;
        }
    }

    public record ParsedGrammar(Map<Integer, GrammarRule<Integer, Integer>> grammarRules, List<Integer> sequence) {
    }

    public static ParsedGrammar parseFile(Path inputFile) throws IOException {
        Map<Integer, GrammarRule<Integer, Integer>> GrammarRules = new HashMap<>();
        List<Integer> ParsedSequence = new ArrayList<>();

        try (Scanner scanner = new Scanner(inputFile)) {
            while (scanner.hasNextLine()) {
                String inputStream = scanner.nextLine().trim();
                // For instance, it should look like: R259:97,258
                if (inputStream.startsWith("R")) {
                    // Get the name of the rule
                    String[] slicedInputStream = inputStream.substring(1).split(":");
                    // Set it as the Rule Name
                    int ruleName = Integer.parseInt(slicedInputStream[0]);
                    // Get the lhs and rhs of the Rule
                    String[] ruleParts = slicedInputStream[1].split(",");
                    // Set the lhs
                    int lhs = Integer.parseInt(ruleParts[0]);
                    // Set the rhs
                    int rhs = Integer.parseInt(ruleParts[1]);

                    // Calculate the length of the rule
                    // Length is 1 if terminal, otherwise check the length of rhs of lhs respectively
                    int lhsLength = (lhs < 256) ? 1 : GrammarRules.get(lhs).length;
                    int rhsLength = (rhs < 256) ? 1 : GrammarRules.get(rhs).length;
                    int totalLength = lhsLength + rhsLength;

                    GrammarRules.put(ruleName, new GrammarRule<>(lhs, rhs, totalLength));
                } else if (inputStream.startsWith("SEQ:")) {
                    // For instance, it should look like: SEQ:259,99,97,100,259
                    String[] sequence = inputStream.substring(4).split(",");
                    for (String sequenceItem : sequence) {
                        ParsedSequence.add(Integer.parseInt(sequenceItem));
                    }
                }
            }
        }
        return new ParsedGrammar(GrammarRules, ParsedSequence);
    }
//These are for the Recompression. Probably will be useful later on
    public static int getFirst(int symbol, ParsedGrammar parsedGrammar) {
        if (symbol < 256) {
            return symbol;
        }
        GrammarRule<Integer, Integer> rule = parsedGrammar.grammarRules().get(symbol);
        if (rule == null) {
            throw new IllegalArgumentException("Rule for symbol " + symbol + " not found.");
        }
        return getFirst(rule.lhs, parsedGrammar);
    }

    public static int getLast(int symbol, ParsedGrammar parsedGrammar) {
        if (symbol < 256) {
            return symbol;
        }
        GrammarRule<Integer, Integer> rule = parsedGrammar.grammarRules().get(symbol);
        if (rule == null) {
            throw new IllegalArgumentException("Rule for symbol " + symbol + " not found.");
        }
        return getLast(rule.rhs, parsedGrammar);
    }
}
