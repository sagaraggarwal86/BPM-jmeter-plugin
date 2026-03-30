# BPM — Browser Performance Metrics · Session Prompt for Claude Sonnet 4.6

---

## 1. Project Snapshot

| Field | Value |
|---|---|
| **Artifact** | `io.github.sagaraggarwal86:bpm-jmeter-plugin` |
| **Version** | `0.1.0-SNAPSHOT` |
| **Language / Build** | Java 17, Maven |
| **JMeter target** | 5.6.3 (provided scope) |
| **Distributable** | Single fat JAR → `JMeter/lib/ext/` or Maven Central |
| **Package root** | `io.github.sagaraggarwal86.jmeter.bpm` |
| **Test framework** | JUnit 5 · Mockito 5 · JaCoCo (80 % line coverage floor) |
| **Key provided deps** | ApacheJMeter_core, selenium-chrome-driver, slf4j-api |
| **Test-only deps** | logback-classic |
| **CI/CD** | GitHub Actions — `build.yml` (matrix: windows + ubuntu, `mvn clean verify`) · `release.yml` (deploys to Maven Central on `v*.*.*` tag via `-P release`) · `codeql.yml` · `dependabot-auto-merge.yml` |
| **JMeter entry point** | `io.github.sagaraggarwal86.jmeter.bpm.gui.BpmListenerGui` (registered via `META-INF/jmeter-plugins.properties`) |
| **Properties file** | `$JMETER_HOME/bin/bpm.properties` (user-editable, auto-generated on first run) |
| **Bundled resources** | `bpm-default.properties` (template), `messages.properties` |
| **Plugin deps** | Requires `jpgc-webdriver` (WebDriver Sampler) to be installed |

---

## 2. Architecture Map

### 2.1 `core` package

| Class | Role | Key API |
|---|---|---|
| `BpmListener` | Main JMeter listener. Extends `AbstractTestElement`, implements `SampleListener`, `TestStateListener`, `Clearable`. Static singleton via `getActiveInstance()`. Owns `CdpSessionManager` per-thread, drives metric collection, writes JSONL, populates GUI queue, and builds `LabelAggregate` map. | `testStarted()`, `testEnded()`, `sampleOccurred()`, `clearData()`, `getActiveInstance()`, `getGuiUpdateQueue()`, `getLabelAggregates()`, `getPropertiesManager()` |
| `BpmListener.LabelAggregate` | Per-label running aggregate used for GUI score box and log summary. Null-safe: `getAverageScore()` returns `Integer` (null when no scored samples — SPA-stale labels). Tracks `frontendTime` and `headroom` with separate non-null counters. | `update()`, `getAverageScore()`, `getAverageLcp()`, `getAverageFcp()`, `getAverageTtfb()`, `getAverageFrontendTime()`, `getAverageHeadroom()`, `getPrimaryImprovementArea()` |
| `CdpSessionManager` | Manages Chrome DevTools Protocol session lifecycle per thread. Injects JavaScript observers (`WebVitals`, `LayoutObserver`, `NetworkObserver`). Returns `boolean` from `ensureObserversInjected()` to signal SPA navigation detected. Sets resource timing buffer size to 500 to prevent silent drops. | `openSession()`, `closeSession()`, `ensureObserversInjected()`, `reInjectObservers()`, `transferBufferedEvents()` |
| `CdpCommandExecutor` | Interface — CDP command contract. | `execute(String method, Map params)` |
| `ChromeCdpCommandExecutor` | Implements `CdpCommandExecutor` via Selenium `ChromeDriver.executeCdpCommand`. | |
| `MetricsBuffer` | Thread-safe ring buffer for CDP network/console events between sampler fire and `sampleOccurred`. | `drain()`, `push()` |

---

### 2.2 `collectors` package

