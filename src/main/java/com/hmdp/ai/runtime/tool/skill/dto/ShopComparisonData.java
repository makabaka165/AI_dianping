package com.hmdp.ai.runtime.tool.skill.dto;
import lombok.Value;import java.util.List;
@Value public class ShopComparisonData {String aspect;List<ShopComparisonItemData> shops;Long highestScoreShopId;}
