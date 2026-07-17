package com.hmdp.ai.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class OutputValidationServiceTest {
    private final OutputValidationService service=new OutputValidationService(new ObjectMapper(),new PiiDetectionService());
    @Test void validatesJsonSchemaAndCitationCompleteness(){
        String schema="{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"string\"}}}";
        OutputValidationService.ValidationResult valid=service.validate("{\"answer\":\"grounded\"}",schema,Collections.singleton("c1"),Collections.singleton("c1"),false);
        assertThat(valid.isValid()).isTrue();
        OutputValidationService.ValidationResult invalid=service.validate("{}",schema,Collections.singleton("c1"),Collections.emptySet(),false);
        assertThat(invalid.getIssues()).contains("OUTPUT_SCHEMA_INVALID","CITATION_INCOMPLETE");
    }
    @Test void enterprisePolicyWordsAreNotHardCodedAsIllegal(){
        ContentPolicyService policy=new ContentPolicyService("");
        assertThat(policy.violations("企业制度规定禁止绕过审批，并说明法律限制与投诉流程")).isEmpty();
    }
}
