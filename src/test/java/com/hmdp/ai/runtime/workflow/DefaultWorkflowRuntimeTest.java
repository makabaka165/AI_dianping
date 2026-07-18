package com.hmdp.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.artifact.ResponseBlock;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import com.hmdp.ai.runtime.node.NodeExecutor;
import com.hmdp.ai.runtime.node.WorkflowNodeRegistry;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultWorkflowRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ThreadPoolTaskExecutor executor;
    private NodeRunRepository nodeRuns;
    private InMemoryWorkflowStateRepository states;
    private WorkflowNodeRegistry registry;
    private WorkflowPauseCoordinator pauses;
    private final AtomicInteger claims = new AtomicInteger();

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20); executor.initialize();
        nodeRuns = mock(NodeRunRepository.class);
        when(nodeRuns.start(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new NodeRunClaim("node-" + claims.incrementAndGet(), true, null));
        states = new InMemoryWorkflowStateRepository();
        registry = mock(WorkflowNodeRegistry.class);
        when(registry.require(any())).thenReturn(new DeterministicExecutor(mapper));
        pauses = mock(WorkflowPauseCoordinator.class);
        doNothing().when(pauses).pause(any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @AfterEach void tearDown(){executor.shutdown();}

    @Test
    void executesCompleteParallelSubgraphsBeforeJoin() {
        WorkflowDefinition workflow = workflow(
                nodes(node("start",WorkflowNodeType.START,"{}"),node("parallel",WorkflowNodeType.PARALLEL,"{\"maxParallelism\":2}"),node("a",WorkflowNodeType.DATA_TRANSFORM,"{}"),node("a2",WorkflowNodeType.DATA_TRANSFORM,"{}"),node("b",WorkflowNodeType.DATA_TRANSFORM,"{}"),node("b2",WorkflowNodeType.DATA_TRANSFORM,"{}"),node("join",WorkflowNodeType.JOIN,"{}"),node("llm",WorkflowNodeType.LLM,"{}"),node("end",WorkflowNodeType.END,"{}")),
                edges(edge("start","parallel",null),edge("parallel","a",null),edge("parallel","b",null),edge("a","a2",null),edge("a2","join",null),edge("b","b2",null),edge("b2","join",null),edge("join","llm",null),edge("llm","end",null)));

        AgentRunOutput output = runtime().execute(workflow, null, context(), input());

        assertEquals("a,a2,b,b2", output.getAnswer());
        assertEquals(WorkflowStateStatus.COMPLETED, states.state.getStatus());
    }

    @Test
    void foreachExecutesBodyForEveryItemAndPreservesResultOrder() {
        WorkflowDefinition workflow = workflow(
                nodes(node("start",WorkflowNodeType.START,"{}"),node("each",WorkflowNodeType.FOREACH,"{\"collectionVariable\":\"items\",\"itemVariable\":\"item\",\"resultVariable\":\"results\",\"maxParallelism\":2}"),node("body",WorkflowNodeType.DATA_TRANSFORM,"{}"),node("join",WorkflowNodeType.JOIN,"{}"),node("llm",WorkflowNodeType.LLM,"{}"),node("end",WorkflowNodeType.END,"{}")),
                edges(edge("start","each",null),edgeLabel("each","body","body"),edge("body","join",null),edge("join","llm",null),edge("llm","end",null)));
        ExecutionContext context = context(Collections.singletonMap("items", java.util.Arrays.asList("x","y","z")));

        AgentRunOutput output = runtime().execute(workflow, null, context, input());

        assertEquals("3", output.getAnswer());
    }

    @Test
    void humanNodeProducesResumableWaitingStateInsteadOfFailure() {
        WorkflowDefinition workflow = workflow(
                nodes(node("start",WorkflowNodeType.START,"{}"),node("human",WorkflowNodeType.HUMAN_FEEDBACK,"{\"question\":\"shopId?\"}"),node("llm",WorkflowNodeType.LLM,"{}"),node("end",WorkflowNodeType.END,"{}")),
                edges(edge("start","human",null),edge("human","llm",null),edge("llm","end",null)));

        WorkflowPausedException paused = assertThrows(WorkflowPausedException.class,
                () -> runtime().execute(workflow, null, context(), input()));

        assertEquals(RunStatus.WAITING_FOR_USER, paused.getRunStatus());
        assertEquals("human", paused.getNodeCode());
    }

    private DefaultWorkflowRuntime runtime(){return new DefaultWorkflowRuntime(registry,nodeRuns,states,mapper,executor,new ConditionDslEvaluator(mapper),new ContentHashService(mapper),pauses);}
    private AgentInputRequest input(){AgentInputRequest input=new AgentInputRequest();input.setText("test");return input;}
    private ExecutionContext context(){return context(Collections.emptyMap());}
    private ExecutionContext context(Map<String,Object> variables){return new ExecutionContext("t","w","u","s",null,"run","agent",1,"zh-CN","UTC",Collections.emptyList(),Collections.emptyList(),new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)),ExecutionBudget.defaults(),Instant.now().plusSeconds(30),variables,"trace");}
    private WorkflowDefinition workflow(List<WorkflowNodeDefinition> nodes,List<WorkflowEdgeDefinition> edges){return new WorkflowDefinition("wv","t","w","wf",1,"{}","{}","{}","{}","PUBLISHED",nodes,edges);}
    private List<WorkflowNodeDefinition> nodes(WorkflowNodeDefinition... values){return java.util.Arrays.asList(values);}
    private List<WorkflowEdgeDefinition> edges(WorkflowEdgeDefinition... values){return java.util.Arrays.asList(values);}
    private WorkflowNodeDefinition node(String code,WorkflowNodeType type,String config){return new WorkflowNodeDefinition(code,code,type,code,config,"{}","{}",2000,1);}
    private WorkflowEdgeDefinition edge(String source,String target,String condition){return new WorkflowEdgeDefinition(source+target,source,target,condition,0,null);}
    private WorkflowEdgeDefinition edgeLabel(String source,String target,String label){return new WorkflowEdgeDefinition(source+target,source,target,null,0,label);}

    private static final class InMemoryWorkflowStateRepository implements WorkflowStateRepository {
        private WorkflowState state;
        public Optional<WorkflowState> find(String t,String w,String r){return Optional.ofNullable(state);}
        public WorkflowState create(WorkflowState value,String actor){state=value;return state;}
        public WorkflowState saveProgress(WorkflowState value,String actor){state=new WorkflowState(value.getTenantId(),value.getWorkspaceId(),value.getRunId(),value.getWorkflowVersionId(),value.getCurrentNodeCodes(),value.getVariables(),value.getCompletedNodeKeys(),value.getExecutionCounts(),null,WorkflowStateStatus.RUNNING,null,value.getStateVersion()+1);return state;}
        public WorkflowState saveWaiting(WorkflowState value,WorkflowStateStatus status,String node,String hash,Instant expires,String actor){state=new WorkflowState(value.getTenantId(),value.getWorkspaceId(),value.getRunId(),value.getWorkflowVersionId(),value.getCurrentNodeCodes(),value.getVariables(),value.getCompletedNodeKeys(),value.getExecutionCounts(),node,status,expires,value.getStateVersion()+1);return state;}
        public boolean resume(String t,String w,String r,String h,Map<String,Object> variables,String actor){return false;}
        public void complete(String t,String w,String r,String actor){state=new WorkflowState(state.getTenantId(),state.getWorkspaceId(),state.getRunId(),state.getWorkflowVersionId(),Collections.emptyList(),state.getVariables(),state.getCompletedNodeKeys(),state.getExecutionCounts(),null,WorkflowStateStatus.COMPLETED,null,state.getStateVersion()+1);}
        public void fail(String t,String w,String r,String actor){}
    }

    private static final class DeterministicExecutor implements NodeExecutor {
        private final ObjectMapper mapper;
        private DeterministicExecutor(ObjectMapper mapper){this.mapper=mapper;}
        public Set<WorkflowNodeType> supportedTypes(){return EnumSet.allOf(WorkflowNodeType.class);}
        public NodeExecutionResult execute(com.hmdp.ai.runtime.node.NodeExecutionContext context){WorkflowNodeType type=context.getNode().getType();if(type==WorkflowNodeType.HUMAN_FEEDBACK)return new NodeExecutionResult(NodeRunStatus.WAITING,mapper.createObjectNode(),null,Collections.emptyMap(),null,null,null,UsageSummary.empty(0),false,null);Map<String,Object> updates=new LinkedHashMap<>();ObjectNode output=mapper.createObjectNode();if(type==WorkflowNodeType.DATA_TRANSFORM){Object item=context.getVariables().get("item");updates.put(context.getNode().getCode(),item==null?context.getNode().getCode():item);output.put("node",context.getNode().getCode());}if(type==WorkflowNodeType.LLM){String answer;if(context.getVariables().containsKey("results"))answer=String.valueOf(((List<?>)context.getVariables().get("results")).size());else{List<String> values=new ArrayList<>();for(String key:java.util.Arrays.asList("a","a2","b","b2"))if(context.getVariables().containsKey(key))values.add(key);answer=String.join(",",values);}AgentRunOutput runOutput=new AgentRunOutput(answer,Collections.<ResponseBlock>emptyList(),Collections.emptyList(),Collections.emptyList(),UsageSummary.empty(0),Collections.emptyList(),RunStatus.COMPLETED);updates.put("agentOutput",runOutput);output.put("answer",answer);}return NodeExecutionResult.success(output,null,updates);}
    }
}
