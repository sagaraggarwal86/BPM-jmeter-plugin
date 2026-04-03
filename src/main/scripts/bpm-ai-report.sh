#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Resolve JMETER_HOME (parent of bin/)
JM="${JMETER_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"

exec java -cp "$JM/lib/ext/*:$JM/lib/*" io.github.sagaraggarwal86.jmeter.bpm.cli.Main "$@"
