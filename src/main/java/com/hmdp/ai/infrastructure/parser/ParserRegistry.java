package com.hmdp.ai.infrastructure.parser;

import com.hmdp.ai.domain.knowledge.parsing.DocumentInspectionPort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParser;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParsingPort;
import com.hmdp.ai.domain.knowledge.parsing.FileInspection;
import com.hmdp.ai.domain.knowledge.parsing.ParseContext;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedFile;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class ParserRegistry implements DocumentInspectionPort, DocumentParsingPort {
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private final Map<String, DocumentParser> parsers = new HashMap<>();
    private final Tika tika = new Tika();

    public ParserRegistry(List<DocumentParser> documentParsers) {
        for(DocumentParser parser:documentParsers) for(String mime:parser.supportedMimeTypes()) parsers.put(mime,parser);
    }

    @Override
    public ParsedFile parse(byte[] bytes, String fileName, String declaredMime) throws IOException {
        FileInspection inspection=inspect(bytes,fileName,declaredMime);DocumentParser parser=parsers.get(inspection.getMimeType());
        ParsedDocument document=parser.parse(new ByteArrayInputStream(bytes),new ParseContext(fileName,inspection.getMimeType(),bytes.length,500,200000));
        return new ParsedFile(document,inspection.getSha256(),inspection.getMimeType());
    }

    @Override public FileInspection inspect(byte[] bytes,String fileName,String declaredMime)throws IOException{validateName(fileName);if(bytes==null||bytes.length==0)throw new IOException("DOCUMENT_EMPTY");if(bytes.length>MAX_FILE_BYTES)throw new IOException("DOCUMENT_SIZE_LIMIT_EXCEEDED");String detected=tika.detect(bytes,fileName);if(!parsers.containsKey(detected))throw new IOException("DOCUMENT_MIME_UNSUPPORTED:"+detected);if(declaredMime!=null&&!declaredMime.trim().isEmpty()&&!compatible(declaredMime,detected))throw new IOException("DOCUMENT_MIME_MISMATCH");return new FileInspection(sha256(bytes),detected,bytes.length);}

    private void validateName(String name) throws IOException { if(name==null || name.trim().isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) throw new IOException("DOCUMENT_FILE_NAME_INVALID"); }
    private boolean compatible(String declared,String detected) { if(declared.equalsIgnoreCase(detected)) return true; return declared.equals("application/octet-stream"); }
    private String sha256(byte[] bytes) throws IOException { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s=new StringBuilder(); for(byte b:digest)s.append(String.format("%02x",b)); return s.toString(); } catch(Exception e){throw new IOException(e);} }

}
