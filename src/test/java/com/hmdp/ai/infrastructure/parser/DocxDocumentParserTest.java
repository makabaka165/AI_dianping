package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxDocumentParserTest {
    @Test
    void preservesParagraphAndTableCoordinates() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("refund policy");
            document.createTable(2, 2).getRow(1).getCell(1).setText("approved");
            document.write(output);
        }
        ParsedDocument parsed = new DocxDocumentParser().parse(new ByteArrayInputStream(output.toByteArray()),
                ParserTestFixtures.context("policy.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.size()));
        assertThat(parsed.getPlainText()).contains("refund policy");
        assertThat(parsed.getTables().get(0).getCells())
                .anyMatch(cell -> cell.getRow() == 1 && cell.getColumn() == 1);
    }
}
