@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

REM Resolve JMETER_HOME (parent of bin/)
if defined JMETER_HOME (
    set "JM=%JMETER_HOME%"
) else (
    for %%I in ("%SCRIPT_DIR%..") do set "JM=%%~fI"
)

java -cp "%JM%\lib\ext\*;%JM%\lib\*" io.github.sagaraggarwal86.jmeter.bpm.cli.Main %*
exit /b %ERRORLEVEL%
