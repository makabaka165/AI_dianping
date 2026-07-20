CREATE TABLE IF NOT EXISTS ai_workflow_node (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    workflow_version_id VARCHAR(64) NOT NULL,
    node_code VARCHAR(128) NOT NULL,
    node_type VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    configuration_json LONGTEXT NOT NULL,
    input_mapping_json LONGTEXT NOT NULL,
    output_mapping_json LONGTEXT NOT NULL,
    timeout_ms INT NOT NULL,
    max_attempts INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_workflow_node_code (workflow_version_id, node_code),
    KEY idx_ai_workflow_node_type (tenant_id, workspace_id, workflow_version_id, node_type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_workflow_edge (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    workflow_version_id VARCHAR(64) NOT NULL,
    source_node_code VARCHAR(128) NOT NULL,
    target_node_code VARCHAR(128) NOT NULL,
    condition_json LONGTEXT NULL,
    priority INT NOT NULL DEFAULT 0,
    label VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_workflow_edge (workflow_version_id, source_node_code, target_node_code, priority),
    KEY idx_ai_workflow_edge_source (tenant_id, workspace_id, workflow_version_id, source_node_code, status, deleted),
    KEY idx_ai_workflow_edge_target (workflow_version_id, target_node_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_workflow_state (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    workflow_version_id VARCHAR(64) NOT NULL,
    current_node_codes_json LONGTEXT NOT NULL,
    variables_json LONGTEXT NOT NULL,
    completed_nodes_json LONGTEXT NOT NULL,
    iteration_state_json LONGTEXT NOT NULL,
    waiting_node_code VARCHAR(128) NULL,
    resume_token_hash CHAR(64) NULL,
    expires_at TIMESTAMP(3) NULL,
    status VARCHAR(32) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_workflow_state_run (tenant_id, workspace_id, run_id),
    KEY idx_ai_workflow_state_recovery (status, expires_at, updated_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO ai_tool (id, tenant_id, workspace_id, code, name, description, latest_version, status, created_by, updated_by)
VALUES
('tool-shop-basic-summary','default','default','get-shop-basic-summary','Get shop basic summary','Returns structured public shop fields and review count.',1,'ACTIVE','system','system'),
('tool-shop-exists','default','default','check-shop-exists','Check shop exists','Checks whether a shop exists.',1,'ACTIVE','system','system'),
('tool-shop-detailed-analysis','default','default','get-shop-detailed-analysis','Get shop detailed analysis','Returns structured shop and review analysis.',1,'ACTIVE','system','system'),
('tool-shop-quality-summary','default','default','get-shop-quality-summary','Get shop quality summary','Returns structured high-quality review evidence.',1,'ACTIVE','system','system'),
('tool-shop-ask','default','default','ask-about-shop','Ask about shop','Returns structured shop evidence relevant to a question.',1,'ACTIVE','system','system'),
('tool-shop-compare','default','default','compare-shops','Compare shops','Returns a deterministic structured comparison.',1,'ACTIVE','system','system'),
('tool-shop-recommend','default','default','recommend-shops','Recommend shops','Returns deterministically ranked shop candidates.',1,'ACTIVE','system','system');

INSERT IGNORE INTO ai_tool_version (
    id,tenant_id,workspace_id,tool_id,version,name,description,protocol,input_schema,output_schema,risk_level,
    side_effect,idempotent,timeout_ms,retry_policy_json,required_permissions_json,configuration_json,enabled,status,
    content_hash,change_note,published_at,published_by,created_by,updated_by
) VALUES
('tool-shop-basic-summary-v1','default','default','tool-shop-basic-summary',1,'Get shop basic summary','Returns structured public shop fields and review count.','LOCAL_SKILL','{"type":"object","required":["shopId"],"properties":{"shopId":{"type":"integer","minimum":1}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,3000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"get-shop-basic-summary"}',1,'PUBLISHED','9cf5e40dc326d97f0ec7e1406d934452e64bb585e52e36c24171af8288e84e9b','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-exists-v1','default','default','tool-shop-exists',1,'Check shop exists','Checks whether a shop exists.','LOCAL_SKILL','{"type":"object","required":["shopId"],"properties":{"shopId":{"type":"integer","minimum":1}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,2000,'{"maxAttempts":1}','["AGENT_RUN"]','{"skillCode":"check-shop-exists"}',1,'PUBLISHED','729bea1c9dffb129fd44e6a92b56a51a08d0aef472a4e7eeec53b47853856819','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-detailed-analysis-v1','default','default','tool-shop-detailed-analysis',1,'Get shop detailed analysis','Returns structured shop and review analysis.','LOCAL_SKILL','{"type":"object","required":["shopId"],"properties":{"shopId":{"type":"integer","minimum":1},"reviewLimit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,5000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"get-shop-detailed-analysis"}',1,'PUBLISHED','217ca99bc0e373ae4d4a3a06343aefd5ae34e02c6d34c5de37a7315913b09b15','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-quality-summary-v1','default','default','tool-shop-quality-summary',1,'Get shop quality summary','Returns structured high-quality review evidence.','LOCAL_SKILL','{"type":"object","required":["shopId"],"properties":{"shopId":{"type":"integer","minimum":1},"minLiked":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,5000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"get-shop-quality-summary"}',1,'PUBLISHED','4de1816a6016299b94c09e237bb0fd42c7b43389f9e67de4e442d955c943fac8','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-ask-v1','default','default','tool-shop-ask',1,'Ask about shop','Returns structured shop evidence relevant to a question.','LOCAL_SKILL','{"type":"object","required":["shopId","question"],"properties":{"shopId":{"type":"integer","minimum":1},"question":{"type":"string","minLength":1,"maxLength":2000}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,5000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"ask-about-shop"}',1,'PUBLISHED','acdd60ce3335772c98ae28f2a79cd77baaa56981ed149f2f5b43db587f1ebea4','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-compare-v1','default','default','tool-shop-compare',1,'Compare shops','Returns a deterministic structured comparison.','LOCAL_SKILL','{"type":"object","required":["shopIds"],"properties":{"shopIds":{"type":"array","minItems":2,"maxItems":10,"items":{"type":"integer","minimum":1}},"aspect":{"type":"string","maxLength":200}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,8000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"compare-shops"}',1,'PUBLISHED','0ab9f4037c177b438fdb3ecc4bd76e5f2f29a20366ab3f4dbaf9922eb6570420','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system'),
('tool-shop-recommend-v1','default','default','tool-shop-recommend',1,'Recommend shops','Returns deterministically ranked shop candidates.','LOCAL_SKILL','{"type":"object","required":["preference"],"properties":{"preference":{"type":"string","minLength":1,"maxLength":2000},"category":{"type":"string","maxLength":128},"limit":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','{"type":"object"}','LOW',0,1,8000,'{"maxAttempts":2}','["AGENT_RUN"]','{"skillCode":"recommend-shops"}',1,'PUBLISHED','82699f523c1968c5543e221f8fc331dcd213f8cf276da883c3f8794200ea81bb','Initial local skill.',CURRENT_TIMESTAMP(3),'system','system','system');

INSERT IGNORE INTO ai_agent_tool_binding (id,tenant_id,workspace_id,agent_version_id,tool_version_id,configuration_json,status,created_by,updated_by)
VALUES
('agent-shop-v1-tool-basic','default','default','agent-shop-consultant-v1','tool-shop-basic-summary-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-exists','default','default','agent-shop-consultant-v1','tool-shop-exists-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-analysis','default','default','agent-shop-consultant-v1','tool-shop-detailed-analysis-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-quality','default','default','agent-shop-consultant-v1','tool-shop-quality-summary-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-ask','default','default','agent-shop-consultant-v1','tool-shop-ask-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-compare','default','default','agent-shop-consultant-v1','tool-shop-compare-v1','{}','ACTIVE','system','system'),
('agent-shop-v1-tool-recommend','default','default','agent-shop-consultant-v1','tool-shop-recommend-v1','{}','ACTIVE','system','system');

INSERT IGNORE INTO ai_workflow_node (id,tenant_id,workspace_id,workflow_version_id,node_code,node_type,name,configuration_json,input_mapping_json,output_mapping_json,timeout_ms,max_attempts,status,created_by,updated_by)
VALUES
('wf-shop-v1-start','default','default','workflow-shop-consultant-v1','start','START','Start','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-validate','default','default','workflow-shop-consultant-v1','validate-input','INPUT_VALIDATION','Validate input','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-normalize','default','default','workflow-shop-consultant-v1','normalize-input','INPUT_NORMALIZE','Normalize input','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-intent','default','default','workflow-shop-consultant-v1','classify-intent','INTENT_CLASSIFY','Classify intent','{}','{}','{}',3000,1,'ACTIVE','system','system'),
('wf-shop-v1-entity','default','default','workflow-shop-consultant-v1','extract-entities','ENTITY_EXTRACT','Extract entities','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-branch','default','default','workflow-shop-consultant-v1','route-intent','BRANCH','Route intent','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-summary','default','default','workflow-shop-consultant-v1','summary-llm','LLM','Shop summary','{"useAgentDefaultPrompt":true,"inputMapping":{"question":"$.text","shopData":"$.shopData","knowledge":"$.retrievalResults","memory":"$.memoryRecall"},"outputVariable":"agentOutput","responseFormat":"JSON","maxOutputTokensOverride":1200}','{}','{"result":"agentOutput"}',120000,2,'ACTIVE','system','system'),
('wf-shop-v1-qa','default','default','workflow-shop-consultant-v1','qa-llm','LLM','Shop QA','{"useAgentDefaultPrompt":true,"inputMapping":{"question":"$.text","shopData":"$.shopData","knowledge":"$.retrievalResults","memory":"$.memoryRecall"},"outputVariable":"agentOutput","responseFormat":"JSON","maxOutputTokensOverride":1200}','{}','{"result":"agentOutput"}',120000,2,'ACTIVE','system','system'),
('wf-shop-v1-compare','default','default','workflow-shop-consultant-v1','compare-llm','LLM','Shop compare','{"useAgentDefaultPrompt":true,"inputMapping":{"question":"$.text","shopData":"$.shopData","knowledge":"$.retrievalResults","memory":"$.memoryRecall"},"outputVariable":"agentOutput","responseFormat":"JSON","maxOutputTokensOverride":1200}','{}','{"result":"agentOutput"}',120000,2,'ACTIVE','system','system'),
('wf-shop-v1-recommend','default','default','workflow-shop-consultant-v1','recommend-llm','LLM','Shop recommend','{"useAgentDefaultPrompt":true,"inputMapping":{"question":"$.text","recommendations":"$.recommendations","memory":"$.memoryRecall"},"outputVariable":"agentOutput","responseFormat":"JSON","maxOutputTokensOverride":1200}','{}','{"result":"agentOutput"}',120000,2,'ACTIVE','system','system'),
('wf-shop-v1-unknown','default','default','workflow-shop-consultant-v1','unknown-llm','LLM','Safe general answer','{"useAgentDefaultPrompt":true,"inputMapping":{"question":"$.text","memory":"$.memoryRecall"},"outputVariable":"agentOutput","responseFormat":"JSON","maxOutputTokensOverride":800}','{}','{"result":"agentOutput"}',120000,2,'ACTIVE','system','system'),
('wf-shop-v1-output','default','default','workflow-shop-consultant-v1','validate-output','OUTPUT_VALIDATION','Validate output','{}','{}','{}',1000,1,'ACTIVE','system','system'),
('wf-shop-v1-end','default','default','workflow-shop-consultant-v1','end','END','End','{}','{}','{}',1000,1,'ACTIVE','system','system');

-- Native runtime node types used by the replacement seed: 'TOOL', 'KNOWLEDGE_RETRIEVE', 'MEMORY_RECALL'.

INSERT IGNORE INTO ai_workflow_edge (id,tenant_id,workspace_id,workflow_version_id,source_node_code,target_node_code,condition_json,priority,label,status,created_by,updated_by)
VALUES
('wf-shop-e01','default','default','workflow-shop-consultant-v1','start','validate-input',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e02','default','default','workflow-shop-consultant-v1','validate-input','normalize-input',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e03','default','default','workflow-shop-consultant-v1','normalize-input','classify-intent',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e04','default','default','workflow-shop-consultant-v1','classify-intent','extract-entities',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e05','default','default','workflow-shop-consultant-v1','extract-entities','route-intent',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e06','default','default','workflow-shop-consultant-v1','route-intent','summary-llm','{"eq":[{"var":"intent"},"SHOP_SUMMARY"]}',100,'summary','ACTIVE','system','system'),
('wf-shop-e07','default','default','workflow-shop-consultant-v1','route-intent','qa-llm','{"in":[{"var":"intent"},["SHOP_QA","KNOWLEDGE_QUERY"]]}',90,'qa','ACTIVE','system','system'),
('wf-shop-e08','default','default','workflow-shop-consultant-v1','route-intent','compare-llm','{"eq":[{"var":"intent"},"SHOP_COMPARE"]}',80,'compare','ACTIVE','system','system'),
('wf-shop-e09','default','default','workflow-shop-consultant-v1','route-intent','recommend-llm','{"eq":[{"var":"intent"},"SHOP_RECOMMEND"]}',70,'recommend','ACTIVE','system','system'),
('wf-shop-e10','default','default','workflow-shop-consultant-v1','route-intent','unknown-llm','{"not":{"in":[{"var":"intent"},["SHOP_SUMMARY","SHOP_QA","KNOWLEDGE_QUERY","SHOP_COMPARE","SHOP_RECOMMEND"]]}}',0,'default','ACTIVE','system','system'),
('wf-shop-e11','default','default','workflow-shop-consultant-v1','summary-llm','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e12','default','default','workflow-shop-consultant-v1','qa-llm','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e13','default','default','workflow-shop-consultant-v1','compare-llm','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e14','default','default','workflow-shop-consultant-v1','recommend-llm','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e15','default','default','workflow-shop-consultant-v1','unknown-llm','validate-output',NULL,0,NULL,'ACTIVE','system','system'),
('wf-shop-e16','default','default','workflow-shop-consultant-v1','validate-output','end',NULL,0,NULL,'ACTIVE','system','system');
