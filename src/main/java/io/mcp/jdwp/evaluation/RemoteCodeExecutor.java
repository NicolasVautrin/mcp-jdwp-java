package io.mcp.jdwp.evaluation;

import io.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import com.sun.jdi.*;
import com.sun.jdi.InvocationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles the remote execution of compiled Java code in a target JVM via JDI.
 */
@Slf4j
@Service
public class RemoteCodeExecutor {

    private static final String CLASSLOADER_DEFINE_CLASS_METHOD = "defineClass";
    private static final String CLASSLOADER_DEFINE_CLASS_SIGNATURE = "(Ljava/lang/String;[BII)Ljava/lang/Class;";

    /**
     * Injects and executes a static method from the given bytecode in the target VM.
     *
     * @param vm           The target VirtualMachine.
     * @param thread       The thread in which to execute the code. Must be suspended.
     * @param classLoader  The ClassLoaderReference to use for defining the new class.
     * @param className    The fully qualified name of the class to load.
     * @param bytecode     The compiled bytecode of the class.
     * @param methodName   The name of the static method to invoke.
     * @param methodArgs   The arguments to pass to the method.
     * @return The Value returned by the remote method invocation.
     * @throws JdiEvaluationException if any step of the remote execution fails.
     */
    public Value execute(VirtualMachine vm, ThreadReference thread, ClassLoaderReference classLoader,
                         String className, byte[] bytecode, String methodName, List<Value> methodArgs)
        throws JdiEvaluationException {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("[Executor] Starting remote execution of {}.{}() with {} args",
                className, methodName, methodArgs.size());

            // 1. Load the class into the target VM
            ClassType loadedClass = loadClass(vm, thread, classLoader, className, bytecode);
            log.debug("[Executor] Class {} loaded successfully", className);

            // 2. Force class preparation/initialization via Class.forName()
            // This ensures the class is in "prepared" state before calling methodsByName()
            forceClassInitialization(vm, thread, classLoader, className);
            log.debug("[Executor] Class {} initialized successfully", className);

            // 3. Find the static method to execute (now safe after initialization)
            Method methodToInvoke = loadedClass.methodsByName(methodName).stream()
                .filter(Method::isStatic)
                .findFirst()
                .orElseThrow(() -> new JdiEvaluationException("Could not find static method '" + methodName + "' in loaded class."));

            log.debug("[Executor] Found static method {}", methodName);

            // 3. Invoke the method
            Value result = loadedClass.invokeMethod(thread, methodToInvoke, methodArgs, ClassType.INVOKE_SINGLE_THREADED);

            long elapsed = System.currentTimeMillis() - startTime;
            String resultType = result != null ? result.type().name() : "null";
            log.info("[Executor] Remote method invoked successfully in {}ms, returned: {}", elapsed, resultType);

            return result;

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Executor] Remote execution failed after {}ms: {}", elapsed, e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to execute remote code: " + e.getMessage(), e);
        } catch (InvocationException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            ObjectReference exception = e.exception();
            String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] Target VM threw exception after {}ms: {}", elapsed, exceptionType, e);
            throw new JdiEvaluationException("Target VM threw exception: " + exceptionType, e);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Executor] Unexpected exception after {}ms: {}", elapsed, e.getClass().getName(), e);
            throw new JdiEvaluationException("Unexpected exception during remote execution: " + e.getMessage(), e);
        }
    }

    private ClassType loadClass(VirtualMachine vm, ThreadReference thread, ClassLoaderReference classLoader,
                                String className, byte[] bytecode) throws JdiEvaluationException {
        try {
            log.debug("[Executor] Loading class {} ({} bytes) using classloader {}",
                className, bytecode.length, classLoader.referenceType().name());

            // Find the ClassLoader.defineClass(String, byte[], int, int) method
            ReferenceType classLoaderType = classLoader.referenceType();
            List<Method> defineClassMethods = classLoaderType.methodsByName(CLASSLOADER_DEFINE_CLASS_METHOD, CLASSLOADER_DEFINE_CLASS_SIGNATURE);
            if (defineClassMethods.isEmpty()) {
                log.error("[Executor] Could not find defineClass method on {}", classLoaderType.name());
                throw new JdiEvaluationException("Could not find 'defineClass' method on the provided ClassLoader.");
            }
            Method defineClassMethod = defineClassMethods.get(0);

            // Create a remote byte array in the target VM to hold our bytecode
            ArrayReference remoteByteArray = createRemoteByteArray(vm, bytecode);
            log.debug("[Executor] Created remote byte array of {} bytes", bytecode.length);

            // Prepare arguments for defineClass: (className, remoteByteArray, 0, bytecode.length)
            List<Value> args = List.of(
                vm.mirrorOf(className),
                remoteByteArray,
                vm.mirrorOf(0),
                vm.mirrorOf(bytecode.length)
            );

            // Invoke ClassLoader.defineClass(...)
            log.debug("[Executor] Invoking defineClass on classloader");
            Value loadedClassObject = classLoader.invokeMethod(thread, defineClassMethod, args, ClassType.INVOKE_SINGLE_THREADED);
            if (!(loadedClassObject instanceof ClassObjectReference)) {
                log.error("[Executor] defineClass returned unexpected type: {}",
                    loadedClassObject != null ? loadedClassObject.getClass().getName() : "null");
                throw new JdiEvaluationException("defineClass did not return a Class object.");
            }

            ClassType result = (ClassType) ((ClassObjectReference) loadedClassObject).reflectedType();
            log.debug("[Executor] Class loaded and reflected successfully: {}", result.name());
            return result;

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            log.error("[Executor] Failed to load class {}: {}", className, e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to load class '" + className + "' into target VM: " + e.getMessage(), e);
        } catch (InvocationException e) {
            ObjectReference exception = e.exception();
            String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] defineClass threw exception {}: {}", exceptionType, e.getMessage(), e);
            throw new JdiEvaluationException("Failed to load class '" + className + "': " + exceptionType, e);
        }
    }

    private ArrayReference createRemoteByteArray(VirtualMachine vm, byte[] bytes) throws JdiEvaluationException {
        try {
            log.debug("[Executor] Creating remote byte array of {} bytes", bytes.length);

            // Find the byte[] array type
            ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
            // Create a new instance of byte[] in the target VM
            ArrayReference arrayRef = byteArrayType.newInstance(bytes.length);

            // To set the values, we need to create a List<Value> of ByteValue mirrors
            List<Value> mirrorBytes = new java.util.ArrayList<>(bytes.length);
            for (byte b : bytes) {
                mirrorBytes.add(vm.mirrorOf(b));
            }

            // Set the array elements in the target VM
            arrayRef.setValues(0, mirrorBytes, 0, bytes.length);
            log.debug("[Executor] Remote byte array created and populated");
            return arrayRef;

        } catch (ClassNotLoadedException | InvalidTypeException e) {
            log.error("[Executor] Failed to create remote byte array: {}", e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to create remote byte array: " + e.getMessage(), e);
        }
    }

    /**
     * Forces class initialization by invoking Class.forName(className, true, classLoader) in the target VM.
     * This ensures the class goes through preparation and initialization before we call methodsByName().
     */
    private void forceClassInitialization(VirtualMachine vm, ThreadReference thread,
                                         ClassLoaderReference classLoader, String className) throws JdiEvaluationException {
        try {
            log.debug("[Executor] Forcing initialization of class {}", className);

            // Get java.lang.Class in the target VM
            ClassType classClass = (ClassType) vm.classesByName("java.lang.Class").get(0);

            // Find Class.forName(String, boolean, ClassLoader) method
            Method forNameMethod = classClass.methodsByName("forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;").get(0);

            // Prepare arguments: (className, true, classLoader)
            StringReference classNameRef = vm.mirrorOf(className);
            BooleanValue initializeRef = vm.mirrorOf(true);

            List<Value> args = List.of(classNameRef, initializeRef, classLoader);

            // Invoke Class.forName() to force initialization
            classClass.invokeMethod(thread, forNameMethod, args, ClassType.INVOKE_SINGLE_THREADED);

            log.debug("[Executor] Class {} initialization completed", className);

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            log.error("[Executor] Failed to initialize class {}: {}", className, e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to initialize class: " + e.getMessage(), e);
        } catch (InvocationException e) {
            // This can happen if the static initializer (<clinit>) throws an exception
            ObjectReference exception = e.exception();
            String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] Static initializer of {} threw exception {}", className, exceptionType, e);
            throw new JdiEvaluationException("Static initializer threw exception: " + exceptionType, e);
        }
    }
}
