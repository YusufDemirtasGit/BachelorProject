package grammarextractor;

import java.util.*;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        long startTime = System.nanoTime();
        StringBuilder output = new StringBuilder();

        for (int symbol : parsedGrammar.sequence()) {
            expand(symbol, parsedGrammar.grammarRules(), output);
        }
        long endTime = System.nanoTime();
        System.out.println("Time required for decompression in total: " + (endTime - startTime) / 1_000_000 + "ms");
        return output.toString();
    }

    public static String decompressPrefix(Parser.ParsedGrammar grammar, int maxChars) {
        StringBuilder output = new StringBuilder(Math.min(maxChars, 4096));
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Deque<Integer> stack = new ArrayDeque<>();

        List<Integer> seq = grammar.sequence();
        for (int i = seq.size() - 1; i >= 0; i--) stack.push(seq.get(i));

        while (!stack.isEmpty() && output.length() < maxChars) {
            int sym = stack.pop();
            if (sym < 256) {
                output.append((char) sym);
                continue;
            }
            List<Integer> rhs = rules.get(sym);
            if (rhs == null) continue;
            for (int i = rhs.size() - 1; i >= 0; i--) stack.push(rhs.get(i));
        }
        return output.substring(0, Math.min(output.length(), maxChars));
    }

    public static Map<Character, Integer> charFrequencies(Parser.ParsedGrammar grammar) {
        Map<Character, Integer> freqs = new TreeMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Deque<Integer> stack = new ArrayDeque<>();

        List<Integer> seq = grammar.sequence();
        for (int i = seq.size() - 1; i >= 0; i--) stack.push(seq.get(i));

        while (!stack.isEmpty()) {
            int sym = stack.pop();
            if (sym < 256) {
                freqs.merge((char) sym, 1, Integer::sum);
                continue;
            }
            List<Integer> rhs = rules.get(sym);
            if (rhs == null) continue;
            for (int i = rhs.size() - 1; i >= 0; i--) stack.push(rhs.get(i));
        }
        return freqs;
    }

    private static void expand(int startSymbol,
                                        Map<Integer, List<Integer>> grammar,
                                        StringBuilder output) {

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

            for (int i = rhs.size() - 1; i >= 0; i--) {
                symbolStack.push(rhs.get(i));
            }
        }
    }

}
