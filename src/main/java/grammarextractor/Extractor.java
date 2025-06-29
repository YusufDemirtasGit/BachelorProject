package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Extractor {


    public static Parser.ParsedGrammar extractExcerpt(Parser.ParsedGrammar parsedInput, int start, int end) throws IllegalArgumentException {
        if (start >= parsedInput.sequence().size()) {
            throw new IllegalArgumentException("Start position must be smaller than the length of the input sequence.");
        } else if (start >= end) {
            throw new IllegalArgumentException("Start position must be smaller than end position.");
        }

        List<Integer> excerptSeq = new ArrayList<>();
        Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules = new HashMap<>();
        int totalTraversed = 0;

        for (int sym : parsedInput.sequence()) {
            //If the end is reached, stop the loop.
            if (totalTraversed >= end) {
                break;
            }

            int symLength = (sym < 256) ? 1 : parsedInput.grammarRules().get(sym).length;
            // If the next symbol gets us over the start, begin processing the rule with the left and right offset.
            if (totalTraversed + symLength > start) {
                processExcerpt(sym, parsedInput, excerptRules, excerptSeq, start - totalTraversed, end - totalTraversed);
            }
        //Update the total traversed length.
            totalTraversed += symLength;
        }

        return new Parser.ParsedGrammar(excerptRules, excerptSeq);
    }

    private static void processExcerpt(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules, List<Integer> excerptSeq, int startOffset, int endOffset) {

        if (symbol < 256) {
            if (startOffset <= 0 && endOffset > 0) {
                excerptSeq.add(symbol);
            }
            return;
        }

        Parser.GrammarRule<Integer, Integer> ruleData = parsedInput.grammarRules().get(symbol);
        if (ruleData == null) {
            throw new IllegalArgumentException("Rule for symbol " + symbol + " not found in the parsed grammar.");
        }

        int leftSize = (ruleData.lhs < 256) ? 1 : parsedInput.grammarRules().get(ruleData.lhs).length;

        if (startOffset < leftSize) {
            processExcerpt(ruleData.lhs, parsedInput, excerptRules, excerptSeq, startOffset, Math.min(endOffset, leftSize));
        }
        if (endOffset > leftSize) {
            processExcerpt(ruleData.rhs, parsedInput, excerptRules, excerptSeq, Math.max(0, startOffset - leftSize), endOffset - leftSize);
        }

        excerptRules.put(symbol, ruleData);
    }
// This is purely me translating the result into a "human-readable" format. The idea is to have an intermediate modular file which can be loaded into other programs.
    public static void writeGrammarToFile(Parser.ParsedGrammar grammarData, String outputFile) throws IOException {
        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(outputFile))) {

            for (Map.Entry<Integer, Parser.GrammarRule<Integer, Integer>> ruleEntry : grammarData.grammarRules().entrySet()) {
                int ruleNum = ruleEntry.getKey();
                Parser.GrammarRule<Integer, Integer> ruleData = ruleEntry.getValue();
                fileWriter.write("R" + ruleNum + ":" + ruleData.lhs + "," + ruleData.rhs);
                fileWriter.newLine();
            }

            fileWriter.write("SEQ:");
            for (int i = 0; i < grammarData.sequence().size(); i++) {
                fileWriter.write(String.valueOf(grammarData.sequence().get(i)));
                if (i != grammarData.sequence().size() - 1) {
                    fileWriter.write(",");
                }
            }
            fileWriter.newLine();
        }
    }
}
