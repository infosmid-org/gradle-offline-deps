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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.NonNull;

/**
 * Registers the {@code gradleOfflineDeps} extension and a {@code fetchOfflineDeps} task.
 * <p>
 * Applying the plugin to a project also applies it to all its subprojects. The task of the project the plugin
 * was applied to depends on the tasks of its subprojects so that one invocation fetches everything.
 */
@SuppressWarnings("unused")
public class GradleOfflineDepsPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "gradleOfflineDeps";
    public static final String TASK_NAME = "fetchOfflineDeps";
    public static final String DEFAULT_REPOSITORY = System.getProperty("user.home") + "/.m2/repository";

    @Override
    public void apply(@NonNull Project project) {
        GradleOfflineDepsExtension inherited = findInherited(project);
        GradleOfflineDepsExtension extension = inherited != null ? inherited : createExtension(project);

        TaskProvider<FetchOfflineDepsTask> task = project.getTasks().register(TASK_NAME, FetchOfflineDepsTask.class, t -> {
            t.getMavenLocalPath().convention(extension.getMavenLocalPath());
            t.getIncludeBuildscript().convention(extension.getIncludeBuildscript());
        });

        for (Project sub : project.getSubprojects()) {
            sub.getPluginManager().apply(GradleOfflineDepsPlugin.class);
            task.configure(t -> t.dependsOn(sub.getTasks().named(TASK_NAME)));
        }
    }

    private static GradleOfflineDepsExtension createExtension(Project project) {
        GradleOfflineDepsExtension extension =
                project.getExtensions().create(EXTENSION_NAME, GradleOfflineDepsExtension.class);
        extension.getMavenLocalPath().convention(DEFAULT_REPOSITORY);
        extension.getIncludeBuildscript().convention(true);
        return extension;
    }

    private static GradleOfflineDepsExtension findInherited(Project project) {
        for (Project parent = project.getParent(); parent != null; parent = parent.getParent()) {
            GradleOfflineDepsExtension found = parent.getExtensions().findByType(GradleOfflineDepsExtension.class);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
