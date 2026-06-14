package com.hmdp.ai.task;

@FunctionalInterface
public interface AiTaskProgressReporter {
    void report(int current, int total);
}
