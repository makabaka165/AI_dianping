package com.hmdp.ai.domain.knowledge.parsing;
public final class FileInspection {private final String sha256,mimeType;private final long size;public FileInspection(String sha256,String mimeType,long size){this.sha256=sha256;this.mimeType=mimeType;this.size=size;}public String getSha256(){return sha256;}public String getMimeType(){return mimeType;}public long getSize(){return size;}}
