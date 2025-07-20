    package grammarextractor;

    import java.nio.file.Files;
    import java.nio.file.Path;
    import java.nio.file.Paths;
    import java.util.*;
    import java.io.*;

    import static grammarextractor.Parser.printGrammar;

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
                                        "R%d: vocc=%d, length=%d, lambda=%s, pho=%s, singleBlock=%s%n",
                                        ruleId,
                                        meta.getVocc(),
                                        meta.getLength(),
                                        meta.getLambda() == -1 ? "None" : meta.getLambda(),
                                        meta.getPho() == -1 ? "None" : meta.getPho(),
                                        meta.isSingleBlock()
                                );
                            }

                            System.out.println("\n✅ Metadata roundtrip completed.");

                        } catch (Exception e) {
                            System.err.println("An error occurred during metadata roundtrip: " + e.getMessage());
                            e.printStackTrace();
                        }
                        break;


//                    case 11:
//                        try {
//                            System.out.println("\nEnter the path to the human-readable grammar file:");
//                            String filename = scanner.nextLine().trim();
//                            Path grammarPath = Paths.get(filename);
//
//                            System.out.println("Parsing the grammar...");
//                            Parser.ParsedGrammar inputGrammar = Parser.parseFile(grammarPath);
//
//                            System.out.println("Enter output file name prefix (e.g., 'roundtrip'): ");
//                            String prefix = scanner.nextLine().trim();
//
//                            // Decompress original grammar
//                            System.out.println("Decompressing original grammar...");
//                            String originalText = Decompressor.decompress(inputGrammar);
//                            try (PrintWriter out = new PrintWriter(prefix + "_original.txt")) {
//                                out.println(originalText);
//                            }
//                            System.out.println("Original text length: " + originalText.length());
//                            System.out.println("Original grammar size: " + inputGrammar.grammarRules().size() + " rules");
//
//                            // Run recompression
//                            System.out.println("\nRunning RePair recompression...");
//                            System.out.println("Note: Sentinels # and $ will be added to the beginning and end");
//                            Recompressor recompressor = new Recompressor(inputGrammar, true);
//                            Parser.ParsedGrammar recompressed = recompressor.runRecompression();
//
//                            // Decompress recompressed grammar
//                            System.out.println("\nDecompressing recompressed grammar...");
//                            String recompressedText = Decompressor.decompress(recompressed);
//
//                            // Remove sentinels # and $ from the recompressed text
//                            String cleanedText = recompressedText;
//                            if (recompressedText.startsWith("#") && recompressedText.endsWith("$")) {
//                                cleanedText = recompressedText.substring(1, recompressedText.length() - 1);
//                                System.out.println("Removed sentinels from recompressed text");
//                            }
//
//                            try (PrintWriter out = new PrintWriter(prefix + "_recompressed.txt")) {
//                                out.println(cleanedText);
//                            }
//
//                            // Write recompressed text with sentinels for inspection
//                            try (PrintWriter out = new PrintWriter(prefix + "_recompressed_with_sentinels.txt")) {
//                                out.println(recompressedText);
//                            }
//
//                            // Write recompressed grammar
//                            System.out.println("\nWriting recompressed grammar to " + prefix + "_grammar.txt");
//                            Extractor.writeGrammarToFile(recompressed, prefix + "_grammar.txt");
//                            System.out.println("Recompressed grammar size: " + recompressed.grammarRules().size() + " rules");
//
//                            // Compare results
//                            System.out.println("\n=== Roundtrip Results ===");
//                            System.out.println("Original length: " + originalText.length());
//                            System.out.println("Recompressed length (without sentinels): " + cleanedText.length());
//                            System.out.println("Original rules: " + inputGrammar.grammarRules().size());
//                            System.out.println("Recompressed rules: " + recompressed.grammarRules().size());
//
//                            if (originalText.equals(cleanedText)) {
//                                System.out.println("\n✅ Recompression roundtrip successful! Text preserved.");
//
//                                // Calculate compression ratio
//                                int originalRules = inputGrammar.grammarRules().size();
//                                int recompressedRules = recompressed.grammarRules().size();
//                                double ratio = (double) recompressedRules / originalRules;
//                                System.out.printf("Compression ratio: %.2f%% of original grammar size%n", ratio * 100);
//                            } else {
//                                System.out.println("\n❌ Recompression roundtrip failed! Text changed.");
//
//                                // Show where differences start
//                                int diffIndex = -1;
//                                for (int i = 0; i < Math.min(originalText.length(), cleanedText.length()); i++) {
//                                    if (originalText.charAt(i) != cleanedText.charAt(i)) {
//                                        diffIndex = i;
//                                        break;
//                                    }
//                                }
//
//                                if (diffIndex >= 0) {
//                                    System.out.println("First difference at position " + diffIndex);
//                                    int start = Math.max(0, diffIndex - 20);
//                                    int end = Math.min(diffIndex + 20, Math.min(originalText.length(), cleanedText.length()));
//                                    System.out.println("Original: ..." + originalText.substring(start, end) + "...");
//                                    System.out.println("Recompressed: ..." + cleanedText.substring(start, end) + "...");
//                                } else if (originalText.length() != cleanedText.length()) {
//                                    System.out.println("Texts have different lengths");
//                                }
//                            }
//
//                            // Additional statistics
//                            System.out.println("\n=== Grammar Statistics ===");
//                            System.out.println("Files created:");
//                            System.out.println("  - " + prefix + "_original.txt (original decompressed text)");
//                            System.out.println("  - " + prefix + "_recompressed.txt (recompressed text without sentinels)");
//                            System.out.println("  - " + prefix + "_recompressed_with_sentinels.txt (with # and $)");
//                            System.out.println("  - " + prefix + "_grammar.txt (recompressed grammar)");
//
//                        } catch (Exception e) {
//                            System.err.println("An error occurred during recompression roundtrip: " + e.getMessage());
//                            e.printStackTrace();
//                        }
//                        break;

                    //                case 9:
    //                    try {
    //                        System.out.println("\nEnter the path to the human-readable grammar file:");
    //                        String filename = scanner.nextLine().trim();
    //                        Path path = Paths.get(filename);
    //                        Parser.ParsedGrammar grammarInlet = Parser.parseFile(path);
    //
    //                        System.out.println("\nBefore PopInlet:");
    //                        printGrammar(grammarInlet);
    //
    //                        // Decompress before PopInlet
    //                        String beforeDecompressed = Decompressor.decompress(grammarInlet);
    //
    //                        // Perform PopInlet
    //                        Map<Integer, Map<Integer, Integer>> usageMatrix = Recompressor.buildUsageMatrix(grammarInlet.grammarRules());
    //                        Map<Integer, Set<Integer>> reverseUsageMap = Recompressor.buildReverseUsageMap(usageMatrix);
    //                        Recompressor.popInlet(grammarInlet.grammarRules(), reverseUsageMap);
    //
    //                        System.out.println("\nAfter PopInlet:");
    //                        printGrammar(grammarInlet);
    //
    //                        // Decompress after PopInlet
    //                        String afterDecompressed = Decompressor.decompress(grammarInlet);
    //
    //                        // Compare decompressed results
    //                        System.out.println("\n\nDecompressed results before pop inlet:");
    //                        System.out.println(beforeDecompressed);
    //                        System.out.println("\n\nDecompressed results after pop inlet:");
    //                        System.out.println(afterDecompressed);
    //                        if (beforeDecompressed.equals(afterDecompressed)) {
    //                            System.out.println("\n✅ Sequences are identical after PopInlet.");
    //                        } else {
    //                            System.out.println("\n❌ Sequences differ after PopInlet.");
    //                        }
    //
    //                    } catch (Exception e) {
    //                        System.err.println("\nAn error occurred during PopInlet test: " + e.getMessage());
    //                        e.printStackTrace();
    //                    }
    //                case 10:
    //                    try {
    //                        System.out.println("\nEnter the path to the human-readable grammar file:");
    //                        String filename = scanner.nextLine().trim();
    //                        Path path = Paths.get(filename);
    //                        Parser.ParsedGrammar grammarOutlet = Parser.parseFile(path);
    //
    //                        System.out.println("\nBefore PopOutlet:");
    //                        printGrammar(grammarOutlet);
    //                        // Decompress before PopInlet
    //                        String beforeDecompressed2 = Decompressor.decompress(grammarOutlet);
    //
    //                        Map<Integer, Map<Integer, Integer>> usageMatrix = Recompressor.buildUsageMatrix(grammarOutlet.grammarRules());
    //                        Map<Integer, Set<Integer>> reverseUsageMap = Recompressor.buildReverseUsageMap(usageMatrix);
    //
    //                        Recompressor.popOutlet(grammarOutlet.grammarRules(), reverseUsageMap);
    //
    //                        System.out.println("\nAfter PopOutlet:");
    //                        printGrammar(grammarOutlet);
    //                        // Decompress after PopInlet
    //                        String afterDecompressed2 = Decompressor.decompress(grammarOutlet);
    //
    //                        // Compare decompressed results
    //                        System.out.println("\n\nDecompressed results before pop outlet:");
    //                        System.out.println(beforeDecompressed2);
    //                        System.out.println("\n\nDecompressed results after pop outlet:");
    //                        System.out.println(afterDecompressed2);
    //                        if (beforeDecompressed2.equals(afterDecompressed2)) {
    //                            System.out.println("\n✅ Sequences are identical after PopOutlet.");
    //                        } else {
    //                            System.out.println("\n❌ Sequences differ after PopOutlet.");
    //                        }
    //
    //                    } catch (Exception e) {
    //                        System.err.println("\nAn error occurred during PopOutlet test: " + e.getMessage());
    //                        e.printStackTrace();
    //                    }
    //                    break;
    //                case 11:
    //                    try {
    //                        System.out.println("\nEnter the input file you would like to test recompression roundtrip:");
    //                        Path inputFile = Paths.get(scanner.nextLine().trim());
    //
    //                        System.out.println("Compressing the file...");
    //                        ProcessBuilder encoder = new ProcessBuilder("./encoder", inputFile.toString());
    //                        encoder.inheritIO();
    //                        Process encodeProcess = encoder.start();
    //                        int encodeExit = encodeProcess.waitFor();
    //                        if (encodeExit != 0) {
    //                            System.err.println("Encoder failed.");
    //                            break;
    //                        }
    //
    //                        Path compressedFile = Paths.get(inputFile + ".rp");
    //
    //                        System.out.println("Decoding compressed file to human-readable grammar...");
    //                        ProcessBuilder decoder = new ProcessBuilder("./decoder", compressedFile.toString(), "input_translated.txt");
    //                        decoder.inheritIO();
    //                        Process decodeProcess = decoder.start();
    //                        int decodeExit = decodeProcess.waitFor();
    //                        if (decodeExit != 0) {
    //                            System.err.println("Decoder failed.");
    //                            break;
    //                        }
    //
    //                        System.out.println("Parsing the grammar...");
    //                        Parser.ParsedGrammar fullGrammar = Parser.parseFile(Paths.get("input_translated.txt"));
    //
    //                        System.out.println("Enter the start position for extraction:");
    //                        int start = Integer.parseInt(scanner.nextLine().trim());
    //                        System.out.println("Enter the end position for extraction:");
    //                        int end = Integer.parseInt(scanner.nextLine().trim());
    //
    //                        System.out.println("Extracting grammar excerpt...");
    //                        Parser.ParsedGrammar excerpt2 = Extractor.extractExcerpt(fullGrammar, start, end);
    //
    //                        // Count rules and RHS symbol size before recompression
    //                        int ruleCountBefore = excerpt2.grammarRules().size();
    //                        int rhsSymbolCountBefore = excerpt2.grammarRules().values().stream()
    //                                .mapToInt(rule -> rule.rhs.size())
    //                                .sum();
    //
    //                        System.out.println("Decompressing excerpt before recompression...");
    //                        String before = Decompressor.decompress(excerpt2);
    //
    //                        System.out.println("Writing excerpt to excerpt_before_recompression.txt");
    //                        Extractor.writeGrammarToFile(excerpt2, "excerpt_before_recompression.txt");
    //
    //                        System.out.println("Recompressing excerpt...");
    //                        Recompressor.recompress(excerpt2);
    //
    //                        // Count rules and RHS size after recompression
    //                        int ruleCountAfter = excerpt2.grammarRules().size();
    //                        int rhsSymbolCountAfter = excerpt2.grammarRules().values().stream()
    //                                .mapToInt(rule -> rule.rhs.size())
    //                                .sum();
    //
    //                        System.out.println("Decompressing excerpt after recompression...");
    //                        String after = Decompressor.decompress(excerpt2);
    //
    //                        System.out.println("Writing recompressed grammar to excerpt_after_recompression.txt");
    //                        Extractor.writeGrammarToFile(excerpt2, "excerpt_after_recompression.txt");
    //
    //                        // Compare decompressed output
    //                        if (before.equals(after)) {
    //                            System.out.println("\nRecompression roundtrip successful. Text preserved.");
    //                        } else {
    //                            System.out.println("\nRecompression roundtrip failed. Text changed.");
    //                        }
    //
    //                        // Grammar structure stats
    //                        System.out.println("\nGrammar size comparison:");
    //                        System.out.println("  Number of rules before recompression: " + ruleCountBefore);
    //                        System.out.println("  Number of rules after recompression : " + ruleCountAfter);
    //                        System.out.println("  Total RHS symbols before recompression: " + rhsSymbolCountBefore);
    //                        System.out.println("  Total RHS symbols after recompression : " + rhsSymbolCountAfter);
    //
    //                        // File size stats
    //                        Path beforeFile = Paths.get("excerpt_before_recompression.txt");
    //                        Path afterFile = Paths.get("excerpt_after_recompression.txt");
    //                        long sizeBefore = Files.size(beforeFile);
    //                        long sizeAfter = Files.size(afterFile);
    //
    //                        System.out.println("\nFile size comparison (in bytes):");
    //                        System.out.println("  Size before recompression: " + sizeBefore + " bytes");
    //                        System.out.println("  Size after recompression : " + sizeAfter + " bytes");
    //
    //                        double ratio = (sizeAfter == 0) ? 0.0 : ((double) sizeBefore / sizeAfter);
    //                        System.out.printf("  Compression ratio: %.2f×\n", ratio);
    //
    //                    } catch (Exception e) {
    //                        System.err.println("An error occurred during recompression roundtrip: " + e.getMessage());
    //                        e.printStackTrace();
    //                    }
    //                    break;



                    case 99:
                        System.exit(0);

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

                    if (line1 == null && line2 == null) {
                        return true; // Both files ended, contents identical
                    }
                    //it should not be necessary to check for line2 for null
                    if (line1 == null || !line1.equals(line2)) {
                        return false;
                    }
                }
            }
        }
    }
