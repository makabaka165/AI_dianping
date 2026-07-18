package com.hmdp.ai.domain.evaluation;
import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;import org.springframework.stereotype.Component;import java.util.*;
@Component public class EvaluationMetricEngine {private final ObjectMapper mapper;private final JsonSchemaValidationService schemas;
    public EvaluationMetricEngine(ObjectMapper mapper,JsonSchemaValidationService schemas){this.mapper=mapper;this.schemas=schemas;}
    public MetricEvaluation evaluate(EvaluationCase c,EvaluationCandidate candidate){try{JsonNode expected=mapper.readTree(c.getExpectedJson());
        JsonNode assertions=mapper.readTree(c.getAssertionsJson());JsonNode actual=candidate.getActual();Map<String,Double>m=new LinkedHashMap<>();
        m.put("exactMatch",expected.equals(actual)?1d:0d);m.put("keywordCoverage",keywordCoverage(assertions.path("keywords"),actual));
        m.put("schemaValidity",schemaValidity(assertions.get("outputSchema"),actual));m.put("toolSelectionAccuracy",fieldAccuracy(assertions,"expectedTool",actual,"selectedTool"));
        m.put("toolArgumentAccuracy",nodeAccuracy(assertions.get("expectedToolArguments"),actual.get("toolArguments")));
        m.put("intentAccuracy",fieldAccuracy(assertions,"expectedIntent",actual,"intent"));Set<String>expectedEvidence=set(assertions.path("expectedEvidenceIds"));
        List<String>actualEvidence=list(actual.path("evidenceIds"));m.put("recallAtK",recall(expectedEvidence,actualEvidence));m.put("mrr",mrr(expectedEvidence,actualEvidence));
        Set<String>actualCitations=new LinkedHashSet<>(list(actual.path("citationIds")));m.put("citationPrecision",precision(expectedEvidence,actualCitations));
        m.put("citationCoverage",recall(expectedEvidence,new ArrayList<>(actualCitations)));m.put("groundedness",actual.path("grounded").asBoolean(false)?1d:0d);
        m.put("latencyMs",(double)candidate.getLatencyMs());m.put("tokenUsage",(double)(candidate.getInputTokens()+candidate.getOutputTokens()));
        m.put("modelCalls",(double)candidate.getModelCalls());m.put("toolCalls",(double)candidate.getToolCalls());m.put("cost",candidate.getCost());
        m.put("successRate",candidate.isSuccess()?1d:0d);boolean passed=candidate.isSuccess()&&thresholds(assertions.path("thresholds"),m);
        return new MetricEvaluation(m,passed);}catch(Exception e){throw new IllegalArgumentException("evaluation case is invalid",e);}}
    private double keywordCoverage(JsonNode words,JsonNode actual){if(!words.isArray()||words.isEmpty())return 1;String text=actual.toString().toLowerCase(Locale.ROOT);int hit=0;
        for(JsonNode word:words)if(text.contains(word.asText().toLowerCase(Locale.ROOT)))hit++;return (double)hit/words.size();}
    private double schemaValidity(JsonNode schema,JsonNode actual){if(schema==null||schema.isNull())return 1;String value=schema.isTextual()?schema.asText():schema.toString();return schemas.validateValue(value,actual,"actual").isValid()?1:0;}
    private double fieldAccuracy(JsonNode assertions,String expectedField,JsonNode actual,String actualField){if(!assertions.has(expectedField))return 1;return assertions.path(expectedField).asText().equals(actual.path(actualField).asText())?1:0;}
    private double nodeAccuracy(JsonNode expected,JsonNode actual){if(expected==null||expected.isNull())return 1;return expected.equals(actual)?1:0;}
    private Set<String>set(JsonNode values){return new LinkedHashSet<>(list(values));}private List<String>list(JsonNode values){List<String>r=new ArrayList<>();if(values!=null&&values.isArray())for(JsonNode v:values)r.add(v.asText());return r;}
    private double recall(Set<String>expected,List<String>actual){if(expected.isEmpty())return 1;int hit=0;for(String id:expected)if(actual.contains(id))hit++;return(double)hit/expected.size();}
    private double precision(Set<String>expected,Set<String>actual){if(actual.isEmpty())return expected.isEmpty()?1:0;int hit=0;for(String id:actual)if(expected.contains(id))hit++;return(double)hit/actual.size();}
    private double mrr(Set<String>expected,List<String>actual){for(int i=0;i<actual.size();i++)if(expected.contains(actual.get(i)))return 1d/(i+1);return expected.isEmpty()?1:0;}
    private boolean thresholds(JsonNode thresholds,Map<String,Double>metrics){if(!thresholds.isObject())return true;Iterator<String>names=thresholds.fieldNames();while(names.hasNext()){String n=names.next();if(metrics.getOrDefault(n,0d)<thresholds.path(n).asDouble())return false;}return true;}}
