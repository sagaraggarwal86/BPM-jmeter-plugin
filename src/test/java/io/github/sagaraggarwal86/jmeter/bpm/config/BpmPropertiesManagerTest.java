package io.github.sagaraggarwal86.jmeter.bpm.config;

import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BpmPropertiesManager}.
 * Covers auto-generation, version detection, backup/upgrade, and property access.
 */
@DisplayName("BpmPropertiesManager")
class BpmPropertiesManagerTest {

    @Test
    @DisplayName("Auto-generates properties file from template on first run")
    void load_missingFile_autoGenerates(@TempDir Path tempDir) {
        Path propsPath = tempDir.resolve("bpm.properties");
        assertFalse(Files.exists(propsPath));

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        assertTrue(Files.exists(propsPath), "Properties file should be auto-generated");
    }

    @Test
    @DisplayName("Loads properties with correct default values")
    void load_defaults_matchDesignDoc(@TempDir Path tempDir) {
        Path propsPath = tempDir.resolve("bpm.properties");
        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        // Metric toggles — all ON by default
        assertTrue(mgr.isWebVitalsEnabled());
        assertTrue(mgr.isNetworkEnabled());
        assertTrue(mgr.isRuntimeEnabled());
        assertTrue(mgr.isConsoleEnabled());

        // SLA thresholds — Google Core Web Vitals
        assertEquals(BpmConstants.DEFAULT_SLA_FCP_GOOD, mgr.getSlaFcpGood());
        assertEquals(BpmConstants.DEFAULT_SLA_LCP_GOOD, mgr.getSlaLcpGood());
        assertEquals(BpmConstants.DEFAULT_SLA_CLS_GOOD, mgr.getSlaClsGood(), 0.001);
        assertEquals(BpmConstants.DEFAULT_SLA_TTFB_GOOD, mgr.getSlaTtfbGood());
        assertEquals(BpmConstants.DEFAULT_SLA_JSERRORS_POOR, mgr.getSlaJsErrorsPoor());

        // Bottleneck thresholds
        assertEquals(60.0, mgr.getBottleneckServerRatio(), 0.001);
        assertEquals(40.0, mgr.getBottleneckResourceRatio(), 0.001);
        assertEquals(60.0, mgr.getBottleneckClientRatio(), 0.001);
        assertEquals(0.5, mgr.getBottleneckLayoutThrashFactor(), 0.001);

        // Security + network
        assertTrue(mgr.isSanitizeEnabled());
        assertEquals(5, mgr.getNetworkTopN());
    }

    @Test
    @DisplayName("Detects version from properties file header")
    void detectVersion_findsVersionInHeader(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\n# Auto-generated\nmetrics.webvitals=true\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        String version = mgr.detectVersion(propsPath);

        assertEquals("1.0", version);
    }

    @Test
    @DisplayName("Version mismatch triggers backup and upgrade")
    void load_versionMismatch_backsUpAndUpgrades(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        // Write an old-version file
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v0.9\nsla.fcp.good=9999\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        // Backup should exist
        Path backupPath = tempDir.resolve("bpm.properties.v0.9.bak");
        assertTrue(Files.exists(backupPath), "Backup file should be created");

        // Upgraded file should have current template values, not the old 9999
        assertEquals(BpmConstants.DEFAULT_SLA_FCP_GOOD, mgr.getSlaFcpGood());
    }

    @Test
    @DisplayName("getOutputPath() resolution: -J flag > bpm.properties > default")
    void getOutputPath_resolutionOrder(@TempDir Path tempDir) {
        Path propsPath = tempDir.resolve("bpm.properties");
        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        // Lowest priority: hardcoded default via template
        assertEquals("bpm-results.jsonl", mgr.getOutputPath());

        // Highest priority: -J flag
        mgr.setJFlagOutput("ci-build-42.jsonl");
        assertEquals("ci-build-42.jsonl", mgr.getOutputPath());

        // Blank -J flag falls through to properties/default
        mgr.setJFlagOutput("   ");
        assertEquals("bpm-results.jsonl", mgr.getOutputPath());
    }

