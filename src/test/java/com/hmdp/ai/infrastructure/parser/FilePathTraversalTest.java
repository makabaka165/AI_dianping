package com.hmdp.ai.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePathTraversalTest {
    @Test
    void rejectsParentDirectorySegments() {
        ParserRegistry registry = new ParserRegistry(Collections.singletonList(new TextDocumentParser()));
        assertThatThrownBy(() -> registry.inspect("safe".getBytes(StandardCharsets.UTF_8),
                "../secret.txt", "text/plain")).hasMessageContaining("FILE_NAME_INVALID");
    }
}
