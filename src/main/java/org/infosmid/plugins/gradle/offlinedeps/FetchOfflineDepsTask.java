/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.infosmid.plugins.gradle.offlinedeps;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves every resolvable configuration of the project (and its {@code buildscript}) and copies the
 * resolved modules, their POMs, parent POMs and imported BOMs to a Maven-layout directory.
 */
@UntrackedTask(because = "Copies resolved artifacts to a directory outside the project that may change independently")
public abstract class FetchOfflineDepsTask extends DefaultTask {

    /** Target Maven repository directory. */
    @Internal
    public abstract Property<String> getMavenLocalPath();

    /** Whether to include the {@code buildscript} configurations. */
    @Internal
    public abstract Property<Boolean> getIncludeBuildscript();

    // Captured at configuration time: Task.getProject() at execution time is deprecated. The task resolves
    // configurations of its project when it runs, so it is declared incompatible with the configuration cache.
    private final Project project;

    public FetchOfflineDepsTask() {
        this.project = getProject();
        setGroup("build setup");
        setDescription("Resolves the dependencies of all configurations and copies them to a local Maven repository.");
        notCompatibleWithConfigurationCache("Resolves configurations of the project at execution time.");
    }

    @TaskAction
    public void fetch() {
        OfflineRepository repository = new OfflineRepository(resolveTarget(project, getMavenLocalPath().get()));
        Set<Gav> components = new LinkedHashSet<>();
        int[] failures = {0};

        collect(project.getConfigurations(), "", repository, components, failures);
        if (getIncludeBuildscript().getOrElse(true)) {
            collect(project.getBuildscript().getConfigurations(), "buildscript ", repository, components, failures);
        }

        PomWalker walker = new PomWalker(gav -> fetchPom(project, repository, gav));
        for (Gav component : components) {
            walker.walk(component);
        }
        for (Gav missing : walker.missing()) {
            getLogger().info("No POM found for {}", missing);
        }

        getLogger().lifecycle("[{}] {} modules, {} files copied to {} ({} already present, {} configuration failures)",
                project.getPath(), components.size(), repository.copied(), repository.root(),
                repository.unchanged(), failures[0]);
    }

    private void collect(ConfigurationContainer container, String label, OfflineRepository repository,
                         Set<Gav> components, int[] failures) {
        for (Configuration configuration : new ArrayList<>(container)) {
            if (!configuration.isCanBeResolved()) {
                continue;
            }
            try {
                collect(configuration, repository, components);
            } catch (RuntimeException e) {
                failures[0]++;
                getLogger().warn("Cannot resolve {}configuration '{}': {}", label, configuration.getName(),
                        rootMessage(e));
            }
        }
    }

    private void collect(Configuration configuration, OfflineRepository repository, Set<Gav> components) {
        LenientConfiguration lenient = configuration.getResolvedConfiguration().getLenientConfiguration();
        for (ResolvedArtifact artifact : lenient.getArtifacts()) {
            if (!(artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier)) {
                continue;
            }
            ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
            Gav gav = new Gav(id.getGroup(), id.getName(), id.getVersion());
            repository.store(gav, artifact.getFile());
            components.add(gav);
        }
        for (UnresolvedDependency unresolved : lenient.getUnresolvedModuleDependencies()) {
            getLogger().warn("Unresolved in '{}': {}", configuration.getName(), unresolved.getSelector());
        }
        // BOMs and other modules without artifacts only show up in the resolution graph
        for (ResolvedComponentResult component : configuration.getIncoming().getResolutionResult().getAllComponents()) {
            if (component.getId() instanceof ModuleComponentIdentifier module) {
                components.add(new Gav(module.getGroup(), module.getModule(), module.getVersion()));
            }
        }
    }

    private Path fetchPom(Project project, OfflineRepository repository, Gav gav) {
        Path pom = repository.pomOf(gav);
        if (Files.isRegularFile(pom)) {
            return pom;
        }
        try {
            Dependency dependency = project.getDependencies().create(gav + "@pom");
            Configuration detached = project.getConfigurations().detachedConfiguration(dependency);
            detached.setTransitive(false);
            for (ResolvedArtifact artifact : detached.getResolvedConfiguration().getLenientConfiguration().getArtifacts()) {
                File file = artifact.getFile();
                repository.store(gav, file);
                return Files.isRegularFile(pom) ? pom : file.toPath();
            }
        } catch (RuntimeException e) {
            getLogger().debug("Cannot resolve POM of {}", gav, e);
        }
        return null;
    }

    private static Path resolveTarget(Project project, String configured) {
        String path = configured;
        if (path.equals("~") || path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        Path result = Paths.get(path);
        return result.isAbsolute() ? result : project.getRootDir().toPath().resolve(result);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