    @Test
    @DisplayName("isDebugEnabled() resolution: -J flag > bpm.properties > false")
    void isDebugEnabled_resolutionOrder(@TempDir Path tempDir) {
        Path propsPath = tempDir.resolve("bpm.properties");
        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        // Default: false
        assertFalse(mgr.isDebugEnabled());

        // -J flag override
        mgr.setJFlagDebug("true");
        assertTrue(mgr.isDebugEnabled());

        mgr.setJFlagDebug("false");
        assertFalse(mgr.isDebugEnabled());
    }

    @Test
    @DisplayName("Invalid property values fall back to defaults with warning")
    void load_invalidValues_fallBackToDefaults(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\n"
                        + "sla.fcp.good=not_a_number\n"
                        + "network.topN=abc\n"
                        + "sla.cls.good=xyz\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        assertEquals(BpmConstants.DEFAULT_SLA_FCP_GOOD, mgr.getSlaFcpGood());
        assertEquals(BpmConstants.DEFAULT_NETWORK_TOP_N, mgr.getNetworkTopN());
        assertEquals(BpmConstants.DEFAULT_SLA_CLS_GOOD, mgr.getSlaClsGood(), 0.001);
    }

    @Test
    @DisplayName("Missing file results in hardcoded defaults (no exception)")
    void load_templateResourceMissing_usesDefaults(@TempDir Path tempDir) {
        // Even if ensurePropertiesFile fails internally, load() should not throw
        Path propsPath = tempDir.resolve("nonexistent-subdir/deeply/nested/bpm.properties");
        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);

