package grammarextractor;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int symbol : parsedGrammar.sequence()) {
            expand(symbol, parsedGrammar.grammarRules(), parsedGrammar.metadata(), output, new HashSet<>());
        }

        return output.toString();
    }

    private static void expand(int symbol,
                               Map<Integer, List<Integer>> grammar,
                               Map<Integer, RuleMetadata> metadata,
                               StringBuilder output,
                               Set<Integer> stack) {

        if (symbol < 256) {
            output.append((char) symbol);
            return;
        }

        if (!stack.add(symbol)) {
            throw new IllegalStateException("Cycle detected in grammar at rule R" + symbol);
        }

        List<Integer> rhs = grammar.get(symbol);
        if (rhs == null) {
            throw new IllegalArgumentException("Missing rule for non-terminal: R" + symbol);
        }

        for (int sub : rhs) {
            expand(sub, grammar, metadata, output, stack);
        }

        stack.remove(symbol);
    }


    private static void expandToWriter(int symbol,
                                       Map<Integer, List<Integer>> grammar,
                                       Map<Integer, RuleMetadata> metadata,
                                       Writer writer) throws IOException {

        if (symbol < 256) {
            writer.write((char) symbol);
            return;
        }

        List<Integer> rhs = grammar.get(symbol);
        if (rhs == null) {
            throw new IllegalArgumentException("Missing rule for non-terminal: R" + symbol);
        }

        for (int sub : rhs) {
            expandToWriter(sub, grammar, metadata, writer);
        }
    }
}
