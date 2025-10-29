@echo off
setlocal

REM Check status of debuggerX and JVM JDWP

set JVM_JDWP_PORT=61959
set DEBUGGERX_PROXY_PORT=55005
set PID_FILE=debuggerX.pid
set APP_NAME=debuggerX-proxy

echo ============================================
echo   debuggerX Status Check
echo ============================================
echo.

REM 1. Check JVM JDWP Port
echo [1] JVM JDWP Port (%JVM_JDWP_PORT%):
netstat -ano | findstr ":%JVM_JDWP_PORT%" | findstr "LISTENING" >NUL 2>&1
if errorlevel 1 (
    echo    [X] NOT LISTENING - JVM not running with JDWP
    echo.
    echo    To fix: Start Tomcat/App with VM option:
    echo    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%JVM_JDWP_PORT%
    set JVM_OK=0
) else (
    echo    [OK] LISTENING
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%JVM_JDWP_PORT%" ^| findstr "LISTENING"') do (
        echo    [INFO] Process PID: %%a
    )
    set JVM_OK=1
)
echo.

REM 2. Check debuggerX Proxy Port
echo [2] debuggerX Proxy Port (%DEBUGGERX_PROXY_PORT%):
netstat -ano | findstr ":%DEBUGGERX_PROXY_PORT%" | findstr "LISTENING" >NUL 2>&1
if errorlevel 1 (
    echo    [X] NOT LISTENING - debuggerX not running
    echo.
    echo    To fix: Run 'start-debuggerx.bat'
    set PROXY_OK=0
) else (
    echo    [OK] LISTENING
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%DEBUGGERX_PROXY_PORT%" ^| findstr "LISTENING"') do (
        echo    [INFO] Process PID: %%a
    )
    set PROXY_OK=1
)
echo.

REM 3. Check debuggerX Process
echo [3] debuggerX Process:
if exist %PID_FILE% (
    set /p PID=<%PID_FILE%
    tasklist /FI "PID eq %PID%" 2>NUL | find "%PID%" >NUL
    if not errorlevel 1 (
        echo    [OK] Running with PID: %PID%
        echo    [INFO] PID file: %PID_FILE%
    ) else (
        echo    [X] PID file exists but process is dead
        echo    [INFO] Stale PID file: %PID_FILE%
        echo    [INFO] Run 'stop-debuggerx.bat' to clean up
    )
) else (
    wmic process where "CommandLine like '%%-Dapp.name=%APP_NAME%%%'" get ProcessId /value 2>NUL | find "=" >NUL
    if not errorlevel 1 (
        for /f "tokens=2" %%a in ('wmic process where "CommandLine like '%%-Dapp.name^=%APP_NAME%%%'" get ProcessId /value ^| find "="') do (
            echo    [OK] Running with PID: %%a
            echo    [WARNING] No PID file found - inconsistent state
        )
    ) else (
        echo    [X] Not running
    )
)
echo.

REM 4. Check Java processes with JDWP
echo [4] Java Processes with JDWP:
wmic process where "name='java.exe' and CommandLine like '%%jdwp%%'" get ProcessId,CommandLine 2>NUL | findstr "jdwp" >NUL
if errorlevel 1 (
    echo    [X] No Java process found with JDWP enabled
) else (
    for /f "skip=1 tokens=*" %%a in ('wmic process where "name='java.exe' and CommandLine like '%%jdwp%%'" get ProcessId^,CommandLine 2^>NUL') do (
        echo    [OK] %%a
    )
)
echo.

REM 5. Summary
echo ============================================
echo   Summary
echo ============================================
if defined JVM_OK if defined PROXY_OK (
    if %JVM_OK%==1 if %PROXY_OK%==1 (
        echo [SUCCESS] All systems ready!
        echo.
        echo You can now:
        echo   - Connect IntelliJ Remote Debug to localhost:%DEBUGGERX_PROXY_PORT%
        echo   - Use MCP Inspector: jdwp_connect("localhost", %DEBUGGERX_PROXY_PORT%^)
        goto :end
    )
)

echo [WARNING] System not ready
echo.
if not defined JVM_OK (
    echo   Step 1: Start JVM with JDWP on port %JVM_JDWP_PORT%
)
if not defined PROXY_OK (
    echo   Step 2: Run 'start-debuggerx.bat'
)

:end
echo ============================================
echo.
pause
