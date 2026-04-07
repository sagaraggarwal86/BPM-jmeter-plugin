# BPM — Browser Performance Metrics

A live Apache JMeter listener plugin that captures browser-side rendering and performance metrics
from WebDriver Sampler executions via Chrome DevTools Protocol (CDP). Provides Core Web Vitals,
network waterfall, runtime health, JS errors, a composite Performance Score, Improvement Area
detection, and an HTML performance report with trend analysis.

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [What BPM Captures](#what-bpm-captures)
- [Filter Settings](#filter-settings)
- [GUI Overview](#gui-overview)
- [HTML Performance Report](#html-performance-report)
- [CLI Mode](#cli-mode)
- [Output Files](#output-files)
- [Configuration](#configuration)
- [Non-GUI Mode](#non-gui-mode)
- [CI Integration Guide](#ci-integration-guide)
- [Performance Impact](#performance-impact)
- [Multiple BPM Listener Instances](#multiple-bpm-listener-instances)
- [Known Limitations](#known-limitations)
- [Running Tests](#running-tests)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Features

| Feature                     | Description                                                                                                                 |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| **Live Metric Capture**     | Core Web Vitals (FCP, LCP, CLS, TTFB), network waterfall, runtime health, JS errors — captured per WebDriver Sampler action |
| **Performance Score**       | Composite 0-100 score weighted against Google Core Web Vitals thresholds                                                    |
| **Improvement Area**        | Automatic detection of the primary bottleneck: server, network, rendering, DOM, or reliability                              |
| **Live Results Table**      | 10 always-visible derived columns + 8 toggleable raw metric columns with SLA-based coloring                                 |
| **Start / End Offset**      | Exclude ramp-up and ramp-down periods by entering a time window in seconds                                                  |
| **Transaction Filter**      | Filter by transaction name with Include/Exclude mode, plain text or regex                                                   |
| **Column Visibility**       | Show or hide raw metric columns via a dropdown multi-select control                                                         |
| **Test Time Info**          | Start, End, and Duration shown automatically during and after test                                                          |
| **SLA Thresholds**          | Configurable per-metric thresholds — breaching cells highlighted in color                                                   |
| **CSV Export**              | Save all visible columns to a CSV file                                                                                      |
| **HTML Performance Report** | Generate a styled HTML report with executive summary, risk assessment, trend analysis, and SLA compliance                   |
| **CLI Mode**                | Generate reports from the command line — no JMeter GUI required                                                             |
| **JSONL Output**            | One JSON record per sampler action — CI-friendly                                                                            |
| **Pure Observer**           | Zero impact on test results — never modifies SampleResults, JTL output, or other listeners                                  |

---

## Requirements

| Requirement       | Version                                   |
|-------------------|-------------------------------------------|
| Java              | 17+                                       |
| Apache JMeter     | 5.6.3+                                    |
| WebDriver Sampler | jpgc-webdriver *(JMeter Plugins Manager)* |
| Chrome / Chromium | Any recent version                        |
| Maven             | 3.8+ *(build only)*                       |

> [!IMPORTANT]
> **BPM requires WebDriver Sampler (jpgc-webdriver) with Chrome/Chromium.** All metrics are captured via Chrome DevTools
> Protocol — Firefox, Safari, and Edge are not supported. HTTP Samplers and other non-WebDriver sampler types produce no
> BPM data.

---

## Installation

### Via JMeter Plugins Manager (Recommended)

Search for "Browser Performance Metrics" in the Plugins Manager and install.

### Manual JAR

1. Download the latest JAR from
   [Maven Central](https://central.sonatype.com/artifact/io.github.sagaraggarwal86/bpm-jmeter-plugin)
   or the [GitHub Releases](https://github.com/sagaraggarwal86/bpm-jmeter-plugin/releases) page.

2. Copy it to your JMeter `lib/ext/` directory:
   ```
   <JMETER_HOME>/lib/ext/bpm-jmeter-plugin-<version>.jar
   ```

3. Restart JMeter.

4. *(Optional — CLI mode)* Copy the wrapper scripts to `<JMETER_HOME>/bin/`:
   ```
   <JMETER_HOME>/bin/bpm-report.bat     (Windows)
   <JMETER_HOME>/bin/bpm-report.sh      (macOS / Linux)
   ```
   The scripts are in the `src/main/scripts/` directory of the source repository.

5. *(Optional — SLA tuning)* A sample `bpm.properties` with all configurable thresholds is
   provided at [docs/bpm.properties](docs/bpm.properties). Copy to `<JMETER_HOME>/bin/` to
   customize SLA thresholds — BPM auto-generates a default on first run if none exists.

### Build from Source

**Prerequisites:** Java 17+, Maven 3.8+

```bash
git clone https://github.com/sagaraggarwal86/bpm-jmeter-plugin.git
cd bpm-jmeter-plugin
mvn clean verify
cp target/bpm-jmeter-plugin-*.jar $JMETER_HOME/lib/ext/
```

---

## Quick Start

1. Create a test plan with a **Thread Group** containing one or more **WebDriver Samplers** using Chrome.
2. Right-click the Thread Group: **Add > Listener > Browser Performance Metrics**.
3. Run the test.
4. Watch the live results table populate with performance scores, render times, and improvement areas.

Zero configuration required. BPM automatically instruments all WebDriver Samplers in the Thread Group.

---

## What BPM Captures

### Raw Metrics (4 Tiers)

| Tier | Category   | Metrics                                                 | Overhead   |
|------|------------|---------------------------------------------------------|------------|
| 1    | Web Vitals | FCP, LCP, CLS, TTFB                                     | Negligible |
| 2    | Network    | Total requests, total bytes, top N slowest + all failed | Low        |
| 3    | Runtime    | JS heap, DOM nodes, layout count, style recalc count    | Low        |
| 4    | Console    | JS error count, warning count, sanitized messages       | Negligible |

### Derived Metrics

| Metric                        | Formula                     | Purpose                                    |
|-------------------------------|-----------------------------|--------------------------------------------|
| **Performance Score** (0-100) | Weighted composite          | Single-number health indicator             |
| **Render Time** (ms)          | LCP - TTFB                  | Pure client-side rendering time            |
| **Server Ratio** (%)          | (TTFB / LCP) x 100          | Server vs client split                     |
| **Frontend Time** (ms)        | FCP - TTFB                  | Browser parse + blocking-script time       |
| **FCP-LCP Gap** (ms)          | LCP - FCP                   | Lazy-load or render-blocking indicator     |
| **Stability**                 | CLS-based                   | Stable / Minor Shifts / Unstable           |
| **Headroom** (%)              | 100 - (LCP / lcpPoor x 100) | LCP budget remaining before Poor threshold |
| **Improvement Area**          | Categorical detection       | Tells you what to fix                      |

### Performance Score

Weighted average against Google Core Web Vitals thresholds:

- LCP (40%), FCP (15%), CLS (15%), TTFB (15%), JS Errors (15%)
- **Good** >= 90 (green) / **Needs Work** 50-89 (amber) / **Poor** < 50 (red)
- Returns `null` when available metric weight < 0.45 (e.g. SPA-stale actions with only CLS + errors)

### Improvement Area Detection

First-match-wins priority:

1. **Fix Network Failures** — failed network requests detected
2. **Reduce Server Response** — TTFB > 60% of LCP
3. **Optimise Heavy Assets** — slowest resource > 40% of LCP
4. **Reduce Render Work** — render time > 60% of LCP
5. **Reduce DOM Complexity** — layout count > DOM nodes x 0.5
6. **None** — all within acceptable range

---

## Filter Settings

### Start / End Offset

Exclude ramp-up and ramp-down samples by entering a time window in seconds.

| Start Offset | End Offset | Behaviour                                       |
|--------------|------------|-------------------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                            |
| `5`          | *(empty)*  | Skip first 5 seconds; include the rest          |
| *(empty)*    | `25`       | Include up to 25 seconds; skip everything after |
| `5`          | `25`       | Include only the 5s - 25s window                |

### Transaction Names

Filter the results table by typing in the **Transaction Names** field.

| Mode                     | Behaviour                        | Example                          |
|--------------------------|----------------------------------|----------------------------------|
| **Plain text** (default) | Case-insensitive substring match | `login` matches `Login Flow`     |
| **RegEx** (checkbox on)  | Java regex pattern match         | `Login\|Checkout` matches either |

| Filter Mode           | Behaviour                                   |
|-----------------------|---------------------------------------------|
| **Include** (default) | Show only transactions matching the pattern |
| **Exclude**           | Hide transactions matching the pattern      |

Click **Apply Filters** to apply. Filters are manual — changing fields does not auto-filter.

---

## GUI Overview

The listener provides a single-panel GUI:

- **Output File** — JSONL output path with Browse button. Press Enter to load an existing file.
- **Filter Settings** — Start/End Offset, Transaction Names filter, Column Selector, Apply Filters button
- **Test Time Info** — Start, End, Duration (live-updating during test)
- **Overall Performance Score** — colored progress bar with Good / Needs Work / Poor counts
- **Results Table** — 10 always-visible derived columns + 8 toggleable raw columns with SLA coloring
- **HTML Report** — Generate HTML Report button (opens save dialog)
- **Save Table Data** — export visible data to CSV

### Column Selector

Click **Select Columns** to toggle raw metric columns (FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns).
All are OFF by default — the 10 derived columns tell the full story. Enable raw columns when you
need to dig deeper.

---

## HTML Performance Report

Click **Generate HTML Report** to create a comprehensive performance analysis report.
A save dialog lets you choose where to save the HTML file. The report opens automatically in your browser.

No external API keys or internet connection required (except for Chart.js and SheetJS CDN in the browser).

### Report Panels

| # | Panel               | Description                                                                             |
|---|---------------------|-----------------------------------------------------------------------------------------|
| 1 | Executive Summary   | KPI cards + descriptive prose: overall assessment, breaches, best/worst, errors         |
| 2 | Performance Metrics | Full data table with pagination, column sorting, and transaction search                 |
| 3 | Performance Trends  | 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render) with SLA threshold lines         |
| 4 | SLA Compliance      | Pass/Warning/Fail verdict matrix per metric per transaction                             |
| 5 | Critical Findings   | Only transactions needing attention, with root cause and recommended actions            |
| 6 | Risk Assessment     | Severity-colored cards: capacity risks, borderline pages, unmeasured navigations, trend |

### Report Features

- Sidebar panel navigation
- Metadata grid (scenario name, virtual users, run date/time, duration)
- Page-based pagination with configurable rows per page (10/25/50/100)
- Click-to-sort on all table columns (ascending/descending)
- Transaction search filter (Performance Metrics + SLA Compliance)
- Per-transaction chart filter in Performance Trends
- SLA threshold lines on charts (green for Score, red for LCP/FCP/TTFB/CLS)
- Excel export via SheetJS (all transactions, not limited by pagination)
- Print/PDF CSS for offline sharing
- Self-contained HTML — one file, no external dependencies except Chart.js and SheetJS CDN

---

## CLI Mode

Generate a performance report from the command line — no JMeter GUI required.

### Setup

Copy `bpm-report.bat` (Windows) or `bpm-report.sh` (macOS/Linux) to `<JMETER_HOME>/bin/`.

### Quick Start

```bash
# Step 1: Run JMeter test
jmeter -n -t test.jmx -l results.jtl -Jbpm.output=bpm-results.jsonl

# Step 2: Generate report
bpm-report -i bpm-results.jsonl
```

### All Options

```
Required:
  -i, --input FILE            BPM JSONL results file

Output:
  -o, --output FILE           HTML report path (default: bpm-report.html)

Filter Options:
  --chart-interval INT        Seconds per chart bucket, 0=auto (default: 0)
  --search STRING             Label filter text (include mode by default)
  --regex                     Treat --search as regex
  --exclude                   Exclude matching labels (default: include)

Report Metadata:
  --scenario-name STRING      Scenario name for report header
  --description STRING        Scenario description
  --virtual-users INT         Virtual user count for report header

Help:
  -h, --help                  Show help message
```

### Exit Codes

| Code | Meaning                        |
|------|--------------------------------|
| `0`  | Success — report generated     |
| `1`  | Invalid command-line arguments |
| `2`  | JSONL parse error              |
| `3`  | Report file write error        |
| `4`  | Unexpected error               |

### CI/CD Pipeline Example

```bash
# Run test
jmeter -n -t test.jmx -Jbpm.output=bpm-results.jsonl

# Generate report
./bpm-report.sh -i bpm-results.jsonl -o report.html \
  --scenario-name "Nightly Load Test" --virtual-users 50

# Report path printed to stdout; progress messages to stderr
```

---

## Output Files

### JSONL (primary)

One JSON object per line, per WebDriver Sampler execution. Default: `bpm-results.jsonl`.

Contains: `bpmVersion`, `timestamp`, `threadName`, `iterationNumber`, `samplerLabel`,
`samplerSuccess`, `samplerDuration`, raw metric objects (`webVitals`, `network`, `runtime`,
`console`), and `derived` object with score, improvement area, ratios, stability, headroom.

---

## Configuration

### bpm.properties

Auto-generated on first run at `<JMETER_HOME>/bin/bpm.properties`. All properties have sensible
defaults matching Google Core Web Vitals thresholds.

| Section          | Key                                       | Default         | Description                               |
|------------------|-------------------------------------------|-----------------|-------------------------------------------|
| Metric toggles   | `metrics.webvitals`                       | `true`          | Enable/disable Web Vitals collection      |
|                  | `metrics.network`                         | `true`          | Enable/disable Network collection         |
|                  | `metrics.runtime`                         | `true`          | Enable/disable Runtime collection         |
|                  | `metrics.console`                         | `true`          | Enable/disable Console collection         |
| Network          | `network.topN`                            | `5`             | Slowest resources to capture              |
| SLA: FCP         | `sla.fcp.good` / `sla.fcp.poor`           | `1800` / `3000` | FCP thresholds (ms)                       |
| SLA: LCP         | `sla.lcp.good` / `sla.lcp.poor`           | `2500` / `4000` | LCP thresholds (ms)                       |
| SLA: CLS         | `sla.cls.good` / `sla.cls.poor`           | `0.1` / `0.25`  | CLS thresholds                            |
| SLA: TTFB        | `sla.ttfb.good` / `sla.ttfb.poor`         | `800` / `1800`  | TTFB thresholds (ms)                      |
| SLA: Errors      | `sla.jserrors.good` / `sla.jserrors.poor` | `0` / `5`       | JS error count thresholds                 |
| SLA: Score       | `sla.score.good` / `sla.score.poor`       | `90` / `50`     | Performance score thresholds              |
| Improvement Area | `bottleneck.server.ratio`                 | `60`            | Server: TTFB % of LCP                     |
|                  | `bottleneck.resource.ratio`               | `40`            | Resource: slowest % of LCP                |
|                  | `bottleneck.client.ratio`                 | `60`            | Client: render time % of LCP              |
|                  | `bottleneck.layoutThrash.factor`          | `0.5`           | Layout: layoutCount vs domNodes           |
| Security         | `security.sanitize`                       | `true`          | Redact sensitive data in console messages |
| Debug            | `bpm.debug`                               | `false`         | Detailed operational logging              |

### Version Upgrades

When BPM detects a version mismatch in `bpm.properties`, it backs up the old file as
`bpm.properties.v<old>.bak` and writes the new template.

---

## Non-GUI Mode

BPM works fully in non-GUI mode. The live table is absent but JSONL and log summary are
written normally.

### -J Flag Overrides

```bash
# Standard run
jmeter -n -t test.jmx -l results.jtl

# CI run with custom output and debug
jmeter -n -t test.jmx -l results.jtl -Jbpm.output=build-1234-bpm.jsonl -Jbpm.debug=true
```

Resolution order: `-J flag` > `bpm.properties` > hardcoded default.

Only `bpm.output` and `bpm.debug` support `-J` overrides. All other properties are read from
`bpm.properties` only.

---

## CI Integration Guide

1. Add BPM JAR to your JMeter installation in CI.
2. Run with custom output: `-Jbpm.output=build-${BUILD_ID}-bpm.jsonl`
3. After the test, generate a report:
   ```bash
   ./bpm-report.sh -i build-${BUILD_ID}-bpm.jsonl \
     --scenario-name "Build ${BUILD_ID}" --virtual-users 50
   ```
4. Archive the JSONL file and HTML report as build artifacts.

---

## Performance Impact

BPM is designed as a pure observer with minimal overhead:

| Metric                           | Impact                                             |
|----------------------------------|----------------------------------------------------|
| Inter-sampler delay              | ~10-25ms per sample (CDP round-trips)              |
| Transaction Controller inflation | ~1-2%                                              |
| Throughput reduction             | ~1% (negligible for WebDriver tests)               |
| Memory                           | Running averages per label — not stored per-sample |
| JSONL writes                     | Buffered, flush every 1 record                     |

---

## Multiple BPM Listener Instances

A test plan may contain more than one BPM Listener element. Each instance operates independently:

- **Per-element file check:** On test start, each listener checks whether its output file exists.
  A dialog appears with **Overwrite** or **Don't Start JMeter Engine** options.
- **Independent lifecycle:** Each listener maintains its own JSONL writer, CDP sessions, health
  counters, and GUI state.
- **No cross-contamination:** Results from one listener never appear in another's output.
- **Default output path:** When no path is configured, all listeners fall back to `bpm-results.jsonl`.
  Always assign distinct output paths when using multiple listeners.

---

## Known Limitations

- **Chrome-only:** CDP metrics require Chrome or Chromium. Firefox, Safari, and Edge are not
  supported. Non-Chrome browsers are detected and skipped with a warning.
- **SPA caveats:** For client-side route changes, the old LCP value may linger because no new
  `largest-contentful-paint` event fires. BPM detects stale LCP and reports null for that sample.
- **WebDriver Sampler required:** BPM instruments only WebDriver Samplers. HTTP Samplers and
  other sampler types are silently skipped.
- **Pure observer:** BPM never modifies SampleResults, JTL output, or other listeners' behavior.
- **Charts require internet:** The HTML report loads Chart.js and SheetJS from CDNs. Open in a
  browser with internet access for charts and Excel export to work.

---

## Running Tests

```bash
# Build + all tests + JaCoCo coverage check
mvn clean verify

# Build only (no tests)
mvn clean package -DskipTests

# Single test class
mvn test -Dtest=JsonlWriterTest

# Release to Maven Central
mvn clean deploy -Prelease
```

---

## Troubleshooting

**The plugin does not appear in JMeter's Add > Listener menu.**
Verify the JAR is in `<JMETER_HOME>/lib/ext/`. Restart JMeter after copying.

**The Generate HTML Report button is greyed out.**
No data in the table. Run a test or load a JSONL file first.

**"No performance data available" dialog appears.**
No test data captured or loaded. Run a test or load a JSONL file first.

**Charts are blank in the HTML report.**
The report loads Chart.js from a CDN. Open the file in a browser with internet access.

**SPA actions show null scores.**
Expected — BPM detects stale LCP on client-side route changes and reports null rather than a
misleading score. Only actions with sufficient metric data (weight >= 0.45) receive a score.

**bpm.properties was overwritten after upgrade.**
BPM auto-detects version mismatches and creates a backup (`bpm.properties.v<old>.bak`) before
writing the new template. Check the backup for your customizations.

---

## Contributing

Bug reports and pull requests are welcome via
[GitHub Issues](https://github.com/sagaraggarwal86/bpm-jmeter-plugin/issues).

Before submitting a pull request:

- Run `mvn clean verify` and confirm all tests pass
- Test manually with JMeter 5.6.3 on your platform
- Keep each pull request focused on a single change

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
