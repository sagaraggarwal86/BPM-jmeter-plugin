#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# BPM AI Report Generator — CLI wrapper (macOS / Linux)
#
# Generates an AI-powered browser performance analysis report from a
# BPM JSONL results file.
#
# Usage:
#   bpm-cli-report.sh -i bpm-results.jsonl --provider groq --config ai-reporter.properties
#   bpm-cli-report.sh --help
#
# Place this script in <JMETER_HOME>/bin/ alongside jmeter.sh.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

# Resolve JMETER_HOME from script location (expects <JMETER_HOME>/bin/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMETER_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# Verify JMeter installation
if [ ! -d "$JMETER_HOME/lib" ]; then
    echo "ERROR: JMeter lib directory not found at $JMETER_HOME/lib" >&2
    echo "Place this script in <JMETER_HOME>/bin/" >&2
    exit 1
fi

# Build classpath: plugin JARs + JMeter libs
CP="$JMETER_HOME/lib/ext/*:$JMETER_HOME/lib/*"

# Launch BPM CLI
exec java -cp "$CP" io.github.sagaraggarwal86.jmeter.bpm.cli.Main "$@"
