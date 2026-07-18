package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
public class HtmlDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return new HashSet<>(Arrays.asList("text/html", "application/xhtml+xml")); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        BodyContentHandler handler=new BodyContentHandler(-1);
        try { new HtmlParser().parse(input,handler,new Metadata(),new org.apache.tika.parser.ParseContext()); }
        catch(Exception e) { throw new IOException("HTML_PARSE_FAILED",e); }
        String text=handler.toString().trim();
        return new ParsedDocument(context.getFileName(),context.getMimeType(),Collections.singletonList(new ParsedSection(context.getFileName(),text,null,Collections.emptyList(),0,text.length())),Collections.emptyList(),Collections.emptyList());
    }
}
