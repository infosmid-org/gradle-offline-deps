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

import org.gradle.api.provider.Property;

/**
 * Configuration of the {@code org.infosmid.plugins.gradle-offline-deps} plugin.
 *
 * <pre>
 * gradleOfflineDeps {
 *     mavenLocalPath = "/some/path"
 * }
 * </pre>
 */
public abstract class GradleOfflineDepsExtension {

    /**
     * Constructs a new {@code GradleOfflineDepsExtension}.
     */
    public GradleOfflineDepsExtension() {
    }

    /**
     * Directory of the Maven repository that receives the dependencies.
     * Defaults to {@code ~/.m2/repository}. A relative path is resolved against the root project directory
     * and a leading {@code ~/} is expanded to the user home.
     *
     * @return the property that contains the Maven repository path
     */
    public abstract Property<String> getMavenLocalPath();

    /**
     * Whether the {@code buildscript} configurations (plugin classpath declared in {@code buildscript {}}) are
     * included. Defaults to {@code true}.
     *
     * @return the property that specifies if buildscript configurations are included
     */
    public abstract Property<Boolean> getIncludeBuildscript();
}
