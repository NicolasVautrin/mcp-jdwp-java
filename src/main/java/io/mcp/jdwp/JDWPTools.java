package io.mcp.jdwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jdi.*;
import io.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import io.mcp.jdwp.watchers.Watcher;
import io.mcp.jdwp.watchers.WatcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Tools for JDWP inspection
 */
@Service
public class JDWPTools {

    private static final Logger log = LoggerFactory.getLogger(JDWPTools.class);

    private final JDIConnectionService jdiService;
    private final DebuggerXManager debuggerXManager;
    private final WatcherManager watcherManager;
    private final JdiExpressionEvaluator expressionEvaluator;

    // Ports from system properties (configured in .mcp.json)
    private static final int DEBUGGERX_PROXY_PORT = Integer.parseInt(
        System.getProperty("DEBUGGERX_PROXY_PORT", "55005")
    );

    public JDWPTools(JDIConnectionService jdiService, DebuggerXManager debuggerXManager, WatcherManager watcherManager, JdiExpressionEvaluator expressionEvaluator) {
        this.jdiService = jdiService;
        this.debuggerXManager = debuggerXManager;
        this.watcherManager = watcherManager;
        this.expressionEvaluator = expressionEvaluator;
    }

    @McpTool(description = "Connect to the JDWP server using configuration from .mcp.json")
    public String jdwp_connect() {
        // Use configuration from system properties
        String host = "localhost";
        int port = DEBUGGERX_PROXY_PORT;

        // Step 1: Ensure debuggerX is running (auto-start if needed)
        String debuggerXStatus = debuggerXManager.ensureDebuggerXRunning();
        if (debuggerXStatus != null) {
            // Error occurred - return detailed message
            return debuggerXStatus;
        }

        // Step 2: Try to connect to debuggerX
        try {
            return jdiService.connect(host, port);
        } catch (Exception e) {
            // Connection failed even though debuggerX should be running
            return String.format(
                "[ERROR] Connection failed to %s:%d\n\n" +
                "debuggerX appears to be running but connection failed.\n\n" +
                "Possible causes:\n" +
                "  1. debuggerX crashed after starting\n" +
                "  2. Firewall blocking the connection\n" +
                "  3. Wrong port configured in .mcp.json (currently: %d)\n\n" +
                "Status:\n%s\n" +
                "Original error: %s",
                host, port, port, debuggerXManager.getStatus(), e.getMessage()
            );
        }
    }

    @McpTool(description = "Disconnect from the JDWP server")
    public String jdwp_disconnect() {
        return jdiService.disconnect();
    }

