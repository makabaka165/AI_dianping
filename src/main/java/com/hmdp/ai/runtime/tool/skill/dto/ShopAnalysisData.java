package com.hmdp.ai.runtime.tool.skill.dto;
import com.hmdp.dto.ai.ShopView;import lombok.Value;import java.util.List;
@Value public class ShopAnalysisData {ShopView shop;int reviewCount;List<ReviewEvidenceData> reviews;}
