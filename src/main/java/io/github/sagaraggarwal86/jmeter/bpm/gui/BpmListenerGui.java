package io.github.sagaraggarwal86.jmeter.bpm.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.core.BpmListener;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.output.CsvExporter;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
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

    // All 15 TableColumn objects stored at init — used for add/remove column visibility
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
        resultsTable.setAutoCreateRowSorter(true);

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
        JButton browseButton = new JButton("Browse...");
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
        transactionNamesField.addActionListener(e -> applyTransactionFilter());
        fieldset.add(transactionNamesField);

        regexCheckBox = new JCheckBox("RegEx");
        fieldset.add(regexCheckBox);

        includeExcludeCombo = new JComboBox<>(new String[]{"Include", "Exclude"});
        fieldset.add(includeExcludeCombo);

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
            this.listenerRef = listener;
            this.propertiesRef = listener.getPropertiesManager();
            if (listener.getGuiUpdateQueue() != null && !updateTimer.isRunning()) {
                testRunning = true;
                updateTimer.start();
            }
        }
    }

    @Override
    public void clearData() {
        if (listenerRef != null) { listenerRef.clearData(); }
        tableModel.clear();
        resetScoreBox();
        columnSelector.resetToDefaults();
        applyColumnVisibility();
        saveTableDataButton.setEnabled(false);
        testStartField.setText("");
        testEndField.setText("");
        testDurationField.setText("");
        testStartTime = null;
    }

    private void drainGuiQueue() {
        BpmListener listener = this.listenerRef;
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
        String txPattern = transactionNamesField.getText().trim();
        boolean useRegex = regexCheckBox.isSelected();
        boolean include = "Include".equals(includeExcludeCombo.getSelectedItem());

        for (BpmResult r : batch) {
            if (testStartTime != null && startOffset > 0) {
                try {
                    Instant sampleTime = Instant.parse(r.timestamp());
                    long elapsedSec = Duration.between(testStartTime, sampleTime).getSeconds();
                    if (elapsedSec < startOffset) { continue; }
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
    }

    private void updateScoreBox(BpmListener listener) {
        var aggregates = listener.getLabelAggregates();
        if (aggregates == null || aggregates.isEmpty()) { return; }

        var props = listener.getPropertiesManager();
        int scoreGood = props != null ? props.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
        int scorePoor = props != null ? props.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;

        int totalSamples = 0;
        long totalWeightedScore = 0;
        int goodCount = 0, needsWorkCount = 0, poorCount = 0;

        for (var entry : aggregates.entrySet()) {
            var agg = entry.getValue();
            int samples = agg.getSampleCount();
            int score = agg.getAverageScore();
            totalSamples += samples;
            totalWeightedScore += (long) score * samples;
            if (score >= scoreGood) { goodCount++; }
            else if (score >= scorePoor) { needsWorkCount++; }
            else { poorCount++; }
        }

        int overallScore = totalSamples > 0 ? (int) (totalWeightedScore / totalSamples) : 0;
        scoreLabel.setText(String.valueOf(overallScore));
        scoreBar.setValue(overallScore);
        scoreBar.setString(String.valueOf(overallScore));
        scoreBar.setForeground(overallScore >= scoreGood ? COLOR_GOOD
                : overallScore >= scorePoor ? COLOR_NEEDS_WORK : COLOR_POOR);
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
        long totalRenderTime;
        double totalServerRatio;
        long totalFcpLcpGap;
        long totalFcp;
        long totalLcp;
        double totalCls;
        long totalTtfb;
        int totalRequests;
        long totalBytes;
        int totalErrors;
        int totalWarnings;
        String lastBottleneck = BpmConstants.BOTTLENECK_NONE;

        RowData(String label) { this.label = label; }

        void update(BpmResult result) {
            sampleCount++;
            DerivedMetrics d = result.derived();
            if (d != null) {
                totalScore += d.performanceScore();
                totalRenderTime += d.renderTime();
                totalServerRatio += d.serverClientRatio();
                totalFcpLcpGap += d.fcpLcpGap();
                if (!BpmConstants.BOTTLENECK_NONE.equals(d.bottleneck())) { lastBottleneck = d.bottleneck(); }
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
            totalRenderTime += other.totalRenderTime;
            totalServerRatio += other.totalServerRatio;
            totalFcpLcpGap += other.totalFcpLcpGap;
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
                case BpmConstants.COL_IDX_LABEL -> label;
                case BpmConstants.COL_IDX_SAMPLES -> sampleCount;
                case BpmConstants.COL_IDX_SCORE -> (int) (totalScore / n);
                case BpmConstants.COL_IDX_RENDER_TIME -> totalRenderTime / n;
                case BpmConstants.COL_IDX_SERVER_RATIO -> String.format("%.2f%%", totalServerRatio / n);
                case BpmConstants.COL_IDX_FCP_LCP_GAP -> totalFcpLcpGap / n;
                case BpmConstants.COL_IDX_BOTTLENECK -> "TOTAL".equals(label) ? "" : lastBottleneck;
                case BpmConstants.COL_IDX_FCP -> totalFcp / n;
                case BpmConstants.COL_IDX_LCP -> totalLcp / n;
                case BpmConstants.COL_IDX_CLS -> String.format("%.3f", totalCls / n);
                case BpmConstants.COL_IDX_TTFB -> totalTtfb / n;
                case BpmConstants.COL_IDX_REQS -> totalRequests / n;
                case BpmConstants.COL_IDX_SIZE -> totalBytes / n / 1024;
                case BpmConstants.COL_IDX_ERRS -> totalErrors;
                case BpmConstants.COL_IDX_WARNS -> totalWarnings;
                default -> "";
            };
        }

        int getScore() { return sampleCount > 0 ? (int) (totalScore / sampleCount) : 0; }
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
            if (modelCol != BpmConstants.COL_IDX_LABEL && modelCol != BpmConstants.COL_IDX_BOTTLENECK) {
                setHorizontalAlignment(SwingConstants.RIGHT);
            } else {
                setHorizontalAlignment(SwingConstants.LEFT);
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