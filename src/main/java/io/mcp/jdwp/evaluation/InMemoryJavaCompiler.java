package io.mcp.jdwp.evaluation;

import io.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles Java source code in memory using the Eclipse JDT Core compiler via the standard Java Compiler API (JSR 199).
 * This service is designed to be self-contained and does not require a JDK to run.
 */
@Slf4j
@Service
public class InMemoryJavaCompiler {

    private String jdkPath;
    private String classpath;

    /**
     * Configures the compiler with the target JVM's JDK path and classpath.
     * This must be called before compiling expressions.
     *
     * @param jdkPath   The file path to the root of the target JDK.
     * @param classpath Classpath string from target JVM (colon or semicolon separated).
     */
    public synchronized void configure(String jdkPath, String classpath) {
        long startTime = System.currentTimeMillis();
        this.jdkPath = jdkPath;
        this.classpath = classpath;

        if (classpath == null || classpath.isEmpty()) {
            log.warn("[Compiler] Classpath is empty, application classes may not be resolved.");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String[] classpathEntries = classpath != null ? classpath.split(File.pathSeparator) : new String[0];
        log.info("[Compiler] Configured with JDK at {} and {} classpath entries in {}ms",
            jdkPath, classpathEntries.length, elapsed);
    }

    /**
     * Compiles a single Java source file in memory.
     *
     * @param className The fully qualified name of the class to compile.
     * @param sourceCode The source code of the class.
     * @return A map where the key is the class name and the value is the compiled bytecode.
     * @throws JdiEvaluationException if compilation fails.
     */
    public Map<String, byte[]> compile(String className, String sourceCode) throws JdiEvaluationException {
        if (this.jdkPath == null) {
            throw new JdiEvaluationException("Compiler is not configured. Please call configure() with a valid JDK path and classpath.");
        }

        long startTime = System.currentTimeMillis();

        JavaCompiler compiler = new org.eclipse.jdt.internal.compiler.tool.EclipseCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null, null);
        MemoryJavaFileManager fileManager = new MemoryJavaFileManager(standardFileManager);

        Path tempDir = null;
        try {
            // 1. Create a temporary directory structure for the source file
            tempDir = Files.createTempDirectory("mcp-compiler-");
            String[] packageAndClass = getPackageAndClassName(className);
            Path packageDir = tempDir;
            if (packageAndClass[0] != null) {
                packageDir = tempDir.resolve(packageAndClass[0].replace('.', File.separatorChar));
                Files.createDirectories(packageDir);
            }
            Path sourceFile = packageDir.resolve(packageAndClass[1] + ".java");

            // 2. Write the source code to the temporary file
            Files.write(sourceFile, sourceCode.getBytes(StandardCharsets.UTF_8));

            // 3. Get a JavaFileObject for the temporary file
            Iterable<? extends JavaFileObject> compilationUnits = standardFileManager.getJavaFileObjects(sourceFile.toFile());

            // 4. Build compiler options
            List<String> options = new ArrayList<>();
            options.addAll(Arrays.asList("-source", "1.8", "-target", "1.8"));
            options.add("-g"); // Preserve local variable names
            options.addAll(Arrays.asList("--system", this.jdkPath));
            if (this.classpath != null && !this.classpath.isEmpty()) {
                options.addAll(Arrays.asList("-classpath", this.classpath));
            }

            // 5. Create and run the compilation task
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits
            );

            boolean success = task.call();

            if (!success) {
                String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> {
                        String location = d.getSource() != null
                            ? "Line " + d.getLineNumber() + " in " + d.getSource().getName()
                            : "Line " + d.getLineNumber();
                        return location + ": " + d.getMessage(null);
                    })
                    .collect(Collectors.joining("\n"));
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[Compiler] Compilation failed after {}ms:\n{}", elapsed, errors);
                throw new JdiEvaluationException("Compilation failed:\n" + errors);
            }

            Map<String, byte[]> compiledBytecode = fileManager.getCompiledBytecode();
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Compiler] Compilation successful in {}ms ({} bytes generated for {} class(es))",
                elapsed, compiledBytecode.values().stream().mapToInt(b -> b.length).sum(), compiledBytecode.size());
            return compiledBytecode;

        } catch (IOException e) {
            throw new JdiEvaluationException("Failed to create or write temporary source file for compilation.", e);
        } finally {
            // 6. Clean up the temporary directory
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                } catch (IOException e) {
                    log.warn("[Compiler] Failed to delete temporary compilation directory: {}", tempDir, e);
                }
            }
        }
    }

    private String[] getPackageAndClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        if (lastDot == -1) {
            return new String[]{null, fullClassName};
        }
        return new String[]{fullClassName.substring(0, lastDot), fullClassName.substring(lastDot + 1)};
    }

    /**
     * An in-memory file manager to capture compiled bytecode.
     */
    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputStreams = new HashMap<>();

        protected MemoryJavaFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    outputStreams.put(className, bos);
                    return bos;
                }
            };
        }

        public Map<String, byte[]> getCompiledBytecode() {
            Map<String, byte[]> bytecodeMap = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : outputStreams.entrySet()) {
                bytecodeMap.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return bytecodeMap;
        }
    }
}
