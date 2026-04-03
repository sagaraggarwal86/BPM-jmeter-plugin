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

Requirements: JDK 17 only, Maven 3.8+. JaCoCo enforces **84%** line coverage excluding `gui/**`, `ai/**`, `cli/**`,
`BpmListener`, `ChromeCdpCommandExecutor`, `CdpSessionManager`, `BpmTimeBucket`.

## Architecture

JMeter listener plugin capturing browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver
Sampler via Chrome DevTools Protocol. Includes optional AI-powered analysis reports.

### Package Structure

| Package       | Responsibility                                                                                                                                                                                                                                                              |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`        | `BpmListener` (entry point, SampleListener/TestStateListener), `BpmCollector` (singleton coordinator: CDP sessions, collectors, ref-counted lifecycle), `CdpSessionManager` (CDP lifecycle), `MetricsBuffer`, `LabelAggregate`, `FileOpenMode` (enum: OVERWRITE/DONT_START) |
| `collectors`  | `MetricsCollector<T>` + 4 implementations (WebVitals, Network, Runtime, Console), `DerivedMetricsCalculator`                                                                                                                                                                |
| `model`       | Data records: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`, `ResourceEntry` (network resource detail record)                                                                                        |
| `config`      | `BpmPropertiesManager` — SLA thresholds, feature toggles, `bpm.properties` management                                                                                                                                                                                       |
| `output`      | `JsonlWriter` (buffered, flush every 1 record, append mode support), `SummaryJsonWriter`, `CsvExporter`                                                                                                                                                                     |
| `gui`         | `BpmListenerGui` (Swing table + AI report button), `BpmTableModel`, `BpmCellRenderer` (SLA color-coded cells), `TooltipTableHeader`, `ColumnSelectorPopup`, `TotalPinnedRowSorter`                                                                                          |
| `ai.provider` | `AiProviderRegistry` (7 providers, shared `ai-reporter.properties`), `AiProviderConfig`, `AiReportService` (OpenAI-compatible API client), `SharedHttpClient`                                                                                                               |
| `ai.prompt`   | `BpmPromptBuilder` (pre-computed verdicts + trends → JSON), `BpmPromptLoader`, `TrendAnalyzer`, `BpmPromptContent`                                                                                                                                                          |
| `ai.report`   | `BpmHtmlReportRenderer` (CommonMark + Chart.js), `BpmHtmlReportRenderer.RenderConfig` (metadata + SLA thresholds + truncation state), `BpmAiReportCoordinator` (returns `GenerateResult` with html + suggestedFilename), `BpmAiReportLauncher`                              |
| `cli`         | `Main` (entry point, exit codes 0-5), `CliArgs` (zero-dep parser: -i, -o, --provider, --config, --search, --regex, --exclude, --chart-interval, --scenario-name, --virtual-users), `BpmCliReportPipeline`, `TimeBucketBuilder`, `BpmParseException`                         |
| `util`        | `JsSnippets`, `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger`                                                                                                                                                                                                          |
| `error`       | `BpmErrorHandler`, `LogOnceTracker`, `AiServiceException` (AI API errors)                                                                                                                                                                                                   |

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
- **All runtime deps `provided`**: JMeter core, Selenium, Jackson on JMeter classpath. Only CommonMark is shaded (
  relocated to `io.github.sagaraggarwal86.shaded.commonmark`).
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

### AI Analysis Architecture

- **Java does ~95% of work**: Performance Metrics table, SLA Compliance verdicts, Critical Findings (diagnosis +
  actions),
  Performance Trends (6 charts with per-label filter), pagination, sorting, search — all Java-generated.
- **AI does ~5%**: Generates 2 sections of narrative prose (Executive Summary, Risk Assessment). Recommendations
  merged into Java-generated Critical Findings panel.
- **7 providers**: groq, gemini, mistral, deepseek, cerebras, openai, claude. Shared `ai-reporter.properties` config.
- **CLI workflow** (two-step):
  ```
  jmeter -n -t test.jmx -Jbpm.output=results.jsonl    # Step 1: run test
  bpm-ai-report -i results.jsonl --provider groq        # Step 2: generate AI report
  ```
- **Prompt design**: 5 rules, 2 sections (Exec Summary + Risk Assessment), 6 edge cases. Optimized for 8,126
  input token budget and weak model compatibility. Java pre-computes verdicts; AI does zero arithmetic.
  System prompt in `src/main/resources/bpm-ai-prompt.txt`.
- **Label truncation**: `BpmPromptBuilder.MAX_AI_LABELS = 20`. When exceeded: sort by score (worst first),
  send top 20, add `remainingLabels` JSON summary. GUI shows confirmation dialog, CLI logs warning,
  HTML report shows yellow notice bar. `BpmPromptContent` carries `totalLabels`/`includedLabels`;
  `RenderConfig` propagates to renderer via `wasLabelTruncated()`.
- **HTML report panels** (6 total):
    1. Executive Summary (AI) — non-technical stakeholder overview
    2. Performance Metrics (Java) — full data table with pagination, sorting, search
    3. Performance Trends (Java) — 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render Time) with SLA lines +
       per-label filter
    4. SLA Compliance (Java) — verdict matrix with Pass/Warning/Fail per metric, search
    5. Critical Findings (Java) — bottleneck summary + per-transaction findings with root cause, impact, recommended
       action
    6. Risk Assessment (AI) — headroom, boundary, SPA blind spots, trend risks (forward-looking, for devs/architects)
- **Report features**: sidebar navigation, metadata grid, page-based pagination + column sorting on all tables,
  transaction search, Excel export (SheetJS), print/PDF CSS, save dialog (JFileChooser), Chart.js CDN.
- **CLI exit codes**: 0 = success, 1 = bad args, 2 = parse error, 3 = AI error, 4 = write error, 5 = unexpected.
- **CLI pipeline**: parse JSONL → apply label filter (--search/--regex/--exclude) → load properties → resolve
  provider → validate config → ping API → build prompt → call AI → render HTML → save file.

### Resource Files

- `bpm-ai-prompt.txt` — AI system prompt (5 rules, 2 sections, 6 edge cases, verdict/metric mappings).
- `messages.properties` — JMeter resource bundle (`bpm_listener_gui=Browser Performance Metrics`).
- `bpm-default.properties` — default config (metric toggles, network topN, SLA thresholds, bottleneck ratios, debug).
- `jmeter-plugins.properties` — Plugins Manager integration (id, name, description, dependency on jpgc-webdriver).

### Key Constraints

- `performanceScore` is `Integer` (nullable) everywhere — unboxing `null` to `int` will NPE and silently abort JSONL
  writes.
- `BpmConstants` is the single source of truth — never hardcode column indices, label strings, or property keys outside
  it.
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
