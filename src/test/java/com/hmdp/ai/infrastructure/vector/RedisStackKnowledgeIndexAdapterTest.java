package com.hmdp.ai.infrastructure.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.IndexVerificationResult;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.test.util.ReflectionTestUtils;

class RedisStackKnowledgeIndexAdapterTest {
  private static final String TENANT = "tenant";
  private static final String WORKSPACE = "workspace";
  private static final String KB = "kb";
  private static final String VERSION = "kb-kb-v1";
  private static final String INDEX = RedisKnowledgeIndexNaming.indexName(VERSION);
  private static final String PREFIX = "hmdp:ai:kb:kb-kb-v1:chunk:";

  @Test
  void validatesSchemaWhenIndexAlreadyExists() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    AtomicBoolean infoRequested = new AtomicBoolean();
    Object indexInfo = indexInfo(PREFIX, 3);
    LettuceConnection redis =
        mock(
            LettuceConnection.class,
            invocation -> {
              Object[] arguments = invocation.getArguments();
              if ("execute".equals(invocation.getMethod().getName()) && arguments.length > 0) {
                if ("FT.CREATE".equals(arguments[0])) {
                  throw new DataAccessResourceFailureException("Index already exists");
                }
                if ("FT.INFO".equals(arguments[0])) {
                  infoRequested.set(true);
                  return indexInfo;
                }
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    when(connections.getConnection()).thenReturn(redis);

    new RedisStackKnowledgeIndexAdapter(connections, repository).ensureIndex(VERSION, 3);

    assertThat(infoRequested).isTrue();
  }

  @Test
  void rejectsExistingIndexWithPrefixOrDimensionDrift() {
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(
            mock(RedisConnectionFactory.class), mock(KnowledgeRepository.class));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    adapter, "validateIndexDefinition", indexInfo("wrong-prefix", 3), INDEX, PREFIX, 3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_INDEX_SCHEMA_MISMATCH: prefix");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    adapter, "validateIndexDefinition", indexInfo(PREFIX, 2), INDEX, PREFIX, 3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_INDEX_SCHEMA_MISMATCH: vector dimension");
  }

  @Test
  void rejectsExistingIndexWithTextWeightDrift() {
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(
            mock(RedisConnectionFactory.class), mock(KnowledgeRepository.class));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    adapter,
                    "validateIndexDefinition",
                    indexInfo(PREFIX, 3, "0.5", "2"),
                    INDEX,
                    PREFIX,
                    3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_INDEX_SCHEMA_MISMATCH: content weight");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    adapter,
                    "validateIndexDefinition",
                    indexInfo(PREFIX, 3, "1", "1.5"),
                    INDEX,
                    PREFIX,
                    3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_INDEX_SCHEMA_MISMATCH: searchText weight");
  }

  @Test
  void rejectsMixedScopeBatchBeforeLookingUpAcl() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);

    KnowledgeChunk otherTenant = chunk("chunk-2", "other-tenant", WORKSPACE);

    assertThatThrownBy(() -> adapter.index(Arrays.asList(chunk("chunk-1", TENANT, WORKSPACE), otherTenant)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CHUNK_BATCH_SCOPE_MISMATCH");
    verifyNoInteractions(repository, connections);
  }

  @Test
  void rejectsNullOrMismatchedEmbeddingsBeforeOpeningPipeline() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);
    KnowledgeChunk invalid =
        new KnowledgeChunk(
            "chunk",
            TENANT,
            WORKSPACE,
            KB,
            1,
            "document",
            1,
            "document-version",
            VERSION,
            0,
            "content",
            "content",
            "hash",
            3,
            new float[] {1, 0},
            1,
            "text/plain",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "{}");

