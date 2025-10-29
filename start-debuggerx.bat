@echo off
setlocal

REM Start DebuggerX JDWP Proxy
REM This proxy enables multiple debuggers (IntelliJ + MCP Inspector) to connect simultaneously
REM to the same JVM through intelligent packet routing

REM Port configuration
set JVM_JDWP_PORT=61959
set DEBUGGERX_PROXY_PORT=55005
set PID_FILE=debuggerX.pid
set APP_NAME=debuggerX-proxy

REM Check if already running
if exist %PID_FILE% (
    set /p OLD_PID=<%PID_FILE%
    tasklist /FI "PID eq %OLD_PID%" 2>NUL | find "%OLD_PID%" >NUL
    if not errorlevel 1 (
        echo [WARNING] debuggerX is already running with PID %OLD_PID%
        echo Run 'stop-debuggerx.bat' first or use 'taskkill /PID %OLD_PID% /F'
        pause
        exit /b 1
    )
    REM Old PID file exists but process is dead, clean it up
    del %PID_FILE%
)

echo Starting DebuggerX proxy...
echo JVM JDWP Port (target):  %JVM_JDWP_PORT%
echo Proxy Port (debuggers):  %DEBUGGERX_PROXY_PORT%
echo.

REM Check if JVM JDWP port is available
echo [INFO] Checking if JVM is running with JDWP on port %JVM_JDWP_PORT%...
netstat -ano | findstr ":%JVM_JDWP_PORT%" | findstr "LISTENING" >NUL 2>&1
if errorlevel 1 (
    echo.
    echo [ERROR] No JVM found listening on port %JVM_JDWP_PORT%
    echo.
    echo debuggerX requires a JVM to be running with JDWP enabled.
    echo.
    echo TO FIX:
    echo   1. Start your application (Tomcat/Spring Boot) in IntelliJ with RUN mode
    echo   2. Add these VM Options to your run configuration:
    echo      -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%JVM_JDWP_PORT%
    echo.
    echo   3. Verify the JVM is listening:
    echo      netstat -ano ^| findstr :%JVM_JDWP_PORT%
    echo.
    echo   4. Then run this script again
    echo.
    pause
    exit /b 1
)
echo [SUCCESS] JVM found listening on port %JVM_JDWP_PORT%
echo.

REM Start with identifiable properties and window title
start "DebuggerX" java ^
    -Dapp.name=%APP_NAME% ^
    -DjvmServerHost=localhost ^
    -DjvmServerPort=%JVM_JDWP_PORT% ^
    -DdebuggerProxyPort=%DEBUGGERX_PROXY_PORT% ^
    -jar lib\debuggerX.jar

REM Wait for process to start
timeout /t 2 >NUL

REM Save PID to file
for /f "tokens=2" %%a in ('wmic process where "CommandLine like '%%-Dapp.name^=%APP_NAME%%%'" get ProcessId /value ^| find "="') do (
    echo %%a>%PID_FILE%
    echo [SUCCESS] Process started with PID: %%a
    echo [INFO] PID saved to %PID_FILE%
)

echo.
echo debuggerX is ready!
echo Multiple debuggers can connect to port %DEBUGGERX_PROXY_PORT%
echo debuggerX will route packets intelligently based on request IDs
echo.
echo To stop: run 'stop-debuggerx.bat' or 'taskkill /PID [PID] /F'
echo.

pause
