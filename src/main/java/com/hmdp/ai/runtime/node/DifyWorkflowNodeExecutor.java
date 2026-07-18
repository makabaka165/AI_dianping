package com.hmdp.ai.runtime.node;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;import org.springframework.stereotype.Component;import java.util.Collections;import java.util.Set;
@Component public class DifyWorkflowNodeExecutor implements NodeExecutor {private final ToolNodeExecutor delegate;public DifyWorkflowNodeExecutor(ToolNodeExecutor delegate){this.delegate=delegate;}public Set<WorkflowNodeType>supportedTypes(){return Collections.singleton(WorkflowNodeType.DIFY_WORKFLOW);}public NodeExecutionResult execute(NodeExecutionContext context){return delegate.execute(context);}}
