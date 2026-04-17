# CLAUDE.md

You are a contributor to a JMeter listener plugin capturing browser performance via Chrome DevTools Protocol, with
HTML reports. Chrome-only by design; pure observer (never crashes the test). Stability over novelty, correctness over
features.

## Rules

**Behavioral**

- Never assume — ask if in doubt.
- Never edit code until the user confirms.
- Never expand scope beyond what was confirmed.
- Recommend alternatives only when there is a concrete risk or significant benefit.
- On conflicting requirements: flag, pause, wait for decision.
- On obstacles: fix the root cause, not the symptom. Never bypass safety checks (`--no-verify`, `git reset --hard`,
  disabling tests).
- Push back when a change violates an enforced invariant, risks data loss, or inverts the dependency direction — even if
  the user asks for it.

**Technical**

- Target JMeter 5.6.3 exclusively. Verify every API against `mvn dependency:sources` output or the installed 5.6.3
  source JARs under `~/.m2/repository/org/apache/jmeter/` — never from training memory.
- Do not change JDK/Maven targets (see Environment). Do not rewrite git history.
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**.
- Before proposing changes, trace impact along the dependency direction (see Architecture).

## Workflow & Communication

- Interactive — present choices one at a time unless trivial and clearly scoped.
- Multi-file changes: present all files together, note dependency order.
- Rollback: revert to the last explicitly approved file set, then ask.
- After changes: self-check for regressions, naming consistency, rule adherence, and all enforced invariants.
- Summarize confirmed state if context grows large; suggest `/compact` proactively.
- Responses: concise — bug-fix explanation ≤10 lines; proposal ≤1 table + 3 bullets; architecture change requires a
  table. No filler, no restating the request.
- Feedback: direct, not diplomatic. Flag concrete concerns even when not asked.
- For non-trivial decisions (≥2 options with materially different risk/effort/impact), present a table and highlight the
  recommendation. Trivial choices use prose.

  | Option | Risk | Effort | Impact | Recommendation |
    |--------|------|--------|--------|----------------|

## Examples

### Decision-table usage

**Trivial — prose, no table:**
> Rename `collectIfNeeded` → `collectIfActive` in `BpmCollector` for clarity vs the `HEALTHY`/`DISABLED` state naming.
> Same class, same signature. Proceed?

**Non-trivial — table:**
> Two approaches for CDP session recovery after browser crash:
>
> | Option | Risk | Effort | Impact | Recommendation |
> |--------|------|--------|--------|----------------|
> | Reset in `CdpSessionManager` on next `collectIfNeeded` | Delays detection by one sample | S | Simpler lifecycle | ✓ |
> | Poll health check every 5 s in background thread | New thread; EDT coordination | M | Faster recovery | |

### Multi-file change presentation

**Bad:** "I'll edit `BpmCollector.java` first, then show the next file."

**Good:** "3 files, apply in order: (1) `BpmConstants.java` — add column index + display name; (2) `BpmTableModel.java` —
expose value at new index; (3) `BpmCellRenderer.java` — render new column. All diffs below."

### Rollback message

**Bad:** *(silently reverts)*

**Good:** "Reverting `BpmHtmlReportRenderer.java` and `ReportDataBuilder.java` to the last approved state
(commit `a3f1c42`). Ready for next instruction."

## Environment

- JDK 17, Maven 3.8+. All runtime deps `provided` (Selenium 4.43.0 + Jackson 2.21.2 on the JMeter classpath). Thin JAR,
  no shading.
- Test stack: JUnit 5.11.3 + Mockito 5.23.0.
- Shell: bash on Windows (Unix syntax — `/dev/null`, forward slashes). `find`/`grep` via Bash tool are fork-unstable on
  this machine; use Glob/Grep tools instead. User runs builds manually.
- UI changes cannot be exercised without a live JMeter runtime — say so explicitly rather than claiming success.

## Build & Coverage

```bash
mvn clean verify                                    # Build + tests + JaCoCo gate
mvn clean package -DskipTests                       # Build only
mvn test -Dtest=JsonlWriterTest                     # Single test class
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush   # Single test method
mvn clean deploy -Prelease                          # Release to Maven Central (GPG sign + sources + javadoc)
```

- JaCoCo gate: **90%** BUNDLE line coverage in `verify` phase. Report at `target/site/jacoco/index.html`.
- Excluded (require JMeter/Selenium/CDP runtime): `gui/**`, `cli/**`, `report/**`, `BpmListener`, `BpmCollector`,
  `LabelAggregate`, `FileOpenMode`, `ChromeCdpCommandExecutor`, `CdpSessionManager`, `BpmTimeBucket`.
