package io.mcp.jdwp.evaluation.exceptions;

/**
 * Custom exception for errors occurring during JDI expression evaluation.
 */
public class JdiEvaluationException extends Exception {

    public JdiEvaluationException(String message) {
        super(message);
    }

    public JdiEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
