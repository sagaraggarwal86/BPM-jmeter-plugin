package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for BpmConstants.
 */
@DisplayName("BpmConstants")
class BpmConstantsTest {

    @Test
    @DisplayName("All 18 column headers defined and match expected count") // CHANGED: 15 → 18 (3 new always-visible columns)
    void allColumnHeaders_matchExpectedCount() {
        assertEquals(18, BpmConstants.ALL_COLUMN_HEADERS.length);
        assertEquals(18, BpmConstants.TOTAL_COLUMN_COUNT);
        assertEquals(10, BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT); // CHANGED: 7 → 10
        assertEquals(8, BpmConstants.RAW_COLUMN_COUNT);
    }

    @Test
    @DisplayName("Default raw column visibility is all OFF")
    void rawColumnsDefaultVisibility_allOff() {
        for (boolean visible : BpmConstants.RAW_COLUMNS_DEFAULT_VISIBILITY) {
            assertFalse(visible, "All raw columns should default to OFF");
        }
    }

    @Test
    @DisplayName("Tooltips exist for all metric columns (not Label/Samples)")
    void tooltips_existForMetricColumns() {
        assertNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_LABEL));
        assertNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_SAMPLES));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_SCORE));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_RENDER_TIME));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_FRONTEND_TIME));  // CHANGED: new column
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_STABILITY));      // CHANGED: new column
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_HEADROOM));       // CHANGED: new column
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_IMPROVEMENT_AREA)); // CHANGED: renamed
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_FCP));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_LCP));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_CLS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_TTFB));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_REQS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_SIZE));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_ERRS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_WARNS));
    }
}