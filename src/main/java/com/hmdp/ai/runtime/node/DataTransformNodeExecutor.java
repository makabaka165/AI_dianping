package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DataTransformNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public DataTransformNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.DATA_TRANSFORM);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            String inputVariable = required(config, "inputVariable");
            String outputVariable = config.path("outputVariable").asText(context.getNode().getCode());
            JsonNode value = mapper.valueToTree(context.getVariables().get(inputVariable));
            for (JsonNode operation : config.path("operations")) value = apply(value, operation, context);
            return NodeExecutionResult.success(value, null,
                    Collections.singletonMap(outputVariable, mapper.convertValue(value, Object.class)));
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(e.getMessage(), false);
        } catch (Exception e) {
            return NodeExecutionResult.failure("DATA_TRANSFORM_FAILED", false);
        }
    }

    private JsonNode apply(JsonNode value, JsonNode operation, NodeExecutionContext context) {
        String type = operation.path("op").asText().toLowerCase(Locale.ROOT);
        switch (type) {
            case "select": return select(value, operation.path("fields"));
            case "rename": return rename(value, operation.path("fields"));
            case "filter": return filter(value, operation);
            case "map": return map(value, operation.path("fields"));
            case "sort": return sort(value, operation.path("field").asText(), operation.path("direction").asText("asc"));
            case "limit": return limit(value, operation.path("count").asInt());
            case "distinct": return distinct(value, operation.path("fields"));
            case "group": return group(value, required(operation, "field"));
            case "aggregate": return aggregate(value, operation);
            case "join": return join(value, mapper.valueToTree(context.getVariables().get(required(operation,
                    "withVariable"))), operation);
            case "calculate": return calculate(value, operation);
            default: throw new IllegalArgumentException("DATA_TRANSFORM_OPERATION_UNSUPPORTED");
        }
    }

    private JsonNode select(JsonNode value, JsonNode fields) {
        return eachObject(value, source -> {
            ObjectNode result = mapper.createObjectNode();
            fields.forEach(field -> {
                if (source.has(field.asText())) result.set(field.asText(), source.get(field.asText()));
            });
            return result;
        });
    }

    private JsonNode rename(JsonNode value, JsonNode fields) {
        return eachObject(value, source -> {
            ObjectNode result = source.deepCopy();
            fields.fields().forEachRemaining(entry -> {
                if (result.has(entry.getKey())) {
                    JsonNode field = result.remove(entry.getKey());
                    result.set(entry.getValue().asText(), field);
                }
            });
            return result;
        });
    }

    private JsonNode filter(JsonNode value, JsonNode operation) {
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : array(value)) {
            JsonNode field = item.path(required(operation, "field"));
            JsonNode expected = operation.get("value");
            if (compare(field, expected, operation.path("operator").asText("eq"))) result.add(item);
        }
        return result;
    }

    private JsonNode map(JsonNode value, JsonNode fields) {
        return eachObject(value, source -> {
            ObjectNode result = source.deepCopy();
            fields.fields().forEachRemaining(entry -> {
                String expression = entry.getValue().asText();
                result.set(entry.getKey(), expression.startsWith("$.")
                        ? source.path(expression.substring(2)) : entry.getValue());
            });
            return result;
        });
    }

    private JsonNode sort(JsonNode value, String field, String direction) {
        List<JsonNode> items = array(value);
        Comparator<JsonNode> comparator = (left, right) -> compareValues(left.path(field), right.path(field));
        if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();
        items.sort(comparator);
        return mapper.valueToTree(items);
    }

    private JsonNode limit(JsonNode value, int count) {
        if (count < 0) throw new IllegalArgumentException("DATA_TRANSFORM_LIMIT_INVALID");
        List<JsonNode> items = array(value);
        return mapper.valueToTree(items.subList(0, Math.min(count, items.size())));
    }

    private JsonNode distinct(JsonNode value, JsonNode fields) {
        ArrayNode result = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array(value)) {
            StringBuilder key = new StringBuilder();
            if (fields.isArray() && fields.size() > 0) fields.forEach(field -> key.append(item.path(field.asText())));
            else key.append(item);
            if (seen.add(key.toString())) result.add(item);
        }
        return result;
    }

    private JsonNode group(JsonNode value, String field) {
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode item : array(value)) groups.computeIfAbsent(item.path(field).asText(), ignored -> new ArrayList<>()).add(item);
        ArrayNode result = mapper.createArrayNode();
        groups.forEach((key, items) -> result.add(mapper.createObjectNode().put("key", key)
                .set("items", mapper.valueToTree(items))));
        return result;
    }

    private JsonNode aggregate(JsonNode value, JsonNode operation) {
        String groupBy = operation.path("groupBy").asText();
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode item : array(value)) groups.computeIfAbsent(groupBy.isEmpty() ? "all" : item.path(groupBy).asText(),
                ignored -> new ArrayList<>()).add(item);
        ArrayNode output = mapper.createArrayNode();
        groups.forEach((key, items) -> {
            ObjectNode row = mapper.createObjectNode().put(groupBy.isEmpty() ? "group" : groupBy, key);
            operation.path("aggregations").fields().forEachRemaining(entry -> {
                JsonNode spec = entry.getValue();
                String op = spec.path("op").asText("count").toLowerCase(Locale.ROOT);
                String field = spec.path("field").asText();
                if ("count".equals(op)) row.put(entry.getKey(), items.size());
                else {
                    List<BigDecimal> numbers = new ArrayList<>();
                    for (JsonNode item : items) if (item.path(field).isNumber()) numbers.add(item.path(field).decimalValue());
                    BigDecimal result = aggregateNumbers(numbers, op);
                    row.put(entry.getKey(), result);
                }
            });
            output.add(row);
        });
        return output;
    }

    private JsonNode join(JsonNode left, JsonNode right, JsonNode operation) {
        String leftKey = required(operation, "leftKey");
        String rightKey = required(operation, "rightKey");
        boolean includeUnmatched = "left".equalsIgnoreCase(operation.path("type").asText("inner"));
        ArrayNode output = mapper.createArrayNode();
        for (JsonNode leftItem : array(left)) {
            boolean matched = false;
            for (JsonNode rightItem : array(right)) {
                if (leftItem.path(leftKey).equals(rightItem.path(rightKey))) {
                    ObjectNode merged = leftItem.deepCopy();
                    rightItem.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
                    output.add(merged);
                    matched = true;
                }
            }
            if (!matched && includeUnmatched) output.add(leftItem);
        }
        return output;
    }

    private JsonNode calculate(JsonNode value, JsonNode operation) {
        String outputField = required(operation, "field");
        String operator = operation.path("operator").asText("add").toLowerCase(Locale.ROOT);
        return eachObject(value, source -> {
            BigDecimal result = null;
            for (JsonNode operand : operation.path("operands")) {
                JsonNode raw = operand.isTextual() ? source.path(operand.asText()) : operand;
                if (!raw.isNumber()) throw new IllegalArgumentException("DATA_TRANSFORM_CALCULATION_INVALID");
                BigDecimal number = raw.decimalValue();
                if (result == null) result = number;
                else if ("add".equals(operator)) result = result.add(number);
                else if ("subtract".equals(operator)) result = result.subtract(number);
                else if ("multiply".equals(operator)) result = result.multiply(number);
                else if ("divide".equals(operator)) result = result.divide(number, 8, java.math.RoundingMode.HALF_UP);
                else throw new IllegalArgumentException("DATA_TRANSFORM_CALCULATION_INVALID");
            }
            ObjectNode output = source.deepCopy();
            output.put(outputField, result == null ? BigDecimal.ZERO : result);
            return output;
        });
    }

    private JsonNode eachObject(JsonNode value, java.util.function.Function<ObjectNode, ObjectNode> transform) {
        if (value.isObject()) return transform.apply((ObjectNode) value);
        ArrayNode output = mapper.createArrayNode();
        for (JsonNode item : array(value)) {
            if (!item.isObject()) throw new IllegalArgumentException("DATA_TRANSFORM_OBJECT_REQUIRED");
            output.add(transform.apply((ObjectNode) item));
        }
        return output;
    }

    private List<JsonNode> array(JsonNode value) {
        if (!value.isArray()) throw new IllegalArgumentException("DATA_TRANSFORM_ARRAY_REQUIRED");
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    private boolean compare(JsonNode actual, JsonNode expected, String operator) {
        int comparison = compareValues(actual, expected);
        switch (operator.toLowerCase(Locale.ROOT)) {
            case "eq": return actual.equals(expected);
            case "ne": return !actual.equals(expected);
            case "gt": return comparison > 0;
            case "gte": return comparison >= 0;
            case "lt": return comparison < 0;
            case "lte": return comparison <= 0;
            case "contains": return actual.asText().contains(expected.asText());
            default: throw new IllegalArgumentException("DATA_TRANSFORM_FILTER_INVALID");
        }
    }

    private int compareValues(JsonNode left, JsonNode right) {
        if (left.isNumber() && right != null && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue());
        return left.asText().compareTo(right == null ? "" : right.asText());
    }

    private BigDecimal aggregateNumbers(List<BigDecimal> values, String operation) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        if ("min".equals(operation)) return values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if ("max".equals(operation)) return values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if ("sum".equals(operation)) return sum;
        if ("avg".equals(operation)) return sum.divide(BigDecimal.valueOf(values.size()), 8,
                java.math.RoundingMode.HALF_UP);
        throw new IllegalArgumentException("DATA_TRANSFORM_AGGREGATE_INVALID");
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.trim().isEmpty()) throw new IllegalArgumentException("DATA_TRANSFORM_CONFIG_INVALID");
        return value;
    }
}
