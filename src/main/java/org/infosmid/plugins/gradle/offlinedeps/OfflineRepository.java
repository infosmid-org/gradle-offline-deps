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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * A Maven-layout directory that artifacts are copied into.
 */
final class OfflineRepository {
    private final Path root;
    private int copied;
    private int unchanged;

    OfflineRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    int copied() {
        return copied;
    }

    int unchanged() {
        return unchanged;
    }

    Path directoryOf(Gav gav) {
        return root.resolve(gav.group().replace('.', '/')).resolve(gav.name()).resolve(gav.version());
    }

    Path pomOf(Gav gav) {
        return directoryOf(gav).resolve(gav.name() + "-" + gav.version() + ".pom");
    }

    /**
     * Stores a resolved file. When the file comes from the Gradle module cache
     * ({@code <group>/<name>/<version>/<hash>/<file>}) all sibling files of the version (pom, Gradle module
     * metadata, sources, ...) are stored with it.
     */
    void store(Gav gav, File file) {
        try {
            Path target = directoryOf(gav);
            Path versionDir = cacheVersionDirectory(gav, file);
            if (versionDir == null) {
                copy(file.toPath(), target.resolve(file.getName()));
                return;
            }
            try (Stream<Path> hashDirs = Files.list(versionDir)) {
                for (Path hashDir : (Iterable<Path>) hashDirs.filter(Files::isDirectory)::iterator) {
                    try (Stream<Path> files = Files.list(hashDir)) {
                        for (Path source : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                            copy(source, target.resolve(source.getFileName().toString()));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store " + file + " for " + gav, e);
        }
    }

    private static Path cacheVersionDirectory(Gav gav, File file) {
        File hashDir = file.getParentFile();
        File versionDir = hashDir == null ? null : hashDir.getParentFile();
        File nameDir = versionDir == null ? null : versionDir.getParentFile();
        File groupDir = nameDir == null ? null : nameDir.getParentFile();
        if (groupDir != null
                && versionDir.getName().equals(gav.version())
                && nameDir.getName().equals(gav.name())
                && groupDir.getName().equals(gav.group())) {
            return versionDir.toPath();
        }
        return null;
    }

    private synchronized void copy(Path source, Path destination) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (normalizedSource.equals(destination) || normalizedSource.startsWith(root)) {
            unchanged++;
            return;
        }
        if (Files.isRegularFile(destination) && Files.size(destination) == Files.size(source)) {
            unchanged++;
            return;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".offline-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        copied++;
    }
}
