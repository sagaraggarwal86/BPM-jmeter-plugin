package io.github.sagaraggarwal86.jmeter.bpm.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderRegistry;
import io.github.sagaraggarwal86.jmeter.bpm.ai.report.BpmAiReportLauncher;
import io.github.sagaraggarwal86.jmeter.bpm.ai.report.BpmHtmlReportRenderer;
import io.github.sagaraggarwal86.jmeter.bpm.cli.TimeBucketBuilder;
import io.github.sagaraggarwal86.jmeter.bpm.core.BpmListener;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.output.CsvExporter;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BpmListenerGui extends AbstractListenerGui implements Clearable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmListenerGui.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final ObjectMapper FILE_MAPPER = new ObjectMapper();
    /**
     * Static reference to the shared BpmListenerGui instance. JMeter shares a single GUI
     * component across all BpmListener elements of the same type. Set in {@link #configure}.
     * Used by BpmListener.testStarted/testEnded to notify the GUI even when the primary
     * instance is a clone (for which GuiPackage.getGui() returns null).
     */
    private static volatile BpmListenerGui activeGui;
    /**
     * Raw BpmResult records from the current test or loaded file — source of truth for retroactive filter rebuilds.
     */
    // CHANGED: Defect #2 — enables retroactive offset re-filtering by rebuilding aggregates from raw records
    private final List<BpmResult> allRawResults = new ArrayList<>();
    /**
     * UUID of the element currently displayed in the GUI. Used to save/restore results
     * when navigating between BpmListener elements.
     */
    private String currentElementId;
    /**
     * Object reference of the element currently displayed. Used to detect element switches
     * even when two elements share the same UUID (e.g., copy-paste in JMeter).
     */
    private TestElement currentElementRef;
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
    private JComboBox<AiProviderConfig> aiProviderCombo;
    private JButton reloadListButton;
    private JButton generateAiReportButton;
    private JTextField chartIntervalField;
    private Timer updateTimer;
    private transient BpmListener listenerRef;
    private transient io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager propertiesRef;
    private boolean testRunning;
    private Instant testStartTime;
    /**
     * True while configure() is populating fields — suppresses spurious applyAllFilters() calls
     * fired by setSelected/setSelectedItem listeners during programmatic configuration.
     */
    private boolean configuringElement = false; // CHANGED: Defect #2 guard
    /**
     * Set to {@code true} by {@link #createTestElement()} so that the immediately following
     * {@link #configure(TestElement)} call knows it is wiring a brand-new element and must
     * blank the display. Cleared on first configure() use.
     */ // CHANGED: Defect #1 (this session) — new listener must show blank GUI
    private boolean pendingFreshClear = false;
    private boolean rawResultsDirty = false;
    // All 18 TableColumn objects stored at init — used for add/remove column visibility
    private TableColumn[] allColumns;

    public BpmListenerGui() {
        super();
        init();
    }

    /**
     * Returns the shared BpmListenerGui instance, or null if no GUI is active (CLI mode).
     */
    public static BpmListenerGui getActiveGui() {
        return activeGui;
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

    private static int parseIntSafe(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
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
        resultsTable.setDefaultRenderer(Object.class, new BpmCellRenderer(tableModel, () -> propertiesRef));
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

        // Bottom panel — matches JAAR footer layout
        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));

        // Save Table Data button
        saveTableDataButton = new JButton("Save Table Data");
        saveTableDataButton.setEnabled(false);
        saveTableDataButton.addActionListener(e -> saveTableData());
        savePanel.add(saveTableDataButton);

        // AI provider dropdown
        aiProviderCombo = new JComboBox<>();
        aiProviderCombo.setPreferredSize(new Dimension(160, 26));
        refreshAiProviders();
        savePanel.add(aiProviderCombo);

        // Reload List button
        reloadListButton = new JButton("Reload List");
        reloadListButton.addActionListener(e -> refreshAiProviders());
        savePanel.add(reloadListButton);

        // Generate AI Report button
        generateAiReportButton = new JButton("Generate AI Report");
        generateAiReportButton.setEnabled(false);
        generateAiReportButton.addActionListener(e -> launchAiReport());
        savePanel.add(generateAiReportButton);

        // Separator + Chart Interval
        savePanel.add(new JSeparator(SwingConstants.VERTICAL) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(2, 24);
            }
        });
        savePanel.add(new JLabel("Chart Interval (s, 0=auto):"));
        chartIntervalField = new JTextField("0", 4);
        applyPositiveIntegerFilter(chartIntervalField);
        savePanel.add(chartIntervalField);

        add(titlePanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(mainPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        centerPanel.add(savePanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        applyColumnVisibility();

        updateTimer = new Timer(BpmConstants.GUI_UPDATE_INTERVAL_MS, e -> drainGuiQueue());
        updateTimer.setRepeats(true);
        updateTimer.start();
    }

    private JPanel createFileFieldset() {
        JPanel fieldset = new JPanel(new BorderLayout(4, 0));
        fieldset.setBorder(BorderFactory.createTitledBorder("Write results to file / Read from file"));
        fieldset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        JPanel inner = new JPanel(new BorderLayout(4, 0));
        inner.add(new JLabel("Filename"), BorderLayout.WEST);
        filenameField = new JTextField("", 30); // CHANGED: Feature #2 — initially empty; default resolved at runtime
        filenameField.addActionListener(e -> loadFileFromField()); // CHANGED: Feature #3 — Enter key loads file if it exists
        // CHANGED: Defect — persist manually-typed path to backing element immediately,
        // so isUserProvidedOutputPath() sees it even if modifyTestElement() hasn't run yet.
        filenameField.getDocument().addDocumentListener(new DocumentListener() {
            private void persist() {
                if (configuringElement) {
                    return;
                } // CHANGED: Defect — skip programmatic setText during configure()
                if (listenerRef != null) {
                    String path = filenameField.getText().trim();
                    listenerRef.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, path);
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                persist();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                persist();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                persist();
            }
        });
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
    public String getLabelResource() {
        return "bpm_listener_gui";
    }

    @Override
    public String getStaticLabel() {
        return "Browser Performance Metrics";
    }

    @Override
    public TestElement createTestElement() {
        // Return a brand-new listener with no data — do NOT wire to any existing active instance. // CHANGED: Bug #2
        BpmListener listener = new BpmListener();
        modifyTestElement(listener);
        // CHANGED: Defect — stamp a stable UUID so primaryByName can distinguish distinct elements
        // even when multiple listeners share the same default name. Clones inherit this property,
        // so clone detection works correctly. Must be set AFTER modifyTestElement() and must NOT
        // be removed below — it is identity, not user data.
        listener.setProperty(BpmConstants.TEST_ELEMENT_ID, java.util.UUID.randomUUID().toString());
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
        listener.removeProperty(BpmConstants.TEST_ELEMENT_CHART_INTERVAL);
        pendingFreshClear = true; // CHANGED: Defect #1 — signal configure() to blank display for this new element
        return listener;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        // Preserve the stable element UUID before super.configureTestElement() calls element.clear(),
        // which wipes all custom properties. Without this, the UUID assigned in createTestElement()
        // is lost on the first navigate-away, causing distinct elements with the same default name
        // to collide in BpmListener.primaryByName and skip their own testStarted() setup.
        String uuid = element.getPropertyAsString(BpmConstants.TEST_ELEMENT_ID, "");
        super.configureTestElement(element);
        if (!uuid.isEmpty()) {
            element.setProperty(BpmConstants.TEST_ELEMENT_ID, uuid);
        }
        String path = filenameField.getText().trim();
        if (!path.isEmpty()) {
            element.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, path);
        }
        element.setProperty(BpmConstants.TEST_ELEMENT_START_OFFSET, startOffsetField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_END_OFFSET, endOffsetField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES, transactionNamesField.getText().trim());
        element.setProperty(BpmConstants.TEST_ELEMENT_REGEX, regexCheckBox.isSelected());
        element.setProperty(BpmConstants.TEST_ELEMENT_INCLUDE, "Include".equals(includeExcludeCombo.getSelectedItem()));
        element.setProperty(BpmConstants.TEST_ELEMENT_CHART_INTERVAL, chartIntervalField.getText().trim());
        // Persist column visibility so each listener retains its own selection across saves/loads.
        if (columnSelector != null) {
            boolean[] vis = columnSelector.getVisibility();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vis.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(vis[i]);
            }
            element.setProperty(BpmConstants.TEST_ELEMENT_COLUMN_VISIBILITY, sb.toString());
        }
    }

    @Override
    public void configure(TestElement element) {
        activeGui = this;
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
            chartIntervalField.setText(element.getPropertyAsString(BpmConstants.TEST_ELEMENT_CHART_INTERVAL, "0"));
            if (element instanceof BpmListener listener) {
                // Assign a stable UUID if missing (old .jmx files saved before UUID support).
                // Must happen before any early return so testStarted() can distinguish elements.
                if (element.getPropertyAsString(BpmConstants.TEST_ELEMENT_ID, "").isEmpty()) {
                    element.setProperty(BpmConstants.TEST_ELEMENT_ID,
                            java.util.UUID.randomUUID().toString());
                }
                // CHANGED: Defect #4 — wire listenerRef before any early return so that browseFile()
                // can persist the selected path to the backing element even for freshly-created listeners.
                // Without this, listenerRef stays null after pendingFreshClear, and the property is
                // never written, causing isUserProvidedOutputPath() to return false at testStarted().
                this.listenerRef = listener;

                currentElementId = element.getPropertyAsString(BpmConstants.TEST_ELEMENT_ID, "");
                currentElementRef = element;

                if (pendingFreshClear) {
                    pendingFreshClear = false;
                    clearDisplayOnly();
                    return;
                }

                tableModel.setTransactionFilter(
                        transactionNamesField.getText().trim(),
                        regexCheckBox.isSelected(),
                        "Include".equals(includeExcludeCombo.getSelectedItem()));

                // Always sync GUI with the element's authoritative data (Aggregate Report pattern).
                String eid = BpmListener.buildElementKey(element);
                BpmListener active = BpmListener.getPrimaryForElement(eid);
                if (active != null && active.getGuiUpdateQueue() != null) {
                    this.listenerRef = active;
                    this.propertiesRef = active.getPropertiesManager();
                    // Drain the queue BEFORE snapshotting rawResults to prevent double-counting.
                    // sampleOccurred adds to rawResults first, then to queue. Both contain the
                    // same items. Without draining, rebuildTableFromRaw would show rawResults data,
                    // then drainGuiQueue would re-add the same items from the queue.
                    ConcurrentLinkedQueue<BpmResult> q = active.getGuiUpdateQueue();
                    while (q.poll() != null) { /* discard — rawResults already has them */ }
                    allRawResults.clear();
                    allRawResults.addAll(active.getRawResults());
                    rebuildTableFromRaw();
                    testRunning = true;
                } else {
                    this.listenerRef = listener;
                    this.propertiesRef = listener.getPropertiesManager();
                    List<BpmResult> stored = listener.getRawResults();
                    if (!stored.isEmpty()) {
                        allRawResults.clear();
                        allRawResults.addAll(stored);
                        rebuildTableFromRaw();
                    } else {
                        allRawResults.clear();
                        tableModel.clear();
                        tableModel.fireTableDataChanged();
                        resetScoreBox();
                        testStartField.setText("");
                        testEndField.setText("");
                        testDurationField.setText("");
                        testStartTime = null;
                        saveTableDataButton.setEnabled(false);
                        updateAiButtonState();
                    }
                    if (testRunning) {
                        testRunning = false;
                        updateFilterFieldsEnabled();
                    }
                }

                // Restore column visibility from TestElement property.
                String visProp = element.getPropertyAsString(
                        BpmConstants.TEST_ELEMENT_COLUMN_VISIBILITY, "");
                if (!visProp.isEmpty()) {
                    String[] parts = visProp.split(",");
                    // Default unspecified columns to their defaults (handles old .jmx
                    // files saved with fewer columns than the current version).
                    boolean[] vis = java.util.Arrays.copyOf(
                            BpmConstants.RAW_COLUMNS_DEFAULT_VISIBILITY,
                            BpmConstants.RAW_COLUMN_COUNT);
                    for (int i = 0; i < vis.length && i < parts.length; i++) {
                        vis[i] = Boolean.parseBoolean(parts[i].trim());
                    }
                    columnSelector.setVisibility(vis);
                } else {
                    columnSelector.resetToDefaults();
                }
            }
        } finally {
            configuringElement = false; // CHANGED: Defect #2 — always release guard
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        // Do NOT call clearDisplayOnly() here — JMeter calls clearGui() on every
        // tree-node navigation.  Results data (allRawResults, table, score box,
        // timestamps) must survive navigate-away/back.  Data is cleared by:
        //   • clearData()   — user-initiated Clear / Clear All
        //   • testStarted() — new test run
        //   • pendingFreshClear in configure() — brand-new element creation
    }

    @Override
    public void clearData() {
        // Clear data on ALL BpmListener GUI-tree elements (not just the displayed one)
        // so that switching to another listener after Clear/Clear All shows empty data.
        try {
            var guiPackage = org.apache.jmeter.gui.GuiPackage.getInstance();
            if (guiPackage != null) {
                var nodes = guiPackage.getTreeModel().getNodesOfType(BpmListener.class);
                for (var node : nodes) {
                    if (node.getTestElement() instanceof BpmListener bl) {
                        bl.clearData();
                        // Reset filter properties so that configure() reads defaults
                        // when the user switches to this (currently inactive) listener.
                        bl.setProperty(BpmConstants.TEST_ELEMENT_START_OFFSET, "");
                        bl.setProperty(BpmConstants.TEST_ELEMENT_END_OFFSET, "");
                        bl.setProperty(BpmConstants.TEST_ELEMENT_TRANSACTION_NAMES, "");
                        bl.setProperty(BpmConstants.TEST_ELEMENT_REGEX, false);
                        bl.setProperty(BpmConstants.TEST_ELEMENT_INCLUDE, true);
                        bl.setProperty(BpmConstants.TEST_ELEMENT_CHART_INTERVAL, "0");
                    }
                }
            }
        } catch (Exception e) {
            // Fallback: clear only the active listener
            if (listenerRef != null) {
                listenerRef.clearData();
            }
        }
        clearDisplayOnly();
    }

    /**
     * Resets only the GUI display without touching the backend listener state or any output file.
     * Called from {@link #clearData()} (user-initiated Clear/Clear All) and from
     * {@code configure()} when {@code pendingFreshClear} is set (brand-new element creation).
     * Never called from {@link #clearGui()} — display data must persist across navigation.
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
        updateAiButtonState();
        testStartField.setText("");
        testEndField.setText("");
        testDurationField.setText("");
        testStartTime = null;
        // Restore interactive state in case clear is called mid-test
        testRunning = false;
        filenameField.setEditable(true);
        if (browseButton != null) {
            browseButton.setEnabled(true);
        }
        // Reset filter fields BEFORE disabling them so values are cleared while still enabled.
        // Guard suppresses applyAllFilters() listeners fired by setSelected/setSelectedItem.
        configuringElement = true;
        try {
            startOffsetField.setText("");
            endOffsetField.setText("");
            transactionNamesField.setText("");
            regexCheckBox.setSelected(false);
            includeExcludeCombo.setSelectedIndex(0); // "Include"
            chartIntervalField.setText("0");
        } finally {
            configuringElement = false;
        }
        updateFilterFieldsEnabled();
    }

    private void drainGuiQueue() {
        // Update elapsed time on every tick during a live test.
        if (testRunning && testStartTime != null) {
            Instant now = Instant.now();
            testEndField.setText(TIME_FMT.format(now));
            Duration elapsed = Duration.between(testStartTime, now);
            testDurationField.setText(String.format("%dh %dm %ds",
                    elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart()));
        }

        BpmListener listener = this.listenerRef;
        if (listener == null) {
            return;
        }

        ConcurrentLinkedQueue<BpmResult> queue = listener.getGuiUpdateQueue();
        if (queue == null) {
            // Fallback: listenerRef may point to the tree element (queue=null) if
            // testStarted() couldn't resolve the primary yet. Try a live lookup.
            if (testRunning && currentElementRef instanceof BpmListener) {
                String eid = BpmListener.buildElementKey(currentElementRef);
                BpmListener active = BpmListener.getPrimaryForElement(eid);
                if (active != null && active.getGuiUpdateQueue() != null) {
                    this.listenerRef = active;
                    this.propertiesRef = active.getPropertiesManager();
                    queue = active.getGuiUpdateQueue();
                }
            }
            if (queue == null) {
                return;
            }
        }

        // Drain queue and update table model directly — O(batch) per tick, not O(total).
        BpmResult result;
        boolean changed = false;
        while ((result = queue.poll()) != null) {
            tableModel.addOrUpdateResult(result);
            changed = true;
            if (testStartTime == null && result.timestamp() != null) {
                try {
                    testStartTime = Instant.parse(result.timestamp());
                    testStartField.setText(TIME_FMT.format(testStartTime));
                } catch (Exception ignored) {
                }
            }
        }
        if (changed) {
            tableModel.fireTableDataChanged();
            updateScoreBox(listener);
        }
    }

    public void testStarted() {
        if (BpmListener.isDontStartPending()) {
            return;
        }
        // Guard: BpmListener.testStarted() calls this via invokeLater for EACH listener
        // element (3 listeners = 3 calls). Only the first call does the real work.
        if (testRunning) {
            return;
        }
        // Update listenerRef to the active primary (execution-tree element) — the GUI
        // tree element's transient fields are null; only the execution primary has them.
        if (currentElementRef instanceof BpmListener) {
            String eid = BpmListener.buildElementKey(currentElementRef);
            BpmListener active = BpmListener.getPrimaryForElement(eid);
            if (active != null) {
                this.listenerRef = active;
                this.propertiesRef = active.getPropertiesManager();
            }
        }
        allRawResults.clear();
        tableModel.clear();
        tableModel.fireTableDataChanged();
        resetScoreBox();
        testEndField.setText("");
        testDurationField.setText("");
        testRunning = true;
        testStartTime = Instant.now();
        testStartField.setText(TIME_FMT.format(testStartTime));
        filenameField.setEditable(false);
        browseButton.setEnabled(false);
        saveTableDataButton.setEnabled(false);
        updateAiButtonState();
        updateFilterFieldsEnabled();
    }

    public void testEnded() {
        // Guard: BpmListener.testEnded() calls this via invokeLater for EACH listener
        // element (3 listeners = 3 calls). Only the first call does the real work;
        // subsequent calls are no-ops since testRunning is already false.
        if (!testRunning) {
            return;
        }
        testRunning = false;
        Instant endTime = Instant.now();
        testEndField.setText(TIME_FMT.format(endTime));
        if (testStartTime != null) {
            Duration dur = Duration.between(testStartTime, endTime);
            testDurationField.setText(String.format("%dh %dm %ds",
                    dur.toHours(), dur.toMinutesPart(), dur.toSecondsPart()));
        }
        // Final authoritative rebuild — picks up all samples including any
        // that arrived after the last timer drain.
        BpmListener listener = this.listenerRef;
        if (listener != null) {
            List<BpmResult> authoritative = listener.getRawResults();
            if (!authoritative.isEmpty()) {
                allRawResults.clear();
                allRawResults.addAll(authoritative);
                rebuildTableFromRaw();
            }
        }
        filenameField.setEditable(true);
        browseButton.setEnabled(true);
        updateFilterFieldsEnabled();
        saveTableDataButton.setEnabled(tableModel.getRowCount() > 0);
        refreshAiProviders();
        updateAiButtonState();
    }

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
                if (score >= scoreGood) {
                    goodCount++;
                } else if (score >= scorePoor) {
                    needsWorkCount++;
                } else {
                    poorCount++;
                }
            }
        }

        if (totalScoredSamples > 0) { // CHANGED: per-action accuracy — show "—" when no scored samples
            int overallScore = (int) (totalWeightedScore / totalScoredSamples);
            scoreLabel.setText(String.valueOf(overallScore));
            scoreBar.setValue(overallScore);
            scoreBar.setString(String.valueOf(overallScore));
            scoreBar.setForeground(overallScore >= scoreGood ? BpmCellRenderer.COLOR_GOOD
                    : overallScore >= scorePoor ? BpmCellRenderer.COLOR_NEEDS_WORK : BpmCellRenderer.COLOR_POOR);
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

        for (BpmTableModel.RowData row : tableModel.getFilteredRows()) {
            if ("TOTAL".equals(row.label)) {
                continue;
            }
            if (row.scoredSampleCount > 0) {
                int score = (int) (row.totalScore / row.scoredSampleCount);
                totalWeightedScore += (long) score * row.scoredSampleCount;
                totalScoredSamples += row.scoredSampleCount;
                if (score >= scoreGood) {
                    goodCount++;
                } else if (score >= scorePoor) {
                    needsWorkCount++;
                } else {
                    poorCount++;
                }
            }
        }

        if (totalScoredSamples > 0) {
            int overallScore = (int) (totalWeightedScore / totalScoredSamples);
            scoreLabel.setText(String.valueOf(overallScore));
            scoreBar.setValue(overallScore);
            scoreBar.setString(String.valueOf(overallScore));
            scoreBar.setForeground(overallScore >= scoreGood ? BpmCellRenderer.COLOR_GOOD
                    : overallScore >= scorePoor ? BpmCellRenderer.COLOR_NEEDS_WORK : BpmCellRenderer.COLOR_POOR);
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
        int endOffset = parseIntSafe(endOffsetField.getText().trim());
        Instant firstFiltered = null; // CHANGED: Defect #1 — track first/last record that survives offset filter
        Instant lastFiltered = null;
        for (BpmResult r : allRawResults) {
            Instant sampleTime = null;
            if (r.timestamp() != null) {
                try {
                    sampleTime = Instant.parse(r.timestamp());
                } catch (Exception ignored) {
                }
            }
            if (testStartTime != null && (startOffset > 0 || endOffset > 0) && sampleTime != null) {
                long elapsedSec = Duration.between(testStartTime, sampleTime).getSeconds();
                if (startOffset > 0 && elapsedSec < startOffset) {
                    continue;
                }
                if (endOffset > 0 && elapsedSec > endOffset) {
                    continue;
                }
            }
            // Record passes filter — track first/last timestamps unconditionally for Test Time Info
            if (sampleTime != null) { // CHANGED: Defect #1 — track regardless of testRunning so live-test Start offset update works
                if (firstFiltered == null) {
                    firstFiltered = sampleTime;
                }
                lastFiltered = sampleTime;
            }
            tableModel.addOrUpdateResult(r);
        }
        tableModel.fireTableDataChanged();
        saveTableDataButton.setEnabled(!testRunning && tableModel.getRowCount() > 0); // CHANGED: Feature #1 — disabled during test execution
        updateAiButtonState();
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
        if (configuringElement) {
            return;
        } // CHANGED: Defect #2 — suppress during programmatic configure()
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
        if (testRunning) {
            return;
        }
        String path = filenameField.getText().trim();
        if (path.isEmpty()) {
            return;
        }
        Path p = Path.of(path);
        if (Files.exists(p)) {
            loadJsonlFile(p);
        } else {
            log.debug("BPM: File not found, skipping load: {}", path);
        }
    }

    // getPreferredColumnWidth removed — auto-resize handles widths

    /**
     * Synchronizes the enabled state of filter fields to the current test-running state and row count.
     * Call this whenever either the row count or {@code testRunning} changes.
     *
     * <ul>
     *   <li>Offset fields — disabled during test execution (Feature #1); enabled otherwise.</li>
     *   <li>Apply Filters — always enabled.</li>
     *   <li>Reload List — disabled during test execution; enabled otherwise.</li>
     *   <li>Transaction-names, regex, include/exclude — always enabled.</li>
     * </ul>
     */ // CHANGED: Feature #1; Change #1; Change #2; Defects #1 #2 #3
    private void updateFilterFieldsEnabled() {
        startOffsetField.setEnabled(!testRunning); // CHANGED: Feature #1 — disabled during test execution
        endOffsetField.setEnabled(!testRunning);   // CHANGED: Feature #1 — disabled during test execution
        transactionNamesField.setEnabled(true);  // CHANGED: always enabled
        regexCheckBox.setEnabled(true);           // CHANGED: always enabled
        includeExcludeCombo.setEnabled(true);     // CHANGED: always enabled
        if (applyFiltersButton != null) {
            applyFiltersButton.setEnabled(true); // CHANGED: always enabled
        }
        if (reloadListButton != null) {
            reloadListButton.setEnabled(!testRunning);
        }
    }

    private void applyColumnVisibility() {
        if (resultsTable == null || allColumns == null) {
            return;
        }
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

    private void browseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("JSONL files (*.jsonl)", "jsonl"));
        int action = testRunning ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (action != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String selectedPath = chooser.getSelectedFile().getAbsolutePath();
        filenameField.setText(selectedPath);
        // CHANGED: Defect #4 — persist path to backing element immediately so testStarted() can
        // find it via isUserProvidedOutputPath() even if the user starts the test without
        // navigating away first (which is when JMeter normally calls modifyTestElement()).
        if (listenerRef != null) {
            listenerRef.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, selectedPath);
        }
        if (!testRunning && Files.exists(Path.of(selectedPath))) {
            loadJsonlFile(Path.of(selectedPath));
        }
    }

    private void loadJsonlFile(Path path) {
        // CHANGED: Feature #3 — reset raw store and table before loading a new file
        allRawResults.clear();
        tableModel.clear();
        testStartTime = null;
        // Clear display immediately; rebuildTableFromRaw() will repopulate from filtered records
        testStartField.setText("");
        testEndField.setText("");
        testDurationField.setText("");
        resetScoreBox();

        int lineCount = 0;
        int errorCount = 0;
        boolean capReached = false;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                lineCount++;
                try {
                    BpmResult result = FILE_MAPPER.readValue(line, BpmResult.class);
                    if (allRawResults.size() < BpmConstants.MAX_RAW_RESULTS) {
                        allRawResults.add(result);
                        rawResultsDirty = true;
                    } else if (!capReached) {
                        capReached = true;
                        log.warn("BPM: MAX_RAW_RESULTS ({}) reached. Excess records discarded.",
                                BpmConstants.MAX_RAW_RESULTS);
                    }
                    // testStartTime is the offset reference point — always the absolute first record's timestamp
                    if (testStartTime == null && result.timestamp() != null) {
                        try {
                            testStartTime = Instant.parse(result.timestamp());
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.warn("BPM: Skipping malformed JSONL line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("BPM: Failed to load JSONL file: {}", path, e);
            return;
        }

        if (lineCount > 0 && allRawResults.isEmpty()) {
            log.warn("BPM: All {} lines in {} failed to parse. No data loaded.", lineCount, path);
            JOptionPane.showMessageDialog(this,
                    "Failed to parse any records from:\n" + path.getFileName()
                            + "\n\n" + errorCount + " line(s) had parse errors.\n"
                            + "Ensure the file is a valid BPM JSONL file.",
                    "BPM — Load Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // CHANGED: Defect #1 — time fields and score are populated inside rebuildTableFromRaw()
        // using first/last timestamps of records that survive the current offset filter.
        if (rawResultsDirty) {
            rawResultsDirty = false;
            rebuildTableFromRaw();
            // Persist file-loaded data into the listener so it survives element switches.
            if (listenerRef != null) {
                listenerRef.setRawResults(allRawResults);
            }
        }
    }

    private void saveTableData() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        chooser.setSelectedFile(new java.io.File("bpm-results.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
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
            JOptionPane.showMessageDialog(this,
                    "Failed to save CSV file:\n" + e.getMessage(),
                    "BPM — Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── AI Report ─────────────────────────────────────────────────────────────

    private void refreshAiProviders() {
        aiProviderCombo.removeAllItems();
        try {
            java.io.File jmeterHome = null;
            String home = System.getProperty("jmeter.home",
                    System.getenv("JMETER_HOME"));
            if (home != null && !home.isBlank()) {
                jmeterHome = new java.io.File(home);
            }
            java.util.List<AiProviderConfig> providers =
                    AiProviderRegistry.loadConfiguredProviders(jmeterHome);
            for (AiProviderConfig p : providers) {
                aiProviderCombo.addItem(p);
            }
        } catch (Exception e) {
            log.warn("BPM: Failed to load AI providers: {}", e.getMessage());
        }
        updateAiButtonState();
    }

    private void updateAiButtonState() {
        if (generateAiReportButton == null) return;
        boolean hasData = tableModel != null && tableModel.getRowCount() > 0;
        boolean hasProvider = aiProviderCombo != null && aiProviderCombo.getItemCount() > 0
                && aiProviderCombo.getSelectedItem() != null;
        generateAiReportButton.setEnabled(!testRunning && hasData && hasProvider);
    }

    private void launchAiReport() {
        AiProviderConfig config = (AiProviderConfig) aiProviderCombo.getSelectedItem();
        if (config == null) return;

        // Resolve output directory — next to JSONL file, or user's home
        Path outputDir;
        String filePath = filenameField.getText().trim();
        if (!filePath.isEmpty()) {
            Path jsonlPath = Path.of(filePath);
            outputDir = jsonlPath.getParent() != null ? jsonlPath.getParent() : Path.of(".");
        } else {
            outputDir = Path.of(System.getProperty("user.home", "."));
        }

        // Get properties ref
        io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager props = propertiesRef;
        if (props == null) {
            props = new io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager();
            props.load();
        }

        // Build filtered data (respecting offset + transaction filters).
        // AI prompt aggregates, charts, and metrics table all use the same filtered set
        // so the report is always consistent regardless of data source.
        int startOffset = parseIntSafe(startOffsetField.getText().trim());
        int endOffset = parseIntSafe(endOffsetField.getText().trim());
        String txFilter = transactionNamesField.getText().trim();
        boolean txRegex = regexCheckBox.isSelected();
        boolean txInclude = "Include".equals(includeExcludeCombo.getSelectedItem());
        java.util.regex.Pattern txPattern = null;
        if (!txFilter.isEmpty() && txRegex) {
            try {
                txPattern = java.util.regex.Pattern.compile(txFilter, java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException ignored) {
            }
        }

        java.util.concurrent.ConcurrentHashMap<String, io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate> aggregates = new java.util.concurrent.ConcurrentHashMap<>();
        List<TimeBucketBuilder.RawSample> rawSamples = new ArrayList<>();
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;
        for (BpmResult r : allRawResults) {
            // Apply offset filter
            if (r.timestamp() != null && testStartTime != null && (startOffset > 0 || endOffset > 0)) {
                try {
                    long elapsedSec = Duration.between(testStartTime, Instant.parse(r.timestamp())).getSeconds();
                    if (startOffset > 0 && elapsedSec < startOffset) continue;
                    if (endOffset > 0 && elapsedSec > endOffset) continue;
                } catch (Exception ignored) {
                }
            }
            // Apply transaction name filter
            String label = r.samplerLabel();
            if (!txFilter.isEmpty() && label != null) {
                boolean matches;
                if (txPattern != null) {
                    matches = txPattern.matcher(label).find();
                } else {
                    matches = label.toLowerCase(java.util.Locale.ROOT)
                            .contains(txFilter.toLowerCase(java.util.Locale.ROOT));
                }
                if (txInclude && !matches) continue;
                if (!txInclude && matches) continue;
            }

            // Build filtered aggregates for AI prompt (same filter as charts/table)
            if (r.derived() != null && label != null && !label.isEmpty()) {
                aggregates.computeIfAbsent(label, k -> new io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate())
                        .update(r.derived(), r.webVitals(), r.network(), r.console());
            }

            if (r.timestamp() != null && r.derived() != null) {
                DerivedMetrics d = r.derived();
                WebVitalsResult wv = r.webVitals();
                long lcpVal = wv != null && wv.lcp() != null ? wv.lcp() : 0;
                long ttfbVal = wv != null && wv.ttfb() != null ? wv.ttfb() : 0;
                rawSamples.add(new TimeBucketBuilder.RawSample(
                        r.timestamp(),
                        label,
                        d.performanceScore(),
                        lcpVal,
                        wv != null && wv.fcp() != null ? wv.fcp() : 0,
                        ttfbVal,
                        wv != null && wv.cls() != null ? wv.cls() : -1,
                        d.renderTime()
                ));
                long epochMs = rawSamples.get(rawSamples.size() - 1).epochMs;
                if (epochMs < minTs) minTs = epochMs;
                if (epochMs > maxTs) maxTs = epochMs;
            }
        }

        // Warn if too many labels for AI analysis
        if (aggregates.size() > io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder.MAX_AI_LABELS) {
            int result = javax.swing.JOptionPane.showConfirmDialog(this,
                    String.format("Your test has %d transactions. AI analysis works best with %d or fewer.\n"
                                    + "The report will analyze the %d most critical transactions.\n\n"
                                    + "Continue, or apply filters first?",
                            aggregates.size(),
                            io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder.MAX_AI_LABELS,
                            io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder.MAX_AI_LABELS),
                    "AI Report — Many Transactions",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (result != javax.swing.JOptionPane.OK_OPTION) return;
        }

        // Compute grouped time buckets (global + per-label)
        int chartInterval = 0;
        try {
            chartInterval = Integer.parseInt(chartIntervalField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        TimeBucketBuilder.GroupedResult grouped = TimeBucketBuilder.buildGrouped(rawSamples, chartInterval);

        // Compute run date/time and duration
        String runDateTime = "";
        String duration = "";
        if (minTs != Long.MAX_VALUE) {
            runDateTime = TIME_FMT.format(Instant.ofEpochMilli(minTs))
                    + " - " + TIME_FMT.format(Instant.ofEpochMilli(maxTs));
            long durationSecs = (maxTs - minTs) / 1000;
            long h = durationSecs / 3600;
            long m = (durationSecs % 3600) / 60;
            long s = durationSecs % 60;
            duration = h + "h " + m + "m " + s + "s";
        }

        // Extract scenario name (Test Plan name) and virtual users (sum of thread groups)
        String scenarioName = "";
        String virtualUsersStr = "";
        try {
            org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
            if (gui != null) {
                var treeModel = gui.getTreeModel();
                java.util.List<org.apache.jmeter.gui.tree.JMeterTreeNode> planNodes =
                        treeModel.getNodesOfType(org.apache.jmeter.testelement.TestPlan.class);
                if (!planNodes.isEmpty()) {
                    scenarioName = planNodes.get(0).getTestElement().getName();
                }
                java.util.List<org.apache.jmeter.gui.tree.JMeterTreeNode> tgNodes =
                        treeModel.getNodesOfType(org.apache.jmeter.threads.AbstractThreadGroup.class);
                int totalThreads = 0;
                for (org.apache.jmeter.gui.tree.JMeterTreeNode tgNode : tgNodes) {
                    if (tgNode.isEnabled()) {
                        try {
                            String numStr = tgNode.getTestElement()
                                    .getPropertyAsString("ThreadGroup.num_threads", "0");
                            totalThreads += Integer.parseInt(numStr.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (totalThreads > 0) {
                    virtualUsersStr = String.valueOf(totalThreads);
                }
            } else {
                log.warn("BPM: GuiPackage is null — cannot read Test Plan name or thread count.");
            }
        } catch (Exception e) {
            log.warn("BPM: Could not read test plan metadata: {}", e.getMessage(), e);
        }
        log.info("BPM: Report metadata — scenario='{}', virtualUsers='{}'", scenarioName, virtualUsersStr);

        BpmHtmlReportRenderer.RenderConfig renderConfig = new BpmHtmlReportRenderer.RenderConfig(
                config.displayName, scenarioName, "", virtualUsersStr,
                runDateTime, duration, "",
                grouped.intervalSeconds,
                props.getSlaScoreGood(), props.getSlaLcpGood(),
                props.getSlaFcpGood(), props.getSlaTtfbGood(), props.getSlaClsGood(),
                props.getSlaScorePoor(), props.getSlaLcpPoor(),
                props.getSlaFcpPoor(), props.getSlaTtfbPoor(), props.getSlaClsPoor());

        // Build metrics table from current table model (already filtered)
        List<String[]> metricsTable = new ArrayList<>();
        metricsTable.add(BpmConstants.ALL_COLUMN_HEADERS);
        List<BpmTableModel.RowData> filteredRows = tableModel.getFilteredRows();
        for (BpmTableModel.RowData row : filteredRows) {
            String[] cells = new String[BpmConstants.TOTAL_COLUMN_COUNT];
            for (int c = 0; c < BpmConstants.TOTAL_COLUMN_COUNT; c++) {
                Object val = row.getColumn(c);
                cells[c] = val != null ? String.valueOf(val) : "";
            }
            metricsTable.add(cells);
        }

        BpmAiReportLauncher.launch(this, aggregates, props, config, outputDir,
                generateAiReportButton, renderConfig,
                grouped.globalBuckets, grouped.perLabelBuckets, metricsTable);
    }
}