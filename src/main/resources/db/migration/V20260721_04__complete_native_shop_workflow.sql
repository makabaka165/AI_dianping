-- Complete the default shop-consultant graph without changing checksums of already applied migrations.
DELETE FROM ai_workflow_edge WHERE workflow_version_id = 'workflow-shop-consultant-v1';

UPDATE ai_workflow_node
SET configuration_json = '{"maxParallelism":4,"branchTimeoutMs":30000}', updated_by = 'system'
WHERE id = 'wf-shop-v1-summary-parallel';

UPDATE ai_workflow_node
SET name = 'Check shop exists',
    configuration_json = '{"toolCode":"check-shop-exists","toolVersion":1,"outputVariable":"shopExists","inputMapping":{"shopId":"$.shopId"}}',
    updated_by = 'system'
WHERE id = 'wf-shop-v1-summary-tool';

UPDATE ai_workflow_node
SET configuration_json = '{"mode":"MERGE_OBJECT","inputVariables":["shopExists","shopAnalysis","shopQuality"],"outputVariable":"summaryEvidence"}',
    updated_by = 'system'
WHERE id = 'wf-shop-v1-summary-join';

UPDATE ai_workflow_node
SET configuration_json = '{"mode":"MERGE_OBJECT","inputVariables":["shopData","shopBasic"],"outputVariable":"qaEvidence"}',
    updated_by = 'system'
WHERE id = 'wf-shop-v1-qa-join';

UPDATE ai_workflow_node
SET node_type = 'FOREACH', name = 'Analyze each shop',
    configuration_json = '{"collectionVariable":"shopIds","itemVariable":"shopId","indexVariable":"shopIndex","resultVariable":"shopData","maxParallelism":4,"branchTimeoutMs":15000,"failurePolicy":"FAIL_FAST"}',
    timeout_ms = 60000, max_attempts = 1, updated_by = 'system'
WHERE id = 'wf-shop-v1-compare-tool';

