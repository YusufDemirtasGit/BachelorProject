package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Extractor {

    public static Parser.ParsedGrammar extractExcerpt(Parser.ParsedGrammar parsedInput, int start, int end) throws IllegalArgumentException {
        int uncompressedSize = getUncompressedSize(parsedInput);
        if ( end > uncompressedSize ) {
            throw new IllegalArgumentException("Start and end must define a valid range within the uncompressed sequence.");
        }
        else if(start < 0 ){
            throw new IllegalArgumentException("Start must be greater than zero.");
        }
        else if(start > end){
            throw new IllegalArgumentException("Start must be less than or equal to end.");
        }

        List<Integer> excerptSequence = new ArrayList<>();
        Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules = new HashMap<>();

        int totalTraversed = 0;
        int startIndex = 0;
        int endIndex = 0;

        // Find which symbol has the start point in it by going through the sequence and find whether next symbol would have us exceed the start pos
        while (startIndex < parsedInput.sequence().size() && totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex)) <= start) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(startIndex));
            startIndex++;
        }
        int totalTraversedBeforeStart = totalTraversed;

        // Find symbol at end boundary
        totalTraversed = 0;
        while (endIndex < parsedInput.sequence().size() && totalTraversed + getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex)) < end) {
            totalTraversed += getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex));
            endIndex++;
        }

        int totalTraversedBeforeEnd = totalTraversed;
        int endSymbolLength = getSymbolLength(parsedInput, parsedInput.sequence().get(endIndex));

        // Case where start & end are within the same symbol
        if (startIndex == endIndex) {
            int symbol = parsedInput.sequence().get(startIndex);
            // If excerpt aligns exactly with symbol boundaries, take whole symbol
            if (totalTraversedBeforeStart == start && totalTraversedBeforeEnd + endSymbolLength == end) {
                excerptSequence.add(symbol);
                copyRule(symbol, parsedInput, excerptRules);
            } else {
                // Part of the excerpt lies in the symbol, recurse into it
                processExcerptSymbol(symbol, parsedInput, excerptRules, excerptSequence, start - totalTraversedBeforeStart, end - totalTraversedBeforeStart);
            }
            return new Parser.ParsedGrammar(excerptRules, excerptSequence);
        }

        // Iterate through the start symbol
        int startSymbol = parsedInput.sequence().get(startIndex);

        if (totalTraversedBeforeStart == start) {
            // Excerpt starts exactly at the boundary, take whole symbol
            excerptSequence.add(startSymbol);
            copyRule(startSymbol, parsedInput, excerptRules);
        }
        else {
            // A part of the excerpt lies in the Symbol.
            //In this case take the relative start position, and get all the rules from this position to the length of the rule
            processStart(startSymbol, parsedInput, excerptRules, excerptSequence, start - totalTraversedBeforeStart, getSymbolLength(parsedInput, startSymbol));
        }

        // Add all symbols that lie entirely within the excerpt
        // It will simply not get executed, if there are no characters in between
        for (int i = startIndex + 1; i < endIndex; i++) {
            int symbol = parsedInput.sequence().get(i);
            excerptSequence.add(symbol);
            copyRule(symbol, parsedInput, excerptRules);
        }

        // Iterate through the end symbol
        int endSymbol = parsedInput.sequence().get(endIndex);

        if (totalTraversedBeforeEnd + endSymbolLength == end) {
            // Excerpt ends exactly after this symbol, take whole symbol
            excerptSequence.add(endSymbol);
            copyRule(endSymbol, parsedInput, excerptRules);
        } else {
            // Partial overlap, recurse into symbol
            processEnd(endSymbol, parsedInput, excerptRules, excerptSequence, end - totalTraversedBeforeEnd);
        }

        return new Parser.ParsedGrammar(excerptRules, excerptSequence);
    }

    // Recursively processes the start symbol if excerpt starts inside it
    private static void processStart(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules, List<Integer> excerptSequence, int from, int to) {
        if (symbol < 256) {
            excerptSequence.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.lhs);

        if (from < leftSize) {
            processStart(rule.lhs, parsedInput, excerptRules, excerptSequence, from, Math.min(to, leftSize));
            if (to > leftSize) {
                excerptSequence.add(rule.rhs);
                copyRule(rule.rhs, parsedInput, excerptRules);
            }
        } else {
            processStart(rule.rhs, parsedInput, excerptRules, excerptSequence, from - leftSize, to - leftSize);
        }

        copyRule(symbol, parsedInput, excerptRules);
    }

    // Recursively process the end symbol by going through it
    private static void processEnd(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules, List<Integer> excerptSequence, int to) {
        if (symbol < 256) {
            excerptSequence.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.lhs);

        if (to < leftSize) {
            processEnd(rule.lhs, parsedInput, excerptRules, excerptSequence, to);
        } else if (to == leftSize) {
            excerptSequence.add(rule.lhs);
            copyRule(rule.lhs, parsedInput, excerptRules);
        } else {
            excerptSequence.add(rule.lhs);
            copyRule(rule.lhs, parsedInput, excerptRules);
            processEnd(rule.rhs, parsedInput, excerptRules, excerptSequence, to - leftSize);
        }

        copyRule(symbol, parsedInput, excerptRules);
    }

    // For the edge case in which the whole excerpt lies inside one symbol
    //TODO: Make sure this works correctly
    private static void processExcerptSymbol(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules, List<Integer> excerptSeq, int from, int to) {
        if (symbol < 256) {
                excerptSeq.add(symbol);
            return;
        }

        Parser.GrammarRule<Integer, Integer> rule = parsedInput.grammarRules().get(symbol);
        int leftSize = getSymbolLength(parsedInput, rule.lhs);

        if (from < leftSize) {
            processExcerptSymbol(rule.lhs, parsedInput, excerptRules, excerptSeq, from, Math.min(to, leftSize));
        }
        if (to > leftSize) {
            processExcerptSymbol(rule.rhs, parsedInput, excerptRules, excerptSeq, from - leftSize, to - leftSize);
        }

        copyRule(symbol, parsedInput, excerptRules);
    }

    // Adds a rule to the excerpt grammar if not already present
    // TODO: count the occurrences of the rules for the recompression.
    private static void copyRule(int symbol, Parser.ParsedGrammar parsedInput, Map<Integer, Parser.GrammarRule<Integer, Integer>> excerptRules) {
        if (symbol >= 256 && !excerptRules.containsKey(symbol)) {
            excerptRules.put(symbol, parsedInput.grammarRules().get(symbol));
        }
    }

    //TODO: Implement this or bake it into copy rules. I don't know which one would be more performant
    private static void addMissingRules(){

    }

    // Returns the length of a symbol. Since my grammar structure only calculates the length for non-terminals. We have to set the length for terminals manually
    private static int getSymbolLength(Parser.ParsedGrammar parsedInput, int symbol) {
        return (symbol < 256) ? 1 : parsedInput.grammarRules().get(symbol).length;
    }

    // Computes the total uncompressed length of the sequence
    public static int getUncompressedSize(Parser.ParsedGrammar parsedInput) {
        int total = 0;
        for (int symbol : parsedInput.sequence()) {
            total += getSymbolLength(parsedInput, symbol);
        }
        return total;
    }

    // This is a modular approach for saving and loading the grammars. It makes it easier for me to debug later on.
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
