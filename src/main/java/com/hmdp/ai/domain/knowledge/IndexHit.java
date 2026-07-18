package com.hmdp.ai.domain.knowledge;
public final class IndexHit {private final String chunkId;private final double score;public IndexHit(String chunkId,double score){this.chunkId=chunkId;this.score=score;}public String getChunkId(){return chunkId;}public double getScore(){return score;}}
