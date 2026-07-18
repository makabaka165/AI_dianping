package com.hmdp.ai.domain.knowledge.parsing;
import java.io.IOException;
public interface DocumentParsingPort {ParsedFile parse(byte[] bytes,String fileName,String declaredMime)throws IOException;}
