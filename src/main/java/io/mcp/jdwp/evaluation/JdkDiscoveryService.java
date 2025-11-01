package io.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers a local JDK installation matching the target JVM's Java version.
 * This is essential for the Eclipse JDT compiler to resolve JDK system classes.
 */
@Slf4j
public class JdkDiscoveryService {

    private final VirtualMachine vm;

    public JdkDiscoveryService(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Discovers a local JDK matching the target JVM's version.
     *
     * @param suspendedThread Thread suspended at a breakpoint
     * @return Path to the local JDK home directory
     * @throws JdkNotFoundException if no matching JDK is found
     */
    public String discoverMatchingJdk(ThreadReference suspendedThread) throws JdkNotFoundException {
        try {
            // 1. Get target JVM version
            String targetVersion = getTargetJavaVersion(suspendedThread);
            String targetHome = getTargetJavaHome(suspendedThread);

            log.info("[JDK Discovery] Target JVM: Java {} at {}", targetVersion, targetHome);

            // 2. Extract major version (e.g., "11" from "11.0.21")
            int targetMajorVersion = extractMajorVersion(targetVersion);

            log.info("[JDK Discovery] Looking for Java {} JDK on MCP server...", targetMajorVersion);

            // 3. Search for matching JDK
            String localJdkPath = findLocalJdk(targetMajorVersion, targetHome);

            if (localJdkPath != null) {
                log.info("[JDK Discovery] ✓ Found matching JDK: {}", localJdkPath);
                return localJdkPath;
            }

            // 4. Not found - throw explicit error
            String errorMessage = String.format(
                "No local JDK installation found for Java %d.\n\n" +
                "The target JVM is running Java %s, but the MCP server cannot find a matching JDK.\n\n" +
                "To fix this:\n" +
                "  1. Install a Java %d JDK on the MCP server\n" +
                "  2. Common locations checked:\n" +
                "     - C:\\Program Files\\Eclipse Adoptium\\jdk-%d.*\n" +
                "     - C:\\Program Files\\Java\\jdk-%d.*\n" +
                "     - C:\\Program Files\\OpenJDK\\jdk-%d.*\n" +
                "     - /usr/lib/jvm/java-%d-openjdk*\n" +
                "     - /usr/lib/jvm/jdk-%d*\n\n" +
                "Expression evaluation requires access to JDK system classes.",
                targetMajorVersion, targetVersion, targetMajorVersion,
                targetMajorVersion, targetMajorVersion, targetMajorVersion,
                targetMajorVersion, targetMajorVersion
            );

            throw new JdkNotFoundException(errorMessage);

        } catch (JdkNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new JdkNotFoundException("Failed to discover JDK: " + e.getMessage(), e);
        }
    }

    private String getTargetJavaVersion(ThreadReference suspendedThread) throws Exception {
        ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
        Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
        StringReference versionArg = vm.mirrorOf("java.version");

        Value result = systemClass.invokeMethod(
            suspendedThread,
            getPropertyMethod,
            Collections.singletonList(versionArg),
            ClassType.INVOKE_SINGLE_THREADED
        );

        return ((StringReference) result).value();
    }

    private String getTargetJavaHome(ThreadReference suspendedThread) throws Exception {
        ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
        Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
        StringReference homeArg = vm.mirrorOf("java.home");

        Value result = systemClass.invokeMethod(
            suspendedThread,
            getPropertyMethod,
            Collections.singletonList(homeArg),
            ClassType.INVOKE_SINGLE_THREADED
        );

        return ((StringReference) result).value();
    }

    private int extractMajorVersion(String version) {
        // Handle both "1.8.0_xxx" (Java 8) and "11.0.21" (Java 9+) formats
        if (version.startsWith("1.8")) {
            return 8;
        }

        // For Java 9+, major version is the first number
        String[] parts = version.split("\\.");
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            log.warn("[JDK Discovery] Could not parse version: {}", version);
            return 0;
        }
    }

    private String findLocalJdk(int majorVersion, String targetHome) {
        // Strategy 1: Check if target JVM's java.home is accessible locally
        if (isValidJdkHome(targetHome)) {
            log.debug("[JDK Discovery] Target JVM home is accessible locally: {}", targetHome);
            return targetHome;
        }

        // Strategy 2: Search common JDK installation directories
        List<String> searchPaths = getCommonJdkPaths(majorVersion);

        for (String path : searchPaths) {
            if (isValidJdkHome(path)) {
                log.debug("[JDK Discovery] Found JDK at: {}", path);
                return path;
            }
        }

        // Strategy 3: Search for any JDK with matching major version
        String foundJdk = searchDirectoriesForJdk(majorVersion);
        if (foundJdk != null) {
            return foundJdk;
        }

        return null;
    }

    private List<String> getCommonJdkPaths(int majorVersion) {
        List<String> paths = new ArrayList<>();

        // Windows paths
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            paths.add(String.format("C:\\Program Files\\Eclipse Adoptium\\jdk-%d.0.21.9-hotspot", majorVersion));
            paths.add(String.format("C:\\Program Files\\Eclipse Adoptium\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\Java\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\OpenJDK\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\Zulu\\zulu-%d", majorVersion));
        } else {
            // Linux/Unix paths
            paths.add(String.format("/usr/lib/jvm/java-%d-openjdk", majorVersion));
            paths.add(String.format("/usr/lib/jvm/java-%d-openjdk-amd64", majorVersion));
            paths.add(String.format("/usr/lib/jvm/jdk-%d", majorVersion));
            paths.add(String.format("/opt/jdk-%d", majorVersion));
        }

        return paths;
    }

    private String searchDirectoriesForJdk(int majorVersion) {
        List<Path> searchDirs = new ArrayList<>();

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            searchDirs.add(Paths.get("C:\\Program Files\\Eclipse Adoptium"));
            searchDirs.add(Paths.get("C:\\Program Files\\Java"));
            searchDirs.add(Paths.get("C:\\Program Files\\OpenJDK"));
        } else {
            searchDirs.add(Paths.get("/usr/lib/jvm"));
            searchDirs.add(Paths.get("/opt"));
        }

        for (Path searchDir : searchDirs) {
            if (!Files.exists(searchDir)) {
                continue;
            }

            try (Stream<Path> paths = Files.list(searchDir)) {
                Optional<Path> matchingJdk = paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("jdk") ||
                                 p.getFileName().toString().contains("java"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.contains("-" + majorVersion) ||
                               name.contains("_" + majorVersion) ||
                               name.matches(".*jdk" + majorVersion + ".*");
                    })
                    .filter(p -> isValidJdkHome(p.toString()))
                    .findFirst();

                if (matchingJdk.isPresent()) {
                    return matchingJdk.get().toString();
                }
            } catch (Exception e) {
                log.debug("[JDK Discovery] Error searching {}: {}", searchDir, e.getMessage());
            }
        }

        return null;
    }

    private boolean isValidJdkHome(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }

        // Check for JDK markers
        // Java 9+: jmods directory or lib/jrt-fs.jar
        if (new File(dir, "jmods").exists() || new File(dir, "lib/jrt-fs.jar").exists()) {
            return true;
        }

        // Java 8: lib/rt.jar
        if (new File(dir, "lib/rt.jar").exists()) {
            return true;
        }

        // Also check in jre subdirectory (some JDK distributions)
        if (new File(dir, "jre/lib/rt.jar").exists()) {
            return true;
        }

        return false;
    }

    /**
     * Exception thrown when no matching JDK is found.
     */
    public static class JdkNotFoundException extends Exception {
        public JdkNotFoundException(String message) {
            super(message);
        }

        public JdkNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
