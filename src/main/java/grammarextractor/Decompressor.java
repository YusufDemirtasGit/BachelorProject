package grammarextractor;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int grammarRule : parsedGrammar.sequence()) {
            expandLeaf(grammarRule, parsedGrammar.grammarRules(), output);
        }

        return output.toString();
    }

    // I actually wanted to call it just expand, but since this is a direct translation of the original code by Re_Serp by Shirou Maruyama, I leave the name as is
    private static void expandLeaf(int symbol, Map<Integer, Parser.GrammarRule<Integer, Integer>> grammar, StringBuilder output) {
        if (symbol < 256) {
            // Current Symbol is a Terminal: convert to readable character
            output.append((char) symbol);
        } else {
            // Current Symbol is a Non-terminal: expand using GrammarRules
            Parser.GrammarRule<Integer, Integer> rule = grammar.get(symbol);
            if (rule == null) {
                throw new IllegalArgumentException("Missing rule for symbol: " + symbol);
            }

            // Idea is to expand all RHS symbols recursively until we hit a terminal
            for (int subSymbol : rule.rhs) {
                expandLeaf(subSymbol, grammar, output);
            }
        }
    }

    // Streaming decompression variant — decompresses the grammar directly into a Writer without building a full in-memory String
    public static void decompressToWriter(Parser.ParsedGrammar parsedGrammar, Writer writer) throws IOException {
        Map<Integer, Parser.GrammarRule<Integer, Integer>> rules = parsedGrammar.grammarRules();

        for (int grammarRule : parsedGrammar.sequence()) {
            expandToWriter(grammarRule, rules, writer);
        }

        writer.flush(); // Flush the output when finished
    }

    // Recursive expansion that writes symbols directly to the Writer
    private static void expandToWriter(int symbol, Map<Integer, Parser.GrammarRule<Integer, Integer>> grammar, Writer writer) throws IOException {
        if (symbol < 256) {
            // Terminal symbol: write character directly
            writer.write((char) symbol);
        } else {
            // Non-terminal: expand recursively
            Parser.GrammarRule<Integer, Integer> rule = grammar.get(symbol);
            if (rule == null) {
                throw new IllegalArgumentException("Missing rule for symbol: " + symbol);
            }

            for (int subSymbol : rule.rhs) {
                expandToWriter(subSymbol, grammar, writer);
            }
        }
    }
}