        assertDoesNotThrow(() -> mgr.load());
        // Falls back to hardcoded defaults
        assertEquals(BpmConstants.DEFAULT_SLA_LCP_GOOD, mgr.getSlaLcpGood());
    }

    @Test
    @DisplayName("detectVersion returns null when version not in first 5 lines")
    void detectVersion_noVersionHeader_returnsNull(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Some comment\n# Another comment\n# Third line\n# Fourth line\n# Fifth line\nmetrics.webvitals=true\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        assertNull(mgr.detectVersion(propsPath));
    }

    @Test
    @DisplayName("detectVersion returns null for non-existent file")
    void detectVersion_nonExistentFile_returnsNull(@TempDir Path tempDir) {
        Path propsPath = tempDir.resolve("does-not-exist.properties");
        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        assertNull(mgr.detectVersion(propsPath));
    }

    @Test
    @DisplayName("Properties not loaded returns defaults (null properties)")
    void noLoad_returnsDefaults() {
        // Don't call load() — properties stays null
        TestablePropertiesManager mgr = new TestablePropertiesManager(Path.of("fake.properties"));

        assertEquals(BpmConstants.DEFAULT_SLA_FCP_GOOD, mgr.getSlaFcpGood());
        assertEquals(BpmConstants.DEFAULT_NETWORK_TOP_N, mgr.getNetworkTopN());
        assertEquals(BpmConstants.DEFAULT_SLA_CLS_GOOD, mgr.getSlaClsGood(), 0.001);
        assertTrue(mgr.isWebVitalsEnabled());
        assertTrue(mgr.isSanitizeEnabled());
        assertFalse(mgr.isDebugEnabled());
        assertEquals(BpmConstants.DEFAULT_OUTPUT_FILENAME, mgr.getOutputPath());
    }

    @Test
    @DisplayName("Blank property values fall back to defaults")
    void load_blankValues_fallBackToDefaults(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\n"
                        + "sla.fcp.good=   \n"
                        + "network.topN=\n"
                        + "sla.cls.good=  \n"
                        + "metrics.webvitals=  \n"
                        + "bpm.debug=  \n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        assertEquals(BpmConstants.DEFAULT_SLA_FCP_GOOD, mgr.getSlaFcpGood());
        assertEquals(BpmConstants.DEFAULT_NETWORK_TOP_N, mgr.getNetworkTopN());
        assertEquals(BpmConstants.DEFAULT_SLA_CLS_GOOD, mgr.getSlaClsGood(), 0.001);
        assertTrue(mgr.isWebVitalsEnabled());
        assertFalse(mgr.isDebugEnabled());
    }

    @Test
    @DisplayName("getOutputPath reads bpm.output from properties file")
    void getOutputPath_fromPropertiesFile(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\nbpm.output=custom-output.jsonl\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        assertEquals("custom-output.jsonl", mgr.getOutputPath());
    }

    @Test
    @DisplayName("Version null triggers backup with .old.bak suffix")
    void load_nullVersion_backsUpWithOldSuffix(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        // No version header — detectVersion returns null, != CURRENT_VERSION
        Files.writeString(propsPath, "# No version here\nsla.fcp.good=1234\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        Path backupPath = tempDir.resolve("bpm.properties.old.bak");
        assertTrue(Files.exists(backupPath), "Backup with .old.bak suffix should be created");
    }

    @Test
    @DisplayName("Boolean property with non-true value returns false")
    void load_booleanNonTrue_returnsFalse(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\nmetrics.webvitals=maybe\nsecurity.sanitize=yes\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        // Boolean.parseBoolean returns false for anything that isn't "true"
        assertFalse(mgr.isWebVitalsEnabled());
        assertFalse(mgr.isSanitizeEnabled());
    }

    @Test
    @DisplayName("All SLA getters return correct values from properties file")
    void load_allSlaGetters(@TempDir Path tempDir) throws IOException {
        Path propsPath = tempDir.resolve("bpm.properties");
        Files.writeString(propsPath,
                "# Browser Performance Metrics (BPM) v1.0\n"
                        + "sla.fcp.good=100\nsla.fcp.poor=200\n"
                        + "sla.lcp.good=300\nsla.lcp.poor=400\n"
                        + "sla.cls.good=0.05\nsla.cls.poor=0.15\n"
                        + "sla.ttfb.good=500\nsla.ttfb.poor=600\n"
                        + "sla.jserrors.good=1\nsla.jserrors.poor=5\n"
                        + "sla.score.good=80\nsla.score.poor=40\n",
                StandardCharsets.UTF_8);

        TestablePropertiesManager mgr = new TestablePropertiesManager(propsPath);
        mgr.load();

        assertEquals(100, mgr.getSlaFcpGood());
        assertEquals(200, mgr.getSlaFcpPoor());
        assertEquals(300, mgr.getSlaLcpGood());
        assertEquals(400, mgr.getSlaLcpPoor());
        assertEquals(0.05, mgr.getSlaClsGood(), 0.001);
        assertEquals(0.15, mgr.getSlaClsPoor(), 0.001);
        assertEquals(500, mgr.getSlaTtfbGood());
        assertEquals(600, mgr.getSlaTtfbPoor());
        assertEquals(1, mgr.getSlaJsErrorsGood());
        assertEquals(5, mgr.getSlaJsErrorsPoor());
        assertEquals(80, mgr.getSlaScoreGood());
        assertEquals(40, mgr.getSlaScorePoor());
    }

    /**
     * Subclass that redirects properties path to a temp directory
     * and stubs out JMeterUtils calls.
     */
    static class TestablePropertiesManager extends BpmPropertiesManager {

        private final Path propertiesPath;
        private String jFlagOutput;
        private String jFlagDebug;

        TestablePropertiesManager(Path propertiesPath) {
            this.propertiesPath = propertiesPath;
        }

        @Override
        protected Path resolvePropertiesPath() {
            return propertiesPath;
        }

        @Override
        public String getJMeterProperty(String key) {
            if ("bpm.output".equals(key)) {
                return jFlagOutput;
            }
            if ("bpm.debug".equals(key)) {
                return jFlagDebug;
            }
            return null;
        }

        void setJFlagOutput(String value) {
            this.jFlagOutput = value;
        }

        void setJFlagDebug(String value) {
            this.jFlagDebug = value;
        }
    }
}
