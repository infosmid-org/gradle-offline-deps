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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Follows the {@code <parent>} chain and the {@code import} scoped BOMs of Maven POMs so that every POM
 * needed to interpret a module is available in the offline repository.
 */
final class PomWalker {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private record RawCoordinate(String group, String name, String version) {
    }

    private record PomInfo(Gav parent, Map<String, String> properties, List<RawCoordinate> imports) {
    }

    private final Function<Gav, Path> fetcher;
    private final Set<Gav> visited = new HashSet<>();
    private final Set<Gav> missing = new LinkedHashSet<>();
    private final Map<Gav, PomInfo> infos = new HashMap<>();

    /**
     * @param fetcher returns the local path of the POM of the module, or {@code null} when it is unavailable
     */
    PomWalker(Function<Gav, Path> fetcher) {
        this.fetcher = fetcher;
    }

    Set<Gav> missing() {
        return missing;
    }

    void walk(Gav gav) {
        if (!visited.add(gav)) {
            return;
        }
        Path pom = fetcher.apply(gav);
        if (pom == null) {
            missing.add(gav);
            return;
        }
        PomInfo info = parse(pom);
        if (info == null) {
            return;
        }
        infos.put(gav, info);
        if (info.parent() != null) {
            walk(info.parent());
        }
        Map<String, String> properties = effectiveProperties(gav);
        for (RawCoordinate imported : info.imports()) {
            String group = substitute(imported.group(), properties);
            String name = substitute(imported.name(), properties);
            String version = substitute(imported.version(), properties);
            if (group != null && name != null && version != null) {
                walk(new Gav(group, name, version));
            }
        }
    }

    private Map<String, String> effectiveProperties(Gav gav) {
        PomInfo info = infos.get(gav);
        Map<String, String> result = new HashMap<>();
        if (info == null) {
            return result;
        }
        if (info.parent() != null) {
            result.putAll(effectiveProperties(info.parent()));
        }
        result.putAll(info.properties());
        for (String prefix : new String[]{"project.", "pom."}) {
            result.put(prefix + "groupId", gav.group());
            result.put(prefix + "artifactId", gav.name());
            result.put(prefix + "version", gav.version());
        }
        return result;
    }

    private static String substitute(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String current = value;
        for (int i = 0; i < 10 && current.contains("${"); i++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) {
                    return null;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            current = out.toString();
        }
        return current.contains("${") ? null : current;
    }

    private static PomInfo parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document;
            try (var in = Files.newInputStream(pom)) {
                document = factory.newDocumentBuilder().parse(in);
            }
            Element project = document.getDocumentElement();

            Gav parent = null;
            Element parentElement = child(project, "parent");
            if (parentElement != null) {
                String group = text(parentElement, "groupId");
                String name = text(parentElement, "artifactId");
                String version = text(parentElement, "version");
                if (group != null && name != null && version != null) {
                    parent = new Gav(group, name, version);
                }
            }

            Map<String, String> properties = new HashMap<>();
            Element propertiesElement = child(project, "properties");
            if (propertiesElement != null) {
                for (Element property : children(propertiesElement)) {
                    properties.put(property.getTagName(), property.getTextContent().trim());
                }
            }

            List<RawCoordinate> imports = new ArrayList<>();
            Element management = child(project, "dependencyManagement");
            Element dependencies = management == null ? null : child(management, "dependencies");
            if (dependencies != null) {
                for (Element dependency : children(dependencies)) {
                    if ("import".equals(text(dependency, "scope")) && "pom".equals(text(dependency, "type"))) {
                        imports.add(new RawCoordinate(
                                text(dependency, "groupId"), text(dependency, "artifactId"), text(dependency, "version")));
                    }
                }
            }
            return new PomInfo(parent, properties, imports);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }
}
