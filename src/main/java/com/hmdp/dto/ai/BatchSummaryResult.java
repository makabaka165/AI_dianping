package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSummaryResult {
    private int total;
    private int success;
    private int failed;
    private List<Long> failedShopIds;
    private long durationMs;
}
