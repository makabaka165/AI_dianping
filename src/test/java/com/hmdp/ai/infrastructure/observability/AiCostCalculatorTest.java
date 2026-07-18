package com.hmdp.ai.infrastructure.observability;
import org.junit.jupiter.api.Test;import java.math.BigDecimal;import static org.assertj.core.api.Assertions.assertThat;
class AiCostCalculatorTest {@Test void calculatesInputAndOutputCostPerMillionTokens(){BigDecimal cost=new AiCostCalculator().calculate(500000,250000,new BigDecimal("2"),new BigDecimal("4"));assertThat(cost).isEqualByComparingTo("2.0000000000");}}
