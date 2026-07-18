package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxDocumentParserTest {
    @Test
    void preservesSheetAndCellAddress() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Orders").createRow(0).createCell(0).setCellValue("amount");
            workbook.write(output);
        }
        ParsedDocument parsed = new XlsxDocumentParser().parse(new ByteArrayInputStream(output.toByteArray()),
                ParserTestFixtures.context("orders.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.size()));
        assertThat(parsed.getTables()).first().extracting("name").isEqualTo("Orders");
        assertThat(parsed.getTables().get(0).getCells().get(0).getAddress()).endsWith("A1");
    }
}
