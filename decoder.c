#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>

#define CHAR_SIZE 256
#define DUMMY_CODE -1
#define OP 1

typedef unsigned int uint;
typedef int CODE;

typedef struct {
    CODE left;
    CODE right;
} RULE;

typedef struct {
    FILE *file;
    int currentByte;
    int bitsRemaining;
} BITIN;

BITIN* createBitin(FILE *file) {
    BITIN *bitin = (BITIN*)malloc(sizeof(BITIN));
    bitin->file = file;
    bitin->currentByte = 0;
    bitin->bitsRemaining = 0;
    return bitin;
}

int readBits(BITIN *bitin, int n) {
    int result = 0;
    for (int i = 0; i < n; i++) {
        if (bitin->bitsRemaining == 0) {
            bitin->currentByte = fgetc(bitin->file);
            if (bitin->currentByte == EOF) {
                fprintf(stderr, "Unexpected end of file.\n");
                exit(1);
            }
            bitin->bitsRemaining = 8;
        }
        bitin->bitsRemaining--;
        result = (result << 1) | ((bitin->currentByte >> bitin->bitsRemaining) & 1);
    }
    return result;
}

uint bits(uint n) {
    uint b = 0;
    while (n) {
        b++;
        n >>= 1;
    }
    return b;
}

void DecodeCFG(FILE *input, FILE *output) {
    uint i;
    RULE *rule;
    uint num_rules, txt_len, seq_len;
    BITIN *bitin;
    uint exc, sp;
    CODE *stack;
    CODE newcode, leaf;
    uint bitlen;
    bool paren;

    fread(&txt_len, sizeof(uint), 1, input);
    fread(&num_rules, sizeof(uint), 1, input);
    fread(&seq_len, sizeof(uint), 1, input);
    printf("txt_len = %d, num_rules = %d, seq_len = %d\n",
           txt_len, num_rules, seq_len);

    // Over-allocate to be safe
    uint max_rules = num_rules + 1024;
    rule = (RULE*)calloc(max_rules, sizeof(RULE));
    stack = (CODE*)calloc(max_rules, sizeof(CODE));
    CODE *compressed_seq = (CODE*)calloc(seq_len, sizeof(CODE));

    // Initialize terminals
    for (i = 0; i <= CHAR_SIZE; i++) {
        rule[i].left = i;
        rule[i].right = DUMMY_CODE;
    }
    for (i = CHAR_SIZE + 1; i < max_rules; i++) {
        rule[i].left = DUMMY_CODE;
        rule[i].right = DUMMY_CODE;
    }

    printf("Decoding CFG (flat output)...\n");
    fflush(stdout);
    bitin = createBitin(input);
    newcode = CHAR_SIZE;

    for (i = 0; i < seq_len; i++) {
        exc = 0;
        sp = 0;
        while (1) {
            paren = readBits(bitin, 1);
            if (paren == OP) {
                exc++;
                bitlen = bits(newcode);
                leaf = readBits(bitin, bitlen);
                stack[sp++] = leaf;
            } else {
                exc--;
                if (exc == 0) break;
                newcode++;
                if (newcode >= max_rules) {
                    fprintf(stderr, "Exceeded rule array size. Aborting.\n");
                    exit(1);
                }
                rule[newcode].right = stack[--sp];
                rule[newcode].left  = stack[--sp];
                stack[sp++] = newcode;
            }
        }
        compressed_seq[i] = stack[sp - 1];
    }

    for (i = CHAR_SIZE + 1; i <= newcode; i++) {
        fprintf(output, "R%d:%d,%d\n", i, rule[i].left, rule[i].right);
    }

    fprintf(output, "SEQ:");
    for (i = 0; i < seq_len; i++) {
        fprintf(output, "%d", compressed_seq[i]);
        if (i != seq_len - 1) fprintf(output, ",");
    }
    fprintf(output, "\n");

    free(rule);
    free(stack);
    free(compressed_seq);
    free(bitin);
    printf("Finished!\n");
}

int main(int argc, char *argv[]) {
    if (argc != 3) {
        printf("Usage: decoder <input_binary_file> <output_text_file>\n");
        return 1;
    }

    FILE *input = fopen(argv[1], "rb");
    if (!input) {
        perror("Error opening input file");
        return 1;
    }

    FILE *output = fopen(argv[2], "w");
    if (!output) {
        perror("Error opening output file");
        fclose(input);
        return 1;
    }

    DecodeCFG(input, output);

    fclose(input);
    fclose(output);
    return 0;
}
