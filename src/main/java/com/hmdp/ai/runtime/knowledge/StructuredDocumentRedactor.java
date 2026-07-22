package com.hmdp.ai.runtime.knowledge;

import com.hmdp.ai.domain.knowledge.parsing.ParsedCell;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParseWarning;
import com.hmdp.ai.domain.knowledge.parsing.ParsedSection;
import com.hmdp.ai.domain.knowledge.parsing.ParsedTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class StructuredDocumentRedactor {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern MOBILE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    public ParsedDocument redact(ParsedDocument document) {
        if (document == null) return null;
        List<ParsedSection> sections = document.getSections().stream()
                .map(this::redactSection)
                .collect(Collectors.toList());
        List<ParsedTable> tables = document.getTables().stream()
                .map(this::redactTable)
                .collect(Collectors.toList());
        List<ParseWarning> warnings = document.getWarnings().stream()
                .map(warning -> new ParseWarning(warning.getCode(), redactText(warning.getMessage())))
                .collect(Collectors.toList());
        return new ParsedDocument(redactText(document.getTitle()), document.getMimeType(), sections, tables,
                warnings);
    }

    private ParsedSection redactSection(ParsedSection section) {
        List<String> headingPath = section.getHeadingPath().stream()
                .map(this::redactText)
                .collect(Collectors.toList());
        return new ParsedSection(redactText(section.getTitle()), redactText(section.getText()), section.getPage(),
                headingPath, section.getSourceOffsetStart(), section.getSourceOffsetEnd());
    }

    private ParsedTable redactTable(ParsedTable table) {
        List<ParsedCell> cells = new ArrayList<>();
        for (ParsedCell cell : table.getCells()) {
            cells.add(new ParsedCell(cell.getRow(), cell.getColumn(), cell.getAddress(),
                    redactText(cell.getValue())));
        }
        return new ParsedTable(redactText(table.getName()), table.getPage(), cells);
    }

    private String redactText(String value) {
        if (value == null) return null;
        String redacted = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        redacted = MOBILE.matcher(redacted).replaceAll("[REDACTED_PHONE]");
        return ID_CARD.matcher(redacted).replaceAll("[REDACTED_ID]");
    }
}
