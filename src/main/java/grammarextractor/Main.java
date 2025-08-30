    package grammarextractor;

    import java.nio.file.Path;
    import java.nio.file.Paths;
    import java.util.*;
    import java.io.*;

    public class Main {
        public static void main(String[] args) throws IOException, InterruptedException {
            System.out.println("Welcome to the Grammar Extractor & Recompressor CLI!");
            System.out.println("=============================");
            System.out.println("This CLI is used to extract and recompress grammars from a file.");
            System.out.println("Initializing...");

            // List of filenames to check and update permissions for
            String[] filesToCheck = { "encoder", "decoder"};

            // Get current working directory
            String currentDir = System.getProperty("user.dir");
            System.out.println("Checking in directory: " + currentDir);

            for (String filename : filesToCheck) {
                File file = new File(currentDir, filename);
                if (file.exists()) {
                    System.out.println("Found: " + filename);

                    boolean readable = file.setReadable(true);
                    boolean writable = file.setWritable(true);
                    boolean executable = file.setExecutable(true);

                    System.out.println("  Set readable:   " + readable);
                    System.out.println("  Set writable:   " + writable);
                    System.out.println("  Set executable: " + executable);

                    // macOS Gatekeeper quarantine removal
                    if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                        Process proc = new ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.getAbsolutePath())
                                .redirectErrorStream(true)
                                .start();
                        proc.waitFor();
                        System.out.println("  Cleared macOS quarantine (if present)");
                    }
                } else {
                    System.out.println("Missing: " + filename);
                }
            }

            if (args.length == 0) {
                // No arguments: run interactive menu
                roundtrip();
                return;
            }

            // Simple CLI parsing
            List<String> argList = Arrays.asList(args);
            if (argList.contains("-h") || argList.contains("--help")) {
                printHelp();
                return;
            }

            if (argList.contains("-c")) {
                String input = getArgValue(argList, "-InputFile");
                if (input == null) {
                    System.err.println("Missing -InputFile for -c (compression).");
                    printHelp();
                    return;
                }
                ProcessBuilder builder1 = new ProcessBuilder("./encoder", input);
                builder1.inheritIO(); // Optional: should let the decoder print to console
                Process process1 = builder1.start();
                int exitCode1 = process1.waitFor();
                if (exitCode1 == 0) {
                    System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + Path.of(input) + ".rp");
                } else {
                    System.err.println("\nEncoder failed with exit code " + exitCode1);
                }
                return;
            }

            if (argList.contains("-d")) {
                String input = getArgValue(argList, "-InputFile");
                String output = getArgValue(argList, "-OutputFile");
                if (input == null || output == null) {
                    System.err.println("Missing -InputFile or -OutputFile for -d (decompression).");
                    printHelp();
                    return;
                }
                ProcessBuilder builder2 = new ProcessBuilder("./decoder", input, output);
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

                String outputstring = Decompressor.decompress(parsedGrammar);
                try (PrintWriter out = new PrintWriter(output)) {
                    out.println(outputstring);
                    System.out.println("\nDecompression successful. Resulting text file is saved as output.txt");
                } catch (FileNotFoundException e) {
                    throw new FileNotFoundException();
                }
                return;
            }

            if (argList.contains("-e")) {
                String input = getArgValue(argList, "-InputFile");
                String output = getArgValue(argList, "-OutputFile");
                String fromStr = getArgValue(argList, "-from");
                String toStr = getArgValue(argList, "-to");

                if (input == null || output == null || fromStr == null || toStr == null) {
                    System.err.println("❌ Missing required arguments for -e (extract).");
                    printHelp();
                    return;
                }

                int from = Integer.parseInt(fromStr);
                int to = Integer.parseInt(toStr);

                System.out.println("Parsing the grammar...");
                Parser.ParsedGrammar grammar = Parser.parseFile(Paths.get(input));
                System.out.println("Extracting excerpt [" + from + ", " + to + ")...");
                Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(grammar, from, to,false);
                System.out.println("Writing excerpt grammar to: " + output);
                Extractor.writeGrammarToFile(excerpt, output);
                System.out.println("✅ Extraction completed successfully.");
                return;
            }

            if (argList.contains("-r")) {
                String input = getArgValue(argList, "-InputFile");
                String output = getArgValue(argList, "-OutputFile");
                String from = getArgValue(argList, "-from");
                String to = getArgValue(argList, "-to");
                String passesStr = getArgValue(argList, "-passes");
                String verbosity = getArgValue(argList, "-verbosity");


                if (input == null || output == null || passesStr == null || from == null || to == null) {
                    System.err.println("❌ Missing required arguments for -r (recompress).");
                    printHelp();
                    return;
                }



                System.out.println("Parsing the grammar...");
                Parser.ParsedGrammar grammar = Parser.parseFile(Paths.get(input));

                Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(grammar,Integer.parseInt(from), Integer.parseInt(to),false);
                System.out.println("Excerpt extraction successful.");
                System.out.println("Recompressing grammar...");
                Recompressor.recompressNTimes(excerpt, Integer.parseInt(passesStr),Boolean.parseBoolean(verbosity),true,false,output);
                System.out.println("Recompression successful. Resulting text file is saved as"+output);

                return;
            }

            System.err.println("❌ Unknown command or missing arguments.");
            printHelp();
        }
        private static String getArgValue(List<String> args, String key) {
            int idx = args.indexOf(key);
            if (idx != -1 && idx + 1 < args.size()) {
                return args.get(idx + 1);
            }
            return null;
        }

        private static void printHelp() {
            System.out.println("""
        === Grammar Extractor & Recompressor CLI ===
        Usage:
          -h                            Show help
          -c -InputFile <file> -OutputFile <file>     Compress file
          -d -InputFile <file> -OutputFile <file>     Decompress file
          -e -from <int> -to <int> -InputFile <file> -OutputFile <file>  Extract excerpt
          -r -from <int> -to <int > -passes <int> -Input <file> -Output <file> Extract and Recompress file
        """);
        }

        private static void roundtrip() throws IOException, InterruptedException {
            Scanner scanner = new Scanner(System.in);
            do {
                System.out.println("\nWhich mode would you like to use?\n");
                System.out.println("1.  Compress");
                System.out.println("2.  Compress (Gonzales) — not implemented");
                System.out.println("3.  Decompress");
                System.out.println("4.  Decompress (Gonzales) — not implemented");
                System.out.println("5.  Roundtrip (random or file)");
                System.out.println("6.  Extract excerpt");
                System.out.println("7.  Create human-readable grammar from .rp");
                System.out.println("8.  Decompress human-readable grammar back into string");
                System.out.println("9.  PopInlet roundtrip — not implemented");
                System.out.println("10. PopOutlet roundtrip — not implemented");
                System.out.println("11. Metadata roundtrip");
                System.out.println("12. Recompression Frequency Calculation roundtrip");
                System.out.println("13. Roundtrip frequency comparison(naive vs recomp)");
                System.out.println("14. Recompress N times (stress test)");
                System.out.println("15. Naive bigram frequencies from decompressed text");
                System.out.println("16. Rule editor demo (insert/set and recompute metadata)");
                System.out.println("17. Excerpt → recompress (experimental)");
                System.out.println("18. Uncross bigrams test ");
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
                    case 6:
                        System.out.println("Enter the file name of the compressed grammar:");
                        String compressedGrammarFileName = scanner.nextLine().trim();
                        System.out.println("Enter the start pos for the grammar excerpt:");
                        int from = Integer.parseInt(scanner.nextLine().trim());
                        System.out.println("Enter the end pos for the grammar excerpt:");
                        int to = Integer.parseInt(scanner.nextLine().trim());

                        System.out.println("Parsing the grammar...");
                        Parser.ParsedGrammar grammar = Parser.parseFile(Paths.get(compressedGrammarFileName));

                        Parser.ParsedGrammar excerpt = Extractor.extractExcerpt(grammar, from, to,false);
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
                        Path fileToTranslate4 = Paths.get(fileToCompress3 + ".rp");
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

                            System.out.println("\n Metadata roundtrip completed.");

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
                            System.err.println(" Error during frequency roundtrip: " + e.getMessage());
                            e.printStackTrace();
                        }
                        break;

                    case 13: {
                        Path grammarFile13 = Path.of("LoremIpsum.txt");
                        Parser.ParsedGrammar original = Parser.parseFile(grammarFile13);

                        //  Step 1: Wrap with sentinels and create binary grammar
                        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(original);
                        Parser.ParsedGrammar initialized = init.grammar();
                        Set<Integer> artificial = init.artificialTerminals(); // Gets the set from initialization

                        //  Step 2: Compute metadata with artificial terminals
                        Map<Integer, RuleMetadata> newMetadata = RuleMetadata.computeAll(initialized, artificial);
                        Parser.ParsedGrammar parsed = new Parser.ParsedGrammar(
                                initialized.grammarRules(), initialized.sequence(), newMetadata);

                        System.out.println("=== Running Roundtrip Bigram Frequency Test ===");

                        //  Step 4: Compute compressed-space frequency map (new logic)
                        Map<Pair<Integer, Integer>, Integer> advancedFreqs =
                                Recompressor.computeBigramFrequencies(parsed, artificial,false);

                        //  Step 5: Compute naive decompression-based frequency map
                        Map<Pair<Integer, Integer>, Integer> naiveFreqs = computeFreqsFromDecompressed(parsed,false,false);

                        //  Step 6: Compare all bigrams
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

                        //  Step 7: Final Output
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
                            System.out.println(" All bigram frequencies match between compressed and naive method!");
                        } else {
                            System.err.println(" Some mismatches found! Check logs above.");
                        }

                        break;
                    }

                    case 14:
                        Path grammarFile17 = Path.of("Test_from_paper.txt");
                        Parser.ParsedGrammar original17 = Parser.parseFile(grammarFile17);
                        Recompressor.recompressNTimes(original17, 0,false,true,false, "output.txt");

                        break;
                    case 15: {
                        Path grammarFile13 = Path.of("Test_grammar_20_words.txt");
                        Parser.ParsedGrammar original = Parser.parseFile(grammarFile13);



                        System.out.println("=== Running Roundtrip Bigram Frequency Test ===");


                        //  Step 5: Compute naive decompression-based frequency map
                        Map<Pair<Integer, Integer>, Integer> naiveFreqs = computeFreqsFromDecompressed(original,false,true);


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

                    case 17:  //Not yet fully implemented
                        System.out.println("Enter the file name of the compressed grammar:");
                        String compressedGrammarFileName2 = scanner.nextLine().trim();
                        System.out.println("Enter the start pos for the grammar excerpt:");
                        int from2 = Integer.parseInt(scanner.nextLine().trim());
                        System.out.println("Enter the end pos for the grammar excerpt:");
                        int to2 = Integer.parseInt(scanner.nextLine().trim());

                        System.out.println("Parsing the grammar...");
                        Parser.ParsedGrammar grammar2 = Parser.parseFile(Paths.get(compressedGrammarFileName2));

                        Parser.ParsedGrammar excerpt2 = Extractor.extractExcerpt(grammar2, from2, to2,false);
                        Extractor.writeGrammarToFile(excerpt2, "extracted_grammar.txt");
                        Recompressor.recompressNTimes(excerpt2, 1000,true,true,true,"output.txt");


                        //For debug purposes. The whole rule does not need to get dumped in the console in the final version

                        //                    for (int rule : excerpt.sequence()) {
                        //                        System.out.println(rule < 256 ? "'" + (char) rule + "'" : "Non-terminal: " + rule);
                        //                    }
                        break;

                    case 18:
                        System.out.println("Enter the file name of the compressed grammar:");
                        String compressedGrammarFileName18 = scanner.nextLine().trim();
                        // Step 1: Parse grammar
                        Parser.ParsedGrammar grammar18 = Parser.parseFile(Paths.get(compressedGrammarFileName18));

                        // Step 2: Compute metadata
                        Map<Integer, RuleMetadata> metadata2 = RuleMetadata.computeAll(grammar18, new HashSet<>());

                        // Step 3: Decompress BEFORE
                        String before = Decompressor.decompress(grammar18);
                        System.out.println("=== BEFORE uncross ===");
                        System.out.println(before);
                        Parser.printGrammar(grammar18);

                        // Step 4: Run uncross
                        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(grammar18.grammarRules());
                        List<Integer> sequence = new ArrayList<>(grammar18.sequence());
                        Recompressor.uncrossBigrams(97,97, rules, metadata2, new HashSet<>());

                        // Build combined grammar again
                        Parser.ParsedGrammar afterGrammar = new Parser.ParsedGrammar(rules, sequence,
                                RuleMetadata.computeAll(new Parser.ParsedGrammar(rules, sequence, Collections.emptyMap()), new HashSet<>()));

                        // Step 5: Decompress AFTER
                        String after = Decompressor.decompress(afterGrammar);
                        System.out.println("=== AFTER uncross ===");
                        System.out.println(after);
                        Parser.printGrammar(afterGrammar);

                        // Step 6: Check equality
                        if (before.equals(after)) {
                            System.out.println("✅ Roundtrip successful: uncross preserved string");
                        } else {
                            System.err.println("❌ Roundtrip failed: mismatch between before and after");
                        }

                        return;
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

        public static Map<Pair<Integer, Integer>, Integer> computeFreqsFromDecompressed(
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