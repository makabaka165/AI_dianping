package com.hmdp.ai.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MimeValidationTest {
    @Test
    void rejectsDeclaredMimeThatConflictsWithDetectedContent() {
        ParserRegistry registry = new ParserRegistry(Arrays.asList(new TextDocumentParser(), new PdfDocumentParser()));
        assertThatThrownBy(() -> registry.inspect("plain".getBytes(StandardCharsets.UTF_8),
                "plain.txt", "application/pdf")).hasMessageContaining("MIME_MISMATCH");
    }
}
