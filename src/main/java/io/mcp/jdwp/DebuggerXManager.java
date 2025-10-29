package io.mcp.jdwp;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Manager for debuggerX proxy - automatically starts and manages the proxy
 */
@Service
public class DebuggerXManager {

    // Ports configurables via System properties (-DJVM_JDWP_PORT=... -DDEBUGGERX_PROXY_PORT=...)
    private static final int JVM_JDWP_PORT = Integer.parseInt(
        System.getProperty("JVM_JDWP_PORT", "61959")
    );
    private static final int DEBUGGERX_PROXY_PORT = Integer.parseInt(
        System.getProperty("DEBUGGERX_PROXY_PORT", "55005")
    );
    private static final String APP_NAME = "debuggerX-proxy";

    /**
     * Check if a port is listening
     */
    private boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if debuggerX is running
     */
    public boolean isDebuggerXRunning() {
        return isPortListening("localhost", DEBUGGERX_PROXY_PORT);
    }

    /**
     * Check if JVM is running with JDWP
     */
    public boolean isJvmJdwpRunning() {
        return isPortListening("localhost", JVM_JDWP_PORT);
    }

    /**
     * Get path to debuggerX lib directory (contains debuggerX.jar)
     */
    private Path getDebuggerXLibPath() {
        StringBuilder debugLog = new StringBuilder();
        debugLog.append("[DEBUG] Searching for debuggerX.jar...\n\n");

        // Strategy 1: Check for HOME system property
        try {
            String home = System.getProperty("HOME");
            debugLog.append("Strategy 1 - System property HOME: ").append(home).append("\n");

            if (home != null) {
                Path libDir = Paths.get(home, "lib");
                Path jarFile = libDir.resolve("debuggerX.jar");
                debugLog.append("  Checking: ").append(jarFile).append("\n");

                if (Files.exists(jarFile)) {
                    System.err.println("[SUCCESS] Found debuggerX.jar at: " + jarFile);
                    return libDir;
                }
                debugLog.append("  [NOT FOUND]\n\n");
            }
        } catch (Exception e) {
            debugLog.append("  [ERROR] ").append(e.getMessage()).append("\n\n");
        }

        // Strategy 2: Try to get path from running JAR
        try {
            String jarPath = DebuggerXManager.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();

            debugLog.append("Strategy 2 - JAR location:\n");
            debugLog.append("  Raw path: ").append(jarPath).append("\n");

            // Remove leading "/" on Windows (e.g., "/C:/Users/..." -> "C:/Users/...")
            if (jarPath.startsWith("/") && jarPath.contains(":")) {
                jarPath = jarPath.substring(1);
            }
            debugLog.append("  Normalized: ").append(jarPath).append("\n");

            // Get server root directory (go up from build/libs to root)
            Path jarFile = Paths.get(jarPath);
            Path parent1 = jarFile.getParent();
            Path parent2 = parent1 != null ? parent1.getParent() : null;
            Path serverRoot = parent2 != null ? parent2.getParent() : null;

            debugLog.append("  Parent: ").append(parent1).append("\n");
            debugLog.append("  Parent.parent: ").append(parent2).append("\n");
            debugLog.append("  Parent.parent.parent (serverRoot): ").append(serverRoot).append("\n");

            if (serverRoot != null) {
                Path libDir = serverRoot.resolve("lib");
                Path debuggerXJar = libDir.resolve("debuggerX.jar");
                debugLog.append("  Checking: ").append(debuggerXJar).append("\n");

                // Verify lib/debuggerX.jar exists
                if (Files.exists(debuggerXJar)) {
                    System.err.println("[SUCCESS] Found debuggerX.jar at: " + debuggerXJar);
                    return libDir;
                }
                debugLog.append("  [NOT FOUND]\n\n");
            }

        } catch (Exception e) {
            debugLog.append("  [ERROR] ").append(e.getMessage()).append("\n\n");
        }

        // Strategy 3: Try current working directory
        try {
            Path cwd = Paths.get(System.getProperty("user.dir"));
            debugLog.append("Strategy 3 - Working directory:\n");
            debugLog.append("  CWD: ").append(cwd).append("\n");

            Path libDir = cwd.resolve("lib");
            Path debuggerXJar = libDir.resolve("debuggerX.jar");
            debugLog.append("  Checking: ").append(debuggerXJar).append("\n");

            if (Files.exists(debuggerXJar)) {
                System.err.println("[SUCCESS] Found debuggerX.jar at: " + debuggerXJar);
                return libDir;
            }
            debugLog.append("  [NOT FOUND]\n\n");
        } catch (Exception e) {
            debugLog.append("  [ERROR] ").append(e.getMessage()).append("\n\n");
        }

        // Not found - print debug log
        System.err.println(debugLog.toString());
        return null;
    }

