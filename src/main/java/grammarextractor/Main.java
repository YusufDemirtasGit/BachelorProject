package grammarextractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Insert a grammar compressed string in the specified form.");
        System.out.println("Enter the PATH for the input file:");
        Path File = Paths.get(scanner.nextLine());
        Parser.ParsedGrammar parsedGrammar= Parser.parseFile(File);
        Map<Integer, Parser.GrammarRule<Integer, Integer>> grammarRules = parsedGrammar.grammarRules();
        List<Integer> compressedString = parsedGrammar.sequence();
        System.out.println("Compressed Grammar has been successfully parsed");
        System.out.println("Compressed Grammar compressed string has been successfully parsed");


//        String fileName = File.getFileName().toString();
//        String parentDir = File.getParent().toString();


    }
}