    @McpTool(description = "Get JVM version information")
    public String jdwp_get_version() {
        try {
            VirtualMachine vm = jdiService.getVM();
            return String.format("VM: %s\nVersion: %s\nDescription: %s",
                vm.name(), vm.version(), vm.description());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "List all threads in the JVM with their status and frame count")
    public String jdwp_get_threads() {
        try {
            VirtualMachine vm = jdiService.getVM();
            List<ThreadReference> threads = vm.allThreads();

            StringBuilder result = new StringBuilder();
            result.append(String.format("Found %d threads:\n\n", threads.size()));

            for (int i = 0; i < threads.size(); i++) {
                ThreadReference thread = threads.get(i);
                result.append(String.format("Thread %d:\n", i));
                result.append(String.format("  ID: %d\n", thread.uniqueID()));
                result.append(String.format("  Name: %s\n", thread.name()));
                result.append(String.format("  Status: %d\n", thread.status()));
                result.append(String.format("  Suspended: %s\n", thread.isSuspended()));

                if (thread.isSuspended()) {
                    try {
                        int frameCount = thread.frameCount();
                        result.append(String.format("  Frames: %d\n", frameCount));
                    } catch (IncompatibleThreadStateException ignored) {}
                }

                result.append("\n");
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get the call stack for a specific thread (by thread ID)")
    public String jdwp_get_stack(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElse(null);

            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Thread must be stopped at a breakpoint.";
            }

            List<StackFrame> frames = thread.frames();
            StringBuilder result = new StringBuilder();
            result.append(String.format("Stack trace for thread %d (%s) - %d frames:\n\n",
                threadId, thread.name(), frames.size()));

            for (int i = 0; i < frames.size(); i++) {
                StackFrame frame = frames.get(i);
                Location location = frame.location();

                result.append(String.format("Frame %d:\n", i));
                result.append(String.format("  at %s.%s(",
                    location.declaringType().name(),
                    location.method().name()));

                try {
                    result.append(String.format("%s:%d)\n",
                        location.sourceName(),
                        location.lineNumber()));
                } catch (AbsentInformationException e) {
                    result.append("Unknown Source)\n");
                }
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get local variables for a specific frame in a thread")
    public String jdwp_get_locals(
            @McpToolParam(description = "Thread unique ID") long threadId,
            @McpToolParam(description = "Frame index (0 = current frame)") int frameIndex) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElse(null);

            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            StackFrame frame = thread.frame(frameIndex);
            Map<LocalVariable, Value> vars = frame.getValues(frame.visibleVariables());

            StringBuilder result = new StringBuilder();
            result.append(String.format("Local variables in frame %d:\n\n", frameIndex));

            for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
                LocalVariable var = entry.getKey();
                Value value = entry.getValue();

                result.append(String.format("%s (%s) = %s\n",
                    var.name(),
                    var.typeName(),
                    formatValue(value)));
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get fields (properties) of an object by its object ID (obtained from jdwp_get_locals)")
    public String jdwp_get_fields(@McpToolParam(description = "Object unique ID") long objectId) {
        try {
            return jdiService.getObjectFields(objectId);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Resume execution of all threads in the VM")
    public String jdwp_resume() {
        try {
            VirtualMachine vm = jdiService.getVM();
            vm.resume();
            return "All threads resumed";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Step over (execute current line and stop at next line)")
    public String jdwp_step_over(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElse(null);

            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Cannot step.";
            }

            // Create step request (STEP_LINE + STEP_OVER)
            com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
            com.sun.jdi.request.StepRequest stepRequest = erm.createStepRequest(
                thread,
                com.sun.jdi.request.StepRequest.STEP_LINE,
                com.sun.jdi.request.StepRequest.STEP_OVER
            );
            stepRequest.addCountFilter(1); // Single step
            stepRequest.enable();

            thread.resume();

            return String.format("Step over executed on thread %d (%s)", threadId, thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Step into (enter method calls)")
    public String jdwp_step_into(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElse(null);

            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Cannot step.";
            }

            // Create step request (STEP_LINE + STEP_INTO)
            com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
            com.sun.jdi.request.StepRequest stepRequest = erm.createStepRequest(
                thread,
                com.sun.jdi.request.StepRequest.STEP_LINE,
                com.sun.jdi.request.StepRequest.STEP_INTO
            );
            stepRequest.addCountFilter(1); // Single step
            stepRequest.enable();

            thread.resume();

            return String.format("Step into executed on thread %d (%s)", threadId, thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Step out (exit current method)")
    public String jdwp_step_out(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElse(null);

            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Cannot step.";
            }

            // Create step request (STEP_LINE + STEP_OUT)
            com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
            com.sun.jdi.request.StepRequest stepRequest = erm.createStepRequest(
                thread,
                com.sun.jdi.request.StepRequest.STEP_LINE,
                com.sun.jdi.request.StepRequest.STEP_OUT
            );
            stepRequest.addCountFilter(1); // Single step
            stepRequest.enable();

            thread.resume();

            return String.format("Step out executed on thread %d (%s)", threadId, thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Set a breakpoint at a specific line in a class")
    public String jdwp_set_breakpoint(
            @McpToolParam(description = "Fully qualified class name (e.g. 'com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto')") String className,
            @McpToolParam(description = "Line number") int lineNumber) {
        try {
            VirtualMachine vm = jdiService.getVM();
            com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

            // Find the class
            List<ReferenceType> classes = vm.classesByName(className);
            if (classes.isEmpty()) {
                return String.format("Error: Class '%s' not found. Make sure the class is loaded.", className);
            }

            ReferenceType refType = classes.get(0);

            // Find location
            List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
            if (locations.isEmpty()) {
                return String.format("Error: No executable code found at line %d in class %s", lineNumber, className);
            }

            com.sun.jdi.Location location = locations.get(0);

            // Create breakpoint
            com.sun.jdi.request.BreakpointRequest bpRequest = erm.createBreakpointRequest(location);
            bpRequest.enable();

            return String.format("Breakpoint set at %s:%d", className, lineNumber);
        } catch (AbsentInformationException e) {
            return "Error: No line number information available for this class. Compile with debug info (-g).";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Remove a breakpoint at a specific line in a class")
    public String jdwp_clear_breakpoint(
            @McpToolParam(description = "Fully qualified class name") String className,
            @McpToolParam(description = "Line number") int lineNumber) {
        try {
            VirtualMachine vm = jdiService.getVM();
            com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();

            // Find the class
            List<ReferenceType> classes = vm.classesByName(className);
            if (classes.isEmpty()) {
                return String.format("Error: Class '%s' not found", className);
            }

            ReferenceType refType = classes.get(0);

            // Find location
            List<com.sun.jdi.Location> locations = refType.locationsOfLine(lineNumber);
            if (locations.isEmpty()) {
                return String.format("Error: No code at line %d in class %s", lineNumber, className);
            }

            com.sun.jdi.Location location = locations.get(0);

            // Find and delete matching breakpoint requests
            List<com.sun.jdi.request.BreakpointRequest> breakpoints = erm.breakpointRequests();
            int removed = 0;
            for (com.sun.jdi.request.BreakpointRequest bp : breakpoints) {
                if (bp.location().equals(location)) {
                    erm.deleteEventRequest(bp);
                    removed++;
                }
            }

            if (removed == 0) {
                return String.format("No breakpoint found at %s:%d", className, lineNumber);
            }

            return String.format("Removed %d breakpoint(s) at %s:%d", removed, className, lineNumber);
        } catch (AbsentInformationException e) {
            return "Error: No line number information available for this class";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "List all breakpoints from all connected debuggers (IntelliJ, MCP, etc.)")
    public String jdwp_list_breakpoints() {
        try {
            // Query debuggerX HTTP API for global breakpoints
            int httpPort = DEBUGGERX_PROXY_PORT + 1; // HTTP API is on proxy port + 1
            String url = String.format("http://localhost:%d/breakpoints", httpPort);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: HTTP " + response.statusCode() + " - " + response.body();
            }

            String body = response.body();

            // Parse JSON using Jackson
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode breakpointsArray = root.get("breakpoints");

            if (breakpointsArray == null || breakpointsArray.size() == 0) {
                return "No breakpoints set";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Active breakpoints: %d\n\n", breakpointsArray.size()));

            int i = 1;
            for (JsonNode bp : breakpointsArray) {
                int requestId = bp.get("requestId").asInt();
                long classId = bp.get("classId").asLong();
                long methodId = bp.get("methodId").asLong();
                long codeIndex = bp.get("codeIndex").asLong();

                JsonNode classNameNode = bp.get("className");
                JsonNode methodNameNode = bp.get("methodName");
                JsonNode lineNumberNode = bp.get("lineNumber");

                String className = (classNameNode != null && !classNameNode.isNull()) ? classNameNode.asText() : null;
                String methodName = (methodNameNode != null && !methodNameNode.isNull()) ? methodNameNode.asText() : null;
                int lineNumber = (lineNumberNode != null && !lineNumberNode.isNull()) ? lineNumberNode.asInt() : -1;

                result.append(String.format("Breakpoint %d (Request ID: %d):\n", i++, requestId));

                // Use resolved information from debuggerX if available
                if (className != null && !className.isEmpty()) {
                    result.append(String.format("  Class: %s\n", className));

                    if (methodName != null && !methodName.isEmpty()) {
                        result.append(String.format("  Method: %s\n", methodName));
                    }

                    if (lineNumber > 0) {
                        result.append(String.format("  Line: %d\n", lineNumber));
                    } else if (methodName == null || methodName.isEmpty()) {
                        result.append(String.format("  Method ID: %d\n", methodId));
                    }

                    result.append(String.format("  Code Index: %d\n", codeIndex));
                } else {
                    // Fallback to raw IDs if not resolved
                    result.append(String.format("  Class ID: %d (unresolved)\n", classId));
                    result.append(String.format("  Method ID: %d\n", methodId));
                    result.append(String.format("  Code Index: %d\n", codeIndex));
                }

                result.append("\n");
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Clear a specific breakpoint by its requestId (from jdwp_list_breakpoints)")
    public String jdwp_clear_breakpoint_by_id(@McpToolParam(description = "Breakpoint request ID to clear") int requestId) {
        try {
            // Call debuggerX HTTP API to clear the breakpoint
            int httpPort = DEBUGGERX_PROXY_PORT + 1; // HTTP API is on proxy port + 1
            String url = String.format("http://localhost:%d/breakpoints/%d", httpPort, requestId);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return String.format("Breakpoint %d cleared successfully", requestId);
            } else if (response.statusCode() == 404) {
                return String.format("Breakpoint %d not found", requestId);
            } else {
                return "Error: HTTP " + response.statusCode() + " - " + response.body();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get recent JDWP events (breakpoints, steps, exceptions, etc.)")
    public String jdwp_get_events(@McpToolParam(description = "Number of recent events to retrieve (default: 20, max: 100)") Integer count) {
        try {
            if (count == null || count <= 0) {
                count = 20;
            }
            if (count > 100) {
                count = 100;
            }

            List<String> events = jdiService.getRecentEvents(count);

            if (events.isEmpty()) {
                return "No events recorded yet.\n\n" +
                       "The event listener captures all JDWP events including:\n" +
                       "  - Breakpoints (from IntelliJ or MCP)\n" +
                       "  - Steps (step over, step into, step out)\n" +
                       "  - Exceptions\n" +
                       "  - Method entries/exits (if enabled)\n\n" +
                       "Events are captured automatically when connected.";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Recent JDWP events (%d most recent):\n\n", events.size()));

            for (int i = 0; i < events.size(); i++) {
                result.append(String.format("%d. %s\n", i + 1, events.get(i)));
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Clear the JDWP event history")
    public String jdwp_clear_events() {
        try {
            jdiService.clearEvents();
            return "Event history cleared";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Configure exception monitoring (enable/disable caught exceptions, set filters)")
    public String jdwp_configure_exception_monitoring(
        @McpToolParam(description = "Enable/disable capturing caught exceptions (true/false, optional)")
        Boolean captureCaught,

        @McpToolParam(description = "Comma-separated list of packages to monitor (e.g. 'com.axelor,org.myapp') - empty means all (optional)")
        String includePackages,

        @McpToolParam(description = "Comma-separated list of exception classes to exclude (e.g. 'java.lang.NumberFormatException,java.io.IOException') (optional)")
        String excludeClasses
    ) {
        try {
            return jdiService.configureExceptionMonitoring(captureCaught, includePackages, excludeClasses);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get current exception monitoring configuration")
    public String jdwp_get_exception_config() {
        try {
            return jdiService.getExceptionConfig();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Clear ALL breakpoints from ALL clients (IntelliJ, MCP, etc.)")
    public String jdwp_clear_all_breakpoints() {
        try {
            // Query debuggerX HTTP API for global breakpoints
            int httpPort = DEBUGGERX_PROXY_PORT + 1; // HTTP API is on proxy port + 1
            String url = String.format("http://localhost:%d/breakpoints", httpPort);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: Failed to query breakpoints from debuggerX proxy (HTTP " + response.statusCode() + ")";
            }

            // Parse JSON response
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            JsonNode breakpointsArray = root.get("breakpoints");

            if (breakpointsArray == null || !breakpointsArray.isArray() || breakpointsArray.size() == 0) {
                return "No breakpoints to clear";
            }

            int count = breakpointsArray.size();

            // Clear each breakpoint by calling jdwp_clear_breakpoint_by_id
            for (JsonNode bp : breakpointsArray) {
                int requestId = bp.get("requestId").asInt();
                jdwp_clear_breakpoint_by_id(requestId);
            }

            return String.format("Successfully cleared %d breakpoint(s) from the JVM.\n\n" +
                "NOTE: This clears breakpoints at the JVM level, which affects ALL connected clients " +
                "(IntelliJ, MCP, etc.). All clients will stop receiving breakpoint events.", count);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Format a JDI Value to a readable string and cache objects
     */
    private String formatValue(Value value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof StringReference) {
            return "\"" + ((StringReference) value).value() + "\"";
        }

        if (value instanceof PrimitiveValue) {
            return value.toString();
        }

        if (value instanceof ObjectReference) {
            ObjectReference obj = (ObjectReference) value;
            // Cache the object for later inspection
            jdiService.cacheObject(obj);
            return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
        }

        if (value instanceof ArrayReference) {
            ArrayReference arr = (ArrayReference) value;
            jdiService.cacheObject(arr);
            return String.format("Array#%d (%s[%d])",
                arr.uniqueID(), arr.type().name(), arr.length());
        }

        return value.toString();
    }

    /**
     * Get the current thread from the breakpoint (via proxy)
     */
    @McpTool(description = "Get the thread ID of the current breakpoint from the proxy")
    public String jdwp_get_current_thread() {
        try {
            Long threadId = jdiService.getCurrentThreadFromProxy();
            if (threadId == null) {
                return "No current breakpoint detected. Trigger a breakpoint first.";
            }

            VirtualMachine vm = jdiService.getVM();
            for (ThreadReference thread : vm.allThreads()) {
                if (thread.uniqueID() == threadId) {
                    return String.format("Current thread: %s (ID=%d, suspended=%s, frames=%d)",
                        thread.name(), threadId, thread.isSuspended(), thread.frameCount());
                }
            }

            return String.format("Thread ID %d found in proxy but not in VM", threadId);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ========================================
    // Watcher Management Tools
    // ========================================

    /**
     * Attach a watcher to a breakpoint to evaluate a single expression when the breakpoint is hit
     */
    @McpTool(description = "Attach a watcher to a breakpoint to evaluate a Java expression when hit. Returns the watcher ID.")
    public String jdwp_attach_watcher(
            @McpToolParam(description = "Breakpoint request ID (from jdwp_list_breakpoints)") int breakpointId,
            @McpToolParam(description = "Descriptive label for this watcher (e.g., 'Trace entity ID', 'Check user name')") String label,
            @McpToolParam(description = "Java expression to evaluate (e.g., 'entity.id', 'user.name', 'items.size()')") String expression) {
        try {
            if (expression == null || expression.trim().isEmpty()) {
                return "Error: No expression provided";
            }

            // Create the watcher
            String watcherId = watcherManager.createWatcher(label, breakpointId, expression.trim());

            return String.format(
                "✓ Watcher attached successfully\n\n" +
                "  Watcher ID: %s\n" +
                "  Label: %s\n" +
                "  Breakpoint: %d\n" +
                "  Expression: %s\n\n" +
                "The watcher will evaluate this expression when breakpoint %d is hit.\n" +
                "Use jdwp_detach_watcher(watcherId) to remove it.",
                watcherId, label, breakpointId, expression.trim(), breakpointId
            );

        } catch (Exception e) {
            log.error("[Watcher] Error attaching watcher", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Detach a watcher by its ID
     */
    @McpTool(description = "Detach a watcher from its breakpoint using the watcher ID")
    public String jdwp_detach_watcher(@McpToolParam(description = "Watcher ID (UUID returned by jdwp_attach_watcher)") String watcherId) {
        try {
            Watcher watcher = watcherManager.getWatcher(watcherId);
            if (watcher == null) {
                return String.format("Error: Watcher '%s' not found.\n\nUse jdwp_list_all_watchers() to see active watchers.", watcherId);
            }

            String label = watcher.getLabel();
            int breakpointId = watcher.getBreakpointId();

            boolean deleted = watcherManager.deleteWatcher(watcherId);
            if (deleted) {
                return String.format("✓ Watcher detached: '%s' (ID: %s, Breakpoint: %d)", label, watcherId, breakpointId);
            } else {
                return "Error: Failed to detach watcher";
            }

        } catch (Exception e) {
            log.error("[Watcher] Error detaching watcher", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * List all watchers attached to a specific breakpoint
     */
    @McpTool(description = "List all watchers attached to a specific breakpoint")
    public String jdwp_list_watchers_for_breakpoint(@McpToolParam(description = "Breakpoint request ID") int breakpointId) {
        try {
            List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);

            if (watchers.isEmpty()) {
                return String.format("No watchers attached to breakpoint %d.\n\n" +
                    "Use jdwp_attach_watcher(%d, \"label\", \"expression\") to attach a watcher.", breakpointId, breakpointId);
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Watchers for breakpoint %d (%d total):\n\n", breakpointId, watchers.size()));

            for (int i = 0; i < watchers.size(); i++) {
                Watcher w = watchers.get(i);
                result.append(String.format("%d. [%s] %s\n", i + 1, w.getId().substring(0, 8), w.getLabel()));
                result.append(String.format("   Expression: %s\n\n", w.getExpression()));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[Watcher] Error listing watchers for breakpoint", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * List all active watchers
     */
    @McpTool(description = "List all active watchers across all breakpoints")
    public String jdwp_list_all_watchers() {
        try {
            List<Watcher> watchers = watcherManager.getAllWatchers();

            if (watchers.isEmpty()) {
                return "No watchers configured.\n\n" +
                    "Use jdwp_attach_watcher(breakpointId, label, expression) to create a watcher.";
            }

            Map<String, Object> stats = watcherManager.getStats();
            StringBuilder result = new StringBuilder();
            result.append(String.format("Active watchers: %d across %d breakpoints\n\n",
                stats.get("totalWatchers"), stats.get("breakpointsWithWatchers")));

            // Group by breakpoint
            Map<Integer, List<Watcher>> grouped = watchers.stream()
                .collect(Collectors.groupingBy(Watcher::getBreakpointId));

            for (Map.Entry<Integer, List<Watcher>> entry : grouped.entrySet()) {
                result.append(String.format("Breakpoint %d (%d watchers):\n", entry.getKey(), entry.getValue().size()));
                for (Watcher w : entry.getValue()) {
                    result.append(String.format("  • [%s] %s\n", w.getId().substring(0, 8), w.getLabel()));
                    result.append(String.format("    Expression: %s\n", w.getExpression()));
                }
                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[Watcher] Error listing all watchers", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Clear all watchers
     */
    @McpTool(description = "Clear all watchers from all breakpoints")
    public String jdwp_clear_all_watchers() {
        try {
            int count = watcherManager.getAllWatchers().size();
            watcherManager.clearAll();
            return String.format("✓ Cleared %d watcher(s)", count);

        } catch (Exception e) {
            log.error("[Watcher] Error clearing watchers", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Evaluate watchers on a suspended thread's stack.
     * Can operate in two scopes:
     * - 'current_frame': (Default & Recommended) Evaluates watchers only for the breakpoint that caused the suspension. Fast and precise.
     * - 'full_stack': Scans every frame of the stack to find any location matching any breakpoint with a watcher. Powerful but slower.
     */
    @McpTool(description = "Evaluate watchers on a suspended thread's stack based on a scope")
    public String jdwp_evaluate_watchers(
            @McpToolParam(description = "Thread unique ID") long threadId,
            @McpToolParam(description = "Evaluation scope: 'current_frame' (default) or 'full_stack'") String scope,
            @McpToolParam(description = "Optional: The specific breakpoint ID that was hit. If provided, evaluation is much faster for 'current_frame' scope") Integer breakpointId) {
        try {
            VirtualMachine vm = jdiService.getVM();

            // Find the thread
            ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadId)
                .findFirst()
                .orElseThrow(() -> new Exception("Thread not found with ID " + threadId));

            if (!thread.isSuspended()) {
                return String.format("[ERROR] Thread %d is NOT suspended\n\n" +
                    "Thread must be stopped at a breakpoint to evaluate watchers.", threadId);
            }

            // CRITICAL: Configure compiler classpath BEFORE any expression evaluation
            // This must be done here (not inside evaluate()) to avoid nested JDI calls
            expressionEvaluator.configureCompilerClasspath(thread);

            if (scope == null || scope.isBlank()) {
                scope = "current_frame";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("=== Watcher Evaluation for Thread %d (Scope: %s) ===\n\n", threadId, scope));
            result.append(String.format("Thread: %s (frames: %d)\n\n", thread.name(), thread.frameCount()));

            int watchersEvaluated;
            if ("full_stack".equalsIgnoreCase(scope)) {
                watchersEvaluated = evaluateWatchersFullStack(thread, result);
            } else {
                watchersEvaluated = evaluateWatchersCurrentFrame(thread, breakpointId, result);
            }

            if (watchersEvaluated == 0) {
                result.append("No watchers found or evaluated for the given scope.\n");
            } else {
                result.append(String.format("Total: Evaluated %d expression(s)\n", watchersEvaluated));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[Watcher] Error evaluating watchers", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Evaluate watchers for the current frame only (frame 0)
     */
    private int evaluateWatchersCurrentFrame(ThreadReference thread, Integer breakpointId, StringBuilder result) throws Exception {
        if (thread.frameCount() == 0) return 0;

        StackFrame frame = thread.frame(0);
        Location location = frame.location();
        int watchersEvaluated = 0;

        // If breakpointId is not provided, we must resolve it from location
        if (breakpointId == null) {
            Map<String, Integer> locationMap = getBreakpointLocationMap();
            String locationKey = location.declaringType().name() + ":" + location.lineNumber();
            breakpointId = locationMap.get(locationKey);
        }

        if (breakpointId == null) {
            result.append("Could not find a matching breakpoint for the current location.\n");
            result.append(String.format("Current location: %s:%d\n", location.declaringType().name(), location.lineNumber()));
            return 0;
        }

        List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
        if (watchers.isEmpty()) {
            return 0;
        }

        result.append(String.format("─── Current Frame #0: %s:%d (Breakpoint ID: %d) ───\n\n",
            location.declaringType().name(), location.lineNumber(), breakpointId));

        for (Watcher watcher : watchers) {
            result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
            try {
                Value value = expressionEvaluator.evaluate(frame, watcher.getExpression());
                result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
                watchersEvaluated++;
            } catch (Exception e) {
                result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
            }
        }
        return watchersEvaluated;
    }

    /**
     * Evaluate watchers for all frames in the stack
     */
    private int evaluateWatchersFullStack(ThreadReference thread, StringBuilder result) throws Exception {
        Map<String, Integer> locationToBreakpointId = getBreakpointLocationMap();
        if (locationToBreakpointId.isEmpty()) {
            result.append("No breakpoints found in debuggerX. Cannot evaluate watchers.\n");
            return 0;
        }

        int watchersEvaluated = 0;
        List<StackFrame> frames = thread.frames();

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            StackFrame frame = frames.get(frameIndex);
            Location location = frame.location();
            String locationKey = location.declaringType().name() + ":" + location.lineNumber();

            Integer breakpointId = locationToBreakpointId.get(locationKey);
            if (breakpointId == null) continue;

            List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
            if (watchers.isEmpty()) continue;

            result.append(String.format("─── Frame #%d: %s:%d (Breakpoint ID: %d) ───\n\n",
                frameIndex, location.declaringType().name(), location.lineNumber(), breakpointId));

            for (Watcher watcher : watchers) {
                result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
                try {
                    Value value = expressionEvaluator.evaluate(frame, watcher.getExpression());
                    result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
                    watchersEvaluated++;
                } catch (Exception e) {
                    result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
                }
            }
        }
        return watchersEvaluated;
    }

    /**
     * Fetch all breakpoints from debuggerX and build a map: "className:lineNumber" -> breakpointId
     */
    private Map<String, Integer> getBreakpointLocationMap() throws Exception {
        Map<String, Integer> map = new java.util.HashMap<>();

        int httpPort = DEBUGGERX_PROXY_PORT + 1;
        String url = String.format("http://localhost:%d/breakpoints", httpPort);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Failed to fetch breakpoints: HTTP " + response.statusCode());
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response.body());
        JsonNode breakpointsArray = root.get("breakpoints");

        if (breakpointsArray != null) {
            for (JsonNode bp : breakpointsArray) {
                int requestId = bp.get("requestId").asInt();
                String className = bp.has("className") ? bp.get("className").asText() : null;
                int lineNumber = bp.has("lineNumber") ? bp.get("lineNumber").asInt() : -1;

                if (className != null && lineNumber > 0) {
                    String key = className + ":" + lineNumber;
                    map.put(key, requestId);
                }
            }
        }

        return map;
    }
}
