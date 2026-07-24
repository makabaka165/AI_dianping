package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.infrastructure.persistence.JdbcKnowledgeRepository;
import com.hmdp.ai.infrastructure.vector.RedisKnowledgeIndexNaming;
import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeShadowIndexActivationTest {
    @Container
    static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

    @Test
    void switchesActivePointerOnlyWhenShadowIndexIsReady() {
        MYSQL.migrateSchema();
        JdbcTemplate jdbc = MYSQL.jdbcTemplate();
        insertKnowledgeVersions(jdbc);
        JdbcKnowledgeRepository repository = new JdbcKnowledgeRepository(jdbc,
                new AiIdGenerator(), new ObjectMapper());

        repository.publishKnowledgeBaseVersion("tenant-shadow", "workspace-shadow", "kb-shadow", 2, "owner");

        assertThat(jdbc.queryForObject("select active_index_version from ai_knowledge_base where id='kb-shadow'",
                String.class)).isEqualTo("shadow-v2");
        assertThat(jdbc.queryForObject("select active from ai_index_version where code='active-v1'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select rollback_after is not null from ai_index_version "
                + "where code='active-v1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select active from ai_index_version where code='shadow-v2'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from ai_knowledge_base_version where id='kbv-shadow-1'",
                String.class)).isEqualTo("ARCHIVED");
    }

    @Test
    void schemaRepairAuditsDefinitionsAndKeepsReadyIndexOnItsExistingPhysicalName() {
        MYSQL.migrateSchema();
        JdbcTemplate jdbc = MYSQL.jdbcTemplate();

        String vectorName = jdbc.queryForObject(
                "select vector_index_name from ai_index_version where code='shop-enterprise-v1'",
                String.class);
        assertThat(vectorName)
                .isEqualTo(RedisKnowledgeIndexNaming.legacyIndexName("shop-enterprise-v1"));
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=database() "
                        + "and table_name='ai_outbox_event' and column_name='deduplication_key' "
                        + "and data_type='varchar' and character_maximum_length=255 "
                        + "and is_nullable='YES'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.statistics where table_schema=database() "
                        + "and table_name='ai_outbox_dead_letter' "
                        + "and index_name='uk_ai_outbox_dead_letter_consumer' and non_unique=0",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.statistics where table_schema=database() "
                        + "and table_name='ai_index_version' and index_name='uk_ai_index_version_code' "
                        + "and non_unique=0 and seq_in_index=1 and column_name='code'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.statistics where table_schema=database() "
                        + "and table_name='ai_knowledge_base_version' "
                        + "and index_name='uk_ai_knowledge_base_index_version' and non_unique=0 "
                        + "and seq_in_index=1 and column_name='index_version'",
                Integer.class)).isEqualTo(1);
    }

    private void insertKnowledgeVersions(JdbcTemplate jdbc) {
        jdbc.update("insert into ai_knowledge_base "
                        + "(id,tenant_id,workspace_id,code,name,latest_version,active_index_version,status,"
                        + "created_by,updated_by) values ('kb-shadow','tenant-shadow','workspace-shadow',"
                        + "'kb-shadow-code','Shadow KB',2,'active-v1','ACTIVE','owner','owner')");
        String versionSql = "insert into ai_knowledge_base_version "
                + "(id,tenant_id,workspace_id,knowledge_base_id,version,embedding_model_profile_id,"
                + "embedding_dimension,chunking_policy_json,retrieval_policy_json,index_version,index_status,"
                + "status,content_hash,change_note,created_by,updated_by) values (?,?,?,?,?,"
                + "'model-shop-embedding',3,'{}','{}',?,?,?,?,?,'owner','owner')";
        jdbc.update(versionSql, "kbv-shadow-1", "tenant-shadow", "workspace-shadow", "kb-shadow", 1,
                "active-v1", "READY", "PUBLISHED", repeat('a'), "v1");
        jdbc.update(versionSql, "kbv-shadow-2", "tenant-shadow", "workspace-shadow", "kb-shadow", 2,
                "shadow-v2", "READY", "DRAFT", repeat('b'), "v2");
        String indexSql = "insert into ai_index_version "
                + "(id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version,code,"
                + "embedding_model_profile_id,embedding_dimension,vector_index_name,lexical_index_name,"
                + "status,active,build_mode,created_by,updated_by) values (?,?,?,?,?,?,"
                + "'model-shop-embedding',3,?,?,?,?,'SHADOW','owner','owner')";
        jdbc.update(indexSql, "index-shadow-1", "tenant-shadow", "workspace-shadow", "kb-shadow", 1,
                "active-v1", "ai_kb_active_v1", "ai_kb_active_v1", "READY", 1);
        jdbc.update(indexSql, "index-shadow-2", "tenant-shadow", "workspace-shadow", "kb-shadow", 2,
                "shadow-v2", "ai_kb_shadow_v2", "ai_kb_shadow_v2", "READY", 0);
    }

    private String repeat(char value) {
        return String.join("", java.util.Collections.nCopies(64, String.valueOf(value)));
    }
}
