package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.infrastructure.persistence.JdbcKnowledgeRepository;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeShadowIndexActivationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("hmdp").withUsername("hmdp").withPassword("hmdp-test");

    @Test
    void switchesActivePointerOnlyWhenShadowIndexIsReady() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword()));
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
