ALTER TABLE ai_eval_result
    ADD COLUMN execution_run_id VARCHAR(64) NULL AFTER eval_case_id,
    ADD KEY idx_ai_eval_result_execution_run (tenant_id, workspace_id, execution_run_id, deleted);
