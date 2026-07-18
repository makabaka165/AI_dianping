package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParseContext;
import com.hmdp.ai.domain.knowledge.parsing.ParsedCell;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTest {
    @Test
    void pdfPreservesPageLocation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            for (String value : Arrays.asList("first page", "second page")) {
                PDPage page = new PDPage(); pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 12); content.newLineAtOffset(50, 700);
                    content.showText(value); content.endText();
                }
            }
            pdf.save(out);
        }
        ParsedDocument parsed = new PdfDocumentParser().parse(new ByteArrayInputStream(out.toByteArray()),
                context("sample.pdf", "application/pdf", out.size()));
        assertThat(parsed.getSections()).hasSize(2);
        assertThat(parsed.getSections().get(1).getPage()).isEqualTo(2);
        assertThat(parsed.getPlainText()).contains("second page");
    }

    @Test
    void docxPreservesHeadingParagraphAndTableCoordinates() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Policy heading");
            doc.createParagraph().createRun().setText("Policy paragraph");
            doc.createTable(2, 2).getRow(1).getCell(1).setText("table-value");
            doc.write(out);
        }
        ParsedDocument parsed = new DocxDocumentParser().parse(new ByteArrayInputStream(out.toByteArray()),
                context("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.size()));
        assertThat(parsed.getPlainText()).contains("Policy paragraph");
        assertThat(parsed.getTables().get(0).getCells()).anyMatch(c -> c.getRow() == 1 && c.getColumn() == 1 && c.getValue().contains("table-value"));
    }

    @Test
    void xlsxPreservesSheetRowColumnAndFormulaResult() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.createSheet("Orders");
            sheet.createRow(0).createCell(0).setCellValue("amount");
            sheet.createRow(1).createCell(0).setCellValue(7);
            sheet.getRow(1).createCell(1).setCellFormula("A2*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll(); book.write(out);
        }
        ParsedDocument parsed = new XlsxDocumentParser().parse(new ByteArrayInputStream(out.toByteArray()),
                context("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.size()));
        assertThat(parsed.getTables().get(0).getName()).isEqualTo("Orders");
        ParsedCell formula = parsed.getTables().get(0).getCells().stream()
                .filter(c -> c.getRow() == 1 && c.getColumn() == 1).findFirst().orElseThrow(AssertionError::new);
        assertThat(formula.getAddress()).contains("B2");
        assertThat(formula.getValue()).isNotBlank();
    }

    @Test
    void markdownPreservesHeadingPath() throws Exception {
        ParsedDocument parsed = new MarkdownDocumentParser().parse(
                new ByteArrayInputStream("# Root\nintro\n## Child\ndetail".getBytes(StandardCharsets.UTF_8)),
                context("sample.md", "text/markdown", 30));
        assertThat(parsed.getSections()).anyMatch(s -> s.getHeadingPath().contains("Child") && s.getText().contains("detail"));
    }

    @Test
    void registryRejectsPathTraversalAndMimeMismatch() {
        ParserRegistry registry = new ParserRegistry(Arrays.asList(new TextDocumentParser(), new PdfDocumentParser()));
        assertThatThrownBy(() -> registry.parse("safe".getBytes(StandardCharsets.UTF_8), "../secret.txt", "text/plain"))
                .hasMessageContaining("FILE_NAME_INVALID");
        assertThatThrownBy(() -> registry.parse("plain".getBytes(StandardCharsets.UTF_8), "plain.txt", "application/pdf"))
                .hasMessageContaining("MIME_MISMATCH");
    }

    private ParseContext context(String name, String mime, long size) { return new ParseContext(name, mime, size, 10, 1000); }
}
