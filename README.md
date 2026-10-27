# Gradle Offline Dependencies Plugin

[![Build Status](https://github.com/infosmid-org/gradle-offline-deps/actions/workflows/snapshot.yml/badge.svg)](https://github.com/infosmid-org/gradle-offline-deps/actions/workflows/snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.infosmid.plugins/gradle-offline-deps.svg)](https://central.sonatype.com/artifact/org.infosmid.plugins/gradle-offline-deps)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Gradle plugin that resolves all dependencies of a project and its subprojects and copies them into a local Maven repository (by default `~/.m2/repository`), creating a self-contained offline dependency repository.

---

## Features

- **Multi-Project Support**: Applying the plugin to the root project automatically applies it across all subprojects and chains tasks so a single invocation collects dependencies for the entire project tree.
- **Recursive POM & BOM Resolution**: Automatically walks the Maven `<parent>` POM hierarchy and all `import`-scoped BOMs (`<dependencyManagement>`), ensuring all metadata needed for offline resolution is copied.
- **Buildscript Dependencies**: Collects dependencies declared in `buildscript { ... }` blocks (such as build plugins) by default.
- **Gradle Module Cache Integration**: Extracts artifacts and associated sibling metadata files (POMs, Gradle module metadata `.module`, sources) directly from the Gradle cache into standard Maven repository structure.
- **Incremental & Repeatable**: Existing identical files are skipped on subsequent runs.
- **Atomic File Operations**: Uses atomic writes to prevent partially written or corrupt artifacts in the repository.

---

## Applying the Plugin

Add the plugin to your root `build.gradle` or `build.gradle.kts`:

### Groovy DSL

```groovy
plugins {
    id 'org.infosmid.plugins.gradle-offline-deps' version '1.0.1'
}
```

### Kotlin DSL

```kotlin
plugins {
    id("org.infosmid.plugins.gradle-offline-deps") version "1.0.1"
}
```

### Version Catalog
```toml
[plugins]
gradle-offline = { id = "org.infosmid.plugins.gradle-offline-deps", version = "1.0.1" }
```
```groovy
plugins {
    alias(libs.plugins.gradle.offline)
}
```

---

## Configuration

You can configure the plugin behavior using the `gradleOfflineDeps` extension block:

### Configuration Options

| Property | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `mavenLocalPath` | `Property<String>` | `~/.m2/repository` | Target directory path for the Maven repository. Supports absolute paths, relative paths (resolved against the root project directory), and `~` home expansion. |
| `includeBuildscript` | `Property<Boolean>` | `true` | Whether to include buildscript classpath configurations (plugins and buildscript dependencies). |

### Configuration Examples

#### Groovy DSL (`build.gradle`)

```groovy
gradleOfflineDeps {
    // Custom target repository directory (e.g. inside project build folder)
    mavenLocalPath = "${rootProject.projectDir}/.m2/repository"

    // Include or exclude buildscript classpath dependencies
    includeBuildscript = true
}
```

#### Kotlin DSL (`build.gradle.kts`)

```kotlin
gradleOfflineDeps {
    // Custom target repository directory
    mavenLocalPath.set("${rootProject.projectDir}/.m2/repository")

    // Include or exclude buildscript classpath dependencies
    includeBuildscript.set(true)
}
```

---

## Prepare for offline use

Run the `fetchOfflineDeps` task:

```shell
./gradlew fetchOfflineDeps
```

This task resolves all resolvable configurations and copies the artifacts and POMs to your configured local repository (defaults to `~/.m2/repository`).

---

## Using the Offline Repository

To build your project in an offline environment using the populated repository:

### In Gradle

In your `repositories` block (e.g., in `build.gradle` or `settings.gradle`):

```groovy
repositories {
    mavenLocal() // or maven { url = uri("${rootProject.projectDir}/.m2/repository") }
}
```

Then execute your build with the `--offline` flag:

```shell
./gradlew build --offline
```

---

## Requirements

- **Java**: 17 or higher
- **Gradle**: 8.x or 9.x

---

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE).
