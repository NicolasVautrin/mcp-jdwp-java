package io.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleton service maintaining persistent JDI connection to remote JDWP server
 */
@Slf4j
@Service
@Scope("singleton")
public class JDIConnectionService {

    private VirtualMachine vm = null;
    private String lastHost = null;
    private int lastPort = 0;

    // Cache pour stocker les ObjectReferences rencontrées
    private final Map<Long, ObjectReference> objectCache = new java.util.concurrent.ConcurrentHashMap<>();

    // Cached classpath from target JVM (discovered once)
    private volatile String cachedClasspath = null;

    // Discovered local JDK path matching target JVM version
    private volatile String discoveredJdkPath = null;

    /**
     * Check if VM connection is alive
     */
    private boolean isVMAlive() {
        if (vm == null) {
            return false;
        }
        try {
            // Try to call a simple method to check if connection is alive
            vm.name();
            return true;
        } catch (Exception e) {
            // Connection is dead
            vm = null;
            return false;
        }
    }

    /**
     * Connect to JDWP server (with auto-reconnect if connection is dead)
     */
    public synchronized String connect(String host, int port) throws Exception {
        // Check if already connected and alive
        if (vm != null && isVMAlive()) {
            return "Already connected to " + vm.name();
        }

        // Clean up dead connection if any
        vm = null;

        // Find SocketAttachingConnector
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector connector = null;

        for (AttachingConnector ac : vmm.attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(ac.name())) {
                connector = ac;
                break;
            }
        }

        if (connector == null) {
            throw new RuntimeException("SocketAttach connector not found");
        }

