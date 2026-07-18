package com.hmdp.ai.infrastructure.observability;
import org.springframework.stereotype.Component;import java.math.*;
@Component public class AiCostCalculator {public BigDecimal calculate(long inputTokens,long outputTokens,BigDecimal inputPricePerMillion,BigDecimal outputPricePerMillion){
    BigDecimal million=BigDecimal.valueOf(1_000_000);return safe(inputPricePerMillion).multiply(BigDecimal.valueOf(inputTokens)).divide(million,10,RoundingMode.HALF_UP)
            .add(safe(outputPricePerMillion).multiply(BigDecimal.valueOf(outputTokens)).divide(million,10,RoundingMode.HALF_UP));}
    private BigDecimal safe(BigDecimal value){return value==null?BigDecimal.ZERO:value;}}
