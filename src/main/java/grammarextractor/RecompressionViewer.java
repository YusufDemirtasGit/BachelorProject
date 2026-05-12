package grammarextractor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Step-by-step animated visualizer for the RePair recompression algorithm.
 *
 * Pre-computes all algorithm states, then replays them with smooth animations.
 * Each "step" is one of: INIT → FREQ → SELECT → UNCROSS → REPLACE → ... → FINAL.
 *
 * Launch standalone: java -cp build/classes/java/main grammarextractor.RecompressionViewer
 * Or call new RecompressionViewer(grammar, maxPasses).setVisible(true) from code.
 */
public class RecompressionViewer extends JFrame {

    // ── Color palette (dark theme) ─────────────────────────────────────────────
    static final Color C_BG       = new Color(0x14, 0x14, 0x26);
    static final Color C_PANEL    = new Color(0x1C, 0x1C, 0x34);
    static final Color C_SURFACE  = new Color(0x22, 0x22, 0x3E);
    static final Color C_BORDER   = new Color(0x38, 0x38, 0x6A);
    static final Color C_TEXT     = new Color(0xE6, 0xE6, 0xF2);
    static final Color C_DIM      = new Color(0x72, 0x72, 0x90);
    static final Color C_TERM     = new Color(0x18, 0x34, 0x56);   // terminal symbol box
    static final Color C_NT       = new Color(0x38, 0x1A, 0x58);   // non-terminal symbol box
    static final Color C_SEL      = new Color(0xFF, 0xC0, 0x20);   // selected bigram highlight
    static final Color C_NEW      = new Color(0x28, 0xFF, 0x88);   // newly created rule
    static final Color C_CHANGED  = new Color(0xFF, 0x60, 0x18);   // rule that just changed
    static final Color C_BAR      = new Color(0x14, 0x60, 0xA8);   // frequency bar
    static final Color C_BAR_SEL  = new Color(0xE9, 0x40, 0x58);   // selected frequency bar
    static final Color C_BTN      = new Color(0x28, 0x28, 0x4C);

    // ── Per-step snapshot ──────────────────────────────────────────────────────
    record StepSnapshot(
        int    pass,
        String phase,           // INIT | FREQ | SELECT | UNCROSS | REPLACE | DONE | FINAL
        Map<Integer, List<Integer>>          rules,
        List<Integer>                        sequence,
        Pair               selectedBigram,
        Map<Pair, Integer> frequencies,
        int    newRuleId,
        int    grammarSize,
        String description
    ) {}

    // ── Runtime state ──────────────────────────────────────────────────────────
    private final List<StepSnapshot> steps = new ArrayList<>();
    private int     cur          = 0;
    private boolean playing      = false;
    private float   animT        = 1f;     // 0→1: transition progress driven by the 30fps timer
    private Set<Integer> changedRuleIds = new HashSet<>();
    private long    lastAdvanceMs = 0;
    private int     stepDelayMs  = 700;

    // ── Panels ─────────────────────────────────────────────────────────────────
    private GrammarPanel  grammarPanel;
    private FreqPanel     freqPanel;
    private StatsPanel    statsPanel;
    private JLabel        phaseLabel;
    private JButton       playBtn;
    private JProgressBar  progress;

    // ═══════════════════════════════════════════════════════════════════════════
    //  Entry points
    // ═══════════════════════════════════════════════════════════════════════════

