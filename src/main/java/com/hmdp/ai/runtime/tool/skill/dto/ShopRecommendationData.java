package com.hmdp.ai.runtime.tool.skill.dto;
import lombok.Value;import java.util.List;
@Value public class ShopRecommendationData {String preference;String category;List<ShopRecommendationItemData> items;}