| Class | Role | Key notes |
|---|---|---|
| `MetricsCollector` | Marker interface for all four collectors. | |
| `WebVitalsCollector` | Collects LCP, FCP, CLS, TTFB from the injected CDP observers. Per-action: `previousClsByThread` map for CLS delta. `lcp=0`/`fcp=0` → returns `null` (no event fired). `resetThreadState(threadName)` on SPA navigation signal. | Returns `WebVitalsResult` (nullable fields) |
| `NetworkCollector` | Drains `MetricsBuffer` for network response events. Returns top-N slowest `ResourceEntry` objects. | Returns `NetworkResult` |
| `RuntimeCollector` | Collects DOM node count, layout count delta, style recalc count delta from CDP runtime evaluation. Per-action deltas: `previousLayoutCountByThread`, `previousStyleRecalcByThread`. `resetThreadState(threadName)` on navigation. | Returns `RuntimeResult` |
| `ConsoleCollector` | Drains `MetricsBuffer` for console error/warning events. Applies `ConsoleSanitizer` when `security.sanitize=true`. | Returns `ConsoleResult` |
| `DerivedMetricsCalculator` | Zero-CDP-cost computation of all derived metrics from the four raw results. `detectImprovementAreas()` is first-match-wins with 5 priority levels. `computePerformanceScore()` returns `null` when total metric weight < `SCORE_MIN_WEIGHT` (0.45) — prevents fake score=100 on SPA-stale samples. | `compute(WebVitalsResult, NetworkResult, RuntimeResult, ConsoleResult, long) → DerivedMetrics` |

**Improvement Area priority order (first match wins):**
1. `Fix Network Failures` — `failedRequests > 0`
2. `Reduce Server Response` — `TTFB / LCP > 60%`
3. `Optimise Heavy Assets` — `slowest[0].duration / LCP > 40%`
4. `Reduce Render Work` — `renderTime / LCP > 60%`
5. `Reduce DOM Complexity` — `layoutCount > domNodes × 0.5`
6. `None` — no condition matched

---

### 2.3 `model` package

All model classes are **immutable records** with Jackson annotations.

| Class | Fields | Notes |
|---|---|---|
| `BpmResult` | `bpmVersion`, `timestamp`, `threadName`, `iterationNumber`, `samplerLabel`, `samplerSuccess`, `samplerDuration`, `webVitals`, `network`, `runtime`, `console`, `derived` | Top-level JSONL record. All sub-results nullable. |
| `WebVitalsResult` | `fcp (Long)`, `lcp (Long)`, `cls (Double)`, `ttfb (Long)` | All fields nullable — SPA-stale samples have null LCP/FCP/TTFB. |
| `NetworkResult` | `totalRequests`, `totalBytes (Long)`, `failedRequests`, `slowest (List<ResourceEntry>)` | |
| `RuntimeResult` | `heapUsed (Long)`, `domNodes`, `layoutCount`, `styleRecalcCount` | All counts are per-action deltas, not cumulative. |
| `ConsoleResult` | `errors`, `warnings`, `messages (List<String>)` | |
| `ResourceEntry` | `url`, `duration`, `size`, `startTime` | |
| `DerivedMetrics` | See §2.2 table below | 10-field record; 3 nullable fields. |

**`DerivedMetrics` record field order (JSONL schema, section 4.2):**

| Field | Type | Null when |
|---|---|---|
| `renderTime` | `long` | Never (0 for SPA) |
| `serverClientRatio` | `double` | Never (0.0 for SPA) |
| `frontendTime` | `Long` | SPA-stale (FCP or TTFB null) |
| `fcpLcpGap` | `long` | Never (0 for SPA) |
| `stabilityCategory` | `String` | SPA-stale (CLS null) |
| `headroom` | `Integer` | SPA-stale (LCP null) |
| `failedRequestRate` | `double` | Never (0.0 when no requests) |
| `improvementArea` | `String` | Never (`"None"` when no match) |
| `improvementAreas` | `List<String>` | Never (empty list when no match) |
| `performanceScore` | `Integer` | SPA-stale (total weight < 0.45) |

---

### 2.4 `config` package

| Class | Role |
|---|---|
| `BpmPropertiesManager` | Reads `bpm.properties` from `$JMETER_HOME/bin/`. Auto-generates from `bpm-default.properties` template on first run. Upgrades with backup on version mismatch. Exposes typed getters for all SLA thresholds, bottleneck ratios, metric enable flags, and behaviour flags. Falls back to `getJMeterProperty(key)` (JMeter properties) if key absent in `bpm.properties`. |

---

### 2.5 `output` package