- Only profile defined: `release`. No `-Pe2e` profile.

## Definition of Done

A task is complete only when all apply:

- `mvn clean verify` passes (tests + JaCoCo ≥90% gate).
- No new compiler warnings or deprecation notices.
- No invariant from *Enforced invariants* violated.
- Dependency direction preserved (`gui → core → collectors → model`; `core → {config, output, util, error}`; `cli → report → {model, output}`).
- Selenium isolation preserved — `ChromeCdpCommandExecutor` is the only class touching Selenium types (invariant #5).
- Pure-observer guarantee preserved — all exceptions caught; never crashes the test (invariant #6).
- CLAUDE.md reviewed and updated if architecture, invariants, or class responsibilities changed.
- README.md reviewed and updated if user-facing behaviour changed.

## Architecture

JMeter listener plugin capturing browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver
Sampler via Chrome DevTools Protocol. Includes HTML performance reports with trend analysis. **Chrome-only via CDP** —
acknowledged constraint, documented not hidden.

**Dependency direction:** `gui → core → collectors → model`; `core → {config, output, util, error}`;
`cli → report → {model, output}`. Core talks to Chrome via the `CdpCommandExecutor` interface (see invariant #5).

### Class inventory

| Class                                                                                                                                 | Package    | Responsibility                                                                                                                                                                                                  |
|---------------------------------------------------------------------------------------------------------------------------------------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BpmListener`                                                                                                                         | core       | Entry point. `SampleListener` + `TestStateListener` + `Clearable`. Owns `primaryByName` (UUID + `\|` + output path) and `primaryByOutputPath` registries.                                                       |
| `BpmCollector`                                                                                                                        | core       | Ref-counted singleton coordinator (`acquire`/`release`). Per-thread `executorsByThread`/`buffersByThread`/`iterationsByThread`. `collectIfNeeded()` is the single collection entry point.                       |
| `CdpSessionManager`                                                                                                                   | core       | Per-thread CDP session. `transferBufferedEvents()` drains JS `window.__bpm_*` buffers.                                                                                                                          |
| `ChromeCdpCommandExecutor`                                                                                                            | core       | Only class touching Selenium types. Implements `CdpCommandExecutor`.                                                                                                                                            |
| `MetricsBuffer`                                                                                                                       | core       | Per-thread mutable state (collectors are stateless against this).                                                                                                                                               |
| `LabelAggregate`, `FileOpenMode`                                                                                                      | core       | Label-level aggregation; file-exists decision enum (`OVERWRITE`/`APPEND`/`DONT_START`).                                                                                                                         |
| `MetricsCollector<T>` + `WebVitalsCollector`/`NetworkCollector`/`RuntimeCollector`/`ConsoleCollector`                                 | collectors | Singletons; per-thread state in `MetricsBuffer`. `WebVitalsCollector` additionally tracks previous LCP for SPA stale detection.                                                                                 |
| `DerivedMetricsCalculator`                                                                                                            | collectors | SLA verdicts, bottleneck classification, composite performance score.                                                                                                                                           |
| `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`, `ResourceEntry` | model      | Jackson records. `@JsonProperty` names are public JSONL schema.                                                                                                                                                 |
| `BpmPropertiesManager`                                                                                                                | config     | `bpm.properties` loader — SLA thresholds, metric toggles, bottleneck ratios.                                                                                                                                    |
| `JsonlWriter`                                                                                                                         | output     | JSONL writer — see Output.                                                                                                                                                                                      |
| `SummaryJsonWriter`, `CsvExporter`                                                                                                    | output     | Post-run summary JSON + CSV export.                                                                                                                                                                             |
| `BpmListenerGui`                                                                                                                      | gui        | Aggregate Report pattern. Owns `deduplicateUuid()` and the update timer; overrides `configure()` / `modifyTestElement()`.                                                                                       |
| `BpmTableModel`                                                                                                                       | gui        | `getFilteredRows()` applies transaction filter. Column count in GUI section.                                                                                                                                    |
| `BpmCellRenderer`, `TooltipTableHeader`, `ColumnSelectorPopup`, `CheckBoxFilterButton`                                                | gui        | Cell rendering + filter UI.                                                                                                                                                                                     |
| `TotalPinnedRowSorter`                                                                                                                | gui        | Pins TOTAL to last view row for every sort direction.                                                                                                                                                           |
| `BpmHtmlReportRenderer`, `BpmReportCoordinator`, `BpmReportLauncher`                                                                  | report     | HTML rendering + launch orchestration.                                                                                                                                                                          |
| `ReportDataBuilder`, `ReportData`                                                                                                     | report     | Pre-computes SLA verdicts, risk, best/worst, weighted score/LCP, trend. Single source of truth for panels.                                                                                                      |
| `ReportPanelBuilder`                                                                                                                  | report     | Template rendering: `{{placeholder}}` + `{{#section}}...{{/section}}`.                                                                                                                                          |
| `TrendAnalyzer`, `TrendData`                                                                                                          | report     | First/second-half comparison. Requires ≥4 time buckets.                                                                                                                                                         |
| `Main`, `CliArgs`, `BpmCliReportPipeline`, `TimeBucketBuilder`, `BpmParseException`                                                   | cli        | `bpm-report` CLI. Exit codes: 0=success, 1=bad args, 2=parse error, 3=write error, 4=unexpected.                                                                                                                |
| `BpmConstants`                                                                                                                        | util       | **Single source of truth** — column indices, label strings, property keys, `VERDICT_*`, `BOTTLENECK_*`, `TREND_*`, `BOTTLENECK_DISPLAY_NAMES`. Helpers `verdictToCss()`/`verdictToDisplay()`/`verdictToIcon()`. |
| `HtmlUtils`                                                                                                                           | util       | `severityTag()` for badge HTML.                                                                                                                                                                                 |
| `JsSnippets`                                                                                                                          | util       | `INJECT_OBSERVERS` (first load) + `REINJECT_OBSERVERS` (resets CLS on reinjection).                                                                                                                             |
| `ConsoleSanitizer`, `TypeConverters`, `BpmDebugLogger`, `BpmFileUtils`                                                                | util       | Support utilities.                                                                                                                                                                                              |
| `BpmErrorHandler`, `LogOnceTracker`                                                                                                   | error      | Thread states: `HEALTHY` → `RE_INIT_NEEDED` → `DISABLED`. `resetThread()` transitions DISABLED → HEALTHY on browser recovery.                                                                                   |

### Lifecycle & clone delegation

- **Clone delegation**: `AbstractTestElement.clone()` copies properties but not transient fields (`testInitialized`,
  `guiUpdateQueue`, `rawResults`, `jsonlWriter`). Per-thread clones delegate `sampleOccurred()` to the primary in
  `primaryByName` (key = UUID + `|` + output path). Only the primary owns mutable state.
- **UUID dedup**: `BpmListenerGui.deduplicateUuid()` (called from `configure()`) scans the GUI tree for UUID collisions
  caused by copy-paste and assigns a fresh UUID. Prevents `primaryByName` key collision that caused sample
  double-counting.
- **Execution tree ≠ GUI tree**: `testStarted()` runs on execution-tree elements; `configure()` receives GUI-tree
  elements. At `testEnded()`, the execution primary persists `rawResults` to the GUI element via `setRawResults()`.
- **`testActuallyStarted`**: set only after full `testStarted()` setup. `testEnded()` skips cleanup if false.
- **`cachedEngine`**: captured at top of `testStarted()` before the blocking dialog. `stopTestEngine()` uses cached
  ref + `ActionRouter` fallback.
- **`pendingFreshClear`**: `createTestElement()` strips properties and sets the flag. `configure()` clears display and
  returns early for new elements.
- **Pre-flight file scan**: first primary wins `preFlightDone.compareAndSet(false, true)` and scans all enabled
  BpmListeners. Single dialog; decision cached in `globalFileDecision` (`OVERWRITE`/`DONT_START`).
- **CLI auto-enable**: `cliAutoEnabled` flag ensures only the first disabled BpmListener is auto-enabled when
  `-Jbpm.output` is passed in non-GUI mode.
- **Error recovery**: when a thread hits `DISABLED` (CDP re-init failed), `BpmCollector.collectIfNeeded()` checks for a
  new browser; if found, `errorHandler.resetThread()` clears stale CDP references and transitions back to `HEALTHY`.

### GUI (Aggregate Report pattern)

- **Data ownership**: data lives in the TestElement, not the GUI. GUI reads unconditionally in `configure()`.
- **Timer**: start once in constructor, never stop. Incremental `addOrUpdateResult` + `fireTableDataChanged`. Full
  `rebuildTableFromRaw()` only on filter change or file load.
- **Per-element settings**: persisted in TestElement properties via `modifyTestElement()`/`configure()`, not static
  caches.
- **Column model**: 18 total (10 always-visible + 8 toggleable). Indices in `BpmConstants.COL_IDX_*`.

### Filtering

- **Manual-only**: filters apply on "Apply Filters" click. Controls (Apply, transaction name, regex, include/exclude)
  are always enabled, including during test execution. `rebuildTableFromRaw()` is the single source of truth for
  retroactive re-filtering.
- **Transaction filter**: text + RegEx checkbox + Include/Exclude combo. Applied in `BpmTableModel.getFilteredRows()`.
- **Stability filter**: multi-select `CheckBoxFilterButton` — `Stable`, `Minor Shifts`, `Unstable`. `null` Set = no
  filter.
- **Improvement Area filter**: multi-select `CheckBoxFilterButton` — `None` + 5 bottleneck types (`RELIABILITY`,
  `SERVER`, `RESOURCE`, `CLIENT`, `LAYOUT`). `null` Set = no filter.
- **Persistence**: stability + improvement area stored as comma-separated values in `TEST_ELEMENT_STABILITY_FILTER` /
  `TEST_ELEMENT_IMPROVEMENT_FILTER`. `"All"` = no filter.
- **Offset reference**: offsets derive from the first record's timestamp in `allRawResults`, not `testStartTime` (
  display-only). Shared by `rebuildTableFromRaw()` and report generation; works for live tests, file loads, and
  post-test filtering.
- **Clear All**: `clearData()` iterates every BpmListener and clears `START_OFFSET`, `END_OFFSET`, `TRANSACTION_NAMES`,
  `REGEX`, `INCLUDE`, `CHART_INTERVAL`, `STABILITY_FILTER`, `IMPROVEMENT_FILTER`.
- **`configuringElement` guard**: suppresses spurious `applyAllFilters()` fired by programmatic UI updates during
  `configure()`.

### Output

- **Path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default `bpm-results.jsonl`.
- **JSONL**: one JSON object per line via `JsonlWriter`. Flush every record. Thread-safe (synchronized). Append mode
  supports the file-exists dialog.
- **Output-path dedup**: `primaryByOutputPath` — first primary for a given path wins; duplicates skip JSONL writing.

### HTML report

- 6 panels, all Java-generated, no external API calls: **Executive Summary**, **Performance Metrics**, **Performance
  Trends** (6 Chart.js charts), **SLA Compliance**, **Critical Findings**, **Risk Assessment**.
- CSS/JS loaded from classpath, cached in static fields, inlined at render time. Chart.js + xlsx-js-style bundled as
  classpath resources with CDN fallback via `inlineOrCdn()`.
- Features: ARIA tabs + keyboard nav, pagination + sorting, transaction search, styled Excel export, dark mode (
  auto/dark/light).
- CLI workflow: `jmeter -n -t test.jmx -Jbpm.output=results.jsonl` → `bpm-report -i results.jsonl`.
- CLI flags: `-i`, `-o`, `--search`, `--regex`, `--exclude`, `--chart-interval`, `--scenario-name`, `--virtual-users`.

### Resource files

| File                                                     | Purpose                                                                     |
|----------------------------------------------------------|-----------------------------------------------------------------------------|
| `bpm-executive-summary.html`, `bpm-risk-assessment.html` | Panel templates                                                             |
| `bpm-report.css`                                         | Report CSS                                                                  |
| `bpm-report.js`                                          | Interactive JS (tabs, pagination, sorting, search, Excel export, dark mode) |
| `chart.umd.min.js`                                       | Chart.js 4.4.1 bundled (CDN fallback)                                       |
| `xlsx-style.bundle.js`                                   | xlsx-js-style 1.2.0 bundled (CDN fallback)                                  |
| `messages.properties`                                    | JMeter resource bundle                                                      |
| `bpm-default.properties`                                 | Default config (metric toggles, SLA thresholds, bottleneck ratios)          |
| `META-INF/jmeter-plugins.properties`                     | Plugins Manager integration                                                 |

## Enforced invariants (do not violate)

1. **JSONL schema is public** — `BpmResult` / `DerivedMetrics` `@JsonProperty` names are backward-compatible. Field
   renames are breaking changes.
2. **`TEST_ELEMENT_*` keys are public** — stored in `.jmx` files. Renaming breaks existing test plans. Includes
   `STABILITY_FILTER`, `IMPROVEMENT_FILTER` (comma-separated multi-select).
3. **`BpmConstants` is the only source** for column indices, label strings, property keys, `VERDICT_*`, `BOTTLENECK_*`,
   `TREND_*`, `BOTTLENECK_DISPLAY_NAMES`. Render verdicts via `verdictToCss/Display/Icon()`; render severity badges via
   `HtmlUtils.severityTag()`.
4. **`performanceScore` is `Integer` (nullable)** — unboxing `null` to `int` will NPE and silently abort JSONL writes.
5. **Selenium isolation** — Selenium types confined to `ChromeCdpCommandExecutor`; rest of core goes through
   `CdpCommandExecutor`. Lazy `Class.forName()`.
6. **Pure observer** — never crash the test; catch all exceptions; graceful degradation.
7. **UI contracts** — preserve `AbstractListenerGui` and `Clearable`.
8. **TOTAL row pinning** — `TotalPinnedRowSorter` pins TOTAL to the last view row for every sort direction.
9. **All runtime deps `provided`** — no shading. Depending on an unprovided runtime jar breaks the build contract.
10. **`## Enforced invariants` heading is load-bearing** — extracted verbatim by `.github/workflows/pr-review.yml`. Do not rename, split, or change its position relative to the next `##` heading.

## Self-Maintenance

- **Ownership split**: `CLAUDE.md` = rules + context for Claude. `README.md` = user-facing features, install, config.
  Change each in its own lane; do not duplicate.
- **Auto-compact**: suggest `/compact` before context becomes unwieldy.

### CLAUDE.md update rules

Trigger: session changes design, architecture, invariants, or class responsibilities.

- Review this file in the same session. Remove stale entries, dedupe, confirm every line is actionable.

**Do not put in CLAUDE.md**:

- Implementation details that rot on refactor (method signatures, minor helper behaviors).
- Facts derivable from `git log` / `git blame` / current code.
- Ephemeral task state (in-progress work, TODOs).
- Restatement of README content (user-facing features, install steps).
- Duplicates of facts already stated elsewhere in this file.

**Final pass — every item must hold**:

- Accuracy: every claim matches current code.
- Reference resolution: every class/file/field/invariant# named exists and is spelled correctly.
- Coverage: every invariant or constraint enforced by code is represented here.
- Anti-list compliance: every line passes "Do not put in CLAUDE.md".
- Token economy: every word earns its place.
- Redundancy: no fact stated more than once.

### README update rules

Trigger: user-facing feature changes (filters, columns, report panels, CLI options, config).

Every README edit must satisfy:

1. **User-benefit framing** — describe features by what they do *for the user*, not by internal mechanics. Architectural
   terms ("pure observer", "clone delegation", "primaryByName") stay in CLAUDE.md.
2. **Features table = summary only** — one short line per feature. Defaults, config keys, CLI flags, filter options, and
   panel details live only in their dedicated sections.
3. **Zero duplicate facts** — each fact appears in at most one place unless there is a legitimate summary/detail split (
   Features row → dedicated section). When the same enumeration would appear in two sections, the second references the
   first by anchor (e.g. Improvement Area filter links to Improvement Area Detection).
4. **Cross-platform shell blocks** — any command involving paths or env vars must show Linux/macOS, Windows PowerShell,
   and Windows cmd. Use absolute-path placeholders (`$JMETER_HOME`, `%JMETER_HOME%`, `$env:JMETER_HOME`).
5. **Badges at top** — Release, Maven Central, License. Use the
   `maven-metadata/v?metadataUrl=…repo1.maven.org…/maven-metadata.xml` variant, **not** `maven-central/v/…`. The latter
   hits `search.maven.org`'s stale Solr index; the former reads the authoritative `maven-metadata.xml` and updates
   within minutes of a deploy.
6. **Self-updating references over literal versions** — prefer shields.io badges to hardcoded version strings.
7. **Callouts over subsections** — use `> [!NOTE]` / `> [!IMPORTANT]` for 1-2 line asides. Don't spawn a dedicated `###`
   section for a two-sentence note (e.g. the `bpm.properties` backup note lives under the config table, not as its own
   subsection).
8. **Contributing = contributor commands only** — `mvn clean verify` is the only build command documented. Release
   commands (`mvn clean deploy -Prelease`) belong in the release workflow, not in README. Contributing links to
   `CLAUDE.md` for architecture instead of duplicating it.
9. **Uninstall section** — document what files are left behind (`bpm.properties`, JSONL outputs) and that they must be
   removed manually if no longer needed.
10. **Explicit auto-behavior** — when documenting auto-activation, auto-backup, auto-generation, state what is created
    and where (e.g. "auto-generated at `<JMETER_HOME>/bin/bpm.properties`").
