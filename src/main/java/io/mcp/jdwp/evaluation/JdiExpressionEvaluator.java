package io.mcp.jdwp.evaluation;

import io.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import io.mcp.jdwp.JDIConnectionService;
import com.sun.jdi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates the evaluation of a Java expression within a given JDI StackFrame context.
 * It generates, compiles, injects, and executes code in the target VM.
 */
@Slf4j
@Service
public class JdiExpressionEvaluator {

    private static final String EVALUATION_PACKAGE = "mcp.jdi.evaluation";
    private static final String EVALUATION_CLASS_PREFIX = "ExpressionEvaluator_";
    private static final String EVALUATION_METHOD_NAME = "evaluate";

    private final InMemoryJavaCompiler compiler;
    private final RemoteCodeExecutor remoteCodeExecutor;
    private final JDIConnectionService jdiConnectionService;

    // Cache to store compiled bytecode for a given expression and context signature
    private final Map<String, Map<String, byte[]>> compilationCache = new ConcurrentHashMap<>();

    // Flag to track if classpath has been configured
    private volatile boolean classpathConfigured = false;

    public JdiExpressionEvaluator(InMemoryJavaCompiler compiler,
                                   RemoteCodeExecutor remoteCodeExecutor,
                                   JDIConnectionService jdiConnectionService) {
        this.compiler = compiler;
        this.remoteCodeExecutor = remoteCodeExecutor;
        this.jdiConnectionService = jdiConnectionService;
    }

    /**
     * Evaluates a Java expression in the context of a given stack frame.
     *
     * @param frame The stack frame providing the context (local variables, 'this').
     * @param expression The Java expression to evaluate.
     * @return The resulting Value from the evaluation.
     * @throws JdiEvaluationException if any part of the process fails.
     */
    public Value evaluate(StackFrame frame, String expression) throws JdiEvaluationException {
        try {
            // NOTE: Classpath configuration must be done BEFORE calling evaluate() to avoid nested JDI calls
            // The caller (e.g., jdwp_evaluate_watchers) is responsible for calling configureCompilerClasspath()

            // 1. Analyze the frame to build the evaluation context
            EvaluationContext context = buildContext(frame);

            // 2. Generate a unique class name with UUID to avoid LinkageError across server restarts
            String uniqueId = UUID.randomUUID().toString().replace("-", "");
            String className = EVALUATION_PACKAGE + "." + EVALUATION_CLASS_PREFIX + uniqueId;

            // 3. Generate the source code for the wrapper class
            String sourceCode = generateSourceCode(className, context, expression);

            // 4. Compile the source code (using cache if possible)
            String cacheKey = context.getSignature() + "###" + expression;
            Map<String, byte[]> compiledCode = compilationCache.computeIfAbsent(cacheKey, k -> {
                try {
                    return compiler.compile(className, sourceCode);
                } catch (JdiEvaluationException e) {
                    throw new RuntimeException(e); // Will be caught and re-wrapped below
                }
            });

            // 5. Find a suitable class loader in the target VM
            ClassLoaderReference classLoader = findClassLoader(frame);

            // 6. Execute the code remotely
            String mainClassName = className.replace('.', '/');
            byte[] bytecode = compiledCode.get(mainClassName);
            if (bytecode == null) {
                throw new JdiEvaluationException("Could not find compiled bytecode for class " + className);
            }

            return remoteCodeExecutor.execute(
                frame.virtualMachine(),
                frame.thread(),
                classLoader,
                className,
                bytecode,
                EVALUATION_METHOD_NAME,
                context.getValues()
            );
        } catch (Exception e) {
            // Un-wrap runtime exception from cache computation
            if (e instanceof RuntimeException && e.getCause() instanceof JdiEvaluationException) {
                throw (JdiEvaluationException) e.getCause();
            }
            throw new JdiEvaluationException("Expression evaluation failed: " + e.getMessage(), e);
        }
    }

    private EvaluationContext buildContext(StackFrame frame) throws AbsentInformationException {
        List<ContextVariable> variables = new ArrayList<>();
        List<Value> values = new ArrayList<>();

        ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            // Use declared type instead of runtime type to avoid issues with dynamic proxies (Guice, CGLIB, etc.)
            String declaredType = getDeclaredType(thisObject.referenceType());
            variables.add(new ContextVariable("_this", declaredType));
            values.add(thisObject);
        }

