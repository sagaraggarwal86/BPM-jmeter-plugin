# Browser Performance Metrics (BPM) — JMeter Plugin

Capture browser-side rendering and performance metrics from JMeter WebDriver Sampler executions using Chrome DevTools
Protocol (CDP). Provides raw metrics, derived diagnostics (Performance Score, Bottleneck Detection), and a live results
table with SLA-based highlighting.

## Why BPM?

No existing JMeter plugin captures browser rendering metrics (Core Web Vitals, network waterfall, runtime health, JS
errors) during load tests. BPM fills this gap by correlating backend load with frontend user experience degradation —
and computes a Performance Score and Bottleneck Indicator so you can identify issues without deep technical analysis.

## Requirements

- **JMeter** 5.6.3+
- **WebDriver Sampler** plugin (install via JMeter Plugins Manager)
- **Chrome or Chromium** browser installed on the load generator

## Installation

### Via JMeter Plugins Manager (recommended)

Search for "Browser Performance Metrics" in the Plugins Manager and install.

### Manual JAR

1. Download `jmeter-browser-performance-metrics-<version>.jar`
   from [Maven Central](https://central.sonatype.com/artifact/io.github.sagaraggarwal86/jmeter-browser-performance-metrics)
   or the [Releases page](https://github.com/sagaraggarwal86/jmeter-browser-performance-metrics/releases).
2. Place the JAR in `<JMETER_HOME>/lib/ext/`.
3. Restart JMeter.

## Quick Start

1. Create a test plan with a **Thread Group** containing one or more **WebDriver Samplers** using Chrome.
2. Right-click the Thread Group → **Add → Listener → Browser Performance Metrics**.
3. Run the test.
4. Watch the live results table populate with performance scores, render times, and bottleneck indicators.

That's it — zero configuration required. BPM automatically instruments all WebDriver Samplers in the Thread Group.

## What BPM Captures

### Raw Metrics (4 Tiers)

| Tier | Category   | Metrics                                                 | Overhead   |
|------|------------|---------------------------------------------------------|------------|
| 1    | Web Vitals | FCP, LCP, CLS, TTFB                                     | Negligible |
| 2    | Network    | Total requests, total bytes, top N slowest + all failed | Low        |
| 3    | Runtime    | JS heap, DOM nodes, layout count, style recalc count    | Low        |
| 4    | Console    | JS error count, warning count, sanitized messages       | Negligible |

### Derived Metrics

| Metric                        | Formula                | Purpose                                |
|-------------------------------|------------------------|----------------------------------------|
| **Render Time** (ms)          | LCP − TTFB             | Pure client-side rendering time        |
| **Server Ratio** (%)          | (TTFB ÷ LCP) × 100     | Server vs client split (e.g. `32.20%`) |
| **FCP-LCP Gap** (ms)          | LCP − FCP              | Lazy-load or render-blocking indicator |
| **Failed Request Rate** (%)   | (failed ÷ total) × 100 | Network reliability                    |
| **Performance Score** (0-100) | Weighted composite     | Single-number health indicator         |
| **Bottleneck**                | Categorical detection  | Tells you what to fix                  |

### Performance Score

Weighted average against Google Core Web Vitals thresholds:

- LCP (40%), FCP (15%), CLS (15%), TTFB (15%), JS Errors (15%)
- **Good** ≥ 90 (green) · **Needs Work** 50-89 (amber) · **Poor** < 50 (red)

### Bottleneck Detection

First-match-wins priority:

1. `Reliability issue` — failed network requests detected
2. `Server bottleneck` — TTFB > 60% of LCP
3. `Resource bottleneck` — slowest resource > 40% of LCP
4. `Client rendering` — render time > 60% of LCP
5. `Layout thrashing` — layout count > DOM nodes × 0.5
6. `—` (none)

The GUI table shows the primary bottleneck. The JSONL file contains all matching bottlenecks in an array for detailed
analysis.

## GUI Overview

The listener provides a single-panel GUI:

- **Info Bar** — status messages (waiting, collecting, warnings)
- **Output File** — JSONL output path with Browse button
- **Performance Score Box** — overall score with colored progress bar and Good/NeedsWork/Poor counts
- **Controls** — Label Filter dropdown, Columns selector, Load File button
- **Results Table** — 7 always-visible derived columns + 8 optional raw columns with SLA coloring
- **Save Table** — export visible data to CSV

### Column Selector

Click **[Columns ▾]** to toggle raw metric columns (FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns). All are OFF by
default — the 7 derived columns tell the full story. Enable raw columns when you need to dig deeper.

### Save Table

Click **[Save Table]** to export the current view (respects label filter and column selection) as CSV. Opens in Excel,
Google Sheets, etc.

## Output Files

### JSONL (primary)

One JSON object per line, per WebDriver Sampler execution. Default: `bpm-results.jsonl`.

Contains: `bpmVersion`, `timestamp`, `threadName`, `iterationNumber`, `samplerLabel`, `samplerSuccess`,
`samplerDuration`, raw metric objects (`webVitals`, `network`, `runtime`, `console`), and `derived` object with score,
bottleneck, ratios.

### Summary JSON (CI/CD)

Written at test end: `bpm-results-summary.json`. Contains overall verdict (PASS/FAIL), overall score, SLA breach count,
and per-label details.

CI pipeline integration:

```bash
jq -e '.verdict == "PASS"' bpm-results-summary.json
```

### Log Summary

Printed at test end via JMeter log (INFO level) — a formatted table of per-label scores, render times, server ratios,
and bottlenecks plus a health line showing collection stats.

## Configuration

### bpm.properties

Auto-generated on first run at `<JMETER_HOME>/bin/bpm.properties`. All properties have sensible defaults matching Google
Core Web Vitals thresholds.

| Section        | Key                                       | Default         | Description                               |
|----------------|-------------------------------------------|-----------------|-------------------------------------------|
| Metric toggles | `metrics.webvitals`                       | `true`          | Enable/disable Web Vitals collection      |
|                | `metrics.network`                         | `true`          | Enable/disable Network collection         |
|                | `metrics.runtime`                         | `true`          | Enable/disable Runtime collection         |
|                | `metrics.console`                         | `true`          | Enable/disable Console collection         |
| Network        | `network.topN`                            | `5`             | Slowest resources to capture              |
| SLA: FCP       | `sla.fcp.good` / `sla.fcp.poor`           | `1800` / `3000` | FCP thresholds (ms)                       |
| SLA: LCP       | `sla.lcp.good` / `sla.lcp.poor`           | `2500` / `4000` | LCP thresholds (ms)                       |
| SLA: CLS       | `sla.cls.good` / `sla.cls.poor`           | `0.1` / `0.25`  | CLS thresholds                            |
| SLA: TTFB      | `sla.ttfb.good` / `sla.ttfb.poor`         | `800` / `1800`  | TTFB thresholds (ms)                      |
| SLA: Errors    | `sla.jserrors.good` / `sla.jserrors.poor` | `0` / `1`       | JS error count thresholds                 |
| SLA: Score     | `sla.score.good` / `sla.score.poor`       | `90` / `50`     | Performance score thresholds              |
| Bottleneck     | `bottleneck.server.ratio`                 | `60`            | Server bottleneck: TTFB % of LCP          |
|                | `bottleneck.resource.ratio`               | `40`            | Resource bottleneck: slowest % of LCP     |
|                | `bottleneck.client.ratio`                 | `60`            | Client rendering: render time % of LCP    |
|                | `bottleneck.layoutThrash.factor`          | `0.5`           | Layout thrashing: layoutCount vs domNodes |
| Security       | `security.sanitize`                       | `true`          | Redact sensitive data in console messages |
| Debug          | `bpm.debug`                               | `false`         | Detailed operational logging              |

### Version Upgrades

When BPM detects a version mismatch in `bpm.properties`, it backs up the old file as `bpm.properties.v<old>.bak` and
writes the new template. Merge your customizations manually.

## Non-GUI Mode

BPM works fully in non-GUI mode. The live table is absent but JSONL, summary JSON, and log summary are all written
normally.

### -J Flag Overrides

Two properties support per-run overrides via JMeter's `-J` flags:

```bash
# Standard run (uses bpm.properties defaults)
jmeter -n -t test-plan.jmx -l results.jtl

# CI run with custom output and debug enabled
jmeter -n -t test-plan.jmx -l results.jtl -Jbpm.output=build-1234-bpm.jsonl -Jbpm.debug=true
```

Resolution order: `-J flag` → `bpm.properties` → hardcoded default.

Only `bpm.output` and `bpm.debug` support `-J` overrides. All other properties (SLA thresholds, bottleneck ratios,
metric toggles, security) are read from `bpm.properties` only.

## CI Integration Guide

1. Add BPM JAR to your JMeter installation in CI.
2. Run with custom output: `-Jbpm.output=build-${BUILD_ID}-bpm.jsonl`
3. After the test, check the verdict:
   ```bash
   jq -e '.verdict == "PASS"' bpm-results-summary.json
   ```
4. Archive `bpm-results.jsonl` and `bpm-results-summary.json` as build artifacts.

## Performance Impact

BPM is designed as a pure observer with minimal overhead:

- **Inter-sampler delay:** ~10-25ms per sample (CDP round-trips for metric collection)
- **Transaction Controller inflation:** ~1-2% (additional time per contained sampler)
- **Throughput reduction:** ~1% (negligible for WebDriver-based tests which are already slow)
- **Memory:** Running averages per label — not stored per-sample in memory
- **JSONL writes:** Buffered, flush every 10 records

These numbers are specific to Tiers 1-4. Tier 5 (Full Trace) was excluded from v1 due to 10-15% browser overhead that
would skew load test results.

## Known Limitations

- **Chrome-only:** CDP metrics require Chrome or Chromium. Firefox, Safari, and Edge are not supported. Non-Chrome
  browsers are detected and skipped with a warning.
- **SPA caveats:** For single-page application client-side route changes, the old LCP value may linger because no new
  `largest-contentful-paint` event fires. BPM detects stale LCP and reports null for that sample.
- **WebDriver Sampler required:** BPM instruments only WebDriver Samplers. HTTP Samplers and other sampler types are
  silently skipped.
- **No JTL modification:** BPM is a pure observer. It never modifies SampleResults, JTL output, or other listeners'
  behavior.

## Building from Source

```bash
# Build + Layer 1/2 tests
mvn clean verify

# Build + E2E tests (requires Chrome)
mvn clean verify -Pe2e

# Release to Maven Central (requires GPG + credentials)
mvn clean deploy -Prelease
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
