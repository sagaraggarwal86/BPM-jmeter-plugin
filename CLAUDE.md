# CLAUDE.md

## Prohibitions [STRICT]

- Target JMeter 5.6.3 exclusively — verify all APIs, interfaces, and classes exist in 5.6.3 before using them
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
- After all changes are finalized, self-check for regressions, naming consistency, and adherence to these rules before
  presenting files
- Analyze impact across dependent layers (API → service → model) before proposing changes
- Code changes: present full file with changes marked as // CHANGED
- Multi-file changes: present all files together with dependency order noted
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Rollback: revert to last explicitly approved file set, then ask how to proceed
- If context grows large, summarize confirmed state before continuing

## Response Style

- Concise — no filler phrases, no restating the request, no vague or over-explanatory content

## Role

- Act as a senior full-stack Java engineer with DevOps, QA, security, architecture, technical documentation, and UI/UX
  expertise

## Skill Set

- JMeter specialist (distributed systems, JVM tuning, network diagnostics, load testing analysis)
- Java 17 application design & development using Maven
- JMeter Plugin development for JMeter 5.6.3
- CI/CD pipelines, GitHub integration
- Complex Java system design
- Concise & unambiguous documentation
- UI/UX design, Project management
- Exception handling, Performance engineering

## Build Commands

```bash
mvn clean verify                          # Build + tests + JaCoCo coverage check
mvn clean verify -Pe2e                    # Build + E2E tests (requires Chrome)
mvn clean package -DskipTests             # Build only
mvn test -Dtest=JsonlWriterTest           # Single test class
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush  # Single test method
mvn clean deploy -Prelease                # Release to Maven Central
```

Requirements: JDK 17 only, Maven 3.8+. JaCoCo enforces **84%** line coverage excluding `gui/**`, `cli/**`, `report/**`,
`BpmListener`, `ChromeCdpCommandExecutor`, `CdpSessionManager`, `BpmTimeBucket`.

## Architecture

JMeter listener plugin capturing browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver
Sampler via Chrome DevTools Protocol. Includes HTML performance reports with trend analysis.

### Package Structure

| Package      | Responsibility                                                                                                                                                                                                                                                                                                                                                     |
|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`       | `BpmListener` (entry point, SampleListener/TestStateListener), `BpmCollector` (singleton coordinator: CDP sessions, collectors, ref-counted lifecycle), `CdpSessionManager` (CDP lifecycle), `MetricsBuffer`, `LabelAggregate`, `FileOpenMode` (enum: OVERWRITE/DONT_START)                                                                                        |
| `collectors` | `MetricsCollector<T>` + 4 implementations (WebVitals, Network, Runtime, Console), `DerivedMetricsCalculator`                                                                                                                                                                                                                                                       |
| `model`      | Data records: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`, `ResourceEntry` (network resource detail record)                                                                                                                                                                               |
| `config`     | `BpmPropertiesManager` — SLA thresholds, feature toggles, `bpm.properties` management                                                                                                                                                                                                                                                                              |
| `output`     | `JsonlWriter` (buffered, flush every 1 record, append mode support), `SummaryJsonWriter`, `CsvExporter`                                                                                                                                                                                                                                                            |
| `gui`        | `BpmListenerGui` (Swing table + report button), `BpmTableModel`, `BpmCellRenderer` (SLA color-coded cells), `TooltipTableHeader`, `ColumnSelectorPopup`, `TotalPinnedRowSorter`                                                                                                                                                                                    |
| `report`     | `BpmHtmlReportRenderer` (Chart.js), `BpmHtmlReportRenderer.RenderConfig` (metadata + SLA thresholds), `BpmReportCoordinator` (returns `GenerateResult` with html + suggestedFilename), `BpmReportLauncher`, `ReportDataBuilder` (pre-computed verdicts + risks), `ReportData`, `ReportPanelBuilder` (template-based panel rendering), `TrendAnalyzer`, `TrendData` |
| `cli`        | `Main` (entry point, exit codes 0-4), `CliArgs` (zero-dep parser: -i, -o, --search, --regex, --exclude, --chart-interval, --scenario-name, --virtual-users), `BpmCliReportPipeline`, `TimeBucketBuilder`, `BpmParseException`                                                                                                                                      |
| `util`       | `JsSnippets`, `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger`, `TypeConverters` (shared toDouble/toLong/toInt), `BpmFileUtils` (ensureParentDirectories), `HtmlUtils` (escapeHtml, severityTag)                                                                                                                                                               |
| `error`      | `BpmErrorHandler`, `LogOnceTracker`                                                                                                                                                                                                                                                                                                                                |

