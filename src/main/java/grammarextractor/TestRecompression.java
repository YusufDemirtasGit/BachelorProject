package grammarextractor;

import java.util.*;

public class TestRecompression {

    public static void main(String[] args) {
        System.out.println("=== Testing RePair Recompression ===\n");

        // Test 1: Simple example from the paper
        testPaperExample();

        // Test 2: Basic repeating pattern
        testRepeatingPattern();

        // Test 3: Complex grammar
        testComplexGrammar();
    }

    /**
     * Test the example from the paper
     */
    private static void testPaperExample() {
        System.out.println("Test 1: Paper Example");
        System.out.println("---------------------");

        // Create grammar for "aabaaabaaabaa"
        Map<Integer, List<Integer>> rules = new HashMap<>();
        rules.put(256, Arrays.asList(97, 97)); // R256 -> aa
        rules.put(257, Arrays.asList(98, 256)); // R257 -> baa
        rules.put(258, Arrays.asList(256, 257)); // R258 -> aabaa
        rules.put(259, Arrays.asList(258, 256, 257, 256)); // R259 -> aabaaaabaaa

        Parser.ParsedGrammar grammar = new Parser.ParsedGrammar(
                rules,
                Collections.singletonList(259),
                Collections.emptyMap()
        );

        // Test decompression
        String original = Decompressor.decompress(grammar);
        System.out.println("Original string: " + original);

        // Apply recompression
        try {
            Parser.ParsedGrammar repairGrammar =
                    Recompressor.recompressNTimes(grammar, 10, true);

            String recompressed = Decompressor.decompress(repairGrammar);
            System.out.println("\nRecompressed string: " + recompressed);

            if (original.equals(recompressed)) {
                System.out.println("✓ Test PASSED: Strings match!");
            } else {
                System.out.println("✗ Test FAILED: Strings don't match!");
                System.out.println("Expected: " + original);
                System.out.println("Got: " + recompressed);
            }
        } catch (Exception e) {
            System.out.println("✗ Test FAILED with exception: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n");
    }

    /**
     * Test with repeating pattern
     */
    private static void testRepeatingPattern() {
        System.out.println("Test 2: Repeating Pattern");
        System.out.println("-------------------------");

        // Create grammar for "aaaabbbbaaaabbbb"
        Map<Integer, List<Integer>> rules = new HashMap<>();
        rules.put(256, Arrays.asList(97, 97, 97, 97)); // R256 -> aaaa
        rules.put(257, Arrays.asList(98, 98, 98, 98)); // R257 -> bbbb
        rules.put(258, Arrays.asList(256, 257)); // R258 -> aaaabbbb
        rules.put(259, Arrays.asList(258, 258)); // R259 -> aaaabbbbaaaabbbb

        Parser.ParsedGrammar grammar = new Parser.ParsedGrammar(
                rules,
                Collections.singletonList(259),
                Collections.emptyMap()
        );

        runTest(grammar, "Repeating pattern");
        System.out.println("\n");
    }

    /**
     * Test with more complex grammar
     */
    private static void testComplexGrammar() {
        System.out.println("Test 3: Complex Grammar");
        System.out.println("-----------------------");

        // Create grammar for a more complex string
        Map<Integer, List<Integer>> rules = new HashMap<>();
        rules.put(256, Arrays.asList(97, 98)); // R256 -> ab
        rules.put(257, Arrays.asList(256, 97)); // R257 -> aba
        rules.put(258, Arrays.asList(257, 256)); // R258 -> abaab
        rules.put(259, Arrays.asList(258, 257, 258)); // R259 -> abaabaabaabaab

        Parser.ParsedGrammar grammar = new Parser.ParsedGrammar(
                rules,
                Collections.singletonList(259),
                Collections.emptyMap()
        );

        runTest(grammar, "Complex grammar");
    }

    /**
     * Helper method to run a test
     */
    private static void runTest(Parser.ParsedGrammar grammar, String testName) {
        try {
            String original = Decompressor.decompress(grammar);
            System.out.println("Original string: " + original);
            System.out.println("Original length: " + original.length());

            Parser.ParsedGrammar repairGrammar =
                    Recompressor.recompressNTimes(grammar, 20, false);

            String recompressed = Decompressor.decompress(repairGrammar);

            if (original.equals(recompressed)) {
                System.out.println("✓ " + testName + " PASSED!");

                // Print some statistics
                System.out.println("Original grammar rules: " + grammar.grammarRules().size());
                System.out.println("RePair grammar rules: " + repairGrammar.grammarRules().size());
            } else {
                System.out.println("✗ " + testName + " FAILED!");
                System.out.println("Expected: " + original);
                System.out.println("Got: " + recompressed);
            }
        } catch (Exception e) {
            System.out.println("✗ " + testName + " FAILED with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test frequency computation
     */
    public static void testFrequencyComputation() {
        System.out.println("Testing Frequency Computation");
        System.out.println("-----------------------------");

        // Create simple grammar
        Map<Integer, List<Integer>> rules = new HashMap<>();
        rules.put(256, Arrays.asList(97, 97)); // R256 -> aa
        rules.put(257, Arrays.asList(98, 256)); // R257 -> baa

        Parser.ParsedGrammar grammar = new Parser.ParsedGrammar(
                rules,
                Collections.singletonList(257),
                Collections.emptyMap()
        );

        // Convert to compressed format
        CompressedGrammar compressed =
                CompressedGrammar.fromParsedGrammar(grammar, new HashSet<>());

        // Compute frequencies
        Map<Pair<Integer, Integer>, Integer> frequencies =
                Recompressor.computeBigramFrequencies(compressed, new HashSet<>());

        System.out.println("Bigram frequencies:");
        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            Pair<Integer, Integer> bigram = entry.getKey();
            System.out.printf("(%c,%c): %d\n",
                    (char) bigram.first.intValue(),
                    (char) bigram.second.intValue(),
                    entry.getValue());
        }
    }
}