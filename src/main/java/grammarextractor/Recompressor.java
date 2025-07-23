package grammarextractor;

import java.util.*;

import static grammarextractor.Main.formatSymbol;
import static grammarextractor.RuleMetadata.computeAll;

public class Recompressor {
    public static void recompress(Parser.ParsedGrammar originalGrammar,
                                  Parser.ParsedGrammar initializedGrammar) {

        Map<Integer, List<Integer>> rules = initializedGrammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = initializedGrammar.metadata();
        List<Integer> sequence = initializedGrammar.sequence();

        // Step 1: Determine the max rule ID from the original grammar
        int maxRuleId = originalGrammar.grammarRules().keySet().stream()
                .max(Integer::compareTo)
                .orElse(255); // Assume terminal < 256

        int nextRuleId = maxRuleId + 1;

        // Step 2: Recompression loop
//        while (true) {
            // Get most frequent bigram (non-repeating or repeating)
            Pair<Integer, Integer> bigram = getMostFrequentBigram(
                    computeBigramFrequencies(originalGrammar, initializedGrammar));

            if (bigram == null); // No more compressible bigrams

            int c1 = bigram.first;
            int c2 = bigram.second;
            int newRuleId = nextRuleId++;

            System.out.printf("🔁 Replacing bigram (%s, %s) → R%d%n",
                    formatSymbol(c1), formatSymbol(c2), newRuleId);

            // Step 3: Uncross the bigram using PopInLet and PopOutLet
            popInlet(c1, c2, rules, metadata);
            popOutLet(c1, c2, rules, metadata);

            // Step 4: Replace all (c1, c2) occurrences in-place with new rule ID
            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
                List<Integer> rhs = entry.getValue();
                List<Integer> updated = new ArrayList<>();

                for (int i = 0; i < rhs.size(); ) {
                    if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                        updated.add(newRuleId);
                        i += 2;
                    } else {
                        updated.add(rhs.get(i));
                        i++;
                    }
                }

                entry.setValue(updated);
            }

            // Step 5: Add new rule RnewRuleId → c1 c2
            rules.put(newRuleId, List.of(c1, c2));

            // Step 6: Recompute metadata for new rule
            RuleMetadata newMeta = computeAll(new Parser.ParsedGrammar(rules, sequence, metadata)).get(newRuleId);
            metadata.put(newRuleId, newMeta);