INSERT INTO ai_workflow_node
(id,tenant_id,workspace_id,workflow_version_id,node_code,node_type,name,configuration_json,input_mapping_json,output_mapping_json,timeout_ms,max_attempts,status,created_by,updated_by)
VALUES
('wf-shop-v1-summary-slots','default','default','workflow-shop-consultant-v1','summary-slot-check','BRANCH','Check summary slots','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-summary-feedback','default','default','workflow-shop-consultant-v1','summary-feedback','HUMAN_FEEDBACK','Request summary shop','{"questions":["Please provide the shopId to summarize."],"requiredVariables":["shopId"]}','{}','{}',86400000,1,'ACTIVE','system','system'),
('wf-shop-v1-summary-analysis','default','default','workflow-shop-consultant-v1','summary-analysis','TOOL','Detailed shop analysis','{"toolCode":"get-shop-detailed-analysis","toolVersion":1,"outputVariable":"shopAnalysis","inputMapping":{"shopId":"$.shopId"}}','{}','{}',5000,2,'ACTIVE','system','system'),
('wf-shop-v1-summary-quality','default','default','workflow-shop-consultant-v1','summary-quality','TOOL','Shop quality summary','{"toolCode":"get-shop-quality-summary","toolVersion":1,"outputVariable":"shopQuality","inputMapping":{"shopId":"$.shopId"}}','{}','{}',5000,2,'ACTIVE','system','system'),
('wf-shop-v1-qa-slots','default','default','workflow-shop-consultant-v1','qa-slot-check','BRANCH','Check QA slots','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-qa-feedback','default','default','workflow-shop-consultant-v1','qa-feedback','HUMAN_FEEDBACK','Request QA shop','{"questions":["Please provide the shopId for this question."],"requiredVariables":["shopId"]}','{}','{}',86400000,1,'ACTIVE','system','system'),
('wf-shop-v1-qa-basic','default','default','workflow-shop-consultant-v1','qa-basic','TOOL','Basic shop summary','{"toolCode":"get-shop-basic-summary","toolVersion":1,"outputVariable":"shopBasic","inputMapping":{"shopId":"$.shopId"}}','{}','{}',3000,2,'ACTIVE','system','system'),
('wf-shop-v1-compare-slots','default','default','workflow-shop-consultant-v1','compare-slot-check','BRANCH','Check compare slots','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-compare-feedback','default','default','workflow-shop-consultant-v1','compare-feedback','HUMAN_FEEDBACK','Request compare shops','{"questions":["Please provide at least two shopIds to compare."],"requiredVariables":["shopIds"]}','{}','{}',86400000,1,'ACTIVE','system','system'),
('wf-shop-v1-compare-analysis','default','default','workflow-shop-consultant-v1','compare-analysis','TOOL','Detailed analysis per shop','{"toolCode":"get-shop-detailed-analysis","toolVersion":1,"outputVariable":"shopAnalysis","inputMapping":{"shopId":"$.shopId"}}','{}','{}',5000,2,'ACTIVE','system','system'),
('wf-shop-v1-compare-quality','default','default','workflow-shop-consultant-v1','compare-quality','TOOL','Quality summary per shop','{"toolCode":"get-shop-quality-summary","toolVersion":1,"outputVariable":"shopQuality","inputMapping":{"shopId":"$.shopId"}}','{}','{}',5000,2,'ACTIVE','system','system'),
('wf-shop-v1-compare-join','default','default','workflow-shop-consultant-v1','compare-join','JOIN','Collect per-shop evidence','{"mode":"MERGE_OBJECT","inputVariables":["shopAnalysis","shopQuality"],"outputVariable":"shopEvidence"}','{}','{}',3000,1,'ACTIVE','system','system'),
('wf-shop-v1-compare-knowledge','default','default','workflow-shop-consultant-v1','compare-knowledge','KNOWLEDGE_RETRIEVE','Comparison knowledge','{"knowledgeBaseId":"kb-shop-enterprise","knowledgeBaseVersion":1,"queryVariable":"text","resultVariable":"retrievalResults","topK":6}','{}','{}',10000,2,'ACTIVE','system','system'),
('wf-shop-v1-recommend-slots','default','default','workflow-shop-consultant-v1','recommend-slot-check','BRANCH','Check recommendation slots','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-recommend-feedback','default','default','workflow-shop-consultant-v1','recommend-feedback','HUMAN_FEEDBACK','Request recommendation preferences','{"questions":["Please provide a category or preference for recommendations."],"requiredVariables":["preference"]}','{}','{}',86400000,1,'ACTIVE','system','system'),
('wf-shop-v1-unknown-confidence','default','default','workflow-shop-consultant-v1','unknown-confidence','BRANCH','Check unknown confidence','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-unknown-feedback','default','default','workflow-shop-consultant-v1','unknown-feedback','HUMAN_FEEDBACK','Clarify request','{"questions":["Please clarify what shop or knowledge task you want to complete."]}','{}','{}',86400000,1,'ACTIVE','system','system');

