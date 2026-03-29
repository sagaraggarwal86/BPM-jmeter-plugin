package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 integration tests for BpmTableModel: row management,
 * label filtering, column visibility.
 */
@DisplayName("GUI Table Model")
class BpmTableModelTest {

    private BpmListenerGui.BpmTableModel model;

    @BeforeEach
    void setUp() {
        model = new BpmListenerGui.BpmTableModel();
    }

    private BpmResult createResult(String label, int score, long lcp) {
        WebVitalsResult vitals = new WebVitalsResult(500L, lcp, 0.02, 300L);
        DerivedMetrics derived = new DerivedMetrics(lcp - 300, 30.0, null, lcp - 500,
                null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), score); // CHANGED: new 10-arg constructor
        return new BpmResult("1.0", "2026-01-01T00:00:00Z", "Thread-1", 1,
                label, true, 2000, vitals, null, null, null, derived);
    }

    @Test
    @DisplayName("Adding results creates rows per label plus TOTAL")
    void addResults_createsRowsPerLabel() {
        model.addOrUpdateResult(createResult("Login", 90, 1200));
        model.addOrUpdateResult(createResult("Dashboard", 60, 3000));
        model.fireTableDataChanged();

        // 2 labels + 1 TOTAL row
        assertEquals(3, model.getRowCount());
    }

    @Test
    @DisplayName("Multiple results for same label aggregate (not duplicate rows)")
    void multipleResultsSameLabel_aggregate() {
        model.addOrUpdateResult(createResult("Login", 90, 1200));
        model.addOrUpdateResult(createResult("Login", 80, 1400));
        model.fireTableDataChanged();

        // 1 label + 1 TOTAL = 2 rows
        assertEquals(2, model.getRowCount());
        // Samples should be 2
        assertEquals(2, model.getValueAt(0, BpmConstants.COL_IDX_SAMPLES));
    }

    @Test
    @DisplayName("Transaction filter shows only matching label plus TOTAL")
    void transactionFilter_showsOnlyMatchingLabel() {
        model.addOrUpdateResult(createResult("Login", 90, 1200));
        model.addOrUpdateResult(createResult("Dashboard", 60, 3000));

        model.setTransactionFilter("Login", false, true);
        model.fireTableDataChanged();

        assertEquals(2, model.getRowCount()); // Login + TOTAL
        assertEquals("Login", model.getValueAt(0, BpmConstants.COL_IDX_LABEL));
        assertEquals("TOTAL", model.getValueAt(1, BpmConstants.COL_IDX_LABEL));
    }

    @Test
    @DisplayName("Null filter shows all labels plus TOTAL")
    void nullFilter_showsAllPlusTotals() {
        model.addOrUpdateResult(createResult("Login", 90, 1200));
        model.addOrUpdateResult(createResult("Dashboard", 60, 3000));

        model.setTransactionFilter(null, false, true);
        model.fireTableDataChanged();

        assertEquals(3, model.getRowCount()); // 2 labels + TOTAL
    }

    @Test
    @DisplayName("Server Ratio displayed as percentage format with 2 decimals")
    void serverRatio_displayedAsPercentage() {
        model.addOrUpdateResult(createResult("Login", 90, 1000));
        model.fireTableDataChanged();

        Object ratio = model.getValueAt(0, BpmConstants.COL_IDX_SERVER_RATIO);
        assertNotNull(ratio);
        assertTrue(ratio.toString().endsWith("%"), "Should end with %: " + ratio);
        assertTrue(ratio.toString().contains("."), "Should have decimal: " + ratio);
    }

    @Test
    @DisplayName("Column count matches TOTAL_COLUMN_COUNT (18)") // CHANGED: 15 → 18 (3 new derived columns)
    void columnCount_matches18() {
        assertEquals(BpmConstants.TOTAL_COLUMN_COUNT, model.getColumnCount());
    }

    @Test
    @DisplayName("Clear removes all rows")
    void clear_removesAllRows() {
        model.addOrUpdateResult(createResult("Login", 90, 1200));
        model.clear();
        model.fireTableDataChanged();

        assertEquals(0, model.getRowCount());
    }
}