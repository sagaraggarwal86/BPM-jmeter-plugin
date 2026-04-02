# CLAUDE.md

## Working Rules

- Never change git history or Java 17 implementation
- Never assume — ask if in doubt
- Never make changes to code until user confirms
- Never change existing functionality or make changes beyond confirmed scope
- Only recommend alternatives when there is a concrete risk or significant benefit
- Analyze impact across dependent layers (collector → model → derived → GUI → output) before proposing changes
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**

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

| Package       | Responsibility                                                                                                                                                |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`        | `BpmListener` (entry point, SampleListener/TestStateListener), `CdpSessionManager` (CDP lifecycle), `MetricsBuffer`, `LabelAggregate`                         |
| `collectors`  | `MetricsCollector<T>` + 4 implementations (WebVitals, Network, Runtime, Console), `DerivedMetricsCalculator`                                                  |
| `model`       | Data records: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`                            |
| `config`      | `BpmPropertiesManager` — SLA thresholds, feature toggles, `bpm.properties` management                                                                         |
| `output`      | `JsonlWriter` (buffered, flush every 10), `SummaryJsonWriter`, `CsvExporter`                                                                                  |
| `gui`         | `BpmListenerGui` (Swing table + AI report button), `ColumnSelectorPopup`, `TotalPinnedRowSorter`                                                              |
| `ai.provider` | `AiProviderRegistry` (7 providers, shared `ai-reporter.properties`), `AiProviderConfig`, `AiReportService` (OpenAI-compatible API client), `SharedHttpClient` |
| `ai.prompt`   | `BpmPromptBuilder` (pre-computed verdicts + trends → JSON), `BpmPromptLoader`, `TrendAnalyzer`, `BpmPromptContent`                                            |
| `ai.report`   | `BpmHtmlReportRenderer` (CommonMark + Chart.js), `BpmAiReportCoordinator`, `BpmAiReportLauncher`                                                              |
| `cli`         | `Main` (entry point), `CliArgs`, `BpmCliReportPipeline`, `TimeBucketBuilder`                                                                                  |
| `util`        | `JsSnippets`, `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger`                                                                                            |
| `error`       | `BpmErrorHandler`, `LogOnceTracker`                                                                                                                           |

### Key Design Decisions

- **No hard Selenium dep**: `BpmListener` uses `Class.forName()` to detect ChromeDriver. All Selenium code isolated in
  `ChromeCdpCommandExecutor`; rest uses `CdpCommandExecutor` interface.
- **JS-buffer capture**: Injects JS hooks (`JsSnippets`) that buffer events in `window.__bpm_*` arrays.
  `CdpSessionManager.transferBufferedEvents()` drains them. `REINJECT_OBSERVERS` resets CLS accumulator;
  `INJECT_OBSERVERS` does not.
- **Stateless collectors**: All four collectors are stateless singletons. Per-thread state in `MetricsBuffer`.
  Exception: `WebVitalsCollector` tracks previous LCP for SPA stale detection.
- **All runtime deps `provided`**: JMeter core, Selenium, Jackson on JMeter classpath. Only CommonMark is shaded (
  relocated to `io.github.sagaraggarwal86.shaded.commonmark`).
- **Clone guard — `primaryByName`**: `ConcurrentHashMap<String, BpmListener>` keyed by composite `elementId|outputPath`.
  First `putIfAbsent` wins setup; clones skip. Cleared per-element in `testEnded()`.
- **Pre-flight file scan**: First primary wins `preFlightDone.compareAndSet(false, true)`, scans ALL enabled
  BpmListeners via `GuiPackage.getTreeModel().getNodesOfType()`. Single dialog lists all conflicts. Decision cached in
  `globalFileDecision` (OVERWRITE/DONT_START). CLI always overwrites.
- **Broadcast write**: Static `allJsonlWriters` + `allGuiQueues` (CopyOnWriteArrayList). The primary that owns CDP
  writes to ALL registered writers/queues.
- **Output path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default `bpm-results.jsonl`.
- **`pendingFreshClear`**: `createTestElement()` strips properties + sets flag. `configure()` clears display and returns
  early for new elements.
- **`testActuallyStarted`**: Instance flag set only after full `testStarted()` setup. `testEnded()` skips cleanup if
  false.
- **`cachedEngine`**: Captured at top of `testStarted()` before blocking dialog. `stopTestEngine()` uses cached ref +
  ActionRouter fallback.
- **Manual-only filtering**: Filters apply only on "Apply Filters" button click. `rebuildTableFromRaw()` is single
  source of truth.

### AI Analysis Architecture

- **Java does ~90% of work**: Parses JSONL, aggregates stats, computes SLA verdicts (GOOD/NEEDS_WORK/POOR), trend
  analysis (RISING/FALLING/STABLE), alert sentences — all pre-computed.
- **AI does ~10%**: Narrates pre-computed facts into readable prose. Fill-in-the-blank templates for weak model
  compatibility.
- **7 providers**: groq, gemini, mistral, deepseek, cerebras, openai, claude. Shared `ai-reporter.properties` config.
- **CLI workflow** (two-step):
  ```
  jmeter -n -t test.jmx -Jbpm.output=results.jsonl    # Step 1: run test
  bpm-ai-report -i results.jsonl --provider groq        # Step 2: generate AI report
  ```
- **Prompt design**: 9 absolute rules, 6 sections with audience-specific templates, 8 edge cases, trend analysis with
  pre-computed direction/alerts. System prompt in `src/main/resources/bpm-ai-prompt.txt`.
- **HTML report**: CommonMark Markdown→HTML + Chart.js time-series charts (Score, LCP, FCP, TTFB over time) + metadata
  grid + sidebar nav.

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
