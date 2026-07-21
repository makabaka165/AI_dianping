package com.hmdp.ai.contract;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiControllerContractTest {
    @Test
    void openApiCoversEveryAgentPlatformControllerPathAndStableRunIdentity() throws Exception {
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(Path.of("docs/api/openapi.yaml"))) {
            document = new Yaml().load(input);
        }
        Map<String, Object> paths = map(document.get("paths"));
        Set<String> controllerPaths = controllerPaths();
        assertThat(paths.keySet()).containsAll(controllerPaths);

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemes = map(components.get("securitySchemes"));
        assertThat(map(schemes.get("bearerAuth")))
                .containsEntry("type", "http")
                .containsEntry("scheme", "bearer");

        Map<String, Object> runCreated = map(map(components.get("schemas")).get("RunCreated"));
        Map<String, Object> properties = map(runCreated.get("properties"));
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "runId", "status", "agentDefinitionId", "agentCode", "agentVersion");
        assertThat(properties).doesNotContainKey("agentId");
    }

    private Set<String> controllerPaths() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> result = new LinkedHashSet<>();
        for (org.springframework.beans.factory.config.BeanDefinition bean
                : scanner.findCandidateComponents("com.hmdp.ai.api")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            List<String> prefixes = mappings(type);
            if (prefixes.isEmpty()) prefixes = Collections.singletonList("");
            for (Method method : type.getDeclaredMethods()) {
                List<String> suffixes = mappings(method);
                for (String prefix : prefixes) {
                    for (String suffix : suffixes) result.add(join(prefix, suffix));
                }
            }
        }
        return result;
    }

    private List<String> mappings(java.lang.reflect.AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) return Collections.emptyList();
        String[] values = mapping.path().length == 0 ? mapping.value() : mapping.path();
        if (values.length == 0) return Collections.singletonList("");
        List<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
    }

    private String join(String prefix, String suffix) {
        String value = (prefix + "/" + suffix).replaceAll("/{2,}", "/");
        if (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
