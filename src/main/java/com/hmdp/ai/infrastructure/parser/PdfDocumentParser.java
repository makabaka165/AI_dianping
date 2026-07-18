package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
public class PdfDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return Collections.singleton("application/pdf"); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        List<ParsedSection> sections = new ArrayList<>(); int offset=0;
        try (PDDocument pdf = PDDocument.load(input)) {
            if (pdf.getNumberOfPages() > context.getMaxPages()) throw new IOException("DOCUMENT_PAGE_LIMIT_EXCEEDED");
            PDFTextStripper stripper = new PDFTextStripper();
            for(int page=1; page<=pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page); String text=stripper.getText(pdf).trim();
                sections.add(new ParsedSection("Page " + page, text, page, Collections.emptyList(), offset, offset+text.length())); offset += text.length();
            }
        }
        return new ParsedDocument(context.getFileName(), context.getMimeType(), sections, Collections.emptyList(), Collections.emptyList());
    }
}
