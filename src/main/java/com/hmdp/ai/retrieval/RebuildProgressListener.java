package com.hmdp.ai.retrieval;

@FunctionalInterface
public interface RebuildProgressListener {
    void onProgress(int current, int total);
}
