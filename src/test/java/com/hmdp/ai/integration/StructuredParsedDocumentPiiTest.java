package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.parsing.ParseWarning;
import com.hmdp.ai.domain.knowledge.parsing.ParsedCell;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedSection;
import com.hmdp.ai.domain.knowledge.parsing.ParsedTable;
import com.hmdp.ai.runtime.knowledge.StructuredDocumentRedactor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredParsedDocumentPiiTest {
    @Test
    void recursivelyRemovesPiiFromPersistableStructuredJsonIncludingMetadataText() throws Exception {
        ParsedSection section = new ParsedSection("Call 13800138000",
                "Identity 110101199001011234 belongs to owner@example.com", 2,
                Arrays.asList("Contacts", "owner@example.com"), 10, 80);
        ParsedTable table = new ParsedTable("Customer 13800138000", 3,
                Collections.singletonList(new ParsedCell(1, 1, "A1", "owner@example.com")));
        ParsedDocument source = new ParsedDocument("owner@example.com", "application/pdf",
                Collections.singletonList(section), Collections.singletonList(table),
                Collections.singletonList(new ParseWarning("OCR_NOTE", "review 110101199001011234")));

        ParsedDocument redacted = new StructuredDocumentRedactor().redact(source);
        String persistedJson = new ObjectMapper().writeValueAsString(redacted);

        assertThat(persistedJson)
                .doesNotContain("13800138000", "110101199001011234", "owner@example.com")
                .contains("[REDACTED_PHONE]", "[REDACTED_ID]", "[REDACTED_EMAIL]");
        assertThat(source.getSections().get(0).getText()).contains("110101199001011234");
    }
}
