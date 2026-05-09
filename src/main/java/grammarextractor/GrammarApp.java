package grammarextractor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Multi-screen GUI application for the RePair recompression algorithm.
 *
 * Screen flow: File Picker → Mode Picker → Animation Mode (RecompressionViewer)
 *                                        → Technical Mode (config → run → report)
 *
 * Launch: java -jar grammarextractor.jar 22    OR    choose option 22 in the interactive menu.
 */
public class GrammarApp extends JFrame {

    // ── Colour palette (same dark theme as RecompressionViewer) ───────────────
    static final Color C_BG      = new Color(0x14, 0x14, 0x26);
    static final Color C_PANEL   = new Color(0x1C, 0x1C, 0x34);
    static final Color C_SURF    = new Color(0x22, 0x22, 0x3E);
    static final Color C_BORDER  = new Color(0x38, 0x38, 0x6A);
    static final Color C_TEXT    = new Color(0xE6, 0xE6, 0xF2);
    static final Color C_DIM     = new Color(0x72, 0x72, 0x90);
    static final Color C_SEL     = new Color(0xFF, 0xC0, 0x20);
    static final Color C_ACCENT  = new Color(0xE9, 0x40, 0x58);
    static final Color C_GREEN   = new Color(0x28, 0xFF, 0x88);
    static final Color C_BLUE    = new Color(0x14, 0x60, 0xA8);
    static final Color[] CHART   = {
        new Color(0x14, 0x80, 0xD0), new Color(0xE9, 0x40, 0x58),
        new Color(0x28, 0xCC, 0x70), new Color(0xFF, 0xC0, 0x20),
        new Color(0xAA, 0x44, 0xEE), new Color(0x00, 0xCC, 0xCC)
    };

    // ── Data records ──────────────────────────────────────────────────────────
    record PassStats(int pass, int grammarSize, int ruleCount, long timeNs,
                     String bigramLabel, int bigramFreq, int saved) {}

    record TechnicalReport(
        String   inputText,  int inputLength,
        Map<Character, Integer> charFreqs, double entropy, int uniqueChars,
        List<PassStats> compPasses, int initialSize, int compressedSize, long compTimeNs,
        int extractFrom, int extractTo, int extractLen, int extractInitSize,
        List<PassStats> recompPasses, int recompFinalSize,
        Map<Pair<Integer,Integer>,Integer> bigramsInitial,
        Map<Pair<Integer,Integer>,Integer> bigrams_final,   // named to avoid clash with accessor
        long totalTimeNs
    ) {}

    // ── Application state ─────────────────────────────────────────────────────
    private Path selectedFile = null;
    private Parser.ParsedGrammar loadedGrammar = null;

    private final CardLayout cards = new CardLayout();
    private final JPanel     deck  = new JPanel(cards);

    // Technical-screen component refs (set during build)
    private JSpinner  spN, spPasses, spFrom, spTo;
    private JTextField tfAlphabet;
    private JPanel    reportHolder;
    private JSplitPane techSplit;

    // ── Entry points ──────────────────────────────────────────────────────────
    public static void launch() {
        SwingUtilities.invokeLater(() -> new GrammarApp().setVisible(true));
    }

