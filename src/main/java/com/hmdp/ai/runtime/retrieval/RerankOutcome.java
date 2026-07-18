package com.hmdp.ai.runtime.retrieval;
import java.util.ArrayList;import java.util.Collections;import java.util.List;
public final class RerankOutcome {private final List<Double> scores;private final String mode;public RerankOutcome(List<Double> scores,String mode){this.scores=Collections.unmodifiableList(new ArrayList<>(scores));this.mode=mode;}public List<Double> getScores(){return scores;}public String getMode(){return mode;}}
