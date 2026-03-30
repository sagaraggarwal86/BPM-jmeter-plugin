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
    private Timer updateTimer;

    private transient BpmListener listenerRef;
    private transient io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager propertiesRef;
    private boolean testRunning;
    private Instant testStartTime;

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
        filenameField = new JTextField(BpmConstants.DEFAULT_OUTPUT_FILENAME, 30);
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
        fieldset.add(startOffsetField);

        fieldset.add(new JLabel("End Offset (s):"));
        endOffsetField = new JTextField(4);
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

        // Apply button: re-evaluates transaction name + regex + include/exclude against // CHANGED: Feature #1
        // already-collected rows. Offset filtering is applied at ingest time in drainGuiQueue.
        JButton applyFilterButton = new JButton("Apply");
        applyFilterButton.setToolTipText(
                "Filter visible rows by transaction name. Offset filters apply to live data only.");
        applyFilterButton.addActionListener(e -> applyTransactionFilter());
        fieldset.add(applyFilterButton);

        // Enter key on transactionNamesField also triggers filter // CHANGED: Feature #1
        transactionNamesField.addActionListener(e -> applyTransactionFilter());

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
        super.configure(element);
        filenameField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, BpmConstants.DEFAULT_OUTPUT_FILENAME));
        startOffsetField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_START_OFFSET, ""));
        endOffsetField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_END_OFFSET, ""));
        transactionNamesField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES, ""));
        regexCheckBox.setSelected(element.getPropertyAsBoolean(BpmConstants.TEST_ELEMENT_REGEX, false));
        includeExcludeCombo.setSelectedItem(
                element.getPropertyAsBoolean(BpmConstants.TEST_ELEMENT_INCLUDE, true) ? "Include" : "Exclude");
        if (element instanceof BpmListener listener) {
            // Only wire to listener if it already has active data. // CHANGED: Bug #2
            // A freshly created listener has a null queue — don't inherit data from
            // any existing active instance; just show a blank GUI for the new listener.
            boolean hasActiveData = listener.getGuiUpdateQueue() != null;
            if (hasActiveData) {
                this.listenerRef = listener;
                this.propertiesRef = listener.getPropertiesManager();
                if (!updateTimer.isRunning()) {
                    testRunning = true;
                    updateTimer.start();
                }
            } else {
                // Fresh listener — sever any stale reference and reset the display // CHANGED: Bug #2
                this.listenerRef = listener;
                this.propertiesRef = null;
                clearDisplayOnly(); // clear GUI state without calling listener.clearData()
            }
        }
    }

    @Override
    public void clearData() {
        // Called by JMeter "Clear" / "Clear All" — reset everything. // CHANGED: Feature #4
        if (listenerRef != null) { listenerRef.clearData(); }
        clearDisplayOnly();
    }

    /** Resets only the GUI display without touching the backend listener state. // CHANGED: Bug #2 helper
     *  Used both by clearData() and by configure() when wiring a fresh listener. */
    private void clearDisplayOnly() {
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
        // Restore interactive state in case clear is called mid-test // CHANGED: Feature #4
        testRunning = false;
        filenameField.setEditable(true);
        if (browseButton != null) { browseButton.setEnabled(true); }
        if (updateTimer != null && updateTimer.isRunning()) { updateTimer.stop(); }
    }

    private void drainGuiQueue() {
        BpmListener listener = BpmListener.getActiveInstance(); // CHANGED: read from the active (initialized) instance — not listenerRef, which may point to the original (uninitialized) test element
        if (listener == null) { listener = this.listenerRef; } // fallback for JSONL read mode (no active test)
        if (listener == null) { return; }

        ConcurrentLinkedQueue<BpmResult> queue = listener.getGuiUpdateQueue();
        if (queue == null) { return; }

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

        int startOffset = parseIntSafe(startOffsetField.getText().trim());
        int endOffset = parseIntSafe(endOffsetField.getText().trim()); // CHANGED: read end offset for filtering
        String txPattern = transactionNamesField.getText().trim();
        boolean useRegex = regexCheckBox.isSelected();
        boolean include = "Include".equals(includeExcludeCombo.getSelectedItem());

        for (BpmResult r : batch) {
            if (testStartTime != null && (startOffset > 0 || endOffset > 0)) { // CHANGED: check both offsets
                try {
                    Instant sampleTime = Instant.parse(r.timestamp());
                    long elapsedSec = Duration.between(testStartTime, sampleTime).getSeconds();
                    if (startOffset > 0 && elapsedSec < startOffset) { continue; }
                    if (endOffset > 0 && elapsedSec > endOffset) { continue; } // CHANGED: skip samples beyond end offset
                } catch (Exception ignored) { }
            }
            if (!txPattern.isEmpty()) {
                boolean matches = matchesTransaction(r.samplerLabel(), txPattern, useRegex);
                if (include && !matches) { continue; }
                if (!include && matches) { continue; }
            }
            tableModel.addOrUpdateResult(r);
        }

        updateScoreBox(listener);
        tableModel.fireTableDataChanged();
        saveTableDataButton.setEnabled(tableModel.getRowCount() > 0);
    }

    public void testStarted() {
        testRunning = true;
        testStartTime = Instant.now();
        testStartField.setText(TIME_FMT.format(testStartTime));
        testEndField.setText("");
        testDurationField.setText("");
        // Disable file controls during test run // CHANGED: Feature #2
        filenameField.setEditable(false);
        browseButton.setEnabled(false);
        saveTableDataButton.setEnabled(false);
        if (!updateTimer.isRunning()) { updateTimer.start(); }
    }

    public void testEnded() {
        testRunning = false;
        Instant endTime = Instant.now();
        testEndField.setText(TIME_FMT.format(endTime));
        if (testStartTime != null) {
            Duration dur = Duration.between(testStartTime, endTime);
            long h = dur.toHours();
            long m = dur.toMinutesPart();
            long s = dur.toSecondsPart();
            testDurationField.setText(String.format("%dh %dm %ds", h, m, s));
        }
        updateTimer.stop();
        drainGuiQueue();
        // Re-enable file controls after test ends // CHANGED: Feature #2
        filenameField.setEditable(true);
        browseButton.setEnabled(true);
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

    private void applyTransactionFilter() {
        tableModel.setTransactionFilter(
                transactionNamesField.getText().trim(),
                regexCheckBox.isSelected(),
                "Include".equals(includeExcludeCombo.getSelectedItem()));
        tableModel.fireTableDataChanged();
    }

    private static boolean matchesTransaction(String label, String pattern, boolean useRegex) {
        if (pattern == null || pattern.isEmpty()) { return true; }
        if (useRegex) {
            try { return Pattern.compile(pattern).matcher(label).find(); }
            catch (PatternSyntaxException e) { return label.contains(pattern); }
        }
        return label.contains(pattern);
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
        tableModel.clear();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) { continue; }
                try {
                    BpmResult result = mapper.readValue(line, BpmResult.class);
                    tableModel.addOrUpdateResult(result);
                } catch (Exception e) {
                    log.warn("BPM: Skipping malformed JSONL line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("BPM: Failed to load JSONL file: {}", path, e);
            return;
        }
        tableModel.fireTableDataChanged();
        saveTableDataButton.setEnabled(tableModel.getRowCount() > 0);
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