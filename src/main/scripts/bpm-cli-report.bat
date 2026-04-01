@echo off
REM ──────────────────────────────────────────────────────────────────
REM BPM AI Report Generator — CLI wrapper (Windows)
REM
REM Generates an AI-powered browser performance analysis report from a
REM BPM JSONL results file.
REM
REM Usage:
REM   bpm-cli-report.bat -i bpm-results.jsonl --provider groq --config ai-reporter.properties
REM   bpm-cli-report.bat --help
REM
REM Place this script in <JMETER_HOME>\bin\ alongside jmeter.bat.
REM ──────────────────────────────────────────────────────────────────

setlocal

REM Resolve JMETER_HOME from script location
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.."
set "JMETER_HOME=%CD%"
popd

REM Verify JMeter installation
if not exist "%JMETER_HOME%\lib" (
    echo ERROR: JMeter lib directory not found at %JMETER_HOME%\lib >&2
    echo Place this script in ^<JMETER_HOME^>\bin\ >&2
    exit /b 1
)

REM Build classpath
set "CP=%JMETER_HOME%\lib\ext\*;%JMETER_HOME%\lib\*"

REM Launch BPM CLI
java -cp "%CP%" io.github.sagaraggarwal86.jmeter.bpm.cli.Main %*
exit /b %ERRORLEVEL%
