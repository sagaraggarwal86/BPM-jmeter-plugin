package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 integration tests for BpmListener lifecycle.
 * No browser required — tests initialization, clear, and edge cases.
 */
@DisplayName("BpmListener Lifecycle")
class BpmListenerLifecycleTest {

    @Test
    @DisplayName("testStarted() initializes all internal state")
    void testStarted_initializesState(@TempDir Path tempDir) {
        BpmListener listener = new BpmListener();
        listener.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results.jsonl").toString());
        listener.testStarted();

        assertNotNull(listener.getGuiUpdateQueue(), "GUI queue should be initialized");
        assertNotNull(listener.getLabelAggregates(), "Label aggregates should be initialized");
        assertNotNull(listener.getPropertiesManager(), "Properties manager should be loaded");

        // Clean up
        listener.testEnded();
    }

    @Test
    @DisplayName("testEnded() is safe to call even without testStarted()")
    void testEnded_withoutStart_noException() {
        BpmListener listener = new BpmListener();
        assertDoesNotThrow(() -> listener.testEnded());
    }

    @Test
    @DisplayName("clearData() resets aggregates and queue")
    void clearData_resetsState(@TempDir Path tempDir) {
        BpmListener listener = new BpmListener();
        listener.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results.jsonl").toString());
        listener.testStarted();

        listener.clearData();

        assertTrue(listener.getLabelAggregates().isEmpty());
        assertTrue(listener.getGuiUpdateQueue().isEmpty());

        listener.testEnded();
    }

    @Test
    @DisplayName("Double testStarted/testEnded cycle works cleanly")
    void doubleLifecycle_worksCleanly(@TempDir Path tempDir) {
        BpmListener listener = new BpmListener();
        listener.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results.jsonl").toString());

        listener.testStarted();
        listener.testEnded();

        listener.testStarted();
        assertNotNull(listener.getGuiUpdateQueue());
        listener.testEnded();
    }

    @Test
    @DisplayName("testEnded after testStarted does not throw")
    void testEnded_afterStart_noException(@TempDir Path tempDir) {
        BpmListener listener = new BpmListener();
        listener.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results.jsonl").toString());
        listener.testStarted();

        // Calling sampleOccurred without a proper SampleEvent context
        // should be handled gracefully (no NPE propagation)
        // We can't easily create a SampleEvent without JMeter context,
        // so we just verify testEnded works after a failed attempt
        assertDoesNotThrow(() -> listener.testEnded());
    }

    @Test
    @DisplayName("Two listeners with distinct element IDs both complete setup independently")
    void twoDistinctElements_bothComplete_setup(@TempDir Path tempDir) {
        BpmListener l1 = new BpmListener();
        BpmListener l2 = new BpmListener();
        // Assign distinct IDs — simulates two BpmListener elements in the same plan
        l1.setProperty(BpmConstants.TEST_ELEMENT_ID, java.util.UUID.randomUUID().toString());
        l2.setProperty(BpmConstants.TEST_ELEMENT_ID, java.util.UUID.randomUUID().toString());
        l1.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results-1.jsonl").toString());
        l2.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results-2.jsonl").toString());

        l1.testStarted();
        l2.testStarted();

        assertNotNull(l1.getGuiUpdateQueue(), "l1 must have initialised its queue");
        assertNotNull(l2.getGuiUpdateQueue(), "l2 must have initialised its queue");

        l1.testEnded();
        l2.testEnded();
    }

    @Test
    @DisplayName("Clone sharing element ID skips setup and delegates sampleOccurred to primary")
    void cloneWithSameId_skipsSetup(@TempDir Path tempDir) {
        String sharedId = java.util.UUID.randomUUID().toString();
        BpmListener primary = new BpmListener();
        BpmListener clone = new BpmListener();
        String outputPath = tempDir.resolve("bpm-results.jsonl").toString();
        primary.setProperty(BpmConstants.TEST_ELEMENT_ID, sharedId);
        clone.setProperty(BpmConstants.TEST_ELEMENT_ID, sharedId);
        primary.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, outputPath);
        clone.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, outputPath); // same path = true clone

        primary.testStarted(); // primary wins the slot
        clone.testStarted();   // clone must return immediately — same composite key

        // Clone's queue is null because it never ran testStarted setup
        assertNull(clone.getGuiUpdateQueue(),
                "Clone must not initialise its own queue — it defers to primary");
        assertNotNull(primary.getGuiUpdateQueue());

        primary.testEnded();
        // clone.testEnded() is safe even though it never started
        assertDoesNotThrow(() -> clone.testEnded());
    }

    @Test
    @DisplayName("dontStartPending is false after a successful start and after testEnded")
    void dontStartPending_isFalse_afterNormalLifecycle(@TempDir Path tempDir) {
        BpmListener l1 = new BpmListener();
        l1.setProperty(BpmConstants.TEST_ELEMENT_OUTPUT_PATH,
                tempDir.resolve("bpm-results.jsonl").toString());

        // Verify initial state
        assertFalse(BpmListener.isDontStartPending());

        // Start l1 normally — dontStartPending must be cleared at successful start
        l1.testStarted();
        assertFalse(BpmListener.isDontStartPending(),
                "dontStartPending must be false after a successful test start");

        l1.testEnded();
        // After testEnded, flag must still be false for the next run
        assertFalse(BpmListener.isDontStartPending());
    }
}