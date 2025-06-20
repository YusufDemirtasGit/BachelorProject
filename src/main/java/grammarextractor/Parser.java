package grammarextractor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
public class Parser {
    static class GrammarRule<A,B>{
        public A lhs;
        public B rhs;

        public GrammarRule(A lhs, B rhs){
            this.lhs = lhs;
            this.rhs = rhs;
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
                    String[] parts = inputStream.substring(1).split(":");
                    // Set it as the Rule Name
                    int ruleName = Integer.parseInt(parts[0]);
                    // Get the lhs and rhs of the Rule
                    String[] ruleParts = parts[1].split(",");
                    // Set the lhs
                    int lhs = Integer.parseInt(ruleParts[0]);
                    // Set the rhs
                    int rhs = Integer.parseInt(ruleParts[1]);
                    GrammarRules.put(ruleName, new GrammarRule<>(lhs, rhs));
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
}
