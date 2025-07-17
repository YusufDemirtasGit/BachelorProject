package grammarextractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.io.*;

import static grammarextractor.Parser.printGrammar;
import static grammarextractor.Recompressor.*;

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

                    try (PrintWriter out = new PrintWriter("excerpt_output.txt")) {
                        out.println(excerpt);
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
                case 9:
                    try {
                        System.out.println("\nEnter the path to the human-readable grammar file:");
                        String filename = scanner.nextLine().trim();
                        Path path = Paths.get(filename);
                        Parser.ParsedGrammar grammarInlet = Parser.parseFile(path);

                        System.out.println("\nBefore PopInlet:");
                        printGrammar(grammarInlet);

                        // Decompress before PopInlet
                        String beforeDecompressed = Decompressor.decompress(grammarInlet);

                        // Perform PopInlet
                        Map<Integer, Map<Integer, Integer>> usageMatrix = Recompressor.buildUsageMatrix(grammarInlet.grammarRules());
                        Map<Integer, Set<Integer>> reverseUsageMap = Recompressor.buildReverseUsageMap(usageMatrix);
                        Recompressor.popInlet(grammarInlet.grammarRules(), reverseUsageMap);

                        System.out.println("\nAfter PopInlet:");
                        printGrammar(grammarInlet);

                        // Decompress after PopInlet
                        String afterDecompressed = Decompressor.decompress(grammarInlet);

                        // Compare decompressed results
                        System.out.println("\n\nDecompressed results before pop inlet:");
                        System.out.println(beforeDecompressed);
                        System.out.println("\n\nDecompressed results after pop inlet:");
                        System.out.println(afterDecompressed);
                        if (beforeDecompressed.equals(afterDecompressed)) {
                            System.out.println("\n✅ Sequences are identical after PopInlet.");
                        } else {
                            System.out.println("\n❌ Sequences differ after PopInlet.");
                        }

                    } catch (Exception e) {
                        System.err.println("\nAn error occurred during PopInlet test: " + e.getMessage());
                        e.printStackTrace();
                    }
                case 10:
                    try {
                        System.out.println("\nEnter the path to the human-readable grammar file:");
                        String filename = scanner.nextLine().trim();
                        Path path = Paths.get(filename);
                        Parser.ParsedGrammar grammarOutlet = Parser.parseFile(path);

                        System.out.println("\nBefore PopOutlet:");
                        printGrammar(grammarOutlet);
                        // Decompress before PopInlet
                        String beforeDecompressed2 = Decompressor.decompress(grammarOutlet);

                        Map<Integer, Map<Integer, Integer>> usageMatrix = Recompressor.buildUsageMatrix(grammarOutlet.grammarRules());
                        Map<Integer, Set<Integer>> reverseUsageMap = Recompressor.buildReverseUsageMap(usageMatrix);

                        Recompressor.popOutlet(grammarOutlet.grammarRules(), reverseUsageMap);

                        System.out.println("\nAfter PopOutlet:");
                        printGrammar(grammarOutlet);
                        // Decompress after PopInlet
                        String afterDecompressed2 = Decompressor.decompress(grammarOutlet);

                        // Compare decompressed results
                        System.out.println("\n\nDecompressed results before pop outlet:");
                        System.out.println(beforeDecompressed2);
                        System.out.println("\n\nDecompressed results after pop outlet:");
                        System.out.println(afterDecompressed2);
                        if (beforeDecompressed2.equals(afterDecompressed2)) {
                            System.out.println("\n✅ Sequences are identical after PopOutlet.");
                        } else {
                            System.out.println("\n❌ Sequences differ after PopOutlet.");
                        }

                    } catch (Exception e) {
                        System.err.println("\nAn error occurred during PopOutlet test: " + e.getMessage());
                        e.printStackTrace();
                    }
                    break;
                case 11:
                    // Step 1: Load the full grammar from file
                    Path inputPath = Path.of("input_grammar.txt"); // replace with actual path
                    Parser.ParsedGrammar fullGrammar = Parser.parseFile(inputPath);

                    // Step 2: Extract a subgrammar (change range as needed)
                    int start = 2;
                    int end = 8;
                    Parser.ParsedGrammar excerpt_rec = Extractor.extractExcerpt(fullGrammar, start, end);

                    System.out.println("\n== Extracted Grammar ==");
                    Extractor.writeGrammarToFile(excerpt_rec, "excerpt_before_recompression.txt");
                    System.out.println("Excerpt written to: excerpt_before_recompression.txt");

                    // Step 3: Decompress before recompression for validation
                    String beforeRecompression = Decompressor.decompress(excerpt_rec);
                    System.out.println("\nDecompressed before recompression:\n" + beforeRecompression);

                    // Step 4: Recompress
                    Recompressor.recompress(excerpt_rec);

                    // Step 5: Decompress again for validation
                    String afterRecompression = Decompressor.decompress(excerpt_rec);
                    System.out.println("\nDecompressed after recompression:\n" + afterRecompression);

                    // Step 6: Output new grammar
                    Extractor.writeGrammarToFile(excerpt_rec, "excerpt_after_recompression.txt");
                    System.out.println("Recompressed grammar written to: excerpt_after_recompression.txt");

                    // Step 7: Check correctness
                    if (beforeRecompression.equals(afterRecompression)) {
                        System.out.println("\n✅ Roundtrip decompression successful. No data loss.");
                    } else {
                        System.out.println("\n❌ Roundtrip decompression mismatch detected.");
                    }



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
