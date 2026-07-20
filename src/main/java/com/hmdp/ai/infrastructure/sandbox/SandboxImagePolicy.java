package com.hmdp.ai.infrastructure.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SandboxImagePolicy {
    private final Set<String> allowedImages;

    public SandboxImagePolicy(@Value("${hmdp.ai.sandbox.allowed-images:}") String configuredImages) {
        if (configuredImages == null || configuredImages.trim().isEmpty()) {
            this.allowedImages = Collections.emptySet();
        } else {
            Set<String> images = Arrays.stream(configuredImages.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            this.allowedImages = Collections.unmodifiableSet(images);
        }
    }

    public void requireAllowed(String image) {
        if (image == null || !image.matches("[A-Za-z0-9._/:@-]{1,200}")) {
            throw new IllegalArgumentException("SANDBOX_IMAGE_INVALID");
        }
        if (!allowedImages.contains(image)) {
            throw new IllegalArgumentException("SANDBOX_IMAGE_NOT_ALLOWED");
        }
    }
}