    assertThatThrownBy(() -> adapter.index(Collections.singletonList(invalid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("EMBEDDING_DIMENSION_MISMATCH");
    verifyNoInteractions(repository, connections);
  }

  @Test
  void rejectsMixedEmbeddingDimensionsBeforeLookingUpAcl() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);

    assertThatThrownBy(
            () ->
                adapter.index(
                    Arrays.asList(
                        chunk("chunk-1", TENANT, WORKSPACE),
                        chunkWithDimension("chunk-2", TENANT, WORKSPACE, 2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CHUNK_BATCH_DIMENSION_MISMATCH");
    verifyNoInteractions(repository, connections);
  }

  @Test
  void rejectsAclPrincipalsOutsideTheSupportedQueryVocabulary() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    when(repository.findDocumentReadPrincipals(eq(TENANT), eq(WORKSPACE), any()))
        .thenReturn(Collections.singletonMap("document", Collections.singletonList("group:ops")));
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);

    assertThatThrownBy(() -> adapter.index(Collections.singletonList(chunk("chunk-1", TENANT, WORKSPACE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DOCUMENT_ACL_PRINCIPAL_INVALID");
    verify(connections, never()).getConnection();
  }

  @Test
  void rejectsBlankAclPrincipalInsteadOfSilentlyDroppingIt() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    when(repository.findDocumentReadPrincipals(eq(TENANT), eq(WORKSPACE), any()))
        .thenReturn(Collections.singletonMap("document", Arrays.asList("workspace", " ")));
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);

    assertThatThrownBy(
            () -> adapter.index(Collections.singletonList(chunk("chunk-1", TENANT, WORKSPACE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DOCUMENT_ACL_PRINCIPAL_INVALID");
    verify(connections, never()).getConnection();
  }

  @Test
  void verificationRequiresExactAclAndMatchingChunkMetadata() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    RedisConnection redis = mock(RedisConnection.class);
    KnowledgeRepository repository = mock(KnowledgeRepository.class);
    when(connections.getConnection()).thenReturn(redis);
    when(repository.findDocumentReadPrincipals(eq(TENANT), eq(WORKSPACE), any()))
        .thenReturn(Collections.singletonMap("document", Collections.singletonList("workspace")));
    when(redis.execute(eq("HGET"), any(byte[].class), any(byte[].class)))
        .thenAnswer(
            invocation -> {
              byte[] fieldBytes = invocation.getArgument(2);
              String field = new String(fieldBytes, StandardCharsets.UTF_8);
              switch (field) {
                case "tenantId":
                  return bytes(TENANT);
                case "workspaceId":
                  return bytes(WORKSPACE);
                case "knowledgeBaseId":
                  return bytes(KB);
                case "indexVersion":
                  return bytes(VERSION);
                case "documentId":
                  return bytes("document");
                case "documentVersion":
                  return bytes("1");
                case "chunkId":
                  return bytes("chunk-1");
                case "status":
                  return bytes("PUBLISHED");
                case "content":
                  return bytes("excellent service attitude");
                case "searchText":
                  return bytes("excellent service attitude");
                case "qualityScore":
                  return bytes("1.0");
                case "effectiveAt":
                  return bytes("0");
                case "expiredAt":
                  return bytes(String.valueOf(Long.MAX_VALUE));
                case "allowedUsers":
                  return bytes("workspace,user:stale");
                case "embedding":
                  return new byte[3 * Float.BYTES];
                default:
                  return null;
              }
            });

    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, repository);
    RedisStackKnowledgeIndexAdapter spy = org.mockito.Mockito.spy(adapter);
    doReturn(Collections.singletonList(new IndexHit("chunk-1", 0.9)))
        .when(spy)
        .vectorSearch(any(), any(), anyInt());
    doReturn(Collections.singletonList(new IndexHit("chunk-1", 1.0)))
        .when(spy)
        .lexicalSearch(any(), anyString(), anyInt());

    IndexVerificationResult result =
        spy.verify(
            new KnowledgeSearchScope(TENANT, WORKSPACE, KB, VERSION, "user"),
            Collections.singletonList(chunk("chunk-1", TENANT, WORKSPACE)));

    assertThat(result.getIndexedChunkCount()).isEqualTo(1);
    assertThat(result.isDimensionPassed()).isFalse();
    assertThat(result.isAclPassed()).isFalse();
    assertThat(result.isValid()).isFalse();
  }

  @Test
  void parsesResp2AndResp3SearchShapes() {
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(mock(RedisConnectionFactory.class), mock(KnowledgeRepository.class));
    List<?> resp2Vector =
        Arrays.asList(
            1L,
            bytes("hmdp:ai:kb:kb-kb-v1:chunk:chunk-1"),
            Arrays.asList(bytes("chunkId"), bytes("chunk-1"), bytes("vector_distance"), bytes("0.25")));
    @SuppressWarnings("unchecked")
    List<IndexHit> vector =
        (List<IndexHit>) ReflectionTestUtils.invokeMethod(adapter, "parse", resp2Vector, true);
    assertThat(vector).singleElement().satisfies(hit -> assertThat(hit.getChunkId()).isEqualTo("chunk-1"));
    assertThat(vector.get(0).getScore()).isEqualTo(0.75);

    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("chunkId", bytes("chunk-2"));
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", bytes("redis-key"));
    item.put("score", bytes("2.5"));
    item.put("extra_attributes", attributes);
    Map<String, Object> resp3 = new LinkedHashMap<>();
    resp3.put("results", Collections.singletonList(item));

    @SuppressWarnings("unchecked")
    List<IndexHit> lexical =
        (List<IndexHit>) ReflectionTestUtils.invokeMethod(adapter, "parse", resp3, false);
    assertThat(lexical).singleElement().satisfies(hit -> assertThat(hit.getChunkId()).isEqualTo("chunk-2"));
    assertThat(lexical.get(0).getScore()).isEqualTo(2.5);
  }

  @Test
  void fallsBackToLegacyIndexWhenTheV2IndexIsNotPresent() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    AtomicReference<String> firstIndex = new AtomicReference<>();
    AtomicReference<String> secondIndex = new AtomicReference<>();
    LettuceConnection redis =
        mock(
            LettuceConnection.class,
            invocation -> {
              Object[] arguments = invocation.getArguments();
              if ("execute".equals(invocation.getMethod().getName())
                  && arguments.length > 0
                  && "FT.SEARCH".equals(arguments[0])) {
                String requested = firstByteArgument(arguments);
                if (firstIndex.get() == null) {
                  firstIndex.set(requested);
                  throw new DataAccessResourceFailureException("Unknown index");
                }
                secondIndex.set(requested);
                return Arrays.asList(
                    1L,
                    bytes("hmdp:ai:kb:kb-kb-v1:chunk:chunk-1"),
                    Arrays.asList(bytes("chunkId"), bytes("chunk-1")));
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    when(connections.getConnection()).thenReturn(redis);

    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, mock(KnowledgeRepository.class));
    List<IndexHit> hits =
        adapter.vectorSearch(
            new KnowledgeSearchScope(TENANT, WORKSPACE, KB, VERSION, "user"),
            new float[] {1, 0, 0},
            1);

    assertThat(firstIndex.get()).isEqualTo(RedisKnowledgeIndexNaming.indexName(VERSION));
    assertThat(secondIndex.get()).isEqualTo(RedisKnowledgeIndexNaming.legacyIndexName(VERSION));
    assertThat(hits).extracting(IndexHit::getChunkId).containsExactly("chunk-1");
  }

  @Test
  void validatesAllIndexCandidatesBeforeDroppingAnyData() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    String legacyIndex = RedisKnowledgeIndexNaming.legacyIndexName(VERSION);
    AtomicBoolean dropRequested = new AtomicBoolean();
    LettuceConnection redis =
        mock(
            LettuceConnection.class,
            invocation -> {
              Object[] arguments = invocation.getArguments();
              if ("execute".equals(invocation.getMethod().getName()) && arguments.length > 0) {
                if ("FT.INFO".equals(arguments[0])) {
                  String requested = firstByteArgument(arguments);
                  if (INDEX.equals(requested)) return indexInfoFor(INDEX, PREFIX, 3);
                  if (legacyIndex.equals(requested)) {
                    return indexInfoFor(
                        legacyIndex, "hmdp:ai:kb:another-version:chunk:", 3);
                  }
                }
                if ("FT.DROPINDEX".equals(arguments[0])) dropRequested.set(true);
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    when(connections.getConnection()).thenReturn(redis);

    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, mock(KnowledgeRepository.class));

    assertThatThrownBy(() -> adapter.drop(VERSION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_INDEX_SCHEMA_MISMATCH: prefix");
    assertThat(dropRequested).isFalse();
  }

  @Test
  void dropShouldExposeMissingRediSearchModule() {
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    LettuceConnection redis =
        mock(
            LettuceConnection.class,
            invocation -> {
              Object[] arguments = invocation.getArguments();
              if ("execute".equals(invocation.getMethod().getName())
                  && arguments.length > 0
                  && "FT.INFO".equals(arguments[0])) {
                throw new DataAccessResourceFailureException("ERR unknown command 'FT.INFO'");
              }
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    when(connections.getConnection()).thenReturn(redis);
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(connections, mock(KnowledgeRepository.class));

    assertThatThrownBy(() -> adapter.drop(VERSION))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessageContaining("unknown command");
  }

  @Test
  void boundsSearchInputsAndEscapesQueryOperators() {
    RedisStackKnowledgeIndexAdapter adapter =
        new RedisStackKnowledgeIndexAdapter(mock(RedisConnectionFactory.class), mock(KnowledgeRepository.class));

    assertThatThrownBy(
            () ->
                adapter.vectorSearch(
                    new KnowledgeSearchScope(TENANT, WORKSPACE, KB, VERSION, "user"),
                    new float[] {1},
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SEARCH_LIMIT_INVALID");
    assertThatThrownBy(
            () ->
                adapter.vectorSearch(
                    new KnowledgeSearchScope(TENANT, WORKSPACE, KB, VERSION, "user"),
                    new float[] {Float.NaN},
                    1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("EMBEDDING_VALUE_INVALID");

    String escaped =
        (String) ReflectionTestUtils.invokeMethod(adapter, "escapeQueryToken", "a:b|c*d");
    assertThat(escaped).isEqualTo("a\\:b\\|c\\*d");
  }

  private KnowledgeChunk chunk(String id, String tenant, String workspace) {
    return chunkWithDimension(id, tenant, workspace, 3);
  }

  private KnowledgeChunk chunkWithDimension(
      String id, String tenant, String workspace, int dimension) {
    return new KnowledgeChunk(
        id,
        tenant,
        workspace,
        KB,
        1,
        "document",
        1,
        "document-version",
        VERSION,
        0,
        "excellent service attitude",
        "excellent service attitude",
        "hash-" + id,
        dimension,
        dimension == 2 ? new float[] {1, 0} : new float[] {1, 0, 0},
        1,
        "text/plain",
        1,
        "Service",
        "Service",
        null,
        null,
        null,
        null,
        null,
        0,
        26,
        "{}");
  }

  private List<Object> indexInfo(String prefix, int dimension) {
    return indexInfo(prefix, dimension, "1", "2");
  }

  private List<Object> indexInfo(
      String prefix, int dimension, String contentWeight, String searchTextWeight) {
    List<Object> attributes =
        Arrays.asList(
            attribute("tenantId", "TAG"),
            attribute("workspaceId", "TAG"),
            attribute("knowledgeBaseId", "TAG"),
            attribute("indexVersion", "TAG"),
            attribute("documentId", "TAG"),
            attribute("documentVersion", "NUMERIC"),
            attribute("chunkId", "TAG"),
            attribute("status", "TAG"),
            attribute("allowedUsers", "TAG"),
            attribute("content", "TEXT", "WEIGHT", contentWeight),
            attribute("searchText", "TEXT", "WEIGHT", searchTextWeight),
            attribute("qualityScore", "NUMERIC"),
            attribute("effectiveAt", "NUMERIC"),
            attribute("expiredAt", "NUMERIC"),
            attribute(
                "embedding",
                "VECTOR",
                "ALGORITHM",
                "HNSW",
                "DATA_TYPE",
                "FLOAT32",
                "DIM",
                String.valueOf(dimension),
                "DISTANCE_METRIC",
                "COSINE"));
    return Arrays.asList(
        bytes("index_name"),
        bytes(INDEX),
        bytes("index_definition"),
        Arrays.asList(
            bytes("key_type"),
            bytes("HASH"),
            bytes("prefixes"),
            Collections.singletonList(bytes(prefix))),
        bytes("attributes"),
        attributes);
  }

  private List<Object> indexInfoFor(String index, String prefix, int dimension) {
    List<Object> info = indexInfo(prefix, dimension);
    info.set(1, bytes(index));
    return info;
  }

  private List<Object> attribute(String name, String type, String... properties) {
    List<Object> values = new ArrayList<>();
    values.add(bytes("identifier"));
    values.add(bytes(name));
    values.add(bytes("attribute"));
    values.add(bytes(name));
    values.add(bytes("type"));
    values.add(bytes(type));
    for (int index = 0; index < properties.length; index += 2) {
      values.add(bytes(properties[index]));
      values.add(bytes(properties[index + 1]));
    }
    return values;
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private String firstByteArgument(Object[] arguments) {
    for (int index = 1; index < arguments.length; index++) {
      if (arguments[index] instanceof byte[]) {
        return new String((byte[]) arguments[index], StandardCharsets.UTF_8);
      }
      if (arguments[index] instanceof byte[][] && ((byte[][]) arguments[index]).length > 0) {
        return new String(((byte[][]) arguments[index])[0], StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("Redis command has no byte arguments");
  }
}
