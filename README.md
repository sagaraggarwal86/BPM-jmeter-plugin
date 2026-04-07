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
- [GUI Overview](#gui-overview)
- [HTML Performance Report](#html-performance-report)
- [CLI Mode](#cli-mode)
- [Output Files](#output-files)
- [Configuration](#configuration)
- [Performance Impact](#performance-impact)
- [Multiple BPM Listener Instances](#multiple-bpm-listener-instances)
- [Known Limitations](#known-limitations)
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
| **Filtering**               | Start/End offset, transaction name (text/regex), stability (multi-select), improvement area (multi-select)                  |
| **Column Visibility**       | Show or hide raw metric columns via a dropdown multi-select control                                                         |
| **SLA Thresholds**          | Configurable per-metric thresholds — breaching cells highlighted in color                                                   |
| **HTML Performance Report** | 6-panel report: executive summary, metrics, trends, SLA compliance, findings, risk assessment                               |
| **CLI Mode**                | Generate reports from the command line — no JMeter GUI required                                                             |
| **CSV / JSONL Output**      | CSV table export + one JSON record per sampler action (CI-friendly)                                                         |
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

## GUI Overview

The listener provides a single-panel GUI:

- **Output File** — JSONL output path with Browse button. Press Enter to load an existing file.
- **Filter Settings** — Start/End Offset, Transaction Names, Stability, Improvement Area, Column Selector, Apply Filters
- **Test Time Info** — Start, End, Duration (live-updating during test)
- **Overall Performance Score** — colored progress bar with Good / Needs Work / Poor counts
- **Results Table** — 10 always-visible derived columns + 8 toggleable raw columns with SLA coloring
- **Bottom Bar** — Save Table Data (CSV), Generate HTML Report, Chart Interval

### Column Selector

Click **Select Columns** to toggle raw metric columns (FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns).
All are OFF by default — the 10 derived columns tell the full story. Enable raw columns when you
need to dig deeper.

### Filter Settings

All filters apply on **Apply Filters** button click (manual, not auto). All filters use AND logic — a
row must match every active filter to appear.

**Start / End Offset** — Exclude ramp-up and ramp-down samples by time window (seconds):

| Start Offset | End Offset | Behaviour                                       |
|--------------|------------|-------------------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                            |
| `5`          | *(empty)*  | Skip first 5 seconds; include the rest          |
| *(empty)*    | `25`       | Include up to 25 seconds; skip everything after |
| `5`          | `25`       | Include only the 5s - 25s window                |

**Transaction Names** — Filter by sampler label:

| Mode                     | Behaviour                        | Example                          |
|--------------------------|----------------------------------|----------------------------------|
| **Plain text** (default) | Case-insensitive substring match | `login` matches `Login Flow`     |
| **RegEx** (checkbox on)  | Java regex pattern match         | `Login\|Checkout` matches either |
| **Include** (default)    | Show only matching transactions  |                                  |
| **Exclude**              | Hide matching transactions       |                                  |

**Stability** — Multi-select checkbox dropdown by CLS category:

| Option       | Matches          |
|--------------|------------------|
| Stable       | CLS ≤ 0.1        |
| Minor Shifts | 0.1 < CLS ≤ 0.25 |
| Unstable     | CLS > 0.25       |

**Improvement Area** — Multi-select checkbox dropdown by bottleneck type:

| Option                 | Matches                               |
|------------------------|---------------------------------------|
| None                   | No bottleneck detected (shown as `-`) |
| Reduce Server Response | Server TTFB is the primary bottleneck |
| Optimise Heavy Assets  | Large resources delaying render       |
| Reduce Render Work     | Client-side rendering overhead        |
| Reduce DOM Complexity  | Excessive DOM causing layout thrash   |
| Fix Network Failures   | Failed network requests detected      |

---

## HTML Performance Report

Click **Generate HTML Report** to create a comprehensive performance analysis report.
A save dialog lets you choose where to save the HTML file. The report opens automatically in your browser.

### Report Panels

| # | Panel               | Description                                                                                        |
|---|---------------------|----------------------------------------------------------------------------------------------------|
| 1 | Executive Summary   | KPI cards + 5-paragraph narrative. Breaches grouped by bottleneck. Errors with relative frequency  |
| 2 | Performance Metrics | Full data table with pagination, column sorting, and transaction search                            |
| 3 | Performance Trends  | 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render) with SLA threshold lines                    |
| 4 | SLA Compliance      | Color + icon verdict matrix per metric per transaction                                             |
| 5 | Critical Findings   | Grouped by bottleneck type with 4-part recommendations (what to do, quick win, long-term, outcome) |
| 6 | Risk Assessment     | Capacity risks, borderline pages, unmeasured navigations, performance trend                        |

### Report Features

- ARIA keyboard navigation, dark mode toggle (auto/dark/light), metadata grid
- Pagination (10/25/50/100), click-to-sort columns, transaction search, per-transaction chart filter
- SLA threshold lines on charts, styled Excel export with SLA cell coloring
- Self-contained HTML — Chart.js and xlsx-js-style bundled in JAR for offline use, CDN as fallback

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

### -J Flag Overrides (Non-GUI Mode)

```bash
# Standard non-GUI run
jmeter -n -t test.jmx -l results.jtl

# Custom output and debug
jmeter -n -t test.jmx -l results.jtl -Jbpm.output=build-1234-bpm.jsonl -Jbpm.debug=true
```

Resolution order: `-J flag` > `bpm.properties` > hardcoded default.
Only `bpm.output` and `bpm.debug` support `-J` overrides.

### CI Example

```bash
# Run test
jmeter -n -t test.jmx -Jbpm.output=bpm-results.jsonl

# Generate report
./bpm-report.sh -i bpm-results.jsonl -o report.html \
  --scenario-name "Nightly Load Test" --virtual-users 50

# Archive bpm-results.jsonl and report.html as build artifacts
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

## Performance Impact

| Metric                           | Impact                                             |
|----------------------------------|----------------------------------------------------|
| Inter-sampler delay              | ~10-25ms per sample (CDP round-trips)              |
| Transaction Controller inflation | ~1-2%                                              |
| Throughput reduction             | ~1% (negligible for WebDriver tests)               |
| Memory                           | Running averages per label — not stored per-sample |
| JSONL writes                     | Buffered, flush every 1 record                     |

---

## Multiple BPM Listener Instances

- Each listener maintains its own JSONL writer, health counters, and GUI state independently.
- On test start, a single dialog lists all file conflicts across all listeners. Choose **Overwrite** or **Don't Start**.
- Always assign distinct output paths when using multiple listeners (default is shared `bpm-results.jsonl`).

---

## Known Limitations

- **SPA caveats:** For client-side route changes, the old LCP may linger (no new `largest-contentful-paint` event).
  BPM detects stale LCP and reports null for that sample.
- **WebDriver Sampler only:** HTTP Samplers and other non-WebDriver types are silently skipped.
- **Charts offline:** Chart.js and xlsx-js-style are bundled in the JAR for offline use. If the bundled versions fail
  to load, the report falls back to CDN — internet access required in that case.

---

## Troubleshooting

| Problem                                  | Solution                                                                                |
|------------------------------------------|-----------------------------------------------------------------------------------------|
| Plugin not in Add > Listener menu        | Verify JAR is in `<JMETER_HOME>/lib/ext/`. Restart JMeter.                              |
| Generate HTML Report button greyed out   | No data in the table. Run a test or load a JSONL file first.                            |
| "No performance data available" dialog   | No data captured or loaded. Run a test or load a JSONL file.                            |
| Charts blank in HTML report              | Chart.js CDN unreachable. Open in a browser with internet access.                       |
| SPA actions show null scores             | Expected — stale LCP detected. Only actions with metric weight >= 0.45 receive a score. |
| bpm.properties overwritten after upgrade | BPM creates a backup (`bpm.properties.v<old>.bak`). Check it for your customizations.   |

---

## Contributing

Bug reports and pull requests are welcome via
[GitHub Issues](https://github.com/sagaraggarwal86/bpm-jmeter-plugin/issues).

Before submitting a pull request:

```bash
mvn clean verify          # All tests + coverage check must pass
mvn clean deploy -Prelease  # Release to Maven Central
```

- Test manually with JMeter 5.6.3 on your platform
- Keep each pull request focused on a single change

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
