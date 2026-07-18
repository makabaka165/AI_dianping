package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
public class DocxDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return Collections.singleton("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        ZipSecureFile.setMinInflateRatio(0.01); List<ParsedSection> sections=new ArrayList<>(); List<ParsedTable> tables=new ArrayList<>(); int offset=0;
        try(XWPFDocument doc=new XWPFDocument(input)) {
            String current=context.getFileName(); List<String> path=new ArrayList<>(); StringBuilder body=new StringBuilder(); int start=0;
            for(XWPFParagraph p:doc.getParagraphs()) { String text=p.getText(); String style=p.getStyle(); boolean heading=style!=null && style.toLowerCase(Locale.ROOT).startsWith("heading");
                if(heading) { if(body.length()>0) sections.add(new ParsedSection(current,body.toString().trim(),null,new ArrayList<>(path),start,offset)); current=text; path=Collections.singletonList(text); body.setLength(0); start=offset; } else body.append(text).append('\n'); offset+=text.length()+1; }
            if(body.length()>0 || sections.isEmpty()) sections.add(new ParsedSection(current,body.toString().trim(),null,new ArrayList<>(path),start,offset));
            int tableNo=0, cells=0; for(XWPFTable table:doc.getTables()) { List<ParsedCell> parsed=new ArrayList<>(); int r=0; for(XWPFTableRow row:table.getRows()) { int c=0; for(XWPFTableCell cell:row.getTableCells()) { if(++cells>context.getMaxCells()) throw new IOException("DOCUMENT_CELL_LIMIT_EXCEEDED"); parsed.add(new ParsedCell(r,c,"R"+(r+1)+"C"+(c+1),cell.getText())); c++; } r++; } tables.add(new ParsedTable("Table "+(++tableNo),null,parsed)); }
        }
        return new ParsedDocument(context.getFileName(),context.getMimeType(),sections,tables,Collections.emptyList());
    }
}
