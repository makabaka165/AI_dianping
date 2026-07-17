package com.hmdp.ai.infrastructure.parser;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class ParserRegistry {
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private final Map<String, DocumentParser> parsers = new HashMap<>();
    private final Tika tika = new Tika();

    public ParserRegistry(List<DocumentParser> documentParsers) {
        for(DocumentParser parser:documentParsers) for(String mime:parser.supportedMimeTypes()) parsers.put(mime,parser);
    }

    public ParseResult parse(byte[] bytes, String fileName, String declaredMime) throws IOException {
        validateName(fileName); if(bytes==null || bytes.length==0) throw new IOException("DOCUMENT_EMPTY"); if(bytes.length>MAX_FILE_BYTES) throw new IOException("DOCUMENT_SIZE_LIMIT_EXCEEDED");
        String detected=tika.detect(bytes,fileName); DocumentParser parser=parsers.get(detected);
        if(parser==null) throw new IOException("DOCUMENT_MIME_UNSUPPORTED:"+detected);
        if(declaredMime!=null && !declaredMime.trim().isEmpty() && !compatible(declaredMime,detected)) throw new IOException("DOCUMENT_MIME_MISMATCH");
        ParsedDocument document=parser.parse(new ByteArrayInputStream(bytes),new ParseContext(fileName,detected,bytes.length,500,200000));
        return new ParseResult(document,sha256(bytes),detected);
    }

    private void validateName(String name) throws IOException { if(name==null || name.trim().isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) throw new IOException("DOCUMENT_FILE_NAME_INVALID"); }
    private boolean compatible(String declared,String detected) { if(declared.equalsIgnoreCase(detected)) return true; return declared.equals("application/octet-stream"); }
    private String sha256(byte[] bytes) throws IOException { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s=new StringBuilder(); for(byte b:digest)s.append(String.format("%02x",b)); return s.toString(); } catch(Exception e){throw new IOException(e);} }

    public static final class ParseResult {
        private final ParsedDocument document; private final String sha256; private final String mimeType;
        public ParseResult(ParsedDocument document,String sha256,String mimeType){this.document=document;this.sha256=sha256;this.mimeType=mimeType;}
        public ParsedDocument getDocument(){return document;} public String getSha256(){return sha256;} public String getMimeType(){return mimeType;}
    }
}
