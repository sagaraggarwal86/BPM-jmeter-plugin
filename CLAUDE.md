# CLAUDE.md

## Prohibitions [STRICT]

- Target **JMeter 5.6.3** exclusively — verify all APIs exist in 5.6.3 before using them
- Never change git history or Java 17 implementation
- Never assume — ask if in doubt
- Never make changes to code until user confirms
- Never change existing functionality or make changes beyond confirmed scope
- Only recommend alternatives when there is a concrete risk or significant benefit
- Analyze impact across dependent layers (collector → model → derived → GUI → output) before proposing changes
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**

## Workflow

- Interactive session — present choices one by one, unless changes are trivial and clearly scoped
- If my choices severely impact application integrity or cause excessive changes, briefly explain consequences and
  recommend better alternatives
- After all changes are finalized, self-check for regressions, naming consistency, and adherence to these rules
- Multi-file changes: present all files together with dependency order noted
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Rollback: revert to last explicitly approved file set, then ask how to proceed
- If context grows large, summarize confirmed state before continuing

## Response Style

- Concise — no filler phrases, no restating the request, no vague or over-explanatory content

## Communication

- Always provide honest feedback — flag risks, trade-offs, or better alternatives even if the user didn't ask.
  Do not agree silently if there is a concrete concern. Be direct, not diplomatic.
- For every decision point or design choice, present options in a concise table:

  | Option | Risk | Effort | Impact | Recommendation |
    |--------|------|--------|--------|----------------|

  Highlight the recommended option. Keep descriptions brief — one line per cell.

## Self-Maintenance

- **Auto-optimize CLAUDE.md**: After any session that adds or modifies design decisions, constraints, or architectural
  details in this file, review CLAUDE.md for redundancy, stale entries, and verbosity. Remove duplicates, compress
  verbose entries, and ensure every line carries actionable information. Do not wait for the user to request this.
- **Auto-compact**: When the conversation context grows large (many tool calls, long code reads, repeated file edits),
  proactively suggest `/compact` to the user before context becomes unwieldy. Do not wait until context is nearly full.
- **Auto-update README.md**: After any session that adds, removes, or modifies user-facing features (filters, columns,
  report panels, CLI options, configuration), update README.md to reflect the change. Keep feature tables, filter docs,
  GUI overview, and configuration sections current. Do not wait for the user to request this.

## Build Commands

```bash
mvn clean verify                          # Build + tests + JaCoCo coverage check
mvn clean verify -Pe2e                    # Build + E2E tests (requires Chrome)
mvn clean package -DskipTests             # Build only
mvn test -Dtest=JsonlWriterTest           # Single test class
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush  # Single test method
mvn clean deploy -Prelease                # Release to Maven Central
```

Requirements: JDK 17 only, Maven 3.8+. JaCoCo enforces **90%** line coverage excluding `gui/**`, `cli/**`, `report/**`,
`BpmListener`, `BpmCollector`, `ChromeCdpCommandExecutor`, `CdpSessionManager`, `LabelAggregate`, `FileOpenMode`,
`BpmTimeBucket`.

## Architecture

JMeter listener plugin capturing browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver
Sampler via Chrome DevTools Protocol. Includes HTML performance reports with trend analysis.

### Package Structure

| Package      | Key Classes                                                                                                                                                                                     |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`       | `BpmListener` (entry point), `BpmCollector` (singleton coordinator), `CdpSessionManager`, `MetricsBuffer`, `LabelAggregate`, `FileOpenMode`                                                     |
| `collectors` | `MetricsCollector<T>` + WebVitals, Network, Runtime, Console implementations; `DerivedMetricsCalculator`                                                                                        |
| `model`      | Records: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`, `ResourceEntry`                                                  |
| `config`     | `BpmPropertiesManager` — SLA thresholds, feature toggles, `bpm.properties`                                                                                                                      |
| `output`     | `JsonlWriter` (buffered, flush every record, append support), `SummaryJsonWriter`, `CsvExporter`                                                                                                |
| `gui`        | `BpmListenerGui`, `BpmTableModel`, `BpmCellRenderer`, `TooltipTableHeader`, `ColumnSelectorPopup`, `CheckBoxFilterButton`, `TotalPinnedRowSorter`                                               |
| `report`     | `BpmHtmlReportRenderer`, `BpmReportCoordinator`, `BpmReportLauncher`, `ReportDataBuilder`, `ReportData`, `ReportPanelBuilder`, `TrendAnalyzer`, `TrendData`                                     |
| `cli`        | `Main` (exit codes 0-4), `CliArgs` (-i, -o, --search, --regex, --exclude, --chart-interval, --scenario-name, --virtual-users), `BpmCliReportPipeline`, `TimeBucketBuilder`, `BpmParseException` |
| `util`       | `JsSnippets`, `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger`, `TypeConverters`, `BpmFileUtils`, `HtmlUtils`                                                                               |
| `error`      | `BpmErrorHandler`, `LogOnceTracker`                                                                                                                                                             |

