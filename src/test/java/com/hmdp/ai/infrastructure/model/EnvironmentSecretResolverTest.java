package com.hmdp.ai.infrastructure.model;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentSecretResolverTest {

    @Test
    void resolvesOnlyExplicitEnvironmentReferences() {
        MockEnvironment environment = new MockEnvironment().withProperty("AI_CHAT_API_KEY", "secret-value");
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(environment);

        assertEquals("secret-value", resolver.resolve("env:AI_CHAT_API_KEY"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("plain-secret"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("env:bad-name"));
    }

    @Test
    void missingSecretDoesNotRevealReferenceValue() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(new MockEnvironment());

        SecretNotConfiguredException error = assertThrows(SecretNotConfiguredException.class,
                () -> resolver.resolve("env:AI_CHAT_API_KEY"));

        assertEquals("required secret is not configured", error.getMessage());
        assertEquals("AI_CHAT_API_KEY", error.getVariableName());
    }
}
