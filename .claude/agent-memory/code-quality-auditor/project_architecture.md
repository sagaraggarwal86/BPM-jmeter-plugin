---
name: BPM Plugin Architecture Patterns
description: Recurring intentional design patterns and known quality gaps in the BPM codebase — established as of the 2026-03-31 audit
type: project
---

## Intentional Patterns (do not flag as violations)

- `Class.forName("org.openqa.selenium.chrome.ChromeDriver")` in `BpmListener` — deliberate lazy Selenium check; avoids
  hard dependency at class load time.
- All Selenium types confined to `ChromeCdpCommandExecutor`; rest of codebase uses `CdpCommandExecutor` interface only.
- `static final ConcurrentHashMap<String, BpmListener> primaryByName` — per-element-name clone registry replacing the
  old global `AtomicBoolean testStartLock`. Intentional; enables multiple BPM elements in one test plan.
- `static volatile BpmListener activeInstance` — coexists with `primaryByName`; used by GUI (`drainGuiQueue`) to find
  the currently active instance. Both registries must be maintained.
- `static volatile boolean dontStartPending` — set when file-exists dialog resolves to DONT_START; blocks subsequent
  clone `testStarted()` calls; cleared in `testEnded()`.
- All runtime deps (`jmeter`, `selenium`, `jackson`) are `provided` scope — plugin JAR ships zero additional
  dependencies.
- JaCoCo excludes `gui/**`, `BpmListener`, `ChromeCdpCommandExecutor`, `CdpSessionManager` from 84% coverage
  enforcement.
- `SummaryJsonWriter` is intentionally disabled in `BpmListener` (pending re-enable); the class and its tests still
  compile and pass.

## Known Quality Gaps (chronic, flagged in audits)

- `BpmListener.java` (~1200 lines) and `BpmListenerGui.java` (~1400 lines) are God classes. Both need extraction.
- 200+ `// CHANGED:` inline annotations saturate the codebase — sprint-era noise, to be stripped before release.
- `log.warn(..., e.getMessage())` pattern (instead of `log.warn(..., e)`) loses stack traces in `sampleOccurred()` and
  `testEnded()` hot paths. This is the most impactful recurring flaw.
- `allRawResults` in `BpmListenerGui` is an unbounded `ArrayList` rebuilt in full on every 500ms timer drain (O(N) EDT
  rebuild on every drain cycle).
- Static mutable state (`primaryByName`, `dontStartPending`, `activeInstance`) on a JMeter element subclass creates test
  isolation failures in `BpmListenerLifecycleTest` — `primaryByName` is never cleared between tests.
- `BpmCellRenderer.applySlaColor()` colours SPA-stale "—" score cells red (treats "—" as 0 = poor) because `getScore()`
  returns 0 when no scored samples exist.
- New behavioral mechanisms introduced in sprint (UUID registry, DONT_START propagation, `pendingFreshClear` handshake)
  are untested despite surrounding green tests.
- `getImprovementAreaValueTooltip()` and `getStabilityValueTooltip()` in `BpmConstants` match on raw string literals
  instead of defined constants — silent breakage if a constant value changes.
- `RowData.getColumn()` uses `Math.max(sampleCount, 1)` as denominator, silently returning 0 instead of "—" when
  sampleCount is 0.
- `printLogSummary()` format header still reads "Bottleneck" despite the rename to "Improvement Area".

## Why: these are structural debts from rapid iterative bug-fixing sprints, not ignorance of the patterns.

## Audit History

- 2026-03-31: Full 14-section audit of BpmListener.java, BpmListenerGui.java, BpmConstants.java (Draft/fixingbugs1
  branch)
