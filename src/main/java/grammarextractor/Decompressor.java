package grammarextractor;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int symbol : parsedGrammar.sequence()) {
            expand(symbol, parsedGrammar.grammarRules(), output);
        }

        return output.toString();
    }

    private static void expand(int startSymbol,
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

}