| Class | Role |
|---|---|
| `JsonlWriter` | Appends `BpmResult` records to the JSONL output file. Flushes every `JSONL_FLUSH_INTERVAL` (10) records. Atomic-move write pattern. |
| `SummaryJsonWriter` | Writes `bpm-results-summary.json` at `testEnded()` for CI/CD integration. Contains overall verdict (PASS/FAIL), overall score, total samples, SLA breach count, and per-label detail. Null-safe: labels with null score are excluded from the weighted average. `lcp=0` renders as `"N/A"` verdict. |
| `CsvExporter` | Exports currently visible table rows to CSV on user request. |

---

### 2.6 `gui` package

| Class | Role |
|---|---|
| `BpmListenerGui` | Extends `AbstractListenerGui`, implements `Clearable`. **1,027 lines — known SRP debt.** Central GUI controller. Owns all Swing fields. Manages `BpmTableModel`, `TotalPinnedRowSorter`, `BpmCellRenderer`, filter state, and the 500 ms `javax.swing.Timer` for live updates. Delegates rendering to `BpmCellRenderer`. |
| `BpmListenerGui.BpmTableModel` | Extends `AbstractTableModel`. 18-column model. TOTAL row always last in `getFilteredRows()`. `getColumnClass()` provides per-column type hints to `TotalPinnedRowSorter`. |
| `BpmListenerGui.RowData` | Per-label accumulator. Null-safe for `frontendTime`, `headroom`, `stabilityCategory`, `performanceScore`. Separate non-null counters for new nullable columns. |
| `BpmListenerGui.TotalPinnedRowSorter` | Extends `TableRowSorter<BpmTableModel>`. Installs a `RowFilter` that excludes the last model row (TOTAL) from sorting. Overrides `getViewRowCount()`, `convertRowIndexToModel()`, `convertRowIndexToView()` to inject TOTAL back at the last view position. Per-column `Comparator` instances handle "—" sentinels, `%`-formatted strings, and numeric types. |
| `BpmListenerGui.BpmCellRenderer` | Row tinting by score (amber/red). SLA colours per column. Value-level tooltips for `Improvement Area` and `Stability` columns. Left-align for text columns (Label, Improvement Area, Stability); right-align for all others. |
| `BpmListenerGui.TooltipTableHeader` | Shows per-column header tooltips from `BpmConstants.getTooltip()` on hover. |
| `ColumnSelectorPopup` | Popup menu to toggle visibility of the 8 raw metric columns. Always-visible columns (0–9) cannot be hidden. |

**18-column model (always-visible: 0–9, raw/toggleable: 10–17):**

| Index | Name | Type | Notes |
|---|---|---|---|
| 0 | Label | String | Always visible; sort alphabetic |
| 1 | Smpl | int | Sample count |
| 2 | Score | int / "—" | "—" when SPA-stale |
| 3 | Rndr(ms) | long | LCP − TTFB |
| 4 | Srvr(%) | String | `"30.00%"` format |
| 5 | Front(ms) | long / "—" | FCP − TTFB |
| 6 | Gap(ms) | long | LCP − FCP |
| 7 | Stability | String | Stable / Minor Shifts / Unstable |
| 8 | Headroom | String | `"95%"` format; "—" for SPA |
| 9 | Improvement Area | String | Left-aligned; value tooltip on hover |
| 10 | FCP(ms) | long | Raw; off by default |
| 11 | LCP(ms) | long | Raw; off by default |
| 12 | CLS | String | `"0.022"` format |
| 13 | TTFB(ms) | long | Raw; off by default |
| 14 | Reqs | int | Avg per sample |
| 15 | Size(KB) | long | Avg per sample |
| 16 | Errs | int | Cumulative |
| 17 | Warns | int | Cumulative |

**State persistence** (all via `TestElement` properties stored in `.jmx`):
`bpm.outputPath`, `bpm.startOffset`, `bpm.endOffset`, `bpm.transactionNames`, `bpm.regex`, `bpm.include`

---

### 2.7 `util` package

