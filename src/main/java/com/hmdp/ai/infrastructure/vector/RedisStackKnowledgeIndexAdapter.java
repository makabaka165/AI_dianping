package com.hmdp.ai.infrastructure.vector;

import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.IndexVerificationResult;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.infrastructure.persistence.EmbeddingBinaryCodec;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.stereotype.Component;

@Component
public class RedisStackKnowledgeIndexAdapter implements KnowledgeIndexPort {
  private static final int MAX_SEARCH_LIMIT = 100;
  private static final String QUERY_SPECIALS = ",.<>{}[]\\\"':;!@#$%^&*()-+=~|\\\\/?";
  private static final String TAG_SPECIALS = ",.<>{}[]\\\"':;!@#$%^&*()-+=~|\\\\/? ";
  private final RedisConnectionFactory connections;
  private final KnowledgeRepository repository;

  public RedisStackKnowledgeIndexAdapter(
      @Qualifier("vectorRedisConnectionFactory") RedisConnectionFactory connections,
      KnowledgeRepository repository) {
    this.connections = connections;
    this.repository = repository;
  }

  @Override
  public void ensureIndex(String indexVersion, int dimension) {
    requireIndexVersion(indexVersion);
    requireDimension(dimension);
    String index = indexName(indexVersion);
    String prefix = prefix(indexVersion);
    try (RedisConnection connection = connections.getConnection()) {
      createPhysicalIndex(connection, index, prefix, dimension);
      validateIndexDefinition(
          executeNested(connection, "FT.INFO", bytes(index)), index, prefix, dimension);
    }
  }

