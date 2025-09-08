package com.azure.openrewrite.goldenimages;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "analyze-golden-image-coverage")
public class GoldenImageCoverageAnalysisMojo extends AbstractMojo {

    @Parameter(property = "targetProfiles", defaultValue = "v2")
    private String targetProfiles;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ProjectBuilder projectBuilder;

    public void execute() throws MojoExecutionException {
        String testResourcesPath = baseDir.getAbsolutePath() + "/src/test/resources/migrationExamples";
        File testResourcesDir = new File(testResourcesPath);

        if (!testResourcesDir.exists()) {
            throw new MojoExecutionException("Test resources directory does not exist: " + testResourcesPath);
        }

        List<File> testFolders = findTestFoldersWithV1(testResourcesDir);
        Set<MethodCall> goldenImageMethodCalls = new HashSet<>();

        for (File folder : testFolders) {
            File v1Dir = new File(folder, "v1");
            File v2Dir = new File(folder, "v2");

            if (v2Dir.exists() && hasChanges(v2Dir, v1Dir)) {
                try {
                    Set<MethodCall> v2MethodCalls = analyzeGoldenImages(v2Dir, "v2");
                    goldenImageMethodCalls.addAll(v2MethodCalls);
                } catch (Exception e) {
                    getLog().error("Failed to analyze golden images in " + v2Dir.getAbsolutePath(), e);
                }
            } else if (!v2Dir.exists()) {
                getLog().warn("Skipping " + folder.getAbsolutePath() + " as v2 directory does not exist.");
            } else {
                getLog().info("Skipping analysis of " + v2Dir.getAbsolutePath() + " as no changes were detected compared to " + v1Dir.getAbsolutePath() + ".");
            }
        }
        outputMethodCallsToFile(goldenImageMethodCalls, "golden-image-method-usage.json");

        Map<String, Set<MethodCall>> publicApiByModule = analyzePublicApiByModule();
        Set<MethodCall> publicApiMethodCalls = publicApiByModule.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toSet());
        outputMethodCallsToFile(publicApiMethodCalls, "public-api-method-usage.json");

        Set<MethodCall> uncoveredMethods = findUncoveredMethods(publicApiMethodCalls, goldenImageMethodCalls);
        outputMethodCallsToFile(uncoveredMethods, "uncovered-api-methods.json");

