package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentParserTest {
    @Test
    void preservesPageNumber() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(50, 700);
                content.showText("policy evidence");
                content.endText();
            }
            pdf.save(output);
        }
        ParsedDocument parsed = new PdfDocumentParser().parse(new ByteArrayInputStream(output.toByteArray()),
                ParserTestFixtures.context("policy.pdf", "application/pdf", output.size()));
        assertThat(parsed.getSections()).first().extracting("page").isEqualTo(1);
        assertThat(parsed.getPlainText()).contains("policy evidence");
    }
}