        for (LocalVariable var : frame.visibleVariables()) {
            if (var.isArgument() || !var.name().startsWith("this$")) {
                variables.add(new ContextVariable(var.name(), var.typeName()));
                values.add(frame.getValue(var));
            }
        }
        return new EvaluationContext(variables, values);
    }

    private String generateSourceCode(String className, EvaluationContext context, String expression) {
        String packageName = EVALUATION_PACKAGE;
        String simpleClassName = className.substring(packageName.length() + 1);

        String methodParameters = context.getVariables().stream()
            .map(v -> v.type + " " + v.name)
            .collect(Collectors.joining(", "));

        // Replace 'this' with '_this' in the expression to match the parameter name
        String safeExpression = expression.replaceAll("(?<!\\w)this(?!\\w)", "_this");

        return "package " + packageName + ";\n" +
               "\n" +
               "// Automatically generated class for JDI expression evaluation\n" +
               "public class " + simpleClassName + " {\n" +
               "    public static Object " + EVALUATION_METHOD_NAME + "(" + methodParameters + ") {\n" +
               "        // User expression:\n" +
               "        return (Object) (" + safeExpression + ");\n" +
               "    }\n" +
               "}\n";
    }

    /**
     * Get the declared (non-proxy) type name of a reference type.
     * This handles dynamic proxies generated by frameworks like Guice, CGLIB, Spring AOP, etc.
     *
     * @param type The runtime type (may be a proxy)
     * @return The declared type name (without proxy suffixes)
     */
    private String getDeclaredType(ReferenceType type) {
        String typeName = type.name();

        // Check if it's a dynamic proxy (contains $$ which is common for Guice, CGLIB, Mockito, etc.)
        if (typeName.contains("$$")) {
            // Try to get the superclass (proxies usually extend the real class)
            if (type instanceof ClassType) {
                ClassType classType = (ClassType) type;
                ClassType superclass = classType.superclass();
                if (superclass != null && !superclass.name().equals("java.lang.Object")) {
                    // Recursively check superclass in case of nested proxies
                    return getDeclaredType(superclass);
                }
            }

            // Fallback: try to extract the base class name before $$
            int dollarIndex = typeName.indexOf("$$");
            if (dollarIndex > 0) {
                return typeName.substring(0, dollarIndex);
            }
        }

        return typeName;
    }

    private ClassLoaderReference findClassLoader(StackFrame frame) throws JdiEvaluationException {
        ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            return thisObject.referenceType().classLoader();
        }
        // Fallback: find the system class loader by looking for a common class
        List<ReferenceType> systemClasses = frame.virtualMachine().classesByName("java.lang.Object");
        if (systemClasses.isEmpty()) {
            throw new JdiEvaluationException("Could not find java.lang.Object to get system class loader.");
        }
        return systemClasses.get(0).classLoader();
    }

    // Helper classes for context management
    private static class EvaluationContext {
        private final List<ContextVariable> variables;
        private final List<Value> values;
        private final String signature;

        EvaluationContext(List<ContextVariable> variables, List<Value> values) {
            this.variables = variables;
            this.values = values;
            this.signature = variables.stream().map(v -> v.type + " " + v.name).collect(Collectors.joining(","));
        }

        public List<ContextVariable> getVariables() { return variables; }
        public List<Value> getValues() { return values; }
        public String getSignature() { return signature; }
    }

    private static class ContextVariable {
        final String name;
        final String type;

        ContextVariable(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * Configure the compiler with target JVM's classpath.
     * This must be called BEFORE any expression evaluation to avoid nested JDI calls.
     *
     * @param suspendedThread A thread already suspended at a breakpoint (REQUIRED)
     */
    public synchronized void configureCompilerClasspath(ThreadReference suspendedThread) {
        if (classpathConfigured) {
            return; // Already configured
        }

        long startTime = System.currentTimeMillis();

        try {
            String classpath = jdiConnectionService.discoverClasspath(suspendedThread);
            String jdkPath = jdiConnectionService.getDiscoveredJdkPath();

            if (jdkPath == null) {
                log.error("[Evaluator] JDK path not discovered, cannot configure compiler");
                classpathConfigured = true; // Don't retry on every evaluation
                return;
            }

            if (classpath != null && !classpath.isEmpty()) {
                compiler.configure(jdkPath, classpath);
                classpathConfigured = true;

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[Evaluator] Compiler configured in {}ms", elapsed);
            } else {
                log.error("[Evaluator] Failed to discover classpath, expression evaluation may fail for application classes");
                classpathConfigured = true; // Don't retry on every evaluation
            }

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Evaluator] Error configuring classpath after {}ms", elapsed, e);
            classpathConfigured = true; // Don't retry on every evaluation
        }
    }
}
