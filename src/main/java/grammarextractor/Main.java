package grammarextractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.io.*;
import java.util.*;

import static java.awt.SystemColor.text;

public class Main {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Insert a grammar compressed string in the specified form.");
        System.out.println("Enter the input file:");
        Path File = Paths.get(scanner.nextLine());
        Parser.ParsedGrammar parsedGrammar= Parser.parseFile(File);
        Map<Integer, Parser.GrammarRule<Integer, Integer>> grammarRules = parsedGrammar.grammarRules();
        List<Integer> compressedString = parsedGrammar.sequence();
        System.out.println("Compressed Grammar has been successfully parsed");
        System.out.println("---------------------------------------------------------------------------------------");
        System.out.println("Which mode would you like to use?");
        System.out.println("1. Compress");
        System.out.println("2. Decompress");
        System.out.println("3. Roundtrip");
        System.out.println("3. Exit");
        System.out.print("Enter your choice: ");
        int choice = scanner.nextInt();
        switch (choice) {
            case 2:
                String output = Decompressor.decompress(parsedGrammar);
                try (PrintWriter out = new PrintWriter("output.txt"))   {
                    out.println(output);
                } catch (java.io.FileNotFoundException e) {
                    throw new java.io.FileNotFoundException();
                }
        }

//        String fileName = File.getFileName().toString();
//        String parentDir = File.getParent().toString();


    }
}