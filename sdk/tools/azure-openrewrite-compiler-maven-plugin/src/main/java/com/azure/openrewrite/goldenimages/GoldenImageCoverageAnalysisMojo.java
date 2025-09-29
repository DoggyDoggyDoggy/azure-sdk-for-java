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

        generateHtmlReport(publicApiByModule, goldenImageMethodCalls, uncoveredMethods);
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
        pathMappings.put("keyvault-v2/azure-security-keyvault-keys/src/main/java/com/azure/v2/security/keyvault/keys",
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
        getLog().info("");
        getLog().info("See uncovered-api-methods.json to view the uncovered methods.");
        getLog().info("");
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
                    for (Path part : path) {
                        String partName = part.toString();
                        if ("implementation".equals(partName) || "cryptography".equals(partName)) {
                            return false;
                        }
                    }
                    return true;
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
    
    private void generateHtmlReport(Map<String, Set<MethodCall>> publicApiByModule,
                                    Set<MethodCall> goldenImageMethodCalls,
                                    Set<MethodCall> uncoveredMethods) {
        try {
            // Calculate overall metrics
            int totalPublicMethods = publicApiByModule.values().stream()
                .mapToInt(Set::size).sum();
            int totalCoveredMethods = totalPublicMethods - uncoveredMethods.size();
            double overallCoverage = totalPublicMethods > 0 ?
                ((double) totalCoveredMethods / totalPublicMethods) * 100 : 0;

            // Build module data structure
            Map<String, ModuleData> moduleDataMap = new LinkedHashMap<>();

            for (Map.Entry<String, Set<MethodCall>> entry : publicApiByModule.entrySet()) {
                String modulePath = entry.getKey();
                Set<MethodCall> moduleMethods = entry.getValue();

                // Group methods by class
                Map<String, List<MethodCall>> methodsByClass = moduleMethods.stream()
                    .collect(Collectors.groupingBy(MethodCall::getClassName));

                // Calculate class-level coverage
                Map<String, ClassData> classDataMap = new LinkedHashMap<>();
                int moduleCovered = 0;

                for (Map.Entry<String, List<MethodCall>> classEntry : methodsByClass.entrySet()) {
                    String className = classEntry.getKey();
                    List<MethodCall> classMethods = classEntry.getValue();

                    List<MethodData> methodDataList = new ArrayList<>();
                    int classCovered = 0;

                    for (MethodCall method : classMethods) {
                        boolean isCovered = goldenImageMethodCalls.stream()
                            .anyMatch(gm -> methodsMatch(method, gm));

                        methodDataList.add(new MethodData(
                            method.getMethodName(),
                            method.getSignature(),
                            isCovered
                        ));

                        if (isCovered) {
                            classCovered++;
                        }
                    }

                    moduleCovered += classCovered;
                    double classCoverage = classMethods.size() > 0 ?
                        ((double) classCovered / classMethods.size()) * 100 : 0;

                    classDataMap.put(className, new ClassData(
                        className,
                        classMethods.size(),
                        classCovered,
                        classCoverage,
                        methodDataList
                    ));
                }

                double moduleCoverage = moduleMethods.size() > 0 ?
                    ((double) moduleCovered / moduleMethods.size()) * 100 : 0;

                moduleDataMap.put(modulePath, new ModuleData(
                    modulePath,
                    moduleMethods.size(),
                    moduleCovered,
                    moduleCoverage,
                    classDataMap
                ));
            }

            // Generate HTML
            String html = generateHtmlContent(
                totalPublicMethods,
                totalCoveredMethods,
                overallCoverage,
                moduleDataMap
            );

            // Write to file
            File outputFile = new File("azure-openrewrite-compiler-maven-plugin", "coverage-report.html");
            Files.write(outputFile.toPath(), html.getBytes());
            getLog().info("HTML coverage report written to: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            getLog().error("Failed to generate HTML report", e);
        }
    }

    private String generateHtmlContent(int totalMethods, int coveredMethods,
                                       double overallCoverage,
                                       Map<String, ModuleData> modules) {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>Azure SDK Golden Image Coverage Report</title>\n");
        html.append("    <style>\n");
        html.append(getStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append("        <h1>Azure SDK Golden Image Coverage Report</h1>\n");

        // Overall coverage section
        html.append("        <div class=\"overall-coverage\">\n");
        html.append("            <h2>Overall Coverage</h2>\n");
        html.append("            <div class=\"metric-card\">\n");
        html.append("                <div class=\"metric-value\">").append(String.format("%.1f%%", overallCoverage)).append("</div>\n");
        html.append("                <div class=\"metric-label\">").append(coveredMethods).append(" / ").append(totalMethods).append(" methods covered</div>\n");
        html.append("                <div class=\"progress-bar\">\n");
        html.append("                    <div class=\"progress-fill\" style=\"width: ").append(String.format("%.1f%%", overallCoverage)).append("\"></div>\n");
        html.append("                </div>\n");
        html.append("            </div>\n");
        html.append("        </div>\n");

        // Module list
        html.append("        <div class=\"modules\">\n");
        html.append("            <h2>Modules</h2>\n");

        for (Map.Entry<String, ModuleData> moduleEntry : modules.entrySet()) {
            ModuleData module = moduleEntry.getValue();
            html.append(generateModuleHtml(module));
        }

        html.append("        </div>\n");
        html.append("    </div>\n");
        html.append(getJavaScript());
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    private String generateModuleHtml(ModuleData module) {
        StringBuilder html = new StringBuilder();
        String moduleId = sanitizeId(module.name);

        html.append("            <div class=\"module\">\n");
        html.append("                <div class=\"module-header\" onclick=\"toggleSection('").append(moduleId).append("')\">\n");
        html.append("                    <span class=\"toggle-icon\" id=\"icon-").append(moduleId).append("\">▶</span>\n");
        html.append("                    <span class=\"module-name\">").append(escapeHtml(module.name)).append("</span>\n");
        html.append("                    <span class=\"coverage-badge\">").append(String.format("%.1f%%", module.coverage)).append("</span>\n");
        html.append("                    <span class=\"method-count\">").append(module.coveredMethods).append(" / ").append(module.totalMethods).append(" methods</span>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"module-content\" id=\"").append(moduleId).append("\" style=\"display: none;\">\n");

        for (Map.Entry<String, ClassData> classEntry : module.classes.entrySet()) {
            ClassData classData = classEntry.getValue();
            html.append(generateClassHtml(classData, moduleId));
        }

        html.append("                </div>\n");
        html.append("            </div>\n");

        return html.toString();
    }

    private String generateClassHtml(ClassData classData, String moduleId) {
        StringBuilder html = new StringBuilder();
        String classId = sanitizeId(moduleId + "-" + classData.className);

        html.append("                    <div class=\"class\">\n");
        html.append("                        <div class=\"class-header\" onclick=\"toggleSection('").append(classId).append("')\">\n");
        html.append("                            <span class=\"toggle-icon\" id=\"icon-").append(classId).append("\">▶</span>\n");
        html.append("                            <span class=\"class-name\">").append(escapeHtml(classData.className)).append("</span>\n");
        html.append("                            <span class=\"coverage-badge\">").append(String.format("%.1f%%", classData.coverage)).append("</span>\n");
        html.append("                            <span class=\"method-count\">").append(classData.coveredMethods).append(" / ").append(classData.totalMethods).append(" methods</span>\n");
        html.append("                        </div>\n");
        html.append("                        <div class=\"class-content\" id=\"").append(classId).append("\" style=\"display: none;\">\n");
        html.append("                            <table class=\"methods-table\">\n");
        html.append("                                <thead>\n");
        html.append("                                    <tr>\n");
        html.append("                                        <th>Method</th>\n");
        html.append("                                        <th>Signature</th>\n");
        html.append("                                    </tr>\n");
        html.append("                                </thead>\n");
        html.append("                                <tbody>\n");

        for (MethodData method : classData.methods) {
            String statusClass = method.isCovered ? "covered" : "uncovered";

            html.append("                                    <tr class=\"").append(statusClass).append("\">\n");
            html.append("                                        <td>").append(escapeHtml(method.methodName)).append("</td>\n");
            html.append("                                        <td><code>").append(escapeHtml(method.signature)).append("</code></td>\n");
            html.append("                                    </tr>\n");
        }

        html.append("                                </tbody>\n");
        html.append("                            </table>\n");
        html.append("                        </div>\n");
        html.append("                    </div>\n");

        return html.toString();
    }

    private String getStyles() {
        return "* { margin: 0; padding: 0; box-sizing: border-box; }\n" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; background: #f5f5f5; color: #333; line-height: 1.6; }\n" +
            ".container { max-width: 1200px; margin: 0 auto; padding: 20px; }\n" +
            "h1 { margin-bottom: 30px; color: #2c3e50; }\n" +
            "h2 { margin: 20px 0 15px 0; color: #34495e; font-size: 1.5em; }\n" +
            ".overall-coverage { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 30px; }\n" +
            ".metric-card { text-align: center; }\n" +
            ".metric-value { font-size: 3em; font-weight: bold; color: #3498db; margin-bottom: 10px; }\n" +
            ".metric-label { font-size: 1.1em; color: #7f8c8d; margin-bottom: 20px; }\n" +
            ".progress-bar { width: 100%; height: 30px; background: #ecf0f1; border-radius: 15px; overflow: hidden; }\n" +
            ".progress-fill { height: 100%; background: linear-gradient(90deg, #3498db, #2ecc71); transition: width 0.3s ease; }\n" +
            ".modules { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
            ".module { border: 1px solid #e0e0e0; border-radius: 6px; margin-bottom: 15px; overflow: hidden; }\n" +
            ".module-header, .class-header { padding: 15px; background: #f8f9fa; cursor: pointer; display: flex; align-items: center; gap: 10px; transition: background 0.2s; }\n" +
            ".module-header:hover, .class-header:hover { background: #e9ecef; }\n" +
            ".toggle-icon { font-size: 0.8em; width: 20px; transition: transform 0.2s; }\n" +
            ".toggle-icon.expanded { transform: rotate(90deg); }\n" +
            ".module-name, .class-name { flex: 1; font-weight: 600; }\n" +
            ".coverage-badge { padding: 4px 12px; border-radius: 12px; font-size: 0.9em; font-weight: bold; background: #3498db; color: white; }\n" +
            ".method-count { color: #7f8c8d; font-size: 0.9em; }\n" +
            ".module-content, .class-content { display: none; }\n" +
            ".class { margin: 10px; border: 1px solid #e0e0e0; border-radius: 4px; }\n" +
            ".class-header { background: #ffffff; }\n" +
            ".methods-table { width: 100%; border-collapse: collapse; margin: 10px; }\n" +
            ".methods-table th { background: #f8f9fa; padding: 12px; text-align: left; font-weight: 600; border-bottom: 2px solid #dee2e6; }\n" +
            ".methods-table td { padding: 5px 12px; border-bottom: 1px solid #e9ecef; }\n" +
            ".methods-table tr.covered { background: #f0f8f2; }\n" +
            ".methods-table tr.uncovered { background: #fff5f5; }\n" +
            ".status-badge { padding: 3px 8px; border-radius: 4px; font-size: 0.85em; font-weight: 600; }\n" +
            ".status-badge.covered { background: #d4edda; color: #155724; }\n" +
            ".status-badge.uncovered { background: #f8d7da; color: #721c24; }\n" +
            "code { font-family: 'Courier New', monospace; font-size: 0.9em; background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }\n";
    }

    private String getJavaScript() {
        return "<script>\n" +
            "function toggleSection(id) {\n" +
            "    const content = document.getElementById(id);\n" +
            "    const icon = document.getElementById('icon-' + id);\n" +
            "    if (content.style.display === 'none') {\n" +
            "        content.style.display = 'block';\n" +
            "        icon.classList.add('expanded');\n" +
            "    } else {\n" +
            "        content.style.display = 'none';\n" +
            "        icon.classList.remove('expanded');\n" +
            "    }\n" +
            "}\n" +
            "</script>\n";
    }

    private String sanitizeId(String input) {
        return input.replaceAll("[^a-zA-Z0-9-]", "-");
    }

    private String escapeHtml(String input) {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    // Data classes
    private static class ModuleData {
        String name;
        int totalMethods;
        int coveredMethods;
        double coverage;
        Map<String, ClassData> classes;

        ModuleData(String name, int totalMethods, int coveredMethods, double coverage, Map<String, ClassData> classes) {
            this.name = name;
            this.totalMethods = totalMethods;
            this.coveredMethods = coveredMethods;
            this.coverage = coverage;
            this.classes = classes;
        }
    }

    private static class ClassData {
        String className;
        int totalMethods;
        int coveredMethods;
        double coverage;
        List<MethodData> methods;

        ClassData(String className, int totalMethods, int coveredMethods, double coverage, List<MethodData> methods) {
            this.className = className;
            this.totalMethods = totalMethods;
            this.coveredMethods = coveredMethods;
            this.coverage = coverage;
            this.methods = methods;
        }
    }

    private static class MethodData {
        String methodName;
        String signature;
        boolean isCovered;

        MethodData(String methodName, String signature, boolean isCovered) {
            this.methodName = methodName;
            this.signature = signature;
            this.isCovered = isCovered;
        }
    }
}