INSERT INTO ai_workflow_edge
(id,tenant_id,workspace_id,workflow_version_id,source_node_code,target_node_code,condition_json,priority,label,status,created_by,updated_by)
VALUES
('wf-complete-e001','default','default','workflow-shop-consultant-v1','start','validate-input',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e002','default','default','workflow-shop-consultant-v1','validate-input','normalize-input',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e003','default','default','workflow-shop-consultant-v1','normalize-input','classify-intent',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e004','default','default','workflow-shop-consultant-v1','classify-intent','extract-entities',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e005','default','default','workflow-shop-consultant-v1','extract-entities','route-intent',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e006','default','default','workflow-shop-consultant-v1','route-intent','summary-slot-check','{"eq":[{"var":"intent"},"SHOP_SUMMARY"]}',100,'summary','ACTIVE','system','system'),
('wf-complete-e007','default','default','workflow-shop-consultant-v1','route-intent','qa-slot-check','{"in":[{"var":"intent"},["SHOP_QA","KNOWLEDGE_QUERY"]]}',90,'qa','ACTIVE','system','system'),
('wf-complete-e008','default','default','workflow-shop-consultant-v1','route-intent','compare-slot-check','{"eq":[{"var":"intent"},"SHOP_COMPARE"]}',80,'compare','ACTIVE','system','system'),
('wf-complete-e009','default','default','workflow-shop-consultant-v1','route-intent','recommend-slot-check','{"eq":[{"var":"intent"},"SHOP_RECOMMEND"]}',70,'recommend','ACTIVE','system','system'),
('wf-complete-e010','default','default','workflow-shop-consultant-v1','route-intent','unknown-confidence',NULL,0,'default','ACTIVE','system','system'),

('wf-complete-e011','default','default','workflow-shop-consultant-v1','summary-slot-check','summary-parallel','{"exists":{"var":"shopId"}}',10,'ready','ACTIVE','system','system'),
('wf-complete-e012','default','default','workflow-shop-consultant-v1','summary-slot-check','summary-feedback',NULL,0,'default','ACTIVE','system','system'),
('wf-complete-e013','default','default','workflow-shop-consultant-v1','summary-feedback','summary-parallel',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e014','default','default','workflow-shop-consultant-v1','summary-parallel','summary-tool',NULL,40,'exists','ACTIVE','system','system'),
('wf-complete-e015','default','default','workflow-shop-consultant-v1','summary-parallel','summary-analysis',NULL,30,'analysis','ACTIVE','system','system'),
('wf-complete-e016','default','default','workflow-shop-consultant-v1','summary-parallel','summary-quality',NULL,20,'quality','ACTIVE','system','system'),
('wf-complete-e017','default','default','workflow-shop-consultant-v1','summary-parallel','summary-knowledge',NULL,10,'knowledge','ACTIVE','system','system'),
('wf-complete-e018','default','default','workflow-shop-consultant-v1','summary-tool','summary-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e019','default','default','workflow-shop-consultant-v1','summary-analysis','summary-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e020','default','default','workflow-shop-consultant-v1','summary-quality','summary-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e021','default','default','workflow-shop-consultant-v1','summary-knowledge','summary-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e022','default','default','workflow-shop-consultant-v1','summary-join','summary-memory',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e023','default','default','workflow-shop-consultant-v1','summary-memory','summary-llm',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e024','default','default','workflow-shop-consultant-v1','summary-llm','summary-citation',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e025','default','default','workflow-shop-consultant-v1','summary-citation','validate-output',NULL,0,NULL,'ACTIVE','system','system'),

('wf-complete-e026','default','default','workflow-shop-consultant-v1','qa-slot-check','qa-parallel','{"exists":{"var":"shopId"}}',10,'ready','ACTIVE','system','system'),
('wf-complete-e027','default','default','workflow-shop-consultant-v1','qa-slot-check','qa-feedback',NULL,0,'default','ACTIVE','system','system'),
('wf-complete-e028','default','default','workflow-shop-consultant-v1','qa-feedback','qa-parallel',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e029','default','default','workflow-shop-consultant-v1','qa-parallel','qa-tool',NULL,40,'answer','ACTIVE','system','system'),
('wf-complete-e030','default','default','workflow-shop-consultant-v1','qa-parallel','qa-basic',NULL,30,'basic','ACTIVE','system','system'),
('wf-complete-e031','default','default','workflow-shop-consultant-v1','qa-parallel','qa-knowledge',NULL,20,'knowledge','ACTIVE','system','system'),
('wf-complete-e032','default','default','workflow-shop-consultant-v1','qa-parallel','qa-memory',NULL,10,'memory','ACTIVE','system','system'),
('wf-complete-e033','default','default','workflow-shop-consultant-v1','qa-tool','qa-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e034','default','default','workflow-shop-consultant-v1','qa-basic','qa-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e035','default','default','workflow-shop-consultant-v1','qa-knowledge','qa-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e036','default','default','workflow-shop-consultant-v1','qa-memory','qa-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e037','default','default','workflow-shop-consultant-v1','qa-join','qa-llm',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e038','default','default','workflow-shop-consultant-v1','qa-llm','qa-citation',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e039','default','default','workflow-shop-consultant-v1','qa-citation','validate-output',NULL,0,NULL,'ACTIVE','system','system'),

('wf-complete-e040','default','default','workflow-shop-consultant-v1','compare-slot-check','compare-tool','{"and":[{"exists":{"var":"shopId1"}},{"exists":{"var":"shopId2"}}]}',10,'ready','ACTIVE','system','system'),
('wf-complete-e041','default','default','workflow-shop-consultant-v1','compare-slot-check','compare-feedback',NULL,0,'default','ACTIVE','system','system'),
('wf-complete-e042','default','default','workflow-shop-consultant-v1','compare-feedback','compare-tool',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e043','default','default','workflow-shop-consultant-v1','compare-tool','compare-analysis',NULL,10,'body','ACTIVE','system','system'),
('wf-complete-e044','default','default','workflow-shop-consultant-v1','compare-tool','compare-memory',NULL,0,'exit','ACTIVE','system','system'),
('wf-complete-e045','default','default','workflow-shop-consultant-v1','compare-analysis','compare-quality',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e046','default','default','workflow-shop-consultant-v1','compare-quality','compare-join',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e047','default','default','workflow-shop-consultant-v1','compare-join','compare-knowledge',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e048','default','default','workflow-shop-consultant-v1','compare-knowledge','compare-memory',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e049','default','default','workflow-shop-consultant-v1','compare-memory','compare-llm',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e050','default','default','workflow-shop-consultant-v1','compare-llm','compare-citation',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e051','default','default','workflow-shop-consultant-v1','compare-citation','validate-output',NULL,0,NULL,'ACTIVE','system','system'),

('wf-complete-e052','default','default','workflow-shop-consultant-v1','recommend-slot-check','recommend-tool','{"or":[{"exists":{"var":"preference"}},{"exists":{"var":"category"}}]}',10,'ready','ACTIVE','system','system'),
('wf-complete-e053','default','default','workflow-shop-consultant-v1','recommend-slot-check','recommend-feedback',NULL,0,'default','ACTIVE','system','system'),
('wf-complete-e054','default','default','workflow-shop-consultant-v1','recommend-feedback','recommend-tool',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e055','default','default','workflow-shop-consultant-v1','recommend-tool','recommend-memory',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e056','default','default','workflow-shop-consultant-v1','recommend-memory','recommend-llm',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e057','default','default','workflow-shop-consultant-v1','recommend-llm','recommend-citation',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e058','default','default','workflow-shop-consultant-v1','recommend-citation','validate-output',NULL,0,NULL,'ACTIVE','system','system'),

('wf-complete-e059','default','default','workflow-shop-consultant-v1','unknown-confidence','unknown-memory','{"gte":[{"var":"intentConfidence"},0.6]}',10,'safe-answer','ACTIVE','system','system'),
('wf-complete-e060','default','default','workflow-shop-consultant-v1','unknown-confidence','unknown-feedback',NULL,0,'default','ACTIVE','system','system'),
('wf-complete-e061','default','default','workflow-shop-consultant-v1','unknown-feedback','unknown-memory',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e062','default','default','workflow-shop-consultant-v1','unknown-memory','unknown-llm',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e063','default','default','workflow-shop-consultant-v1','unknown-llm','unknown-citation',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e064','default','default','workflow-shop-consultant-v1','unknown-citation','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-complete-e065','default','default','workflow-shop-consultant-v1','validate-output','end',NULL,0,NULL,'ACTIVE','system','system');
