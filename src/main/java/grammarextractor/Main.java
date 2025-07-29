    package grammarextractor;

    import java.nio.file.Path;
    import java.nio.file.Paths;
    import java.util.*;
    import java.io.*;

    public class Main {
        public static void main(String[] args) throws IOException, InterruptedException {
            Scanner scanner = new Scanner(System.in);
            do {
                System.out.println("\nWhich mode would you like to use?\n");
                System.out.println("1. Compress");
                System.out.println("2. Compress(Gonzales) not yet implemented");
                System.out.println("3. Decompress");
                System.out.println("4. Decompress(Gonzales) not yet implemented");
                System.out.println("5. Roundtrip");
                System.out.println("6. Extract Excerpt(Not Yet fully implemented)");
                System.out.println("7. Create human readable format");
                System.out.println("8. Decompress Human readable format back into string");
                System.out.println("9. PopInlet Roundtrip");
                System.out.println("10. PopOutlet Roundtrip");
                System.out.println("11. Metadata Roundtrip");
                System.out.println("12. Frequency Roundtrip");
                System.out.println("13. Roundtrip Frequency Comparison");
                System.out.println("99. Exit");

                System.out.print("Enter your choice: ");
                int choice;
                try {
                    choice = Integer.parseInt(scanner.nextLine().trim());
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number.");
                    continue;
                }
                switch (choice) {
                    case 1:
                        System.out.println("\nPlease enter the input file you would like to compress:");
                        Path fileToCompress = Paths.get(scanner.nextLine().trim());
                        ProcessBuilder builder1 = new ProcessBuilder("./encoder", fileToCompress.toString());
                        builder1.inheritIO(); // Optional: should let the decoder print to console
                        Process process1 = builder1.start();
                        int exitCode1 = process1.waitFor();
                        if (exitCode1 == 0) {
                            System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + fileToCompress + ".rp");
                        } else {
                            System.err.println("\nEncoder failed with exit code " + exitCode1);
                        }
                        break;

                    case 3:
                        System.out.println("\nPlease enter the compressed input file you would like to decompress (File name ends with .rp):");
                        Path fileToTranslate = Paths.get(scanner.nextLine());
                        ProcessBuilder builder2 = new ProcessBuilder("./decoder", fileToTranslate.toString(), "input_translated.txt");
                        builder2.inheritIO(); // Optional: should let the decoder print to console
                        Process process2 = builder2.start();
                        int exitCode2 = process2.waitFor();
                        if (exitCode2 == 0) {
                            System.out.println("\nBinary file translated successfully. The result is saved under input_translated.txt");
                        } else {
                            System.err.println("\nDecoder failed with exit code " + exitCode2);
                        }
                        System.out.println("\nParsing the grammar from input_translated.txt");
                        Parser.ParsedGrammar parsedGrammar = Parser.parseFile(Paths.get("input_translated.txt"));

                        String output = Decompressor.decompress(parsedGrammar);
                        try (PrintWriter out = new PrintWriter("output.txt")) {
                            out.println(output);
                            System.out.println("\nDecompression successful. Resulting text file is saved as output.txt");
                        } catch (FileNotFoundException e) {
                            throw new FileNotFoundException();
                        }
                        break;
                    case 5:
                        System.out.println("\nRoundtrip can either be used with a randomly generated string or an input file of your choice");
                        System.out.println("1. Test with randomly generated string");
                        System.out.println("2. Test with an input file of your choice");
                        int choice2;
                        choice2 = Integer.parseInt(scanner.nextLine().trim());
                        Path fileToTest = Paths.get("Input.txt");
                        if (choice2 == 1) {
                            System.out.println("\nHow long should the test input be");
                            int length = Integer.parseInt(scanner.nextLine().trim());
                            RandomStringGenerator.generateRandomStringToFile(length, "test_input_random.txt");
                            System.out.println("Random string generated and saved to test_input_random.txt");
                            fileToTest = Paths.get("test_input_random.txt");
                        } else if (choice2 == 2) {
                            System.out.println("\nEnter the input file you would like to do a whole roundtrip");
                            fileToTest = Paths.get(scanner.nextLine().trim());
                        } else {
                            System.out.println("\nInvalid choice");
                        }
                        ProcessBuilder builder3 = new ProcessBuilder("./encoder", fileToTest.toString());
                        builder3.inheritIO(); // Optional: should let the decoder print to console
                        Process process3 = builder3.start();
                        int exitCode3 = process3.waitFor();
                        if (exitCode3 == 0) {
                            System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + fileToTest + ".rp");
                        } else {
                            System.err.println("\nEncoder failed with exit code " + exitCode3);
                        }

                        Path fileToTranslate2 = Paths.get(fileToTest + ".rp");
                        ProcessBuilder builder4 = new ProcessBuilder("./decoder", fileToTranslate2.toString(), "test_translated.txt");
                        builder4.inheritIO(); // Optional: should let the decoder print to console
                        Process process4 = builder4.start();
                        int exitCode4 = process4.waitFor();
                        if (exitCode4 == 0) {
                            System.out.println("\nBinary file translated successfully. The result is saved under test_translated.txt");
                        } else {
                            System.err.println("\nDecoder failed with exit code " + exitCode4);
                        }
                        System.out.println("\nParsing the grammar from test_translated.txt");
                        Parser.ParsedGrammar parsedGrammar2 = Parser.parseFile(Paths.get("test_translated.txt"));

                        String output2 = Decompressor.decompress(parsedGrammar2);
                        try (PrintWriter out = new PrintWriter("test_output.txt")) {
                            out.println(output2);
                            System.out.println("\nDecompression successful. Resulting text file is saved as output.txt");
                        } catch (FileNotFoundException e) {
                            throw new FileNotFoundException();
                        }
                        if (areFilesEqual(fileToTest, Paths.get("test_output.txt"))) {
                            System.out.println("\nTest successful. Input and output are identical");
                        } else {
                            System.err.println("Test failed, Input and Output are not identical");
                        }
                        break;
                    case 6:  //Not yet fully implemented
                        System.out.println("Enter the file name of the compressed grammar:");
                        String compressedGrammarFileName = scanner.nextLine().trim();
                        System.out.println("Enter the start pos for the grammar excerpt:");
                        int from = Integer.parseInt(scanner.nextLine().trim());
                        System.out.println("Enter the end pos for the grammar excerpt:");
                        int to = Integer.parseInt(scanner.nextLine().trim());

                        System.out.println("Parsing the grammar...");
                        Parser.ParsedGrammar grammar = Parser.parseFile(Paths.get(compressedGrammarFileName));

                        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(grammar, from, to);
                        Extractor.writeGrammarToFile(excerpt, "extracted_grammar.txt");
                        String output6 = Decompressor.decompress(excerpt);
                        try (PrintWriter out = new PrintWriter("excerpt_output.txt")) {
                            out.println(output6);
                            System.out.println("\nDecompression successful. Resulting text file is saved as output.txt");
                        } catch (FileNotFoundException e) {
                            throw new FileNotFoundException();
                        }

                        //For debug purposes. The whole rule does not need to get dumped in the console in the final version

    //                    for (int rule : excerpt.sequence()) {
    //                        System.out.println(rule < 256 ? "'" + (char) rule + "'" : "Non-terminal: " + rule);
    //                    }
                        break;
                    case 7:
                        System.out.println("\nPlease enter the input file you would like to compress:");
                        Path fileToCompress3 = Paths.get(scanner.nextLine().trim());
                        ProcessBuilder builder5 = new ProcessBuilder("./encoder", fileToCompress3.toString());
                        builder5.inheritIO(); // Optional: should let the decoder print to console
                        Process process5 = builder5.start();
                        int exitCode5 = process5.waitFor();
                        if (exitCode5 == 0) {
                            System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + fileToCompress3 + ".rp");
                        } else {
                            System.err.println("\nEncoder failed with exit code " + exitCode5);
                        }
                        Path fileToTranslate4 = Paths.get(fileToCompress3 + "rp");
                        ProcessBuilder builder6 = new ProcessBuilder("./decoder", fileToTranslate4.toString(), "input_translated.txt");
                        builder6.inheritIO(); // Optional: should let the decoder print to console
                        Process process6 = builder6.start();
                        int exitCode6 = process6.waitFor();
                        if (exitCode6 == 0) {
                            System.out.println("\nBinary file translated successfully. The result is saved under input_translated.txt");
                        } else {
                            System.err.println("\nDecoder failed with exit code " + exitCode6);
                        }
                        break;
                    case 8:
                        System.out.println("\nPlease enter the human readable grammar input file you would like to decompress:");
                        Path fileToCompress5 = Paths.get(scanner.nextLine().trim());
                        System.out.println("\nParsing the grammar from input_translated.txt");
                        Parser.ParsedGrammar parsedGrammar3 = Parser.parseFile(fileToCompress5);

                        String output3 = Decompressor.decompress(parsedGrammar3);
                        try (PrintWriter out = new PrintWriter("output_from_translated.txt")) {
                            out.println(output3);
                            System.out.println("\nDecompression successful. Resulting text file is saved as output_from_translated");
                        } catch (FileNotFoundException e) {
                            throw new FileNotFoundException();
                        }
                        break;
                    // inside your Main class, in the switch over modes:

//                    case 9:  // or whatever unused case number
//                        System.out.print("Enter grammar file path for recompression test: ");
//                        String recompressPath = scanner.nextLine().trim();
//                        // Parse the grammar
//                        Parser.ParsedGrammar toRecompress = Parser.parseFile(Path.of(recompressPath));
//                        // Run the new Recompressor with verbose=true
//                        Recompressor recompressor = new Recompressor(toRecompress, true);
//                        recompressor.runRecompression();
//                        break;
                    case 11:
                        System.out.print("\nEnter the path to a human-readable grammar file: ");
                        String grammarFile = scanner.nextLine().trim();

                        try {
                            // Step 1: Parse the grammar (automatically computes metadata)
                            System.out.println("\nParsing grammar and computing metadata...");
                            Parser.ParsedGrammar parsed = Parser.parseFile(Path.of(grammarFile));

                            // Step 2: Print grammar rules
                            System.out.println("\n=== Grammar Rules ===");
                            Parser.printGrammar(parsed);

                            // Step 3: Print metadata for each rule
                            System.out.println("\n=== Rule Metadata ===");
                            for (Map.Entry<Integer, RuleMetadata> entry : parsed.metadata().entrySet()) {
                                int ruleId = entry.getKey();
                                RuleMetadata meta = entry.getValue();
                                System.out.printf(
                                        "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s, leftRun=%d, rightRun=%d%n",
                                        ruleId,
                                        meta.getVocc(),
                                        meta.getLength(),
                                        meta.getLeftmostTerminal() == -1 ? "None" : meta.getLeftmostTerminal(),
                                        meta.getRightmostTerminal() == -1 ? "None" : meta.getRightmostTerminal(),
                                        meta.isSingleBlock(),
                                        meta.getLeftRunLength(),
                                        meta.getRightRunLength()
                                );
                            }

                            System.out.println("\n✅ Metadata roundtrip completed.");

                        } catch (Exception e) {
                            System.err.println("An error occurred during metadata roundtrip: " + e.getMessage());
                            e.printStackTrace();
                        }
                        break;

                    case 12:  // Roundtrip: Just compute and print bigram frequencies
                        System.out.print("\nEnter the path to a human-readable grammar file: ");
                        String grammarFile3 = scanner.nextLine().trim();

                        try {
                            // Step 1: Parse grammar and compute metadata
                            System.out.println("\nParsing grammar and computing metadata...");
                            Parser.ParsedGrammar parsed = Parser.parseFile(Path.of(grammarFile3));

                            // Wrap top-level sequence with sentinels and reduce to a root rule
                            Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(parsed);
                            Parser.ParsedGrammar initialized = init.grammar();
                            Set<Integer> artificial = init.artificialTerminals(); // Get from initialization

                            // Recompute metadata for the initialized grammar
                            Map<Integer, RuleMetadata> initializedMetadata = RuleMetadata.computeAll(initialized, artificial);
                            initialized = new Parser.ParsedGrammar(
                                    initialized.grammarRules(),
                                    initialized.sequence(),
                                    initializedMetadata
                            );

                            // Step 2: Print grammar rules
                            System.out.println("\n=== Grammar Rules ===");
                            Parser.printGrammar(parsed);

                            // Step 3: Print metadata (for original parsed grammar)
                            System.out.println("\n=== Rule Metadata ===");
                            for (Map.Entry<Integer, RuleMetadata> entry : parsed.metadata().entrySet()) {
                                int ruleId = entry.getKey();
                                RuleMetadata meta = entry.getValue();
                                System.out.printf(
                                        "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s%n",
                                        ruleId,
                                        meta.getVocc(),
                                        meta.getLength(),
                                        meta.getLeftmostTerminal() == -1 ? "None" : formatSymbol(meta.getLeftmostTerminal()),
                                        meta.getRightmostTerminal() == -1 ? "None" : formatSymbol(meta.getRightmostTerminal()),
                                        meta.isSingleBlock()
                                );
                            }

                            // Step 4: Compute bigram frequencies using new method
                            Map<Pair<Integer, Integer>, Integer> freqs = Recompressor.computeBigramFrequencies(initialized, artificial,false);

                            // Step 5: Print frequencies
                            System.out.println("\n=== Bigram Frequencies ===");
                            for (Map.Entry<Pair<Integer, Integer>, Integer> entry : freqs.entrySet()) {
                                Pair<Integer, Integer> bigram = entry.getKey();
                                int freq = entry.getValue();

                                String left = formatSymbol(bigram.first);
                                String right = formatSymbol(bigram.second);

                                System.out.printf("Bigram (%s, %s): %d%n", left, right, freq);
                            }

                        } catch (Exception e) {
                            System.err.println("❌ Error during frequency roundtrip: " + e.getMessage());
                            e.printStackTrace();
                        }
                        break;

                    case 13: {
                        Path grammarFile13 = Path.of("LoremIpsum.txt");
                        Parser.ParsedGrammar original = Parser.parseFile(grammarFile13);

                        // ✅ Step 1: Wrap with sentinels and create binary grammar
                        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(original);
                        Parser.ParsedGrammar initialized = init.grammar();
                        Set<Integer> artificial = init.artificialTerminals(); // Gets the set from initialization

                        // ✅ Step 2: Compute metadata with artificial terminals
                        Map<Integer, RuleMetadata> newMetadata = RuleMetadata.computeAll(initialized, artificial);
                        Parser.ParsedGrammar parsed = new Parser.ParsedGrammar(
                                initialized.grammarRules(), initialized.sequence(), newMetadata);

                        System.out.println("=== Running Roundtrip Bigram Frequency Test ===");

                        // ✅ Step 4: Compute compressed-space frequency map (new logic)
                        Map<Pair<Integer, Integer>, Integer> advancedFreqs =
                                Recompressor.computeBigramFrequencies(parsed, artificial,false);

                        // ✅ Step 5: Compute naive decompression-based frequency map
                        Map<Pair<Integer, Integer>, Integer> naiveFreqs = computeFromDecompressed(parsed,false,false);

                        // ✅ Step 6: Compare all bigrams
                        Set<Pair<Integer, Integer>> allBigrams = new HashSet<>();
                        allBigrams.addAll(advancedFreqs.keySet());
                        allBigrams.addAll(naiveFreqs.keySet());

                        boolean mismatchFound = false;

                        for (Pair<Integer, Integer> bigram : allBigrams) {
                            int advCount = advancedFreqs.getOrDefault(bigram, 0);
                            int naiveCount = naiveFreqs.getOrDefault(bigram, 0);
                            if (advCount != naiveCount) {
                                mismatchFound = true;
                                String left = formatSymbol(bigram.first);
                                String right = formatSymbol(bigram.second);
                                System.err.printf("❌ Mismatch for bigram (%s, %s): advanced=%d, naive=%d%n",
                                        left, right, advCount, naiveCount);
                            }
                        }

                        // ✅ Step 7: Final Output
                        System.out.println("=== Advanced Frequency Roundtrip Results ===");
                        System.out.println(advancedFreqs);

                        System.out.println("=== Naive Frequency Roundtrip Results ===");
                        System.out.println(naiveFreqs);

                        System.out.println("\n=== Rule Metadata ===");
                        for (Map.Entry<Integer, RuleMetadata> entry : parsed.metadata().entrySet()) {
                            int ruleId = entry.getKey();
                            RuleMetadata meta = entry.getValue();
                            System.out.printf(
                                    "R%d: vocc=%d, length=%d, leftmost=%s, rightmost=%s, singleBlock=%s, leftRun=%d, rightRun=%d%n",
                                    ruleId,
                                    meta.getVocc(),
                                    meta.getLength(),
                                    meta.getLeftmostTerminal() == -1 ? "None" : formatSymbol(meta.getLeftmostTerminal()),
                                    meta.getRightmostTerminal() == -1 ? "None" : formatSymbol(meta.getRightmostTerminal()),
                                    meta.isSingleBlock(),
                                    meta.getLeftRunLength(),
                                    meta.getRightRunLength()
                            );
                        }

                        if (!mismatchFound) {
                            System.out.println("✅ All bigram frequencies match between compressed and naive method!");
                        } else {
                            System.err.println("❌ Some mismatches found! Check logs above.");
                        }

                        break;
                    }

                    case 14:
                        Path grammarFile17 = Path.of("fibonacci.txt");
                        Parser.ParsedGrammar original17 = Parser.parseFile(grammarFile17);
                        Recompressor.recompressNTimes(original17, 1000,true,true,true);

                        break;
                    case 15: {
                        Path grammarFile13 = Path.of("Test_grammar_20_words.txt");
                        Parser.ParsedGrammar original = Parser.parseFile(grammarFile13);



                        System.out.println("=== Running Roundtrip Bigram Frequency Test ===");


                        // ✅ Step 5: Compute naive decompression-based frequency map
                        Map<Pair<Integer, Integer>, Integer> naiveFreqs = computeFromDecompressed(original,false,true);


                        System.out.println("=== Naive Frequency Roundtrip Results ===");
                        System.out.println(naiveFreqs);

                        break;
                    }
                    case 16:
                        Path grammarFile16 = Path.of("Test_from_paper.txt");
                        Parser.ParsedGrammar original16 = Parser.parseFile(grammarFile16);

                        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(original16, new HashSet<>());
                        int ruleId = 257;
                        Parser.printGrammar(original16);
                        System.out.println("=== Rule Metadata ===");
                        RuleMetadata.printMetadata(metadata);
                        Parser.RuleEditor.insert(original16.grammarRules(), ruleId, 0, 65);
                        metadata = RuleMetadata.computeAll(original16, new HashSet<>());
                        RuleMetadata.printMetadata(metadata);
                        Parser.printGrammar(original16);
                        Parser.RuleEditor.set(original16.grammarRules(), ruleId, 1, 12);
                        metadata = RuleMetadata.computeAll(original16, new HashSet<>());
                        RuleMetadata.printMetadata(metadata);
                        Parser.printGrammar(original16);


                        break;


                    case 99:
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Invalid choice or not yet implemented");
                        break;
                }
            } while (true);
        }

        public static boolean areFilesEqual(Path file1, Path file2) throws IOException {
            try (BufferedReader reader1 = new BufferedReader(new FileReader(file1.toFile()));
                 BufferedReader reader2 = new BufferedReader(new FileReader(file2.toFile()))) {

                String line1, line2;
                while (true) {
                    line1 = reader1.readLine();
                    line2 = reader2.readLine();

                    if (line1 == null && line2 == null) return true;
                    if (line1 == null || !line1.equals(line2)) return false;
                }
            }
        }

        public static Map<Pair<Integer, Integer>, Integer> computeFromDecompressed(
                Parser.ParsedGrammar grammar,
                boolean removeSentinels,
                boolean addSentinels
        ) {
            // Step 1: Decompress
            String decompressed = Decompressor.decompress(grammar);

            // Step 2: Add sentinels if requested
            if (addSentinels) {
                decompressed = "#" + decompressed + "$";
                System.out.println("Added sentinel characters '#' (start) and '$' (end) to decompressed string.");
            }

            // Step 3: Remove sentinels if requested
            if (removeSentinels) {
                decompressed = decompressed.replace("#", "").replace("$", "");
                System.out.println("Removed sentinel characters '#' and '$' from decompressed string.");
            }

            Map<Pair<Integer, Integer>, Integer> bigramFreqs = new HashMap<>();

            System.out.println("\n=== Starting Naive Bigram Frequency Computation from Decompressed String ===");
            System.out.println("Decompressed: " + decompressed);

            Set<Integer> repeatingPositions = new HashSet<>();

            // Pass 1: Repeating bigrams (c, c)
            int i = 0;
            while (i < decompressed.length()) {
                char a = decompressed.charAt(i);

                int j = i + 1;
                while (j < decompressed.length() && decompressed.charAt(j) == a) j++;

                int len = j - i;
                if (len >= 2) {
                    int freq = len / 2;
                    Pair<Integer, Integer> bigram = Pair.of((int) a, (int) a);
                    bigramFreqs.merge(bigram, freq, Integer::sum);
                    System.out.printf("Detected run %c^%d → (%c,%c) += %d%n", a, len, a, a, freq);

                    for (int p = i; p < j - 1; p++) {
                        repeatingPositions.add(p);
                    }
                }

                i = j;
            }

            // Pass 2: Non-repeating bigrams (a,b) with a ≠ b
            for (int k = 0; k < decompressed.length() - 1; k++) {
                if (repeatingPositions.contains(k)) continue;

                char a = decompressed.charAt(k);
                char b = decompressed.charAt(k + 1);

                if (a != b) {
                    Pair<Integer, Integer> pair = Pair.of((int) a, (int) b);
                    bigramFreqs.merge(pair, 1, Integer::sum);
                    System.out.printf("Non-repeating bigram (%c,%c) += 1%n", a, b);
                } else {
                    System.out.printf("Skipping (a,a) at [%d,%d] – handled by run detection%n", k, k + 1);
                }
            }

            System.out.println("=== Completed Naive Bigram Frequency Computation ===");
            return bigramFreqs;
        }






        public static String formatSymbol(int sym) {
            if (sym < 32) return "'\\x" + Integer.toHexString(sym) + "'";
            if (sym == 32) return "' '";
            if (sym == 35) return "'#'";
            if (sym == 36) return "'$'";
            if (sym < 127) return "'" + (char) sym + "'";
            return "R" + sym;
        }
    }