package com.hmdp.ai.regression;

import com.hmdp.ai.domain.knowledge.parsing.ParsedCell;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedSection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StructuredParsedDocumentPiiRegressionTest {
    @Test
    void structuredRedactorMustRemovePiiFromSectionsAndCells() throws Exception {
        Class<?> type = Class.forName("com.hmdp.ai.runtime.knowledge.StructuredDocumentRedactor");
        Object redactor = type.getConstructor().newInstance();
        Method redact = type.getMethod("redact", ParsedDocument.class);
        ParsedDocument document = new ParsedDocument("owner@example.com", "text/plain",
                Collections.singletonList(new ParsedSection("13800138000", "ID 110101199001011234",
                        null, Collections.singletonList("owner@example.com"), 0, 20)),
                Collections.emptyList(), Collections.emptyList());
        ParsedDocument result = (ParsedDocument) redact.invoke(redactor, document);
        assertFalse(result.getPlainText().contains("110101199001011234"));
        assertFalse(result.getTitle().contains("@example.com"));
    }
}
