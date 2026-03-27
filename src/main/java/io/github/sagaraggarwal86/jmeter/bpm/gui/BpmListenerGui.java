package io.github.sagaraggarwal86.jmeter.bpm.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.core.BpmListener;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.output.CsvExporter;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.apache.jmeter.gui.util.FileDialoger;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Swing GUI panel for the Browser Performance Metrics listener.
 *
 * <p>Extends {@link AbstractListenerGui} and implements {@link Clearable}.
 * Layout follows design doc section 5: info bar, output path, performance score box,
 * controls bar (label filter + columns + load file), results table, and save button.</p>
 *
 * <p>Live updates from {@link BpmListener} are drained from a {@link ConcurrentLinkedQueue}
 * via a 500ms {@link Timer} on the EDT.</p>
 */
public class BpmListenerGui extends AbstractListenerGui implements Clearable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmListenerGui.class);

    // SLA colors
    private static final Color COLOR_GOOD = new Color(0, 128, 0);       // Green
    private static final Color COLOR_NEEDS_WORK = new Color(204, 102, 0); // Amber
    private static final Color COLOR_POOR = new Color(204, 0, 0);       // Red

    // Row tint colors (subtle)
    private static final Color ROW_TINT_AMBER = new Color(255, 243, 224);
    private static final Color ROW_TINT_RED = new Color(255, 230, 230);

    // ── GUI components ─────────────────────────────────────────────────────────────────────

    private JLabel infoBar;
    private JTextField outputPathField;
    private JLabel scoreLabel;
    private JProgressBar scoreBar;
    private JLabel scoreCategoryLabel;
    private JComboBox<String> labelFilter;
    private ColumnSelectorPopup columnSelector;
    private JButton columnsButton;
    private JButton loadFileButton;
    private JButton saveTableButton;
    private JTable resultsTable;
    private BpmTableModel tableModel;
    private Timer updateTimer;

    // Reference to the BpmListener for queue draining
    private transient BpmListener listenerRef;

    // Reference to the properties manager for live SLA thresholds (§5.2 Decision #10) // CHANGED
    private transient io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager propertiesRef;

    // State
    private boolean testRunning;

    /**
     * Creates and lays out all GUI components.
     */
    public BpmListenerGui() {
        super();
        init();
    }

    /**
     * Builds the complete panel layout.
     */
    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        // Title panel from AbstractListenerGui
        java.awt.Container titlePanel = makeTitlePanel(); // CHANGED: makeTitlePanel() returns Container, not Box

        // Main content panel
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        mainPanel.add(createInfoBar());
        mainPanel.add(Box.createVerticalStrut(4));
        mainPanel.add(createOutputPathPanel());
        mainPanel.add(Box.createVerticalStrut(4));
        mainPanel.add(createScoreBox());
        mainPanel.add(Box.createVerticalStrut(4));
        mainPanel.add(createControlsBar());
        mainPanel.add(Box.createVerticalStrut(2));

        // Table
        tableModel = new BpmTableModel();
        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultsTable.setDefaultRenderer(Object.class, new BpmCellRenderer());
        resultsTable.getTableHeader().setReorderingAllowed(false);

        // Custom header tooltips
        resultsTable.setTableHeader(new TooltipTableHeader(resultsTable.getColumnModel()));

        JScrollPane scrollPane = new JScrollPane(resultsTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(800, 300));

        // Save button panel
        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        saveTableButton = new JButton("Save Table");
        saveTableButton.setEnabled(false);
        saveTableButton.addActionListener(e -> saveTable());
        savePanel.add(saveTableButton);

        add(titlePanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(mainPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        centerPanel.add(savePanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        // Apply default column visibility
        applyColumnVisibility();

        // Setup update timer (500ms)
        updateTimer = new Timer(BpmConstants.GUI_UPDATE_INTERVAL_MS, e -> drainGuiQueue());
        updateTimer.setRepeats(true);
    }

    // ── Component creation ─────────────────────────────────────────────────────────────────

    private JPanel createInfoBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        infoBar = new JLabel(BpmConstants.INFO_DEFAULT);
        panel.add(infoBar, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return panel;
    }

    private JPanel createOutputPathPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.add(new JLabel("Output File:"), BorderLayout.WEST);

        outputPathField = new JTextField(BpmConstants.DEFAULT_OUTPUT_FILENAME, 30);
        panel.add(outputPathField, BorderLayout.CENTER);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(e -> browseOutputPath());
        panel.add(browseButton, BorderLayout.EAST);

        return panel;
    }

    private JPanel createScoreBox() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(""),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        scoreLabel = new JLabel("Overall Performance Score: —");
        scoreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(scoreLabel);

        scoreBar = new JProgressBar(0, 100);
        scoreBar.setValue(0);
        scoreBar.setStringPainted(true);
        scoreBar.setString("—");
        scoreBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        scoreBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        box.add(Box.createVerticalStrut(2));
        box.add(scoreBar);

        scoreCategoryLabel = new JLabel("Good: 0  Needs Work: 0  Poor: 0");
        scoreCategoryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(Box.createVerticalStrut(2));
        box.add(scoreCategoryLabel);

        return box;
    }

    private JPanel createControlsBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        panel.add(new JLabel("Label Filter:"));
        labelFilter = new JComboBox<>(new String[]{"All Labels"});
        labelFilter.setPreferredSize(new Dimension(160, 24));
        labelFilter.addActionListener(e -> applyLabelFilter());
        panel.add(labelFilter);

        // Columns button
        columnSelector = new ColumnSelectorPopup(e -> applyColumnVisibility());
        columnsButton = new JButton("Columns \u25BE");
        columnsButton.addActionListener(e ->
                columnSelector.show(columnsButton, 0, columnsButton.getHeight()));
        panel.add(columnsButton);

        // Spacer
        panel.add(Box.createHorizontalStrut(20));

        // Load File button
        loadFileButton = new JButton("Load File");
        loadFileButton.addActionListener(e -> loadFile());
        panel.add(loadFileButton);

        return panel;
    }

    // ── AbstractListenerGui overrides ──────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public String getLabelResource() {
        return "bpm_listener_gui";
    }

    /** {@inheritDoc} */
    @Override
    public String getStaticLabel() {
        return "Browser Performance Metrics";
    }

    /** {@inheritDoc} */
    @Override
    public TestElement createTestElement() {
        BpmListener listener = new BpmListener();
        modifyTestElement(listener);
        return listener;
    }

    /** {@inheritDoc} */
    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        String path = outputPathField.getText().trim();                      // CHANGED: P3 — persist GUI path into JMX so it survives save/reload
        if (!path.isEmpty()) {
            element.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, path);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void configure(TestElement element) {
        super.configure(element);
        String savedPath = element.getPropertyAsString(              // CHANGED: P3 — restore persisted path into the field on configure
                BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                BpmConstants.DEFAULT_OUTPUT_FILENAME);
        outputPathField.setText(savedPath);
        if (element instanceof BpmListener listener) {
            this.listenerRef = listener;
            this.propertiesRef = listener.getPropertiesManager();
            if (listener.getGuiUpdateQueue() != null && !updateTimer.isRunning()) {
                testRunning = true;
                loadFileButton.setEnabled(false);
                updateTimer.start();
                infoBar.setText(BpmConstants.INFO_WAITING);
            }
        }
    }

    // ── Clearable ──────────────────────────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public void clearData() {
        if (listenerRef != null) { // CHANGED: §5.8 — delegate to listener to reset backend state
            listenerRef.clearData();
        }
        tableModel.clear();
        labelFilter.removeAllItems();
        labelFilter.addItem("All Labels");
        resetScoreBox();
        columnSelector.resetToDefaults();
        applyColumnVisibility();
        saveTableButton.setEnabled(false);
        infoBar.setText(BpmConstants.INFO_WAITING);
    }

    // ── Live update draining ───────────────────────────────────────────────────────────────

    /**
     * Drains the GUI update queue and applies results to the table model.
     * Called by the 500ms Swing Timer on the EDT.
     */
    private void drainGuiQueue() {
        BpmListener listener = this.listenerRef;
        if (listener == null) {
            return;
        }

        // Check for Scenario A/C info-bar override before processing data // CHANGED: §5.7
        String override = listener.getInfoBarOverride();
        if (override != null) {
            infoBar.setText(override);
        }

        ConcurrentLinkedQueue<BpmResult> queue = listener.getGuiUpdateQueue();
        if (queue == null) {
            return;
        }

        List<BpmResult> batch = new ArrayList<>();
        BpmResult result;
        while ((result = queue.poll()) != null) {
            batch.add(result);
        }

        if (batch.isEmpty()) {
            return;
        }

        // First data arrived — update info bar
        if (infoBar.getText().equals(BpmConstants.INFO_WAITING)) {
            infoBar.setText(BpmConstants.INFO_COLLECTING);
        }

        for (BpmResult r : batch) {
            tableModel.addOrUpdateResult(r);
            updateLabelFilter(r.samplerLabel());
        }

        // Update score box from listener's aggregates
        updateScoreBox(listener);
        tableModel.fireTableDataChanged();
        saveTableButton.setEnabled(tableModel.getRowCount() > 0);
    }

    /**
     * Called by BpmListener.testStarted() indirectly — starts the timer.
     */
    public void testStarted() {
        testRunning = true;
        loadFileButton.setEnabled(false);
        infoBar.setText(BpmConstants.INFO_WAITING);
        if (!updateTimer.isRunning()) {
            updateTimer.start();
        }
    }

    /**
     * Called by BpmListener.testEnded() indirectly — stops the timer, enables Load File.
     */
    public void testEnded() {
        testRunning = false;
        loadFileButton.setEnabled(true);
        updateTimer.stop();
        // Final drain
        drainGuiQueue();
    }

    // ── Score box ──────────────────────────────────────────────────────────────────────────

    private void updateScoreBox(BpmListener listener) {
        var aggregates = listener.getLabelAggregates();
        if (aggregates == null || aggregates.isEmpty()) {
            return;
        }

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

            if (score >= scoreGood) {
                goodCount++;
            } else if (score >= scorePoor) {
                needsWorkCount++;
            } else {
                poorCount++;
            }
        }

        int overallScore = totalSamples > 0 ? (int) (totalWeightedScore / totalSamples) : 0;

        scoreLabel.setText("Overall Performance Score: " + overallScore);
        scoreBar.setValue(overallScore);
        scoreBar.setString(String.valueOf(overallScore));

        if (overallScore >= scoreGood) {
            scoreBar.setForeground(COLOR_GOOD);
        } else if (overallScore >= scorePoor) {
            scoreBar.setForeground(COLOR_NEEDS_WORK);
        } else {
            scoreBar.setForeground(COLOR_POOR);
        }

        scoreCategoryLabel.setText(String.format("Good: %d  Needs Work: %d  Poor: %d",
                goodCount, needsWorkCount, poorCount));
    }

    private void resetScoreBox() {
        scoreLabel.setText("Overall Performance Score: —");
        scoreBar.setValue(0);
        scoreBar.setString("—");
        scoreBar.setForeground(Color.GRAY);
        scoreCategoryLabel.setText("Good: 0  Needs Work: 0  Poor: 0");
    }

    // ── Label filter ───────────────────────────────────────────────────────────────────────

    private void updateLabelFilter(String label) {
        for (int i = 0; i < labelFilter.getItemCount(); i++) {
            if (label.equals(labelFilter.getItemAt(i))) {
                return;
            }
        }
        labelFilter.addItem(label);
    }

    private void applyLabelFilter() {
        String selected = (String) labelFilter.getSelectedItem();
        tableModel.setFilterLabel("All Labels".equals(selected) ? null : selected);
        tableModel.fireTableDataChanged();
    }

    // ── Column visibility ──────────────────────────────────────────────────────────────────

    private void applyColumnVisibility() {
        if (resultsTable == null) {
            return;
        }
        boolean[] rawVisibility = columnSelector.getVisibility();
        TableColumnModel cm = resultsTable.getColumnModel();

        // Rebuild column model: always-visible first, then visible raw columns
        // We hide columns by setting width to 0 (simpler than removing/adding columns)
        for (int i = 0; i < BpmConstants.TOTAL_COLUMN_COUNT; i++) {
            try {
                TableColumn col = cm.getColumn(i);
                if (i < BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT) {
                    // Always visible — set reasonable width
                    col.setMinWidth(50);
                    col.setPreferredWidth(getPreferredColumnWidth(i));
                    col.setMaxWidth(300);
                } else {
                    // Raw column — show or hide
                    int rawIndex = i - BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT;
                    if (rawVisibility[rawIndex]) {
                        col.setMinWidth(50);
                        col.setPreferredWidth(getPreferredColumnWidth(i));
                        col.setMaxWidth(300);
                    } else {
                        col.setMinWidth(0);
                        col.setPreferredWidth(0);
                        col.setMaxWidth(0);
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // Column model may not be fully initialized yet
                break;
            }
        }
        resultsTable.revalidate();
        resultsTable.repaint();
    }

    private int getPreferredColumnWidth(int modelIndex) {
        return switch (modelIndex) {
            case BpmConstants.COL_IDX_LABEL -> 120;
            case BpmConstants.COL_IDX_SAMPLES -> 50;
            case BpmConstants.COL_IDX_SCORE -> 55;
            case BpmConstants.COL_IDX_RENDER_TIME -> 75;
            case BpmConstants.COL_IDX_SERVER_RATIO -> 70;
            case BpmConstants.COL_IDX_FCP_LCP_GAP -> 70;
            case BpmConstants.COL_IDX_BOTTLENECK -> 130;
            default -> 70; // raw columns
        };
    }

    // ── Browse / Load / Save ───────────────────────────────────────────────────────────────

    private void browseOutputPath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSONL files (*.jsonl)", "jsonl"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSONL files (*.jsonl)", "jsonl"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path path = chooser.getSelectedFile().toPath();
        ObjectMapper mapper = new ObjectMapper();

        tableModel.clear();
        labelFilter.removeAllItems();
        labelFilter.addItem("All Labels");

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    BpmResult result = mapper.readValue(line, BpmResult.class);
                    tableModel.addOrUpdateResult(result);
                    updateLabelFilter(result.samplerLabel());
                } catch (Exception e) {
                    log.warn("BPM: Skipping malformed JSONL line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("BPM: Failed to load JSONL file: {}", path, e);
            infoBar.setText("Failed to load file: " + e.getMessage());
            return;
        }

        tableModel.fireTableDataChanged();
        saveTableButton.setEnabled(tableModel.getRowCount() > 0);
        infoBar.setText("Loaded: " + path.getFileName());
    }

    private void saveTable() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        chooser.setSelectedFile(new java.io.File("bpm-results.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path outputPath = chooser.getSelectedFile().toPath();

        // Build visible column headers and row data
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
            infoBar.setText("Saved: " + outputPath.getFileName());
        } catch (IOException e) {
            log.warn("BPM: Failed to save CSV: {}", outputPath, e);
            infoBar.setText("Save failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Table Model
    // ════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Table model backed by a per-label aggregate map. Maintains running averages
     * for all 15 columns. Supports label filtering.
     */
    static class BpmTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        /**
         * Ordered map of label → row data (15-element Object array).
         * LinkedHashMap preserves insertion order.
         */
        private final LinkedHashMap<String, RowData> rows = new LinkedHashMap<>();
        private String filterLabel;
        private List<RowData> filteredRows;

        @Override
        public int getRowCount() {
            return getFilteredRows().size();
        }

        @Override
        public int getColumnCount() {
            return BpmConstants.TOTAL_COLUMN_COUNT;
        }

        @Override
        public String getColumnName(int column) {
            return BpmConstants.ALL_COLUMN_HEADERS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return getFilteredValueAt(rowIndex, columnIndex);
        }

        Object getFilteredValueAt(int rowIndex, int columnIndex) {
            List<RowData> filtered = getFilteredRows();
            if (rowIndex < 0 || rowIndex >= filtered.size()) {
                return null;
            }
            return filtered.get(rowIndex).getColumn(columnIndex);
        }

        int getFilteredRowCount() {
            return getFilteredRows().size();
        }

        void setFilterLabel(String label) {
            this.filterLabel = label;
            this.filteredRows = null;
        }

        /**
         * Adds or updates a label's aggregate from a BpmResult.
         */
        void addOrUpdateResult(BpmResult result) {
            String label = result.samplerLabel();
            RowData row = rows.computeIfAbsent(label, k -> new RowData(label));
            row.update(result);
            filteredRows = null; // invalidate cache
        }

        void clear() {
            rows.clear();
            filteredRows = null;
        }

        private List<RowData> getFilteredRows() {
            if (filteredRows != null) {
                return filteredRows;
            }
            if (filterLabel == null) {
                // Include all rows + TOTAL row
                List<RowData> result = new ArrayList<>(rows.values());
                if (!result.isEmpty()) {
                    result.add(computeTotalRow());
                }
                filteredRows = result;
            } else {
                RowData single = rows.get(filterLabel);
                filteredRows = single != null ? List.of(single) : List.of();
            }
            return filteredRows;
        }

        private RowData computeTotalRow() {
            RowData total = new RowData("TOTAL");
            for (RowData row : rows.values()) {
                total.mergeFrom(row);
            }
            return total;
        }
    }

    /**
     * Per-label aggregate row data. Maintains running sums for average computation.
     */
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

        RowData(String label) {
            this.label = label;
        }

        void update(BpmResult result) {
            sampleCount++;
            DerivedMetrics d = result.derived();
            if (d != null) {
                totalScore += d.performanceScore();
                totalRenderTime += d.renderTime();
                totalServerRatio += d.serverClientRatio();
                totalFcpLcpGap += d.fcpLcpGap();
                if (!BpmConstants.BOTTLENECK_NONE.equals(d.bottleneck())) {
                    lastBottleneck = d.bottleneck();
                }
            }
            WebVitalsResult v = result.webVitals();
            if (v != null) {
                totalFcp  += v.fcp()  != null ? v.fcp()  : 0L;   // CHANGED: null-safe unboxing (WebVitalsResult uses boxed Long/Double; SPA stale returns null)
                totalLcp  += v.lcp()  != null ? v.lcp()  : 0L;   // CHANGED
                totalCls  += v.cls()  != null ? v.cls()  : 0.0;  // CHANGED
                totalTtfb += v.ttfb() != null ? v.ttfb() : 0L;   // CHANGED
            }
            NetworkResult n = result.network();
            if (n != null) {
                totalRequests += n.totalRequests();
                totalBytes += n.totalBytes();
            }
            ConsoleResult c = result.console();
            if (c != null) {
                totalErrors += c.errors();
                totalWarnings += c.warnings();
            }
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
            // TOTAL row has no bottleneck
        }

        Object getColumn(int index) {
            int n = Math.max(sampleCount, 1);
            return switch (index) {
                case BpmConstants.COL_IDX_LABEL -> label;
                case BpmConstants.COL_IDX_SAMPLES -> sampleCount;
                case BpmConstants.COL_IDX_SCORE -> (int) (totalScore / n);
                case BpmConstants.COL_IDX_RENDER_TIME -> totalRenderTime / n;
                case BpmConstants.COL_IDX_SERVER_RATIO ->
                        String.format("%.2f%%", totalServerRatio / n);
                case BpmConstants.COL_IDX_FCP_LCP_GAP -> totalFcpLcpGap / n;
                case BpmConstants.COL_IDX_BOTTLENECK ->
                        "TOTAL".equals(label) ? "" : lastBottleneck;
                case BpmConstants.COL_IDX_FCP -> totalFcp / n;
                case BpmConstants.COL_IDX_LCP -> totalLcp / n;
                case BpmConstants.COL_IDX_CLS ->
                        String.format("%.3f", totalCls / n);
                case BpmConstants.COL_IDX_TTFB -> totalTtfb / n;
                case BpmConstants.COL_IDX_REQS -> totalRequests / n;
                case BpmConstants.COL_IDX_SIZE -> totalBytes / n / 1024;
                case BpmConstants.COL_IDX_ERRS -> totalErrors;
                case BpmConstants.COL_IDX_WARNS -> totalWarnings;
                default -> "";
            };
        }

        int getScore() {
            return sampleCount > 0 ? (int) (totalScore / sampleCount) : 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Cell Renderer
    // ════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Custom cell renderer implementing SLA text coloring and row background tinting.
     */
    class BpmCellRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value,
                    isSelected, hasFocus, row, column);

            if (isSelected) {
                return c;
            }

            // Reset colors
            c.setForeground(table.getForeground());
            c.setBackground(table.getBackground());

            // Get the model column index (column model may be reordered — though we disabled that)
            int modelCol = table.convertColumnIndexToModel(column);

            // Row background tint by score — use live thresholds, fall back to defaults (§5.2) // CHANGED
            List<RowData> filtered = tableModel.getFilteredRows();
            if (row >= 0 && row < filtered.size()) {
                int score = filtered.get(row).getScore();
                int scorePoor = propertiesRef != null
                        ? propertiesRef.getSlaScorePoor() : BpmConstants.DEFAULT_SLA_SCORE_POOR;
                int scoreGood = propertiesRef != null
                        ? propertiesRef.getSlaScoreGood() : BpmConstants.DEFAULT_SLA_SCORE_GOOD;
                if (score > 0 && score < scorePoor) {
                    c.setBackground(ROW_TINT_RED);
                } else if (score > 0 && score < scoreGood) {
                    c.setBackground(ROW_TINT_AMBER);
                }
            }

            // SLA text coloring for specific columns
            applySlaColor(c, modelCol, value);

            // Right-align numeric columns
            if (modelCol != BpmConstants.COL_IDX_LABEL
                    && modelCol != BpmConstants.COL_IDX_BOTTLENECK) {
                setHorizontalAlignment(SwingConstants.RIGHT);
            } else {
                setHorizontalAlignment(SwingConstants.LEFT);
            }

            return c;
        }

        private void applySlaColor(Component c, int modelCol, Object value) {
            if (value == null) {
                return;
            }

            // Read thresholds from live propertiesRef; fall back to constants if not yet loaded // CHANGED: §5.2 Decision #10
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
                case BpmConstants.COL_IDX_SCORE -> {
                    int score = toInt(value);
                    c.setForeground(score >= scoreGood ? COLOR_GOOD
                            : score >= scorePoor ? COLOR_NEEDS_WORK
                              : COLOR_POOR);
                }
                case BpmConstants.COL_IDX_FCP -> {
                    long fcp = toLong(value);
                    c.setForeground(fcp <= fcpGood ? COLOR_GOOD
                            : fcp <= fcpPoor ? COLOR_NEEDS_WORK
                              : COLOR_POOR);
                }
                case BpmConstants.COL_IDX_LCP -> {
                    long lcp = toLong(value);
                    c.setForeground(lcp <= lcpGood ? COLOR_GOOD
                            : lcp <= lcpPoor ? COLOR_NEEDS_WORK
                              : COLOR_POOR);
                }
                case BpmConstants.COL_IDX_CLS -> {
                    double cls = toDoubleFromFormatted(value);
                    c.setForeground(cls <= clsGood ? COLOR_GOOD
                            : cls <= clsPoor ? COLOR_NEEDS_WORK
                              : COLOR_POOR);
                }
                case BpmConstants.COL_IDX_TTFB -> {
                    long ttfb = toLong(value);
                    c.setForeground(ttfb <= ttfbGood ? COLOR_GOOD
                            : ttfb <= ttfbPoor ? COLOR_NEEDS_WORK
                              : COLOR_POOR);
                }
                case BpmConstants.COL_IDX_ERRS -> {
                    int errs = toInt(value);
                    c.setForeground(errs == 0 ? COLOR_GOOD : COLOR_POOR);
                }
                default -> { /* No SLA coloring for other columns */ }
            }
        }

        private int toInt(Object value) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private long toLong(Object value) {
            if (value instanceof Number n) {
                return n.longValue();
            }
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private double toDoubleFromFormatted(Object value) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Tooltip Table Header
    // ════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Custom JTableHeader that provides per-column tooltips via
     * {@link BpmConstants#getTooltip(int)}.
     */
    static class TooltipTableHeader extends JTableHeader {

        private static final long serialVersionUID = 1L;

        TooltipTableHeader(TableColumnModel columnModel) {
            super(columnModel);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int viewCol = columnAtPoint(e.getPoint());
            if (viewCol < 0) {
                return null;
            }
            int modelCol = getTable().convertColumnIndexToModel(viewCol);
            return BpmConstants.getTooltip(modelCol);
        }
    }
}