package com.hmdp.ai.domain.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowValidator {
    private final JsonSchemaValidationService schemas;
    private final ConditionDslEvaluator conditions;
    private final ObjectMapper mapper;
    public WorkflowValidator(JsonSchemaValidationService schemas,ConditionDslEvaluator conditions,ObjectMapper mapper){this.schemas=schemas;this.conditions=conditions;this.mapper=mapper;}

    public ValidationResult validate(WorkflowDefinition workflow){
        List<ValidationIssue> issues=new ArrayList<>();
        issues.addAll(schemas.validateSchema(workflow.getInputSchema(),"inputSchema").getIssues());
        issues.addAll(schemas.validateSchema(workflow.getOutputSchema(),"outputSchema").getIssues());
        Map<String,WorkflowNodeDefinition> nodes=new HashMap<>();
        for(WorkflowNodeDefinition n:workflow.getNodes()) if(nodes.put(n.getCode(),n)!=null) issues.add(issue("WORKFLOW_NODE_DUPLICATE",n.getCode(),"node code is duplicated"));
        List<WorkflowNodeDefinition> starts=workflow.getNodes().stream().filter(n->n.getType()==WorkflowNodeType.START).collect(Collectors.toList());
        if(starts.size()!=1)issues.add(issue("WORKFLOW_START_COUNT","nodes","workflow must contain exactly one START"));
        if(workflow.getNodes().stream().noneMatch(n->n.getType()==WorkflowNodeType.END))issues.add(issue("WORKFLOW_END_REQUIRED","nodes","workflow requires at least one END"));
        Map<String,List<String>> graph=new HashMap<>();
        for(WorkflowEdgeDefinition e:workflow.getEdges()){
            if(!nodes.containsKey(e.getSourceNodeCode())||!nodes.containsKey(e.getTargetNodeCode()))issues.add(issue("WORKFLOW_EDGE_NODE_MISSING",e.getId(),"edge references a missing node"));
            graph.computeIfAbsent(e.getSourceNodeCode(),k->new ArrayList<>()).add(e.getTargetNodeCode());
            if(e.getConditionJson()!=null)try{conditions.evaluate(e.getConditionJson(),java.util.Collections.emptyMap());}catch(Exception x){issues.add(issue("WORKFLOW_CONDITION_INVALID",e.getId(),"edge condition is invalid"));}
        }
        if(starts.size()==1){Set<String> reached=reachable(starts.get(0).getCode(),graph);for(String code:nodes.keySet())if(!reached.contains(code))issues.add(issue("WORKFLOW_NODE_UNREACHABLE",code,"node is unreachable"));}
        detectIllegalCycles(nodes,graph,issues);
        validateExecutionPolicy(workflow, issues);
        for(WorkflowNodeDefinition n:workflow.getNodes()){
            if(n.getTimeoutMs()<=0||n.getMaxAttempts()<=0)issues.add(issue("WORKFLOW_NODE_POLICY_INVALID",n.getCode(),"timeout and maxAttempts must be positive"));
            validateNodeConfiguration(n, workflow, nodes, graph, issues);
        }
        return new ValidationResult(issues);
    }
    private Set<String> reachable(String start,Map<String,List<String>> g){Set<String>s=new HashSet<>();ArrayDeque<String>q=new ArrayDeque<>();q.add(start);while(!q.isEmpty()){String x=q.remove();if(s.add(x))q.addAll(g.getOrDefault(x,java.util.Collections.emptyList()));}return s;}
    private boolean hasReachableJoin(String start,Map<String,WorkflowNodeDefinition> nodes,Map<String,List<String>>g){return reachable(start,g).stream().anyMatch(c->nodes.get(c)!=null&&nodes.get(c).getType()==WorkflowNodeType.JOIN);}
    private void validateExecutionPolicy(WorkflowDefinition workflow,List<ValidationIssue> issues){try{com.fasterxml.jackson.databind.JsonNode policy=mapper.readTree(workflow.getExecutionPolicyJson());if(!policy.isObject())issues.add(issue("WORKFLOW_EXECUTION_POLICY_INVALID","executionPolicyJson","execution policy must be an object"));for(String field:java.util.Arrays.asList("maxWorkflowNodes","maxLoopIterations","maxParallelism")){if(policy.has(field)&&policy.path(field).asInt(0)<=0)issues.add(issue("WORKFLOW_EXECUTION_LIMIT_INVALID","executionPolicyJson."+field,field+" must be positive"));}}catch(Exception e){issues.add(issue("WORKFLOW_EXECUTION_POLICY_INVALID","executionPolicyJson","execution policy is invalid JSON"));}}
    private void validateNodeConfiguration(WorkflowNodeDefinition node,WorkflowDefinition workflow,Map<String,WorkflowNodeDefinition> nodes,Map<String,List<String>> graph,List<ValidationIssue> issues){try{com.fasterxml.jackson.databind.JsonNode config=mapper.readTree(node.getConfigurationJson());if(!config.isObject()){issues.add(issue("WORKFLOW_NODE_CONFIG_INVALID",node.getCode(),"node configuration must be an object"));return;}if(node.getType()==WorkflowNodeType.LOOP){if(config.path("maxIterations").asInt(0)<=0)issues.add(issue("WORKFLOW_LOOP_LIMIT_REQUIRED",node.getCode(),"LOOP requires positive maxIterations"));if(!config.has("terminationCondition"))issues.add(issue("WORKFLOW_LOOP_CONDITION_REQUIRED",node.getCode(),"LOOP requires terminationCondition"));if(config.path("perIterationTimeoutMs").asLong(0)<=0)issues.add(issue("WORKFLOW_LOOP_TIMEOUT_REQUIRED",node.getCode(),"LOOP requires positive perIterationTimeoutMs"));if(config.path("deduplicationKey").asText("").trim().isEmpty())issues.add(issue("WORKFLOW_LOOP_DEDUP_REQUIRED",node.getCode(),"LOOP requires deduplicationKey"));if(config.path("accumulatorStrategy").asText("").trim().isEmpty())issues.add(issue("WORKFLOW_LOOP_ACCUMULATOR_REQUIRED",node.getCode(),"LOOP requires accumulatorStrategy"));}
        if(node.getType()==WorkflowNodeType.PARALLEL){long branches=workflow.getEdges().stream().filter(e->node.getCode().equals(e.getSourceNodeCode())).count();if(branches<2)issues.add(issue("WORKFLOW_PARALLEL_BRANCH_REQUIRED",node.getCode(),"PARALLEL requires at least two branches"));if(!hasReachableJoin(node.getCode(),nodes,graph))issues.add(issue("WORKFLOW_PARALLEL_JOIN_REQUIRED",node.getCode(),"PARALLEL requires a reachable JOIN"));if(config.has("maxParallelism")&&config.path("maxParallelism").asInt(0)<=0)issues.add(issue("WORKFLOW_PARALLEL_LIMIT_REQUIRED",node.getCode(),"maxParallelism must be positive"));if(config.has("branchTimeoutMs")&&config.path("branchTimeoutMs").asLong(0)<=0)issues.add(issue("WORKFLOW_PARALLEL_TIMEOUT_REQUIRED",node.getCode(),"branchTimeoutMs must be positive"));}
        if(node.getType()==WorkflowNodeType.FOREACH){if(config.path("collectionVariable").asText("").trim().isEmpty())issues.add(issue("WORKFLOW_FOREACH_COLLECTION_REQUIRED",node.getCode(),"FOREACH requires collectionVariable"));boolean body=workflow.getEdges().stream().anyMatch(e->node.getCode().equals(e.getSourceNodeCode())&&"body".equalsIgnoreCase(e.getLabel()));if(!body)issues.add(issue("WORKFLOW_FOREACH_BODY_REQUIRED",node.getCode(),"FOREACH requires a body edge"));if(!hasReachableJoin(node.getCode(),nodes,graph))issues.add(issue("WORKFLOW_FOREACH_JOIN_REQUIRED",node.getCode(),"FOREACH requires a reachable JOIN"));}
        if(node.getType()==WorkflowNodeType.BRANCH&&workflow.getEdges().stream().noneMatch(e->node.getCode().equals(e.getSourceNodeCode())))issues.add(issue("WORKFLOW_BRANCH_EDGE_REQUIRED",node.getCode(),"BRANCH requires outgoing edges"));
    }catch(Exception e){issues.add(issue("WORKFLOW_NODE_CONFIG_INVALID",node.getCode(),"node configuration is invalid"));}}
    private void detectIllegalCycles(Map<String,WorkflowNodeDefinition> nodes,Map<String,List<String>>g,List<ValidationIssue> issues){Set<String>vis=new HashSet<>(),stack=new HashSet<>();for(String n:nodes.keySet())dfs(n,nodes,g,vis,stack,issues);}
    private void dfs(String n,Map<String,WorkflowNodeDefinition> nodes,Map<String,List<String>>g,Set<String>vis,Set<String>stack,List<ValidationIssue> issues){if(stack.contains(n)){if(nodes.get(n).getType()!=WorkflowNodeType.LOOP)issues.add(issue("WORKFLOW_ILLEGAL_CYCLE",n,"cycles must be expressed through LOOP nodes"));return;}if(!vis.add(n))return;stack.add(n);for(String x:g.getOrDefault(n,java.util.Collections.emptyList()))dfs(x,nodes,g,vis,stack,issues);stack.remove(n);}
    private ValidationIssue issue(String c,String p,String m){return new ValidationIssue(c,p,m);}
}
