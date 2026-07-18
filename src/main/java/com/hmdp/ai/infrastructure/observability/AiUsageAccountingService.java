package com.hmdp.ai.infrastructure.observability;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import java.math.BigDecimal;import java.util.Map;
@Service public class AiUsageAccountingService {private final JdbcTemplate jdbc;private final AiCostCalculator costs;
    public AiUsageAccountingService(JdbcTemplate jdbc,AiCostCalculator costs){this.jdbc=jdbc;this.costs=costs;}
    public UsageAccounting summarize(String tenant,String workspace,String runId){Map<String,Object> row=jdbc.queryForMap(
            "select coalesce(sum(c.input_tokens),0) input_tokens,coalesce(sum(c.output_tokens),0) output_tokens,"+
                    "coalesce(sum(c.input_tokens*p.input_token_price/1000000+c.output_tokens*p.output_token_price/1000000),0) total_cost,"+
                    "count(*) model_calls from ai_model_call c left join ai_model_profile p on p.id=c.model_profile_id "+
                    "where c.tenant_id=? and c.workspace_id=? and c.run_id=? and c.deleted=0",tenant,workspace,runId);
        return new UsageAccounting(number(row.get("input_tokens")),number(row.get("output_tokens")),number(row.get("model_calls")),new BigDecimal(String.valueOf(row.get("total_cost"))));}
    private long number(Object value){return value==null?0:((Number)value).longValue();}
    public static final class UsageAccounting {private final long inputTokens,outputTokens,modelCalls;private final BigDecimal totalCost;
        public UsageAccounting(long inputTokens,long outputTokens,long modelCalls,BigDecimal totalCost){this.inputTokens=inputTokens;this.outputTokens=outputTokens;this.modelCalls=modelCalls;this.totalCost=totalCost;}
        public long getInputTokens(){return inputTokens;}public long getOutputTokens(){return outputTokens;}public long getModelCalls(){return modelCalls;}public BigDecimal getTotalCost(){return totalCost;}}}