    /** Standalone launch — loads Test_from_paper.txt from the working directory. */
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args.length > 0 ? args[0] : "Test_from_paper.txt");
        Parser.ParsedGrammar g = Parser.parseFile(p);
        int passes = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        SwingUtilities.invokeLater(() -> new RecompressionViewer(g, passes).setVisible(true));
    }

    public RecompressionViewer(Parser.ParsedGrammar grammar, int maxPasses) {
        super("RePair Recompression Visualizer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        captureSteps(grammar, maxPasses == 0 ? 80 : maxPasses);
        buildUI();
        setSize(1380, 800);
        setLocationRelativeTo(null);
        showStep(0);
        // 30fps animation tick
        new javax.swing.Timer(33, e -> tick()).start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Step capture — mirrors the recompressNTimes loop with state snapshots
    // ═══════════════════════════════════════════════════════════════════════════

    private void captureSteps(Parser.ParsedGrammar orig, int maxPasses) {
        Recompressor.InitializedGrammar init = Recompressor.initializeWithSentinelsAndRootRule(orig);
        Map<Integer, List<Integer>> rules = new LinkedHashMap<>(init.grammar().grammarRules());
        List<Integer> seq  = new ArrayList<>(init.grammar().sequence());
        Set<Integer>  art  = new HashSet<>(init.artificialTerminals());
        Map<Integer, List<Integer>> artRules = new LinkedHashMap<>();
        int nextId = rules.keySet().stream().max(Integer::compareTo).orElse(255) + 1;

        Map<Integer, RuleMetadata> meta = meta(rules, seq, art);
        snap(0, "INIT", rules, seq, null, Map.of(), -1, meta, "Grammar initialized with sentinels '#' and '$'");

        for (int pass = 1; pass <= maxPasses; pass++) {
            meta = meta(rules, seq, art);
            Parser.ParsedGrammar wg = new Parser.ParsedGrammar(rules, seq, meta);

            // ① compute frequencies
            Map<Pair, Integer> freqs =
                Recompressor.computeBigramFrequencies(wg, art, false, null);
            snap(pass, "FREQ", rules, seq, null, freqs, -1, meta,
                "Pass " + pass + ": " + freqs.size() + " distinct bigrams");

            if (freqs.isEmpty()) {
                snap(pass, "DONE", rules, seq, null, Map.of(), -1, meta, "No bigrams found — compression complete");
                break;
            }

            // ② select most frequent
            Pair bg = Recompressor.getMostFrequentBigram(freqs, art);
            if (bg == null || freqs.getOrDefault(bg, 0) <= 1) {
                snap(pass, "DONE", rules, seq, bg, freqs, -1, meta, "All frequencies ≤ 1 — done");
                break;
            }
            snap(pass, "SELECT", rules, seq, bg, freqs, -1, meta,
                "Pass " + pass + ": selected (" + sym(bg.first) + ", " + sym(bg.second)
                + ")  ×" + freqs.get(bg));

            // ③ uncross
            Recompressor.uncrossBigrams(bg.first, bg.second, rules, meta, art);
            meta = meta(rules, seq, art);
            snap(pass, "UNCROSS", rules, seq, bg, freqs, -1, meta,
                "Pass " + pass + ": bigram uncrossed (boundaries made explicit)");

            // ④ replace
            int newId = nextId++;
            Recompressor.replaceBigramInRules(bg.first, bg.second, newId, rules, art);
            artRules.put(newId, List.of(bg.first, bg.second));
            art.add(newId);
            Recompressor.removeRedundantRules(rules, seq);
            meta = meta(rules, seq, art);
            snap(pass, "REPLACE", rules, seq, bg, freqs, newId, meta,
                "Pass " + pass + ": R" + newId + " ← (" + sym(bg.first) + ", " + sym(bg.second) + ")");
        }

        // Final combined grammar
        Map<Integer, List<Integer>> fin = new LinkedHashMap<>(rules);
        fin.putAll(artRules);
        meta = meta(fin, seq, art);
        snap(-1, "FINAL", fin, seq, null, Map.of(), -1, meta, "Final compressed grammar");
    }

    /** Compute metadata helper. */
    private static Map<Integer, RuleMetadata> meta(
            Map<Integer, List<Integer>> rules, List<Integer> seq, Set<Integer> art) {
        return RuleMetadata.computeAll(
            new Parser.ParsedGrammar(rules, seq, Collections.emptyMap()), art);
    }

    /** Deep-copy current state into a new snapshot record. */
    private void snap(int pass, String phase,
                      Map<Integer, List<Integer>> rules, List<Integer> seq,
                      Pair bigram, Map<Pair,Integer> freqs,
                      int newId, Map<Integer, RuleMetadata> ignored, String desc) {
        Map<Integer, List<Integer>> rc = new LinkedHashMap<>();
        rules.forEach((k, v) -> rc.put(k, new ArrayList<>(v)));
        int sz = rc.values().stream().mapToInt(List::size).sum() + seq.size();
        steps.add(new StepSnapshot(pass, phase, rc, new ArrayList<>(seq),
            bigram, new HashMap<>(freqs), newId, sz, desc));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UI construction
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        getContentPane().setBackground(C_BG);
        setLayout(new BorderLayout(5, 5));

        add(buildControlBar(), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(1, 3, 5, 0));
        center.setBackground(C_BG);
        center.setBorder(new EmptyBorder(0, 5, 0, 5));
        grammarPanel = new GrammarPanel();
        freqPanel    = new FreqPanel();
        statsPanel   = new StatsPanel();
        center.add(grammarPanel);
        center.add(freqPanel);
        center.add(statsPanel);
        add(center, BorderLayout.CENTER);

        progress = new JProgressBar(0, Math.max(1, steps.size() - 1));
        progress.setForeground(C_BAR_SEL);
        progress.setBackground(C_PANEL);
        progress.setBorder(new EmptyBorder(2, 5, 4, 5));
        add(progress, BorderLayout.SOUTH);
    }

    private JPanel buildControlBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        bar.setBackground(C_PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));

        bar.add(label("⚙  RePair Visualizer", C_TEXT, 15, Font.BOLD));
        bar.add(Box.createHorizontalStrut(20));

        phaseLabel = label("", C_SEL, 12, Font.PLAIN);
        bar.add(phaseLabel);
        bar.add(Box.createHorizontalStrut(20));

        JButton resetBtn = btn("⏮");
        JButton backBtn  = btn("◀");
        JButton stepBtn  = btn("▶");
        playBtn          = btn("⏵  Play");

        resetBtn.addActionListener(e -> go(0));
        backBtn .addActionListener(e -> { stopPlay(); if (cur > 0) showStep(cur - 1); });
        stepBtn .addActionListener(e -> { stopPlay(); advance(); });
        playBtn .addActionListener(e -> {
            playing = !playing;
            playBtn.setText(playing ? "⏸  Pause" : "⏵  Play");
            if (playing) lastAdvanceMs = System.currentTimeMillis();
        });
        bar.add(resetBtn); bar.add(backBtn); bar.add(stepBtn); bar.add(playBtn);

        bar.add(Box.createHorizontalStrut(12));
        bar.add(label("Speed:", C_DIM, 11, Font.PLAIN));
        JSlider spd = new JSlider(120, 2500, stepDelayMs);
        spd.setBackground(C_PANEL); spd.setPreferredSize(new Dimension(110, 24));
        spd.setInverted(true);
        spd.addChangeListener(e -> stepDelayMs = spd.getValue());
        bar.add(spd);

        return bar;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Animation loop
    // ═══════════════════════════════════════════════════════════════════════════

    private void tick() {
        boolean dirty = false;
        if (animT < 1f) { animT = Math.min(1f, animT + 0.06f); dirty = true; }
        if (playing) {
            long now = System.currentTimeMillis();
            if (now - lastAdvanceMs >= stepDelayMs) {
                if (cur < steps.size() - 1) { advance(); lastAdvanceMs = now; }
                else { stopPlay(); }
                dirty = true;
            }
        }
        if (dirty) { grammarPanel.repaint(); freqPanel.repaint(); statsPanel.repaint(); }
    }

    private void advance() { if (cur < steps.size() - 1) showStep(cur + 1); }
    private void go(int idx) { stopPlay(); showStep(idx); }
    private void stopPlay() { playing = false; playBtn.setText("⏵  Play"); }

    private void showStep(int idx) {
        StepSnapshot prev = (cur < steps.size()) ? steps.get(cur) : null;
        cur = idx;
        StepSnapshot snap = steps.get(idx);

        // Detect changed rules for the orange flash animation
        changedRuleIds.clear();
        if (prev != null) {
            for (int rid : snap.rules.keySet()) {
                if (!prev.rules.containsKey(rid) || !prev.rules.get(rid).equals(snap.rules.get(rid)))
                    changedRuleIds.add(rid);
            }
        }

        animT = 0f;
        phaseLabel.setText("[" + snap.phase + "]   " + snap.description);
        progress.setValue(idx);
        grammarPanel.repaint();
        freqPanel.repaint();
        statsPanel.repaint();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PANEL: Grammar Rules (left)
    // ═══════════════════════════════════════════════════════════════════════════

    class GrammarPanel extends JPanel {
        private final Canvas canvas = new Canvas();
        private final JScrollPane scroll;

        GrammarPanel() {
            setLayout(new BorderLayout()); setBackground(C_PANEL);
            setBorder(BorderFactory.createLineBorder(C_BORDER));
            add(header("GRAMMAR  RULES"), BorderLayout.NORTH);
            scroll = new JScrollPane(canvas);
            scroll.setBackground(C_PANEL); scroll.getViewport().setBackground(C_PANEL);
            scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(14);
            add(scroll, BorderLayout.CENTER);
        }

        @Override public void repaint() { super.repaint(); if (canvas != null) canvas.repaint(); }

        class Canvas extends JPanel {
            Canvas() { setBackground(C_PANEL); }

            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                StepSnapshot snap = steps.isEmpty() ? null : steps.get(cur);
                if (snap == null) return;
                Graphics2D g = aa(g0);

                int y = 10, pad = 8;
                // Sequence row first
                y = drawRow(g, -1, snap.sequence, snap, y, pad);
                y += 8;

                // Rule rows sorted by ID
                List<Integer> ids = new ArrayList<>(snap.rules.keySet());
                Collections.sort(ids);
                for (int rid : ids) y = drawRow(g, rid, snap.rules.get(rid), snap, y, pad);

                setPreferredSize(new Dimension(getWidth(), y + 16));
                revalidate();
            }

            private int drawRow(Graphics2D g, int ruleId, List<Integer> syms,
                                StepSnapshot snap, int startY, int pad) {
                final int BW = 36, BH = 23, GAP = 3, ROW_H = 32;
                int x = pad, y = startY;
                boolean isNew  = ruleId == snap.newRuleId;
                boolean isChanged = changedRuleIds.contains(ruleId);

                // Row background glow for new/changed rules
                if (isNew) {
                    g.setColor(new Color(0x0C, 0x30, 0x14));
                    g.fillRoundRect(1, y - 2, getWidth() - 2, ROW_H + 2, 6, 6);
                } else if (isChanged) {
                    float a = (1f - animT) * 0.40f;
                    g.setColor(new Color(1f, 0.35f, 0.06f, a));
                    g.fillRoundRect(1, y - 2, getWidth() - 2, ROW_H + 2, 6, 6);
                }

                // Rule label
                String lbl = ruleId < 0 ? "SEQ:" : "R" + ruleId + ":";
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(isNew ? C_NEW : isChanged ? blend(C_CHANGED, C_DIM, animT) : C_DIM);
                g.drawString(lbl, x, y + 18);
                x += 56;

                for (int i = 0; i < syms.size(); i++) {
                    int s = syms.get(i);
                    // Wrap to next line if not enough space
                    if (x + BW + pad > getWidth()) { x = pad + 56; y += ROW_H; }

                    boolean isSel = snap.selectedBigram != null
                        && (s == snap.selectedBigram.first || s == snap.selectedBigram.second);
                    // Highlight consecutive (c1, c2) pairs — c1 gets a dot connector on the right
                    boolean isPairLeft = isSel && snap.selectedBigram != null
                        && s == snap.selectedBigram.first
                        && i + 1 < syms.size() && syms.get(i + 1) == snap.selectedBigram.second;

                    // Box background
                    Color bg = s >= 256 ? C_NT : C_TERM;
                    if (isSel)               bg = blend(bg, C_SEL,  0.45f + 0.35f * animT);
                    if (s == snap.newRuleId) bg = blend(bg, C_NEW,  0.55f);

                    g.setColor(bg);
                    g.fillRoundRect(x, y + 4, BW, BH, 5, 5);

                    // Border
                    Color brd = isSel ? blend(C_SEL, Color.WHITE, 0.2f)
                        : s >= 256 ? new Color(0x66, 0x40, 0xA0) : new Color(0x38, 0x60, 0x90);
                    g.setColor(brd);
                    g.setStroke(new BasicStroke(isSel ? 1.8f : 1f));
                    g.drawRoundRect(x, y + 4, BW, BH, 5, 5);
                    g.setStroke(new BasicStroke(1f));

                    // Connector dot between consecutive bigram pair
                    if (isPairLeft) {
                        g.setColor(C_SEL);
                        g.fillOval(x + BW + 1, y + 4 + BH/2 - 3, 6, 6);
                    }

                    // Symbol text
                    String txt = symShort(s);
                    g.setFont(new Font("Monospaced", Font.BOLD, s >= 256 ? 9 : 11));
                    g.setColor(isSel ? Color.BLACK : C_TEXT);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(txt,
                        x + (BW - fm.stringWidth(txt)) / 2,
                        y + 4 + (BH + fm.getAscent() - fm.getDescent()) / 2);

                    x += BW + GAP;
                }
                return y + ROW_H + 3;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PANEL: Bigram Frequency Chart (center)
    // ═══════════════════════════════════════════════════════════════════════════

    class FreqPanel extends JPanel {
        FreqPanel() {
            setBackground(C_PANEL);
            setBorder(BorderFactory.createLineBorder(C_BORDER));
            setLayout(new BorderLayout());
            add(header("BIGRAM  FREQUENCIES"), BorderLayout.NORTH);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            StepSnapshot snap = steps.isEmpty() ? null : steps.get(cur);
            if (snap == null) return;
            Graphics2D g = aa(g0);

            int hdrH = 32, pad = 14;
            int w = getWidth(), h = getHeight();
            int bot = h - 28;

            if (snap.frequencies.isEmpty()) {
                g.setColor(C_DIM);
                g.setFont(new Font("SansSerif", Font.ITALIC, 12));
                String msg = "( no bigrams to show )";
                g.drawString(msg, w / 2 - g.getFontMetrics().stringWidth(msg) / 2, hdrH + (h - hdrH) / 2);
                return;
            }

            // Sort descending by frequency, top 14
            List<Map.Entry<Pair,Integer>> entries = new ArrayList<>(snap.frequencies.entrySet());
            entries.sort((a, b) -> b.getValue() - a.getValue());
            if (entries.size() > 14) entries = entries.subList(0, 14);

            int maxF = entries.get(0).getValue();
            int n    = entries.size();
            int barW = Math.max(14, (w - 2 * pad) / n - 5);
            int gapX = Math.max(2, ((w - 2 * pad) - n * barW) / (n + 1));
            int chartH = bot - hdrH - 20;

            for (int i = 0; i < n; i++) {
                Pair bg = entries.get(i).getKey();
                int freq = entries.get(i).getValue();
                boolean isSel = bg.equals(snap.selectedBigram);

                float ratio = (float) freq / maxF;
                // Bars grow from 0 → full height during the transition
                int barH = (int)(chartH * ratio * animT);
                int bx   = pad + gapX + i * (barW + gapX);
                int by   = bot - barH;

                // Gradient fill
                Color top_ = isSel ? blend(C_BAR_SEL, new Color(0xFF, 0xFF, 0x80), 0.25f) : blend(C_BAR, C_TEXT, 0.15f);
                Color bot_ = isSel ? C_BAR_SEL : C_BAR;
                g.setPaint(new GradientPaint(bx, by, top_, bx, bot, bot_));
                g.fillRoundRect(bx, by, barW, barH, 4, 4);
                g.setPaint(null);

                if (isSel) {
                    // Glowing border for the selected bigram
                    g.setColor(new Color(C_SEL.getRed(), C_SEL.getGreen(), C_SEL.getBlue(), (int)(180 * animT)));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(bx - 1, by - 1, barW + 2, barH + 2, 4, 4);
                    g.setStroke(new BasicStroke(1f));
                }

                // X-axis label
                g.setFont(new Font("Monospaced", Font.BOLD, 9));
                String lbl = symShort(bg.first) + symShort(bg.second);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(isSel ? C_SEL : C_DIM);
                g.drawString(lbl, bx + (barW - fm.stringWidth(lbl)) / 2, bot + 14);

                // Frequency count above bar
                if (barH > 16) {
                    String fs = "" + freq;
                    g.setColor(C_TEXT);
                    g.drawString(fs, bx + (barW - fm.stringWidth(fs)) / 2, by - 3);
                }
            }

            // Y-axis label
            g.setColor(C_DIM);
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.drawString("freq", pad - 2, hdrH + 22);
            g.drawString("" + maxF, pad - 2, hdrH + 38);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PANEL: Statistics + Sparkline + Log (right)
    // ═══════════════════════════════════════════════════════════════════════════

    class StatsPanel extends JPanel {
        private final List<String> log = new ArrayList<>();
        private int lastLogged = -1;

        StatsPanel() {
            setBackground(C_PANEL);
            setBorder(BorderFactory.createLineBorder(C_BORDER));
            setLayout(new BorderLayout());
            add(header("STATISTICS  &  LOG"), BorderLayout.NORTH);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            StepSnapshot snap = steps.isEmpty() ? null : steps.get(cur);
            if (snap == null) return;
            if (cur != lastLogged) {
                log.add(snap.description);
                if (log.size() > 50) log.remove(0);
                lastLogged = cur;
            }
            Graphics2D g = aa(g0);

            int x = 12, y = 40, w = getWidth();
            Font bf = new Font("SansSerif", Font.BOLD, 12);
            Font nf = new Font("SansSerif", Font.PLAIN, 12);

            row(g, x, y, "Step",         cur + " / " + (steps.size() - 1), bf, nf); y += 20;
            row(g, x, y, "Pass",         snap.pass < 0 ? "FINAL" : "" + snap.pass, bf, nf); y += 20;
            row(g, x, y, "Phase",        snap.phase, bf, nf); y += 20;
            row(g, x, y, "Grammar size", "" + snap.grammarSize, bf, nf); y += 20;
            row(g, x, y, "Rules",        "" + snap.rules.size(), bf, nf); y += 20;

            if (snap.selectedBigram != null) {
                row(g, x, y, "Bigram",
                    sym(snap.selectedBigram.first) + " + " + sym(snap.selectedBigram.second),
                    bf, nf); y += 20;
                row(g, x, y, "Frequency",
                    "" + snap.frequencies.getOrDefault(snap.selectedBigram, 0),
                    bf, nf); y += 20;
            }

            if (snap.newRuleId > 0 && snap.selectedBigram != null) {
                g.setFont(bf); g.setColor(C_DIM); g.drawString("New rule:", x, y);
                g.setFont(nf); g.setColor(C_NEW);
                g.drawString("R" + snap.newRuleId + " → (" + sym(snap.selectedBigram.first)
                    + ", " + sym(snap.selectedBigram.second) + ")", x + 88, y);
                y += 20;
            }

            y += 8;
            divider(g, x, y, w); y += 12;

            // Grammar size sparkline
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(C_DIM); g.drawString("SIZE OVER TIME", x, y); y += 14;
            sparkline(g, x, y, w - 2*x, 52); y += 60;

            divider(g, x, y, w); y += 12;

            // Event log (most recent first)
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(C_DIM); g.drawString("EVENT LOG", x, y); y += 14;

            g.setFont(new Font("Monospaced", Font.PLAIN, 10));
            for (int i = log.size() - 1; i >= 0 && y < getHeight() - 4; i--) {
                boolean curr = (i == log.size() - 1);
                g.setColor(curr ? C_TEXT : C_DIM);
                String line = log.get(i);
                if (line.length() > 33) line = line.substring(0, 31) + "…";
                g.drawString(line, x, y);
                y += 13;
            }
        }

        private void row(Graphics2D g, int x, int y, String k, String v, Font kf, Font vf) {
            g.setFont(kf); g.setColor(C_DIM);   g.drawString(k + ":", x, y);
            g.setFont(vf); g.setColor(C_TEXT);  g.drawString(v, x + 88, y);
        }

        private void divider(Graphics2D g, int x, int y, int w) {
            g.setColor(C_BORDER); g.fillRect(x, y, w - 2*x, 1);
        }

        private void sparkline(Graphics2D g, int x, int y, int w, int h) {
            if (steps.isEmpty()) return;
            int n = steps.size();
            int maxSz = steps.stream().mapToInt(StepSnapshot::grammarSize).max().orElse(1);

            // Background
            g.setColor(new Color(0x18, 0x28, 0x40));
            g.fillRoundRect(x, y, w, h, 4, 4);

            // Line
            int[] px = new int[n], py = new int[n];
            for (int i = 0; i < n; i++) {
                px[i] = x + i * (w - 4) / Math.max(1, n - 1) + 2;
                py[i] = y + h - 4 - (int)((float) steps.get(i).grammarSize / maxSz * (h - 8));
            }
            g.setColor(C_BAR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < n - 1; i++) g.drawLine(px[i], py[i], px[i+1], py[i+1]);
            g.setStroke(new BasicStroke(1f));

            // Current-step dot
            if (cur < n) {
                g.setColor(C_SEL);
                g.fillOval(px[cur] - 4, py[cur] - 4, 8, 8);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private JLabel header(String text) {
        JLabel l = new JLabel("  " + text);
        l.setFont(new Font("SansSerif", Font.BOLD, 11));
        l.setForeground(C_DIM);
        l.setBackground(new Color(0x10, 0x10, 0x22));
        l.setOpaque(true);
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            new EmptyBorder(5, 6, 5, 6)));
        return l;
    }

    private JLabel label(String text, Color c, int size, int style) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", style, size));
        l.setForeground(c);
        return l;
    }

    private JButton btn(String text) {
        JButton b = new JButton(text);
        b.setBackground(C_BTN); b.setForeground(C_TEXT);
        b.setFont(new Font("SansSerif", Font.PLAIN, 12));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true),
            new EmptyBorder(4, 10, 4, 10)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Enable anti-aliasing on a Graphics2D context. */
    private static Graphics2D aa(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** Linear color blend: t=0 → a, t=1 → b. */
    static Color blend(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
            (int)(a.getRed()   + t * (b.getRed()   - a.getRed())),
            (int)(a.getGreen() + t * (b.getGreen() - a.getGreen())),
            (int)(a.getBlue()  + t * (b.getBlue()  - a.getBlue())));
    }

    /** Human-readable symbol name. */
    static String sym(int s) {
        if (s == 35) return "'#'"; if (s == 36) return "'$'";
        if (s < 32)  return "\\x" + Integer.toHexString(s);
        if (s == 32) return "' '";
        if (s < 127) return "'" + (char) s + "'";
        return "R" + s;
    }

    /** Single-character representation for symbol boxes. */
    static String symShort(int s) {
        if (s == 35) return "#"; if (s == 36) return "$";
        if (s < 32)  return "·";
        if (s == 32) return "␣";
        if (s < 127) return String.valueOf((char) s);
        return "R" + s;
    }
}