### Core Design Decisions

- **No hard Selenium dep**: `Class.forName()` detects ChromeDriver. All Selenium code isolated in
  `ChromeCdpCommandExecutor`;
  rest uses `CdpCommandExecutor` interface.
- **JS-buffer capture**: `JsSnippets` injects hooks buffering events in `window.__bpm_*` arrays.
  `CdpSessionManager.transferBufferedEvents()` drains them. `REINJECT_OBSERVERS` resets CLS; `INJECT_OBSERVERS` does
  not.
- **BpmCollector singleton**: Ref-counted (`acquire`/`release`). Per-thread maps: `executorsByThread`,
  `buffersByThread`,
  `iterationsByThread` — cleared in `shutdown()` when refCount=0. `collectIfNeeded()` is the single collection entry
  point.
- **Output-path dedup**: `primaryByOutputPath` — first primary for a given path wins; duplicates skip JSONL writing.
- **Stateless collectors**: All four collectors are stateless singletons. Per-thread state in `MetricsBuffer`.
  Exception: `WebVitalsCollector` tracks previous LCP for SPA stale detection.
- **All runtime deps `provided`**: JMeter core, Selenium, Jackson on JMeter classpath. No shaded dependencies.
- **Pure observer**: Never crashes the test; all exceptions caught; graceful degradation.

### JMeter Lifecycle & Clone Delegation

- **Clone delegation**: `AbstractTestElement.clone()` copies all properties including custom ones. Transient fields
  (testInitialized, guiUpdateQueue, rawResults, jsonlWriter) are NOT shared. Per-thread clones delegate
  `sampleOccurred()` to the primary registered in `primaryByName`. Only the primary owns mutable state.
- **UUID dedup**: `deduplicateUuid()` in `configure()` detects UUID collision from copy-paste. Scans all BpmListener
  elements in the tree; if another owns the same UUID, assigns a fresh one. Prevents `primaryByName` key collision
  that caused sample double-counting.
- **Execution tree ≠ GUI tree**: `testStarted()` runs on execution-tree elements. `configure()` receives GUI-tree
  elements. `gui.testEnded()` persists rawResults from execution primary to GUI element via `setRawResults()`.
- **`testActuallyStarted`**: Instance flag set only after full `testStarted()` setup. `testEnded()` skips cleanup if
  false.
- **`cachedEngine`**: Captured at top of `testStarted()` before blocking dialog. `stopTestEngine()` uses cached ref +
  ActionRouter fallback.
- **`pendingFreshClear`**: `createTestElement()` strips properties + sets flag. `configure()` clears display and returns
  early for new elements.

### GUI Design (Aggregate Report Pattern)

Follows JMeter's `StatVisualizer` design:

- **Data ownership**: Data lives in the TestElement, not the GUI. GUI reads unconditionally in `configure()`.
- **Timer**: Start once in constructor, never stop. Direct model updates (`addOrUpdateResult` + `fireTableDataChanged`).
  Full `rebuildTableFromRaw()` only on filter change or file load.
- **Per-element settings**: Persist in TestElement properties via `modifyTestElement()`/`configure()`, not static
  caches.
- **Column model**: 18 total (10 always-visible + 8 toggleable). Column indices in `BpmConstants.COL_IDX_*`.

### Filtering System

- **Manual-only**: Filters apply only on "Apply Filters" button click — always enabled (even during test execution).
  `rebuildTableFromRaw()` is single source of truth for retroactive re-filtering.
- **Transaction filter**: Text field + RegEx checkbox + Include/Exclude combo. Applied in
  `BpmTableModel.getFilteredRows()`.
- **Stability filter**: Multi-select `CheckBoxFilterButton` with options: Stable, Minor Shifts, Unstable.
  `null` Set = no filter. Uses `Set.contains()` matching against `RowData.lastStabilityCategory`.
- **Improvement Area filter**: Multi-select `CheckBoxFilterButton` with options: None, plus 5 bottleneck types.
  `null` Set = no filter. Uses `Set.contains()` matching against `RowData.lastImprovementArea`.
- **Filter persistence**: Stability and Improvement Area persisted as comma-separated values in TestElement properties
  (`TEST_ELEMENT_STABILITY_FILTER`, `TEST_ELEMENT_IMPROVEMENT_FILTER`). `"All"` = no filter.
- **Offset reference from data**: Start/End offset filtering derives reference time from the first record's timestamp
  in `allRawResults`, not from `testStartTime`. Ensures correctness for live tests, file loads, and post-test filtering.
  `testStartTime` is only used for Test Time Info display. Both `rebuildTableFromRaw()` and report generation use
  this approach.