### Key Design Decisions

- **No hard Selenium dep**: `BpmListener` uses `Class.forName()` to detect ChromeDriver. All Selenium code isolated in
  `ChromeCdpCommandExecutor`; rest uses `CdpCommandExecutor` interface.
- **JS-buffer capture**: Injects JS hooks (`JsSnippets`) that buffer events in `window.__bpm_*` arrays.
  `CdpSessionManager.transferBufferedEvents()` drains them. `REINJECT_OBSERVERS` resets CLS accumulator;
  `INJECT_OBSERVERS` does not.
- **BpmCollector singleton**: Ref-counted (`acquire`/`release`) coordinator owning CDP sessions, all 4 collectors,
  and `DerivedMetricsCalculator`. Per-thread maps: `executorsByThread`, `buffersByThread`, `iterationsByThread` —
  all cleared in `shutdown()` when refCount reaches 0. `collectIfNeeded()` is the single entry point for metric
  collection per sample.
- **Output-path dedup**: `primaryByOutputPath` prevents multiple BpmListeners writing to the same JSONL file.
  First primary for a given path wins; subsequent primaries for the same path skip setup.
- **Stateless collectors**: All four collectors (WebVitals, Network, Runtime, Console) are stateless singletons.
  Per-thread state in `MetricsBuffer`. Exception: `WebVitalsCollector` tracks previous LCP for SPA stale detection.
- **All runtime deps `provided`**: JMeter core, Selenium, Jackson on JMeter classpath. No shaded dependencies.
- **Clone delegation**: JMeter's `AbstractTestElement.clone()` creates new instances via the no-arg
  constructor — transient fields (testInitialized, guiUpdateQueue, rawResults, jsonlWriter) are NOT shared.
  Per-thread clones delegate `sampleOccurred()` to the primary registered in `primaryByName`.
  Only the primary (execution-tree element that ran `testStarted()`) owns mutable state.
- **Execution tree ≠ GUI tree**: JMeter creates a separate execution tree for test runs. `testStarted()`
  runs on execution-tree elements (different object identity from GUI-tree elements). `configure()` receives
  GUI-tree elements. `gui.testEnded()` must persist rawResults from the execution primary to the GUI element
  via `setRawResults()` so data survives post-test `configure()` calls.
- **Aggregate Report pattern**: GUI follows JMeter's `StatVisualizer` design — data lives in the TestElement
  (`BpmListener.rawResults`), GUI reads from it unconditionally in `configure()`. Timer runs forever with
  direct model updates (`addOrUpdateResult` + `fireTableDataChanged`), no queue-drain-rebuild. Full
  `rebuildTableFromRaw()` only on filter change or file load. Column visibility persisted in TestElement
  properties, not static caches.
- **Pre-flight file scan**: First primary wins `preFlightDone.compareAndSet(false, true)`, scans ALL enabled
  BpmListeners via `GuiPackage.getTreeModel().getNodesOfType()`. Single dialog lists all conflicts. Decision cached in
  `globalFileDecision` (OVERWRITE/DONT_START). CLI always overwrites.
- **Output path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default `bpm-results.jsonl`.
- **`pendingFreshClear`**: `createTestElement()` strips properties + sets flag. `configure()` clears display and returns
  early for new elements.
- **`testActuallyStarted`**: Instance flag set only after full `testStarted()` setup. `testEnded()` skips cleanup if
  false.
- **`cachedEngine`**: Captured at top of `testStarted()` before blocking dialog. `stopTestEngine()` uses cached ref +
  ActionRouter fallback.
- **Manual-only filtering**: Filters apply only on "Apply Filters" button click — always enabled (even during test
  execution). `rebuildTableFromRaw()` is single source of truth.
