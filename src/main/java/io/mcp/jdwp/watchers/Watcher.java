package io.mcp.jdwp.watchers;

import java.util.UUID;

/**
 * Represents a watcher attached to a breakpoint for expression evaluation.
 * Watchers are identified by a unique UUID and can have a descriptive label.
 * Each watcher evaluates a single Java expression.
 */
public class Watcher {
    private final String id;
    private final String label;
    private final int breakpointId;
    private final String expression;

    /**
     * Creates a new watcher with a generated UUID.
     *
     * @param label User-friendly description (e.g., "Trace entity ID")
     * @param breakpointId JDWP breakpoint request ID this watcher is attached to
     * @param expression Java expression to evaluate (e.g., "entity.id", "users.stream().count()")
     */
    public Watcher(String label, int breakpointId, String expression) {
        this.id = UUID.randomUUID().toString();
        this.label = label;
        this.breakpointId = breakpointId;
        this.expression = expression;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getBreakpointId() {
        return breakpointId;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return String.format("Watcher{id='%s', label='%s', breakpointId=%d, expression='%s'}",
            id, label, breakpointId, expression);
    }
}
