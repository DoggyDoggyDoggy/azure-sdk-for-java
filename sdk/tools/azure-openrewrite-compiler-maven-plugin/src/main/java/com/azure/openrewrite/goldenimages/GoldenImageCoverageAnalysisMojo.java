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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // --- Golden Image Analysis (Existing Functionality) ---
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

        // --- Public API Analysis (New Functionality) ---
        Set<MethodCall> publicApiMethodCalls = analyzePublicApi();
        outputMethodCallsToFile(publicApiMethodCalls, "public-api-method-usage.json");
    }

    private Set<MethodCall> analyzePublicApi() throws MojoExecutionException {
        getLog().info("Starting public API analysis for Azure V2 SDKs.");

        // Resolve the root of the repository from the plugin's base directory
        File repoRoot = baseDir.getParentFile().getParentFile();

        // Define the relative paths to the source directories
        List<String> relativePaths = Arrays.asList(
            "appconfiguration-v2/azure-data-appconfiguration/src/main/java/com/azure/v2/data/appconfiguration",
            "core-v2/azure-core/src/main/java/com/azure/v2/core",
            "clientcore/core/src/main/java/io/clientcore/core",
            "identity-v2/azure-identity/src/main/java/com/azure/v2/identity",
            "keyvault-v2/azure-security-keyvault-keys/src/samples/java/com/azure/v2/security/keyvault/keys",
            "keyvault-v2/azure-security-keyvault-administration/src/main/java/com/azure/v2/security/keyvault/administration",
            "keyvault-v2/azure-security-keyvault-certificates/src/main/java/com/azure/v2/security/keyvault/certificates",
            "keyvault-v2/azure-security-keyvault-secrets/src/main/java/com/azure/v2/security/keyvault/secrets"
        );

        Set<MethodCall> allDeclaredMethods = new HashSet<>();
        List<String> classpathElements = getClasspathForProfile("v2");

        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        for (String path : relativePaths) {
            File sourceDir = new File(repoRoot, path);
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

        for (String path : relativePaths) {
            File sourceDir = new File(repoRoot, path);
            if (sourceDir.exists()) {
                try {
                    allDeclaredMethods.addAll(extractDeclaredMethodsFromDirectory(sourceDir, javaParser));
                } catch (IOException e) {
                    getLog().error("Failed to analyze source directory " + sourceDir.getAbsolutePath(), e);
                }
            } else {
                getLog().warn("Skipping missing source directory: " + sourceDir.getAbsolutePath());
            }
        }

        getLog().info("Total unique public API methods found: " + allDeclaredMethods.size());
        return allDeclaredMethods;
    }

    private Set<MethodCall> extractDeclaredMethodsFromDirectory(File directory, JavaParser javaParser) throws IOException {
        Set<MethodCall> declaredMethods = new HashSet<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            List<Path> javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    boolean isImplementation = path.toString().contains("/implementation/");
                    if (isImplementation) {
                        getLog().info("Skipping implementation file: " + path.toString());
                    }
                    return !isImplementation;
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
                    String signature = buildMethodSignature(resolved);
                    MethodCall methodCall = new MethodCall(className, methodName, signature, sourceFile);
                    declaredMethods.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve method declaration: " + n.getNameAsString() + " in " + sourceFile);
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
                    String signature = buildMethodSignature(resolved);
                    MethodCall methodCall = new MethodCall(className, methodName, signature, sourceFile);
                    methodCalls.add(methodCall);
                } catch (Exception e) {
                    getLog().debug("Could not resolve method call: " + n.getNameAsString() + " in " + sourceFile);
                }
                super.visit(n, arg);
            }
        }, null);
        return methodCalls;
    }

//    private boolean shouldIncludeMethod(String methodName) {
//        if (methodName.startsWith("get") || methodName.startsWith("set") || methodName.startsWith("is")) {
//            return false;
//        }
//        if (methodName.equals("toString") || methodName.equals("equals") ||
//            methodName.equals("hashCode") || methodName.equals("clone")) {
//            return false;
//        }
//        return true;
//    }

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

    private void outputMethodCallsToFile(Set<MethodCall> methodCalls, String filename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            methodCalls.stream()
                .collect(Collectors.groupingBy(MethodCall::getClassName))
                .forEach((className, calls) -> {
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
            File outputFile = new File(baseDir, filename);
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, root);
            getLog().info("Method usage analysis written to: " + outputFile.getAbsolutePath());
            getLog().info("Total unique method calls found in " + filename + ": " + methodCalls.size());
            getLog().info("Classes with method calls: " +
                methodCalls.stream().map(MethodCall::getClassName).collect(Collectors.toSet()).size());
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
