@echo off
REM Start DebuggerX JDWP Proxy
REM This proxy enables multiple debuggers (IntelliJ + MCP Inspector) to connect simultaneously
REM to the same JVM through intelligent packet routing

REM Port configuration
set JVM_JDWP_PORT=61959
set DEBUGGERX_PROXY_PORT=55005

echo Starting DebuggerX proxy...
echo JVM JDWP Port (target):  %JVM_JDWP_PORT%
echo Proxy Port (debuggers):  %DEBUGGERX_PROXY_PORT%
echo.
echo Multiple debuggers can connect to port %DEBUGGERX_PROXY_PORT%
echo debuggerX will route packets intelligently based on request IDs
echo.

java -DjvmServerHost=localhost -DjvmServerPort=%JVM_JDWP_PORT% -DdebuggerProxyPort=%DEBUGGERX_PROXY_PORT% -jar lib\debuggerX.jar

pause