        // Set connection arguments
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));

        // Attach
        vm = connector.attach(args);
        lastHost = host;
        lastPort = port;

        return String.format("Connected to %s (version %s)", vm.name(), vm.version());
    }

    /**
     * Ensure connection is alive, reconnect if needed
     */
    private synchronized void ensureConnected() throws Exception {
        if (vm == null || !isVMAlive()) {
            if (lastHost != null && lastPort != 0) {
                // Try to reconnect
                connect(lastHost, lastPort);
            } else {
                throw new Exception("Not connected to JDWP server. Use jdwp_connect first.");
            }
        }
    }

    /**
     * Disconnect from JDWP server (without sending Dispose command to avoid killing debuggerX)
     */
    public synchronized String disconnect() {
        if (vm == null) {
            return "Not connected";
        }

        // DON'T call vm.dispose() - it sends a Dispose command that kills debuggerX connection
        // Just clean up our local reference
        vm = null;
        return "Disconnected";
    }

    /**
     * Get the connected VirtualMachine (with auto-reconnect)
     */
    public synchronized VirtualMachine getVM() throws Exception {
        ensureConnected();
        return vm;
    }

    /**
     * Check if connected
     */
    public synchronized boolean isConnected() {
        return vm != null;
    }

    /**
     * Store an ObjectReference in the cache
     */
    public void cacheObject(ObjectReference obj) {
        if (obj != null) {
            objectCache.put(obj.uniqueID(), obj);
        }
    }

    /**
     * Get fields of an object by its ID, or elements if it's an array
     */
    public synchronized String getObjectFields(long objectId) throws Exception {
        ensureConnected();

        ObjectReference obj = objectCache.get(objectId);
        if (obj == null) {
            return String.format("[ERROR] Object #%d not found in cache\n\n" +
                "This object was not previously discovered.\n" +
                "Use jdwp_get_locals() to discover objects in the current scope.",
                objectId);
        }

        try {
            // Check if it's an array
            if (obj instanceof ArrayReference) {
                return getArrayElements((ArrayReference) obj, objectId);
            }

            ReferenceType refType = obj.referenceType();
            String typeName = refType.name();

            // Check for common Java collections and provide smart views
            if (typeName.startsWith("java.util.") && isCollection(typeName)) {
                return getCollectionView(obj, objectId, typeName);
            }

            // Regular object - get fields
            StringBuilder result = new StringBuilder();
            result.append(String.format("Object #%d (%s):\n\n", objectId, refType.name()));

            // Get all fields (including inherited)
            java.util.List<Field> fields = refType.allFields();

            for (Field field : fields) {
                Value value = obj.getValue(field);
                String valueStr = formatFieldValue(value);

                result.append(String.format("%s %s = %s\n",
                    field.typeName(),
                    field.name(),
                    valueStr));
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Check if type is a known collection
     */
    private boolean isCollection(String typeName) {
        return typeName.startsWith("java.util.ArrayList") ||
               typeName.startsWith("java.util.LinkedList") ||
               typeName.startsWith("java.util.HashMap") ||
               typeName.startsWith("java.util.LinkedHashMap") ||
               typeName.startsWith("java.util.HashSet") ||
               typeName.startsWith("java.util.TreeSet") ||
               typeName.startsWith("java.util.TreeMap");
    }

    /**
     * Provide smart view for collections
     */
    private String getCollectionView(ObjectReference obj, long objectId, String typeName) {
        StringBuilder result = new StringBuilder();
        result.append(String.format("Object #%d (%s):\n\n", objectId, typeName));

        try {
            // Get size
            Field sizeField = obj.referenceType().fieldByName("size");
            if (sizeField != null) {
                Value sizeValue = obj.getValue(sizeField);
                int size = ((IntegerValue) sizeValue).value();
                result.append(String.format("Size: %d\n\n", size));

                // For List types
                if (typeName.contains("List")) {
                    result.append(getListElements(obj, size));
                }
                // For Map types
                else if (typeName.contains("Map")) {
                    result.append(getMapEntries(obj, size));
                }
                // For Set types
                else if (typeName.contains("Set")) {
                    result.append(getSetElements(obj, size));
                }
            }

            result.append("\n--- Internal fields ---\n\n");
            // Also show internal fields
            for (Field field : obj.referenceType().allFields()) {
                Value value = obj.getValue(field);
                String valueStr = formatFieldValue(value);
                result.append(String.format("%s %s = %s\n",
                    field.typeName(), field.name(), valueStr));
            }

        } catch (Exception e) {
            result.append("Error inspecting collection: ").append(e.getMessage());
        }

        return result.toString();
    }

    /**
     * Get elements from a List (ArrayList, LinkedList)
     */
    private String getListElements(ObjectReference list, int size) {
        StringBuilder result = new StringBuilder();
        result.append("Elements:\n");

        try {
            // ArrayList uses elementData array
            Field elementDataField = list.referenceType().fieldByName("elementData");
            if (elementDataField != null) {
                ArrayReference array = (ArrayReference) list.getValue(elementDataField);
                if (array != null) {
                    int limit = Math.min(size, 50); // Limit to 50 elements
                    for (int i = 0; i < limit; i++) {
                        Value value = array.getValue(i);
                        result.append(String.format("  [%d] = %s\n", i, formatFieldValue(value)));
                    }
                    if (size > 50) {
                        result.append(String.format("  ... (%d more elements)\n", size - 50));
                    }
                }
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    /**
     * Get entries from a Map (HashMap, LinkedHashMap, TreeMap)
     */
    private String getMapEntries(ObjectReference map, int size) {
        StringBuilder result = new StringBuilder();
        result.append("Entries:\n");

        try {
            // For LinkedHashMap, use head/tail entry chain
            Field headField = map.referenceType().fieldByName("head");
            if (headField != null) {
                ObjectReference entry = (ObjectReference) map.getValue(headField);
                int count = 0;
                int limit = 50;

                while (entry != null && count < limit) {
                    // Get key and value from entry
                    Field keyField = entry.referenceType().fieldByName("key");
                    Field valueField = entry.referenceType().fieldByName("value");

                    if (keyField != null && valueField != null) {
                        Value key = entry.getValue(keyField);
                        Value value = entry.getValue(valueField);
                        result.append(String.format("  %s = %s\n",
                            formatFieldValue(key), formatFieldValue(value)));
                    }

                    // Move to next entry
                    Field nextField = entry.referenceType().fieldByName("after");
                    if (nextField == null) {
                        nextField = entry.referenceType().fieldByName("next");
                    }
                    if (nextField != null) {
                        entry = (ObjectReference) entry.getValue(nextField);
                    } else {
                        break;
                    }
                    count++;
                }

                if (size > limit) {
                    result.append(String.format("  ... (%d more entries)\n", size - limit));
                }
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    /**
     * Get elements from a Set (HashSet, TreeSet)
     */
    private String getSetElements(ObjectReference set, int size) {
        StringBuilder result = new StringBuilder();
        result.append("Elements:\n");

        try {
            // HashSet uses a HashMap internally (field: map)
            Field mapField = set.referenceType().fieldByName("map");
            if (mapField != null) {
                ObjectReference map = (ObjectReference) set.getValue(mapField);
                if (map != null) {
                    // Extract keys from the map (values are dummy PRESENT objects)
                    result.append(getMapEntries(map, size));
                }
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    /**
     * Get elements of an array
     */
    private String getArrayElements(ArrayReference array, long arrayId) {
        StringBuilder result = new StringBuilder();
        int length = array.length();
        String typeName = array.type().name();

        result.append(String.format("Array #%d (%s) - %d elements:\n\n", arrayId, typeName, length));

        // Limit to first 100 elements for performance
        int limit = Math.min(length, 100);

        for (int i = 0; i < limit; i++) {
            Value value = array.getValue(i);
            String valueStr = formatFieldValue(value);
            result.append(String.format("[%d] = %s\n", i, valueStr));
        }

        if (length > 100) {
            result.append(String.format("\n... (%d more elements)\n", length - 100));
        }

        return result.toString();
    }

    /**
     * Format a field value for display and cache ObjectReferences
     */
    private String formatFieldValue(Value value) {
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
            cacheObject(obj); // Store in cache for later inspection
            return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
        }

        if (value instanceof ArrayReference) {
            ArrayReference arr = (ArrayReference) value;
            cacheObject(arr);
            return String.format("Array#%d (%s[%d])",
                arr.uniqueID(), arr.type().name(), arr.length());
        }

        return value.toString();
    }

    /**
     * TODO: Implement event history tracking
     */
    public java.util.List<String> getRecentEvents(int count) {
        return new java.util.ArrayList<>();
    }

    /**
     * TODO: Implement event history clearing
     */
    public void clearEvents() {
        // Stub implementation
    }

    /**
     * TODO: Implement exception monitoring configuration
     */
    public String configureExceptionMonitoring(Boolean captureCaught, String includePackages, String excludeClasses) {
        return "Exception monitoring not yet implemented";
    }

    /**
     * TODO: Implement exception config retrieval
     */
    public String getExceptionConfig() {
        return "Exception monitoring not yet implemented";
    }

    /**
     * Discover the full classpath of the target JVM by exploring its classloader hierarchy.
     * Results are cached after first call.
     *
     * This method explores the context classloader hierarchy to find all dynamically loaded JARs,
     * which is essential for Tomcat/container applications where most JARs are not in java.class.path.
     *
     * MUST be called with a thread that is already suspended at a breakpoint.
     * This ensures the thread is in a compatible state for INVOKE_SINGLE_THREADED.
     *
     * @param suspendedThread A thread already suspended at a breakpoint (REQUIRED)
     * @return Classpath string (colon or semicolon separated depending on OS), or null if unavailable
     */
    public String discoverClasspath(ThreadReference suspendedThread) {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }

        if (suspendedThread == null) {
            log.error("[JDI] discoverClasspath() requires a suspended thread from a breakpoint");
            return null;
        }

        try {
            ensureConnected();

            log.info("[JDI] Discovering full classpath using breakpoint thread '{}'", suspendedThread.name());

            // Use ClasspathDiscoverer to explore classloader hierarchy
            io.mcp.jdwp.evaluation.ClasspathDiscoverer discoverer =
                new io.mcp.jdwp.evaluation.ClasspathDiscoverer(vm);

            // Discover both JDK path and application classpath
            io.mcp.jdwp.evaluation.ClasspathDiscoverer.DiscoveryResult result =
                discoverer.discoverFullClasspath(suspendedThread);

            // Store discovered JDK path for later use by JDT compiler
            discoveredJdkPath = result.getLocalJdkPath();
            log.info("[JDI] Using local JDK: {}", discoveredJdkPath);

            Set<String> classpathEntries = result.getApplicationClasspath();

            if (classpathEntries.isEmpty()) {
                log.warn("[JDI] No classpath entries discovered");
                return null;
            }

            // Determine separator based on first entry (Windows uses backslash, Unix forward slash)
            String separator = classpathEntries.stream()
                .findFirst()
                .map(path -> path.contains("\\") ? ";" : ":")
                .orElse(System.getProperty("path.separator"));

            // Join all entries into a single classpath string
            cachedClasspath = String.join(separator, classpathEntries);

            log.info("[JDI] Full classpath discovered ({} entries)", classpathEntries.size());

            return cachedClasspath;

        } catch (io.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException e) {
            // Critical error: No matching JDK found locally
            log.error("[JDI] {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("[JDI] Failed to discover classpath", e);
            return null;
        }
    }

    /**
     * Get the discovered local JDK path matching the target JVM version.
     * This path is discovered during classpath discovery and can be used by the JDT compiler.
     *
     * @return Local JDK path, or null if not yet discovered
     */
    public String getDiscoveredJdkPath() {
        return discoveredJdkPath;
    }

    /**
     * Query the debuggerX proxy to get the current breakpoint thread ID.
     * Makes HTTP GET request to http://localhost:55006/current-thread
     *
     * @return threadId if a breakpoint is active, null otherwise
     */
    public Long getCurrentThreadFromProxy() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:55006/current-thread"))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // If 404, no breakpoint event captured yet
            if (response.statusCode() == 404) {
                return null;
            }

            // If not 200, something went wrong
            if (response.statusCode() != 200) {
                System.err.println("[JDI] Failed to get current thread from proxy: HTTP " + response.statusCode() + " - " + response.body());
                return null;
            }

            // Parse JSON manually to extract threadId (simple regex-based parsing)
            String body = response.body();
            Pattern pattern = Pattern.compile("\"threadId\"\\s*:\\s*(\\d+)");
            Matcher matcher = pattern.matcher(body);

            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }

            System.err.println("[JDI] Failed to parse threadId from proxy response: " + body);
            return null;

        } catch (Exception e) {
            System.err.println("[JDI] Error querying proxy for current thread: " + e.getMessage());
            return null;
        }
    }
}
