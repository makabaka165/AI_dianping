package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserTest {
    @Test
    void preservesHeadingPath() throws Exception {
        byte[] bytes = "# Root\nintro\n## Child\ndetail".getBytes(StandardCharsets.UTF_8);
        ParsedDocument parsed = new MarkdownDocumentParser().parse(new ByteArrayInputStream(bytes),
                ParserTestFixtures.context("policy.md", "text/markdown", bytes.length));
        assertThat(parsed.getSections())
                .anyMatch(section -> section.getHeadingPath().contains("Child") && section.getText().contains("detail"));
    }
}
