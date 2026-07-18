package com.hmdp.ai.application.dto.evaluation;
import com.fasterxml.jackson.databind.JsonNode;import javax.validation.constraints.*;
public class CreateEvaluationCaseRequest {@NotBlank@Size(max=128)private String name;@NotNull private JsonNode input;
    @NotNull private JsonNode expected;@NotNull private JsonNode assertions;
    public String getName(){return name;}public void setName(String v){name=v;}public JsonNode getInput(){return input;}public void setInput(JsonNode v){input=v;}
    public JsonNode getExpected(){return expected;}public void setExpected(JsonNode v){expected=v;}public JsonNode getAssertions(){return assertions;}public void setAssertions(JsonNode v){assertions=v;}}
