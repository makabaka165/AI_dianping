package com.hmdp.ai.domain.knowledge.parsing;
import java.io.IOException;
public interface DocumentInspectionPort {FileInspection inspect(byte[] bytes,String fileName,String declaredMime)throws IOException;}
