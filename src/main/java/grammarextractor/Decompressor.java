package grammarextractor;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

public class Decompressor {

    public static String decompress(Parser.ParsedGrammar parsedGrammar) {
        StringBuilder output = new StringBuilder();

        for (int symbol : parsedGrammar.sequence()) {
            expand(symbol, parsedGrammar.grammarRules(), parsedGrammar.metadata(), output);
        }

        return output.toString();
    }

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

    public static void decompressToWriter(Parser.ParsedGrammar parsedGrammar, Writer writer) throws IOException {
        Map<Integer, List<Integer>> grammar = parsedGrammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = parsedGrammar.metadata();

        for (int symbol : parsedGrammar.sequence()) {
            expandToWriter(symbol, grammar, metadata, writer);
        }

        writer.flush();
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
