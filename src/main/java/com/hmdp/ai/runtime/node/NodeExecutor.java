package com.hmdp.ai.runtime.node;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;import java.util.Set;
public interface NodeExecutor {Set<WorkflowNodeType> supportedTypes();NodeExecutionResult execute(NodeExecutionContext context);}