- **DISABLED recovery**: When a thread reaches `DISABLED` (CDP re-init failed), `BpmCollector.collectIfNeeded()`
  checks if a new browser is available. If so, `errorHandler.resetThread()` transitions back to `HEALTHY`,
  stale CDP references (`VAR_BPM_DEV_TOOLS`, `VAR_BPM_EVENT_BUFFER`, executorsByThread, buffersByThread`) are
  cleared, and collection resumes with the fresh browser. This handles WebDriverConfig creating a new
  ChromeDriver after a crash.
- **Retroactive filtering**: `allRawResults` (ArrayList) stores every BpmResult for the current element.
  `rebuildTableFromRaw()` replays all raw results through current filter settings, enabling retroactive
  offset/transaction re-filtering without re-running the test.
- **Clear All resets all listeners**: `BpmListenerGui.clearData()` iterates all BpmListener elements in the
  tree (not just the active one) and clears filter properties (`TEST_ELEMENT_START_OFFSET`, `END_OFFSET`,
  `TRANSACTION_NAMES`, `REGEX`, `INCLUDE`, `CHART_INTERVAL`) so inactive listeners also reset.

### HTML Report Architecture

- **All Java-generated**: All 6 panels are rendered entirely in Java. No external API calls.
- **CSS/JS externalized**: Report CSS in `bpm-report.css`, interactive JS in `bpm-report.js` — both loaded from
  classpath and cached in static fields at class init. CSS/JS are inlined into the HTML `<style>`/`<script>` tags
  at render time. Chart.js and xlsx-js-style bundled as classpath resources with CDN fallback via `inlineOrCdn()`.
- **`ReportDataBuilder`**: Pre-computes SLA verdicts (`VERDICT_GOOD`/`VERDICT_NEEDS_WORK`/`VERDICT_POOR`),
  risk assessments (headroom, boundary, SPA blind spots), best/worst performers, weighted score/LCP, error
  summaries, and trend analysis from per-label aggregates. Returns structured `ReportData` record (single source
  of truth for all panels). No label cap — all transactions processed.
- **`ReportPanelBuilder`**: Template-based rendering for Executive Summary and Risk Assessment panels.
  Templates cached in static fields at class init. Replaces `{{placeholder}}` markers, handles conditional
  `{{#section}}...{{/section}}` blocks. Breach items grouped by bottleneck type with nested expandable
  page lists. Error items use ratio-based relative frequency labels.
- **`TrendAnalyzer`**: Splits time-series into first/second half, computes direction (RISING/FALLING/STABLE),
  percentage changes, and pre-written alert sentences. Returns `TrendData` record. Requires ≥4 time buckets.
- **Verdict constants**: `BpmConstants.VERDICT_GOOD/NEEDS_WORK/POOR/NA` are the single source of truth.
  `verdictToCss()` maps to CSS classes. `verdictToDisplay()` maps to human labels. `verdictToIcon()` maps
  to accessibility icons (checkmark/warning/cross).
- **Trend constants**: `BpmConstants.TREND_STABLE/MOSTLY_STABLE/DEGRADING` for trend stability labels.
  Shared `trendStabilityText()` in ReportPanelBuilder produces consistent prose across panels.
- **Bottleneck display names**: `BpmConstants.BOTTLENECK_DISPLAY_NAMES` maps bottleneck constants to
  friendly names (e.g., "Reduce Server Response" → "Slow Server Response") used in breach and findings cards.
- **Single source of truth**: `ReportData` is the authoritative data source for all panels — Executive
  Summary, KPI cards, SLA Compliance summary, Critical Findings, and Risk Assessment all read from the same
  pre-computed record. No independent verdict re-computation from string data.
- **Accessibility**: ARIA tab pattern (role=tablist/tab/tabpanel, aria-selected, aria-controls, keyboard
  navigation with arrow keys). SLA cells use color + accessibility icons. Dark mode via `@media
  (prefers-color-scheme: dark)` + manual toggle (`html[data-theme]`).
- **CLI workflow** (two-step):
  ```
  jmeter -n -t test.jmx -Jbpm.output=results.jsonl    # Step 1: run test
  bpm-report -i results.jsonl                           # Step 2: generate report
  ```
- **HTML report panels** (6 total):
    1. Executive Summary — KPI cards (from ReportData) + 5-paragraph narrative (health, trend, risk, actions,
       errors). Breaches grouped by bottleneck with nested expandable page lists. Errors with relative frequency.
    2. Performance Metrics — full data table with pagination, sorting, search, sticky label column
    3. Performance Trends — 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render Time) with SLA lines +
       per-label filter. Render Time shows "No SLA threshold" subtitle.
    4. SLA Compliance — color + icon verdict matrix (no text badges), intro line, thresholds above table
    5. Critical Findings — grouped by bottleneck type, expandable cards with 4-part recommendations
       (what to do, quick win, long-term fix, expected outcome), expandable transaction lists
    6. Risk Assessment — professional cards with CSS status dots and left-border accent (no emojis);
       green "all clear" cards collapsed via `<details>` element
- **Report features**: sidebar navigation (ARIA tabs + keyboard nav), metadata grid, page-based pagination +
  column sorting on all tables, transaction search, styled Excel export (xlsx-js-style with header styling,
  SLA cell coloring, auto column widths), dark mode toggle (auto/dark/light cycle), save dialog (JFileChooser),
  scroll shadow indicators, Chart.js CDN fallback.
- **CLI exit codes**: 0 = success, 1 = bad args, 2 = parse error, 3 = write error, 4 = unexpected.
- **CLI pipeline**: parse JSONL → apply label filter (--search/--regex/--exclude) → load properties →
  build report data → render HTML → save file.

### Resource Files

- `bpm-executive-summary.html` — Executive Summary template: 5-paragraph assessment, expandable breach/error sections.
- `bpm-risk-assessment.html` — Risk Assessment template: professional cards with CSS dots, expandable risk items.
- `bpm-report.css` — All report CSS (layout, tables, SLA colors, dark mode auto + manual toggle, critical findings
  cards, breach/error expandables, risk assessment dots, scroll shadows). Cached in static field.
- `bpm-report.js` — Interactive JS (panel switching with ARIA, table pagination/sorting, search filter, styled Excel
  export via xlsx-js-style, dark mode toggle, scroll shadow detection, error banner helper). Cached in static field.
- `chart.umd.min.js` — Chart.js 4.4.1 bundled for offline use. Falls back to CDN if missing.
- `xlsx-style.bundle.js` — xlsx-js-style 1.2.0 (SheetJS fork with cell styling) bundled for offline use. Falls back to
  CDN if missing. Excel button greyed out if neither source loads.
- `messages.properties` — JMeter resource bundle (`bpm_listener_gui=Browser Performance Metrics`).
- `bpm-default.properties` — default config (metric toggles, network topN, SLA thresholds, bottleneck ratios, debug).
- `META-INF/jmeter-plugins.properties` — Plugins Manager integration (id, name, description, dependency on
  jpgc-webdriver).

### Key Constraints

- `performanceScore` is `Integer` (nullable) everywhere — unboxing `null` to `int` will NPE and silently abort JSONL
  writes.
- `BpmConstants` is the single source of truth — never hardcode column indices, label strings, property keys, verdict
  strings (`VERDICT_*`), bottleneck labels (`BOTTLENECK_*`), trend stability labels (`TREND_*`), or bottleneck
  display names (`BOTTLENECK_DISPLAY_NAMES`) outside it. Use `verdictToCss()`/`verdictToDisplay()`/`verdictToIcon()`
  for verdict rendering. Use `HtmlUtils.severityTag()` for Critical/Warning badge HTML.
- JSONL schema (`DerivedMetrics`, `BpmResult` `@JsonProperty` names) is public and backward-compatible — field renames
  are breaking changes.
- `BpmConstants.TEST_ELEMENT_*` key strings are stored in `.jmx` files — renaming breaks existing test plans.
- Selenium types confined to `ChromeCdpCommandExecutor` — lazy class loading via `Class.forName()`.
- Chrome-only via CDP — acknowledged constraint, documented not hidden.
- Pure observer — never crashes the test; all exceptions caught; graceful degradation.
- UI preserves `AbstractListenerGui` and `Clearable` contracts.
- `TotalPinnedRowSorter` must pin TOTAL to last view row for all sort directions.
- **Column model**: 18 total columns (10 always-visible + 8 toggleable). Always-visible: Label, Samples, Score,
  Render Time, Server Ratio, Frontend Time, FCP-LCP Gap, Stability, Headroom, Improvement Area. Toggleable raw
  metrics: FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns. Column indices defined in
  `BpmConstants.COL_IDX_*` — never hardcode ordinals.

### Reference Architecture — JMeter Aggregate Report (`StatVisualizer`)

When making GUI/state management decisions, refer to JMeter's Aggregate Report (`StatVisualizer` + `ResultCollector`)
as the proven reference implementation:

- **Data ownership**: Data lives in the TestElement, not the GUI. GUI reads from the element unconditionally.
- **`configure()`**: Always sync GUI with element data — no `switchedElement` guards or conditional rebuilds.
- **Timer**: Start once in constructor, never stop. Use a `volatile boolean dataChanged` flag — idle ticks cost nothing.
- **Model updates**: Update aggregate rows in-place; timer just calls `fireTableDataChanged()`. Full rebuild only on
  filter changes.
- **Clone delegation**: `AbstractTestElement.clone()` creates new instances — transient state is NOT shared.
  Per-thread clones must delegate to the primary (tree element). `ResultCollector` uses a static `FileEntry` map.
- **Per-element settings**: Persist in TestElement properties via `modifyTestElement()`/`configure()`, not static
  caches.
