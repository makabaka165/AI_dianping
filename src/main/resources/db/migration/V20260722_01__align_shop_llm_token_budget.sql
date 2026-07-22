-- Keep the published model snapshot immutable while correcting the default workflow invocation budget.
UPDATE ai_workflow_node node
JOIN ai_agent_version agent_version
  ON agent_version.workflow_version_id = node.workflow_version_id
 AND agent_version.deleted = 0
JOIN ai_model_profile_version model_version
  ON model_version.id = agent_version.model_profile_version_id
 AND model_version.deleted = 0
SET node.configuration_json = JSON_SET(
        node.configuration_json,
        '$.maxOutputTokensOverride',
        model_version.max_output_tokens),
    node.updated_by = 'system'
WHERE agent_version.id = 'agent-shop-consultant-v1'
  AND node.node_type = 'LLM'
  AND node.status = 'ACTIVE'
  AND node.deleted = 0
  AND JSON_EXTRACT(node.configuration_json, '$.maxOutputTokensOverride') IS NOT NULL
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
          node.configuration_json,
          '$.maxOutputTokensOverride')) AS UNSIGNED) > model_version.max_output_tokens;
