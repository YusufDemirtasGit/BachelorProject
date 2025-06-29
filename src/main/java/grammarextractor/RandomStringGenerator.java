package grammarextractor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

public class RandomStringGenerator {

    private static final char[] CHAR_POOL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final Random RANDOM = new Random();

    public static void generateRandomStringToFile(int length, String fileName) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            final int CHUNK_SIZE = 100_000;  // Write in chunks to avoid memory issues for huge strings

            int written = 0;
            char[] buffer = new char[CHUNK_SIZE];

            while (written < length) {
                int chunk = Math.min(CHUNK_SIZE, length - written);

                for (int i = 0; i < chunk; i++) {
                    buffer[i] = CHAR_POOL[RANDOM.nextInt(CHAR_POOL.length)];
                }

                writer.write(buffer, 0, chunk);
                written += chunk;
            }
        }
    }
}
