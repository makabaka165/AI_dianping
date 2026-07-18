package com.hmdp.ai.runtime.tool.skill.dto;
import com.hmdp.dto.ai.ShopView;import lombok.Value;import java.util.List;
@Value public class ShopQuestionEvidenceData {Long shopId;String question;ShopView shop;List<ReviewEvidenceData> evidence;boolean insufficientEvidence;}
