ALTER TABLE ai_tool_call
    ADD COLUMN invocation_id VARCHAR(64) NULL AFTER node_run_id,
    ADD COLUMN input_schema_validation VARCHAR(32) NULL AFTER result_summary,
    ADD COLUMN approval_request_id VARCHAR(64) NULL AFTER input_schema_validation,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER approval_request_id,
    ADD COLUMN circuit_breaker_state VARCHAR(32) NULL AFTER retry_count,
    ADD COLUMN timeout_ms INT NOT NULL DEFAULT 0 AFTER circuit_breaker_state,
    ADD COLUMN result_size_bytes BIGINT NOT NULL DEFAULT 0 AFTER timeout_ms,
    ADD COLUMN artifact_ids_json LONGTEXT NULL AFTER result_size_bytes,
    ADD COLUMN citation_ids_json LONGTEXT NULL AFTER artifact_ids_json;

ALTER TABLE ai_retrieval_record
    ADD COLUMN invocation_id VARCHAR(64) NULL AFTER node_run_id,
    ADD COLUMN selected_chunk_ids_json LONGTEXT NULL AFTER result_chunk_ids_json,
    ADD COLUMN fused_candidate_count INT NOT NULL DEFAULT 0 AFTER lexical_candidate_count,
    ADD COLUMN reranked_candidate_count INT NOT NULL DEFAULT 0 AFTER fused_candidate_count;

UPDATE ai_tool_call
SET node_run_id = LEFT(CONCAT(run_id, ':legacy'), 64)
WHERE node_run_id IS NULL;

UPDATE ai_retrieval_record
SET node_run_id = LEFT(CONCAT(run_id, ':legacy'), 64)
WHERE node_run_id IS NULL;

UPDATE ai_model_call
SET node_run_id = LEFT(CONCAT(run_id, ':legacy'), 64)
WHERE node_run_id IS NULL;

ALTER TABLE ai_tool_call MODIFY node_run_id VARCHAR(64) NOT NULL;
ALTER TABLE ai_retrieval_record MODIFY node_run_id VARCHAR(64) NOT NULL;
ALTER TABLE ai_model_call MODIFY node_run_id VARCHAR(64) NOT NULL;
