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
    @DisplayName("All 18 column headers defined and match expected count")
    void allColumnHeaders_matchExpectedCount() {
        assertEquals(18, BpmConstants.ALL_COLUMN_HEADERS.length);
        assertEquals(18, BpmConstants.TOTAL_COLUMN_COUNT);
        assertEquals(10, BpmConstants.ALWAYS_VISIBLE_COLUMN_COUNT);
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
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_FRONTEND_TIME));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_STABILITY));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_HEADROOM));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_IMPROVEMENT_AREA));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_FCP));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_LCP));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_CLS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_TTFB));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_REQS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_SIZE));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_ERRS));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_WARNS));
    }

    @Test
    @DisplayName("getTooltip returns null for invalid column index")
    void getTooltip_invalidIndex_returnsNull() {
        assertNull(BpmConstants.getTooltip(-1));
        assertNull(BpmConstants.getTooltip(999));
    }

    @Test
    @DisplayName("getTooltip covers server ratio and FCP-LCP gap columns")
    void getTooltip_additionalColumns() {
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_SERVER_RATIO));
        assertNotNull(BpmConstants.getTooltip(BpmConstants.COL_IDX_FCP_LCP_GAP));
    }

    @Test
    @DisplayName("getImprovementAreaValueTooltip returns tooltip for known values and null for unknown")
    void improvementAreaTooltip_knownAndUnknown() {
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_NONE));
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_SERVER));
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_RELIABILITY));
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_RESOURCE));
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_CLIENT));
        assertNotNull(BpmConstants.getImprovementAreaValueTooltip(BpmConstants.BOTTLENECK_LAYOUT));
        assertNull(BpmConstants.getImprovementAreaValueTooltip(null));
        assertNull(BpmConstants.getImprovementAreaValueTooltip("Unknown Value"));
    }

    @Test
    @DisplayName("getStabilityValueTooltip returns tooltip for known values and null for unknown")
    void stabilityTooltip_knownAndUnknown() {
        assertNotNull(BpmConstants.getStabilityValueTooltip(BpmConstants.STABILITY_STABLE));
        assertNotNull(BpmConstants.getStabilityValueTooltip(BpmConstants.STABILITY_MINOR_SHIFTS));
        assertNotNull(BpmConstants.getStabilityValueTooltip(BpmConstants.STABILITY_UNSTABLE));
        assertNull(BpmConstants.getStabilityValueTooltip(null));
        assertNull(BpmConstants.getStabilityValueTooltip("Unknown"));
    }

    // ── verdictToDisplay ──

    @Test
    @DisplayName("verdictToDisplay maps all verdicts correctly")
    void verdictToDisplay_allVerdicts() {
        assertEquals("Pass", BpmConstants.verdictToDisplay(BpmConstants.VERDICT_GOOD));
        assertEquals("Warning", BpmConstants.verdictToDisplay(BpmConstants.VERDICT_NEEDS_WORK));
        assertEquals("Fail", BpmConstants.verdictToDisplay(BpmConstants.VERDICT_POOR));
        assertNull(BpmConstants.verdictToDisplay(BpmConstants.VERDICT_NA));
        assertNull(BpmConstants.verdictToDisplay(null));
        assertNull(BpmConstants.verdictToDisplay("UNKNOWN"));
    }

    // ── verdictToIcon ──

    @Test
    @DisplayName("verdictToIcon maps all verdicts correctly")
    void verdictToIcon_allVerdicts() {
        assertEquals("\u2713 ", BpmConstants.verdictToIcon(BpmConstants.VERDICT_GOOD));
        assertEquals("\u26A0 ", BpmConstants.verdictToIcon(BpmConstants.VERDICT_NEEDS_WORK));
        assertEquals("\u2717 ", BpmConstants.verdictToIcon(BpmConstants.VERDICT_POOR));
        assertEquals("", BpmConstants.verdictToIcon(BpmConstants.VERDICT_NA));
        assertEquals("", BpmConstants.verdictToIcon(null));
        assertEquals("", BpmConstants.verdictToIcon("UNKNOWN"));
    }

    // ── verdictToCss ──

    @Test
    @DisplayName("verdictToCss maps all verdicts correctly")
    void verdictToCss_allVerdicts() {
        assertEquals("sla-pass", BpmConstants.verdictToCss(BpmConstants.VERDICT_GOOD));
        assertEquals("sla-warn", BpmConstants.verdictToCss(BpmConstants.VERDICT_NEEDS_WORK));
        assertEquals("sla-fail", BpmConstants.verdictToCss(BpmConstants.VERDICT_POOR));
        assertEquals("sla-na", BpmConstants.verdictToCss(BpmConstants.VERDICT_NA));
        assertEquals("sla-na", BpmConstants.verdictToCss(null));
        assertEquals("sla-na", BpmConstants.verdictToCss("UNKNOWN"));
    }
}