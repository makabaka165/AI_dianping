package com.hmdp.ai.infrastructure.model;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class EnvironmentSecretResolver implements SecretResolver {
    private static final String PREFIX = "env:";
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,127}");
    private final Environment environment;

    public EnvironmentSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean supports(String secretReference) {
        return secretReference != null && secretReference.startsWith(PREFIX);
    }

    @Override
    public String resolve(String secretReference) {
        Objects.requireNonNull(secretReference, "secretReference");
        if (!supports(secretReference)) {
            throw new IllegalArgumentException("unsupported secret reference");
        }
        String variable = secretReference.substring(PREFIX.length());
        if (!VARIABLE_NAME.matcher(variable).matches()) {
            throw new IllegalArgumentException("invalid environment secret reference");
        }
        String value = environment.getProperty(variable);
        if (value == null || value.trim().isEmpty()) {
            throw new SecretNotConfiguredException(variable);
        }
        return value;
    }
}
