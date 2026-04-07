package io.github.sagaraggarwal86.jmeter.bpm.core;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Production {@link CdpCommandExecutor} implementation backed by a {@link ChromeDriver}.
 *
 * <p><strong>All Selenium imports are confined to this class.</strong> No other
 * BPM class should import Selenium types directly. This enables lazy class loading
 * and prevents {@link ClassNotFoundException} when Selenium is not on the classpath.</p>
 *
 * <p>Wraps the ChromeDriver's {@code executeCdpCommand} and {@code executeScript}
 * methods. The driver instance is not owned by this executor — it is managed by
 * the WebDriver Sampler. This executor only holds a reference for CDP operations.</p>
 */
public final class ChromeCdpCommandExecutor implements CdpCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChromeCdpCommandExecutor.class);

    private final ChromiumDriver driver;
    private volatile boolean closed;

    /**
     * Creates a new executor wrapping the given ChromeDriver.
     *
     * @param driver the ChromeDriver instance from the WebDriver Sampler;
     *               must not be null and must be a {@link ChromiumDriver}
     * @throws IllegalArgumentException if driver is null or not a ChromiumDriver
     */
    public ChromeCdpCommandExecutor(WebDriver driver) {
        if (driver == null) {
            throw new IllegalArgumentException("WebDriver must not be null");
        }
        if (!(driver instanceof ChromiumDriver chromiumDriver)) {
            throw new IllegalArgumentException(
                    "Expected ChromiumDriver but got: " + driver.getClass().getName());
        }
        this.driver = chromiumDriver;
        this.closed = false;
    }

    /**
     * Factory method that accepts a raw {@code Object} and casts to {@code WebDriver} internally.
     *
     * <p>This allows callers (e.g. {@code BpmListener}) to create executors without importing
     * Selenium types directly, supporting the lazy class loading requirement.</p>
     *
     * @param browserObject the browser object from JMeterVariables; must be a {@link WebDriver}
     * @return a new ChromeCdpCommandExecutor
     * @throws IllegalArgumentException if the object is null or not a ChromiumDriver
     */
    public static ChromeCdpCommandExecutor fromBrowserObject(Object browserObject) {
        if (browserObject == null) {
            throw new IllegalArgumentException("Browser object must not be null");
        }
        if (!(browserObject instanceof WebDriver webDriver)) {
            throw new IllegalArgumentException(
                    "Expected WebDriver but got: " + browserObject.getClass().getName());
        }
        return new ChromeCdpCommandExecutor(webDriver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes via {@link JavascriptExecutor#executeScript(String, Object...)}.</p>
     */
    @Override
    public Object executeScript(String script) {
        checkNotClosed();
        return ((JavascriptExecutor) driver).executeScript(script);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes via {@link ChromiumDriver#executeCdpCommand(String, Map)}.</p>
     */
    @Override
    public Map<String, Object> executeCdpCommand(String method, Map<String, Object> params) {
        checkNotClosed();
        return driver.executeCdpCommand(method, params);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Enables the domain by executing {@code <domain>.enable} CDP command
     * with an empty parameter map.</p>
     */
    @Override
    public void enableDomain(String domain) {
        checkNotClosed();
        driver.executeCdpCommand(domain + ".enable", Map.of());
        log.debug("BPM: Enabled CDP domain: {}", domain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Marks this executor as closed. Does not close the underlying
     * WebDriver — that is owned by the WebDriver Sampler.</p>
     */
    @Override
    public void close() {
        closed = true;
    }

    /**
     * Returns the underlying ChromiumDriver for session management operations
     * that require direct driver access (e.g. DevTools session creation).
     *
     * @return the ChromiumDriver instance
     */
    public ChromiumDriver getDriver() {
        return driver;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("CdpCommandExecutor has been closed");
        }
    }
}
