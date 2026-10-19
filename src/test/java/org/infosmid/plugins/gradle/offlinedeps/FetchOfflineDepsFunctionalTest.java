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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchOfflineDepsFunctionalTest {

    @TempDir
    Path dir;

    static Stream<String> gradleVersions() {
        return Arrays.stream(System.getProperty("testGradleVersions", "current").split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("gradleVersions")
    void copiesModulesPomsParentsAndBomsFromAllProjects(String gradleVersion) throws IOException {
        Path remote = createRemoteRepository();
        Path target = dir.resolve("target-m2");
        writeBuild(remote, "gradleOfflineDeps { mavenLocalPath = '" + target + "' }");

        BuildResult result = runner(gradleVersion, "fetchOfflineDeps").build();

        assertTrue(result.getOutput().contains(":app:"), result.getOutput());
        assertFile(target, "org/example/lib/2/lib-2.jar");
        assertFile(target, "org/example/lib/2/lib-2.pom");
        assertFile(target, "org/example/parent-pom/1/parent-pom-1.pom");
        assertFile(target, "org/example/bom/1/bom-1.pom");
        // only declared in a buildscript classpath
        assertFile(target, "org/example/tool/3/tool-3.jar");
    }

    @ParameterizedTest
    @MethodSource("gradleVersions")
    void isRepeatable(String gradleVersion) throws IOException {
        Path remote = createRemoteRepository();
        Path target = dir.resolve("target-m2");
        writeBuild(remote, "gradleOfflineDeps { mavenLocalPath = '" + target + "' }");

        runner(gradleVersion, "fetchOfflineDeps").build();
        BuildResult second = runner(gradleVersion, "fetchOfflineDeps").build();

        assertTrue(second.getOutput().contains("0 files copied"), second.getOutput());
    }

    @Test
    void defaultsToUserHomeMavenRepository() throws IOException {
        Path remote = createRemoteRepository();
        writeBuild(remote, "tasks.register('showPath') { doLast { println 'PATH=' + gradleOfflineDeps.mavenLocalPath.get() } }");

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.resolve("project").toFile())
                .withPluginClasspath()
                .withArguments("showPath", "-q")
                .build();

        assertTrue(result.getOutput().contains("PATH=" + System.getProperty("user.home") + "/.m2/repository"),
                result.getOutput());
    }

    private GradleRunner runner(String gradleVersion, String... arguments) {
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(dir.resolve("project").toFile())
                .withPluginClasspath()
                .withArguments(arguments);
        if (!"current".equals(gradleVersion)) {
            runner.withGradleVersion(gradleVersion);
        }
        return runner;
    }

    private void writeBuild(Path remote, String extensionConfig) throws IOException {
        Path project = dir.resolve("project");
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\ninclude 'app'\n");
        Files.writeString(project.resolve("build.gradle"), """
                buildscript {
                    repositories { maven { url = uri('%1$s') } }
                    dependencies { classpath 'org.example:tool:3' }
                }
                plugins { id 'org.infosmid.plugins.gradle-offline-deps' }
                %2$s
                """.formatted(slash(remote), extensionConfig));
        Files.writeString(project.resolve("app/build.gradle"), """
                plugins { id 'java-library' }
                repositories { maven { url = uri('%1$s') } }
                dependencies {
                    implementation platform('org.example:bom:1')
                    implementation 'org.example:lib'
                }
                """.formatted(slash(remote)));
    }

    private Path createRemoteRepository() throws IOException {
        Path remote = dir.resolve("remote");
        publish(remote, "org.example", "parent-pom", "1", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>parent-pom</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>2</lib.version></properties>
                </project>""", false);
        publish(remote, "org.example", "bom", "1", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.example</groupId><artifactId>parent-pom</artifactId><version>1</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>""", false);
        publish(remote, "org.example", "lib", "2", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.example</groupId><artifactId>parent-pom</artifactId><version>1</version></parent>
                  <artifactId>lib</artifactId><version>2</version>
                </project>""", true);
        publish(remote, "org.example", "tool", "3", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>tool</artifactId><version>3</version>
                </project>""", true);
        return remote;
    }

    private static void publish(Path remote, String group, String name, String version, String pom, boolean jar)
            throws IOException {
        Path base = remote.resolve(group.replace('.', '/')).resolve(name).resolve(version);
        Files.createDirectories(base);
        Files.writeString(base.resolve(name + "-" + version + ".pom"), pom);
        if (jar) {
            try (OutputStream out = Files.newOutputStream(base.resolve(name + "-" + version + ".jar"));
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write("Manifest-Version: 1.0\n".getBytes());
                zip.closeEntry();
            }
        }
    }

    private static void assertFile(Path root, String relative) {
        assertTrue(Files.isRegularFile(root.resolve(relative)), relative + " missing in " + root);
        assertEquals(relative, root.relativize(root.resolve(relative)).toString());
    }

    private static String slash(Path path) {
        return path.toUri().toString();
    }
}
