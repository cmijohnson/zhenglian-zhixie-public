package com.yingge.cloud.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class McpCapabilityManifestTest {
    private static final Pattern METHOD = Pattern.compile("public\\s+[\\w<>?,.\\[\\]\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern MANIFEST_OPERATION = Pattern.compile("operation:\\s*([A-Za-z0-9_]+#[A-Za-z0-9_]+)");

    @Test
    void everyControllerOperationIsExplicitlyClassified() throws Exception {
        Path source = Path.of("src/main/java/com/yingge/cloud");
        Set<String> controllerOperations = new LinkedHashSet<>();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith("Controller.java") && !name.startsWith("._");
            }).toList()) {
                boolean mapping = false;
                String className = file.getFileName().toString().replace(".java", "");
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("@GetMapping") || trimmed.startsWith("@PostMapping")
                            || trimmed.startsWith("@PutMapping") || trimmed.startsWith("@PatchMapping")
                            || trimmed.startsWith("@DeleteMapping")) {
                        mapping = true;
                    }
                    if (mapping) {
                        var matcher = METHOD.matcher(trimmed);
                        if (matcher.find()) {
                            controllerOperations.add(className + "#" + matcher.group(1));
                            mapping = false;
                        }
                    }
                }
            }
        }

        String manifest = Files.readString(Path.of("src/main/resources/mcp-capability-manifest.yml"));
        Set<String> registered = new LinkedHashSet<>();
        var matcher = MANIFEST_OPERATION.matcher(manifest);
        while (matcher.find()) registered.add(matcher.group(1));

        assertThat(controllerOperations).as("controller method discovery must stay active")
                .hasSizeGreaterThan(90);
        assertThat(registered).as("manifest should classify the complete API surface")
                .hasSizeGreaterThan(90);
        assertThat(registered).as("manifest cannot contain stale controller methods")
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(controllerOperations);
        assertThat(manifest).contains("classification: TOOL", "classification: RESOURCE",
                "classification: CONTROL_PLANE", "classification: DEFERRED_FILES");
    }
}