//        }

        System.out.println("\n🎉 Full recompression completed.");

        // Print final grammar after all replacements
        System.out.println("\n=== 📦 Final Grammar After Full Recompression ===");
        Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));
    }
    public static void recompressOnceVerbose(Parser.ParsedGrammar originalGrammar) {
        // Step 1: Initialize with sentinels + binary rules
        Parser.ParsedGrammar initialized = initializeWithSentinelsAndRootRule(originalGrammar);

        // Step 2: Compute initial metadata
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(initialized);
        Parser.ParsedGrammar workingGrammar = new Parser.ParsedGrammar(
                initialized.grammarRules(), initialized.sequence(), metadata
        );

        Map<Integer, List<Integer>> rules = workingGrammar.grammarRules();
        List<Integer> sequence = workingGrammar.sequence();

        // Decompress before
        String before = Decompressor.decompress(workingGrammar);
        System.out.println("\n=== ⏮ Decompressed Output BEFORE Recompression ===");
        System.out.println(before);

        // Compute bigram frequencies and select best
        System.out.println("\n=== 🔍 Computing Bigram Frequencies ===");
        Map<Pair<Integer, Integer>, Integer> frequencies =
                computeBigramFrequencies(originalGrammar, workingGrammar);

        Pair<Integer, Integer> bigram = getMostFrequentBigram(frequencies);
        if (bigram == null) {
            System.out.println("❌ No compressible bigrams found.");
            return;
        }

        int c1 = bigram.first;
        int c2 = bigram.second;

        int maxRuleId = rules.keySet().stream().max(Integer::compareTo).orElse(255);
        int newRuleId = maxRuleId + 1;

        System.out.printf("✅ Most frequent bigram: (%s, %s) → R%d [%d occurrences]%n",
                formatSymbol(c1), formatSymbol(c2), newRuleId, frequencies.get(bigram));

        // Show grammar before uncrossing
        System.out.println("\n--- 📄 Grammar BEFORE Uncrossing ---");
        Parser.printGrammar(workingGrammar);

        // Uncrossing: popIn and popOut
        System.out.println("\n=== 🔧 Applying PopInLet ===");
        popInlet(c1, c2, rules, metadata);
        System.out.println("=== 🔧 Applying PopOutLet ===");
        popOutLet(c1, c2, rules, metadata);

        System.out.println("\n--- 📄 Grammar AFTER Uncrossing ---");
        Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));

        // Replace bigram with new rule
        System.out.println("\n=== ✏ Replacing Bigram with New Rule ===");
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            List<Integer> updated = new ArrayList<>();

            for (int i = 0; i < rhs.size(); ) {
                if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                    updated.add(newRuleId);
                    System.out.printf("📌 Rule R%d: replacing (%s, %s) → R%d%n",
                            ruleId, formatSymbol(c1), formatSymbol(c2), newRuleId);
                    i += 2;
                } else {
                    updated.add(rhs.get(i));
                    i++;
                }
            }

            entry.setValue(updated);
        }

        rules.put(newRuleId, List.of(c1, c2));
        metadata = RuleMetadata.computeAll(new Parser.ParsedGrammar(rules, sequence, metadata));
        workingGrammar = new Parser.ParsedGrammar(rules, sequence, metadata);

        RuleMetadata newMeta = metadata.get(newRuleId);
        System.out.printf("🧠 Metadata for R%d → vocc=%d, length=%d, left=%s, right=%s, isSB=%b, runL=%d, runR=%d%n",
                newRuleId, newMeta.getVocc(), newMeta.getLength(),
                formatSymbol(newMeta.getLeftmostTerminal()), formatSymbol(newMeta.getRightmostTerminal()),
                newMeta.isSingleBlock(), newMeta.getLeftRunLength(), newMeta.getRightRunLength());

        // Decompress after
        String after = Decompressor.decompress(workingGrammar);
        System.out.println("\n=== ⏭ Decompressed Output AFTER Recompression ===");
        System.out.println(after);

        // Check roundtrip
        System.out.println("\n=== ✅ Roundtrip Check ===");
        if (before.equals(after)) {
            System.out.println("✔ Grammar still produces the same output (roundtrip OK).");
        } else {
            System.err.println("❌ Output changed after recompression!");
            int mismatch = -1;
            for (int i = 0; i < Math.min(before.length(), after.length()); i++) {
                if (before.charAt(i) != after.charAt(i)) {
                    mismatch = i;
                    break;
                }
            }
            if (mismatch >= 0) {
                System.out.printf("🔍 First mismatch at index %d: '%c' vs '%c'%n",
                        mismatch, before.charAt(mismatch), after.charAt(mismatch));
            }
        }

        System.out.println("\n=== 📦 Final Grammar After This Recompression ===");
        Parser.printGrammar(workingGrammar);
    }
    public static void recompressTwiceVerbose(Parser.ParsedGrammar originalGrammar) {
        // Step 1: Initialize grammar
        Parser.ParsedGrammar initialized = initializeWithSentinelsAndRootRule(originalGrammar);
        Map<Integer, List<Integer>> rules = initialized.grammarRules();
        List<Integer> sequence = initialized.sequence();
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(initialized);

        Parser.ParsedGrammar workingGrammar = new Parser.ParsedGrammar(rules, sequence, metadata);

        // Track already-compressed bigrams → ruleID
        Map<Pair<Integer, Integer>, Integer> seenBigrams = new HashMap<>();
        int nextFreeRuleId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        for (int pass = 1; pass <= 2; pass++) {
            System.out.printf("\n\n=== 🔁 Recompression Pass %d ===\n", pass);

            String before = Decompressor.decompress(workingGrammar);
            System.out.printf("\n⏮ Decompressed Output BEFORE Pass %d:\n%s\n", pass, before);

            // Compute bigram frequencies
            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(workingGrammar, workingGrammar);

            System.out.println("\n--- 📊 Bigram Frequencies ---");
            frequencies.entrySet().stream()
                    .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
                    .forEach(entry -> {
                        Pair<Integer, Integer> bigram = entry.getKey();
                        int freq = entry.getValue();
                        System.out.printf("(%s, %s) → %d%n",
                                formatSymbol(bigram.first), formatSymbol(bigram.second), freq);
                    });

            // Select most frequent unseen bigram
            Pair<Integer, Integer> bigram = frequencies.entrySet().stream()
                    .filter(entry -> !seenBigrams.containsKey(entry.getKey()))
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (bigram == null) {
                System.out.println("❌ No new compressible bigrams found.");
                break;
            }

            int c1 = bigram.first;
            int c2 = bigram.second;

            // Use existing rule ID or assign new one
            int newRuleId = seenBigrams.getOrDefault(bigram, nextFreeRuleId++);
            seenBigrams.put(bigram, newRuleId);

            System.out.printf("✅ Most frequent bigram: (%s, %s) → R%d [%d occurrences]\n",
                    formatSymbol(c1), formatSymbol(c2), newRuleId, frequencies.get(bigram));

            System.out.printf("\n--- 📄 Grammar BEFORE Uncrossing (Pass %d) ---\n", pass);
            Parser.printGrammar(workingGrammar);

            // Uncross
            System.out.println("\n=== 🔧 Applying PopInLet ===");
            popInlet(c1, c2, rules, metadata);
            System.out.println("=== 🔧 Applying PopOutLet ===");
            popOutLet(c1, c2, rules, metadata);

            System.out.printf("\n--- 📄 Grammar AFTER Uncrossing (Pass %d) ---\n", pass);
            Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));

            // Replace bigram with ruleID
            System.out.printf("\n=== ✏ Replacing Bigram (%s, %s) with R%d ===\n",
                    formatSymbol(c1), formatSymbol(c2), newRuleId);
            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
                int ruleId = entry.getKey();
                List<Integer> rhs = entry.getValue();
                List<Integer> updated = new ArrayList<>();

                for (int i = 0; i < rhs.size(); ) {
                    if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                        updated.add(newRuleId);
                        System.out.printf("📌 Rule R%d: replacing (%s, %s) → R%d\n",
                                ruleId, formatSymbol(c1), formatSymbol(c2), newRuleId);
                        i += 2;
                    } else {
                        updated.add(rhs.get(i));
                        i++;
                    }
                }

                entry.setValue(updated);
            }

            // Only add rule if it's newly assigned
            rules.putIfAbsent(newRuleId, List.of(c1, c2));

            // Refresh grammar
            metadata = RuleMetadata.computeAll(new Parser.ParsedGrammar(rules, sequence, metadata));
            workingGrammar = new Parser.ParsedGrammar(rules, sequence, metadata);

            // Print metadata
            RuleMetadata newMeta = metadata.get(newRuleId);
            System.out.printf("🧠 Metadata for R%d → vocc=%d, length=%d, left=%s, right=%s, isSB=%b, runL=%d, runR=%d\n",
                    newRuleId, newMeta.getVocc(), newMeta.getLength(),
                    formatSymbol(newMeta.getLeftmostTerminal()), formatSymbol(newMeta.getRightmostTerminal()),
                    newMeta.isSingleBlock(), newMeta.getLeftRunLength(), newMeta.getRightRunLength());

            // Decompress and check roundtrip
            String after = Decompressor.decompress(workingGrammar);
            System.out.printf("\n⏭ Decompressed Output AFTER Pass %d:\n%s\n", pass, after);

            System.out.printf("\n=== ✅ Roundtrip Check for Pass %d ===\n", pass);
            if (before.equals(after)) {
                System.out.println("✔ Grammar still produces the same output (roundtrip OK).");
            } else {
                System.err.println("❌ Output changed after recompression!");
                for (int i = 0; i < Math.min(before.length(), after.length()); i++) {
                    if (before.charAt(i) != after.charAt(i)) {
                        System.out.printf("🔍 Mismatch at %d: '%c' vs '%c'\n", i, before.charAt(i), after.charAt(i));
                        break;
                    }
                }
            }

            System.out.printf("\n📦 Final Grammar After Pass %d:\n", pass);
            Parser.printGrammar(workingGrammar);
        }
    }


    public static void recompressUntilDoneVerbose(Parser.ParsedGrammar originalGrammar) {
        // Step 1: Initialize with sentinels + binary rules
        Parser.ParsedGrammar working = initializeWithSentinelsAndRootRule(originalGrammar);

        // Step 2: Compute metadata
        Map<Integer, RuleMetadata> metadata = RuleMetadata.computeAll(working);
        List<Integer> sequence = working.sequence();
        Map<Integer, List<Integer>> rules = working.grammarRules();

        int round = 1;
        int nextRuleId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        while (true) {
            System.out.printf("\n\n======= 🔄 ROUND %d =======\n", round);

            // Recompute metadata for this round
            metadata = RuleMetadata.computeAll(new Parser.ParsedGrammar(rules, sequence, metadata));
            working = new Parser.ParsedGrammar(rules, sequence, metadata);

            // Decompress before
            String before = Decompressor.decompress(working);
            System.out.println("\n⏮ Decompressed BEFORE:");
            System.out.println(before);

            // Find best bigram
            Map<Pair<Integer, Integer>, Integer> frequencies =
                    computeBigramFrequencies(originalGrammar, working);
            System.out.println("\n--- 📊 Bigram Frequencies ---");
            frequencies.entrySet().stream()
                    .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue())) // descending order
                    .forEach(entry -> {
                        Pair<Integer, Integer> bigram = entry.getKey();
                        int freq = entry.getValue();
                        System.out.printf("(%s, %s) → %d%n",
                                formatSymbol(bigram.first), formatSymbol(bigram.second), freq);
                    });

            Pair<Integer, Integer> bigram = getMostFrequentBigram(frequencies);
            if (bigram == null || frequencies.getOrDefault(bigram, 0) <= 1) {
                System.out.println("\n✅ No compressible bigrams with frequency > 1. Stopping.");
                break;
            }

            int c1 = bigram.first;
            int c2 = bigram.second;
            int newRuleId = nextRuleId++;

            System.out.printf("🔍 Most frequent bigram: (%s, %s) → R%d [%d occurrences]%n",
                    formatSymbol(c1), formatSymbol(c2), newRuleId, frequencies.get(bigram));

            System.out.println("\n📄 Grammar BEFORE Uncrossing:");
            Parser.printGrammar(working);

            // Uncross the bigram
            System.out.println("\n🔧 Applying PopInLet");
            popInlet(c1, c2, rules, metadata);
            System.out.println("🔧 Applying PopOutLet");
            popOutLet(c1, c2, rules, metadata);

            System.out.println("\n📄 Grammar AFTER Uncrossing:");
            Parser.printGrammar(new Parser.ParsedGrammar(rules, sequence, metadata));

            // Replace (c1, c2) with Rnew
            int replacements = 0;
            for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
                int ruleId = entry.getKey();
                List<Integer> rhs = entry.getValue();
                List<Integer> updated = new ArrayList<>();

                for (int i = 0; i < rhs.size(); ) {
                    if (i + 1 < rhs.size() && rhs.get(i) == c1 && rhs.get(i + 1) == c2) {
                        updated.add(newRuleId);
                        replacements++;
                        System.out.printf("📌 Rule R%d: replacing (%s,%s) → R%d%n",
                                ruleId, formatSymbol(c1), formatSymbol(c2), newRuleId);
                        i += 2;
                    } else {
                        updated.add(rhs.get(i));
                        i++;
                    }
                }

                entry.setValue(updated);
            }

            rules.put(newRuleId, List.of(c1, c2));
            metadata = RuleMetadata.computeAll(new Parser.ParsedGrammar(rules, sequence, metadata));
            RuleMetadata newMeta = metadata.get(newRuleId);

            System.out.printf("🧠 Metadata for R%d → vocc=%d, length=%d, left=%s, right=%s, isSB=%b, runL=%d, runR=%d%n",
                    newRuleId, newMeta.getVocc(), newMeta.getLength(),
                    formatSymbol(newMeta.getLeftmostTerminal()), formatSymbol(newMeta.getRightmostTerminal()),
                    newMeta.isSingleBlock(), newMeta.getLeftRunLength(), newMeta.getRightRunLength());

            // Decompress after
            String after = Decompressor.decompress(new Parser.ParsedGrammar(rules, sequence, metadata));
            System.out.println("\n⏭ Decompressed AFTER:");
            System.out.println(after);

            if (before.equals(after)) {
                System.out.println("✅ Roundtrip OK (output unchanged)");
            } else {
                System.err.println("❌ Output changed!");
            }

            round++;
        }

        System.out.println("\n🎉 Full recompression completed.");
    }

    public static Map<Pair<Integer, Integer>, Integer> computeBigramFrequencies(
            Parser.ParsedGrammar original,
            Parser.ParsedGrammar initializedWithMetadata
    ) {
        // Step 1: Compute both frequency types
        Map<Pair<Integer, Integer>, Integer> repeatingFreqs =
                computeRepeatingFrequencies(initializedWithMetadata);

        Map<Pair<Integer, Integer>, Integer> nonRepeatingFreqs =
                computeNonRepeatingFrequencies(initializedWithMetadata);

        // Step 2: Merge them
        Map<Pair<Integer, Integer>, Integer> merged = new HashMap<>(nonRepeatingFreqs);
        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : repeatingFreqs.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        return merged;
    }


    public static Map<Pair<Integer, Integer>, Integer> computeNonRepeatingFrequencies(Parser.ParsedGrammar grammar) {
        Map<Pair<Integer, Integer>, Integer> bigramFreqs = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();
        if (grammar.sequence().size() != 1 || grammar.sequence().get(0) < 256) {
            throw new IllegalStateException("Grammar sequence must be a single root nonterminal (use initializeWithSentinelsAndRootRule)");
        }

        System.out.println("=== Starting Non-Repeating Bigram Frequency Computation ===");

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> rhs = entry.getValue();
            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null || rhs.isEmpty()) {
                System.out.printf("Skipping rule R%d: no metadata or empty RHS%n", ruleId);
                continue;
            }

            System.out.printf("\n--- Processing Rule R%d ---\n", ruleId);
            System.out.println("RHS: " + rhs);
            int vocc = meta.getVocc();
            System.out.println("Virtual Occurrence Count (vocc): " + vocc);
            List<Integer> window = new ArrayList<>();

            // === 1. Add rightmost terminal of first symbol
            int firstSymbol = rhs.get(0);
            if (firstSymbol >= 256) {
                RuleMetadata firstMeta = metadata.getOrDefault(firstSymbol, dummyMeta());
                int rightmost = firstMeta.getRightmostTerminal();
                System.out.printf("Rightmost terminal of first symbol R%d: %s%n", firstSymbol,
                        rightmost >= 0 ? rightmost : "None");
                if (rightmost >= 0) {
                    window.add(rightmost);
                }
            }
            else window.add(firstSymbol);

            // === 2. Add middle symbols
            if (rhs.size() > 2) {
                System.out.print("Middle symbols (w(X)): ");
                for (int i = 1; i < rhs.size() - 1; i++) {
                    int sym = rhs.get(i);
                    window.add(sym);
                    System.out.print(sym + " ");
                }
                System.out.println();
            }

            // === 3. Add leftmost terminal of last symbol
            int lastSymbol = rhs.get(rhs.size() - 1);
            if (lastSymbol >= 256) {
                RuleMetadata lastMeta = metadata.getOrDefault(lastSymbol, dummyMeta());
                int leftmost = lastMeta.getLeftmostTerminal();
                System.out.printf("Leftmost terminal of last symbol R%d: %s%n", lastSymbol,
                        leftmost >= 0 ? leftmost : "None");
                if (leftmost >= 0) {
                    window.add(leftmost);
                }
            }
            else window.add(lastSymbol);

            // === 4. Count terminal-terminal bigrams in the window
            System.out.println("Built window: " + window);
            for (int i = 0; i < window.size()-1; i++) {
                int a = window.get(i);
                int b = window.get(i + 1);
                if (a < 256 && b < 256 && a != b) {
                    Pair<Integer, Integer> bigram = Pair.of(a, b);
                    bigramFreqs.merge(bigram, vocc, Integer::sum);
                    System.out.printf("Added bigram (%c,%c) with count += %d%n", a, b, vocc);
                } else {
                    if (a >= 256 || b >= 256) {
                        System.out.printf("Skipping non-terminal bigram (%d,%d)%n", a, b);
                    } else if (a == b) {
                        System.out.printf("Skipping repeating bigram (%c,%c)%n", a, b);
                    }
                }
            }
        }

        System.out.println("\n=== Completed Non-Repeating Bigram Frequency Computation ===");
        return bigramFreqs;
    }

    public static Map<Pair<Integer, Integer>, Integer> computeRepeatingFrequencies(Parser.ParsedGrammar grammar) {
        Map<Pair<Integer, Integer>, Integer> freqMap = new HashMap<>();
        Map<Integer, List<Integer>> rules = grammar.grammarRules();
        Map<Integer, RuleMetadata> metadata = grammar.metadata();

        for (int ruleId : rules.keySet()) {
            RuleMetadata meta = metadata.get(ruleId);
            if (meta == null) continue;

            int vocc = meta.getVocc();
            int len = meta.getLength();
            if (len < 2) continue;

            // Step 1: Extract ρ(X'), w(X), λ(X)
            List<Integer> rhs = rules.get(ruleId);
            if (rhs == null || rhs.isEmpty()) continue;

            List<Integer> context = new ArrayList<>();

            // Right run of the first symbol
            int first = rhs.get(0);
            if (first < 256) context.add(first);
            else {
                int rlen = metadata.getOrDefault(first, dummyMeta()).getRightRunLength();
                int term = metadata.getOrDefault(first, dummyMeta()).getRightmostTerminal();
                for (int i = 0; i < rlen; i++) context.add(term);
            }

            // Middle symbols
            for (int i = 1; i < rhs.size() - 1; i++) {
                int sym = rhs.get(i);
                if (sym < 256) context.add(sym);
                else {
                    RuleMetadata subMeta = metadata.getOrDefault(sym, dummyMeta());
                    int subLen = subMeta.getLength();
                    int subTerm = subMeta.getLeftmostTerminal();
                    for (int j = 0; j < subLen; j++) context.add(subTerm); // safe assumption
                }
            }

            // Left run of last symbol
            int last = rhs.get(rhs.size() - 1);
            if (last < 256) context.add(last);
            else {
                int llen = metadata.getOrDefault(last, dummyMeta()).getLeftRunLength();
                int term = metadata.getOrDefault(last, dummyMeta()).getLeftmostTerminal();
                for (int i = 0; i < llen; i++) context.add(term);
            }

            // Step 2: Scan for c^d blocks (d ≥ 2)
            for (int i = 0; i < context.size(); ) {
                int c = context.get(i);
                int j = i + 1;
                while (j < context.size() && context.get(j) == c) j++;

                int d = j - i;
                if (d >= 2) {
                    // Step 3: Skip if block is prefix/suffix of valSh(X)
                    boolean isPrefix = (i == 0 && meta.getLeftRunLength() == d);
                    boolean isSuffix = (j == context.size() && meta.getRightRunLength() == d);
                    if (!isPrefix && !isSuffix) {
                        freqMap.merge(Pair.of(c, c), (d / 2) * vocc, Integer::sum);
                    }
                }
                i = j;
            }
        }

        return freqMap;
    }


    public static Parser.ParsedGrammar initializeWithSentinelsAndRootRule(Parser.ParsedGrammar original) {
        Map<Integer, List<Integer>> oldRules = original.grammarRules();
        List<Integer> originalSeq = original.sequence();

        if (originalSeq == null || originalSeq.isEmpty()) {
            throw new IllegalArgumentException("Original sequence is empty.");
        }

        Map<Integer, List<Integer>> newRules = new HashMap<>(oldRules);

        // Add sentinels: '#' = 35, '$' = 36
        List<Integer> extended = new ArrayList<>();
        extended.add(35); // #
        extended.addAll(originalSeq);
        extended.add(36); // $

        // Start assigning new rule IDs
        int maxRuleId = oldRules.keySet().stream().max(Integer::compareTo).orElse(255);
        int nextRuleId = maxRuleId + 1;

        // Convert extended sequence to binary rules
        int current = extended.get(0);
        for (int i = 1; i < extended.size(); i++) {
            int next = extended.get(i);
            List<Integer> rhs = List.of(current, next);
            newRules.put(nextRuleId, rhs);
            current = nextRuleId;
            nextRuleId++;
        }

        int rootRule = current;
        List<Integer> newSeq = List.of(rootRule);

        return new Parser.ParsedGrammar(newRules, newSeq, null); // metadata will be recomputed later
    }
    public static Pair<Integer, Integer> getMostOccurringBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
        if (frequencies == null || frequencies.isEmpty()) {
            return null;
        }

        Pair<Integer, Integer> maxBigram = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                maxBigram = entry.getKey();
            }
        }

        return maxBigram;
    }
    public static void popInlet(int c1, int c2, Map<Integer, List<Integer>> rules, Map<Integer, RuleMetadata> metadata) {

        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            List<Integer> rhs = entry.getValue();
            List<Integer> updated = new ArrayList<>();

            for (int i = 0; i < rhs.size(); i++) {
                int sym = rhs.get(i);

                if (sym >= 256) { // It's a nonterminal (variable)
                    RuleMetadata meta = metadata.get(sym);
                    if (meta == null) {
                        updated.add(sym);
                        continue;
                    }

                    // Push c2 to the left if sym is not the first symbol and starts with c2
                    if (i > 0 && meta.getLeftmostTerminal() == c2) {
                        updated.add(c2);
                    }

                    updated.add(sym);

                    // Push c1 to the right if sym is not the last symbol and ends with c1
                    if (i < rhs.size() - 1 && meta.getRightmostTerminal() == c1) {
                        updated.add(c1);
                    }
                } else {
                    // It's a terminal, just add it
                    updated.add(sym);
                }
            }

            entry.setValue(updated);
        }
    }

    public static void popOutLet(int c1, int c2,
                                 Map<Integer, List<Integer>> rules,
                                 Map<Integer, RuleMetadata> metadata) {
        Set<Integer> nullRules = new HashSet<>();

        // First pass: remove c2 from head and c1 from tail
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            List<Integer> body = new ArrayList<>(entry.getValue());

            // Remove c2 from the start
            if (!body.isEmpty() && body.get(0) == c2) {
                body.remove(0);
            }

            // Remove c1 from the end
            if (!body.isEmpty() && body.get(body.size() - 1) == c1) {
                body.remove(body.size() - 1);
            }

            if (body.isEmpty()) {
                nullRules.add(ruleId);
            } else {
                rules.put(ruleId, body);
            }
        }

        // Second pass: remove references to null rules in all remaining rules
        for (Map.Entry<Integer, List<Integer>> entry : rules.entrySet()) {
            int ruleId = entry.getKey();
            if (nullRules.contains(ruleId)) continue;

            List<Integer> original = entry.getValue();
            List<Integer> filtered = new ArrayList<>();
            for (int sym : original) {
                if (!nullRules.contains(sym)) {
                    filtered.add(sym);
                }
            }
            rules.put(ruleId, filtered);
        }

        // Third pass: remove null rules from the grammar
        for (int nullRule : nullRules) {
            rules.remove(nullRule);
        }
    }
    public static Pair<Integer, Integer> getMostFrequentBigram(Map<Pair<Integer, Integer>, Integer> frequencies) {
        Pair<Integer, Integer> mostFrequent = null;
        int maxCount = -1;

        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : frequencies.entrySet()) {
            int count = entry.getValue();
            if (count > maxCount) {
                maxCount = count;
                mostFrequent = entry.getKey();
            }
        }

        return mostFrequent;
    }


    private static RuleMetadata dummyMeta() {
        return new RuleMetadata(0, 0, -1, -1, false, 0, 0);
    }


    private static String printable(int symbol) {
        return (symbol < 256) ? "'" + (char) symbol + "'" : "R" + symbol;
    }


}