    /**
     * Start debuggerX proxy
     */
    private String startDebuggerX() {
        Path libPath = getDebuggerXLibPath();

        if (libPath == null) {
            return "[ERROR] Cannot find debuggerX.jar\n\n" +
                   "Solution: Add -DHOME to .mcp.json:\n\n" +
                   "\"args\": [\n" +
                   "  \"-DHOME=C:/Users/nicolasv/MCP_servers/mcp-jdwp-java\",\n" +
                   "  \"-jar\",\n" +
                   "  \"C:/Users/.../mcp-jdwp-java-1.0.0.jar\"\n" +
                   "]";
        }

        try {
            // Get the parent directory (server root)
            File workingDir = libPath.getParent().toFile();

            System.err.println("[INFO] Starting debuggerX from: " + workingDir);
            System.err.println("[INFO] JVM Port: " + JVM_JDWP_PORT + " -> Proxy Port: " + DEBUGGERX_PROXY_PORT);

            // Start debuggerX with identifiable process name
            ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Dapp.name=" + APP_NAME,
                "-DjvmServerHost=localhost",
                "-DjvmServerPort=" + String.valueOf(JVM_JDWP_PORT),
                "-DdebuggerProxyPort=" + String.valueOf(DEBUGGERX_PROXY_PORT),
                "-jar", "lib\\debuggerX.jar"
            );
            pb.directory(workingDir);

            // Redirect output to parent process (visible in Claude Code logs)
            pb.inheritIO();

            Process process = pb.start();

            // Wait for debuggerX to start
            System.err.println("[INFO] Waiting for debuggerX to start...");
            for (int i = 0; i < 15; i++) {
                Thread.sleep(200);
                if (isDebuggerXRunning()) {
                    System.err.println("[SUCCESS] debuggerX is running on port " + DEBUGGERX_PROXY_PORT);
                    return null; // Success
                }
            }

            // Timeout
            return "[ERROR] debuggerX failed to start within 3 seconds\n\n" +
                   "Check if port " + DEBUGGERX_PROXY_PORT + " is already in use:\n" +
                   "  netstat -ano | findstr :" + DEBUGGERX_PROXY_PORT;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[ERROR] Interrupted while starting debuggerX: " + e.getMessage();
        } catch (Exception e) {
            return "[ERROR] Failed to start debuggerX: " + e.getMessage();
        }
    }

    /**
     * Ensure debuggerX is running - start it if needed
     * Returns null if successful, error message otherwise
     */
    public String ensureDebuggerXRunning() {
        // 1. Check if JVM JDWP port is available
        if (!isJvmJdwpRunning()) {
            return String.format(
                "[ERROR] No JVM found listening on port %d\n\n" +
                "debuggerX requires a JVM to be running with JDWP enabled.\n\n" +
                "TO FIX:\n" +
                "  1. Start your application (Tomcat/Spring Boot) in IntelliJ with RUN mode\n" +
                "  2. Add these VM Options to your run configuration:\n" +
                "     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%d\n\n" +
                "  3. Verify the JVM is listening:\n" +
                "     netstat -ano | findstr :%d\n\n" +
                "  4. Then try jdwp_connect again",
                JVM_JDWP_PORT, JVM_JDWP_PORT, JVM_JDWP_PORT
            );
        }

        // 2. Check if debuggerX is already running
        if (isDebuggerXRunning()) {
            return null; // Already running, all good
        }

        // 3. Try to start debuggerX
        return startDebuggerX();
    }

    /**
     * Get status information
     */
    public String getStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== debuggerX Status ===\n\n");

        status.append(String.format("JVM JDWP Port (%d): %s\n",
            JVM_JDWP_PORT,
            isJvmJdwpRunning() ? "[OK] LISTENING" : "[X] NOT LISTENING"
        ));

        status.append(String.format("Proxy Port (%d): %s\n",
            DEBUGGERX_PROXY_PORT,
            isDebuggerXRunning() ? "[OK] LISTENING" : "[X] NOT LISTENING"
        ));

        return status.toString();
    }
}
