@echo off
setlocal

REM Stop DebuggerX JDWP Proxy

set PID_FILE=debuggerX.pid
set APP_NAME=debuggerX-proxy

echo Stopping debuggerX proxy...
echo.

REM Try to stop using PID file
if exist %PID_FILE% (
    set /p PID=<%PID_FILE%
    echo [INFO] Found PID file: %PID_FILE%
    echo [INFO] Stopping process with PID: %PID%

    REM Check if process is actually running
    tasklist /FI "PID eq %PID%" 2>NUL | find "%PID%" >NUL
    if not errorlevel 1 (
        taskkill /PID %PID% /F >NUL 2>&1
        if not errorlevel 1 (
            echo [SUCCESS] debuggerX stopped successfully
            del %PID_FILE%
            goto :end
        ) else (
            echo [ERROR] Failed to stop process %PID%
        )
    ) else (
        echo [WARNING] Process %PID% is not running
        del %PID_FILE%
    )
) else (
    echo [WARNING] No PID file found at %PID_FILE%
)

REM Fallback: try to find process by app.name property
echo [INFO] Trying to find debuggerX by app name...
for /f "tokens=2" %%a in ('wmic process where "CommandLine like '%%-Dapp.name^=%APP_NAME%%%'" get ProcessId /value 2^>NUL ^| find "="') do (
    echo [INFO] Found debuggerX with PID: %%a
    taskkill /PID %%a /F >NUL 2>&1
    if not errorlevel 1 (
        echo [SUCCESS] debuggerX stopped successfully
        goto :end
    ) else (
        echo [ERROR] Failed to stop process %%a
    )
)

REM Last resort: find by port
echo [INFO] Trying to find process listening on port %DEBUGGERX_PROXY_PORT%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%DEBUGGERX_PROXY_PORT% ^| findstr LISTENING') do (
    echo [INFO] Found process PID: %%a listening on port %DEBUGGERX_PROXY_PORT%
    taskkill /PID %%a /F >NUL 2>&1
    if not errorlevel 1 (
        echo [SUCCESS] Process stopped successfully
        goto :end
    )
)

echo [ERROR] debuggerX process not found
echo.

:end
echo.
pause
