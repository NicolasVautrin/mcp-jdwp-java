package io.mcp.jdwp;

import com.sun.jdi.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A lightweight expression evaluator for JDI.
 *
 * <p>This evaluator supports simple dot-notation expressions to navigate object graphs,
 * accessing fields and invoking no-argument getter methods (e.g., "entity.id", "request.getFileName()").
 *
 * <p>It is designed to be simple, dependency-free, and performant within the constraints of JDI.
 */
public class JdiExpressionEvaluator {

    private static final Pattern DOT_SPLIT_PATTERN = Pattern.compile("\\.");

    /**
     * Evaluates an expression within the context of a given stack frame.
     *
     * @param thread     The thread reference, required for method invocations.
     * @param frame      The stack frame providing the context (local variables, 'this').
     * @param expression The dot-notation expression to evaluate (e.g., "entity.id").
     * @return An Optional containing the resulting JDI {@link Value}, or an empty Optional if
     *         the evaluation fails (e.g., null pointer, field not found).
     */
    public Optional<Value> evaluate(ThreadReference thread, StackFrame frame, String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return Optional.empty();
        }

        String[] parts = DOT_SPLIT_PATTERN.split(expression);
        String startVarName = parts[0];

        Optional<Value> currentValueOpt = getStartingValue(frame, startVarName);
        if (!currentValueOpt.isPresent()) {
            return Optional.empty();
        }

        Value currentValue = currentValueOpt.get();

        // Navigate the rest of the path
        for (int i = 1; i < parts.length; i++) {
            if (currentValue == null) {
                return Optional.empty(); // Null pointer in the path
            }
            if (!(currentValue instanceof ObjectReference)) {
                // Cannot navigate into a primitive or null value
                return Optional.empty();
            }

            ObjectReference currentObject = (ObjectReference) currentValue;
            String part = parts[i];

            Optional<Value> nextValueOpt = resolvePart(thread, currentObject, part);
            if (!nextValueOpt.isPresent()) {
                return Optional.empty(); // Field or method not found
            }
            currentValue = nextValueOpt.get();
        }

        return Optional.ofNullable(currentValue);
    }

    /**
     * Gets the initial variable or field from the stack frame.
     */
    private Optional<Value> getStartingValue(StackFrame frame, String name) {
        try {
            // 1. Try to find a local variable
            LocalVariable localVar = frame.visibleVariableByName(name);
            if (localVar != null) {
                return Optional.ofNullable(frame.getValue(localVar));
            }

            // 2. If not found, try to find a field in 'this'
            ObjectReference thisObject = frame.thisObject();
            if (thisObject != null) {
                Field field = thisObject.referenceType().fieldByName(name);
                if (field != null) {
                    return Optional.ofNullable(thisObject.getValue(field));
                }
            }
        } catch (AbsentInformationException e) {
            // Debug information is not available to access local variables.
            // We can still try to resolve against 'this'.
            ObjectReference thisObject = frame.thisObject();
            if (thisObject != null) {
                Field field = thisObject.referenceType().fieldByName(name);
                if (field != null) {
                    return Optional.ofNullable(thisObject.getValue(field));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves one part of an expression against an object reference.
     * It prioritizes fields, then getters.
     */
    private Optional<Value> resolvePart(ThreadReference thread, ObjectReference currentObject, String part) {
        ReferenceType refType = currentObject.referenceType();

        // 1. Try to resolve as a field
        Field field = refType.fieldByName(part);
        if (field != null) {
            return Optional.ofNullable(currentObject.getValue(field));
        }

        // 2. Try to resolve as a getter method (e.g., getId, isEnabled)
        String getterName1 = "get" + capitalize(part);
        String getterName2 = "is" + capitalize(part);

        // Search for no-argument methods
        List<Method> methods1 = refType.methodsByName(getterName1);
        List<Method> methods2 = refType.methodsByName(getterName2);

        Method methodToInvoke = findNoArgMethod(methods1);
        if (methodToInvoke == null) {
            methodToInvoke = findNoArgMethod(methods2);
        }

        if (methodToInvoke != null) {
            try {
                Value result = currentObject.invokeMethod(thread, methodToInvoke, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
                return Optional.ofNullable(result);
            } catch (Exception e) {
                // Invocation failed (e.g., exception in target VM)
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private Method findNoArgMethod(List<Method> methods) {
        for (Method method : methods) {
            if (method.argumentTypeNames().isEmpty()) {
                return method;
            }
        }
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
