package grammarextractor;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

public class GrammarApp extends JFrame {

    // ── Colour palette ───────────────────────────────────────────────────────
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

    // ── Pipeline modes ───────────────────────────────────────────────────────
    enum Pipeline {
        FULL_ROUNDTRIP("Full roundtrip  (Compress + Extract + Recompress)"),
        COMPRESS_ONLY("Compress only"),
        COMPRESS_EXTRACT("Compress + Extract"),
        EXTRACT_RECOMPRESS("Extract + Recompress");

        final String label;
        Pipeline(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    // ── Data records ─────────────────────────────────────────────────────────
    record PassStats(int pass, int grammarSize, int ruleCount, long timeNs,
                     String bigramLabel, int bigramFreq, int saved) {}

    record TechnicalReport(
        String inputText, int inputLength,
        Map<Character, Integer> charFreqs, double entropy, int uniqueChars,
        Pipeline pipeline,
        List<PassStats> compPasses, int initialSize, int compressedSize, long compTimeNs,
        int extractFrom, int extractTo, int extractLen, int extractInitSize,
        List<PassStats> recompPasses, int recompFinalSize,
        Map<Pair, Integer> bigramsInitial,
        Map<Pair, Integer> bigrams_final,
        long totalTimeNs
    ) {}

    // ── Application state ────────────────────────────────────────────────────
    private Path selectedFile = null;

    private final CardLayout cards = new CardLayout();
    private final JPanel     deck  = new JPanel(cards);

    private JTextField tfLength;
    private JSpinner   spPasses, spFrom, spTo;
    private JTextField tfAlphabet;
    private JPanel     reportHolder;
    private JSplitPane techSplit;
    private JComboBox<Pipeline> cbPipeline;
    private JPanel     extractParamsPanel;
    private JTextArea  techFilePreview;
    private JLabel     techFileMeta;

    private boolean useExistingFile = false;
    private Path    techSelectedFile = null;

    // ── Entry points ─────────────────────────────────────────────────────────
    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            // macOS Aqua L&F has a known NegativeArraySizeException when painting
            // JComboBox with a custom border. Switch to Nimbus (or Metal) to avoid it.
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
                if (!UIManager.getLookAndFeel().getName().equals("Nimbus")) {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                }
            } catch (Exception ignored) {}
            new GrammarApp().setVisible(true);
        });
    }

    public GrammarApp() {
        super("RePair Grammar Lab");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setBackground(C_BG);

        deck.setBackground(C_BG);
        deck.add(buildModePicker(),  "mode");
        deck.add(buildFilePicker(),  "file");
        deck.add(buildTechnical(),   "technical");
        add(deck);

        setSize(1250, 820);
        setLocationRelativeTo(null);
        cards.show(deck, "mode");
    }

    private static boolean isAcceptedFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        // .rp files are binary (decoder_mac output) and can't be parsed directly.
        return name.endsWith(".txt") || name.endsWith(".hrf");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — File Picker (Animation Mode)
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildFilePicker() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(header("  Animation Mode   ·   Choose a grammar file to visualize"), BorderLayout.NORTH);

        DefaultListModel<Path> model = new DefaultListModel<>();
        JList<Path> list = new JList<>(model);
        list.setBackground(C_PANEL); list.setForeground(C_TEXT);
        list.setFont(new Font("SansSerif", Font.PLAIN, 12));
        list.setSelectionBackground(C_BLUE); list.setSelectionForeground(C_TEXT);
        list.setFixedCellHeight(48);
        list.setCellRenderer(new FileCell());

        try {
            Files.list(Path.of("."))
                 .filter(GrammarApp::isAcceptedFile)
                 .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                 .forEach(model::addElement);
        } catch (IOException ignored) {}

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(new EmptyBorder(6, 6, 6, 0));
        listScroll.setBackground(C_PANEL); listScroll.getViewport().setBackground(C_PANEL);

        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(C_PANEL);
        left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));
        left.add(sectionHdr("  FILES IN WORKING DIRECTORY"), BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);

        JTextArea preview = new JTextArea("  Select a file to preview");
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
            showFilePreview(p, preview, meta);
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(330); split.setDividerSize(4); split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        JPanel bot = navBar();
        JButton back = plainBtn("◀  Back");
        back.addActionListener(e -> cards.show(deck, "mode"));
        JButton open = accentBtn("Open Visualizer");
        open.addActionListener(e -> {
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(this, "Select a file first.", "No file", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                Parser.ParsedGrammar g = loadGrammarFromFile(selectedFile);
                new RecompressionViewer(g, 0).setVisible(true);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        bot.add(back);
        bot.add(Box.createHorizontalGlue());
        bot.add(open);
        bot.add(Box.createHorizontalStrut(12));
        root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private void showFilePreview(Path p, JTextArea preview, JLabel meta) {
        try {
            long sz = Files.size(p);
            String name = p.getFileName().toString().toLowerCase();
            if (name.endsWith(".rp")) {
                preview.setText("Binary grammar file (.rp)\nSize: " + fmtBytes(sz));
                meta.setText("  " + fmtBytes(sz) + "  ·  Binary compressed grammar");
                return;
            }
            char[] buf = new char[1200];
            int nRead;
            boolean isGrammar;
            long lines;
            try (BufferedReader br = Files.newBufferedReader(p)) {
                nRead = br.read(buf);
                String head = nRead > 0 ? new String(buf, 0, Math.min(nRead, 40)) : "";
                isGrammar = head.startsWith("R") || head.contains("SEQ:");
            }
            String txt = nRead > 0 ? new String(buf, 0, nRead) : "";
            if (sz > nRead) txt += "\n...(truncated)";
            preview.setText(txt);
            preview.setCaretPosition(0);
            try (var stream = Files.lines(p)) { lines = stream.count(); }
            meta.setText("  " + fmtBytes(sz) + "  ·  " + lines + " lines  ·  "
                + (isGrammar ? "Grammar file" : "Plain text"));
        } catch (IOException ex) {
            preview.setText("Cannot read file: " + ex.getMessage());
        }
    }

    private Parser.ParsedGrammar loadGrammarFromFile(Path p) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(p)) {
            char[] probe = new char[40];
            int n = br.read(probe);
            if (n > 0) {
                String head = new String(probe, 0, n);
                if (head.startsWith("R") || head.contains("SEQ:")) {
                    return Parser.parseFile(p);
                }
            }
        }
        List<Integer> seq = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(p)) {
            int ch;
            while ((ch = br.read()) != -1) seq.add(ch);
        }
        Parser.ParsedGrammar pg = new Parser.ParsedGrammar(new HashMap<>(), seq, Collections.emptyMap());
        return new Parser.ParsedGrammar(new HashMap<>(), seq,
            RuleMetadata.computeAll(pg, Collections.emptySet()));
    }

    private class FileCell extends DefaultListCellRenderer {
        private record FileInfo(long size, boolean grammar) {}
        private final Map<Path, FileInfo> cache = new HashMap<>();

        private FileInfo info(Path p) {
            return cache.computeIfAbsent(p, path -> {
                long sz = 0; boolean gram = false;
                try {
                    sz = Files.size(path);
                    try (var reader = Files.newBufferedReader(path)) {
                        char[] buf = new char[40];
                        int n = reader.read(buf);
                        if (n > 0) {
                            String head = new String(buf, 0, n);
                            gram = head.startsWith("R") || head.contains("SEQ:");
                        }
                    }
                } catch (IOException ignored) {}
                return new FileInfo(sz, gram);
            });
        }

        @Override public Component getListCellRendererComponent(
                JList<?> l, Object v, int i, boolean sel, boolean foc) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(true);
            row.setBackground(sel ? C_BLUE : i % 2 == 0 ? C_PANEL : C_SURF);
            row.setBorder(new EmptyBorder(8, 12, 8, 12));

            Path p = (Path) v;
            FileInfo fi = info(p);
            String name = p.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";

            JLabel icon = new JLabel(fi.grammar || ext.equals(".rp") ? "G" : "T");
            icon.setFont(new Font("SansSerif", Font.BOLD, 16));
            icon.setForeground(fi.grammar || ext.equals(".rp") ? C_GREEN : C_SEL);
            row.add(icon, BorderLayout.WEST);

            JPanel infoP = new JPanel(); infoP.setOpaque(false);
            infoP.setLayout(new BoxLayout(infoP, BoxLayout.Y_AXIS));
            JLabel nl = new JLabel(name);
            nl.setFont(new Font("SansSerif", Font.BOLD, 12));
            nl.setForeground(sel ? Color.WHITE : C_TEXT);
            String typeStr = ext.equals(".rp") ? "binary grammar" : (fi.grammar ? "grammar" : "text");
            JLabel sl = new JLabel(fmtBytes(fi.size) + "  ·  " + typeStr);
            sl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            sl.setForeground(sel ? new Color(0xCC, 0xCC, 0xFF) : C_DIM);
            infoP.add(nl); infoP.add(sl);
            row.add(infoP, BorderLayout.CENTER);
            return row;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — Mode Picker
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildModePicker() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(header("  RePair Grammar Lab   ·   Choose a mode"), BorderLayout.NORTH);

        JPanel cards2 = new JPanel(new GridLayout(1, 2, 24, 0));
        cards2.setBackground(C_BG);
        cards2.setBorder(new EmptyBorder(48, 64, 48, 64));

        JPanel animCard = modeCard("A", "Animation Mode", C_BLUE,
            "Select a grammar or text file, then step through\n"
          + "the recompression algorithm one operation at a time.\n"
          + "Watch rules update, bigram bars animate, and\n"
          + "grammar size converge live.\n\n"
          + "Best for: understanding the algorithm, demos.");
        attachCardClick(animCard, () -> cards.show(deck, "file"));

        JPanel techCard = modeCard("T", "Technical Mode", C_ACCENT,
            "Generate random text (any length) or load an\n"
          + "existing file, pick a pipeline (compress, extract,\n"
          + "recompress), and get an extensive report\n"
          + "with charts and a pass-by-pass statistics table.\n\n"
          + "Best for: research, benchmarking, paper stats.");
        attachCardClick(techCard, () -> cards.show(deck, "technical"));

        cards2.add(animCard); cards2.add(techCard);
        root.add(cards2, BorderLayout.CENTER);
        return root;
    }

    private void attachCardClick(JComponent card, Runnable action) {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { action.run(); }
        };
        card.addMouseListener(ma);
        attachClickRecursive(card, ma);
    }

    private void attachClickRecursive(Component c, MouseListener ml) {
        if (c instanceof JTextArea ta) {
            // JTextArea handles its own mouse events; disable interaction so clicks bubble up.
            ta.setFocusable(false);
            ta.setHighlighter(null);
        }
        c.addMouseListener(ml);
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) attachClickRecursive(child, ml);
        }
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

        JLabel il = new JLabel(icon); il.setFont(new Font("SansSerif",Font.BOLD,48)); il.setForeground(accent); il.setAlignmentX(.5f);
        JLabel tl = new JLabel(title); tl.setFont(new Font("SansSerif",Font.BOLD,20)); tl.setForeground(accent); tl.setAlignmentX(.5f);
        JTextArea dl = new JTextArea(desc); dl.setEditable(false); dl.setOpaque(false);
        dl.setWrapStyleWord(true); dl.setLineWrap(true);
        dl.setFont(new Font("SansSerif",Font.PLAIN,12)); dl.setForeground(C_DIM);
        JLabel cta = new JLabel("Click to select  >"); cta.setFont(new Font("SansSerif",Font.BOLD,12)); cta.setForeground(accent); cta.setAlignmentX(.5f);

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
        root.add(header("  Technical Mode   ·   Roundtrip Analysis"), BorderLayout.NORTH);

        // ── Source toggle ────────────────────────────────────────────────────
        JToggleButton btnGenerate = new JToggleButton("Generate random text");
        JToggleButton btnFile     = new JToggleButton("Use existing file");
        ButtonGroup   srcGroup    = new ButtonGroup();
        srcGroup.add(btnGenerate); srcGroup.add(btnFile);
        btnGenerate.setSelected(true);
        styleToggle(btnGenerate, true);
        styleToggle(btnFile, false);

        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
        toggleRow.setBackground(C_PANEL);
        toggleRow.setBorder(new EmptyBorder(0, 20, 0, 0));
        toggleRow.add(btnGenerate); toggleRow.add(Box.createHorizontalStrut(4)); toggleRow.add(btnFile);

        // ── Source-specific area (CardLayout) ────────────────────────────────
        CardLayout srcCards = new CardLayout();
        JPanel     srcDeck  = new JPanel(srcCards);
        srcDeck.setBackground(C_PANEL);

        // Panel A — generate random text
        JPanel genPanel = new JPanel();
        genPanel.setLayout(new BoxLayout(genPanel, BoxLayout.Y_AXIS));
        genPanel.setBackground(C_PANEL);
        genPanel.setBorder(new EmptyBorder(4, 0, 4, 0));

        tfLength   = new JTextField("500");
        tfAlphabet = new JTextField("abcdefghijklmnopqrstuvwxyz");
        styleField(tfLength); styleField(tfAlphabet);

        genPanel.add(fRow("Text length N  (no upper limit):", tfLength));
        genPanel.add(Box.createVerticalStrut(8));
        genPanel.add(fRow("Alphabet:", tfAlphabet));
        srcDeck.add(genPanel, "generate");

        // Panel B — existing file list with preview
        DefaultListModel<Path> techFileModel = new DefaultListModel<>();
        JList<Path> techFileList = new JList<>(techFileModel);
        techFileList.setBackground(C_SURF); techFileList.setForeground(C_TEXT);
        techFileList.setFont(new Font("SansSerif", Font.PLAIN, 11));
        techFileList.setSelectionBackground(C_BLUE); techFileList.setSelectionForeground(C_TEXT);
        techFileList.setFixedCellHeight(36);
        techFileList.setCellRenderer(new FileCell());
        try {
            Files.list(Path.of("."))
                 .filter(GrammarApp::isAcceptedFile)
                 .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                 .forEach(techFileModel::addElement);
        } catch (IOException ignored) {}

        techFilePreview = new JTextArea("  Select a file to see overview");
        techFilePreview.setEditable(false); techFilePreview.setLineWrap(true); techFilePreview.setWrapStyleWord(true);
        techFilePreview.setBackground(C_SURF); techFilePreview.setForeground(C_DIM);
        techFilePreview.setFont(new Font("Monospaced", Font.PLAIN, 10));
        techFilePreview.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane previewScroll = new JScrollPane(techFilePreview);
        previewScroll.setBorder(null);
        previewScroll.getViewport().setBackground(C_SURF);
        previewScroll.setPreferredSize(new Dimension(250, 100));

        techFileMeta = new JLabel(" ");
        techFileMeta.setFont(new Font("SansSerif", Font.PLAIN, 10));
        techFileMeta.setForeground(C_DIM);
        techFileMeta.setBorder(new EmptyBorder(2, 8, 2, 0));

        techFileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && techFileList.getSelectedValue() != null) {
                techSelectedFile = techFileList.getSelectedValue();
                showFilePreview(techSelectedFile, techFilePreview, techFileMeta);
            }
        });

        JScrollPane fileScroll = new JScrollPane(techFileList);
        fileScroll.setBorder(new EmptyBorder(4, 0, 0, 0));
        fileScroll.setBackground(C_SURF); fileScroll.getViewport().setBackground(C_SURF);

        JPanel filePanel = new JPanel(new BorderLayout());
        filePanel.setBackground(C_PANEL);
        filePanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        JSplitPane fileSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileScroll, previewScroll);
        fileSplit.setDividerLocation(280); fileSplit.setDividerSize(3); fileSplit.setBorder(null);
        fileSplit.setBackground(C_PANEL);

        filePanel.add(fileSplit, BorderLayout.CENTER);
        filePanel.add(techFileMeta, BorderLayout.SOUTH);
        srcDeck.add(filePanel, "file");

        btnGenerate.addActionListener(e -> {
            useExistingFile = false;
            styleToggle(btnGenerate, true); styleToggle(btnFile, false);
            srcCards.show(srcDeck, "generate");
        });
        btnFile.addActionListener(e -> {
            useExistingFile = true;
            styleToggle(btnGenerate, false); styleToggle(btnFile, true);
            srcCards.show(srcDeck, "file");
        });

        // ── Pipeline selector ────────────────────────────────────────────────
        cbPipeline = new JComboBox<>(Pipeline.values());
        cbPipeline.setSelectedItem(Pipeline.FULL_ROUNDTRIP);
        cbPipeline.setBackground(C_SURF); cbPipeline.setForeground(C_TEXT);
        cbPipeline.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cbPipeline.setBorder(BorderFactory.createLineBorder(C_BORDER, 1, true));

        // ── Shared params ────────────────────────────────────────────────────
        spPasses = spinner(0,  0, 10_000, 1);
        spFrom   = spinner(20, 0, Integer.MAX_VALUE - 1, 1);
        spTo     = spinner(80, 1, Integer.MAX_VALUE, 1);

        extractParamsPanel = new JPanel();
        extractParamsPanel.setLayout(new BoxLayout(extractParamsPanel, BoxLayout.Y_AXIS));
        extractParamsPanel.setBackground(C_PANEL);
        extractParamsPanel.add(fRow("Extract start (inclusive):", spFrom));
        extractParamsPanel.add(Box.createVerticalStrut(8));
        extractParamsPanel.add(fRow("Extract end (exclusive):",   spTo));

        cbPipeline.addActionListener(e -> {
            Pipeline sel = (Pipeline) cbPipeline.getSelectedItem();
            boolean needsExtract = sel == Pipeline.FULL_ROUNDTRIP
                || sel == Pipeline.COMPRESS_EXTRACT
                || sel == Pipeline.EXTRACT_RECOMPRESS;
            extractParamsPanel.setVisible(needsExtract);
        });

        JPanel sharedPanel = new JPanel();
        sharedPanel.setLayout(new BoxLayout(sharedPanel, BoxLayout.Y_AXIS));
        sharedPanel.setBackground(C_PANEL);
        sharedPanel.setBorder(new EmptyBorder(8, 0, 4, 0));
        sharedPanel.add(fRow("Pipeline:", cbPipeline));
        sharedPanel.add(Box.createVerticalStrut(8));
        sharedPanel.add(fRow("Max passes (0 = unlimited):", spPasses));
        sharedPanel.add(Box.createVerticalStrut(8));
        sharedPanel.add(extractParamsPanel);

        // ── Assemble form ────────────────────────────────────────────────────
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(C_PANEL);
        form.setBorder(new EmptyBorder(12, 28, 12, 28));
        form.add(toggleRow);
        form.add(Box.createVerticalStrut(10));
        srcDeck.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        srcDeck.setPreferredSize(new Dimension(800, 130));
        form.add(srcDeck);
        form.add(Box.createVerticalStrut(10));
        form.add(sharedPanel);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBackground(C_PANEL); formScroll.getViewport().setBackground(C_PANEL);
        formScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        formScroll.setMinimumSize(new Dimension(0, 200));
        formScroll.setPreferredSize(new Dimension(800, 320));

        // ── Report holder ────────────────────────────────────────────────────
        reportHolder = new JPanel(new BorderLayout());
        reportHolder.setBackground(C_BG);
        JPanel hintPanel = new JPanel(new GridBagLayout());
        hintPanel.setBackground(C_BG);
        JPanel hintBox = new JPanel();
        hintBox.setLayout(new BoxLayout(hintBox, BoxLayout.Y_AXIS)); hintBox.setOpaque(false);
        JLabel hIcon = new JLabel(">>"); hIcon.setFont(new Font("Monospaced", Font.BOLD, 36)); hIcon.setForeground(C_DIM);
        hIcon.setAlignmentX(0.5f);
        JLabel hText = new JLabel("Configure parameters above, then click  Run Analysis");
        hText.setFont(new Font("SansSerif", Font.ITALIC, 13)); hText.setForeground(C_DIM);
        hText.setAlignmentX(0.5f);
        hintBox.add(hIcon); hintBox.add(Box.createVerticalStrut(10)); hintBox.add(hText);
        hintPanel.add(hintBox);
        reportHolder.add(hintPanel, BorderLayout.CENTER);

        techSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, reportHolder);
        techSplit.setDividerLocation(320); techSplit.setResizeWeight(0.0);
        techSplit.setDividerSize(5); techSplit.setBorder(null);
        root.add(techSplit, BorderLayout.CENTER);

        JPanel bot = navBar();
        JButton back = plainBtn("◀  Back");
        back.addActionListener(e -> cards.show(deck, "mode"));
        JButton run = accentBtn("Run Analysis");
        run.addActionListener(e -> runTechnical());
        bot.add(back); bot.add(Box.createHorizontalGlue()); bot.add(run);
        bot.add(Box.createHorizontalStrut(12));
        root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private void styleToggle(JToggleButton b, boolean active) {
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setBackground(active ? C_ACCENT : C_SURF);
        b.setForeground(active ? Color.WHITE : C_DIM);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false); b.setOpaque(true);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(active ? C_ACCENT : C_BORDER, 1, true),
            new EmptyBorder(7, 18, 7, 18)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void runTechnical() {
        Pipeline pipe = (Pipeline) cbPipeline.getSelectedItem();
        int passes = (int) spPasses.getValue();
        int from   = (int) spFrom.getValue();
        int to     = (int) spTo.getValue();

        if (useExistingFile && techSelectedFile == null) {
            JOptionPane.showMessageDialog(this, "Select a file from the list.", "No file", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int n = 0;
        String alpha = tfAlphabet.getText().trim();
        if (alpha.isEmpty()) alpha = "abcdefghijklmnopqrstuvwxyz";

        if (!useExistingFile) {
            try {
                n = Integer.parseInt(tfLength.getText().trim());
                if (n <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Text length must be a positive integer.",
                    "Bad length", JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean needsExtract = pipe == Pipeline.FULL_ROUNDTRIP
                || pipe == Pipeline.COMPRESS_EXTRACT
                || pipe == Pipeline.EXTRACT_RECOMPRESS;
            if (needsExtract && (from >= to || to > n)) {
                JOptionPane.showMessageDialog(this, "Invalid range: need 0 <= start < end <= N.",
                    "Bad range", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        // Build progress panel
        JPanel busyPanel = new JPanel(new GridBagLayout());
        busyPanel.setBackground(C_BG);
        JPanel busyBox = new JPanel();
        busyBox.setLayout(new BoxLayout(busyBox, BoxLayout.Y_AXIS)); busyBox.setOpaque(false);

        JLabel bPhase = new JLabel("Initializing...");
        bPhase.setFont(new Font("SansSerif", Font.BOLD, 14)); bPhase.setForeground(C_SEL);
        bPhase.setAlignmentX(0.5f);

        JLabel bDetail = new JLabel(" ");
        bDetail.setFont(new Font("SansSerif", Font.PLAIN, 11)); bDetail.setForeground(C_DIM);
        bDetail.setAlignmentX(0.5f);

        JLabel bTime = new JLabel(" ");
        bTime.setFont(new Font("Monospaced", Font.PLAIN, 11)); bTime.setForeground(C_DIM);
        bTime.setAlignmentX(0.5f);

        JProgressBar bBar = new JProgressBar();
        bBar.setIndeterminate(true);
        bBar.setForeground(C_ACCENT); bBar.setBackground(C_PANEL);
        bBar.setAlignmentX(0.5f); bBar.setMaximumSize(new Dimension(300, 4));

        busyBox.add(bPhase); busyBox.add(Box.createVerticalStrut(6));
        busyBox.add(bDetail); busyBox.add(Box.createVerticalStrut(8));
        busyBox.add(bBar); busyBox.add(Box.createVerticalStrut(8));
        busyBox.add(bTime);
        busyPanel.add(busyBox);
        reportHolder.removeAll(); reportHolder.add(busyPanel, BorderLayout.CENTER);
        reportHolder.revalidate(); reportHolder.repaint();

        final int fn = n, fpasses = passes, ffrom = from, fto = to;
        final String fa = alpha;
        final boolean fromFile = useExistingFile;
        final Path fpath = techSelectedFile;
        final long startMs = System.currentTimeMillis();

        SwingWorker<TechnicalReport, String> w = new SwingWorker<>() {
            @Override protected TechnicalReport doInBackground() throws Exception {
                if (fromFile) {
                    publish("Loading file: " + fpath.getFileName() + "...");
                    Parser.ParsedGrammar g = loadGrammarFromFile(fpath);
                    String txt = Decompressor.decompress(g);
                    int textLen = txt.length();
                    int adjFrom = Math.min(ffrom, textLen - 1);
                    int adjTo   = Math.min(fto,   textLen);
                    if (adjFrom >= adjTo) adjTo = Math.min(adjFrom + 50, textLen);
                    return pipelineCore(g, txt, fpasses, adjFrom, adjTo, pipe, this::publish);
                } else {
                    publish("Generating random text (N=" + fn + ")...");
                    return pipeline(fn, fpasses, ffrom, fto, fa, pipe, this::publish);
                }
            }
            @Override protected void process(List<String> chunks) {
                String latest = chunks.get(chunks.size() - 1);
                if (latest.startsWith("PHASE:")) {
                    bPhase.setText(latest.substring(6));
                } else {
                    bDetail.setText(latest);
                }
                long elapsed = System.currentTimeMillis() - startMs;
                bTime.setText(String.format("Elapsed: %.1f s", elapsed / 1000.0));
            }
            @Override protected void done() {
                try {
                    TechnicalReport r = get();
                    reportHolder.removeAll();
                    reportHolder.add(buildReport(r), BorderLayout.CENTER);
                    reportHolder.revalidate(); reportHolder.repaint();
                    techSplit.setDividerLocation(320);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    reportHolder.removeAll();
                    JLabel err = new JLabel("  Error: " + cause.getMessage());
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

    @FunctionalInterface
    interface ProgressCallback { void update(String msg); }

    private TechnicalReport pipeline(int n, int maxPasses, int from, int to, String alpha,
                                      Pipeline pipe, ProgressCallback progress) {
        Random rnd = new Random(42);
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) buf[i] = alpha.charAt(rnd.nextInt(alpha.length()));
        String input = new String(buf);

        List<Integer> flatSeq = new ArrayList<>(n);
        for (char c : buf) flatSeq.add((int) c);
        Parser.ParsedGrammar orig = new Parser.ParsedGrammar(
            new HashMap<>(), flatSeq,
            RuleMetadata.computeAll(
                new Parser.ParsedGrammar(new HashMap<>(), flatSeq, Collections.emptyMap()),
                Collections.emptySet()));

        return pipelineCore(orig, input, maxPasses, from, to, pipe, progress);
    }

    private TechnicalReport pipelineCore(Parser.ParsedGrammar orig, String inputText,
                                          int maxPasses, int from, int to,
                                          Pipeline pipe, ProgressCallback progress) {
        long T0 = System.nanoTime();
        int n = inputText.length();

        // Char frequencies + entropy
        progress.update("PHASE:Computing character statistics...");
        Map<Character, Integer> cf = new TreeMap<>();
        for (char c : inputText.toCharArray()) cf.merge(c, 1, Integer::sum);
        double entropy = cf.values().stream()
            .mapToDouble(cnt -> { double p = (double)cnt/n; return -p * Math.log(p) / Math.log(2); })
            .sum();

        boolean doCompress   = pipe == Pipeline.FULL_ROUNDTRIP || pipe == Pipeline.COMPRESS_ONLY || pipe == Pipeline.COMPRESS_EXTRACT;
        boolean doExtract    = pipe == Pipeline.FULL_ROUNDTRIP || pipe == Pipeline.COMPRESS_EXTRACT || pipe == Pipeline.EXTRACT_RECOMPRESS;
        boolean doRecompress = pipe == Pipeline.FULL_ROUNDTRIP || pipe == Pipeline.EXTRACT_RECOMPRESS;

        // Initialize grammar
        progress.update("PHASE:Initializing grammar...");
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(orig);
        Map<Integer,List<Integer>> rules = new LinkedHashMap<>(init.grammar().grammarRules());
        List<Integer> seq  = new ArrayList<>(init.grammar().sequence());
        Set<Integer>  art  = new HashSet<>(init.artificialTerminals());
        Map<Integer,List<Integer>> artR = new LinkedHashMap<>();
        int nextId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        int initSz = rules.values().stream().mapToInt(List::size).sum() + seq.size();

        Map<Integer,RuleMetadata> m0 = RuleMetadata.computeAll(rules, seq, art);
        Map<Pair,Integer> bigramsInit =
            Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules, seq, m0), art, false, null);

        List<PassStats> compPasses = new ArrayList<>();
        int actualMax = maxPasses == 0 ? 999_999 : maxPasses;

        long cT0 = System.nanoTime();
        if (doCompress) {
            progress.update("PHASE:Compressing...");
            for (int pass = 1; pass <= actualMax; pass++) {
                long t0 = System.nanoTime();
                progress.update("Pass " + pass + ": computing metadata...");
                Map<Integer,RuleMetadata> m = RuleMetadata.computeAll(rules, seq, art);
                progress.update("Pass " + pass + ": computing bigram frequencies...");
                Map<Pair,Integer> freqs =
                    Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules,seq,m), art, false, null);
                if (freqs.isEmpty()) break;
                progress.update("Pass " + pass + ": selecting most frequent bigram...");
                Pair bg = Recompressor.getMostFrequentBigram(freqs, art);
                if (bg == null || freqs.getOrDefault(bg, 0) <= 1) break;
                progress.update("Pass " + pass + ": uncrossing + replacing (" + RecompressionViewer.sym(bg.first) + "," + RecompressionViewer.sym(bg.second) + ")...");
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
        }
        long cT1 = System.nanoTime();
        int compSz = rules.values().stream().mapToInt(List::size).sum() + seq.size();

        Map<Pair,Integer> bigFinal =
            Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(rules, seq,
                RuleMetadata.computeAll(rules,seq,art)), art, false, null);

        // Extract
        int exInitSz = 0;
        List<PassStats> recompPasses = new ArrayList<>();
        int recompFinal = 0;

        if (doExtract) {
            progress.update("PHASE:Extracting excerpt...");
            Map<Integer,List<Integer>> comb = new LinkedHashMap<>(rules);
            comb.putAll(artR);
            Map<Integer,RuleMetadata> combMeta =
                RuleMetadata.computeAll(comb, seq, new HashSet<>());
            Parser.ParsedGrammar compGram = new Parser.ParsedGrammar(comb, seq, combMeta);

            int fullSz  = Extractor.getUncompressedSize(compGram);
            int adjFrom = Math.min(from + 1, fullSz - 1);
            int adjTo   = Math.min(to   + 1, fullSz);
            if (adjFrom >= adjTo) adjTo = Math.min(adjFrom + 50, fullSz);

            Parser.ParsedGrammar exGram;
            try {
                exGram = Extractor.extractExcerpt(compGram, adjFrom, adjTo, false);
            } catch (Exception ex) {
                String full = Decompressor.decompress(compGram);
                String slice = full.substring(adjFrom, Math.min(adjTo, full.length()));
                List<Integer> sl = new ArrayList<>();
                for (char c : slice.toCharArray()) sl.add((int)c);
                exGram = new Parser.ParsedGrammar(new HashMap<>(), sl,
                    RuleMetadata.computeAll(new Parser.ParsedGrammar(new HashMap<>(),sl,Collections.emptyMap()),Collections.emptySet()));
            }
            exInitSz = Parser.sizeOfGrammar(exGram);

            if (doRecompress) {
                progress.update("PHASE:Recompressing excerpt...");
                Recompressor.InitializedGrammar init2 = Recompressor.initializeWithSentinelsAndRootRule(exGram);
                Map<Integer,List<Integer>> r2 = new LinkedHashMap<>(init2.grammar().grammarRules());
                List<Integer> s2 = new ArrayList<>(init2.grammar().sequence());
                Set<Integer>  a2 = new HashSet<>(init2.artificialTerminals());
                int nx2 = r2.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

                for (int pass = 1; pass <= actualMax; pass++) {
                    long t0 = System.nanoTime();
                    progress.update("Recomp pass " + pass + "...");
                    Map<Integer,RuleMetadata> m = RuleMetadata.computeAll(r2, s2, a2);
                    Map<Pair,Integer> freqs =
                        Recompressor.computeBigramFrequencies(new Parser.ParsedGrammar(r2,s2,m), a2, false, null);
                    if (freqs.isEmpty()) break;
                    Pair bg = Recompressor.getMostFrequentBigram(freqs, a2);
                    if (bg == null || freqs.getOrDefault(bg, 0) <= 1) break;
                    Recompressor.uncrossBigrams(bg.first, bg.second, r2, m, a2);
                    int nid = nx2++;
                    Recompressor.replaceBigramInRules(bg.first, bg.second, nid, r2, a2);
                    a2.add(nid);
                    Recompressor.removeRedundantRules(r2, s2);
                    int sz  = r2.values().stream().mapToInt(List::size).sum() + s2.size();
                    int prv = recompPasses.isEmpty() ? exInitSz : recompPasses.get(recompPasses.size()-1).grammarSize();
                    recompPasses.add(new PassStats(pass, sz, r2.size(), System.nanoTime()-t0,
                        RecompressionViewer.sym(bg.first)+"+"+ RecompressionViewer.sym(bg.second),
                        freqs.get(bg), prv - sz));
                }
                recompFinal = r2.values().stream().mapToInt(List::size).sum() + s2.size();
            }
        }

        progress.update("PHASE:Building report...");
        String preview = inputText.length() > 300 ? inputText.substring(0, 300) + "..." : inputText;
        return new TechnicalReport(
            preview, n, cf, entropy, cf.size(), pipe,
            compPasses, initSz, compSz, cT1-cT0,
            from, to, to-from, exInitSz,
            recompPasses, recompFinal,
            bigramsInit, bigFinal,
            System.nanoTime()-T0
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Report Panel
    // ═════════════════════════════════════════════════════════════════════════

    private JScrollPane buildReport(TechnicalReport r) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(C_BG);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        root.add(rTitle("  Roundtrip Analysis Report  [" + r.pipeline.label + "]"));
        root.add(Box.createVerticalStrut(14));
        root.add(summaryCards(r));
        root.add(Box.createVerticalStrut(16));

        boolean hasCompress = !r.compPasses.isEmpty();
        boolean hasExtract  = r.extractInitSize > 0;
        boolean hasRecomp   = !r.recompPasses.isEmpty();

        // Row 1: Size convergence  |  Reduction per pass
        if (hasCompress) {
            root.add(chartRow(
                lineChart("Grammar Size Convergence",
                    sizeSeriesData(r, hasRecomp), hasRecomp ? new String[]{"Full compression","Extract recomp"} : new String[]{"Full compression"},
                    hasRecomp ? new Color[]{CHART[0], CHART[1]} : new Color[]{CHART[0]}, "Pass","Symbols"),
                barChart("Size Reduction per Pass",
                    reductionData(r), "Pass","Symbols saved", CHART[2])
            ));
            root.add(Box.createVerticalStrut(12));
        }

        // Row 2: Bigrams before  |  Bigrams after
        root.add(chartRow(
            bigramChart("Top Bigrams -- Before Compression", r.bigramsInitial, CHART[0]),
            bigramChart("Top Bigrams -- After Compression",  r.bigrams_final(),  CHART[1])
        ));
        root.add(Box.createVerticalStrut(12));

        // Row 3: Time per pass  |  Character distribution
        if (hasCompress) {
            root.add(chartRow(
                barChart("Time per Pass (us)", timeData(r), "Pass","us", CHART[3]),
                charDistChart("Character Frequency Distribution", r.charFreqs)
            ));
            root.add(Box.createVerticalStrut(12));
        } else {
            root.add(chartRow(
                charDistChart("Character Frequency Distribution", r.charFreqs),
                efficiencyChart("Pass Efficiency  (size saved vs time)", r)
            ));
            root.add(Box.createVerticalStrut(12));
        }

        // Row 4: Rule count  |  Efficiency scatter
        if (hasCompress) {
            root.add(chartRow(
                lineChart("Rule Count over Passes",
                    ruleCountData(r), new String[]{"Rules created"},
                    new Color[]{CHART[4]}, "Pass","Rules"),
                efficiencyChart("Pass Efficiency  (size saved vs time)", r)
            ));
            root.add(Box.createVerticalStrut(12));
        }

        // Row 5: Summary table  |  Pass table
        root.add(chartRow(summaryTable(r), passTable(r)));

        JScrollPane scroll = new JScrollPane(root);
        scroll.setBackground(C_BG); scroll.getViewport().setBackground(C_BG);
        scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(22);
        return scroll;
    }

    // -- Summary cards row ---------------------------------------------------
    private JPanel summaryCards(TechnicalReport r) {
        JPanel p = new JPanel(new GridLayout(1, 6, 8, 0));
        p.setBackground(C_BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
        double ratio = (double) r.initialSize / Math.max(1, r.compressedSize);
        double pct   = 100.0 * (1 - (double) r.compressedSize / r.initialSize);
        p.add(kpiCard("Input Length",    fmt(r.inputLength) + " chars",  CHART[0]));
        p.add(kpiCard("Passes",          "" + r.compPasses.size(),       CHART[2]));
        p.add(kpiCard("Compressed Size", fmt(r.compressedSize) + " syms",CHART[3]));
        p.add(kpiCard("Ratio",           String.format("%.2fx  (%.0f%%)", ratio, pct), C_SEL));
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

    // -- Data helpers for charts -----------------------------------------------

    private double[][][] sizeSeriesData(TechnicalReport r, boolean includeRecomp) {
        double[] x0 = new double[r.compPasses.size()+1], y0 = new double[r.compPasses.size()+1];
        x0[0]=0; y0[0]=r.initialSize;
        for (int i=0;i<r.compPasses.size();i++){x0[i+1]=r.compPasses.get(i).pass(); y0[i+1]=r.compPasses.get(i).grammarSize();}
        if (!includeRecomp || r.recompPasses.isEmpty()) return new double[][][]{{x0,y0}};
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
                int lx=ml+6,ly=mt+12;
                for(int i=0;i<names.length&&i<data.length;i++){
                    g.setColor(colors[i%colors.length]); g.fillRect(lx,ly-6,14,3);
                    g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(C_DIM);
                    g.drawString(names[i],lx+18,ly);
                    lx+=g.getFontMetrics().stringWidth(names[i])+38;
                }
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

    private JPanel bigramChart(String title, Map<Pair,Integer> freqs, Color col) {
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
                List<Map.Entry<Pair,Integer>> sorted=new ArrayList<>(freqs.entrySet());
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
                    g.setColor(C_DIM); g.drawString("x"+freq,bx+bw+4,by+bh-1);
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
                g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(C_DIM);
                g.drawString("time (us) ->",ml+cw-50,mt+ch+22);
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

    // ── Summary table (JTable) ───────────────────────────────────────────────

    private JPanel summaryTable(TechnicalReport r) {
        double ratio = (double)r.initialSize / Math.max(1, r.compressedSize);
        double pct   = 100*(1-(double)r.compressedSize/r.initialSize);
        double rratio = (double)r.extractInitSize / Math.max(1, r.recompFinalSize);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Input length",            fmt(r.inputLength) + " chars"});
        rows.add(new String[]{"Unique chars",            "" + r.uniqueChars});
        rows.add(new String[]{"Shannon entropy",         String.format("%.4f bits/char", r.entropy)});
        rows.add(new String[]{"Initial grammar size",    fmt(r.initialSize) + " symbols"});
        rows.add(new String[]{"Compressed grammar size", fmt(r.compressedSize) + " symbols"});
        rows.add(new String[]{"Compression ratio",       String.format("%.3fx  (%.1f%% smaller)", ratio, pct)});
        rows.add(new String[]{"Compression passes",      "" + r.compPasses.size()});
        if (!r.compPasses.isEmpty())
            rows.add(new String[]{"Avg size/pass",       fmt((long)r.compPasses.stream().mapToInt(PassStats::saved).average().orElse(0)) + " syms"});
        rows.add(new String[]{"Compression time",        String.format("%.2f ms", r.compTimeNs/1e6)});
        if (r.extractInitSize > 0) {
            rows.add(new String[]{"Extract [from,to)",   "[" + r.extractFrom + ", " + r.extractTo + ")  = " + r.extractLen + " chars"});
            rows.add(new String[]{"Extract grammar size", fmt(r.extractInitSize) + " symbols"});
        }
        if (!r.recompPasses.isEmpty()) {
            rows.add(new String[]{"Recomp passes",       "" + r.recompPasses.size()});
            rows.add(new String[]{"Recomp final size",   fmt(r.recompFinalSize) + " symbols"});
            rows.add(new String[]{"Recomp ratio",        String.format("%.3fx", rratio)});
        }
        rows.add(new String[]{"Total time",              String.format("%.2f ms", r.totalTimeNs/1e6)});

        String[][] data = rows.toArray(new String[0][]);
        String[] cols = {"Metric", "Value"};
        return buildDarkTable(cols, data, new int[]{200, 300}, null);
    }

    // ── Pass table (JTable) ──────────────────────────────────────────────────

    private JPanel passTable(TechnicalReport r) {
        List<PassStats> ps = r.compPasses;
        String[] cols = {"#", "Size", "Saved", "Time (us)", "Bigram", "Freq"};
        String[][] data = new String[ps.size()][6];
        for (int i = 0; i < ps.size(); i++) {
            PassStats p = ps.get(i);
            data[i] = new String[]{
                "" + p.pass(),
                fmt(p.grammarSize()),
                "-" + fmt(p.saved()),
                fmt((long)(p.timeNs()/1000)),
                p.bigramLabel().length() > 16 ? p.bigramLabel().substring(0, 15) + "..." : p.bigramLabel(),
                "x" + fmt(p.bigramFreq())
            };
        }
        Color[] colColors = {C_TEXT, C_TEXT, CHART[2], C_DIM, C_TEXT, C_SEL};
        return buildDarkTable(cols, data, new int[]{35, 65, 55, 70, 130, 50}, colColors);
    }

    private JPanel buildDarkTable(String[] cols, String[][] data, int[] colWidths, Color[] colColors) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(C_PANEL);
        wrapper.setPreferredSize(new Dimension(550, 290));
        wrapper.setBorder(BorderFactory.createLineBorder(C_BORDER));

        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setBackground(C_PANEL);
        table.setForeground(C_TEXT);
        table.setGridColor(new Color(0x28, 0x28, 0x48));
        table.setSelectionBackground(C_BLUE);
        table.setSelectionForeground(C_TEXT);
        table.setFont(new Font("Monospaced", Font.PLAIN, 11));
        table.setRowHeight(20);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);

        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(0x20, 0x20, 0x38));
        header.setForeground(C_DIM);
        header.setFont(new Font("SansSerif", Font.BOLD, 10));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        header.setReorderingAllowed(false);

        if (colWidths != null) {
            for (int i = 0; i < Math.min(colWidths.length, table.getColumnCount()); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(colWidths[i]);
            }
        }

        if (colColors != null) {
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(
                        JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                    JLabel c = (JLabel) super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                    c.setBackground(row % 2 == 0 ? C_PANEL : new Color(0x1A, 0x1A, 0x30));
                    c.setForeground(sel ? C_TEXT : (col < colColors.length ? colColors[col] : C_TEXT));
                    c.setFont(new Font("Monospaced", Font.PLAIN, 11));
                    c.setBorder(new EmptyBorder(0, 4, 0, 4));
                    return c;
                }
            });
        } else {
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(
                        JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                    JLabel c = (JLabel) super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                    c.setBackground(row % 2 == 0 ? C_PANEL : new Color(0x1A, 0x1A, 0x30));
                    c.setForeground(sel ? C_TEXT : (col == 0 ? C_DIM : C_TEXT));
                    c.setFont(col == 0 ? new Font("SansSerif", Font.PLAIN, 11) : new Font("Monospaced", Font.BOLD, 11));
                    c.setBorder(new EmptyBorder(0, 6, 0, 6));
                    return c;
                }
            });
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBackground(C_PANEL);
        scroll.getViewport().setBackground(C_PANEL);
        scroll.setBorder(null);

        String title = colColors != null ? "Pass-by-Pass Details (" + data.length + " passes)" : "Summary Metrics";
        wrapper.add(sectionHdr("  " + title), BorderLayout.NORTH);
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
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
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 10)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setPaint(new GradientPaint(0, 0, C_PANEL, getWidth(), 0, new Color(0x1E, 0x1E, 0x3C)));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 14)); l.setForeground(C_TEXT);
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
        f.setFont(new Font("Monospaced", Font.PLAIN, 12));
        if (f instanceof JComboBox) return;
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true),
            new EmptyBorder(3, 8, 3, 8)));
        if (f instanceof JTextField tf) {
            tf.setCaretColor(C_TEXT);
        }
        if (f instanceof JSpinner sp) {
            JComponent ed = sp.getEditor(); ed.setBackground(C_SURF);
            if (ed instanceof JSpinner.DefaultEditor de) {
                de.getTextField().setBackground(C_SURF);
                de.getTextField().setForeground(C_TEXT);
                de.getTextField().setCaretColor(C_TEXT);
                de.getTextField().setBorder(new EmptyBorder(2, 6, 2, 6));
            }
        }
    }

    private JButton plainBtn(String text) {
        JButton b = new JButton(text);
        Color normal = new Color(0x28, 0x28, 0x4C);
        Color hover  = new Color(0x34, 0x34, 0x62);
        b.setBackground(normal); b.setForeground(C_TEXT);
        b.setFont(new Font("SansSerif", Font.PLAIN, 12)); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true), new EmptyBorder(7, 16, 7, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(hover); }
            public void mouseExited(MouseEvent e)  { b.setBackground(normal); }
        });
        return b;
    }

    private JButton accentBtn(String text) {
        JButton b = new JButton(text);
        Color normal = C_ACCENT;
        Color hover  = new Color(
            Math.min(255, normal.getRed() + 30),
            Math.min(255, normal.getGreen() + 15),
            Math.min(255, normal.getBlue() + 15));
        b.setBackground(normal); b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 12)); b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_ACCENT.darker(), 1, true), new EmptyBorder(7, 20, 7, 20)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(hover); }
            public void mouseExited(MouseEvent e)  { b.setBackground(normal); }
        });
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
