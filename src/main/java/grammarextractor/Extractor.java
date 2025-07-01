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
            //This is a necessary step because of the way I have processed the grammar rules. Non-terminals have the attribute length, but the terminals don't. So we need to define it here.
            int symLength = (sym < 256) ? 1 : parsedInput.grammarRules().get(sym).length;

            // Does the symbol contribute to the excerpt? If yes, then process it
            if (totalTraversed + symLength > start) {
                processExcerpt(sym, parsedInput, excerptRules, excerptSeq, start - totalTraversed, end - totalTraversed);
            }
            //Update the total traversed length.
            totalTraversed += symLength;
        }
        // New modified grammar structure for the excerpt
        return new Parser.ParsedGrammar(excerptRules, excerptSeq);
    }
    // Main Idea: Recursively go through a symbol to find the relevant part for the extraction
    private static void processExcerpt(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules, List<Integer> excerptSeq, int startOffset, int endOffset) {
    //Check for a terminal
        if (symbol < 256) {
            //Is it inside the boundaries? Then add it to our result.Otherwise skip it
            if (startOffset <= 0 && endOffset > 0) {
                excerptSeq.add(symbol);
            }
            return;
        }
    //If it is a non-terminal, get its child rules (lhs and rhs are in this case a tuple)
        Parser.GrammarRule<Integer, Integer> ruleData = parsedInput.grammarRules().get(symbol);

        if (ruleData == null) {
            throw new IllegalArgumentException("Rule for symbol " + symbol + " not found in the parsed grammar.");
        }
        //Determine the length of the left child.
        //If it is a terminal 1,otherwise get the length from the Grammar Rules
        int leftSize = (ruleData.lhs < 256) ? 1 : parsedInput.grammarRules().get(ruleData.lhs).length;
        //Navigate to the left child node recursively. If the part we search falls under it, then change the parameters accordingly.
        if (startOffset < leftSize) {
            processExcerpt(ruleData.lhs, parsedInput, excerptRules, excerptSeq, startOffset, Math.min(endOffset, leftSize));
        }
        // Excerpt falls into the rhs node. So we can navigate to the right child and change the parameters accordingly. If the startOffset-left size delivers a negative value that means the excerpt starts before the right child.In which case we can just take 0 which is the beginning of the right child.
        if (endOffset > leftSize) {
            processExcerpt(ruleData.rhs, parsedInput, excerptRules, excerptSeq, Math.max(0, startOffset - leftSize), endOffset - leftSize);
        }

        excerptRules.put(symbol, ruleData);
    }

    // This is purely me translating the result into a "human-readable" format. The idea is to have an intermediate modular file which can be loaded into other programs. It should also help with debugging
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
