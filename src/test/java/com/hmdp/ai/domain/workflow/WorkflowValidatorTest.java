package com.hmdp.ai.domain.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowValidatorTest {
    @Test
    void canBeCreatedBySpringWithConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class);
            context.registerBean(JsonSchemaValidationService.class);
            context.registerBean(ConditionDslEvaluator.class);
            context.registerBean(WorkflowValidator.class);
            context.refresh();

            assertNotNull(context.getBean(WorkflowValidator.class));
        }
    }

    @Test
    void keepsTheRestrictedExecutorConstructorAvailableForCapabilityChecks() {
        ObjectMapper mapper = new ObjectMapper();
        WorkflowValidator validator = new WorkflowValidator(
                new JsonSchemaValidationService(mapper), new ConditionDslEvaluator(mapper), mapper,
                EnumSet.of(WorkflowNodeType.START, WorkflowNodeType.END, WorkflowNodeType.LLM));
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("transform", WorkflowNodeType.TEXT_TRANSFORM, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "transform", null, null),
                edge("transform", "answer", null, null),
                edge("answer", "end", null, null)));

        assertIssue(validator.validate(workflow), "WORKFLOW_NODE_TYPE_UNSUPPORTED");
    }

    @Test
    void acceptsReachableAcyclicWorkflowThatProducesOutput() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("answer", WorkflowNodeType.LLM,
                        "{\"useAgentDefaultPrompt\":true,\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "answer", null, null),
                edge("answer", "end", null, null)));

        assertTrue(validator().validate(workflow).isValid());
    }

    @Test
    void rejectsIllegalCycleAndMissingEnd() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("transform", WorkflowNodeType.TEXT_TRANSFORM, "{}")), Arrays.asList(
                edge("start", "transform", null, null),
                edge("transform", "start", null, null)));

        ValidationResult result = validator().validate(workflow);

        assertIssue(result, "WORKFLOW_ILLEGAL_CYCLE");
        assertIssue(result, "WORKFLOW_END_REQUIRED");
    }

    @Test
    void rejectsTerminalPathThatDoesNotReachEnd() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("dead", WorkflowNodeType.TEXT_TRANSFORM, "{}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "answer", null, null),
                edge("start", "dead", null, null),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_TERMINAL_MUST_BE_END");
    }

    @Test
    void rejectsAnyEndPathWithoutAgentOutput() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("end", WorkflowNodeType.END, "{}")),
                Collections.singletonList(edge("start", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_END_OUTPUT_REQUIRED");
    }

    @Test
    void requiresBranchDefaultRoute() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("route", WorkflowNodeType.BRANCH, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "route", null, null),
                edge("route", "answer", "{\"eq\":[1,1]}", "matched"),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_BRANCH_DEFAULT_REQUIRED");
    }

    @Test
    void requiresParallelBranchesToConvergeAtOneJoin() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("parallel", WorkflowNodeType.PARALLEL, "{}"),
                node("left", WorkflowNodeType.TEXT_TRANSFORM, "{}"),
                node("right", WorkflowNodeType.TEXT_TRANSFORM, "{}"),
                node("leftJoin", WorkflowNodeType.JOIN, "{}"),
                node("rightJoin", WorkflowNodeType.JOIN, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "parallel", null, null),
                edge("parallel", "left", null, "left"),
                edge("parallel", "right", null, "right"),
                edge("left", "leftJoin", null, null),
                edge("right", "rightJoin", null, null),
                edge("leftJoin", "answer", null, null),
                edge("rightJoin", "answer", null, null),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_PARALLEL_COMMON_JOIN_REQUIRED");
    }

    @Test
    void requiresLoopBodyAndExitEdges() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("loop", WorkflowNodeType.LOOP,
                        "{\"maxIterations\":2,\"terminationCondition\":{\"eq\":[1,2]},"
                                + "\"perIterationTimeoutMs\":1000,\"deduplicationKey\":\"id\","
                                + "\"accumulatorStrategy\":\"APPEND\"}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "loop", null, null),
                edge("loop", "answer", null, "exit"),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_LOOP_BODY_REQUIRED");
    }

    @Test
    void protectsSystemVariablesFromOutputMappings() {
        WorkflowNodeDefinition transform = new WorkflowNodeDefinition("transform", "transform",
                WorkflowNodeType.TEXT_TRANSFORM, "transform", "{}", "{}",
                "{\"runId\":\"$.value\"}", 1000, 1);
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"), transform,
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "transform", null, null),
                edge("transform", "answer", null, null),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_RESERVED_VARIABLE_OVERWRITE");
    }

    @Test
    void rejectsHumanPauseInsideParallelBranch() {
        WorkflowDefinition workflow = workflow(Arrays.asList(
                node("start", WorkflowNodeType.START, "{}"),
                node("parallel", WorkflowNodeType.PARALLEL, "{}"),
                node("human", WorkflowNodeType.HUMAN_FEEDBACK, "{}"),
                node("right", WorkflowNodeType.TEXT_TRANSFORM, "{}"),
                node("join", WorkflowNodeType.JOIN, "{}"),
                node("answer", WorkflowNodeType.LLM, "{\"outputVariable\":\"agentOutput\"}"),
                node("end", WorkflowNodeType.END, "{}")), Arrays.asList(
                edge("start", "parallel", null, null),
                edge("parallel", "human", null, "left"),
                edge("parallel", "right", null, "right"),
                edge("human", "join", null, null),
                edge("right", "join", null, null),
                edge("join", "answer", null, null),
                edge("answer", "end", null, null)));

        assertIssue(validator().validate(workflow), "WORKFLOW_PARALLEL_HUMAN_NODE_UNSUPPORTED");
    }

    private WorkflowValidator validator() {
        ObjectMapper mapper = new ObjectMapper();
        return new WorkflowValidator(new JsonSchemaValidationService(mapper),
                new ConditionDslEvaluator(mapper), mapper);
    }

    private WorkflowDefinition workflow(List<WorkflowNodeDefinition> nodes,
                                        List<WorkflowEdgeDefinition> edges) {
        return new WorkflowDefinition("v", "t", "w", "wf", 1,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", "{}", "DRAFT", nodes, edges);
    }

    private WorkflowNodeDefinition node(String code, WorkflowNodeType type, String configuration) {
        return new WorkflowNodeDefinition(code, code, type, code, configuration, "{}", "{}", 1000, 1);
    }

    private WorkflowEdgeDefinition edge(String source, String target, String condition, String label) {
        return new WorkflowEdgeDefinition(source + target + String.valueOf(label), source, target,
                condition, 0, label);
    }

    private void assertIssue(ValidationResult result, String code) {
        assertFalse(result.isValid());
        assertTrue(result.getIssues().stream().anyMatch(issue -> code.equals(issue.getCode())),
                () -> "missing issue " + code);
    }
}
