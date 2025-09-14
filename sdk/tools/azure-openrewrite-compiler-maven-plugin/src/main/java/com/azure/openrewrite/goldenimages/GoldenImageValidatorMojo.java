package com.azure.openrewrite.goldenimages;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;


@Mojo(name = "validate-golden-image", requiresDependencyResolution = ResolutionScope.TEST)
public class GoldenImageValidatorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info("Compiling and validating migration example sources...");

            GoldenImageValidator.runAllValidations(
                getLog(),
                baseDir.toPath(),
                project
            );

        } catch (Exception e) {
            throw new MojoExecutionException("Validation failed", e);
        }
    }
}

