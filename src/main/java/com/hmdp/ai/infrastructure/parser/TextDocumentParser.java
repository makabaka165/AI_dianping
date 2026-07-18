package com.hmdp.ai.infrastructure.parser;
import com.hmdp.ai.domain.knowledge.parsing.*;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

@Component
public class TextDocumentParser implements DocumentParser {
    public Set<String> supportedMimeTypes() { return new HashSet<>(Arrays.asList("text/plain", "text/csv")); }
    public ParsedDocument parse(InputStream input, ParseContext context) throws IOException {
        byte[] bytes = read(input);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try { text = decoder.decode(ByteBuffer.wrap(stripBom(bytes))).toString(); }
        catch (CharacterCodingException e) { throw new IOException("DOCUMENT_ENCODING_INVALID", e); }
        return new ParsedDocument(context.getFileName(), context.getMimeType(),
                Collections.singletonList(new ParsedSection(context.getFileName(), text, null,
                        Collections.emptyList(), 0, text.length())), Collections.emptyList(), Collections.emptyList());
    }
    static byte[] read(InputStream input) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=input.read(b))!=-1) out.write(b,0,n); return out.toByteArray(); }
    private byte[] stripBom(byte[] bytes) { return bytes.length >= 3 && bytes[0]==(byte)0xEF && bytes[1]==(byte)0xBB && bytes[2]==(byte)0xBF ? Arrays.copyOfRange(bytes,3,bytes.length) : bytes; }
}
