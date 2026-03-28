package io.github.sagaraggarwal86.jmeter.bpm.config;

import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the BPM properties file lifecycle: auto-generation, version detection,
 * backup/upgrade, and property access with -J flag overrides.
 *
 * <p>Thread-safe: properties are loaded once during {@link #load()} and are
 * immutable thereafter. Intended to be called from {@code testStarted()}.</p>
 *
 * <p>Resolution order for {@code getOutputPath()} and {@code isDebugEnabled()}:
 * {@code -J flag → bpm.properties → hardcoded default}. All other properties
 * resolve from bpm.properties only (no -J support).</p>
 */
public class BpmPropertiesManager { // CHANGED: removed final — tests subclass this to override resolvePropertiesPath() and getJMeterProperty()

    private static final Logger log = LoggerFactory.getLogger(BpmPropertiesManager.class);

    /** Bundled template resource path inside the JAR. */
    private static final String TEMPLATE_RESOURCE = "/bpm-default.properties";

    /** Properties filename. */
    private static final String PROPERTIES_FILENAME = "bpm.properties";

    /** Pattern to extract version from the header comment: {@code # Browser Performance Metrics (BPM) vX.Y} */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^#.*Browser Performance Metrics \\(BPM\\)\\s+v(\\S+)");

    /** Current plugin version embedded in the template. */
    static final String CURRENT_VERSION = "1.0";

    // -J flag property keys (JMeter system properties set via -Jbpm.output / -Jbpm.debug)
    private static final String J_FLAG_OUTPUT = "bpm.output";
    private static final String J_FLAG_DEBUG = "bpm.debug";

    // bpm.properties keys
    private static final String KEY_METRICS_WEBVITALS = "metrics.webvitals";
    private static final String KEY_METRICS_NETWORK = "metrics.network";
    private static final String KEY_METRICS_RUNTIME = "metrics.runtime";
    private static final String KEY_METRICS_CONSOLE = "metrics.console";
    private static final String KEY_NETWORK_TOP_N = "network.topN";

    private static final String KEY_SLA_FCP_GOOD = "sla.fcp.good";
    private static final String KEY_SLA_FCP_POOR = "sla.fcp.poor";
    private static final String KEY_SLA_LCP_GOOD = "sla.lcp.good";
    private static final String KEY_SLA_LCP_POOR = "sla.lcp.poor";
    private static final String KEY_SLA_CLS_GOOD = "sla.cls.good";
    private static final String KEY_SLA_CLS_POOR = "sla.cls.poor";
    private static final String KEY_SLA_TTFB_GOOD = "sla.ttfb.good";
    private static final String KEY_SLA_TTFB_POOR = "sla.ttfb.poor";
    private static final String KEY_SLA_JSERRORS_GOOD = "sla.jserrors.good";
    private static final String KEY_SLA_JSERRORS_POOR = "sla.jserrors.poor";
    private static final String KEY_SLA_SCORE_GOOD = "sla.score.good";
    private static final String KEY_SLA_SCORE_POOR = "sla.score.poor";

    private static final String KEY_BOTTLENECK_SERVER_RATIO = "bottleneck.server.ratio";
    private static final String KEY_BOTTLENECK_RESOURCE_RATIO = "bottleneck.resource.ratio";
    private static final String KEY_BOTTLENECK_CLIENT_RATIO = "bottleneck.client.ratio";
    private static final String KEY_BOTTLENECK_LAYOUT_FACTOR = "bottleneck.layoutThrash.factor";

    private static final String KEY_SECURITY_SANITIZE = "security.sanitize";
    private static final String KEY_DEBUG = "bpm.debug";
    private static final String KEY_BPM_OUTPUT = "bpm.output"; // CHANGED: P6 — needed for bpm.properties middle tier in getOutputPath()

    // Hardcoded defaults (lowest priority fallback)
    private static final String DEFAULT_OUTPUT_PATH = "bpm-results.jsonl";
    private static final boolean DEFAULT_DEBUG = false;
    private static final boolean DEFAULT_METRIC_ENABLED = true;
    private static final int DEFAULT_NETWORK_TOP_N = 5;
    private static final long DEFAULT_SLA_FCP_GOOD = 1800L;
    private static final long DEFAULT_SLA_FCP_POOR = 3000L;
    private static final long DEFAULT_SLA_LCP_GOOD = 2500L;
    private static final long DEFAULT_SLA_LCP_POOR = 4000L;
    private static final double DEFAULT_SLA_CLS_GOOD = 0.1;
    private static final double DEFAULT_SLA_CLS_POOR = 0.25;
    private static final long DEFAULT_SLA_TTFB_GOOD = 800L;
    private static final long DEFAULT_SLA_TTFB_POOR = 1800L;
    private static final int DEFAULT_SLA_JSERRORS_GOOD = 0;
    private static final int DEFAULT_SLA_JSERRORS_POOR = 5; // CHANGED: Gap 2 — aligned with design doc §3.3
    private static final int DEFAULT_SLA_SCORE_GOOD = 90;
    private static final int DEFAULT_SLA_SCORE_POOR = 50;
    private static final double DEFAULT_BOTTLENECK_SERVER_RATIO = 60.0;
    private static final double DEFAULT_BOTTLENECK_RESOURCE_RATIO = 40.0;
    private static final double DEFAULT_BOTTLENECK_CLIENT_RATIO = 60.0;
    private static final double DEFAULT_BOTTLENECK_LAYOUT_FACTOR = 0.5;
    private static final boolean DEFAULT_SECURITY_SANITIZE = true;

    private volatile Properties properties;

    /**
     * Loads (or auto-generates) {@code bpm.properties} from the JMeter bin directory.
     * Performs version detection and backup/upgrade if needed.
     *
     * <p>This method is intended to be called once from {@code testStarted()}.
     * Subsequent calls reload the file.</p>
     */
    public void load() {
        Path propertiesPath = resolvePropertiesPath();
        ensurePropertiesFile(propertiesPath);
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            log.warn("BPM: Failed to read {}. Using hardcoded defaults.", propertiesPath, e);
        }
        this.properties = props;
    }

    // ======================== Output Path ========================

    /**
     * Returns the JSONL output file path.
     *
     * <p>Resolution order: {@code -Jbpm.output → bpm.properties → "bpm-results.jsonl"}</p>
     *
     * @return output path, never null
     */
    public String getOutputPath() {
        // -J flag (highest priority)
        String jFlag = getJMeterProperty(J_FLAG_OUTPUT);
        if (jFlag != null && !jFlag.isBlank()) {
            return jFlag;
        }
        // bpm.properties middle tier — reads the (optionally uncommented) bpm.output key // CHANGED: P6 — this tier was skipped with a comment; the key exists in the template, the code must honour it
        String propValue = properties != null ? properties.getProperty(KEY_BPM_OUTPUT) : null;
        if (propValue != null && !propValue.isBlank()) {
            return propValue.trim();
        }
        // Hardcoded default (lowest priority)
        return DEFAULT_OUTPUT_PATH;
    }

    // ======================== Debug ========================

    /**
     * Returns whether debug mode is enabled.
     *
     * <p>Resolution order: {@code -Jbpm.debug → bpm.properties → false}</p>
     *
     * @return true if debug logging is enabled
     */
    public boolean isDebugEnabled() {
        // -J flag (highest priority)
        String jFlag = getJMeterProperty(J_FLAG_DEBUG);
        if (jFlag != null && !jFlag.isBlank()) {
            return Boolean.parseBoolean(jFlag);
        }
        return getBooleanProperty(KEY_DEBUG, DEFAULT_DEBUG);
    }

    // ======================== Metric Toggles ========================

    /** @return true if Web Vitals collection is enabled */
    public boolean isWebVitalsEnabled() {
        return getBooleanProperty(KEY_METRICS_WEBVITALS, DEFAULT_METRIC_ENABLED);
    }

    /** @return true if Network metrics collection is enabled */
    public boolean isNetworkEnabled() {
        return getBooleanProperty(KEY_METRICS_NETWORK, DEFAULT_METRIC_ENABLED);
    }

    /** @return true if Runtime metrics collection is enabled */
    public boolean isRuntimeEnabled() {
        return getBooleanProperty(KEY_METRICS_RUNTIME, DEFAULT_METRIC_ENABLED);
    }

    /** @return true if Console metrics collection is enabled */
    public boolean isConsoleEnabled() {
        return getBooleanProperty(KEY_METRICS_CONSOLE, DEFAULT_METRIC_ENABLED);
    }

    /** @return number of slowest resources to capture in network tier */
    public int getNetworkTopN() {
        return getIntProperty(KEY_NETWORK_TOP_N, DEFAULT_NETWORK_TOP_N);
    }

    // ======================== SLA Thresholds ========================

    /** @return FCP good threshold in milliseconds */
    public long getSlaFcpGood() {
        return getLongProperty(KEY_SLA_FCP_GOOD, DEFAULT_SLA_FCP_GOOD);
    }

    /** @return FCP poor threshold in milliseconds */
    public long getSlaFcpPoor() {
        return getLongProperty(KEY_SLA_FCP_POOR, DEFAULT_SLA_FCP_POOR);
    }

    /** @return LCP good threshold in milliseconds */
    public long getSlaLcpGood() {
        return getLongProperty(KEY_SLA_LCP_GOOD, DEFAULT_SLA_LCP_GOOD);
    }

    /** @return LCP poor threshold in milliseconds */
    public long getSlaLcpPoor() {
        return getLongProperty(KEY_SLA_LCP_POOR, DEFAULT_SLA_LCP_POOR);
    }

    /** @return CLS good threshold (unitless) */
    public double getSlaClsGood() {
        return getDoubleProperty(KEY_SLA_CLS_GOOD, DEFAULT_SLA_CLS_GOOD);
    }

    /** @return CLS poor threshold (unitless) */
    public double getSlaClsPoor() {
        return getDoubleProperty(KEY_SLA_CLS_POOR, DEFAULT_SLA_CLS_POOR);
    }

    /** @return TTFB good threshold in milliseconds */
    public long getSlaTtfbGood() {
        return getLongProperty(KEY_SLA_TTFB_GOOD, DEFAULT_SLA_TTFB_GOOD);
    }

    /** @return TTFB poor threshold in milliseconds */
    public long getSlaTtfbPoor() {
        return getLongProperty(KEY_SLA_TTFB_POOR, DEFAULT_SLA_TTFB_POOR);
    }

    /** @return JS errors good threshold (count) */
    public int getSlaJsErrorsGood() {
        return getIntProperty(KEY_SLA_JSERRORS_GOOD, DEFAULT_SLA_JSERRORS_GOOD);
    }

    /** @return JS errors poor threshold (count) */
    public int getSlaJsErrorsPoor() {
        return getIntProperty(KEY_SLA_JSERRORS_POOR, DEFAULT_SLA_JSERRORS_POOR);
    }

    /** @return Performance score good threshold */
    public int getSlaScoreGood() {
        return getIntProperty(KEY_SLA_SCORE_GOOD, DEFAULT_SLA_SCORE_GOOD);
    }

    /** @return Performance score poor threshold */
    public int getSlaScorePoor() {
        return getIntProperty(KEY_SLA_SCORE_POOR, DEFAULT_SLA_SCORE_POOR);
    }

    // ======================== Bottleneck Thresholds ========================

    /** @return Server bottleneck ratio threshold (TTFB as % of LCP) */
    public double getBottleneckServerRatio() {
        return getDoubleProperty(KEY_BOTTLENECK_SERVER_RATIO, DEFAULT_BOTTLENECK_SERVER_RATIO);
    }

    /** @return Resource bottleneck ratio threshold (slowest resource as % of LCP) */
    public double getBottleneckResourceRatio() {
        return getDoubleProperty(KEY_BOTTLENECK_RESOURCE_RATIO, DEFAULT_BOTTLENECK_RESOURCE_RATIO);
    }

    /** @return Client rendering bottleneck ratio threshold (render time as % of LCP) */
    public double getBottleneckClientRatio() {
        return getDoubleProperty(KEY_BOTTLENECK_CLIENT_RATIO, DEFAULT_BOTTLENECK_CLIENT_RATIO);
    }

    /** @return Layout thrashing factor (layoutCount &gt; domNodes * factor) */
    public double getBottleneckLayoutThrashFactor() {
        return getDoubleProperty(KEY_BOTTLENECK_LAYOUT_FACTOR, DEFAULT_BOTTLENECK_LAYOUT_FACTOR);
    }

    // ======================== Security ========================

    /** @return true if console message sanitization is enabled */
    public boolean isSanitizeEnabled() {
        return getBooleanProperty(KEY_SECURITY_SANITIZE, DEFAULT_SECURITY_SANITIZE);
    }

    // ======================== Internal ========================

    /**
     * Resolves the path to {@code bpm.properties} in the JMeter bin directory.
     * Falls back to the current working directory if JMETER_HOME is null.
     */
    protected Path resolvePropertiesPath() { // CHANGED: package-private → protected; cross-package test subclasses override this
        String jmeterHome = JMeterUtils.getJMeterHome();
        Path baseDir;
        if (jmeterHome != null && !jmeterHome.isBlank()) {
            baseDir = Path.of(jmeterHome, "bin");
        } else {
            log.warn("BPM: JMETER_HOME is null. Using current working directory for bpm.properties.");
            baseDir = Path.of(System.getProperty("user.dir"));
        }
        return baseDir.resolve(PROPERTIES_FILENAME);
    }

    /**
     * Ensures the properties file exists. Auto-generates from template on first run.
     * Performs version detection and backup/upgrade on version mismatch.
     */
    void ensurePropertiesFile(Path propertiesPath) {
        if (!Files.exists(propertiesPath)) {
            writeTemplate(propertiesPath);
            log.info("BPM: Auto-generated {} (v{})", propertiesPath, CURRENT_VERSION);
            return;
        }

        String existingVersion = detectVersion(propertiesPath);
        if (!CURRENT_VERSION.equals(existingVersion)) {
            backupAndUpgrade(propertiesPath, existingVersion);
        }
    }

    /**
     * Reads the version string from the properties file header comment.
     *
     * @param propertiesPath path to the properties file
     * @return version string or null if not found
     */
    String detectVersion(Path propertiesPath) {
        try (BufferedReader reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
            String line;
            // Only scan the first 5 lines for the version header
            for (int i = 0; i < 5 && (line = reader.readLine()) != null; i++) {
                Matcher matcher = VERSION_PATTERN.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (IOException e) {
            log.warn("BPM: Failed to read version from {}", propertiesPath, e);
        }
        return null;
    }

    /**
     * Backs up the existing properties file and overwrites with the new template.
     */
    private void backupAndUpgrade(Path propertiesPath, String oldVersion) {
        String backupSuffix = (oldVersion != null) ? ".v" + oldVersion + ".bak" : ".old.bak";
        Path backupPath = propertiesPath.resolveSibling(PROPERTIES_FILENAME + backupSuffix);
        try {
            Files.copy(propertiesPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("BPM: Backed up {} to {}", PROPERTIES_FILENAME, backupPath.getFileName());
        } catch (IOException e) {
            log.warn("BPM: Failed to backup {}. Proceeding with overwrite.", propertiesPath, e);
        }
        writeTemplate(propertiesPath);
        log.info("BPM: Upgraded {} from v{} to v{}", PROPERTIES_FILENAME, oldVersion, CURRENT_VERSION);
    }

    /**
     * Writes the bundled template resource to the given path.
     */
    private void writeTemplate(Path propertiesPath) {
        try (InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (templateStream == null) {
                log.error("BPM: Bundled template {} not found in JAR. Cannot auto-generate properties.",
                        TEMPLATE_RESOURCE);
                return;
            }
            Files.createDirectories(propertiesPath.getParent());
            Files.copy(templateStream, propertiesPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("BPM: Failed to write template to {}", propertiesPath, e);
        }
    }

    /**
     * Retrieves a JMeter system property set via {@code -J} flag.
     * Returns null if not set. Extracted for testability.
     */
    public String getJMeterProperty(String key) { // CHANGED: widened from package-private to public; called by BpmListener (different package)
        try {
            return JMeterUtils.getProperty(key);
        } catch (Exception e) {
            // JMeterUtils may not be initialized in unit tests
            return null;
        }
    }

    private boolean getBooleanProperty(String key, boolean defaultValue) {
        Properties props = this.properties;
        if (props == null) {
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private int getIntProperty(String key, int defaultValue) {
        Properties props = this.properties;
        if (props == null) {
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("BPM: Invalid integer for property '{}': '{}'. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private long getLongProperty(String key, long defaultValue) {
        Properties props = this.properties;
        if (props == null) {
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("BPM: Invalid long for property '{}': '{}'. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private double getDoubleProperty(String key, double defaultValue) {
        Properties props = this.properties;
        if (props == null) {
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("BPM: Invalid double for property '{}': '{}'. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}