package com.hmdp.ai.infrastructure.vector;

import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.infrastructure.persistence.EmbeddingBinaryCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class RedisStackKnowledgeIndexAdapter implements KnowledgeIndexPort {
    private final RedisConnectionFactory connections;
    private final KnowledgeRepository repository;
    public RedisStackKnowledgeIndexAdapter(@Qualifier("vectorRedisConnectionFactory") RedisConnectionFactory connections,
                                           KnowledgeRepository repository){this.connections=connections;this.repository=repository;}
    @Override public void ensureIndex(String indexVersion,int dimension){String index=indexName(indexVersion);String prefix=prefix(indexVersion);try(RedisConnection connection=connections.getConnection()){connection.execute("FT.CREATE",bytes(index),bytes("ON"),bytes("HASH"),bytes("PREFIX"),bytes("1"),bytes(prefix),bytes("SCHEMA"),bytes("tenantId"),bytes("TAG"),bytes("workspaceId"),bytes("TAG"),bytes("knowledgeBaseId"),bytes("TAG"),bytes("indexVersion"),bytes("TAG"),bytes("documentId"),bytes("TAG"),bytes("documentVersion"),bytes("NUMERIC"),bytes("chunkId"),bytes("TAG"),bytes("status"),bytes("TAG"),bytes("allowedUsers"),bytes("TAG"),bytes("content"),bytes("TEXT"),bytes("WEIGHT"),bytes("1.0"),bytes("searchText"),bytes("TEXT"),bytes("WEIGHT"),bytes("2.0"),bytes("qualityScore"),bytes("NUMERIC"),bytes("effectiveAt"),bytes("NUMERIC"),bytes("expiredAt"),bytes("NUMERIC"),bytes("embedding"),bytes("VECTOR"),bytes("HNSW"),bytes("6"),bytes("TYPE"),bytes("FLOAT32"),bytes("DIM"),bytes(String.valueOf(dimension)),bytes("DISTANCE_METRIC"),bytes("COSINE"));}catch(DataAccessException e){String message=String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);if(!message.contains("index already exists"))throw e;}}
    @Override public void index(List<KnowledgeChunk> chunks){if(chunks==null||chunks.isEmpty())return;KnowledgeChunk first=chunks.get(0);Set<String>documentIds=new LinkedHashSet<>();for(KnowledgeChunk chunk:chunks)documentIds.add(chunk.getDocumentId());Map<String,List<String>>acl=repository.findDocumentReadPrincipals(first.getTenantId(),first.getWorkspaceId(),new ArrayList<>(documentIds));try(RedisConnection connection=connections.getConnection()){connection.openPipeline();for(KnowledgeChunk chunk:chunks){List<byte[]> args=new ArrayList<>();field(args,"tenantId",chunk.getTenantId());field(args,"workspaceId",chunk.getWorkspaceId());field(args,"knowledgeBaseId",chunk.getKnowledgeBaseId());field(args,"indexVersion",chunk.getIndexVersion());field(args,"documentId",chunk.getDocumentId());field(args,"documentVersion",String.valueOf(chunk.getDocumentVersion()));field(args,"chunkId",chunk.getId());field(args,"status","PUBLISHED");List<String>principals=acl.getOrDefault(chunk.getDocumentId(),Collections.emptyList());if(principals.isEmpty())throw new IllegalStateException("DOCUMENT_ACL_REQUIRED");field(args,"allowedUsers",String.join(",",principals));field(args,"content",chunk.getContent());field(args,"searchText",chunk.getSearchText());field(args,"qualityScore",String.valueOf(chunk.getQualityScore()));field(args,"effectiveAt","0");field(args,"expiredAt",String.valueOf(Long.MAX_VALUE));args.add(bytes("embedding"));args.add(EmbeddingBinaryCodec.encode(chunk.getEmbedding()));connection.execute("HSET",combine(bytes(key(chunk.getIndexVersion(),chunk.getId())),args));}connection.closePipeline();}}
    @Override public void delete(String indexVersion,List<String> ids){if(ids==null||ids.isEmpty())return;try(RedisConnection connection=connections.getConnection()){connection.openPipeline();for(String id:ids)connection.execute("DEL",bytes(key(indexVersion,id)));connection.closePipeline();}}
    @Override public List<IndexHit> vectorSearch(KnowledgeSearchScope scope,float[] embedding,int limit){String filter=filter(scope)+"=>[KNN $K @embedding $BLOB AS vector_distance]";try(RedisConnection connection=connections.getConnection()){Object raw=connection.execute("FT.SEARCH",bytes(indexName(scope.getIndexVersion())),bytes(filter),bytes("PARAMS"),bytes("4"),bytes("K"),bytes(String.valueOf(limit)),bytes("BLOB"),EmbeddingBinaryCodec.encode(embedding),bytes("SORTBY"),bytes("vector_distance"),bytes("ASC"),bytes("RETURN"),bytes("2"),bytes("chunkId"),bytes("vector_distance"),bytes("DIALECT"),bytes("2"),bytes("LIMIT"),bytes("0"),bytes(String.valueOf(limit)));return parse(raw,true);}}
    @Override public List<IndexHit> lexicalSearch(KnowledgeSearchScope scope,String query,int limit){String terms=lexicalTerms(query);if(terms.isEmpty())return Collections.emptyList();String expression=filter(scope)+" @searchText:("+terms+")";try(RedisConnection connection=connections.getConnection()){Object raw=connection.execute("FT.SEARCH",bytes(indexName(scope.getIndexVersion())),bytes(expression),bytes("WITHSCORES"),bytes("RETURN"),bytes("1"),bytes("chunkId"),bytes("LIMIT"),bytes("0"),bytes(String.valueOf(limit)));return parse(raw,false);}}
    private String filter(KnowledgeSearchScope scope){return "(@tenantId:{"+tag(scope.getTenantId())+"} @workspaceId:{"+tag(scope.getWorkspaceId())+"} @knowledgeBaseId:{"+tag(scope.getKnowledgeBaseId())+"} @indexVersion:{"+tag(scope.getIndexVersion())+"} @status:{PUBLISHED} @allowedUsers:{all|workspace|"+tag("user:"+scope.getUserId())+"})";}
    private List<IndexHit> parse(Object raw,boolean vector){if(!(raw instanceof List))return Collections.emptyList();List<?> values=(List<?>)raw;List<IndexHit> hits=new ArrayList<>();int step=vector?2:3;for(int i=1;i<values.size();i+=step){String key=text(values.get(i));double score=1.0;if(!vector&&i+1<values.size())score=parseDouble(text(values.get(i+1)),0);Object fields=values.get(i+(vector?1:2));if(fields instanceof List){List<?> list=(List<?>)fields;String chunk=null;Double distance=null;for(int f=0;f+1<list.size();f+=2){String name=text(list.get(f)),value=text(list.get(f+1));if("chunkId".equals(name))chunk=value;if("vector_distance".equals(name))distance=parseDouble(value,1);}if(chunk==null&&key!=null)chunk=key.substring(key.lastIndexOf(':')+1);if(vector&&distance!=null)score=Math.max(0,1-distance);if(chunk!=null)hits.add(new IndexHit(chunk,score));}}return hits;}
    private String lexicalTerms(String value){if(value==null)return "";String[] tokens=value.trim().split("\\s+");List<String> safe=new ArrayList<>();for(String token:tokens){String escaped=escapeQueryToken(token);if(!escaped.isEmpty())safe.add(escaped);}return String.join("|",safe);}
    private String escapeQueryToken(String value){String specials="-[]{}()|!@~:\"'\\\\";StringBuilder result=new StringBuilder();for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(specials.indexOf(ch)>=0)result.append('\\');result.append(ch);}return result.toString();}
    private String tag(String value){return value==null?"":value.replaceAll("([,\\.<>\\{\\}\\[\\]\\\"':;!@#$%^&*()\\-+=~| ])","\\\\$1");}
    private String indexName(String version){return "ai_kb_"+version.replaceAll("[^A-Za-z0-9_]","_");}
    private String prefix(String version){return "hmdp:ai:kb:"+version+":chunk:";}
    private String key(String version,String chunk){return prefix(version)+chunk;}
    private void field(List<byte[]> args,String name,String value){args.add(bytes(name));args.add(bytes(value==null?"":value));}
    private byte[][] combine(byte[] first,List<byte[]> rest){byte[][] values=new byte[rest.size()+1][];values[0]=first;for(int i=0;i<rest.size();i++)values[i+1]=rest.get(i);return values;}
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private String text(Object value){if(value instanceof byte[])return new String((byte[])value,StandardCharsets.UTF_8);return value==null?null:String.valueOf(value);}
    private double parseDouble(String value,double fallback){try{return Double.parseDouble(value);}catch(Exception e){return fallback;}}
}
