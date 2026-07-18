package com.hmdp.ai.domain.evaluation;
import java.util.*;
public final class MetricEvaluation {private final Map<String,Double>metrics;private final boolean passed;
    public MetricEvaluation(Map<String,Double>metrics,boolean passed){this.metrics=Collections.unmodifiableMap(new LinkedHashMap<>(metrics));this.passed=passed;}
    public Map<String,Double>getMetrics(){return metrics;}public boolean isPassed(){return passed;}}
