package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class MarkdownDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return new HashSet<>(Arrays.asList("text/markdown", "text/x-markdown")); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        String text = new String(TextDocumentParser.read(input), StandardCharsets.UTF_8);
        List<ParsedSection> sections = new ArrayList<>(); List<String> path = new ArrayList<>();
        String title = context.getFileName(); StringBuilder body = new StringBuilder(); int offset = 0; int start = 0;
        for (String line : text.split("\\R", -1)) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (body.length() > 0) sections.add(new ParsedSection(title, body.toString().trim(), null, new ArrayList<>(path), start, offset));
                int level=0; while(level<line.length() && line.charAt(level)=='#') level++;
                title=line.substring(level).trim(); while(path.size()>=level) path.remove(path.size()-1); path.add(title);
                body.setLength(0); start=offset;
            } else body.append(line).append('\n');
            offset += line.length()+1;
        }
        if (body.length()>0 || sections.isEmpty()) sections.add(new ParsedSection(title, body.toString().trim(), null, new ArrayList<>(path), start, text.length()));
        return new ParsedDocument(context.getFileName(), context.getMimeType(), sections, Collections.emptyList(), Collections.emptyList());
    }
}
