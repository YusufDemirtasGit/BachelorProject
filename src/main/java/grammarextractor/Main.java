package grammarextractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.io.*;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Which mode would you like to use?");
        System.out.println("1. Compress");
        System.out.println("2. Decompress");
        System.out.println("3. Roundtrip");
        System.out.println("3. Exit");

        System.out.print("Enter your choice: ");
        int choice = scanner.nextInt();
        switch (choice) {
            case 1:
                System.out.println("Please enter the input file you would like to compress:");
                Path fileToCompress = Paths.get(scanner.nextLine());
                ProcessBuilder builder1 = new ProcessBuilder("./encoder", fileToCompress.toString());
                builder1.inheritIO(); // Optional: should let the decoder print to console
                Process process1 = builder1.start();
                int exitCode1 = process1.waitFor();
                if (exitCode1 == 0) {
                    System.out.println("Input file translated successfully. The Resulting binary file is saved as:" + fileToCompress.toString()+".rp");
                } else {
                    System.err.println("Encoder failed with exit code " + exitCode1);
                }

            case 2:
                System.out.println("Please enter the compressed input file you would like to decompress (File name ends with .rp):");
                Path fileToTranslate = Paths.get(scanner.nextLine());
                ProcessBuilder builder2 = new ProcessBuilder("./decoder", fileToTranslate.toString(),"input_translated.txt");
                builder2.inheritIO(); // Optional: should let the decoder print to console
                Process process2 = builder2.start();
                int exitCode2 = process2.waitFor();
                if (exitCode2 == 0) {
                    System.out.println("Binary file translated successfully. The result is saved under input_translated.txt");
                } else {
                    System.err.println("Decoder failed with exit code " + exitCode2);
                }
                System.out.println("Parsing the grammar from input_translated.txt");
                Parser.ParsedGrammar parsedGrammar= Parser.parseFile(Paths.get("input_translated.txt"));

                String output = Decompressor.decompress(parsedGrammar);
                try (PrintWriter out = new PrintWriter("output.txt"))   {
                    out.println(output);
                    System.out.println("Decompression successful. Resulting text file is saved as output.txt");
                } catch (java.io.FileNotFoundException e) {
                    throw new java.io.FileNotFoundException();
                }
            case 3:

                default:
                    System.out.println("Invalid choice or not yet implemented");
        }


    }
}