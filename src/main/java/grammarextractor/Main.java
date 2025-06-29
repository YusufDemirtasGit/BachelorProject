package grammarextractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.io.*;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        Scanner scanner = new Scanner(System.in);
        do {
            System.out.println("\nWhich mode would you like to use?\n");
            System.out.println("1. Compress");
            System.out.println("2. Decompress");
            System.out.println("3. Roundtrip");
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
                        System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + fileToCompress.toString() + ".rp");
                    } else {
                        System.err.println("\nEncoder failed with exit code " + exitCode1);
                    }
                    break;

                case 2:
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
                case 3:
                    System.out.println("\nRoundtrip can either be used with a randomly generated string or an input file of your choice");
                    System.out.println("1. Test with randomly generated string");
                    System.out.println("2. Test with an input file of your choice");
                    int choice2;
                    choice2 = Integer.parseInt(scanner.nextLine().trim());
                    Path fileToTest= Paths.get("Input.txt");
                    if (choice2 == 1) {
                        System.out.println("\nHow long should the test input be");
                        int length = Integer.parseInt(scanner.nextLine().trim());
                        RandomStringGenerator.generateRandomStringToFile(length, "test_input_random.txt");
                        System.out.println("Random string generated and saved to test_input_random.txt");
                        fileToTest = Paths.get("test_input_random.txt");
                    }
                    else if (choice2 == 2) {
                        System.out.println("\nEnter the input file you would like to do a whole roundtrip");
                        fileToTest = Paths.get(scanner.nextLine().trim());
                    }
                    else{
                        System.out.println("\nInvalid choice");
                    }
                    ProcessBuilder builder3 = new ProcessBuilder("./encoder", fileToTest.toString());
                    builder3.inheritIO(); // Optional: should let the decoder print to console
                    Process process3 = builder3.start();
                    int exitCode3 = process3.waitFor();
                    if (exitCode3 == 0) {
                        System.out.println("\nInput file translated successfully. The Resulting binary file is saved as:" + fileToTest.toString() + ".rp");
                    } else {
                        System.err.println("\nEncoder failed with exit code " + exitCode3);
                    }

                    Path fileToTranslate2 = Paths.get(fileToTest.toString()+".rp");
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
                    if(areFilesEqualLineByLine(fileToTest, Paths.get("test_output.txt"))){
                        System.out.println("\nTest successful. Input and output are identical");
                    }
                    else {
                        System.err.println("Test failed, Input and Output are not identical");
                    }
                    break;


                case 99:
                    System.exit(0);

                default:
                    System.out.println("Invalid choice or not yet implemented");
                    break;
            }


        } while (true);

    }
    public static boolean areFilesEqualLineByLine(Path file1, Path file2) throws IOException {
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
