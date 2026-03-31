package io.github.sagaraggarwal86.jmeter.bpm.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.core.BpmListener;
import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.output.CsvExporter;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class BpmListenerGui extends AbstractListenerGui implements Clearable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmListenerGui.class);

    private static final Color COLOR_GOOD = new Color(0, 128, 0);
    private static final Color COLOR_NEEDS_WORK = new Color(204, 102, 0);
    private static final Color COLOR_POOR = new Color(204, 0, 0);
    private static final Color ROW_TINT_AMBER = new Color(255, 243, 224);
    private static final Color ROW_TINT_RED = new Color(255, 230, 230);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss").withZone(ZoneId.systemDefault());

    private JTextField filenameField;
    private JButton browseButton;          // CHANGED: field — needed to disable during test run (Feature #2)
    private JTextField startOffsetField;
    private JTextField endOffsetField;
    private ColumnSelectorPopup columnSelector;
    private JTextField transactionNamesField;
    private JCheckBox regexCheckBox;
    private JComboBox<String> includeExcludeCombo;
    private JTextField testStartField;
    private JTextField testEndField;
    private JTextField testDurationField;
    private JTable resultsTable;
    private BpmTableModel tableModel;
    private JLabel scoreLabel;
    private JProgressBar scoreBar;
    private JLabel scoreCategoryLabel;
    private JButton saveTableDataButton;
    private JButton applyFiltersButton;     // CHANGED: Change #2 — Apply Filters button
    private Timer updateTimer;

    private transient BpmListener listenerRef;
    private transient io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager propertiesRef;
    private boolean testRunning;
    private Instant testStartTime;
    /** True while configure() is populating fields — suppresses spurious applyAllFilters() calls
     *  fired by setSelected/setSelectedItem listeners during programmatic configuration. */
    private boolean configuringElement = false; // CHANGED: Defect #2 guard

    /**
     * Set to {@code true} by {@link #createTestElement()} so that the immediately following
     * {@link #configure(TestElement)} call knows it is wiring a brand-new element and must
     * blank the display. Cleared on first configure() use.
     */ // CHANGED: Defect #1 (this session) — new listener must show blank GUI
    private boolean pendingFreshClear = false;

    /** Raw BpmResult records from the current test or loaded file — source of truth for retroactive filter rebuilds. */
    // CHANGED: Defect #2 — enables retroactive offset re-filtering by rebuilding aggregates from raw records
    private final List<BpmResult> allRawResults = new ArrayList<>();

    // All 18 TableColumn objects stored at init — used for add/remove column visibility
    private TableColumn[] allColumns;

    public BpmListenerGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 4));
        setBorder(makeBorder());

        java.awt.Container titlePanel = makeTitlePanel();

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.add(createFileFieldset());
        mainPanel.add(Box.createVerticalStrut(2));
        mainPanel.add(createHelpLink());
        mainPanel.add(Box.createVerticalStrut(2));
        mainPanel.add(createFilterSettingsFieldset());
        mainPanel.add(Box.createVerticalStrut(2));
        mainPanel.add(createTestTimeAndScoreRow());
        mainPanel.add(Box.createVerticalStrut(2));

        tableModel = new BpmTableModel();
        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.setDefaultRenderer(Object.class, new BpmCellRenderer());
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setTableHeader(new TooltipTableHeader(resultsTable.getColumnModel()));

        // Custom row sorter: pins TOTAL to bottom regardless of sort direction,
        // and provides correct per-column comparators for mixed-type columns. // CHANGED: Feature #3 + Bug #1
        TotalPinnedRowSorter sorter = new TotalPinnedRowSorter(tableModel);
        resultsTable.setRowSorter(sorter);

        // Store all columns for add/remove visibility toggling
        TableColumnModel cm = resultsTable.getColumnModel();
        allColumns = new TableColumn[cm.getColumnCount()];
        for (int i = 0; i < allColumns.length; i++) {
            allColumns[i] = cm.getColumn(i);
        }

        JScrollPane scrollPane = new JScrollPane(resultsTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(800, 300));

        // Save button — centered at bottom
        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        saveTableDataButton = new JButton("Save Table Data");
        saveTableDataButton.setEnabled(false);
        saveTableDataButton.addActionListener(e -> saveTableData());
        savePanel.add(saveTableDataButton);

        add(titlePanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(mainPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        centerPanel.add(savePanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        applyColumnVisibility();

        updateTimer = new Timer(BpmConstants.GUI_UPDATE_INTERVAL_MS, e -> drainGuiQueue());
        updateTimer.setRepeats(true);
    }

    private JPanel createFileFieldset() {
        JPanel fieldset = new JPanel(new BorderLayout(4, 0));
        fieldset.setBorder(BorderFactory.createTitledBorder("Write results to file / Read from file"));
        fieldset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        JPanel inner = new JPanel(new BorderLayout(4, 0));
        inner.add(new JLabel("Filename"), BorderLayout.WEST);
        filenameField = new JTextField("", 30); // CHANGED: Feature #2 — initially empty; default resolved at runtime
        filenameField.addActionListener(e -> loadFileFromField()); // CHANGED: Feature #3 — Enter key loads file if it exists
        inner.add(filenameField, BorderLayout.CENTER);
        browseButton = new JButton("Browse..."); // CHANGED: Feature #2 — stored as field for disable during test
        browseButton.addActionListener(e -> browseFile());
        inner.add(browseButton, BorderLayout.EAST);

        fieldset.add(inner, BorderLayout.CENTER);
        return fieldset;
    }

    private JPanel createHelpLink() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        JLabel helpLabel = new JLabel("\u2139 Help on this plugin");
        helpLabel.setForeground(new Color(0, 102, 204));
        helpLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(BpmConstants.HELP_URL));
                } catch (Exception ex) {
                    log.debug("BPM: Failed to open help URL: {}", ex.getMessage());
                }
            }
        });
        panel.add(helpLabel);
        return panel;
    }

    private JPanel createFilterSettingsFieldset() {
        JPanel fieldset = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        fieldset.setBorder(BorderFactory.createTitledBorder("Filter Settings"));
        fieldset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55));

        fieldset.add(new JLabel("Start Offset (s):"));
        startOffsetField = new JTextField(4);
        applyPositiveIntegerFilter(startOffsetField); // CHANGED: Defect #2 — digits-only validation
        fieldset.add(startOffsetField);

        fieldset.add(new JLabel("End Offset (s):"));
        endOffsetField = new JTextField(4);
        applyPositiveIntegerFilter(endOffsetField);   // CHANGED: Defect #2 — digits-only validation
        fieldset.add(endOffsetField);

        columnSelector = new ColumnSelectorPopup(e -> applyColumnVisibility());
        JButton columnsButton = new JButton("Select Columns \u2610");
        columnsButton.addActionListener(e ->
                columnSelector.show(columnsButton, 0, columnsButton.getHeight()));
        fieldset.add(columnsButton);

        fieldset.add(new JLabel("Transaction Names:"));
        transactionNamesField = new JTextField(12);
        fieldset.add(transactionNamesField);

        regexCheckBox = new JCheckBox("RegEx");
        fieldset.add(regexCheckBox);

        includeExcludeCombo = new JComboBox<>(new String[]{"Include", "Exclude"});
        fieldset.add(includeExcludeCombo);

        // CHANGED: Change #2 — Apply Filters button; no auto-filtering on field change.
        // All filtering is deferred until the user explicitly clicks Apply Filters.
        applyFiltersButton = new JButton("Apply Filters");
        applyFiltersButton.addActionListener(e -> applyAllFilters());
        fieldset.add(applyFiltersButton);

        return fieldset;
    }

    private JPanel createTestTimeAndScoreRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55));

        // Test Time Info (left)
        JPanel timeFieldset = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        timeFieldset.setBorder(BorderFactory.createTitledBorder("Test Time Info"));

        timeFieldset.add(new JLabel("Start:"));
        testStartField = new JTextField(14);
        testStartField.setEditable(false);
        timeFieldset.add(testStartField);

        timeFieldset.add(new JLabel("End:"));
        testEndField = new JTextField(14);
        testEndField.setEditable(false);
        timeFieldset.add(testEndField);

        timeFieldset.add(new JLabel("Duration:"));
        testDurationField = new JTextField(8);
        testDurationField.setEditable(false);
        timeFieldset.add(testDurationField);

        // Overall Performance Score (right)
        JPanel scoreFieldset = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        scoreFieldset.setBorder(BorderFactory.createTitledBorder("Overall Performance Score"));

        scoreLabel = new JLabel("\u2014");
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(18f));
        scoreFieldset.add(scoreLabel);

        scoreBar = new JProgressBar(0, 100);
        scoreBar.setValue(0);
        scoreBar.setStringPainted(true);
        scoreBar.setString("\u2014");
        scoreBar.setPreferredSize(new Dimension(180, 16));
        scoreFieldset.add(scoreBar);

        scoreCategoryLabel = new JLabel("Good: 0  Needs Work: 0  Poor: 0");
        scoreFieldset.add(scoreCategoryLabel);

        row.add(timeFieldset);
        row.add(scoreFieldset);
        return row;
    }

    @Override
    public String getLabelResource() { return "bpm_listener_gui"; }

    @Override
    public String getStaticLabel() { return "Browser Performance Metrics"; }

    @Override
    public TestElement createTestElement() {
        // Return a brand-new listener with no data — do NOT wire to any existing active instance. // CHANGED: Bug #2
        BpmListener listener = new BpmListener();
        modifyTestElement(listener);
        // CHANGED: Defect #1 (prev session) — strip filename so every new BPM Listener starts empty.
        // CHANGED: Defect #3 — also strip the five filter properties; modifyTestElement() copies the
        // current GUI state (from a previously-configured listener) into the new element, so without
        // these removes, configure() would inherit the old listener's filter values.
        listener.removeProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH);
        listener.removeProperty(BpmConstants.TEST_ELEMENT_START_OFFSET);
        listener.removeProperty(BpmConstants.TEST_ELEMENT_END_OFFSET);
        listener.removeProperty(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES);
        listener.removeProperty(BpmConstants.TEST_ELEMENT_REGEX);
        listener.removeProperty(BpmConstants.TEST_ELEMENT_INCLUDE);
        pendingFreshClear = true; // CHANGED: Defect #1 — signal configure() to blank display for this new element
        return listener;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        String path = filenameField.getText().trim();
        if (!path.isEmpty()) {
            element.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, path);
        }
        element.setProperty(BpmConstants.TEST_ELEMENT_START_OFFSET, startOffsetField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_END_OFFSET, endOffsetField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES, transactionNamesField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_REGEX, regexCheckBox.isSelected());
        element.setProperty(BpmConstants.TEST_ELEMENT_INCLUDE, "Include".equals(includeExcludeCombo.getSelectedItem()));
    }

    @Override
    public void configure(TestElement element) {
        configuringElement = true; // CHANGED: Defect #2 — suppress applyAllFilters() during field population
        try {
            super.configure(element);
            filenameField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, "")); // CHANGED: Feature #2 — default empty, not DEFAULT_OUTPUT_FILENAME
            startOffsetField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_START_OFFSET, ""));
            endOffsetField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_END_OFFSET, ""));
            transactionNamesField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES, ""));
            regexCheckBox.setSelected(element.getPropertyAsBoolean(BpmConstants.TEST_ELEMENT_REGEX, false));
            includeExcludeCombo.setSelectedItem(
                    element.getPropertyAsBoolean(BpmConstants.TEST_ELEMENT_INCLUDE, true) ? "Include" : "Exclude");
            if (element instanceof BpmListener listener) {
                // CHANGED: Defect #1 (this session) — brand-new element created via createTestElement():
                // clear the shared GUI so the user sees a blank slate, then return.
                // Fields above were already set to empty/default (properties were stripped).
                if (pendingFreshClear) {
                    pendingFreshClear = false;
                    clearDisplayOnly();
                    return;
                }

                // CHANGED: Defects #1 #2 #3 — never call clearDisplayOnly() from configure().
                // Display data (table, score, time fields) persists across Save, navigate-away/back,
                // and test-start configure() calls. Only clearData() (user-initiated) or
                // testStarted() (new run) may clear the display.
                // Use getActiveInstance() — not guiUpdateQueue presence — to determine live state.
                // guiUpdateQueue is transient: null after deserialization even when data still exists.
                BpmListener active = BpmListener.getActiveInstance();
                if (active != null) {
                    // Live test running — wire to the active instance and ensure timer is running.
                    this.listenerRef = active;
                    this.propertiesRef = active.getPropertiesManager();
                    if (!updateTimer.isRunning()) {
                        testRunning = true;
                        updateTimer.start();
                    }
                } else {
                    // No active test — wire to element, leave display data intact.
                    this.listenerRef = listener;
                    this.propertiesRef = listener.getPropertiesManager();
                    if (testRunning) {
                        // Guard: timer may have been started by a prior stale configure() call;
                        // stop it now that we know no test is active.
                        testRunning = false;
                        if (updateTimer.isRunning()) { updateTimer.stop(); }
                        updateFilterFieldsEnabled();
                    }
                }
            }
        } finally {
            configuringElement = false; // CHANGED: Defect #2 — always release guard
        }
    }

    @Override
    public void clearData() {
        // Called by JMeter "Clear" / "Clear All" — reset everything. // CHANGED: Feature #4
        if (listenerRef != null) { listenerRef.clearData(); }
        clearDisplayOnly();
    }

    /**
     * Resets only the GUI display without touching the backend listener state or any output file.
     * Called only from {@link #clearData()} (user-initiated Clear/Clear All).
     * Never called from {@code configure()} — display data must persist across Save and navigate events.
     *
     * <p>Filter fields (start/end offset, transaction names, regex, include/exclude) are reset
     * to their defaults here. The reset is wrapped in a {@code configuringElement} guard to
     * suppress the {@link #applyAllFilters()} listeners that fire on setSelected/setSelectedItem.</p>
     *
     * <p>The filename field is intentionally <em>not</em> cleared.</p>
     *
     * <p>NOTE: clearData()/clearDisplayOnly() never write to or truncate the JSONL file or any
     * other output — only in-memory and UI state is reset.</p>
     */ // CHANGED: Defect #1 filter resets; Defects #1 #2 #3 Javadoc: removed configure() reference
    private void clearDisplayOnly() {
        allRawResults.clear();
        tableModel.clear();
        tableModel.fireTableDataChanged();
        resetScoreBox();
        columnSelector.resetToDefaults();
        applyColumnVisibility();
        saveTableDataButton.setEnabled(false);
        testStartField.setText("");
        testEndField.setText("");
        testDurationField.setText("");
        testStartTime = null;
        // Restore interactive state in case clear is called mid-test
        testRunning = false;
        filenameField.setEditable(true);
        if (browseButton != null) { browseButton.setEnabled(true); }
        updateFilterFieldsEnabled(); // CHANGED: Feature #1 — table is empty after clear, so all filter fields disabled
        // CHANGED: Defect #1 — reset filter fields; guard suppresses applyAllFilters() listeners
        configuringElement = true;
        try {
            startOffsetField.setText("");
            endOffsetField.setText("");
            transactionNamesField.setText("");
            regexCheckBox.setSelected(false);
            includeExcludeCombo.setSelectedIndex(0); // "Include"
        } finally {
            configuringElement = false;
        }
        if (updateTimer != null && updateTimer.isRunning()) { updateTimer.stop(); }
    }

    private void drainGuiQueue() {
        BpmListener listener = BpmListener.getActiveInstance(); // CHANGED: read from the active (initialized) instance — not listenerRef, which may point to the original (uninitialized) test element
        if (listener == null) { listener = this.listenerRef; } // fallback for JSONL read mode (no active test)
        if (listener == null) { return; }

        ConcurrentLinkedQueue<BpmResult> queue = listener.getGuiUpdateQueue();
        if (queue == null) { return; }

        // CHANGED: Feature #1 — update End and Duration continuously every timer tick during live test,
        // regardless of whether new records arrived. Gives real-time elapsed view without waiting for testEnded().
        if (testRunning && testStartTime != null) {
            Instant now = Instant.now();
            testEndField.setText(TIME_FMT.format(now));
            Duration elapsed = Duration.between(testStartTime, now);
            testDurationField.setText(String.format("%dh %dm %ds",
                    elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart()));
        }

        List<BpmResult> batch = new ArrayList<>();
        BpmResult result;
        while ((result = queue.poll()) != null) { batch.add(result); }
        if (batch.isEmpty()) { return; }

        if (testStartTime == null) {
            String ts = batch.get(0).timestamp();
            if (ts != null) {
                try {
                    testStartTime = Instant.parse(ts);
                    testStartField.setText(TIME_FMT.format(testStartTime));
                } catch (Exception ignored) { }
            }
        }

        // CHANGED: Defect #2 — store all incoming records raw; offset filter applied in rebuild,
        // not at ingest, so changing offset retroactively re-filters the entire dataset.
        allRawResults.addAll(batch);
        rebuildTableFromRaw();

        updateScoreBox(listener);
    }

    public void testStarted() {
        if (BpmListener.isDontStartPending()) { return; } // CHANGED: Defect #2 — user chose "Don't Start"; preserve loaded file data
        // CHANGED: Feature #3 (this session) — Append removed; always clear for a new test run
        allRawResults.clear();
        tableModel.clear();
        tableModel.fireTableDataChanged();
        resetScoreBox();
        testEndField.setText("");
        testDurationField.setText("");
        testRunning = true;
        testStartTime = Instant.now();
        testStartField.setText(TIME_FMT.format(testStartTime));
        // Disable file controls during test run // CHANGED: Feature #2 (prev session)
        filenameField.setEditable(false);
        browseButton.setEnabled(false);
        saveTableDataButton.setEnabled(false);
        updateFilterFieldsEnabled(); // CHANGED: Feature #1 — disables start/end offset during test execution
        if (!updateTimer.isRunning()) { updateTimer.start(); }
    }

    public void testEnded() {
        boolean wasRunning = testRunning; // CHANGED: Defect #2 — capture before reset; false when DONT_START (testStarted never set it true)
        testRunning = false;
        // Only update time fields when the test actually ran; preserves loaded file timestamps on DONT_START
        if (wasRunning) { // CHANGED: Defect #2
            Instant endTime = Instant.now();
            testEndField.setText(TIME_FMT.format(endTime));
            if (testStartTime != null) {
                Duration dur = Duration.between(testStartTime, endTime);
                long h = dur.toHours();
                long m = dur.toMinutesPart();
                long s = dur.toSecondsPart();
                testDurationField.setText(String.format("%dh %dm %ds", h, m, s));
            }
        }
        updateTimer.stop();
        drainGuiQueue();
        // Re-enable file controls after test ends // CHANGED: Feature #2 (prev session)
        filenameField.setEditable(true);
        browseButton.setEnabled(true);
        updateFilterFieldsEnabled(); // CHANGED: Feature #1 — replaces explicit startOffsetField.setEnabled(true) / endOffsetField.setEnabled(true)
        saveTableDataButton.setEnabled(tableModel.getRowCount() > 0);
    }

    private void updateScoreBox(BpmListener listener) {
        var aggregates = listener.getLabelAggregates();
        if (aggregates == null || aggregates.isEmpty()) { return; }

        var props = listener.getPropertiesManager();
        int scoreGood = props != null ? props.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
        int scorePoor = props != null ? props.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;

        int totalSamples = 0;
        long totalWeightedScore = 0;
        int totalScoredSamples = 0; // CHANGED: per-action accuracy — only labels with non-null scores
        int goodCount = 0, needsWorkCount = 0, poorCount = 0;

        for (var entry : aggregates.entrySet()) {
            var agg = entry.getValue();
            int samples = agg.getSampleCount();
            Integer score = agg.getAverageScore(); // CHANGED: per-action accuracy — nullable
            totalSamples += samples;
            if (score != null) { // CHANGED: per-action accuracy — skip SPA-stale labels
                totalWeightedScore += (long) score * samples;
                totalScoredSamples += samples;
                if (score >= scoreGood) { goodCount++; }
                else if (score >= scorePoor) { needsWorkCount++; }
                else { poorCount++; }
            }
        }

        if (totalScoredSamples > 0) { // CHANGED: per-action accuracy — show "—" when no scored samples
            int overallScore = (int) (totalWeightedScore / totalScoredSamples);
            scoreLabel.setText(String.valueOf(overallScore));
            scoreBar.setValue(overallScore);
            scoreBar.setString(String.valueOf(overallScore));
            scoreBar.setForeground(overallScore >= scoreGood ? COLOR_GOOD
                    : overallScore >= scorePoor ? COLOR_NEEDS_WORK : COLOR_POOR);
        } else {
            scoreLabel.setText("\u2014");
            scoreBar.setValue(0);
            scoreBar.setString("\u2014");
            scoreBar.setForeground(Color.GRAY);
        }
        scoreCategoryLabel.setText(String.format("Good: %d  Needs Work: %d  Poor: %d",
                goodCount, needsWorkCount, poorCount));
    }

    private void resetScoreBox() {
        scoreLabel.setText("\u2014");
        scoreBar.setValue(0);
        scoreBar.setString("\u2014");
        scoreBar.setForeground(Color.GRAY);
        scoreCategoryLabel.setText("Good: 0  Needs Work: 0  Poor: 0");
    }

    /**
     * Populates (or clears) the Test Time Info fields from the first and last
     * record timestamps that survived the current offset filter.
     * Called from {@link #rebuildTableFromRaw()} when not in a live test so that
     * adjusting Start/End Offset immediately updates the displayed time window.
     */ // CHANGED: Defect #1
    private void updateTimeFieldsFromRaw(Instant first, Instant last) {
        testStartField.setText(first != null ? TIME_FMT.format(first) : "");
        testEndField.setText(last != null ? TIME_FMT.format(last) : "");
        if (first != null && last != null) {
            Duration dur = Duration.between(first, last);
            testDurationField.setText(String.format("%dh %dm %ds",
                    dur.toHours(), dur.toMinutesPart(), dur.toSecondsPart()));
        } else {
            testDurationField.setText("");
        }
    }

    /**
     * Computes and displays the overall performance score directly from the table model rows.
     * Used when no live BpmListener is active (file-load mode and post-filter apply).
     * Mirrors the weighted-average logic in {@link #updateScoreBox(BpmListener)}.
     */ // CHANGED: Defect #1
    private void updateScoreBoxFromTable() {
        int scoreGood = propertiesRef != null ? propertiesRef.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
        int scorePoor = propertiesRef != null ? propertiesRef.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;

        long totalWeightedScore = 0;
        int totalScoredSamples = 0;
        int goodCount = 0, needsWorkCount = 0, poorCount = 0;

        for (RowData row : tableModel.getFilteredRows()) {
            if ("TOTAL".equals(row.label)) { continue; }
            if (row.scoredSampleCount > 0) {
                int score = (int) (row.totalScore / row.scoredSampleCount);
                totalWeightedScore += (long) score * row.scoredSampleCount;
                totalScoredSamples += row.scoredSampleCount;
                if (score >= scoreGood)      { goodCount++; }
                else if (score >= scorePoor) { needsWorkCount++; }
                else                         { poorCount++; }
            }
        }

        if (totalScoredSamples > 0) {
            int overallScore = (int) (totalWeightedScore / totalScoredSamples);
            scoreLabel.setText(String.valueOf(overallScore));
            scoreBar.setValue(overallScore);
            scoreBar.setString(String.valueOf(overallScore));
            scoreBar.setForeground(overallScore >= scoreGood ? COLOR_GOOD
                    : overallScore >= scorePoor ? COLOR_NEEDS_WORK : COLOR_POOR);
        } else {
            resetScoreBox();
        }
        scoreCategoryLabel.setText(String.format("Good: %d  Needs Work: %d  Poor: %d",
                goodCount, needsWorkCount, poorCount));
    }

    /**
     * Rebuilds the table model from raw records, applying the current offset filter.
     * The transaction name filter is applied at view time via {@link BpmTableModel#getFilteredRows()}.
     * Called on every incoming batch and on any filter change for retroactive re-filtering.
     */ // CHANGED: Defect #2 — replaces ingest-time offset filter; resolves Open Item #3
    private void rebuildTableFromRaw() {
        tableModel.clear();
        int startOffset = parseIntSafe(startOffsetField.getText().trim());
        int endOffset   = parseIntSafe(endOffsetField.getText().trim());
        Instant firstFiltered = null; // CHANGED: Defect #1 — track first/last record that survives offset filter
        Instant lastFiltered  = null;
        for (BpmResult r : allRawResults) {
            Instant sampleTime = null;
            if (r.timestamp() != null) {
                try { sampleTime = Instant.parse(r.timestamp()); } catch (Exception ignored) { }
            }
            if (testStartTime != null && (startOffset > 0 || endOffset > 0) && sampleTime != null) {
                long elapsedSec = Duration.between(testStartTime, sampleTime).getSeconds();
                if (startOffset > 0 && elapsedSec < startOffset) { continue; }
                if (endOffset   > 0 && elapsedSec > endOffset)   { continue; }
            }
            // Record passes filter — track first/last timestamps unconditionally for Test Time Info
            if (sampleTime != null) { // CHANGED: Defect #1 — track regardless of testRunning so live-test Start offset update works
                if (firstFiltered == null) { firstFiltered = sampleTime; }
                lastFiltered = sampleTime;
            }
            tableModel.addOrUpdateResult(r);
        }
        tableModel.fireTableDataChanged();
        saveTableDataButton.setEnabled(!testRunning && tableModel.getRowCount() > 0); // CHANGED: Feature #1 — disabled during test execution
        updateFilterFieldsEnabled(); // CHANGED: Feature #1
        if (testRunning) {
            // CHANGED: Defect #1 — when start offset is active during live test, update Start
            // to reflect the first record that entered the window; End/Duration are handled
            // by the continuous timer update in drainGuiQueue() so only Start is touched here.
            if (firstFiltered != null && parseIntSafe(startOffsetField.getText().trim()) > 0) {
                testStartField.setText(TIME_FMT.format(firstFiltered));
            }
        } else {
            updateTimeFieldsFromRaw(firstFiltered, lastFiltered); // CHANGED: Defect #1
            updateScoreBoxFromTable();
        }
    }

    /**
     * Unified filter trigger — sets the transaction name filter on the table model and
     * rebuilds aggregated rows from raw records with the current offset applied.
     * Called on any filter-field change (offset, transaction name, regex, include/exclude).
     */ // CHANGED: Feature #2 — replaces applyTransactionFilter(); now covers both offset and tx-name
    private void applyAllFilters() {
        if (configuringElement) { return; } // CHANGED: Defect #2 — suppress during programmatic configure()
        tableModel.setTransactionFilter(
                transactionNamesField.getText().trim(),
                regexCheckBox.isSelected(),
                "Include".equals(includeExcludeCombo.getSelectedItem()));
        rebuildTableFromRaw();
    }

    /**
     * Loads the JSONL file whose path is in the filename field when Enter is pressed.
     * No-op during a live test or if the path is empty / file does not exist.
     */ // CHANGED: Feature #3 — Enter key on filename field loads file
    private void loadFileFromField() {
        if (testRunning) { return; }
        String path = filenameField.getText().trim();
        if (path.isEmpty()) { return; }
        Path p = Path.of(path);
        if (Files.exists(p)) {
            loadJsonlFile(p);
        }
    }

    /**
     * Restricts a text field to positive integer input (digits 0–9 only).
     * Non-digit characters are silently rejected on every keystroke.
     */ // CHANGED: Defect #2 — validation matching JAAR's offset field behaviour
    private static void applyPositiveIntegerFilter(JTextField field) {
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
                    throws BadLocationException {
                if (text != null && text.chars().allMatch(Character::isDigit)) {
                    super.insertString(fb, offset, text, attr);
                }
            }
            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attr)
                    throws BadLocationException {
                if (text != null && text.chars().allMatch(Character::isDigit)) {
                    super.replace(fb, offset, length, text, attr);
                }
            }
        });
    }

    /**
     * Synchronizes the enabled state of filter fields to the current test-running state and row count.
     * Call this whenever either the row count or {@code testRunning} changes.
     *
     * <ul>
     *   <li>Offset fields — disabled during test execution (Feature #1); enabled otherwise.</li>
     *   <li>Apply Filters — always enabled (Change #2).</li>
     *   <li>Transaction-names, regex, include/exclude — enabled when the table has data.</li>
     * </ul>
     */ // CHANGED: Feature #1; Change #1; Change #2; Defects #1 #2 #3
    private void updateFilterFieldsEnabled() {
        boolean hasRows = tableModel.getRowCount() > 0;
        startOffsetField.setEnabled(!testRunning); // CHANGED: Feature #1 — disabled during test execution
        endOffsetField.setEnabled(!testRunning);   // CHANGED: Feature #1 — disabled during test execution
        transactionNamesField.setEnabled(hasRows);
        regexCheckBox.setEnabled(hasRows);
        includeExcludeCombo.setEnabled(hasRows);
        if (applyFiltersButton != null) { applyFiltersButton.setEnabled(true); } // CHANGED: Change #2 — always enabled
    }

    private void applyColumnVisibility() {
        if (resultsTable == null || allColumns == null) { return; }
        boolean[] rawVisibility = columnSelector.getVisibility();

        // Remove all columns from view
        TableColumnModel cm = resultsTable.getColumnModel();
        while (cm.getColumnCount() > 0) {
            cm.removeColumn(cm.getColumn(0));
        }

        // Add back always-visible columns (0-6)
        for (int i = 0; i < BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT; i++) {
            resultsTable.addColumn(allColumns[i]);
        }

        // Add back selected raw columns (7-14)
        for (int i = 0; i < BpmConstants.RAW_COLUMN_COUNT; i++) {
            if (rawVisibility[i]) {
                resultsTable.addColumn(allColumns[BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT + i]);
            }
        }

        resultsTable.revalidate();
        resultsTable.repaint();
    }

    // getPreferredColumnWidth removed — auto-resize handles widths

    private void browseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("JSONL files (*.jsonl)", "jsonl"));
        int action = testRunning ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (action != JFileChooser.APPROVE_OPTION) { return; }
        String selectedPath = chooser.getSelectedFile().getAbsolutePath();
        filenameField.setText(selectedPath);
        if (!testRunning && Files.exists(Path.of(selectedPath))) {
            loadJsonlFile(Path.of(selectedPath));
        }
    }

    private void loadJsonlFile(Path path) {
        ObjectMapper mapper = new ObjectMapper();
        // CHANGED: Feature #3 — reset raw store and table before loading a new file
        allRawResults.clear();
        tableModel.clear();
        testStartTime = null;
        // Clear display immediately; rebuildTableFromRaw() will repopulate from filtered records
        testStartField.setText("");
        testEndField.setText("");
        testDurationField.setText("");
        resetScoreBox();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) { continue; }
                try {
                    BpmResult result = mapper.readValue(line, BpmResult.class);
                    allRawResults.add(result); // CHANGED: Feature #3 — store raw for retroactive filtering
                    // testStartTime is the offset reference point — always the absolute first record's timestamp
                    if (testStartTime == null && result.timestamp() != null) {
                        try { testStartTime = Instant.parse(result.timestamp()); } catch (Exception ignored) { }
                    }
                } catch (Exception e) {
                    log.warn("BPM: Skipping malformed JSONL line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("BPM: Failed to load JSONL file: {}", path, e);
            return;
        }

        // CHANGED: Defect #1 — time fields and score are populated inside rebuildTableFromRaw()
        // using first/last timestamps of records that survive the current offset filter.
        rebuildTableFromRaw();
    }

    private void saveTableData() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        chooser.setSelectedFile(new java.io.File("bpm-results.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        Path outputPath = chooser.getSelectedFile().toPath();
        List<String> headers = new ArrayList<>();
        List<Integer> visibleModelIndices = new ArrayList<>();
        boolean[] rawVisibility = columnSelector.getVisibility();
        for (int i = 0; i < BpmConstants.TOTAL_COLUMN_COUNT; i++) {
            if (i < BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT) {
                headers.add(BpmConstants.ALL_COLUMN_HEADERS[i]);
                visibleModelIndices.add(i);
            } else {
                int rawIdx = i - BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT;
                if (rawVisibility[rawIdx]) {
                    headers.add(BpmConstants.ALL_COLUMN_HEADERS[i]);
                    visibleModelIndices.add(i);
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (int row = 0; row < tableModel.getFilteredRowCount(); row++) {
            List<String> rowData = new ArrayList<>();
            for (int colIdx : visibleModelIndices) {
                Object val = tableModel.getFilteredValueAt(row, colIdx);
                rowData.add(val != null ? val.toString() : "");
            }
            rows.add(rowData);
        }
        try {
            CsvExporter.export(outputPath, headers, rows);
            log.info("BPM: Table data saved to {}", outputPath);
        } catch (IOException e) {
            log.warn("BPM: Failed to save CSV: {}", outputPath, e);
        }
    }

    private static int parseIntSafe(String text) {
        if (text == null || text.isEmpty()) { return 0; }
        try { return Integer.parseInt(text); }
        catch (NumberFormatException e) { return 0; }
    }

    static class BpmTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final LinkedHashMap<String, RowData> rows = new LinkedHashMap<>();
        private String txPattern;
        private boolean txRegex;
        private boolean txInclude = true;
        private List<RowData> filteredRows;

        @Override public int getRowCount() { return getFilteredRows().size(); }
        @Override public int getColumnCount() { return BpmConstants.TOTAL_COLUMN_COUNT; }
        @Override public String getColumnName(int column) { return BpmConstants.ALL_COLUMN_HEADERS[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) { return getFilteredValueAt(rowIndex, columnIndex); }

        /**
         * Declares per-column types so the row sorter picks the right comparator. // CHANGED: Bug #1
         * String is used for formatted columns (ratio, headroom, stability, improvement area)
         * so the custom TotalPinnedRowSorter comparators can handle them correctly.
         */
        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case BpmConstants.COL_IDX_LABEL,
                     BpmConstants.COL_IDX_SERVER_RATIO,
                     BpmConstants.COL_IDX_HEADROOM,
                     BpmConstants.COL_IDX_STABILITY,
                     BpmConstants.COL_IDX_IMPROVEMENT_AREA,
                     BpmConstants.COL_IDX_CLS -> String.class;
                default -> Object.class; // TotalPinnedRowSorter handles numeric vs "—" per column
            };
        }

        Object getFilteredValueAt(int rowIndex, int columnIndex) {
            List<RowData> filtered = getFilteredRows();
            if (rowIndex < 0 || rowIndex >= filtered.size()) { return null; }
            return filtered.get(rowIndex).getColumn(columnIndex);
        }

        int getFilteredRowCount() { return getFilteredRows().size(); }

        void setTransactionFilter(String pattern, boolean useRegex, boolean include) {
            this.txPattern = (pattern != null && !pattern.isEmpty()) ? pattern : null;
            this.txRegex = useRegex;
            this.txInclude = include;
            this.filteredRows = null;
        }

        void addOrUpdateResult(BpmResult result) {
            String label = result.samplerLabel();
            RowData row = rows.computeIfAbsent(label, k -> new RowData(label));
            row.update(result);
            filteredRows = null;
        }

        void clear() { rows.clear(); filteredRows = null; }

        List<RowData> getFilteredRows() {
            if (filteredRows != null) { return filteredRows; }
            List<RowData> result = new ArrayList<>();
            for (RowData row : rows.values()) {
                if (txPattern != null) {
                    boolean matches = matchesTransaction(row.label, txPattern, txRegex);
                    if (txInclude && !matches) { continue; }
                    if (!txInclude && matches) { continue; }
                }
                result.add(row);
            }
            if (!result.isEmpty()) { result.add(computeTotalRow(result)); }
            filteredRows = result;
            return filteredRows;
        }

        private RowData computeTotalRow(List<RowData> sourceRows) {
            RowData total = new RowData("TOTAL");
            for (RowData row : sourceRows) { total.mergeFrom(row); }
            return total;
        }

        /**
         * Returns {@code true} if {@code label} matches {@code pattern} using either regex or
         * substring matching, depending on {@code useRegex}. An empty or null pattern always matches.
         */
        private static boolean matchesTransaction(String label, String pattern, boolean useRegex) { // CHANGED: moved from BpmListenerGui; made static to match static-class context
            if (pattern == null || pattern.isEmpty()) { return true; }
            if (useRegex) {
                try { return Pattern.compile(pattern).matcher(label).find(); }
                catch (PatternSyntaxException e) { return label.contains(pattern); }
            }
            return label.contains(pattern);
        }
    }

    static class RowData {
        final String label;
        int sampleCount;
        long totalScore;
        int scoredSampleCount; // CHANGED: per-action accuracy
        long totalRenderTime;
        double totalServerRatio;
        long totalFrontendTime;   // CHANGED: new
        int frontendTimeCount;    // CHANGED: new
        long totalFcpLcpGap;
        String lastStabilityCategory = null; // CHANGED: new
        long totalHeadroom;       // CHANGED: new
        int headroomCount;        // CHANGED: new
        long totalFcp;
        long totalLcp;
        double totalCls;
        long totalTtfb;
        int totalRequests;
        long totalBytes;
        int totalErrors;
        int totalWarnings;
        String lastImprovementArea = BpmConstants.BOTTLENECK_NONE; // CHANGED: renamed

        RowData(String label) { this.label = label; }

        void update(BpmResult result) {
            sampleCount++;
            DerivedMetrics d = result.derived();
            if (d != null) {
                if (d.performanceScore() != null) {
                    totalScore += d.performanceScore();
                    scoredSampleCount++;
                }
                totalRenderTime += d.renderTime();
                totalServerRatio += d.serverClientRatio();
                totalFcpLcpGap += d.fcpLcpGap();
                if (d.frontendTime() != null) { totalFrontendTime += d.frontendTime(); frontendTimeCount++; } // CHANGED: new
                if (d.stabilityCategory() != null) { lastStabilityCategory = d.stabilityCategory(); }        // CHANGED: new
                if (d.headroom() != null) { totalHeadroom += d.headroom(); headroomCount++; }                  // CHANGED: new
                if (!BpmConstants.BOTTLENECK_NONE.equals(d.improvementArea())) {                               // CHANGED: renamed
                    lastImprovementArea = d.improvementArea();
                }
            }
            WebVitalsResult v = result.webVitals();
            if (v != null) {
                totalFcp  += v.fcp()  != null ? v.fcp()  : 0L;
                totalLcp  += v.lcp()  != null ? v.lcp()  : 0L;
                totalCls  += v.cls()  != null ? v.cls()  : 0.0;
                totalTtfb += v.ttfb() != null ? v.ttfb() : 0L;
            }
            NetworkResult n = result.network();
            if (n != null) { totalRequests += n.totalRequests(); totalBytes += n.totalBytes(); }
            ConsoleResult c = result.console();
            if (c != null) { totalErrors += c.errors(); totalWarnings += c.warnings(); }
        }

        void mergeFrom(RowData other) {
            sampleCount += other.sampleCount;
            totalScore += other.totalScore;
            scoredSampleCount += other.scoredSampleCount;
            totalRenderTime += other.totalRenderTime;
            totalServerRatio += other.totalServerRatio;
            totalFrontendTime += other.totalFrontendTime; // CHANGED: new
            frontendTimeCount += other.frontendTimeCount; // CHANGED: new
            totalFcpLcpGap += other.totalFcpLcpGap;
            if (other.lastStabilityCategory != null) lastStabilityCategory = other.lastStabilityCategory; // CHANGED: new
            totalHeadroom += other.totalHeadroom;         // CHANGED: new
            headroomCount += other.headroomCount;         // CHANGED: new
            totalFcp += other.totalFcp;
            totalLcp += other.totalLcp;
            totalCls += other.totalCls;
            totalTtfb += other.totalTtfb;
            totalRequests += other.totalRequests;
            totalBytes += other.totalBytes;
            totalErrors += other.totalErrors;
            totalWarnings += other.totalWarnings;
        }

        Object getColumn(int index) {
            int n = Math.max(sampleCount, 1);
            return switch (index) {
                case BpmConstants.COL_IDX_LABEL            -> label;
                case BpmConstants.COL_IDX_SAMPLES          -> sampleCount;
                case BpmConstants.COL_IDX_SCORE            ->
                        scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : "—";
                case BpmConstants.COL_IDX_RENDER_TIME      -> totalRenderTime / n;
                case BpmConstants.COL_IDX_SERVER_RATIO     -> String.format("%.2f%%", totalServerRatio / n);
                case BpmConstants.COL_IDX_FRONTEND_TIME    -> // CHANGED: new
                        frontendTimeCount > 0 ? totalFrontendTime / frontendTimeCount : "—";
                case BpmConstants.COL_IDX_FCP_LCP_GAP      -> totalFcpLcpGap / n;
                case BpmConstants.COL_IDX_STABILITY        -> // CHANGED: new
                        lastStabilityCategory != null ? lastStabilityCategory : "—";
                case BpmConstants.COL_IDX_HEADROOM         -> // CHANGED: new
                        headroomCount > 0 ? (int) (totalHeadroom / headroomCount) + "%" : "—";
                case BpmConstants.COL_IDX_IMPROVEMENT_AREA -> "TOTAL".equals(label) ? "" : lastImprovementArea; // CHANGED: renamed
                case BpmConstants.COL_IDX_FCP              -> totalFcp / n;
                case BpmConstants.COL_IDX_LCP              -> totalLcp / n;
                case BpmConstants.COL_IDX_CLS              -> String.format("%.3f", totalCls / n);
                case BpmConstants.COL_IDX_TTFB             -> totalTtfb / n;
                case BpmConstants.COL_IDX_REQS             -> totalRequests / n;
                case BpmConstants.COL_IDX_SIZE             -> totalBytes / n / 1024;
                case BpmConstants.COL_IDX_ERRS             -> totalErrors;
                case BpmConstants.COL_IDX_WARNS            -> totalWarnings;
                default -> "";
            };
        }

        int getScore() { return scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : 0; }
    }

    class BpmCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (isSelected) { return c; }
            c.setForeground(table.getForeground());
            c.setBackground(table.getBackground());
            int modelCol = table.convertColumnIndexToModel(column);
            List<RowData> filtered = tableModel.getFilteredRows();
            if (row >= 0 && row < filtered.size()) {
                int score = filtered.get(row).getScore();
                int scorePoor = propertiesRef != null ? propertiesRef.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;
                int scoreGood = propertiesRef != null ? propertiesRef.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
                if (score > 0 && score < scorePoor) { c.setBackground(ROW_TINT_RED); }
                else if (score > 0 && score < scoreGood) { c.setBackground(ROW_TINT_AMBER); }
            }
            applySlaColor(c, modelCol, value);
            // Text columns left-aligned; numeric columns right-aligned // CHANGED: added new text columns
            if (modelCol == BpmConstants.COL_IDX_LABEL
                    || modelCol == BpmConstants.COL_IDX_IMPROVEMENT_AREA
                    || modelCol == BpmConstants.COL_IDX_STABILITY) {
                setHorizontalAlignment(SwingConstants.LEFT);
            } else {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }
            // Value-level cell tooltips for Improvement Area and Stability columns // CHANGED: new
            if (modelCol == BpmConstants.COL_IDX_IMPROVEMENT_AREA && value instanceof String s) {
                setToolTipText(BpmConstants.getImprovementAreaValueTooltip(s));
            } else if (modelCol == BpmConstants.COL_IDX_STABILITY && value instanceof String s) {
                setToolTipText(BpmConstants.getStabilityValueTooltip(s));
            } else {
                setToolTipText(null);
            }
            return c;
        }

        private void applySlaColor(Component c, int modelCol, Object value) {
            if (value == null) { return; }
            int scoreGood  = propertiesRef != null ? propertiesRef.getSlaScoreGood()  : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
            int scorePoor  = propertiesRef != null ? propertiesRef.getSlaScorePoor()  : BpmConstants.DEFAULT_SLA_SCORE_POOR;
            long fcpGood   = propertiesRef != null ? propertiesRef.getSlaFcpGood()    : BpmConstants.DEFAULT_SLA_FCP_GOOD;
            long fcpPoor   = propertiesRef != null ? propertiesRef.getSlaFcpPoor()    : BpmConstants.DEFAULT_SLA_FCP_POOR;
            long lcpGood   = propertiesRef != null ? propertiesRef.getSlaLcpGood()    : BpmConstants.DEFAULT_SLA_LCP_GOOD;
            long lcpPoor   = propertiesRef != null ? propertiesRef.getSlaLcpPoor()    : BpmConstants.DEFAULT_SLA_LCP_POOR;
            double clsGood = propertiesRef != null ? propertiesRef.getSlaClsGood()    : BpmConstants.DEFAULT_SLA_CLS_GOOD;
            double clsPoor = propertiesRef != null ? propertiesRef.getSlaClsPoor()    : BpmConstants.DEFAULT_SLA_CLS_POOR;
            long ttfbGood  = propertiesRef != null ? propertiesRef.getSlaTtfbGood()   : BpmConstants.DEFAULT_SLA_TTFB_GOOD;
            long ttfbPoor  = propertiesRef != null ? propertiesRef.getSlaTtfbPoor()   : BpmConstants.DEFAULT_SLA_TTFB_POOR;
            switch (modelCol) {
                case BpmConstants.COL_IDX_SCORE -> { int s = toInt(value); c.setForeground(s >= scoreGood ? COLOR_GOOD : s >= scorePoor ? COLOR_NEEDS_WORK : COLOR_POOR); }
                case BpmConstants.COL_IDX_FCP -> { long v = toLong(value); c.setForeground(v <= fcpGood ? COLOR_GOOD : v <= fcpPoor ? COLOR_NEEDS_WORK : COLOR_POOR); }
                case BpmConstants.COL_IDX_LCP -> { long v = toLong(value); c.setForeground(v <= lcpGood ? COLOR_GOOD : v <= lcpPoor ? COLOR_NEEDS_WORK : COLOR_POOR); }
                case BpmConstants.COL_IDX_CLS -> { double v = toDoubleFromFormatted(value); c.setForeground(v <= clsGood ? COLOR_GOOD : v <= clsPoor ? COLOR_NEEDS_WORK : COLOR_POOR); }
                case BpmConstants.COL_IDX_TTFB -> { long v = toLong(value); c.setForeground(v <= ttfbGood ? COLOR_GOOD : v <= ttfbPoor ? COLOR_NEEDS_WORK : COLOR_POOR); }
                case BpmConstants.COL_IDX_ERRS -> { int e = toInt(value); c.setForeground(e == 0 ? COLOR_GOOD : COLOR_POOR); }
                case BpmConstants.COL_IDX_STABILITY -> { // CHANGED: new — colour-code stability category
                    if (BpmConstants.STABILITY_STABLE.equals(value))        c.setForeground(COLOR_GOOD);
                    else if (BpmConstants.STABILITY_MINOR_SHIFTS.equals(value)) c.setForeground(COLOR_NEEDS_WORK);
                    else if (BpmConstants.STABILITY_UNSTABLE.equals(value)) c.setForeground(COLOR_POOR);
                }
                case BpmConstants.COL_IDX_HEADROOM -> { // CHANGED: new — green if plenty of budget, amber if tight, red if critical
                    String hStr = value instanceof String s ? s.replace("%", "").trim() : "";
                    try {
                        int h = Integer.parseInt(hStr);
                        c.setForeground(h > 50 ? COLOR_GOOD : h > 20 ? COLOR_NEEDS_WORK : COLOR_POOR);
                    } catch (NumberFormatException ignored) { }
                }
                default -> { }
            }
        }

        private int toInt(Object value) {
            if (value instanceof Number n) { return n.intValue(); }
            try { return Integer.parseInt(value.toString().trim()); } catch (NumberFormatException e) { return 0; }
        }
        private long toLong(Object value) {
            if (value instanceof Number n) { return n.longValue(); }
            try { return Long.parseLong(value.toString().trim()); } catch (NumberFormatException e) { return 0; }
        }
        private double toDoubleFromFormatted(Object value) {
            if (value instanceof Number n) { return n.doubleValue(); }
            try { return Double.parseDouble(value.toString().trim()); } catch (NumberFormatException e) { return 0.0; }
        }
    }

    /**
     * Custom row sorter that:
     * (a) always pins the TOTAL row to the last view position regardless of sort direction, // CHANGED: Feature #3
     * (b) provides per-column comparators that handle mixed-type columns ("—" / numeric). // CHANGED: Bug #1
     *
     * <p>Implementation: A RowFilter excludes the TOTAL model row from the sorter's
     * sort pass. The three index-conversion methods are overridden to inject TOTAL
     * back at the last view position and to report the correct total view row count.
     */
    private static class TotalPinnedRowSorter extends TableRowSorter<BpmTableModel> {

        TotalPinnedRowSorter(BpmTableModel model) {
            super(model);
            installTotalFilter();
            installComparators();
        }

        // ── TOTAL pinning ────────────────────────────────────────────────────

        /** Installs a RowFilter that hides the last model row (TOTAL) from the sorter.
         *  TOTAL is then injected back at the end via the index overrides below. */
        private void installTotalFilter() {
            setRowFilter(new RowFilter<BpmTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends BpmTableModel, ? extends Integer> e) {
                    int count = getModel().getRowCount();
                    // Include all rows except the last one (TOTAL).
                    // When model is empty or has only 1 row, include nothing (avoids -1 index).
                    return count > 1 && e.getIdentifier() < count - 1;
                }
            });
        }

        @Override
        public int getViewRowCount() {
            int base = super.getViewRowCount(); // count of non-TOTAL visible rows
            // Add 1 slot for TOTAL if model has at least one row (which would be TOTAL)
            return getModel().getRowCount() > 0 ? base + 1 : base;
        }

        @Override
        public int convertRowIndexToModel(int viewIndex) {
            // The last view index always maps to the last model row (TOTAL).
            int count = getModel().getRowCount();
            if (count > 0 && viewIndex == super.getViewRowCount()) {
                return count - 1;
            }
            return super.convertRowIndexToModel(viewIndex);
        }

        @Override
        public int convertRowIndexToView(int modelIndex) {
            // The last model row (TOTAL) always maps to the last view index.
            int count = getModel().getRowCount();
            if (count > 0 && modelIndex == count - 1) {
                return super.getViewRowCount(); // = getViewRowCount() - 1
            }
            return super.convertRowIndexToView(modelIndex);
        }

        // Re-install filter on model changes so the excluded-last-row index stays current.
        @Override public void modelStructureChanged() { installTotalFilter(); super.modelStructureChanged(); }
        @Override public void allRowsChanged()        { installTotalFilter(); super.allRowsChanged(); }
        @Override public void rowsInserted(int f, int e) { installTotalFilter(); super.rowsInserted(f, e); }
        @Override public void rowsDeleted(int f, int e)  { installTotalFilter(); super.rowsDeleted(f, e); }
        @Override public void rowsUpdated(int f, int e)  { installTotalFilter(); super.rowsUpdated(f, e); }

        // ── Per-column comparators ────────────────────────────────────────────

        /**
         * Installs comparators for every column so sorting works correctly regardless
         * of whether the cell value is a formatted String, a Number, or the "—" sentinel. // CHANGED: Bug #1
         */
        private void installComparators() {
            for (int col = 0; col < BpmConstants.TOTAL_COLUMN_COUNT; col++) {
                setComparator(col, buildComparator(col));
            }
        }

        @SuppressWarnings("unchecked")
        private Comparator<Object> buildComparator(int col) {
            return switch (col) {
                // Pure alphabetic string columns
                case BpmConstants.COL_IDX_LABEL,
                     BpmConstants.COL_IDX_STABILITY,
                     BpmConstants.COL_IDX_IMPROVEMENT_AREA ->
                        Comparator.comparing(v -> v == null ? "" : v.toString());

                // Percentage-formatted strings: strip "%" then compare numerically;
                // "—" sorts as -1 (SPA rows appear first in ASC, last in DESC)
                case BpmConstants.COL_IDX_SERVER_RATIO,
                     BpmConstants.COL_IDX_HEADROOM ->
                        (a, b) -> Double.compare(parsePercent(a), parsePercent(b));

                // Float-formatted string (CLS)
                case BpmConstants.COL_IDX_CLS ->
                        (a, b) -> Double.compare(parseDouble(a), parseDouble(b));

                // Columns that can return Integer, Long, or "—" (SPA-stale)
                // "—" sorts as Long.MIN_VALUE (SPA rows first in ASC)
                default -> (a, b) -> Long.compare(parseLong(a), parseLong(b));
            };
        }

        private static double parsePercent(Object v) {
            if (v == null || "—".equals(v)) return -1.0;
            try { return Double.parseDouble(v.toString().replace("%", "").trim()); }
            catch (NumberFormatException e) { return -1.0; }
        }

        private static double parseDouble(Object v) {
            if (v == null || "—".equals(v)) return -1.0;
            try { return Double.parseDouble(v.toString().trim()); }
            catch (NumberFormatException e) { return -1.0; }
        }

        private static long parseLong(Object v) {
            if (v == null || "—".equals(v)) return Long.MIN_VALUE;
            if (v instanceof Number n) return n.longValue();
            try { return Long.parseLong(v.toString().trim()); }
            catch (NumberFormatException e) { return Long.MIN_VALUE; }
        }
    }

    static class TooltipTableHeader extends JTableHeader {
        private static final long serialVersionUID = 1L;
        TooltipTableHeader(TableColumnModel columnModel) { super(columnModel); }
        @Override
        public String getToolTipText(MouseEvent e) {
            int viewCol = columnAtPoint(e.getPoint());
            if (viewCol < 0) { return null; }
            return BpmConstants.getTooltip(getTable().convertColumnIndexToModel(viewCol));
        }
    }
}