package com.azure.openrewrite.goldenimages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.Nullable;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;



public class GoldenImageValidator {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static boolean isIdentical(Path p1, Path p2) throws IOException {
        String content1 = new String(Files.readAllBytes(p1), StandardCharsets.UTF_8)
            .replaceAll("\\r\\n?", "\n")
            .trim();

        String content2 = new String(Files.readAllBytes(p2), StandardCharsets.UTF_8)
            .replaceAll("\\r\\n?", "\n")
            .trim();

        return content1.equals(content2);
    }



    public static void runAllValidations(
        Log log,
        Path baseDir,
        MavenProject project
    ) throws Exception {
        Path basePath = baseDir.resolve("src/test/resources/migrationExamples");
        if (!Files.exists(basePath)) {
            throw new IllegalStateException("migrationExamples path not found: " + basePath.toAbsolutePath());
        }

        int total = 0;
        int passed = 0;
        int skipped = 0;
        int failed = 0;

        try (Stream<Path> testDirs = Files.walk(basePath)) {
            for (Path testDir : testDirs.filter(Files::isDirectory).collect(Collectors.toList())) {

                Path v1Dir = testDir.resolve("v1");
                Path v2Dir = testDir.resolve("v2");

                if (!Files.exists(v1Dir) || !Files.exists(v2Dir)) {
                    continue;
                }

                Optional<Path> mainV1File = Files.walk(v1Dir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .findFirst();
                Optional<Path> mainV2File = Files.walk(v2Dir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .findFirst();

                if (mainV1File.isPresent() && mainV2File.isPresent()) {
                    if (isIdentical(mainV1File.get(), mainV2File.get())) {
                        log.info("Skipped (identical source): " + testDir.getFileName());
                        skipped++;
                        continue;
                    }

                    Path v1Output = compileSourcesForSingleTest(
                        v1Dir, project, log, "v1", testDir);
                    Path v2Output = compileSourcesForSingleTest(
                        v2Dir, project, log, "v2", testDir);

                    if (v1Output == null || v2Output == null) {
                        log.warn("Skipping validation due to failed compilation.");
                        continue;
                    }

                    String v1ClassName = getFullyQualifiedClassName(v1Dir, mainV1File.get());
                    String v2ClassName = getFullyQualifiedClassName(v2Dir, mainV2File.get());

                    log.info("Validating: " + testDir.getFileName());


                    RunResult v1Result = runAndCapture(v1ClassName, log, project, v1Output);
                    RunResult v2Result = runAndCapture(v2ClassName, log, project, v2Output);



                    Path validationOutputDir = baseDir.resolve("target/validationResults")
                        .resolve(basePath.relativize(testDir));
                    Files.createDirectories(validationOutputDir);


                    writeOutput(validationOutputDir, "v1-output.json", v1Result.output, v1Result.exception);
                    writeOutput(validationOutputDir, "v2-output.json", v2Result.output, v2Result.exception);

                    boolean v1Failed = v1Result.exception != null;
                    boolean v2Failed = v2Result.exception != null;


                    if (v1Failed || v2Failed) {
                        failed++;
                        log.warn("Failed due to runtime exception: " + testDir.getFileName());
                    } else if (Objects.equals(v1Result.output, v2Result.output)) {
                        log.info("Passed: " + testDir.getFileName());
                        passed++;
                    } else {
                        failed++;
                        log.warn("Failed: " + testDir.getFileName());
                    }
                    total++;
                }
            }
        }
        log.info(String.format(
            "%nValidation Summary: %d/%d passed. %d failed. %d skipped",
            passed, total, failed, skipped
        ));
    }



    private static @Nullable Path compileSourcesForSingleTest(
        Path versionDir,
        MavenProject project,
        Log log,
        String version,
        Path testDir
    ) throws MojoExecutionException {

        List<String> sourceFiles = new ArrayList<>();

        try {
            Files.walk(versionDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> sourceFiles.add(p.toFile().getAbsolutePath()));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read source files for test: " + testDir, e);
        }

        if (sourceFiles.isEmpty()) {
            log.warn("No source files found in " + versionDir);
            return null;
        }

        File outputDir = new File(project.getBuild().getTestOutputDirectory(), version + File.separator + testDir.getFileName());
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create output directory: " + outputDir.getAbsolutePath());
        }

        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(outputDir.getAbsolutePath());
        options.add("-Xlint:-unchecked");
        options.add("-Xlint:-deprecation");

        try {
            String classpath = String.join(File.pathSeparator, project.getTestClasspathElements());
            options.add("-classpath");
            options.add(classpath);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve test classpath elements", e);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException("Java Compiler not available. Are you running on a JRE instead of a JDK?");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromStrings(sourceFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            boolean success = task.call();
            boolean hasErrors = diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);

            if (hasErrors) {
                for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        URI sourceUri = diagnostic.getSource() != null
                            ? ((JavaFileObject) diagnostic.getSource()).toUri()
                            : URI.create("unknown://source");

                        log.error(String.format("Error on line %d in %s: %s",
                            diagnostic.getLineNumber(),
                            sourceUri,
                            diagnostic.getMessage(null)));
                    }
                }
                return null;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error during compilation", e);
        }
        log.info("Compilation succeeded for " + testDir.getFileName() + " (" + version + ")");
        return outputDir.toPath();
    }



    private static RunResult runAndCapture(
        String fullyQualifiedClassName,
        Log log,
        MavenProject project,
        Path classOutputDir
    ) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos);
        System.setOut(capture);
        Exception exception = null;

        try {
            List<URL> urls = new ArrayList<>();

            urls.add(classOutputDir.toUri().toURL());

            for (String element : project.getTestClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }

            try (URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader())) {

                Class<?> cls = Class.forName(fullyQualifiedClassName, true, classLoader);
                Method main = cls.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[] {});
            }

        } catch (Exception e) {
            Throwable realException = (e instanceof InvocationTargetException && e.getCause() != null)
                ? e.getCause()
                : e;

            log.warn("Error running " + fullyQualifiedClassName + ": " + realException.toString());
            realException.printStackTrace();

            exception = realException instanceof Exception ? (Exception) realException : new Exception(realException);
        } finally {
            try {
                capture.flush();
                System.out.flush();
            } catch (Exception e) {
                log.warn("Error while flushing output: " + e.getMessage());
            } finally {
                System.setOut(originalOut);
            }
        }

        return new RunResult(baos.toString(), exception);
    }

    private static String getFullyQualifiedClassName(Path rootSourceDir, Path javaFile) throws IOException {
        List<String> lines = Files.readAllLines(javaFile);
        String packageName = "";
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("package ")) {
                int semicolonIndex = line.indexOf(';');
                if (semicolonIndex > 0) {
                    packageName = line.substring(8, semicolonIndex).trim();
                }
                break;
            }
        }

        String className = javaFile.getFileName().toString().replace(".java", "");
        if (!packageName.isEmpty()) {
            return packageName + "." + className;
        } else {
            return className;
        }
    }

    private static void writeOutput(Path testDir, String filename, String output, @Nullable Exception exception) throws IOException {
        ObjectNode json = mapper.createObjectNode();
        json.put("stdout", output);

        if (exception != null) {
            json.put("exception", exception.toString());
        }

        Path outputPath = testDir.resolve(filename);
        Files.write(outputPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json));
    }

    private static class RunResult {
        final String output;
        final Exception exception;

        RunResult(String output, Exception exception) {
            this.output = output;
            this.exception = exception;
        }
    }


}


