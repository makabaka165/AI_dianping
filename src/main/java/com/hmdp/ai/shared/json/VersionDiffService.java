package com.hmdp.ai.shared.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Component
public class VersionDiffService {
    private final ObjectMapper objectMapper;

    public VersionDiffService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<FieldDiff> diff(Object left, Object right) {
        ObjectNode leftNode = objectMapper.valueToTree(left);
        ObjectNode rightNode = objectMapper.valueToTree(right);
        Set<String> fields = new TreeSet<>();
        addFields(leftNode, fields);
        addFields(rightNode, fields);
        List<FieldDiff> differences = new ArrayList<>();
        for (String field : fields) {
            JsonNode leftValue = leftNode.get(field);
            JsonNode rightValue = rightNode.get(field);
            if (leftValue == null ? rightValue != null : !leftValue.equals(rightValue)) {
                differences.add(new FieldDiff(field, leftValue, rightValue));
            }
        }
        return differences;
    }

    private void addFields(ObjectNode node, Set<String> target) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) target.add(names.next());
    }
}