    public GrammarApp() {
        super("RePair Grammar Lab");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setBackground(C_BG);

        deck.setBackground(C_BG);
        deck.add(buildFilePicker(),  "file");
        deck.add(buildModePicker(),  "mode");
        deck.add(buildTechnical(),   "technical");
        add(deck);

        setSize(1250, 820);
        setLocationRelativeTo(null);
        cards.show(deck, "file");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — File Picker
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildFilePicker() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(header("  RePair Grammar Lab   ·   Choose a file"), BorderLayout.NORTH);

        // ── Left: file list ──────────────────────────────────────────────────
        DefaultListModel<Path> model = new DefaultListModel<>();
        JList<Path> list = new JList<>(model);
        list.setBackground(C_PANEL); list.setForeground(C_TEXT);
        list.setFont(new Font("SansSerif", Font.PLAIN, 12));
        list.setSelectionBackground(C_BLUE); list.setSelectionForeground(C_TEXT);
        list.setFixedCellHeight(48);
        list.setCellRenderer(new FileCell());

        try {
            Files.list(Path.of("."))
                 .filter(p -> p.getFileName().toString().endsWith(".txt"))
                 .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                 .forEach(model::addElement);
        } catch (IOException ignored) {}

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(new EmptyBorder(6, 6, 6, 0));
        listScroll.setBackground(C_PANEL); listScroll.getViewport().setBackground(C_PANEL);

        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(C_PANEL);
        left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));
        left.add(sectionHdr("  TEXT FILES IN WORKING DIRECTORY"), BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);

        // ── Right: preview ───────────────────────────────────────────────────
        JTextArea preview = new JTextArea("← Select a file to preview");
        preview.setEditable(false); preview.setLineWrap(true); preview.setWrapStyleWord(true);
        preview.setBackground(C_SURF); preview.setForeground(C_TEXT);
        preview.setFont(new Font("Monospaced", Font.PLAIN, 11));
        preview.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel meta = new JLabel(" ");
        meta.setForeground(C_DIM); meta.setFont(new Font("SansSerif", Font.PLAIN, 11));
        meta.setBorder(new EmptyBorder(4, 12, 4, 0));

        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(new EmptyBorder(6, 6, 0, 6));
        previewScroll.getViewport().setBackground(C_SURF);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(C_BG);
        right.add(sectionHdr("  FILE PREVIEW"), BorderLayout.NORTH);
        right.add(previewScroll, BorderLayout.CENTER);
        right.add(meta, BorderLayout.SOUTH);

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || list.getSelectedValue() == null) return;
            Path p = list.getSelectedValue();
            selectedFile = p;
            try {
                long sz = Files.size(p);
                String txt = Files.readString(p);
                preview.setText(txt.length() > 1200 ? txt.substring(0, 1200) + "\n…(truncated)" : txt);
                preview.setCaretPosition(0);
                boolean isGrammar = txt.startsWith("R") || txt.contains("\nSEQ:");
                long lines = txt.lines().count();
                meta.setText("  " + fmtBytes(sz) + "  ·  " + lines + " lines  ·  "
                    + (isGrammar ? "Grammar file" : "Plain text"));
            } catch (IOException ex) {
                preview.setText("Cannot read file: " + ex.getMessage());
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(330); split.setDividerSize(4); split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        // ── Bottom bar ───────────────────────────────────────────────────────
        JPanel bot = navBar();
        JButton next = accentBtn("Continue  ▶");
        next.addActionListener(e -> {
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(this, "Select a file first.", "No file", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                String txt = Files.readString(selectedFile);
                if (txt.startsWith("R") || txt.contains("\nSEQ:")) {
                    loadedGrammar = Parser.parseFile(selectedFile);
                } else {
                    // Plain text: build flat terminal grammar
                    List<Integer> seq = new ArrayList<>();
                    for (char c : txt.toCharArray()) seq.add((int) c);
                    Parser.ParsedGrammar pg = new Parser.ParsedGrammar(
                        new HashMap<>(), seq, Collections.emptyMap());
                    loadedGrammar = new Parser.ParsedGrammar(
                        new HashMap<>(), seq,
                        RuleMetadata.computeAll(pg, Collections.emptySet()));
                }
                cards.show(deck, "mode");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        bot.add(Box.createHorizontalGlue());
        bot.add(next);
        bot.add(Box.createHorizontalStrut(12));
        root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private class FileCell extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> l, Object v, int i, boolean sel, boolean foc) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(true);
            row.setBackground(sel ? C_BLUE : i % 2 == 0 ? C_PANEL : C_SURF);
            row.setBorder(new EmptyBorder(7, 10, 7, 10));

            Path p = (Path) v;
            String name = p.getFileName().toString();
            long sz = 0; boolean gram = false;
            try {
                sz = Files.size(p);
                String head = Files.readString(p).substring(0, Math.min(40, (int) sz));
                gram = head.startsWith("R") || head.contains("SEQ:");
            } catch (IOException ignored) {}

            JLabel icon = new JLabel(gram ? "⚙" : "📄");
            icon.setFont(new Font("SansSerif", Font.PLAIN, 20));
            icon.setForeground(gram ? C_GREEN : C_SEL);
            row.add(icon, BorderLayout.WEST);

            JPanel info = new JPanel(); info.setOpaque(false);
            info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
            JLabel nl = new JLabel(name);
            nl.setFont(new Font("SansSerif", Font.BOLD, 12));
            nl.setForeground(sel ? Color.WHITE : C_TEXT);
            JLabel sl = new JLabel(fmtBytes(sz) + "  ·  " + (gram ? "grammar" : "text"));
            sl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            sl.setForeground(sel ? new Color(0xCC, 0xCC, 0xFF) : C_DIM);
            info.add(nl); info.add(sl);
            row.add(info, BorderLayout.CENTER);
            return row;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — Mode Picker
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildModePicker() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(header("  Choose a mode"), BorderLayout.NORTH);

        JPanel cards2 = new JPanel(new GridLayout(1, 2, 24, 0));
        cards2.setBackground(C_BG);
        cards2.setBorder(new EmptyBorder(48, 64, 48, 64));

        JPanel animCard = modeCard("🎬", "Animation Mode", C_BLUE,
            "Step through the recompression algorithm one\n"
          + "operation at a time. Watch grammar rules update,\n"
          + "bigram bars animate, and size converge live.\n\n"
          + "Best for: understanding the algorithm, demos.");
        animCard.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (loadedGrammar != null)
                    new RecompressionViewer(loadedGrammar, 0).setVisible(true);
            }
        });

        JPanel techCard = modeCard("🔬", "Technical Mode", C_ACCENT,
            "Generate random text of length N, compress it,\n"
          + "extract a sub-range, recompress the extract.\n"
          + "Get an extensive report with 8 charts and a\n"
          + "full pass-by-pass statistics table.\n\n"
          + "Best for: research, benchmarking, paper stats.");
        techCard.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { cards.show(deck, "technical"); }
        });

        cards2.add(animCard); cards2.add(techCard);
        root.add(cards2, BorderLayout.CENTER);

        JPanel bot = navBar();
        JButton back = plainBtn("◀  Back");
        back.addActionListener(e -> cards.show(deck, "file"));
        bot.add(back); bot.add(Box.createHorizontalGlue());
        root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private JPanel modeCard(String icon, String title, Color accent, String desc) {
        JPanel card = new JPanel() {
            boolean hov;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hov=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hov=false; repaint(); }
            }); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                g.setColor(hov ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28) : C_PANEL);
                g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,16,16);
                g.setColor(hov ? accent : C_BORDER);
                g.setStroke(new BasicStroke(hov ? 2f : 1f));
                g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,16,16);
            }
        };
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(false); card.setBorder(new EmptyBorder(32, 28, 32, 28));

        JLabel il = new JLabel(icon); il.setFont(new Font("SansSerif",Font.PLAIN,48)); il.setAlignmentX(.5f);
        JLabel tl = new JLabel(title); tl.setFont(new Font("SansSerif",Font.BOLD,20)); tl.setForeground(accent); tl.setAlignmentX(.5f);
        JTextArea dl = new JTextArea(desc); dl.setEditable(false); dl.setOpaque(false);
        dl.setWrapStyleWord(true); dl.setLineWrap(true);
        dl.setFont(new Font("SansSerif",Font.PLAIN,12)); dl.setForeground(C_DIM);
        JLabel cta = new JLabel("Click to select  →"); cta.setFont(new Font("SansSerif",Font.BOLD,12)); cta.setForeground(accent); cta.setAlignmentX(.5f);

        card.add(il); card.add(Box.createVerticalStrut(10));
        card.add(tl); card.add(Box.createVerticalStrut(16));
        card.add(dl); card.add(Box.createVerticalGlue());
        card.add(cta);
        return card;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 3 — Technical Mode
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildTechnical() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(header("  Technical Mode  ·  Roundtrip Analysis"), BorderLayout.NORTH);

        // Config form
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(C_PANEL);
        form.setBorder(new EmptyBorder(16, 28, 16, 28));

        spN      = spinner(300,  10, 50_000, 50);
        spPasses = spinner(0,     0,    500,  1);
        spFrom   = spinner(20,    0, 49_999,  1);
        spTo     = spinner(80,    1, 50_000,  1);
        tfAlphabet = new JTextField("abcdefghijklmnopqrstuvwxyz");
        styleField(tfAlphabet);

        form.add(fRow("Text length N:",                 spN));
        form.add(Box.createVerticalStrut(8));
        form.add(fRow("Max compression passes (0=∞):", spPasses));
        form.add(Box.createVerticalStrut(8));
        form.add(fRow("Extract start (inclusive):",    spFrom));
        form.add(Box.createVerticalStrut(8));
        form.add(fRow("Extract end (exclusive):",      spTo));
        form.add(Box.createVerticalStrut(8));
        form.add(fRow("Alphabet:",                     tfAlphabet));

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBackground(C_PANEL); formScroll.getViewport().setBackground(C_PANEL);
        formScroll.setBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER));
        formScroll.setMinimumSize(new Dimension(0, 190));
        formScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, 210));

        // Report holder
        reportHolder = new JPanel(new BorderLayout());
        reportHolder.setBackground(C_BG);
        JLabel hint = new JLabel("  Configure above and click  ▶ Run Analysis  to generate the report.");
        hint.setFont(new Font("SansSerif", Font.ITALIC, 13)); hint.setForeground(C_DIM);
        hint.setBorder(new EmptyBorder(28, 28, 0, 0));
        reportHolder.add(hint, BorderLayout.NORTH);

        techSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, reportHolder);
        techSplit.setDividerLocation(210); techSplit.setResizeWeight(0.0);
        techSplit.setDividerSize(5); techSplit.setBorder(null);
        root.add(techSplit, BorderLayout.CENTER);

        JPanel bot = navBar();
        JButton back = plainBtn("◀  Back");
        back.addActionListener(e -> cards.show(deck, "mode"));
        JButton run = accentBtn("▶  Run Analysis");
        run.addActionListener(e -> runTechnical());
        bot.add(back); bot.add(Box.createHorizontalGlue()); bot.add(run);
        bot.add(Box.createHorizontalStrut(12));
        root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private void runTechnical() {
        int n       = (int) spN.getValue();
        int passes  = (int) spPasses.getValue();
        int from    = (int) spFrom.getValue();
        int to      = (int) spTo.getValue();
        String alpha = tfAlphabet.getText().trim();
        if (alpha.isEmpty()) alpha = "abcdefghijklmnopqrstuvwxyz";

        if (from >= to || to > n) {
            JOptionPane.showMessageDialog(this, "Invalid range: need 0 ≤ from < to ≤ N.",
                "Bad range", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JLabel spinner = new JLabel("  ⏳  Running analysis…");
        spinner.setFont(new Font("SansSerif", Font.ITALIC, 13)); spinner.setForeground(C_SEL);
        spinner.setBorder(new EmptyBorder(28, 28, 0, 0));
        reportHolder.removeAll(); reportHolder.add(spinner, BorderLayout.NORTH);
        reportHolder.revalidate(); reportHolder.repaint();

        final String fa = alpha;
        SwingWorker<TechnicalReport, Void> w = new SwingWorker<>() {
            @Override protected TechnicalReport doInBackground() {
                return pipeline(n, passes, from, to, fa);
            }
            @Override protected void done() {
                try {
                    TechnicalReport r = get();
                    reportHolder.removeAll();
                    reportHolder.add(buildReport(r), BorderLayout.CENTER);
                    reportHolder.revalidate(); reportHolder.repaint();
                    techSplit.setDividerLocation(210);
                } catch (Exception ex) {
                    reportHolder.removeAll();
                    JLabel err = new JLabel("  Error: " + ex.getCause().getMessage());
                    err.setForeground(C_ACCENT); err.setBorder(new EmptyBorder(28, 28, 0, 0));
                    reportHolder.add(err, BorderLayout.NORTH);
                    reportHolder.revalidate(); reportHolder.repaint();
                }
            }
        };
        w.execute();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Pipeline
    // ═════════════════════════════════════════════════════════════════════════

    private TechnicalReport pipeline(int n, int maxPasses, int from, int to, String alpha) {
        long T0 = System.nanoTime();

        // Generate random text
        Random rnd = new Random(42);
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) buf[i] = alpha.charAt(rnd.nextInt(alpha.length()));
        String input = new String(buf);

        // Char frequencies + entropy
        Map<Character, Integer> cf = new TreeMap<>();
        for (char c : buf) cf.merge(c, 1, Integer::sum);
        double entropy = cf.values().stream()
            .mapToDouble(cnt -> { double p = (double)cnt/n; return -p * Math.log(p) / Math.log(2); })
            .sum();

        // Build flat grammar
        List<Integer> flatSeq = new ArrayList<>(n);
        for (char c : buf) flatSeq.add((int) c);
        Parser.ParsedGrammar orig = new Parser.ParsedGrammar(
            new HashMap<>(), flatSeq,
            RuleMetadata.computeAll(
                new Parser.ParsedGrammar(new HashMap<>(), flatSeq, Collections.emptyMap()),
                Collections.emptySet()));

        // Initialize + compress
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(orig);
        Map<Integer,List<Integer>> rules = new LinkedHashMap<>(init.grammar().grammarRules());
        List<Integer> seq  = new ArrayList<>(init.grammar().sequence());
        Set<Integer>  art  = new HashSet<>(init.artificialTerminals());
        Map<Integer,List<Integer>> artR = new LinkedHashMap<>();
        int nextId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        int initSz = rules.values().stream().mapToInt(List::size).sum() + seq.size();

        // Initial bigrams
        Map<Integer,RuleMetadata> m0 = meta(rules, seq, art);
        Map<Pair<Integer,Integer>,Integer> bigramsInit =
            Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules, seq, m0), art, false, null);

        List<PassStats> compPasses = new ArrayList<>();
        int actualMax = maxPasses == 0 ? 999_999 : maxPasses;

        long cT0 = System.nanoTime();
        for (int pass = 1; pass <= actualMax; pass++) {
            long t0 = System.nanoTime();
            Map<Integer,RuleMetadata> m = meta(rules, seq, art);
            Map<Pair<Integer,Integer>,Integer> freqs =
                Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules,seq,m), art, false, null);
            if (freqs.isEmpty()) break;
            Pair<Integer,Integer> bg = Recompressor.getMostFrequentBigram(freqs, art);
            if (bg == null || freqs.getOrDefault(bg, 0) <= 1) break;
            Recompressor.uncrossBigrams(bg.first, bg.second, rules, m, art);
            int nid = nextId++;
            Recompressor.replaceBigramInRules(bg.first, bg.second, nid, rules, art);
            artR.put(nid, List.of(bg.first, bg.second));
            art.add(nid);
            Recompressor.removeRedundantRules(rules, seq);
            int sz  = rules.values().stream().mapToInt(List::size).sum() + seq.size();
            int prv = compPasses.isEmpty() ? initSz : compPasses.get(compPasses.size()-1).grammarSize();
            compPasses.add(new PassStats(pass, sz, rules.size(), System.nanoTime()-t0,
                RecompressionViewer.sym(bg.first)+"+"+ RecompressionViewer.sym(bg.second),
                freqs.get(bg), prv - sz));
        }
        long cT1 = System.nanoTime();
        int compSz = rules.values().stream().mapToInt(List::size).sum() + seq.size();

        // Final bigrams
        Map<Pair<Integer,Integer>,Integer> bigFinal =
            Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules, seq, meta(rules,seq,art)), art, false, null);

        // Build combined grammar for extraction
        Map<Integer,List<Integer>> comb = new LinkedHashMap<>(rules);
        comb.putAll(artR);
        Map<Integer,RuleMetadata> combMeta =
            RuleMetadata.computeAll(new Parser.ParsedGrammar(comb, seq, Collections.emptyMap()), new HashSet<>());
        Parser.ParsedGrammar compGram = new Parser.ParsedGrammar(comb, seq, combMeta);

        // Adjust positions for leading '#' sentinel
        int adjFrom = Math.min(from + 1, Extractor.getUncompressedSize(compGram) - 1);
        int adjTo   = Math.min(to   + 1, Extractor.getUncompressedSize(compGram));

        // Extract
        Parser.ParsedGrammar exGram;
        try {
            exGram = Extractor.extractExcerpt(compGram, adjFrom, adjTo, false);
        } catch (Exception ex) {
            // Fallback: build flat grammar from the decompressed slice
            String slice = Decompressor.decompress(compGram).substring(adjFrom, adjTo);
            List<Integer> sl = new ArrayList<>();
            for (char c : slice.toCharArray()) sl.add((int)c);
            exGram = new Parser.ParsedGrammar(new HashMap<>(), sl,
                RuleMetadata.computeAll(new Parser.ParsedGrammar(new HashMap<>(),sl,Collections.emptyMap()),Collections.emptySet()));
        }
        int exInitSz = Parser.sizeOfGrammar(exGram);

        // Recompress extract
        Recompressor.InitializedGrammar init2 = Recompressor.initializeWithSentinelsAndRootRule(exGram);
        Map<Integer,List<Integer>> r2 = new LinkedHashMap<>(init2.grammar().grammarRules());
        List<Integer> s2 = new ArrayList<>(init2.grammar().sequence());
        Set<Integer>  a2 = new HashSet<>(init2.artificialTerminals());
        Map<Integer,List<Integer>> ar2 = new LinkedHashMap<>();
        int nx2 = r2.keySet().stream().max(Integer::compareTo).orElse(255) + 1;
        List<PassStats> recompPasses = new ArrayList<>();

        for (int pass = 1; pass <= actualMax; pass++) {
            long t0 = System.nanoTime();
            Map<Integer,RuleMetadata> m = meta(r2, s2, a2);
            Map<Pair<Integer,Integer>,Integer> freqs =
                Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(r2,s2,m), a2, false, null);
            if (freqs.isEmpty()) break;
            Pair<Integer,Integer> bg = Recompressor.getMostFrequentBigram(freqs, a2);
            if (bg == null || freqs.getOrDefault(bg, 0) <= 1) break;
            Recompressor.uncrossBigrams(bg.first, bg.second, r2, m, a2);
            int nid = nx2++;
            Recompressor.replaceBigramInRules(bg.first, bg.second, nid, r2, a2);
            ar2.put(nid, List.of(bg.first, bg.second));
            a2.add(nid);
            Recompressor.removeRedundantRules(r2, s2);
            int sz  = r2.values().stream().mapToInt(List::size).sum() + s2.size();
            int prv = recompPasses.isEmpty() ? exInitSz : recompPasses.get(recompPasses.size()-1).grammarSize();
            recompPasses.add(new PassStats(pass, sz, r2.size(), System.nanoTime()-t0,
                RecompressionViewer.sym(bg.first)+"+"+ RecompressionViewer.sym(bg.second),
                freqs.get(bg), prv - sz));
        }
        int recompFinal = r2.values().stream().mapToInt(List::size).sum() + s2.size();

        return new TechnicalReport(
            input.length() > 300 ? input.substring(0, 300) + "…" : input, n,
            cf, entropy, cf.size(),
            compPasses, initSz, compSz, cT1-cT0,
            from, to, to-from, exInitSz,
            recompPasses, recompFinal,
            bigramsInit, bigFinal,
            System.nanoTime()-T0
        );
    }

    private static Map<Integer,RuleMetadata> meta(
            Map<Integer,List<Integer>> rules, List<Integer> seq, Set<Integer> art) {
        return RuleMetadata.computeAll(
            new Parser.ParsedGrammar(rules, seq, Collections.emptyMap()), art);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Report Panel — 8 charts + 2 tables
    // ═════════════════════════════════════════════════════════════════════════

    private JScrollPane buildReport(TechnicalReport r) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(C_BG);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        root.add(rTitle("  Roundtrip Analysis Report"));
        root.add(Box.createVerticalStrut(14));
        root.add(summaryCards(r));
        root.add(Box.createVerticalStrut(16));

        // Row 1: Size convergence  |  Reduction per pass
        root.add(chartRow(
            lineChart("Grammar Size Convergence",
                sizeSeriesData(r), new String[]{"Full compression","Extract recomp"},
                new Color[]{CHART[0], CHART[1]}, "Pass","Symbols"),
            barChart("Size Reduction per Pass",
                reductionData(r), "Pass","Symbols saved", CHART[2])
        ));
        root.add(Box.createVerticalStrut(12));

        // Row 2: Bigrams before  |  Bigrams after
        root.add(chartRow(
            bigramChart("Top Bigrams — Before Compression", r.bigramsInitial, CHART[0]),
            bigramChart("Top Bigrams — After Compression",  r.bigrams_final(),  CHART[1])
        ));
        root.add(Box.createVerticalStrut(12));

        // Row 3: Time per pass  |  Character distribution
        root.add(chartRow(
            barChart("Time per Pass (µs)", timeData(r), "Pass","µs", CHART[3]),
            charDistChart("Character Frequency Distribution", r.charFreqs)
        ));
        root.add(Box.createVerticalStrut(12));

        // Row 4: Rule count  |  Efficiency scatter
        root.add(chartRow(
            lineChart("Rule Count over Passes",
                ruleCountData(r), new String[]{"Rules created"},
                new Color[]{CHART[4]}, "Pass","Rules"),
            efficiencyChart("Pass Efficiency  (size saved vs time)", r)
        ));
        root.add(Box.createVerticalStrut(12));

        // Row 5: Summary table  |  Pass table
        root.add(chartRow(
            summaryTable(r),
            passTable(r)
        ));

        JScrollPane scroll = new JScrollPane(root);
        scroll.setBackground(C_BG); scroll.getViewport().setBackground(C_BG);
        scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(22);
        return scroll;
    }

    // -- Summary cards row -------------------------------------------------------
    private JPanel summaryCards(TechnicalReport r) {
        JPanel p = new JPanel(new GridLayout(1, 6, 8, 0));
        p.setBackground(C_BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
        double ratio = (double) r.initialSize / Math.max(1, r.compressedSize);
        double pct   = 100.0 * (1 - (double) r.compressedSize / r.initialSize);
        p.add(kpiCard("Input Length",    fmt(r.inputLength) + " chars",  CHART[0]));
        p.add(kpiCard("Passes",          "" + r.compPasses.size(),       CHART[2]));
        p.add(kpiCard("Compressed Size", fmt(r.compressedSize) + " syms",CHART[3]));
        p.add(kpiCard("Ratio",           String.format("%.2f×  (%.0f%%↓)", ratio, pct), C_SEL));
        p.add(kpiCard("Entropy",         String.format("%.3f bits/char", r.entropy), CHART[4]));
        p.add(kpiCard("Total Time",      String.format("%.1f ms", r.totalTimeNs/1e6), C_ACCENT));
        return p;
    }

    private JPanel kpiCard(String label, String value, Color accent) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = aa(g0); int w=getWidth(), h=getHeight();
                g.setColor(C_PANEL); g.fillRoundRect(0,0,w-1,h-1,10,10);
                g.setColor(accent);  g.fillRoundRect(0,h-3,w-1,3,3,3);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false);
        p.setBorder(new EmptyBorder(10,12,12,12));
        JLabel vl = new JLabel(value); vl.setFont(new Font("SansSerif",Font.BOLD,15)); vl.setForeground(accent); vl.setAlignmentX(.5f);
        JLabel kl = new JLabel(label); kl.setFont(new Font("SansSerif",Font.PLAIN, 9)); kl.setForeground(C_DIM); kl.setAlignmentX(.5f);
        p.add(Box.createVerticalGlue()); p.add(vl); p.add(Box.createVerticalStrut(3)); p.add(kl); p.add(Box.createVerticalGlue());
        return p;
    }

    // -- Data helpers for charts -------------------------------------------------------

    private double[][][] sizeSeriesData(TechnicalReport r) {
        double[] x0 = new double[r.compPasses.size()+1], y0 = new double[r.compPasses.size()+1];
        x0[0]=0; y0[0]=r.initialSize;
        for (int i=0;i<r.compPasses.size();i++){x0[i+1]=r.compPasses.get(i).pass(); y0[i+1]=r.compPasses.get(i).grammarSize();}
        double[] x1 = new double[r.recompPasses.size()+1], y1 = new double[r.recompPasses.size()+1];
        x1[0]=0; y1[0]=r.extractInitSize;
        for (int i=0;i<r.recompPasses.size();i++){x1[i+1]=r.recompPasses.get(i).pass(); y1[i+1]=r.recompPasses.get(i).grammarSize();}
        return new double[][][]{{x0,y0},{x1,y1}};
    }
    private double[][] reductionData(TechnicalReport r) {
        int n=r.compPasses.size(); double[] xs=new double[n],ys=new double[n];
        for(int i=0;i<n;i++){xs[i]=r.compPasses.get(i).pass(); ys[i]=r.compPasses.get(i).saved();}
        return new double[][]{xs,ys};
    }
    private double[][] timeData(TechnicalReport r) {
        int n=r.compPasses.size(); double[] xs=new double[n],ys=new double[n];
        for(int i=0;i<n;i++){xs[i]=r.compPasses.get(i).pass(); ys[i]=r.compPasses.get(i).timeNs()/1_000.0;}
        return new double[][]{xs,ys};
    }
    private double[][][] ruleCountData(TechnicalReport r) {
        int n=r.compPasses.size()+1; double[] xs=new double[n],ys=new double[n];
        xs[0]=0; ys[0]=0;
        for(int i=0;i<r.compPasses.size();i++){xs[i+1]=r.compPasses.get(i).pass(); ys[i+1]=r.compPasses.get(i).ruleCount();}
        return new double[][][]{{xs,ys}};
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Chart Components
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel lineChart(String title, double[][][] data, String[] names, Color[] colors,
                             String xl, String yl) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight(),ml=58,mr=12,mt=34,mb=36;
                int cw=w-ml-mr, ch=h-mt-mb;
                drawChartBase(g,title,w,h,ml,mr,mt,mb,cw,ch);
                if (data==null||data.length==0) return;
                double xMn=Double.MAX_VALUE,xMx=-Double.MAX_VALUE,yMn=0,yMx=-Double.MAX_VALUE;
                for(double[][] s:data){for(double x:s[0]){xMn=Math.min(xMn,x);xMx=Math.max(xMx,x);}
                                       for(double y:s[1]) yMx=Math.max(yMx,y);}
                if(xMx==xMn) xMx=xMn+1; if(yMx==0) yMx=1;
                drawYGrid(g,ml,mt,cw,ch,yMn,yMx,yl);
                for(int si=0;si<data.length;si++){
                    double[] xs=data[si][0],ys=data[si][1]; if(xs.length<2) continue;
                    g.setColor(colors[si%colors.length]);
                    g.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                    int[] px=new int[xs.length],py=new int[xs.length];
                    for(int i=0;i<xs.length;i++){
                        px[i]=ml+(int)((xs[i]-xMn)/(xMx-xMn)*cw);
                        py[i]=mt+ch-(int)((ys[i]-yMn)/(yMx-yMn)*ch);}
                    for(int i=0;i<xs.length-1;i++) g.drawLine(px[i],py[i],px[i+1],py[i+1]);
                    g.setStroke(new BasicStroke(1f));
                    for(int i=0;i<xs.length;i++) g.fillOval(px[i]-3,py[i]-3,6,6);
                }
                // Legend
                int lx=ml+6,ly=mt+12;
                for(int i=0;i<names.length&&i<data.length;i++){
                    g.setColor(colors[i%colors.length]); g.fillRect(lx,ly-6,14,3);
                    g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(C_DIM);
                    g.drawString(names[i],lx+18,ly);
                    lx+=g.getFontMetrics().stringWidth(names[i])+38;
                }
                // X ticks
                drawXTicks(g,ml,mt,cw,ch,data[0][0],xMn,xMx,xl);
            }
        };
    }

    private JPanel barChart(String title, double[][] data, String xl, String yl, Color col) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight(),ml=58,mr=10,mt=34,mb=36;
                int cw=w-ml-mr, ch=h-mt-mb;
                drawChartBase(g,title,w,h,ml,mr,mt,mb,cw,ch);
                if(data==null||data[1].length==0) return;
                double yMx=Arrays.stream(data[1]).max().orElse(1); if(yMx==0) yMx=1;
                int n=data[1].length, bw=Math.max(4,cw/(n+1)), gap=Math.max(2,(cw-n*bw)/(n+1));
                drawYGrid(g,ml,mt,cw,ch,0,yMx,yl);
                for(int i=0;i<n;i++){
                    int bh=(int)(ch*data[1][i]/yMx);
                    int bx=ml+gap+i*(bw+gap), by=mt+ch-bh;
                    g.setPaint(new GradientPaint(bx,by,RecompressionViewer.blend(col,Color.WHITE,.2f),bx,mt+ch,col));
                    g.fillRoundRect(bx,by,bw,bh,3,3); g.setPaint(null);
                    if(n<=30||i%(Math.max(1,n/8))==0){
                        g.setFont(new Font("SansSerif",Font.PLAIN,8)); g.setColor(C_DIM);
                        String lbl=fmt((long)data[0][i]);
                        g.drawString(lbl,bx+(bw-g.getFontMetrics().stringWidth(lbl))/2,mt+ch+12);}
                }
            }
        };
    }

    private JPanel bigramChart(String title, Map<Pair<Integer,Integer>,Integer> freqs, Color col) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight();
                g.setColor(C_PANEL); g.fillRect(0,0,w,h);
                chartTitle(g,title,w,34);
                if(freqs==null||freqs.isEmpty()){
                    g.setColor(C_DIM); g.setFont(new Font("SansSerif",Font.ITALIC,11));
                    g.drawString("(no bigrams)",w/2-40,h/2); return;}
                List<Map.Entry<Pair<Integer,Integer>,Integer>> sorted=new ArrayList<>(freqs.entrySet());
                sorted.sort((a,b)->b.getValue()-a.getValue());
                int n=Math.min(13,sorted.size()), maxF=sorted.get(0).getValue();
                int ml=64,mr=60,mt=36,mb=4;
                int ch=h-mt-mb, bh=Math.max(7,(ch-n*2)/n);
                for(int i=0;i<n;i++){
                    var e=sorted.get(i); int freq=e.getValue();
                    int bw=maxF>0?(int)((w-ml-mr)*(double)freq/maxF):0;
                    int bx=ml, by=mt+i*(bh+3);
                    g.setPaint(new GradientPaint(bx,by,RecompressionViewer.blend(col,Color.WHITE,.2f),bx+bw,by,col));
                    g.fillRoundRect(bx,by,bw,bh,3,3); g.setPaint(null);
                    g.setFont(new Font("Monospaced",Font.BOLD,9)); g.setColor(C_TEXT);
                    String lbl= RecompressionViewer.sym(e.getKey().first)+ RecompressionViewer.sym(e.getKey().second);
                    g.drawString(lbl,bx-g.getFontMetrics().stringWidth(lbl)-4,by+bh-1);
                    g.setColor(C_DIM); g.drawString("×"+freq,bx+bw+4,by+bh-1);
                }
            }
        };
    }

    private JPanel charDistChart(String title, Map<Character,Integer> cfreqs) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight();
                g.setColor(C_PANEL); g.fillRect(0,0,w,h);
                chartTitle(g,title,w,34);
                if(cfreqs==null||cfreqs.isEmpty()) return;
                List<Map.Entry<Character,Integer>> sorted=new ArrayList<>(cfreqs.entrySet());
                sorted.sort((a,b)->b.getValue()-a.getValue());
                int n=Math.min(20,sorted.size()), maxF=sorted.get(0).getValue();
                int ml=22,mr=52,mt=36,mb=4;
                int ch=h-mt-mb, bh=Math.max(6,(ch-n*2)/n);
                for(int i=0;i<n;i++){
                    var e=sorted.get(i); Color c=CHART[i%CHART.length];
                    int bw=maxF>0?(int)((w-ml-mr-12)*(double)e.getValue()/maxF):0;
                    int bx=ml+14, by=mt+i*(bh+3);
                    g.setColor(c); g.fillRoundRect(bx,by,bw,bh,2,2);
                    g.setFont(new Font("Monospaced",Font.BOLD,9)); g.setColor(C_DIM);
                    g.drawString("'"+e.getKey()+"'",bx-14,by+bh-1);
                    g.setColor(C_TEXT); g.drawString(fmt(e.getValue()),bx+bw+4,by+bh-1);
                }
            }
        };
    }

    private JPanel efficiencyChart(String title, TechnicalReport r) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight(),ml=58,mr=14,mt=34,mb=36;
                int cw=w-ml-mr,ch=h-mt-mb;
                drawChartBase(g,title,w,h,ml,mr,mt,mb,cw,ch);
                List<PassStats> ps=r.compPasses; if(ps.isEmpty()) return;
                double xMx=ps.stream().mapToLong(p->p.timeNs()/1000).max().orElse(1);
                double yMx=ps.stream().mapToInt(PassStats::saved).max().orElse(1);
                if(xMx==0) xMx=1; if(yMx==0) yMx=1;
                // Axis labels
                g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(C_DIM);
                g.drawString("time (µs) →",ml+cw-50,mt+ch+22);
                g.drawString("symbols saved",2,mt-4);
                drawYGrid(g,ml,mt,cw,ch,0,yMx,"saved");
                for(int i=0;i<ps.size();i++){
                    PassStats p=ps.get(i);
                    int px_=ml+(int)((p.timeNs()/1000)/xMx*cw);
                    int py_=mt+ch-(int)((double)p.saved()/yMx*ch);
                    float t=(float)i/Math.max(1,ps.size()-1);
                    Color c=RecompressionViewer.blend(CHART[2],CHART[1],t);
                    g.setColor(c); g.fillOval(px_-4,py_-4,8,8);
                    if(i<8||i==ps.size()-1){
                        g.setFont(new Font("SansSerif",Font.PLAIN,8)); g.setColor(C_DIM);
                        g.drawString("P"+p.pass(),px_+5,py_-2);}
                }
            }
        };
    }

    private JPanel summaryTable(TechnicalReport r) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight();
                g.setColor(C_PANEL); g.fillRect(0,0,w,h);
                chartTitle(g,"Summary Metrics",w,34);
                double ratio=(double)r.initialSize/Math.max(1,r.compressedSize);
                double pct=100*(1-(double)r.compressedSize/r.initialSize);
                double eratio=(double)r.extractLen/Math.max(1,r.extractInitSize);
                double rratio=(double)r.extractInitSize/Math.max(1,r.recompFinalSize);
                String[][] rows={
                    {"Input length",           fmt(r.inputLength)+" chars"},
                    {"Unique chars",           ""+r.uniqueChars+" / "+alpha(r)+" in alphabet"},
                    {"Shannon entropy",        String.format("%.4f bits/char",r.entropy)},
                    {"Theoretical min size",   String.format("%.0f bits  (%.0f bytes)", r.entropy*r.inputLength, r.entropy*r.inputLength/8)},
                    {"Initial grammar size",   fmt(r.initialSize)+" symbols"},
                    {"Compressed grammar size",fmt(r.compressedSize)+" symbols"},
                    {"Compression ratio",      String.format("%.3f×  (%.1f%% smaller)",ratio,pct)},
                    {"Compression passes",     ""+r.compPasses.size()},
                    {"Avg Δsize / pass",       r.compPasses.isEmpty()?"—":fmt((long)r.compPasses.stream().mapToInt(PassStats::saved).average().orElse(0))+" syms"},
                    {"Compression time",       String.format("%.2f ms",r.compTimeNs/1e6)},
                    {"Extract [from,to)",      "["+r.extractFrom+", "+r.extractTo+")  = "+r.extractLen+" chars"},
                    {"Extract grammar size",   fmt(r.extractInitSize)+" symbols"},
                    {"Extract / full ratio",   String.format("%.4f",((double)r.extractInitSize/r.initialSize))},
                    {"Recomp passes (extract)",""+r.recompPasses.size()},
                    {"Extract recomp ratio",   String.format("%.3f×",rratio)},
                };
                Font kf=new Font("SansSerif",Font.PLAIN,10), vf=new Font("Monospaced",Font.BOLD,10);
                int rh=Math.max(13,(h-44)/rows.length);
                for(int i=0;i<rows.length;i++){
                    int ry=44+i*rh;
                    if(i%2==0){g.setColor(new Color(0x20,0x20,0x38)); g.fillRect(4,ry-2,w-8,rh);}
                    g.setFont(kf); g.setColor(C_DIM);  g.drawString(rows[i][0],10,ry+rh-4);
                    g.setFont(vf); g.setColor(C_TEXT); g.drawString(rows[i][1],w/2+4,ry+rh-4);
                }
            }
        };
    }

    private String alpha(TechnicalReport r) { return tfAlphabet != null ? String.valueOf(tfAlphabet.getText().length()) : "?"; }

    private JPanel passTable(TechnicalReport r) {
        return new JPanel() {
            { setBackground(C_PANEL); setPreferredSize(new Dimension(550, 290)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = aa(g0);
                int w=getWidth(),h=getHeight();
                g.setColor(C_PANEL); g.fillRect(0,0,w,h);
                chartTitle(g,"Pass-by-Pass Details",w,34);
                String[] cols={"#","Size","Δ","Time µs","Bigram","Freq"};
                int[] cw={28,55,50,60,110,40};
                g.setColor(new Color(0x20,0x20,0x38)); g.fillRect(4,36,w-8,15);
                g.setFont(new Font("SansSerif",Font.BOLD,9)); g.setColor(C_DIM);
                int cx=8;
                for(int i=0;i<cols.length;i++){g.drawString(cols[i],cx,49); cx+=cw[i];}
                List<PassStats> ps=r.compPasses;
                int rh=13, maxR=(h-54)/rh, start=Math.max(0,ps.size()-maxR);
                g.setFont(new Font("Monospaced",Font.PLAIN,9));
                for(int i=start;i<ps.size()&&i-start<maxR;i++){
                    PassStats p=ps.get(i); int ry=54+(i-start)*rh;
                    if((i-start)%2==0){g.setColor(new Color(0x1A,0x1A,0x30)); g.fillRect(4,ry-1,w-8,rh);}
                    cx=8;
                    String[] vals={""+p.pass(), fmt(p.grammarSize()), "−"+fmt(p.saved()),
                        fmt((long)(p.timeNs()/1000)),
                        p.bigramLabel().length()>14?p.bigramLabel().substring(0,13)+"…":p.bigramLabel(),
                        "×"+fmt(p.bigramFreq())};
                    Color[] vc={C_TEXT,C_TEXT,CHART[2],C_DIM,C_TEXT,C_SEL};
                    for(int j=0;j<vals.length;j++){g.setColor(vc[j]); g.drawString(vals[j],cx,ry+rh-3); cx+=cw[j];}
                }
                if(start>0){g.setColor(C_DIM);g.setFont(new Font("SansSerif",Font.ITALIC,9));
                    g.drawString("(showing last "+maxR+" of "+ps.size()+" passes)",8,53);}
            }
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared chart drawing utilities
    // ═════════════════════════════════════════════════════════════════════════

    private void drawChartBase(Graphics2D g, String title, int w, int h,
                                int ml, int mr, int mt, int mb, int cw, int ch) {
        g.setColor(C_PANEL); g.fillRect(0,0,w,h);
        chartTitle(g,title,w,mt);
        g.setColor(C_BORDER);
        g.drawLine(ml,mt,ml,mt+ch); g.drawLine(ml,mt+ch,ml+cw,mt+ch);
    }

    private void drawYGrid(Graphics2D g, int ml, int mt, int cw, int ch,
                           double yMn, double yMx, String yLabel) {
        g.setFont(new Font("SansSerif",Font.PLAIN,9));
        for(int i=0;i<=5;i++){
            int gy=mt+(int)(ch*i/5.0);
            g.setColor(new Color(0x28,0x28,0x48)); g.drawLine(ml,gy,ml+cw,gy);
            g.setColor(C_DIM);
            String lbl=fmt((long)(yMx-(yMx-yMn)*i/5.0));
            g.drawString(lbl,ml-g.getFontMetrics().stringWidth(lbl)-3,gy+4);
        }
        g.setColor(C_DIM); g.drawString(yLabel,2,mt-4);
    }

    private void drawXTicks(Graphics2D g, int ml, int mt, int cw, int ch,
                             double[] xs, double xMn, double xMx, String xLabel) {
        if(xs==null||xs.length==0) return;
        g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(C_DIM);
        for(int t : new int[]{0,xs.length/2,xs.length-1}){
            if(t<xs.length){
                int tx=ml+(int)((xs[t]-xMn)/(xMx-xMn)*cw);
                g.drawString(fmt((long)xs[t]),tx-6,mt+ch+12);}
        }
        g.drawString(xLabel,ml+cw-22,mt+ch+24);
    }

    private void chartTitle(Graphics2D g, String title, int w, int mt) {
        g.setFont(new Font("SansSerif",Font.BOLD,11)); g.setColor(C_DIM);
        g.drawString(title,10,mt-6);
        g.setColor(new Color(0x2C,0x2C,0x50)); g.fillRect(10,mt-4,w-20,1);
    }

    private JPanel chartRow(JPanel... panels) {
        JPanel row = new JPanel(new GridLayout(1,panels.length,10,0));
        row.setBackground(C_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,300));
        row.setMinimumSize(new Dimension(0,240));
        for(JPanel p:panels){ p.setBorder(BorderFactory.createLineBorder(C_BORDER)); row.add(p); }
        return row;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared UI helpers
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel header(String text) {
        JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,14,9));
        p.setBackground(C_PANEL); p.setBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER));
        JLabel l=new JLabel(text); l.setFont(new Font("SansSerif",Font.BOLD,14)); l.setForeground(C_TEXT);
        p.add(l); return p;
    }

    private JLabel sectionHdr(String text) {
        JLabel l=new JLabel(text); l.setFont(new Font("SansSerif",Font.BOLD,10)); l.setForeground(C_DIM);
        l.setBackground(new Color(0x10,0x10,0x20)); l.setOpaque(true);
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,0,1,0,C_BORDER),new EmptyBorder(4,8,4,8)));
        return l;
    }

    private JPanel navBar() {
        JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,10,7));
        p.setBackground(C_PANEL); p.setBorder(BorderFactory.createMatteBorder(1,0,0,0,C_BORDER));
        return p;
    }

    private JPanel fRow(String lbl, JComponent field) {
        JPanel row=new JPanel(new BorderLayout(10,0));
        row.setBackground(C_PANEL); row.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));
        JLabel l=new JLabel(lbl); l.setFont(new Font("SansSerif",Font.PLAIN,12)); l.setForeground(C_TEXT);
        l.setPreferredSize(new Dimension(270,28));
        styleField(field);
        row.add(l,BorderLayout.WEST); row.add(field,BorderLayout.CENTER);
        return row;
    }

    private JSpinner spinner(int val, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val,min,max,step));
        s.setPreferredSize(new Dimension(150,28)); return s;
    }

    private void styleField(JComponent f) {
        f.setBackground(C_SURF); f.setForeground(C_TEXT);
        f.setFont(new Font("Monospaced",Font.PLAIN,12));
        f.setBorder(BorderFactory.createLineBorder(C_BORDER));
        if(f instanceof JSpinner sp){
            JComponent ed=sp.getEditor(); ed.setBackground(C_SURF);
            if(ed instanceof JSpinner.DefaultEditor de){
                de.getTextField().setBackground(C_SURF);
                de.getTextField().setForeground(C_TEXT);
                de.getTextField().setCaretColor(C_TEXT);}
        }
    }

    private JButton plainBtn(String text) {
        JButton b=new JButton(text);
        b.setBackground(new Color(0x28,0x28,0x4C)); b.setForeground(C_TEXT);
        b.setFont(new Font("SansSerif",Font.PLAIN,12)); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER,1,true),new EmptyBorder(5,14,5,14)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }

    private JButton accentBtn(String text) {
        JButton b=plainBtn(text);
        b.setBackground(C_ACCENT); b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_ACCENT.darker(),1,true),new EmptyBorder(5,16,5,16)));
        return b;
    }

    private JLabel rTitle(String text) {
        JLabel l=new JLabel(text); l.setFont(new Font("SansSerif",Font.BOLD,17)); l.setForeground(C_TEXT);
        l.setAlignmentX(0f); return l;
    }

    static Graphics2D aa(Graphics g) {
        Graphics2D g2=(Graphics2D)g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    static String fmt(long n) { return new DecimalFormat("#,###").format(n); }
    static String fmtBytes(long b) {
        if(b<1024) return b+" B";
        if(b<1024*1024) return String.format("%.1f KB",b/1024.0);
        return String.format("%.1f MB",b/(1024.0*1024));
    }
}
