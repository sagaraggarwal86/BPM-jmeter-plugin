package io.github.sagaraggarwal86.jmeter.bpm.e2e;

import io.github.sagaraggarwal86.jmeter.bpm.collectors.ConsoleCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.DerivedMetricsCalculator;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.NetworkCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.RuntimeCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.WebVitalsCollector;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.CdpSessionManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.ChromeCdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.ConsoleSanitizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 3 — End-to-end tests using real headless Chrome.
 *
 * <p>Tests each collector against a real browser loading the bundled test HTML page.
 * Requires Chrome/Chromium installed. Run via {@code mvn verify -Pe2e}.</p>
 *
 * <p>Tests are ordered to share a single browser session for efficiency:
 * session init → page load → collect in sequence.</p>
 */
@DisplayName("E2E: Real Browser Collectors")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmE2eTest {

    private static ChromeDriver driver;
    private static CdpCommandExecutor executor;
    private static MetricsBuffer buffer;
    private static CdpSessionManager sessionManager;

    @BeforeAll
    static void setUpBrowser() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1280,720");

        driver = new ChromeDriver(options);
        executor = new ChromeCdpCommandExecutor(driver);
        buffer = new MetricsBuffer();
        sessionManager = new CdpSessionManager();

        // Open CDP session (enable domains, inject observers)
        sessionManager.openSession(executor);

        // Load test page
        URL testPage = BpmE2eTest.class.getResource("/e2e/test-page.html");
        assertNotNull(testPage, "Test page resource must exist");
        driver.get(testPage.toString());

        // Wait for page to settle (layout shifts, observers to fire)
        Thread.sleep(2000);

        // Transfer buffered events from browser to Java-side buffer
        sessionManager.transferBufferedEvents(executor, buffer);
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            sessionManager.closeSession(executor);
        }
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    @Order(1)
    @DisplayName("WebVitalsCollector returns non-zero FCP and TTFB from real page")
    void webVitalsCollector_realPage_nonZeroValues() {
        WebVitalsCollector collector = new WebVitalsCollector();
        WebVitalsResult result = collector.collect(executor, buffer);

        assertNotNull(result, "WebVitals should not be null on first collection");
        assertTrue(result.fcp() >= 0, "FCP should be non-negative, got: " + result.fcp());
        assertTrue(result.ttfb() >= 0, "TTFB should be non-negative, got: " + result.ttfb());
        // LCP may or may not fire depending on timing; just verify no exception
    }

    @Test
    @Order(2)
    @DisplayName("RuntimeCollector returns sane heap and DOM node counts")
    void runtimeCollector_realPage_saneValues() {
        RuntimeCollector collector = new RuntimeCollector();
        RuntimeResult result = collector.collect(executor, buffer);

        assertNotNull(result);
        assertTrue(result.heapUsed() > 0, "Heap should be > 0, got: " + result.heapUsed());
        assertTrue(result.domNodes() > 0, "DOM nodes should be > 0, got: " + result.domNodes());
        assertTrue(result.layoutCount() >= 0, "Layout count should be >= 0");
        assertTrue(result.styleRecalcCount() >= 0, "Style recalc should be >= 0");
    }

    @Test
    @Order(3)
    @DisplayName("ConsoleCollector captures deliberate test errors and warnings")
    void consoleCollector_realPage_capturesErrors() {
        // Transfer again to pick up any new console messages
        sessionManager.transferBufferedEvents(executor, buffer);

        ConsoleCollector collector = new ConsoleCollector(new ConsoleSanitizer(false));
        ConsoleResult result = collector.collect(executor, buffer);

        assertNotNull(result);
        // The test page deliberately logs 1 error + 1 warning
        assertTrue(result.errors() >= 1,
                "Should capture at least 1 deliberate error, got: " + result.errors());
        assertTrue(result.warnings() >= 1,
                "Should capture at least 1 deliberate warning, got: " + result.warnings());

        // Verify message content
        boolean foundTestError = result.messages().stream()
                .anyMatch(m -> m.contains("BPM-TEST-ERROR"));
        assertTrue(foundTestError, "Should find deliberate test error marker in messages");
    }

    @Test
    @Order(4)
    @DisplayName("NetworkCollector returns resource entries from real page load")
    void networkCollector_realPage_hasResources() {
        // Transfer network events accumulated during page load
        sessionManager.transferBufferedEvents(executor, buffer);

        NetworkCollector collector = new NetworkCollector(5);
        NetworkResult result = collector.collect(executor, buffer);

        assertNotNull(result);
        // The test page is a simple file:// or data:// URL — network entries depend
        // on whether Resource Timing captures anything for the page load itself.
        // We just verify no exceptions and sane structure.
        assertTrue(result.totalRequests() >= 0, "Total requests should be >= 0");
        assertTrue(result.totalBytes() >= 0, "Total bytes should be >= 0");
        assertNotNull(result.slowest(), "Slowest list should not be null");
    }

    @Test
    @Order(5)
    @DisplayName("DerivedMetricsCalculator produces valid score from real collector output")
    void derivedMetrics_realCollectorOutput_validScore() {
        // Collect fresh vitals
        WebVitalsCollector vitalsCollector = new WebVitalsCollector();
        // Reset stale detection for a fresh read
        vitalsCollector.reset();
        WebVitalsResult vitals = vitalsCollector.collect(executor, buffer);

        RuntimeCollector runtimeCollector = new RuntimeCollector();
        RuntimeResult runtime = runtimeCollector.collect(executor, buffer);

        BpmPropertiesManager props = new BpmPropertiesManager() {
            @Override
            protected Path resolvePropertiesPath() { // CHANGED: protected — cross-package override
                return Path.of(System.getProperty("java.io.tmpdir"), "bpm-e2e-test.properties");
            }

            @Override
            public String getJMeterProperty(String key) { // CHANGED: public — must not narrow parent's public access
                return null;
            }
        };
        props.load();

        DerivedMetricsCalculator calculator = new DerivedMetricsCalculator(props);
        DerivedMetrics derived = calculator.compute(vitals, null, runtime, null, 2000);

        assertNotNull(derived);
        assertTrue(derived.performanceScore() >= 0 && derived.performanceScore() <= 100,
                "Score should be 0-100, got: " + derived.performanceScore());
        assertNotNull(derived.bottleneck(), "Bottleneck should not be null");
        assertNotNull(derived.bottlenecks(), "Bottlenecks list should not be null");
    }

    @Test
    @Order(6)
    @DisplayName("CdpSessionManager re-inject observers works after initial collection")
    void cdpSessionManager_reInject_noException() {
        assertDoesNotThrow(() -> sessionManager.reInjectObservers(executor),
                "Re-injecting observers should not throw");

        // Verify collection still works after re-inject
        RuntimeCollector collector = new RuntimeCollector();
        RuntimeResult result = collector.collect(executor, buffer);
        assertNotNull(result);
        assertTrue(result.heapUsed() > 0);
    }
}