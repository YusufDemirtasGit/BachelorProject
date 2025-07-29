package grammarextractor;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int symbol : parsedGrammar.sequence()) {
            expandIterative(symbol, parsedGrammar.grammarRules(), output);
        }

        return output.toString();
    }

    private static void expandIterative(int startSymbol,
                                        Map<Integer, List<Integer>> grammar,
                                        StringBuilder output) {

        // Use a stack to simulate recursion
        Deque<Integer> symbolStack = new ArrayDeque<>();
        symbolStack.push(startSymbol);

        while (!symbolStack.isEmpty()) {
            int symbol = symbolStack.pop();

            if (symbol < 256) {
                output.append((char) symbol);
                continue;
            }

            List<Integer> rhs = grammar.get(symbol);
            if (rhs == null) {
                throw new IllegalArgumentException("Missing rule for non-terminal: R" + symbol);
            }

            // Push symbols in reverse order so they're processed left-to-right
            for (int i = rhs.size() - 1; i >= 0; i--) {
                symbolStack.push(rhs.get(i));
            }
        }
    }

    // Iterative version for Writer
    private static void expandToWriterIterative(int startSymbol,
                                                Map<Integer, List<Integer>> grammar,
                                                Writer writer) throws IOException {

        Deque<Integer> symbolStack = new ArrayDeque<>();
        symbolStack.push(startSymbol);

        while (!symbolStack.isEmpty()) {
            int symbol = symbolStack.pop();

            if (symbol < 256) {
                writer.write((char) symbol);
                continue;
            }

            List<Integer> rhs = grammar.get(symbol);
            if (rhs == null) {
                throw new IllegalArgumentException("Missing rule for non-terminal: R" + symbol);
            }

            // Push symbols in reverse order for left-to-right processing
            for (int i = rhs.size() - 1; i >= 0; i--) {
                symbolStack.push(rhs.get(i));
            }
        }
    }

    // Recursive methods (simplified without cycle detection)
    private static void expand(int symbol,
                               Map<Integer, List<Integer>> grammar,
                               Map<Integer, RuleMetadata> metadata,
                               StringBuilder output) {

        if (symbol < 256) {
            output.append((char) symbol);
            return;
        }

        List<Integer> rhs = grammar.get(symbol);
        if (rhs == null) {
            throw new IllegalArgumentException("Missing rule for non-terminal: R" + symbol);
        }

        for (int sub : rhs) {
            expand(sub, grammar, metadata, output);
        }
    }

}