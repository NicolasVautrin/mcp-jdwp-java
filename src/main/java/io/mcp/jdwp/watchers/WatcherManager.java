package io.mcp.jdwp.watchers;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages watchers attached to JDWP breakpoints.
 * Watchers are stored MCP-side (not in the target JVM) and evaluated using JdiExpressionEvaluator.
 *
 * This service maintains two indexes for efficient lookups:
 * - By watcher ID (for direct access)
 * - By breakpoint ID (for event-driven evaluation)
 */
@Service
public class WatcherManager {

    // Primary index: watcherId -> Watcher
    private final Map<String, Watcher> watchersById = new ConcurrentHashMap<>();

    // Secondary index: breakpointId -> List<Watcher>
    private final Map<Integer, List<Watcher>> watchersByBreakpoint = new ConcurrentHashMap<>();

    /**
     * Creates and registers a new watcher.
     *
     * @param label User-friendly description
     * @param breakpointId JDWP breakpoint request ID
     * @param expression Java expression to evaluate
     * @return The unique ID of the created watcher
     */
    public synchronized String createWatcher(String label, int breakpointId, String expression) {
        Watcher watcher = new Watcher(label, breakpointId, expression);

        // Add to primary index
        watchersById.put(watcher.getId(), watcher);

        // Add to secondary index
        watchersByBreakpoint
            .computeIfAbsent(breakpointId, k -> new ArrayList<>())
            .add(watcher);

        return watcher.getId();
    }

    /**
     * Retrieves a watcher by its unique ID.
     *
     * @param watcherId UUID of the watcher
     * @return The watcher, or null if not found
     */
    public Watcher getWatcher(String watcherId) {
        return watchersById.get(watcherId);
    }

    /**
     * Retrieves all watchers attached to a specific breakpoint.
     *
     * @param breakpointId JDWP breakpoint request ID
     * @return List of watchers (empty if none attached)
     */
    public List<Watcher> getWatchersForBreakpoint(int breakpointId) {
        return watchersByBreakpoint.getOrDefault(breakpointId, Collections.emptyList());
    }

    /**
     * Retrieves all watchers.
     *
     * @return List of all registered watchers
     */
    public List<Watcher> getAllWatchers() {
        return new ArrayList<>(watchersById.values());
    }

    /**
     * Deletes a watcher by its unique ID.
     *
     * @param watcherId UUID of the watcher to delete
     * @return true if deleted, false if not found
     */
    public synchronized boolean deleteWatcher(String watcherId) {
        Watcher watcher = watchersById.remove(watcherId);
        if (watcher == null) {
            return false;
        }

        // Remove from secondary index
        List<Watcher> breakpointWatchers = watchersByBreakpoint.get(watcher.getBreakpointId());
        if (breakpointWatchers != null) {
            breakpointWatchers.removeIf(w -> w.getId().equals(watcherId));
            if (breakpointWatchers.isEmpty()) {
                watchersByBreakpoint.remove(watcher.getBreakpointId());
            }
        }

        return true;
    }

    /**
     * Deletes all watchers attached to a specific breakpoint.
     *
     * @param breakpointId JDWP breakpoint request ID
     * @return Number of watchers deleted
     */
    public synchronized int deleteWatchersForBreakpoint(int breakpointId) {
        List<Watcher> watchers = watchersByBreakpoint.remove(breakpointId);
        if (watchers == null || watchers.isEmpty()) {
            return 0;
        }

        // Remove from primary index
        watchers.forEach(w -> watchersById.remove(w.getId()));

        return watchers.size();
    }

    /**
     * Clears all watchers.
     */
    public synchronized void clearAll() {
        watchersById.clear();
        watchersByBreakpoint.clear();
    }

    /**
     * Gets statistics about registered watchers.
     *
     * @return Map with statistics (totalWatchers, breakpointsWithWatchers, etc.)
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalWatchers", watchersById.size());
        stats.put("breakpointsWithWatchers", watchersByBreakpoint.size());
        stats.put("avgWatchersPerBreakpoint",
            watchersByBreakpoint.isEmpty() ? 0.0 :
            (double) watchersById.size() / watchersByBreakpoint.size());
        return stats;
    }
}
