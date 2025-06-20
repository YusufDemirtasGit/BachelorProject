package grammarextractor;
import java.util.Map;
public class Decompressor {
    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int GrammarRule : parsedGrammar.sequence()) {
            expandLeaf(GrammarRule, parsedGrammar.grammarRules(), output);
        }

        return output.toString();
    }
    //I actually wanted to call it just expand, but since this is a direct translation of the original code by Re_Serp by Shirou Maruyama, I leave the name as is
    private static void expandLeaf(int symbol,Map<Integer, Parser.GrammarRule<Integer, Integer>> grammar, StringBuilder output) {
        if (symbol < 256) {
            // Current Symbol is a Terminal: convert to readable character
            output.append((char) symbol);
        } else {
            // Current Symbol is a Non-terminal: expand using GrammarRules
            Parser.GrammarRule<Integer, Integer> rule = grammar.get(symbol);
            if (rule == null) {
                throw new IllegalArgumentException("Missing rule for symbol: " + symbol);
            }
            // Idea is to expand the left hand side until we hit a terminal then continue with right hand size
            expandLeaf(rule.lhs, grammar, output);
            expandLeaf(rule.rhs, grammar, output);
        }
    }
}
