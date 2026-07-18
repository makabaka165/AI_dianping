package com.hmdp.ai.integration;
import org.flywaydb.core.Flyway;import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;import org.testcontainers.junit.jupiter.*;import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
@Tag("integration")@Testcontainers(disabledWithoutDocker=true)class MemoryPersistenceIntegrationTest {@Container static final MySQLContainer<?>MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36")).withDatabaseName("hmdp").withUsername("hmdp").withPassword("test");
    @Test void flywayCreatesConversationMemoryFeedbackAndEvaluationTables(){Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));Integer count=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('ai_conversation','ai_message','ai_memory_fact','ai_feedback','ai_eval_run')",Integer.class);assertThat(count).isEqualTo(5);}}
