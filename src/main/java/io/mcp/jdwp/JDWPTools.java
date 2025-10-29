package io.mcp.jdwp;

import com.sun.jdi.*;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * MCP Tools for JDWP inspection
 */
@Service
public class JDWPTools {

    private final JDIConnectionService jdiService;
    private final DebuggerXManager debuggerXManager;

    public JDWPTools(JDIConnectionService jdiService, DebuggerXManager debuggerXManager) {
        this.jdiService = jdiService;
        this.debuggerXManager = debuggerXManager;
    }

    @McpTool(description = "Connect to a JDWP server on specified host and port")
    public String jdwp_connect(
            @McpToolParam(description = "JDWP server hostname") String host,
            @McpToolParam(description = "JDWP server port") int port) {

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
                "  1. Wrong port (you connected to %d, expected proxy port is typically 55005)\n" +
                "  2. debuggerX crashed after starting\n" +
                "  3. Firewall blocking the connection\n\n" +
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

    @McpTool(description = "Invoke a method on an object (like in a debugger console). Example: invoke toString() or getModel()")
    public String jdwp_invoke_method(
            @McpToolParam(description = "Thread unique ID") long threadId,
            @McpToolParam(description = "Object unique ID") long objectId,
            @McpToolParam(description = "Method name to invoke (e.g. 'toString', 'getModel')") String methodName) {
        try {
            return jdiService.invokeMethod(threadId, objectId, methodName, null, null);
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
}
