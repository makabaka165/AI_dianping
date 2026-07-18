package com.hmdp.ai.infrastructure.objectstorage;

import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;

@Component
public class MinioObjectStorageAdapter implements ObjectStoragePort {
    private final MinioClient minio;private final String bucket;private volatile boolean bucketReady;
    public MinioObjectStorageAdapter(MinioClient minio,@Value("${minio.bucket:hmdp-ai}") String bucket){this.minio=minio;this.bucket=bucket;}
    @Override public StoredObject put(String tenant,String knowledgeBase,String fileName,String contentType,byte[] content,String sha256){try{ensureBucket();String extension=extension(fileName);String key=tenant+"/"+knowledgeBase+"/"+LocalDate.now()+"/"+sha256+(extension.isEmpty()?"":"."+extension);minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(contentType).stream(new ByteArrayInputStream(content),content.length,-1).build());return new StoredObject(key,bucket,contentType,content.length,sha256,fileName);}catch(Exception e){throw new IllegalStateException("MINIO_UPLOAD_FAILED",e);}}
    @Override public InputStream get(String bucket,String objectKey){try{return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());}catch(Exception e){throw new IllegalStateException("MINIO_READ_FAILED",e);}}
    @Override public void delete(String bucket,String objectKey){try{minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());}catch(Exception e){throw new IllegalStateException("MINIO_DELETE_FAILED",e);}}
    private void ensureBucket()throws Exception{if(bucketReady)return;synchronized(this){if(bucketReady)return;if(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());bucketReady=true;}}
    private String extension(String name){if(name==null)return "";int dot=name.lastIndexOf('.');if(dot<0||dot==name.length()-1)return "";return name.substring(dot+1).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");}
}