  @Override
  public void index(List<KnowledgeChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) return;
    validateChunks(chunks);
    KnowledgeChunk first = chunks.get(0);
    Set<String> documentIds = new LinkedHashSet<>();
    for (KnowledgeChunk chunk : chunks) documentIds.add(chunk.getDocumentId());
    Map<String, List<String>> acl =
        repository.findDocumentReadPrincipals(
            first.getTenantId(), first.getWorkspaceId(), new ArrayList<>(documentIds));
    if (acl == null) throw new IllegalStateException("DOCUMENT_ACL_LOOKUP_FAILED");
    java.util.LinkedHashMap<String, List<String>> validatedAcl = new java.util.LinkedHashMap<>();
    for (String documentId : documentIds) {
      List<String> principals = normalizePrincipals(acl.get(documentId));
      if (principals.isEmpty()) throw new IllegalStateException("DOCUMENT_ACL_REQUIRED");
      validatedAcl.put(documentId, principals);
    }
    try (RedisConnection connection = connections.getConnection()) {
      connection.openPipeline();
      for (KnowledgeChunk chunk : chunks) {
        List<byte[]> args = new ArrayList<>();
        field(args, "tenantId", chunk.getTenantId());
        field(args, "workspaceId", chunk.getWorkspaceId());
        field(args, "knowledgeBaseId", chunk.getKnowledgeBaseId());
        field(args, "indexVersion", chunk.getIndexVersion());
        field(args, "documentId", chunk.getDocumentId());
        field(args, "documentVersion", String.valueOf(chunk.getDocumentVersion()));
        field(args, "chunkId", chunk.getId());
        field(args, "status", "PUBLISHED");
        List<String> principals = validatedAcl.get(chunk.getDocumentId());
        field(args, "allowedUsers", String.join(",", principals));
        field(args, "content", chunk.getContent());
        field(args, "searchText", chunk.getSearchText());
        field(args, "qualityScore", String.valueOf(chunk.getQualityScore()));
        field(args, "effectiveAt", "0");
        field(args, "expiredAt", String.valueOf(Long.MAX_VALUE));
        args.add(bytes("embedding"));
        args.add(EmbeddingBinaryCodec.encode(chunk.getEmbedding()));
        connection.execute(
            "HSET", combine(bytes(key(chunk.getIndexVersion(), chunk.getId())), args));
      }
      connection.closePipeline();
    }
  }

  @Override
  public void delete(String indexVersion, List<String> ids) {
    if (ids == null || ids.isEmpty()) return;
    requireIndexVersion(indexVersion);
    Set<String> uniqueIds = new LinkedHashSet<>();
    for (String id : ids) uniqueIds.add(requireIdentifier(id, "chunkId"));
    try (RedisConnection connection = connections.getConnection()) {
      connection.openPipeline();
      for (String id : uniqueIds) connection.execute("DEL", bytes(key(indexVersion, id)));
      connection.closePipeline();
    }
  }

  @Override
  public void drop(String indexVersion) {
    requireIndexVersion(indexVersion);
    try (RedisConnection connection = connections.getConnection()) {
      String prefix = prefix(indexVersion);
      List<String> existingIndexes = new ArrayList<>();
      for (String candidate : RedisKnowledgeIndexNaming.candidateIndexNames(indexVersion)) {
        Object info = findIndexInfo(connection, candidate);
        if (info == null) continue;
        // Validate every candidate before issuing the first destructive command. A legacy name
        // may be owned by another version; discovering that after dropping v2 would leave a
        // partially deleted index and make cleanup retries unsafe.
        validateIndexIdentity(info, candidate, prefix);
        existingIndexes.add(candidate);
      }
      for (String candidate : existingIndexes) {
        dropPhysicalIndex(connection, candidate);
      }
    }
  }

  @Override
  public IndexVerificationResult verify(KnowledgeSearchScope scope, List<KnowledgeChunk> expected) {
    validateScope(scope);
    if (expected == null || expected.isEmpty()) {
      return new IndexVerificationResult(0, 0, 0, 0, false, false, false, false);
    }
    validateChunks(expected);
    for (KnowledgeChunk chunk : expected) {
      if (!scope.getTenantId().equals(chunk.getTenantId())
          || !scope.getWorkspaceId().equals(chunk.getWorkspaceId())
          || !scope.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())
          || !scope.getIndexVersion().equals(chunk.getIndexVersion())) {
        throw new IllegalArgumentException("CHUNK_SCOPE_MISMATCH");
      }
    }
    Set<String> documents = new LinkedHashSet<>();
    Set<String> indexedDocuments = new LinkedHashSet<>();
    Map<String, List<String>> acl =
        repository.findDocumentReadPrincipals(
            scope.getTenantId(), scope.getWorkspaceId(), documentIds(expected));
    if (acl == null) throw new IllegalStateException("DOCUMENT_ACL_LOOKUP_FAILED");
    long indexed = 0;
    boolean dimensions = true;
    boolean aclValid = true;
    try (RedisConnection connection = connections.getConnection()) {
      for (KnowledgeChunk chunk : expected) {
        documents.add(chunk.getDocumentId());
        String redisKey = key(scope.getIndexVersion(), chunk.getId());
        String chunkId = hashText(connection, redisKey, "chunkId");
        byte[] embedding = hashBytes(connection, redisKey, "embedding");
        String principals = hashText(connection, redisKey, "allowedUsers");
        boolean metadataValid =
            chunk.getId().equals(chunkId)
                && chunk.getTenantId().equals(hashText(connection, redisKey, "tenantId"))
                && chunk.getWorkspaceId().equals(hashText(connection, redisKey, "workspaceId"))
                && chunk
                    .getKnowledgeBaseId()
                    .equals(hashText(connection, redisKey, "knowledgeBaseId"))
                && chunk.getIndexVersion().equals(hashText(connection, redisKey, "indexVersion"))
                && chunk.getDocumentId().equals(hashText(connection, redisKey, "documentId"))
                && String.valueOf(chunk.getDocumentVersion())
                    .equals(hashText(connection, redisKey, "documentVersion"))
                && "PUBLISHED".equals(hashText(connection, redisKey, "status"))
                && chunk.getContent().equals(hashText(connection, redisKey, "content"))
                && chunk.getSearchText().equals(hashText(connection, redisKey, "searchText"))
                && numericMatches(
                    chunk.getQualityScore(), hashText(connection, redisKey, "qualityScore"))
                && numericMatches(0, hashText(connection, redisKey, "effectiveAt"))
                && numericMatches(Long.MAX_VALUE, hashText(connection, redisKey, "expiredAt"));
        if (metadataValid) {
          indexed++;
          indexedDocuments.add(chunk.getDocumentId());
        }
        dimensions &=
            embedding != null
                && embedding.length == chunk.getEmbeddingDimension() * Float.BYTES
                && Arrays.equals(EmbeddingBinaryCodec.encode(chunk.getEmbedding()), embedding);
        Set<String> actualAcl = parsePrincipals(principals);
        Set<String> expectedAcl =
            new LinkedHashSet<>(normalizePrincipals(acl.get(chunk.getDocumentId())));
        aclValid &= !expectedAcl.isEmpty() && actualAcl.equals(expectedAcl);
      }
    }
    KnowledgeChunk sample = expected.get(0);
    boolean vector =
        searchEventually(
            () ->
                vectorSearch(scope, sample.getEmbedding(), 5).stream()
                    .anyMatch(hit -> sample.getId().equals(hit.getChunkId())));
    String lexicalQuery = firstTerm(sample.getSearchText());
    boolean lexical =
        !lexicalQuery.isEmpty()
            && searchEventually(
                () ->
                    lexicalSearch(scope, lexicalQuery, 5).stream()
                        .anyMatch(hit -> sample.getId().equals(hit.getChunkId())));
    return new IndexVerificationResult(
        expected.size(),
        indexed,
        documents.size(),
        indexedDocuments.size(),
        vector,
        lexical,
        dimensions,
        aclValid);
  }

  @Override
  public List<IndexHit> vectorSearch(KnowledgeSearchScope scope, float[] embedding, int limit) {
    validateScope(scope);
    validateEmbedding(embedding, null);
    requireSearchLimit(limit);
    String filter = filter(scope) + "=>[KNN $K @embedding $BLOB AS vector_distance]";
    try (RedisConnection connection = connections.getConnection()) {
      Object raw =
          searchWithFallback(
              connection,
              scope.getIndexVersion(),
              bytes(filter),
              bytes("PARAMS"),
              bytes("4"),
              bytes("K"),
              bytes(String.valueOf(limit)),
              bytes("BLOB"),
              EmbeddingBinaryCodec.encode(embedding),
              bytes("SORTBY"),
              bytes("vector_distance"),
              bytes("ASC"),
              bytes("RETURN"),
              bytes("2"),
              bytes("chunkId"),
              bytes("vector_distance"),
              bytes("DIALECT"),
              bytes("2"),
              bytes("LIMIT"),
              bytes("0"),
              bytes(String.valueOf(limit)));
      return parse(raw, true);
    }
  }

  @Override
  public List<IndexHit> lexicalSearch(KnowledgeSearchScope scope, String query, int limit) {
    validateScope(scope);
    requireSearchLimit(limit);
    String terms = lexicalTerms(query);
    if (terms.isEmpty()) return Collections.emptyList();
    String expression = filter(scope) + " @searchText:(" + terms + ")";
    try (RedisConnection connection = connections.getConnection()) {
      Object raw =
          searchWithFallback(
              connection,
              scope.getIndexVersion(),
              bytes(expression),
              bytes("WITHSCORES"),
              bytes("RETURN"),
              bytes("1"),
              bytes("chunkId"),
              bytes("LIMIT"),
              bytes("0"),
              bytes(String.valueOf(limit)));
      return parse(raw, false);
    }
  }

  private Object search(RedisConnection connection, byte[]... arguments) {
    return executeNested(connection, "FT.SEARCH", arguments);
  }

  /** Uses the v2 index and falls back to the deployed v1 name during a rolling migration. */
  private Object searchWithFallback(
      RedisConnection connection, String indexVersion, byte[]... queryArguments) {
    try {
      return search(connection, prepend(bytes(indexName(indexVersion)), queryArguments));
    } catch (DataAccessException e) {
      if (!isUnknownIndex(e)) throw e;
      return search(
          connection,
          prepend(bytes(RedisKnowledgeIndexNaming.legacyIndexName(indexVersion)), queryArguments));
    }
  }

  private byte[][] prepend(byte[] first, byte[][] rest) {
    byte[][] result = new byte[rest.length + 1][];
    result[0] = first;
    System.arraycopy(rest, 0, result, 1, rest.length);
    return result;
  }

  private Object executeNested(RedisConnection connection, String command, byte[]... arguments) {
    if (!(connection instanceof LettuceConnection))
      throw new IllegalStateException("REDIS_STACK_LETTUCE_CONNECTION_REQUIRED");
    return ((LettuceConnection) connection)
        .execute(command, new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), arguments);
  }

  private void createPhysicalIndex(
      RedisConnection connection, String index, String prefix, int dimension) {
    try {
      connection.execute(
          "FT.CREATE",
          bytes(index),
          bytes("ON"),
          bytes("HASH"),
          bytes("PREFIX"),
          bytes("1"),
          bytes(prefix),
          bytes("SCHEMA"),
          bytes("tenantId"),
          bytes("TAG"),
          bytes("workspaceId"),
          bytes("TAG"),
          bytes("knowledgeBaseId"),
          bytes("TAG"),
          bytes("indexVersion"),
          bytes("TAG"),
          bytes("documentId"),
          bytes("TAG"),
          bytes("documentVersion"),
          bytes("NUMERIC"),
          bytes("chunkId"),
          bytes("TAG"),
          bytes("status"),
          bytes("TAG"),
          bytes("allowedUsers"),
          bytes("TAG"),
          bytes("content"),
          bytes("TEXT"),
          bytes("WEIGHT"),
          bytes("1.0"),
          bytes("searchText"),
          bytes("TEXT"),
          bytes("WEIGHT"),
          bytes("2.0"),
          bytes("qualityScore"),
          bytes("NUMERIC"),
          bytes("effectiveAt"),
          bytes("NUMERIC"),
          bytes("expiredAt"),
          bytes("NUMERIC"),
          bytes("embedding"),
          bytes("VECTOR"),
          bytes("HNSW"),
          bytes("6"),
          bytes("TYPE"),
          bytes("FLOAT32"),
          bytes("DIM"),
          bytes(String.valueOf(dimension)),
          bytes("DISTANCE_METRIC"),
          bytes("COSINE"));
    } catch (DataAccessException e) {
      if (!isAlreadyExistingIndex(e)) throw e;
    }
  }

  private Object findIndexInfo(RedisConnection connection, String index) {
    try {
      return executeNested(connection, "FT.INFO", bytes(index));
    } catch (DataAccessException e) {
      if (isUnknownIndex(e)) return null;
      throw e;
    }
  }

  private void dropPhysicalIndex(RedisConnection connection, String index) {
    try {
      connection.execute("FT.DROPINDEX", bytes(index), bytes("DD"));
    } catch (DataAccessException e) {
      if (!isUnknownIndex(e)) throw e;
    }
  }

  private void validateIndexIdentity(Object raw, String expectedIndex, String expectedPrefix) {
    if (!expectedIndex.equals(text(valueByName(raw, "index_name")))) {
      indexSchemaMismatch("index name");
    }
    Object definition = valueByName(raw, "index_definition");
    if (!equalsIgnoreCase("HASH", text(valueByName(definition, "key_type")))) {
      indexSchemaMismatch("key type");
    }
    Set<String> prefixes = textValues(valueByName(definition, "prefixes"));
    if (prefixes.size() != 1 || !prefixes.contains(expectedPrefix)) {
      indexSchemaMismatch("prefix");
    }
  }

  private boolean isAlreadyExistingIndex(DataAccessException error) {
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return message.contains("index already exists")
        || message.contains("already exists")
        || message.contains("index exists");
  }

  private boolean isUnknownIndex(DataAccessException error) {
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return message.contains("unknown index")
        || message.contains("no such index")
        || message.contains("index does not exist");
  }

  private void validateIndexDefinition(
      Object raw, String expectedIndex, String expectedPrefix, int expectedDimension) {
    if (!expectedIndex.equals(text(valueByName(raw, "index_name")))) {
      indexSchemaMismatch("index name");
    }

    Object definition = valueByName(raw, "index_definition");
    if (!equalsIgnoreCase("HASH", text(valueByName(definition, "key_type")))) {
      indexSchemaMismatch("key type");
    }
    Set<String> prefixes = textValues(valueByName(definition, "prefixes"));
    if (prefixes.size() != 1 || !prefixes.contains(expectedPrefix)) {
      indexSchemaMismatch("prefix");
    }

    Map<String, Object> attributes = indexAttributes(valueByName(raw, "attributes"));
    requireIndexField(attributes, "tenantId", "TAG");
    requireIndexField(attributes, "workspaceId", "TAG");
    requireIndexField(attributes, "knowledgeBaseId", "TAG");
    requireIndexField(attributes, "indexVersion", "TAG");
    requireIndexField(attributes, "documentId", "TAG");
    requireIndexField(attributes, "documentVersion", "NUMERIC");
    requireIndexField(attributes, "chunkId", "TAG");
    requireIndexField(attributes, "status", "TAG");
    requireIndexField(attributes, "allowedUsers", "TAG");
    Object content = requireIndexField(attributes, "content", "TEXT");
    Object searchText = requireIndexField(attributes, "searchText", "TEXT");
    requireIndexField(attributes, "qualityScore", "NUMERIC");
    requireIndexField(attributes, "effectiveAt", "NUMERIC");
    requireIndexField(attributes, "expiredAt", "NUMERIC");
    Object embedding = requireIndexField(attributes, "embedding", "VECTOR");

    if (Math.abs(parseDouble(text(valueByName(content, "weight")), 0) - 1.0) > 0.000001) {
      indexSchemaMismatch("content weight");
    }
    if (Math.abs(parseDouble(text(valueByName(searchText, "weight")), 0) - 2.0) > 0.000001) {
      indexSchemaMismatch("searchText weight");
    }
    if (!equalsIgnoreCase("HNSW", text(valueByName(embedding, "algorithm")))) {
      indexSchemaMismatch("vector algorithm");
    }
    if (!equalsIgnoreCase("FLOAT32", text(valueByName(embedding, "data_type")))) {
      indexSchemaMismatch("vector data type");
    }
    if (expectedDimension > 0
        && parseLong(text(valueByName(embedding, "dim")), -1) != expectedDimension) {
      indexSchemaMismatch("vector dimension");
    }
    if (!equalsIgnoreCase("COSINE", text(valueByName(embedding, "distance_metric")))) {
      indexSchemaMismatch("vector distance metric");
    }
  }

  private Map<String, Object> indexAttributes(Object raw) {
    if (!(raw instanceof List)) indexSchemaMismatch("attributes");
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Object attribute : (List<?>) raw) {
      String name = text(valueByName(attribute, "attribute"));
      if (isBlank(name)) name = text(valueByName(attribute, "identifier"));
      if (!isBlank(name)) attributes.put(name, attribute);
    }
    return attributes;
  }

  private Object requireIndexField(
      Map<String, Object> attributes, String name, String expectedType) {
    Object attribute = attributes.get(name);
    if (attribute == null
        || !equalsIgnoreCase(expectedType, text(valueByName(attribute, "type")))) {
      indexSchemaMismatch("field " + name);
    }
    if ("TAG".equalsIgnoreCase(expectedType)) {
      String separator = text(valueByName(attribute, "separator"));
      if (separator != null && !",".equals(separator)) {
        indexSchemaMismatch("field " + name + " separator");
      }
    }
    return attribute;
  }

  private Set<String> textValues(Object raw) {
    if (raw == null) return Collections.emptySet();
    Set<String> values = new LinkedHashSet<>();
    if (raw instanceof List) {
      for (Object value : (List<?>) raw) values.add(text(value));
    } else {
      values.add(text(raw));
    }
    return values;
  }

  private boolean equalsIgnoreCase(String expected, String actual) {
    return actual != null && expected.equalsIgnoreCase(actual);
  }

  private void indexSchemaMismatch(String detail) {
    throw new IllegalStateException("REDIS_INDEX_SCHEMA_MISMATCH: " + detail);
  }

  private String filter(KnowledgeSearchScope scope) {
    return "(@tenantId:{"
        + tag(scope.getTenantId())
        + "} @workspaceId:{"
        + tag(scope.getWorkspaceId())
        + "} @knowledgeBaseId:{"
        + tag(scope.getKnowledgeBaseId())
        + "} @indexVersion:{"
        + tag(scope.getIndexVersion())
        + "} @status:{PUBLISHED} @allowedUsers:{all|workspace|"
        + tag("user:" + scope.getUserId())
        + "})";
  }

  private List<IndexHit> parse(Object raw, boolean vector) {
    if (raw instanceof Map) {
      Object results = valueByName(raw, "results");
      return results instanceof List
          ? parseResp3Results((List<?>) results, vector)
          : Collections.emptyList();
    }
    if (!(raw instanceof List)) return Collections.emptyList();
    List<?> values = (List<?>) raw;
    Object resp3Results = pairValue(values, "results");
    if (resp3Results instanceof List) {
      return parseResp3Results((List<?>) resp3Results, vector);
    }
    return parseResp2Results(values, vector);
  }

  private List<IndexHit> parseResp3Results(List<?> results, boolean vector) {
    List<IndexHit> hits = new ArrayList<>();
    for (Object result : results) {
      String key = text(valueByName(result, "id"));
      double score = vector ? 1.0 : parseDouble(text(valueByName(result, "score")), 0);
      IndexHit hit = createHit(key, valueByName(result, "extra_attributes"), score, vector);
      if (hit != null) hits.add(hit);
    }
    return hits;
  }

  private List<IndexHit> parseResp2Results(List<?> values, boolean vector) {
    List<IndexHit> hits = new ArrayList<>();
    int step = vector ? 2 : 3;
    int fieldsOffset = vector ? 1 : 2;
    for (int i = 1; i + fieldsOffset < values.size(); i += step) {
      String key = text(values.get(i));
      double score = 1.0;
      if (!vector && i + 1 < values.size()) score = parseDouble(text(values.get(i + 1)), 0);
      IndexHit hit = createHit(key, values.get(i + fieldsOffset), score, vector);
      if (hit != null) hits.add(hit);
    }
    return hits;
  }

  private IndexHit createHit(String key, Object rawFields, double score, boolean vector) {
    String chunk = text(valueByName(rawFields, "chunkId"));
    String rawDistance = text(valueByName(rawFields, "vector_distance"));
    if (isBlank(chunk) && !isBlank(key)) chunk = key.substring(key.lastIndexOf(':') + 1);
    if (isBlank(chunk)) return null;
    if (vector && rawDistance != null) {
      score = Math.max(0, Math.min(1, 1 - parseDouble(rawDistance, 1)));
    }
    if (!Double.isFinite(score)) score = 0;
    return new IndexHit(chunk, score);
  }

  private Object valueByName(Object values, String name) {
    if (values instanceof Map) {
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) values).entrySet()) {
        if (equalsIgnoreCase(name, text(entry.getKey()))) return entry.getValue();
      }
      return null;
    }
    return values instanceof List ? pairValue((List<?>) values, name) : null;
  }

  private Object pairValue(List<?> pairs, String name) {
    for (int index = 0; index + 1 < pairs.size(); index += 2) {
      if (equalsIgnoreCase(name, text(pairs.get(index)))) return pairs.get(index + 1);
    }
    return null;
  }

  private String lexicalTerms(String value) {
    if (value == null) return "";
    String[] tokens = value.trim().split("\\s+");
    List<String> safe = new ArrayList<>();
    for (String token : tokens) {
      String escaped = escapeQueryToken(token);
      if (!escaped.isEmpty()) safe.add(escaped);
    }
    return String.join("|", safe);
  }

  private List<String> documentIds(List<KnowledgeChunk> chunks) {
    Set<String> ids = new LinkedHashSet<>();
    for (KnowledgeChunk chunk : chunks) ids.add(chunk.getDocumentId());
    return new ArrayList<>(ids);
  }

  private String firstTerm(String value) {
    if (value == null || value.trim().isEmpty()) return "";
    String[] terms = value.trim().split("\\s+");
    return terms[0];
  }

  private boolean searchEventually(java.util.function.BooleanSupplier search) {
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        if (search.getAsBoolean()) return true;
      } catch (Exception ignored) {
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private String escapeQueryToken(String value) {
    return escape(value, QUERY_SPECIALS);
  }

  private String tag(String value) {
    return value == null ? "" : escape(value, TAG_SPECIALS);
  }

  private String escape(String value, String specials) {
    StringBuilder result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (specials.indexOf(ch) >= 0) result.append('\\');
      result.append(ch);
    }
    return result.toString();
  }

  private String indexName(String version) {
    return RedisKnowledgeIndexNaming.indexName(version);
  }

  private String prefix(String version) {
    return "hmdp:ai:kb:" + version + ":chunk:";
  }

  private String key(String version, String chunk) {
    return prefix(version) + chunk;
  }

  private void validateChunks(List<KnowledgeChunk> chunks) {
    KnowledgeChunk first = chunks.get(0);
    if (first == null) throw new IllegalArgumentException("CHUNK_REQUIRED");
    requireIdentifier(first.getTenantId(), "tenantId");
    requireIdentifier(first.getWorkspaceId(), "workspaceId");
    requireIdentifier(first.getKnowledgeBaseId(), "knowledgeBaseId");
    requireIndexVersion(first.getIndexVersion());
    int batchDimension = first.getEmbeddingDimension();
    Set<String> ids = new HashSet<>();
    for (KnowledgeChunk chunk : chunks) {
      if (chunk == null) throw new IllegalArgumentException("CHUNK_REQUIRED");
      if (!first.getTenantId().equals(chunk.getTenantId())
          || !first.getWorkspaceId().equals(chunk.getWorkspaceId())
          || !first.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())
          || !first.getIndexVersion().equals(chunk.getIndexVersion())) {
        throw new IllegalArgumentException("CHUNK_BATCH_SCOPE_MISMATCH");
      }
      requireIdentifier(chunk.getId(), "chunkId");
      requireIdentifier(chunk.getDocumentId(), "documentId");
      if (!ids.add(chunk.getId())) throw new IllegalArgumentException("DUPLICATE_CHUNK_ID");
      requireDimension(chunk.getEmbeddingDimension());
      if (chunk.getEmbeddingDimension() != batchDimension) {
        throw new IllegalArgumentException("CHUNK_BATCH_DIMENSION_MISMATCH");
      }
      validateEmbedding(chunk.getEmbedding(), chunk.getEmbeddingDimension());
      if (chunk.getContent() == null || chunk.getContent().trim().isEmpty()) {
        throw new IllegalArgumentException("CHUNK_CONTENT_REQUIRED");
      }
      if (chunk.getSearchText() == null || chunk.getSearchText().trim().isEmpty()) {
        throw new IllegalArgumentException("CHUNK_SEARCH_TEXT_REQUIRED");
      }
      if (!Double.isFinite(chunk.getQualityScore())) {
        throw new IllegalArgumentException("CHUNK_QUALITY_INVALID");
      }
    }
  }

  private void validateScope(KnowledgeSearchScope scope) {
    if (scope == null) throw new IllegalArgumentException("SEARCH_SCOPE_REQUIRED");
    requireIdentifier(scope.getTenantId(), "tenantId");
    requireIdentifier(scope.getWorkspaceId(), "workspaceId");
    requireIdentifier(scope.getKnowledgeBaseId(), "knowledgeBaseId");
    requireIndexVersion(scope.getIndexVersion());
    requireIdentifier(scope.getUserId(), "userId");
  }

  private String requireIndexVersion(String value) {
    return requireIdentifier(value, "indexVersion");
  }

  private String requireIdentifier(String value, String name) {
    if (isBlank(value) || value.length() > 512 || !value.equals(value.trim())) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw new IllegalArgumentException(name + " is invalid");
      }
    }
    return value;
  }

  private void requireDimension(int dimension) {
    if (dimension <= 0 || dimension > 65536) {
      throw new IllegalArgumentException("EMBEDDING_DIMENSION_INVALID");
    }
  }

  private void validateEmbedding(float[] embedding, Integer expectedDimension) {
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("EMBEDDING_REQUIRED");
    }
    if (expectedDimension != null && embedding.length != expectedDimension) {
      throw new IllegalArgumentException("EMBEDDING_DIMENSION_MISMATCH");
    }
    for (float value : embedding) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("EMBEDDING_VALUE_INVALID");
    }
  }

  private void requireSearchLimit(int limit) {
    if (limit <= 0 || limit > MAX_SEARCH_LIMIT) {
      throw new IllegalArgumentException("SEARCH_LIMIT_INVALID");
    }
  }

  private List<String> normalizePrincipals(List<String> principals) {
    if (principals == null || principals.isEmpty()) return Collections.emptyList();
    Set<String> normalized = new LinkedHashSet<>();
    for (String principal : principals) {
      if (isBlank(principal)) {
        throw new IllegalArgumentException("DOCUMENT_ACL_PRINCIPAL_INVALID");
      }
      String value = principal.trim();
      if (value.indexOf(',') >= 0 || value.indexOf('|') >= 0) {
        throw new IllegalArgumentException("DOCUMENT_ACL_PRINCIPAL_INVALID");
      }
      if (!"all".equals(value)
          && !"workspace".equals(value)
          && !(value.startsWith("user:") && value.length() > "user:".length())) {
        throw new IllegalArgumentException("DOCUMENT_ACL_PRINCIPAL_INVALID");
      }
      normalized.add(requireIdentifier(value, "aclPrincipal"));
    }
    return new ArrayList<>(normalized);
  }

  private Set<String> parsePrincipals(String raw) {
    if (isBlank(raw)) return Collections.emptySet();
    Set<String> values = new LinkedHashSet<>();
    for (String principal : raw.split(",", -1)) {
      if (isBlank(principal)) return Collections.emptySet();
      values.add(principal.trim());
    }
    return values;
  }

  private String hashText(RedisConnection connection, String redisKey, String field) {
    Object value = connection.execute("HGET", bytes(redisKey), bytes(field));
    return text(value);
  }

  private byte[] hashBytes(RedisConnection connection, String redisKey, String field) {
    Object value = connection.execute("HGET", bytes(redisKey), bytes(field));
    return value instanceof byte[] ? (byte[]) value : null;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private void field(List<byte[]> args, String name, String value) {
    args.add(bytes(name));
    args.add(bytes(value == null ? "" : value));
  }

  private byte[][] combine(byte[] first, List<byte[]> rest) {
    byte[][] values = new byte[rest.size() + 1][];
    values[0] = first;
    for (int i = 0; i < rest.size(); i++) values[i + 1] = rest.get(i);
    return values;
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private String text(Object value) {
    if (value instanceof byte[]) return new String((byte[]) value, StandardCharsets.UTF_8);
    return value == null ? null : String.valueOf(value);
  }

  private double parseDouble(String value, double fallback) {
    try {
      return Double.parseDouble(value);
    } catch (Exception e) {
      return fallback;
    }
  }

  private boolean numericMatches(double expected, String actual) {
    if (actual == null) return false;
    double parsed = parseDouble(actual, Double.NaN);
    return Double.isFinite(parsed) && Math.abs(expected - parsed) <= 0.000001;
  }

  private long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (Exception e) {
      return fallback;
    }
  }
}
