#!/usr/bin/env bash
# DEPRECATED: Use bpm-ai-report.sh instead.
echo "WARNING: bpm-cli-report.sh is deprecated. Use bpm-ai-report.sh instead." >&2
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/bpm-ai-report.sh" "$@"
