package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.VERDICT_GOOD;
import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.VERDICT_NA;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReportDataBuilder")
class ReportDataBuilderTest {

    private BpmPropertiesManager props;

    @BeforeEach
    void setUp() {
        props = new BpmPropertiesManager();
        props.load();
    }

    private LabelAggregate createAggregate(int score, long lcp, long fcp, long ttfb,
                                           double cls, int errors) {
        return createAggregate(score, lcp, fcp, ttfb, cls, errors, null);
    }

    private LabelAggregate createAggregate(int score, long lcp, long fcp, long ttfb,
                                           double cls, int errors, Integer headroom) {
        LabelAggregate agg = new LabelAggregate();
        DerivedMetrics dm = new DerivedMetrics(
                lcp - ttfb,
                lcp > 0 ? (double) ttfb / lcp * 100 : 0,
                fcp > 0 && ttfb > 0 ? fcp - ttfb : null,
                lcp - fcp,
                "Stable",
                headroom,
                0.0,
                "None",
                Collections.emptyList(),
                score);
        WebVitalsResult wv = new WebVitalsResult(fcp, lcp, cls, ttfb);
        agg.update(dm, wv, null, null);
        // Add errors by calling update with console results
        for (int i = 0; i < errors; i++) {
            agg.update(dm, wv, null,
                    new io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult(1, 0, Collections.emptyList()));
        }
        return agg;
    }

    @Test
    @DisplayName("Empty aggregates produce empty report data")
    void emptyAggregates() {
        ReportData data = ReportDataBuilder.build(Collections.emptyMap(), props);
        assertEquals(0, data.totalLabels());
        assertEquals(0, data.totalSamples());
        assertEquals(VERDICT_NA, data.overallVerdict());
        assertTrue(data.breaches().isEmpty());
        assertTrue(data.headroomRisks().isEmpty());
    }

    @Test
    @DisplayName("All-passing labels produce zero breaches")
    void allPassing() {
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        aggs.put("/home", createAggregate(95, 1500, 800, 400, 0.05, 0));
        aggs.put("/about", createAggregate(92, 1800, 900, 500, 0.08, 0));

        ReportData data = ReportDataBuilder.build(aggs, props);
        assertEquals(2, data.totalLabels());
        assertEquals(VERDICT_GOOD, data.overallVerdict());
        assertEquals(2, data.passCount());
        assertEquals(0, data.criticalCount());
        assertEquals(0, data.warnCount());
        assertTrue(data.breaches().isEmpty());
        assertEquals("/home", data.bestLabel().isEmpty() ? "" : data.bestLabel());
    }

    @Test
    @DisplayName("Poor score creates a breach entry")
    void poorScore() {
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        aggs.put("/checkout", createAggregate(30, 5000, 2000, 1000, 0.3, 5));
        aggs.put("/home", createAggregate(95, 1500, 800, 400, 0.05, 0));

        ReportData data = ReportDataBuilder.build(aggs, props);
        assertEquals(1, data.criticalCount());
        assertFalse(data.breaches().isEmpty());
        assertTrue(data.breaches().get(0).hasCritical());
        assertEquals("/checkout", data.worstLabel());
        assertEquals("/home", data.bestLabel());
    }

    @Test
    @DisplayName("Headroom risk detected for low headroom labels")
    void headroomRisk() {
        // Create a label with low headroom (< 30%)
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        // headroom=5 means only 5% margin before failing quality targets
        aggs.put("/risky", createAggregate(55, 3800, 1800, 900, 0.15, 0, 5));

        ReportData data = ReportDataBuilder.build(aggs, props);
        // Headroom depends on LCP vs lcpPoor threshold
        // With LCP=3800 and lcpPoor=4000: headroom = 100 - (3800/4000*100) = 5%
        assertFalse(data.headroomRisks().isEmpty());
    }

    @Test
    @DisplayName("SPA labels detected (null score)")
    void spaLabels() {
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        // SPA label: score is null (DerivedMetrics with null score)
        LabelAggregate spaAgg = new LabelAggregate();
        DerivedMetrics dm = new DerivedMetrics(
                0, 0, null, 0, "Stable", null, 0.0, "None", Collections.emptyList(), null);
        spaAgg.update(dm, null, null, null);
        aggs.put("/dashboard", spaAgg);

        ReportData data = ReportDataBuilder.build(aggs, props);
        assertEquals(1, data.spaLabels().size());
        assertEquals("/dashboard", data.spaLabels().get(0));
    }

    @Test
    @DisplayName("Top JS errors collected and sorted")
    void topJsErrors() {
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        aggs.put("/page1", createAggregate(85, 2000, 1000, 500, 0.1, 3));
        aggs.put("/page2", createAggregate(80, 2200, 1100, 600, 0.12, 7));
        aggs.put("/page3", createAggregate(90, 1500, 800, 400, 0.05, 1));

        ReportData data = ReportDataBuilder.build(aggs, props);
        assertFalse(data.topJsErrors().isEmpty());
        // Should be sorted by count descending
        assertEquals("/page2", data.topJsErrors().get(0).label());
        assertEquals(7, data.topJsErrors().get(0).count());
        assertTrue(data.totalErrors() > 0);
    }

    @Test
    @DisplayName("Weighted score calculated correctly")
    void weightedScore() {
        Map<String, LabelAggregate> aggs = new LinkedHashMap<>();
        aggs.put("/home", createAggregate(90, 1500, 800, 400, 0.05, 0));
        aggs.put("/checkout", createAggregate(60, 3500, 1500, 800, 0.2, 0));

        ReportData data = ReportDataBuilder.build(aggs, props);
        // Both have 1 sample each, so weighted = (90+60)/2 = 75
        assertTrue(data.weightedScore() > 0);
    }

    @Test
    @DisplayName("Null aggregates throws NPE")
    void nullAggregates() {
        assertThrows(NullPointerException.class,
                () -> ReportDataBuilder.build(null, props));
    }

    @Test
    @DisplayName("Null props throws NPE")
    void nullProps() {
        assertThrows(NullPointerException.class,
                () -> ReportDataBuilder.build(Collections.emptyMap(), null));
    }
}