| Class | Role |
|---|---|
| `BpmConstants` | **665 lines. Single source of truth** for all column indices, headers, tooltips, Improvement Area labels, Stability labels, SLA defaults, property keys, `TEST_ELEMENT_*` keys, score weights, GUI timing constants. `getTooltip(int)` dispatches column header tooltips. `getImprovementAreaValueTooltip(String)` and `getStabilityValueTooltip(String)` dispatch cell-level tooltips. |
| `BpmDebugLogger` | Gated debug logger. `enabled` flag set once at `testStarted()` from `bpm.debug` property. All methods are no-ops when disabled. `logDerivedMetrics(String, Integer, String)` — nullable score, renders null as `"—"`. |
| `JsSnippets` | All CDP JavaScript injection strings as named constants. Includes `SET_RESOURCE_BUFFER_SIZE` (500 entries). |
| `ConsoleSanitizer` | Strips URLs, IPs, stack traces, and tokens from console messages when `security.sanitize=true`. |

---

### 2.8 `error` package

| Class | Role |
|---|---|
| `BpmErrorHandler` | Centralised error classification and structured log output. One-line-per-event format. |
| `LogOnceTracker` | Suppresses repeated identical error log entries per test run. |

---

## 3. Key Invariants

### Performance scoring
- **Weights**: LCP 40%, FCP 15%, CLS 15%, TTFB 15%, JS Errors 15%
- **Thresholds per metric**: Good → 100 pts, Needs Work → 50 pts, Poor → 0 pts
- **`SCORE_MIN_WEIGHT = 0.45`**: Score is `null` when available metric weight < 0.45. SPA-stale samples (only CLS 0.15 + errors 0.15 = 0.30) always produce null score. `null` means "insufficient data", never 0 or 100.
- **Rounding**: `Math.round()` (half-up) — not `round()` (banker's rounding).

### Per-action accuracy
- All Web Vitals metrics are per-action deltas, not cumulative session values.
- CLS delta tracked via `previousClsByThread` map; reset on navigation signal.
- `layoutCount` and `styleRecalcCount` are per-action deltas via `previousLayoutCountByThread`.
- LCP/FCP = 0 from CDP → treated as null (no event fired for that action), not as 0 ms.
- SPA navigation detected by `ensureObserversInjected()` returning `true` → triggers `resetThreadState()` on both `WebVitalsCollector` and `RuntimeCollector`.

### SPA-stale detection
- SPA actions (click without full page load) produce no LCP, FCP, or TTFB events.
- Such samples get: `lcp=null`, `fcp=null`, `ttfb=null`, `frontendTime=null`, `headroom=null`, `performanceScore=null`.
- CLS delta and layout delta are still captured for SPA actions (incremental shifts).
- `improvementArea` and `stabilityCategory` are still computed for SPA actions from available data.

### JSONL schema integrity
- `bpm.properties` key strings — stored in `.jmx` files; renaming breaks backward compatibility.
- `DerivedMetrics` JSON property names (`improvementArea`, `improvementAreas`, `frontendTime`, `stabilityCategory`, `headroom`) — JSONL schema; renaming breaks existing output files.
- `BpmResult` field names — same constraint.
- `BpmConstants.COL_IDX_*` values — CSV export and `RowData.getColumn()` depend on positional indices; shifting without updating all switch cases breaks the table.
- `BpmConstants.ALL_COLUMN_HEADERS` array order — must match column index constants exactly.

### GUI contracts
- `BpmListenerGui.configure()` with a fresh listener (null queue) must clear the GUI display — prevents data inheritance from an already-running listener (Bug #2 pattern).
- `testStarted()` disables Browse button, Save button, and makes filename field non-editable.
- `testEnded()` re-enables all controls.
- `clearData()` / `clearDisplayOnly()` must restore all controls to their default editable/enabled state.
- `TotalPinnedRowSorter` must maintain TOTAL at the last view row for all sort directions — never sort TOTAL with the data rows.
- `getColumnClass()` in `BpmTableModel` must match what `TotalPinnedRowSorter.buildComparator()` expects.

### Hard constraints
- **Never change Java version** (must remain Java 17).
- **Never alter git history**.
- **CDP is Chrome-only** — do not attempt Selenium WebDriver integration with non-Chrome browsers.
- **Fat JAR is the only distributable artifact** — no multi-module split.
- **`BpmConstants` is the single source of truth** for all labels, column indices, and property keys — never hardcode these strings elsewhere.
- **`DerivedMetricsCalculator.detectImprovementAreas()` is the single source of truth for improvement area logic** — never duplicate detection conditions in the GUI or output layer.
- **`performanceScore` is `Integer` (nullable)** — never unbox without null check; unboxing `null` to `int` will NPE and silently abort JSONL writes.
- **`BpmDebugLogger.logDerivedMetrics()` takes `Integer` (nullable) score** — same reason as above.
- **`SummaryJsonWriter.buildSummaryJson()` must null-check score** before `((Number) stat.get("score")).intValue()` — was the cause of `testEnded` WARN.
- **Column 0 (Label) must always remain visible** — `ColumnSelectorPopup` must not expose it.
- **Always-visible columns (0–9) must never be toggled off** — `ColumnSelectorPopup` and `applyColumnVisibility()` only operate on raw columns (10–17).

### AI analysis (planned — standalone)
BPM will implement AI analysis directly, with no dependency on JAAR. The JSONL schema is
self-contained and AI-ready — every record carries all raw and derived metrics needed for analysis
without external context. The `derived.improvementAreas` array is the primary AI correlation
signal. The AI capability will be provider-agnostic (OpenAI-compatible endpoints), consistent with
the approach proven in JAAR. No AI functionality exists in v0.1.0-SNAPSHOT.
- `BpmConstants.TEST_ELEMENT_*` property key strings — stored in `.jmx` files.
- `DerivedMetrics` Jackson `@JsonProperty` names — JSONL schema; existing output files depend on them.
- `SCORE_MIN_WEIGHT = 0.45` — changing this changes which samples get null scores.
- `BOTTLENECK_NONE = "None"` — the "no issue" sentinel; downstream tools (JAAR) may test for this value.
- Performance score weight constants (`SCORE_WEIGHT_LCP=0.40`, etc.) — changing weights changes all historical scores.
- SLA default thresholds — changing breaks existing `bpm.properties` comparisons.

---

## 4. Current State — Open Items

| # | Area | Description | Status |
|---|---|---|---|
| 1 | `gui` | `BpmListenerGui` exceeds 300-line SRP guideline (1,027 lines). Splitting requires a dedicated GUI test strategy. Documented in class. | [ ] Open — deferred |
| 2 | `gui` | No test coverage for GUI classes (`BpmListenerGui`, `ColumnSelectorPopup`, `TotalPinnedRowSorter`, `BpmCellRenderer`). Excluded from JaCoCo. | [ ] Open — blocked on GUI test harness |
| 3 | `gui` | Offset filtering (Start/End Offset) is applied at ingest time in `drainGuiQueue()`. Retroactive re-filtering on Apply button only re-evaluates the transaction name filter, not offset. Limitation: offset filter cannot be applied to already-aggregated data without storing raw records. | [ ] Open — by design; documented |
| 4 | `core` | First-sample contamination: iter=1 UI_001 (first SPA click after a full page load) collects page-load resources from the preceding action's network buffer. Mitigation: add a dummy no-op sampler before the first SPA click in the JMX. | [ ] Open — structural; not fixable in BPM |
| 5 | `core` | `BpmListener` (~974 lines) is approaching SRP limit. `LabelAggregate` inner class and summary writer logic are candidates for extraction. | [ ] Open — low priority |
| 6 | `build` | JaCoCo coverage floor is 80 %. Any new production code without tests will fail CI. | [ ] Ongoing gate |
| 7 | `output` | `SummaryJsonWriter` uses `bottleneck` as the JSON key name in the per-label stat map (passed via `BpmListener.writeSummaryJson()`). Should be renamed to `improvementArea` for consistency with `DerivedMetrics` schema. | [ ] Open — minor schema inconsistency |
| 8 | `util` | `BpmConstants` at 665 lines. Consider splitting into `BpmColumnConstants` and `BpmSlaConstants` when next column or SLA additions are made. | [ ] Open — low priority |
| 9 | `ai` (new package) | Standalone AI analysis planned — provider-agnostic (OpenAI-compatible), no JAAR dependency. JSONL schema is already AI-ready. Design and implementation not yet started. | [ ] Open — planned next phase |

---

## 5. Change Log (append-only)

| Session Date | Area Changed | Description | Status |
|---|---|---|---|
| 2026-03-27 | `core`, `collectors`, `gui`, `output`, `util` | Full 13-phase ground-up implementation: all collectors, CDP session management, GUI, 14 test files, CI/CD workflows. | Done |
| 2026-03-28 | `model`, `collectors`, `util`, `gui` | Per-action accuracy fixes: CLS delta, LCP/FCP null-on-zero, layout delta, resource buffer size 500. Null score for SPA-stale (SCORE_MIN_WEIGHT). NPE fix in BpmDebugLogger (Integer score). NPE fix in SummaryJsonWriter (null score). Column model: 15→18 columns; Bottleneck→ImprovementArea rename; 3 new derived columns (Front(ms), Stability, Headroom); value tooltips. | Done |
| 2026-03-29 | `gui` | Feature #1–4 + Bug #1–2: filter Apply button; Browse/Save disabled during test; TOTAL pinned to bottom (TotalPinnedRowSorter); full clear on Clear/Clear All; per-column sort comparators; fresh listener shows blank GUI. | Done |
| <!-- append rows here --> | | | |

---

### How to update the Change Log

At the end of every session, paste the following into the chat:

```
Update Change Log: [date] | [area] | [what changed] | [done/in-progress]
```

Claude will append a new row to the table and confirm. Never edit past rows.

---

## 6. Standing Instructions for Claude

1. **Never change Java version or alter git history.**
2. **Never assume — ask if in doubt.**
3. **Never make code changes until explicitly confirmed.**
4. **Never change existing functionality beyond confirmed scope.**
5. **If uncertain, state uncertainty explicitly and ask.**
6. **Only recommend alternatives when there is a concrete risk or significant benefit.**
7. **Interactive session — present choices one at a time, unless changes are trivial and clearly scoped.**
8. **If choices severely impact application integrity or cause excessive changes, briefly explain consequences and recommend better alternatives.**
9. **After all changes are finalised, self-check for regressions, naming consistency, and adherence to these rules before presenting files.**
10. **Analyse impact across dependent layers (collector → model → derived → GUI → output) before proposing changes.**
11. **Code changes: present full file with changes marked as `// CHANGED`.**
12. **Multi-file changes: present all files together with dependency order noted.**
13. **Conflicting requirements: flag the conflict, pause, and wait for decision.**
14. **Rollback: revert to last explicitly approved file set, then ask how to proceed.**
15. **Align test expectations to production behaviour, not the reverse.**
16. **All public/package-private methods must have Javadoc.**
17. **Any new class exceeding 300 lines triggers an SRP review comment.**
18. **Maintain 80 % JaCoCo line coverage — do not add production code without corresponding tests unless the class is already in the JaCoCo exclusion list (all `gui` classes).**
19. **`performanceScore` is `Integer` (nullable) everywhere — never pass it as primitive `int`.**
20. **`BpmConstants` is the single source of truth — never hardcode column indices, label strings, or property keys outside it.**
21. **Accuracy first**: any metric computation change requires a reanalysis pass against `results.jsonl` before the change is accepted.
22. **JSONL schema is public** — any field rename or type change in `DerivedMetrics` or `BpmResult` is a breaking change and must be explicitly flagged.

---

## 7. Session Kick-off Template

Copy and paste at the start of each new session:

```
Context:
  Project: BPM — Browser Performance Metrics (io.github.sagaraggarwal86:bpm-jmeter-plugin)
  Version: 0.1.0-SNAPSHOT | Java 17 | Maven | JMeter 5.6.3
  Packages: core · collectors · model · config · output · gui · util · error
  Coverage floor: 80% (JaCoCo). GUI classes excluded from coverage.
  Key constraints:
    - performanceScore is Integer (nullable) everywhere — null means SPA-stale, not 0 or 100
    - BpmConstants is single source of truth for all column indices, labels, and property keys
    - JSONL schema (DerivedMetrics, BpmResult) is public — field renames are breaking changes
    - TotalPinnedRowSorter must pin TOTAL to last row in all sort directions
    - BpmListenerGui is known SRP debt (~1027 lines)

Today's goal: [GOAL]

Constraints: [any session-specific limits, e.g. "no new dependencies", "GUI-only changes", "tests must pass on Windows + Ubuntu"]

Reference the Change Log and Open Items before proposing anything.
```
