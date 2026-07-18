package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
public class XlsxDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return Collections.singleton("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        ZipSecureFile.setMinInflateRatio(0.01); List<ParsedSection> sections=new ArrayList<>(); List<ParsedTable> tables=new ArrayList<>(); DataFormatter formatter=new DataFormatter(Locale.ROOT); int count=0;
        try(Workbook workbook=WorkbookFactory.create(input)) { FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();
            for(Sheet sheet:workbook) { List<ParsedCell> cells=new ArrayList<>(); StringBuilder text=new StringBuilder(); for(Row row:sheet) { for(Cell cell:row) { if(++count>context.getMaxCells()) throw new IOException("DOCUMENT_CELL_LIMIT_EXCEEDED"); String value=formatter.formatCellValue(cell,evaluator); cells.add(new ParsedCell(row.getRowNum(),cell.getColumnIndex(),new CellReference(cell).formatAsString(),value)); if(!value.isEmpty()) text.append(value).append('\t'); } text.append('\n'); } tables.add(new ParsedTable(sheet.getSheetName(),null,cells)); sections.add(new ParsedSection(sheet.getSheetName(),text.toString().trim(),null,Collections.singletonList(sheet.getSheetName()),0,text.length())); }
        }
        return new ParsedDocument(context.getFileName(),context.getMimeType(),sections,tables,Collections.emptyList());
    }
}
