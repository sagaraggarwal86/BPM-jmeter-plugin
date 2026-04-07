package io.github.sagaraggarwal86.jmeter.bpm.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for BpmErrorHandler and LogOnceTracker.
 */
@DisplayName("BpmErrorHandler + LogOnceTracker")
class BpmErrorHandlerTest {

    private LogOnceTracker tracker;
    private BpmErrorHandler handler;

    @BeforeEach
    void setUp() {
        tracker = new LogOnceTracker();
        handler = new BpmErrorHandler(tracker);
    }

    @Test
    @DisplayName("New thread starts in HEALTHY state")
    void newThread_startsHealthy() {
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertFalse(handler.isThreadDisabled("Thread-1"));
    }

    @Test
    @DisplayName("Collection error transitions HEALTHY → RE_INIT_NEEDED")
    void collectionError_transitionsToReInitNeeded() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        assertEquals(BpmErrorHandler.ThreadState.RE_INIT_NEEDED, handler.getThreadState("Thread-1"));
        assertTrue(handler.needsReInit("Thread-1"));
    }

    @Test
    @DisplayName("Session error transitions to DISABLED")
    void sessionError_transitionsToDisabled() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        handler.handleSessionError("Thread-1", new RuntimeException("Re-init failed"));
        assertEquals(BpmErrorHandler.ThreadState.DISABLED, handler.getThreadState("Thread-1"));
        assertTrue(handler.isThreadDisabled("Thread-1"));
    }

    @Test
    @DisplayName("Successful re-init transitions RE_INIT_NEEDED → HEALTHY")
    void reInitSuccess_transitionsBackToHealthy() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        handler.markReInitSuccess("Thread-1");
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertEquals(1, handler.getReInitCount());
    }

    @Test
    @DisplayName("Thread states are isolated between different threads")
    void threadStates_isolated() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        assertEquals(BpmErrorHandler.ThreadState.RE_INIT_NEEDED, handler.getThreadState("Thread-1"));
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-2"));
    }

    @Test
    @DisplayName("reset() clears all state")
    void reset_clearsAllState() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        handler.reset();
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertEquals(0, handler.getFailureCount());
        assertEquals(0, handler.getReInitCount());
    }

    @Test
    @DisplayName("resetThread transitions DISABLED → HEALTHY and clears log-once entries")
    void resetThread_recoversFromDisabled() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        handler.handleSessionError("Thread-1", new RuntimeException("re-init failed"));
        assertTrue(handler.isThreadDisabled("Thread-1"));
        assertTrue(tracker.hasWarned("Thread-1", "session-disabled"));

        handler.resetThread("Thread-1");

        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertFalse(handler.isThreadDisabled("Thread-1"));
        // Log-once entries for this thread should be cleared
        assertFalse(tracker.hasWarned("Thread-1", "session-disabled"));
    }

    @Test
    @DisplayName("resetThread does not affect other threads")
    void resetThread_isolatedToThread() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        handler.handleSessionError("Thread-1", new RuntimeException("fail"));
        handler.handleCollectionError("Thread-2", new RuntimeException("error"));
        handler.handleSessionError("Thread-2", new RuntimeException("fail"));

        handler.resetThread("Thread-1");

        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertTrue(handler.isThreadDisabled("Thread-2"));
    }

    @Test
    @DisplayName("Collection error on already RE_INIT_NEEDED thread stays RE_INIT_NEEDED")
    void collectionError_onReInitNeeded_staysReInitNeeded() {
        handler.handleCollectionError("Thread-1", new RuntimeException("first"));
        handler.handleCollectionError("Thread-1", new RuntimeException("second"));
        assertEquals(BpmErrorHandler.ThreadState.RE_INIT_NEEDED, handler.getThreadState("Thread-1"));
        assertEquals(2, handler.getFailureCount());
    }

    @Test
    @DisplayName("Collection error on DISABLED thread stays DISABLED and increments failure count")
    void collectionError_onDisabled_staysDisabled() {
        handler.handleCollectionError("Thread-1", new RuntimeException("err"));
        handler.handleSessionError("Thread-1", new RuntimeException("fail"));
        int countBefore = handler.getFailureCount();
        handler.handleCollectionError("Thread-1", new RuntimeException("another"));
        assertTrue(handler.isThreadDisabled("Thread-1"));
        assertEquals(countBefore + 1, handler.getFailureCount());
    }

    @Test
    @DisplayName("resetThread on non-existent thread is a no-op")
    void resetThread_nonExistentThread_noOp() {
        assertDoesNotThrow(() -> handler.resetThread("Thread-99"));
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-99"));
    }

    @Test
    @DisplayName("Failure count accumulates across collection and session errors")
    void failureCount_accumulatesAcrossErrorTypes() {
        handler.handleCollectionError("Thread-1", new RuntimeException("a"));
        handler.handleSessionError("Thread-1", new RuntimeException("b"));
        assertEquals(2, handler.getFailureCount());
    }

    // LogOnceTracker tests

    @Test
    @DisplayName("warnOnce logs first occurrence and suppresses second")
    void warnOnce_logsOnceOnly() {
        tracker.warnOnce("Thread-1", "non-chrome", "msg");
        assertTrue(tracker.hasWarned("Thread-1", "non-chrome"));
        assertEquals(1, tracker.getLoggedCount());

        // Second call with same key — should not increase count
        tracker.warnOnce("Thread-1", "non-chrome", "msg2");
        assertEquals(1, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("warnOnce isolates by thread — same key different threads both log")
    void warnOnce_threadIsolation() {
        tracker.warnOnce("Thread-1", "non-chrome", "msg");
        tracker.warnOnce("Thread-2", "non-chrome", "msg");
        assertEquals(2, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker reset clears all tracked warnings")
    void trackerReset_clearsAll() {
        tracker.warnOnce("Thread-1", "key", "msg");
        tracker.reset();
        assertFalse(tracker.hasWarned("Thread-1", "key"));
        assertEquals(0, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker resetThread clears only that thread's warnings")
    void trackerResetThread_clearsOnlyTargetThread() {
        tracker.warnOnce("Thread-1", "key-a", "msg");
        tracker.warnOnce("Thread-1", "key-b", "msg");
        tracker.warnOnce("Thread-2", "key-a", "msg");
        assertEquals(3, tracker.getLoggedCount());

        tracker.resetThread("Thread-1");

        assertFalse(tracker.hasWarned("Thread-1", "key-a"));
        assertFalse(tracker.hasWarned("Thread-1", "key-b"));
        assertTrue(tracker.hasWarned("Thread-2", "key-a"));
        assertEquals(1, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker resetThread allows re-logging for the same thread")
    void trackerResetThread_allowsRelogging() {
        tracker.warnOnce("Thread-1", "key-a", "msg");
        assertTrue(tracker.hasWarned("Thread-1", "key-a"));

        tracker.resetThread("Thread-1");
        assertFalse(tracker.hasWarned("Thread-1", "key-a"));

        // Should be able to log again
        tracker.warnOnce("Thread-1", "key-a", "msg again");
        assertTrue(tracker.hasWarned("Thread-1", "key-a"));
        assertEquals(1, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker resetThread on thread with no warnings is safe")
    void trackerResetThread_noWarnings_safe() {
        assertDoesNotThrow(() -> tracker.resetThread("Thread-99"));
        assertEquals(0, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker markOnce returns true first time and false thereafter")
    void trackerMarkOnce_firstTrueThenFalse() {
        assertTrue(tracker.markOnce("Thread-1", "reinit-success"));
        assertFalse(tracker.markOnce("Thread-1", "reinit-success"));
        // Different thread, same key — first time
        assertTrue(tracker.markOnce("Thread-2", "reinit-success"));
    }
}