- **Clear All resets all listeners**: `clearData()` iterates all BpmListener elements in the tree and clears all filter
  properties (`START_OFFSET`, `END_OFFSET`, `TRANSACTION_NAMES`, `REGEX`, `INCLUDE`, `CHART_INTERVAL`,
  `STABILITY_FILTER`, `IMPROVEMENT_FILTER`).
- **`configuringElement` guard**: Suppresses spurious `applyAllFilters()` calls fired by programmatic UI updates
  during `configure()`.

### Error Recovery

- **DISABLED recovery**: When a thread reaches DISABLED (CDP re-init failed), `BpmCollector.collectIfNeeded()` checks
  for a new browser. If found, `errorHandler.resetThread()` transitions back to HEALTHY, clears stale CDP references,
  and collection resumes.
- **Pre-flight file scan**: First primary wins `preFlightDone.compareAndSet(false, true)`, scans all enabled
  BpmListeners. Single dialog lists all conflicts. Decision cached in `globalFileDecision` (OVERWRITE/DONT_START).

### Output

- **Output path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default `bpm-results.jsonl`.
- **JSONL**: One JSON object per line via `JsonlWriter`. Flush every record. Thread-safe (synchronized).
  Append mode support for file-exists dialog.

### HTML Report

- **All Java-generated**: 6 panels rendered entirely in Java. No external API calls.
- **CSS/JS**: `bpm-report.css` + `bpm-report.js` loaded from classpath, cached in static fields, inlined at render time.
  Chart.js + xlsx-js-style bundled as classpath resources with CDN fallback via `inlineOrCdn()`.
- **`ReportDataBuilder`**: Pre-computes SLA verdicts, risk assessments, best/worst performers, weighted score/LCP,
  error summaries, trend analysis. Returns `ReportData` record — single source of truth for all panels.
- **`ReportPanelBuilder`**: Template-based rendering with `{{placeholder}}` and `{{#section}}...{{/section}}` blocks.
- **`TrendAnalyzer`**: First/second half comparison. Requires ≥4 time buckets. Returns `TrendData`.
- **Panels**: Executive Summary, Performance Metrics, Performance Trends (6 Chart.js charts), SLA Compliance,
  Critical Findings, Risk Assessment.
- **Features**: ARIA tabs + keyboard nav, pagination + sorting, transaction search, styled Excel export,
  dark mode toggle (auto/dark/light), Chart.js CDN fallback.
- **CLI workflow**: `jmeter -n -t test.jmx -Jbpm.output=results.jsonl` → `bpm-report -i results.jsonl`
- **CLI exit codes**: 0=success, 1=bad args, 2=parse error, 3=write error, 4=unexpected.

### Resource Files

| File                                 | Purpose                                                                     |
|--------------------------------------|-----------------------------------------------------------------------------|
| `bpm-executive-summary.html`         | Executive Summary template                                                  |
| `bpm-risk-assessment.html`           | Risk Assessment template                                                    |
| `bpm-report.css`                     | All report CSS (layout, SLA colors, dark mode, cards, scroll shadows)       |
| `bpm-report.js`                      | Interactive JS (tabs, pagination, sorting, search, Excel export, dark mode) |
| `chart.umd.min.js`                   | Chart.js 4.4.1 bundled (CDN fallback)                                       |
| `xlsx-style.bundle.js`               | xlsx-js-style 1.2.0 bundled (CDN fallback)                                  |
| `messages.properties`                | JMeter resource bundle                                                      |
| `bpm-default.properties`             | Default config (metric toggles, SLA thresholds, bottleneck ratios)          |
| `META-INF/jmeter-plugins.properties` | Plugins Manager integration                                                 |

### Key Constraints

- `performanceScore` is `Integer` (nullable) — unboxing `null` to `int` will NPE and silently abort JSONL writes.
- **`BpmConstants` is the single source of truth** — never hardcode column indices, label strings, property keys,
  verdict strings (`VERDICT_*`), bottleneck labels (`BOTTLENECK_*`), trend labels (`TREND_*`), or display names
  (`BOTTLENECK_DISPLAY_NAMES`). Use `verdictToCss()`/`verdictToDisplay()`/`verdictToIcon()` for rendering.
  Use `HtmlUtils.severityTag()` for badge HTML.
- **JSONL schema is public** — `DerivedMetrics` and `BpmResult` `@JsonProperty` names are backward-compatible.
  Field renames are breaking changes.
- **TestElement property keys** (`BpmConstants.TEST_ELEMENT_*`) are stored in `.jmx` files — renaming breaks
  existing test plans. Includes `STABILITY_FILTER` and `IMPROVEMENT_FILTER` (comma-separated multi-select).
- Selenium types confined to `ChromeCdpCommandExecutor` — lazy class loading via `Class.forName()`.
- Chrome-only via CDP — acknowledged constraint, documented not hidden.
- UI preserves `AbstractListenerGui` and `Clearable` contracts.
- `TotalPinnedRowSorter` must pin TOTAL to last view row for all sort directions.