        logDetailedCoverageMetrics(publicApiByModule, goldenImageMethodCalls, uncoveredMethods);
    }

    private Map<String, Set<MethodCall>> analyzePublicApiByModule() throws MojoExecutionException {
        getLog().info("Starting public API analysis for Azure V2 SDKs.");

        File repoRoot = baseDir.getParentFile().getParentFile();

        // Define the relative paths to the source directories with their simplified names
        Map<String, String> pathMappings = new java.util.LinkedHashMap<>();
        pathMappings.put("appconfiguration-v2/azure-data-appconfiguration/src/main/java/com/azure/v2/data/appconfiguration",
            "com/azure/v2/data/appconfiguration");
        pathMappings.put("core-v2/azure-core/src/main/java/com/azure/v2/core",
            "com/azure/v2/core");
        pathMappings.put("clientcore/core/src/main/java/io/clientcore/core",
            "io/clientcore/core");
        pathMappings.put("identity-v2/azure-identity/src/main/java/com/azure/v2/identity",
            "com/azure/v2/identity");
        pathMappings.put("keyvault-v2/azure-security-keyvault-keys/src/samples/java/com/azure/v2/security/keyvault/keys",
            "com/azure/v2/security/keyvault/keys");
        pathMappings.put("keyvault-v2/azure-security-keyvault-administration/src/main/java/com/azure/v2/security/keyvault/administration",
            "com/azure/v2/security/keyvault/administration");
        pathMappings.put("keyvault-v2/azure-security-keyvault-certificates/src/main/java/com/azure/v2/security/keyvault/certificates",
            "com/azure/v2/security/keyvault/certificates");
        pathMappings.put("keyvault-v2/azure-security-keyvault-secrets/src/main/java/com/azure/v2/security/keyvault/secrets",
            "com/azure/v2/security/keyvault/secrets");

        Map<String, Set<MethodCall>> methodsByModule = new java.util.LinkedHashMap<>();
        List<String> classpathElements = getClasspathForProfile("v2");

        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        for (String fullPath : pathMappings.keySet()) {
            File sourceDir = new File(repoRoot, fullPath);
            if (sourceDir.exists()) {
                combinedTypeSolver.add(new JavaParserTypeSolver(sourceDir));
            }
        }
        for (String classpathElement : classpathElements) {
            try {
                if (classpathElement.endsWith(".jar")) {
                    combinedTypeSolver.add(new JarTypeSolver(classpathElement));
                }
            } catch (IOException e) {
                getLog().warn("Could not add JAR to type solver: " + classpathElement);
            }
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        JavaParser javaParser = new JavaParser();
        javaParser.getParserConfiguration().setSymbolResolver(symbolSolver);

        for (Map.Entry<String, String> entry : pathMappings.entrySet()) {
            String fullPath = entry.getKey();
            String simplePath = entry.getValue();
            File sourceDir = new File(repoRoot, fullPath);

            if (sourceDir.exists()) {
                try {
                    Set<MethodCall> moduleMethods = extractDeclaredMethodsFromDirectory(sourceDir, javaParser);
                    methodsByModule.put(simplePath, moduleMethods);
                    getLog().info("Found " + moduleMethods.size() + " methods in " + simplePath);
                } catch (IOException e) {
                    getLog().error("Failed to analyze source directory " + sourceDir.getAbsolutePath(), e);
                    methodsByModule.put(simplePath, new HashSet<>());
                }
            } else {
                getLog().warn("Skipping missing source directory: " + sourceDir.getAbsolutePath());
                methodsByModule.put(simplePath, new HashSet<>());
            }
        }

        int totalMethods = methodsByModule.values().stream().mapToInt(Set::size).sum();
        getLog().info("Total unique public API methods found across all modules: " + totalMethods);
        return methodsByModule;
    }
    private void logDetailedCoverageMetrics(Map<String, Set<MethodCall>> publicApiByModule,
                                            Set<MethodCall> goldenImageMethods,
                                            Set<MethodCall> uncoveredMethods) {
        getLog().info("=== DETAILED COVERAGE ANALYSIS BY MODULE ===");

        int totalPublicMethods = 0;
        int totalCoveredMethods = 0;

        for (Map.Entry<String, Set<MethodCall>> entry : publicApiByModule.entrySet()) {
            String modulePath = entry.getKey();
            Set<MethodCall> moduleMethods = entry.getValue();

            int modulePublicMethods = moduleMethods.size();
            int moduleCoveredMethods = 0;

            // Count how many methods from this module are covered
            for (MethodCall moduleMethod : moduleMethods) {
                boolean isCovered = goldenImageMethods.stream()
                    .anyMatch(goldenMethod -> methodsMatch(moduleMethod, goldenMethod));
                if (isCovered) {
                    moduleCoveredMethods++;
                }
            }

            totalPublicMethods += modulePublicMethods;
            totalCoveredMethods += moduleCoveredMethods;

            double coveragePercentage = modulePublicMethods > 0 ?
                ((double) moduleCoveredMethods / modulePublicMethods) * 100 : 0;

            getLog().info(String.format("%s: %d/%d method calls covered by Golden Images (%.1f%%)",
                modulePath, moduleCoveredMethods, modulePublicMethods, coveragePercentage));
        }

        getLog().info("=== OVERALL COVERAGE SUMMARY ===");
        double overallCoverage = totalPublicMethods > 0 ?
            ((double) totalCoveredMethods / totalPublicMethods) * 100 : 0;
        getLog().info(String.format("TOTAL: %d/%d method calls covered by Golden Images (%.1f%%)",
            totalCoveredMethods, totalPublicMethods, overallCoverage));
        getLog().info("Uncovered methods: " + uncoveredMethods.size());
        getLog().info("See uncovered-api-methods.json to view the uncovered methods.");
    }

    private Set<MethodCall> findUncoveredMethods(Set<MethodCall> publicApiMethods, Set<MethodCall> goldenImageMethods) {
        getLog().info("Analyzing coverage gap between public API and golden image tests");

        Set<MethodCall> uncoveredMethods = new HashSet<>();

        for (MethodCall publicApiMethod : publicApiMethods) {
            boolean isCovered = goldenImageMethods.stream()
                .anyMatch(goldenMethod -> methodsMatch(publicApiMethod, goldenMethod));

            if (!isCovered) {
                uncoveredMethods.add(publicApiMethod);
            }
        }

        getLog().info("Found " + uncoveredMethods.size() + " uncovered API methods");
        return uncoveredMethods;
    }

    private boolean methodsMatch(MethodCall method1, MethodCall method2) {
        return method1.getClassName().equals(method2.getClassName()) &&
            method1.getMethodName().equals(method2.getMethodName()) &&
            method1.getSignature().equals(method2.getSignature());
    }

    private Set<MethodCall> extractDeclaredMethodsFromDirectory(File directory, JavaParser javaParser) throws IOException {
        Set<MethodCall> declaredMethods = new HashSet<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            List<Path> javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    boolean isInExcludedFolder = path.toString().contains("/implementation/")
                        || path.toString().contains("/cryptography/");
                    return !isInExcludedFolder;
                })
                .collect(Collectors.toList());

            for (Path javaFile : javaFiles) {
                try {
                    ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
                    if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                        CompilationUnit cu = parseResult.getResult().get();
                        Set<MethodCall> fileDeclaredMethods = extractDeclaredMethods(cu, javaFile.toString());
                        declaredMethods.addAll(fileDeclaredMethods);
                    } else {
                        getLog().warn("Failed to parse: " + javaFile.toString());
                        if (parseResult.getProblems().size() > 0) {
                            parseResult.getProblems().forEach(problem -> getLog().warn("Parse problem: " + problem.getMessage()));
                        }
                    }
                } catch (Exception e) {
                    getLog().warn("Error processing file " + javaFile.toString() + ": " + e.getMessage());
                }
            }
        }
        return declaredMethods;
    }

    private Set<MethodCall> extractDeclaredMethods(CompilationUnit cu, String sourceFile) {
        Set<MethodCall> declaredMethods = new HashSet<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                try {
                    ResolvedMethodDeclaration resolved = n.resolve();
                    String methodName = resolved.getName();
                    String className = resolved.declaringType().getQualifiedName();
                    if (isSyntheticClass(className)) {
                        return;
                    }
                    String signature = buildMethodSignature(resolved);
                    MethodCall methodCall = new MethodCall(className, methodName, signature, sourceFile);
                    declaredMethods.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve method declaration: " + n.getNameAsString() + " in " + sourceFile);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                try {
                    ResolvedConstructorDeclaration resolved = n.resolve();
                    String className = resolved.declaringType().getQualifiedName();
                    if (isSyntheticClass(className)) {
                        return;
                    }
                    String signature = buildConstructorSignature(resolved);
                    // Use "<init>" as the method name for constructors (JVM convention)
                    MethodCall methodCall = new MethodCall(className, "<init>", signature, sourceFile);
                    declaredMethods.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve constructor declaration in " + sourceFile);
                }
                super.visit(n, arg);
            }
        }, null);
        return declaredMethods;
    }

    private Set<MethodCall> extractMethodCallsFromDirectory(File directory, JavaParser javaParser) throws IOException {
        Set<MethodCall> methodCalls = new HashSet<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            List<Path> javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .collect(Collectors.toList());

            for (Path javaFile : javaFiles) {
                try {
                    ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
                    if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                        CompilationUnit cu = parseResult.getResult().get();
                        Set<MethodCall> fileMethodCalls = extractMethodCalls(cu, javaFile.toString());
                        methodCalls.addAll(fileMethodCalls);
                    } else {
                        getLog().warn("Failed to parse: " + javaFile.toString());
                        if (parseResult.getProblems().size() > 0) {
                            parseResult.getProblems().forEach(problem -> getLog().warn("Parse problem: " + problem.getMessage()));
                        }
                    }
                } catch (Exception e) {
                    getLog().warn("Error processing file " + javaFile.toString() + ": " + e.getMessage());
                }
            }
        }
        return methodCalls;
    }

    private String buildConstructorSignature(ResolvedConstructorDeclaration constructor) {
        StringBuilder signature = new StringBuilder();
        signature.append("<init>(");
        for (int i = 0; i < constructor.getNumberOfParams(); i++) {
            if (i > 0) {
                signature.append(",");
            }
            signature.append(constructor.getParam(i).getType().describe());
        }
        signature.append(")");
        return signature.toString();
    }

    private List<File> findTestFoldersWithV1(File baseDir) throws MojoExecutionException {
        try (Stream<Path> paths = Files.walk(baseDir.toPath())) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> path.resolve("v1").toFile().exists())
                .map(Path::toFile)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to find test folders", e);
        }
    }

    private Set<MethodCall> analyzeGoldenImages(File profileDir, String profile) throws MojoExecutionException {
        getLog().info("Analyzing golden images in " + profileDir.getAbsolutePath() + " with profile " + profile);
        Set<MethodCall> methodCalls = new HashSet<>();
        try {
            List<String> classpathElements = getClasspathForProfile(profile);
            TypeSolver typeSolver = createTypeSolver(profileDir, classpathElements);
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            JavaParser javaParser = new JavaParser();
            javaParser.getParserConfiguration().setSymbolResolver(symbolSolver);
            methodCalls.addAll(extractMethodCallsFromDirectory(profileDir, javaParser));
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze golden images in " + profileDir.getAbsolutePath(), e);
        }
        getLog().info("Found " + methodCalls.size() + " unique method calls in " + profileDir.getAbsolutePath());
        return methodCalls;
    }

    private String buildMethodSignature(ResolvedMethodDeclaration method) {
        StringBuilder signature = new StringBuilder();
        signature.append(method.getName()).append("(");
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            if (i > 0) {
                signature.append(",");
            }
            signature.append(method.getParam(i).getType().describe());
        }
        signature.append(")");
        return signature.toString();
    }

    private TypeSolver createTypeSolver(File profileDir, List<String> classpathElements) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        combinedTypeSolver.add(new JavaParserTypeSolver(profileDir));
        for (String classpathElement : classpathElements) {
            try {
                if (classpathElement.endsWith(".jar")) {
                    combinedTypeSolver.add(new JarTypeSolver(classpathElement));
                }
            } catch (IOException e) {
                getLog().warn("Could not add JAR to type solver: " + classpathElement);
            }
        }
        return combinedTypeSolver;
    }

    private List<String> getClasspathForProfile(String profile) throws MojoExecutionException {
        try {
            ProjectBuildingRequest buildingRequest = session.getProjectBuildingRequest();
            buildingRequest.setActiveProfileIds(Collections.singletonList(profile));
            buildingRequest.setResolveDependencies(true);
            ProjectBuildingResult result = projectBuilder.build(project.getFile(), buildingRequest);
            MavenProject activeProject = result.getProject();
            return activeProject.getCompileClasspathElements();
        } catch (ProjectBuildingException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve classpath for profile " + profile, e);
        }
    }

    private Set<MethodCall> extractMethodCalls(CompilationUnit cu, String sourceFile) {
        Set<MethodCall> methodCalls = new HashSet<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                try {
                    ResolvedMethodDeclaration resolved = n.resolve();
                    String methodName = resolved.getName();
                    String className = resolved.declaringType().getQualifiedName();
                    if (isSyntheticClass(className)) {
                        return;
                    }
                    String signature = buildMethodSignature(resolved);
                    MethodCall methodCall = new MethodCall(className, methodName, signature, sourceFile);
                    methodCalls.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve method call: " + n.getNameAsString() + " in " + sourceFile);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                try {
                    ResolvedConstructorDeclaration resolved = n.resolve();
                    String className = resolved.declaringType().getQualifiedName();
                    if (isSyntheticClass(className)) {
                        return;
                    }
                    String signature = buildConstructorSignature(resolved);
                    // Use "<init>" as the method name for constructor calls
                    MethodCall methodCall = new MethodCall(className, "<init>", signature, sourceFile);
                    methodCalls.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve constructor call in " + sourceFile);
                }
                super.visit(n, arg);
            }
        }, null);
        return methodCalls;
    }

    private boolean hasEqualFileContents(File file1, File file2) throws IOException {
        String before = Files.readAllLines(file1.toPath()).stream().collect(Collectors.joining("\n"));
        String after = Files.readAllLines(file2.toPath()).stream().collect(Collectors.joining("\n"));
        return before.equals(after);
    }

    private boolean hasChanges(File profileDir, File previousProfileDir) throws MojoExecutionException {
        getLog().info("Comparing files in " + profileDir.getAbsolutePath() + " with " + previousProfileDir.getAbsolutePath());
        try (Stream<Path> profilePaths = Files.walk(profileDir.toPath())) {
            return profilePaths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .anyMatch(profileFile -> {
                    Path relativePath = profileDir.toPath().relativize(profileFile);
                    Path previousFile = previousProfileDir.toPath().resolve(relativePath);
                    try {
                        if (!Files.exists(previousFile) || !hasEqualFileContents(profileFile.toFile(), previousFile.toFile())) {
                            getLog().info("Changes detected in " + profileFile.toString());
                            return true;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    getLog().info("No changes detected in " + profileFile.toString());
                    return false;
                });
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compare files in " + profileDir.getAbsolutePath() + " and " + previousProfileDir.getAbsolutePath(), e);
        }
    }

    private boolean isSyntheticClass(String className) {
        return className.contains(".Anonymous-")
            || className.contains("$Lambda$")
            || className.matches(".*\\$\\d+.*"); // e.g., MyClass$1
    }

    private void outputMethodCallsToFile(Set<MethodCall> methodCalls, String filename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();

            methodCalls.stream()
                .collect(Collectors.groupingBy(MethodCall::getClassName))
                .entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String className = entry.getKey();
                    List<MethodCall> calls = entry.getValue();

                    ArrayNode methodArray = mapper.createArrayNode();
                    calls.forEach(call -> {
                        ObjectNode methodNode = mapper.createObjectNode();
                        methodNode.put("methodName", call.getMethodName());
                        methodNode.put("signature", call.getSignature());
                        methodNode.put("sourceFile", call.getSourceFile());
                        methodArray.add(methodNode);
                    });
                    root.set(className, methodArray);
                });

            File outputFile = new File("azure-openrewrite-compiler-maven-plugin", filename);
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, root);
            getLog().info("Method usage analysis written to: " + outputFile.getAbsolutePath());
//            getLog().info("Total unique method calls found in " + filename + ": " + methodCalls.size());
//            getLog().info("Classes with method calls: " +
//                methodCalls.stream().map(MethodCall::getClassName).collect(Collectors.toSet()).size());
        } catch (IOException e) {
            getLog().error("Failed to write method usage analysis to file", e);
        }
    }

    private static class MethodCall {
        private final String className;
        private final String methodName;
        private final String signature;
        private final String sourceFile;

        public MethodCall(String className, String methodName, String signature, String sourceFile) {
            this.className = className;
            this.methodName = methodName;
            this.signature = signature;
            this.sourceFile = sourceFile;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getSignature() {
            return signature;
        }

        public String getSourceFile() {
            return sourceFile;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            MethodCall that = (MethodCall) obj;
            return className.equals(that.className) &&
                methodName.equals(that.methodName) &&
                signature.equals(that.signature);
        }

        @Override
        public int hashCode() {
            return (className + "." + signature).hashCode();
        }

        @Override
        public String toString() {
            return className + "." + signature;
        }
    }
}
