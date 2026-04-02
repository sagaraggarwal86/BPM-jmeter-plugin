@echo off
REM DEPRECATED: Use bpm-ai-report.bat instead.
echo WARNING: bpm-cli-report.bat is deprecated. Use bpm-ai-report.bat instead. >&2
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%bpm-ai-report.bat" %*
exit /b %ERRORLEVEL%
