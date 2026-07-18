package com.hmdp.ai.application.dto;

import com.hmdp.ai.shared.json.FieldDiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VersionDiffResponse {
    private final int leftVersion;
    private final int rightVersion;
    private final List<FieldDiff> differences;

    public VersionDiffResponse(int leftVersion, int rightVersion, List<FieldDiff> differences) {
        this.leftVersion = leftVersion;
        this.rightVersion = rightVersion;
        this.differences = Collections.unmodifiableList(new ArrayList<>(differences));
    }

    public int getLeftVersion() { return leftVersion; }
    public int getRightVersion() { return rightVersion; }
    public List<FieldDiff> getDifferences() { return differences; }
}
