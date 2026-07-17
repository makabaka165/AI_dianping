package com.hmdp.ai.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class OutputValidationService {
    private final ObjectMapper mapper; private final PiiDetectionService pii;
    public OutputValidationService(ObjectMapper mapper,PiiDetectionService pii){this.mapper=mapper;this.pii=pii;}
    public ValidationResult validate(String output,String jsonSchema,Set<String> requiredCitationIds,Set<String> actualCitationIds,boolean allowPii){
        List<String> issues=new ArrayList<>(); if(output==null||output.trim().isEmpty())issues.add("OUTPUT_EMPTY"); if(output!=null&&output.length()>100000)issues.add("OUTPUT_TOO_LONG");
        if(!allowPii&&output!=null&&!pii.detect(output).isEmpty())issues.add("OUTPUT_PII_PRESENT");
        if(requiredCitationIds!=null&&actualCitationIds!=null&&!actualCitationIds.containsAll(requiredCitationIds))issues.add("CITATION_INCOMPLETE");
        if(jsonSchema!=null&&!jsonSchema.trim().isEmpty()&&output!=null){try{JsonNode schemaNode=mapper.readTree(jsonSchema);JsonNode value=mapper.readTree(output);JsonSchema schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode);for(ValidationMessage ignored:schema.validate(value))issues.add("OUTPUT_SCHEMA_INVALID");}catch(Exception e){issues.add("OUTPUT_JSON_INVALID");}}
        return new ValidationResult(issues.isEmpty(),issues);
    }
    public static final class ValidationResult {private final boolean valid;private final List<String> issues;ValidationResult(boolean valid,List<String> issues){this.valid=valid;this.issues=Collections.unmodifiableList(issues);}public boolean isValid(){return valid;}public List<String> getIssues(){return issues;}}
}
