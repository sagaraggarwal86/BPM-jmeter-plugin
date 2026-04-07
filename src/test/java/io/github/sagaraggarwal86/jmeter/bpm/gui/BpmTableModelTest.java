package io.github.sagaraggarwal86.jmeter.bpm.gui;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 integration tests for BpmTableModel: row management,
 * label filtering, column visibility.
 */
@DisplayName("GUI Table Model")
class BpmTableModelTest {

    private BpmTableModel model;

    @BeforeEach
    void setUp() {
        model = new BpmTableModel();
    }

    private BpmResult createResult(String label, int score, long lcp) {
        return createResult(label, score, lcp, null, BpmConstants.BOTTLENECK_NONE);
    }

    private BpmResult createResult(String label, int score, long lcp,
                                   String stability, String improvementArea) {
        WebVitalsResult vitals = new WebVitalsResult(500L, lcp, 0.02, 300L);
        DerivedMetrics derived = new DerivedMetrics(lcp - 300, 30.0, null, lcp - 500,
                stability, null, 0.0, improvementArea, List.of(), score);
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
    @DisplayName("Column count matches TOTAL_COLUMN_COUNT (18)")
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

    @Nested
    @DisplayName("Stability Filter")
    class StabilityFilterTest {

        @Test
        @DisplayName("Null stability filter shows all rows")
        void nullFilter_showsAll() {
            model.addOrUpdateResult(createResult("Page1", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Page2", 80, 2000, BpmConstants.STABILITY_UNSTABLE, BpmConstants.BOTTLENECK_NONE));

            model.setDropdownFilters(null, null);

            assertEquals(3, model.getFilteredRowCount()); // 2 + TOTAL
        }

        @Test
        @DisplayName("Single stability selection filters correctly")
        void singleSelection_filtersCorrectly() {
            model.addOrUpdateResult(createResult("Stable1", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Unstable1", 50, 4000, BpmConstants.STABILITY_UNSTABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Minor1", 70, 2500, BpmConstants.STABILITY_MINOR_SHIFTS, BpmConstants.BOTTLENECK_NONE));

            model.setDropdownFilters(Set.of(BpmConstants.STABILITY_STABLE), null);

            assertEquals(2, model.getFilteredRowCount()); // Stable1 + TOTAL
            assertEquals("Stable1", model.getFilteredValueAt(0, BpmConstants.COL_IDX_LABEL));
        }

        @Test
        @DisplayName("Multi-select stability shows matching rows")
        void multiSelect_showsMatching() {
            model.addOrUpdateResult(createResult("Stable1", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Unstable1", 50, 4000, BpmConstants.STABILITY_UNSTABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Minor1", 70, 2500, BpmConstants.STABILITY_MINOR_SHIFTS, BpmConstants.BOTTLENECK_NONE));

            model.setDropdownFilters(Set.of(BpmConstants.STABILITY_STABLE, BpmConstants.STABILITY_MINOR_SHIFTS), null);

            assertEquals(3, model.getFilteredRowCount()); // Stable1 + Minor1 + TOTAL
        }

        @Test
        @DisplayName("Stability filter with no matches returns empty")
        void noMatches_returnsEmpty() {
            model.addOrUpdateResult(createResult("Stable1", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));

            model.setDropdownFilters(Set.of(BpmConstants.STABILITY_UNSTABLE), null);

            assertEquals(0, model.getFilteredRowCount());
        }
    }

    @Nested
    @DisplayName("Improvement Area Filter")
    class ImprovementAreaFilterTest {

        @Test
        @DisplayName("Single improvement area selection filters correctly")
        void singleSelection_filtersCorrectly() {
            model.addOrUpdateResult(createResult("Fast", 95, 800, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Slow", 60, 3500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("Heavy", 70, 2500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_RESOURCE));

            model.setDropdownFilters(null, Set.of(BpmConstants.BOTTLENECK_SERVER));

            assertEquals(2, model.getFilteredRowCount()); // Slow + TOTAL
            assertEquals("Slow", model.getFilteredValueAt(0, BpmConstants.COL_IDX_LABEL));
        }

        @Test
        @DisplayName("None filter matches rows with no bottleneck")
        void noneFilter_matchesNoBottleneck() {
            model.addOrUpdateResult(createResult("Fast", 95, 800, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Slow", 60, 3500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));

            model.setDropdownFilters(null, Set.of(BpmConstants.BOTTLENECK_NONE));

            assertEquals(2, model.getFilteredRowCount()); // Fast + TOTAL
            assertEquals("Fast", model.getFilteredValueAt(0, BpmConstants.COL_IDX_LABEL));
        }

        @Test
        @DisplayName("Multi-select improvement area shows matching rows")
        void multiSelect_showsMatching() {
            model.addOrUpdateResult(createResult("Fast", 95, 800, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_NONE));
            model.addOrUpdateResult(createResult("Slow", 60, 3500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("Heavy", 70, 2500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_RESOURCE));

            model.setDropdownFilters(null, Set.of(BpmConstants.BOTTLENECK_SERVER, BpmConstants.BOTTLENECK_RESOURCE));

            assertEquals(3, model.getFilteredRowCount()); // Slow + Heavy + TOTAL
        }
    }

    @Nested
    @DisplayName("Combined Filters (AND logic)")
    class CombinedFilterTest {

        @Test
        @DisplayName("Stability + Improvement Area AND logic")
        void stabilityAndImprovement_andLogic() {
            model.addOrUpdateResult(createResult("A", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("B", 50, 4000, BpmConstants.STABILITY_UNSTABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("C", 70, 2500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_RESOURCE));

            model.setDropdownFilters(
                    Set.of(BpmConstants.STABILITY_STABLE),
                    Set.of(BpmConstants.BOTTLENECK_SERVER));

            // Only A matches both: Stable AND Server
            assertEquals(2, model.getFilteredRowCount()); // A + TOTAL
            assertEquals("A", model.getFilteredValueAt(0, BpmConstants.COL_IDX_LABEL));
        }

        @Test
        @DisplayName("Transaction + Stability + Improvement Area all AND together")
        void allThreeFilters_andLogic() {
            model.addOrUpdateResult(createResult("Login", 90, 1200, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("Dashboard", 50, 4000, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));
            model.addOrUpdateResult(createResult("Logout", 70, 2500, BpmConstants.STABILITY_STABLE, BpmConstants.BOTTLENECK_SERVER));

            model.setTransactionFilter("Login", false, true);
            model.setDropdownFilters(
                    Set.of(BpmConstants.STABILITY_STABLE),
                    Set.of(BpmConstants.BOTTLENECK_SERVER));

            // Only Login matches all three filters
            assertEquals(2, model.getFilteredRowCount()); // Login + TOTAL
            assertEquals("Login", model.getFilteredValueAt(0, BpmConstants.COL_IDX_LABEL));
        }
    }

    @Nested
    @DisplayName("Offset Filter")
    class OffsetFilterTest {

        private BpmResult resultAt(String label, Instant time) {
            WebVitalsResult vitals = new WebVitalsResult(500L, 1200L, 0.02, 300L);
            DerivedMetrics derived = new DerivedMetrics(900, 25.0, null, 700,
                    BpmConstants.STABILITY_STABLE, 80, 0.0,
                    BpmConstants.BOTTLENECK_NONE, List.of(), 90);
            return new BpmResult("1.0", time.toString(), "Thread-1", 1,
                    label, true, 2000, vitals, null, null, null, derived);
        }

        private List<BpmResult> sampleData() {
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            List<BpmResult> records = new ArrayList<>();
            records.add(resultAt("Page1", base));                                    // t=0s
            records.add(resultAt("Page2", base.plusSeconds(3)));                      // t=3s
            records.add(resultAt("Page3", base.plusSeconds(7)));                      // t=7s
            records.add(resultAt("Page4", base.plusSeconds(15)));                     // t=15s
            records.add(resultAt("Page5", base.plusSeconds(30)));                     // t=30s
            records.add(resultAt("Page6", base.plusSeconds(60)));                     // t=60s
            return records;
        }

        @Test
        @DisplayName("No offset returns all records")
        void noOffset_returnsAll() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 0, 0);
            assertEquals(6, result.size());
        }

        @Test
        @DisplayName("Start offset 1 skips first second only")
        void startOffset1_skipsFirstSecond() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 1, 0);
            // t=0 skipped (< 1s), t=3,7,15,30,60 pass
            assertEquals(5, result.size());
            assertEquals("Page2", result.get(0).samplerLabel());
        }

        @Test
        @DisplayName("End offset 10 keeps first 10 seconds")
        void endOffset10_keepsFirst10Seconds() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 0, 10);
            // t=0,3,7 pass (≤10), t=15,30,60 skipped (>10)
            assertEquals(3, result.size());
            assertEquals("Page3", result.get(2).samplerLabel());
        }

        @Test
        @DisplayName("Start 5, End 30 keeps window")
        void startAndEnd_keepsWindow() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 5, 30);
            // t=0,3 skipped (<5), t=7,15,30 pass, t=60 skipped (>30)
            assertEquals(3, result.size());
            assertEquals("Page3", result.get(0).samplerLabel());
            assertEquals("Page5", result.get(2).samplerLabel());
        }

        @Test
        @DisplayName("Start offset larger than all records returns empty")
        void startOffset_beyondAll_returnsEmpty() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 1000, 0);
            assertEquals(0, result.size());
        }

        @Test
        @DisplayName("End offset 0 with start offset only applies start filter")
        void endOffsetZero_onlyStartApplied() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(sampleData(), 10, 0);
            // t=0,3,7 skipped (<10), t=15,30,60 pass
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("Empty list returns empty")
        void emptyList_returnsEmpty() {
            List<BpmResult> result = BpmListenerGui.applyOffsetFilter(new ArrayList<>(), 1, 10);
            assertTrue(result.isEmpty());
        }
    }
}