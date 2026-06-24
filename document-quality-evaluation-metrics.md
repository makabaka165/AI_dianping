# 文档质量评估量化指标记录

记录日期：2026-06-24

## 评测背景

本次评测用于验证“文档质量评估 + 质量感知索引决策”对 RAG 检索结果的影响。评测覆盖两类知识来源：

- `PLATFORM_POLICY`：平台退款、投诉、账号、支付、优惠券等规则文档。
- `SHOP_REVIEW`：店铺服务、口味、环境、价格、排队等评价文档。

评测目标不是证明线上真实收益，而是在可重复的离线受控环境中验证质量评分体系是否能减少低质量文档进入 TopK 结果，并观察对可用召回的影响。

## 评测规模

评测入口：

```text
src/test/java/com/hmdp/ai/retrieval/OfflineQualityRetrievalEvaluationTest.java
```

评测集规模：

| 类别 | 高质量 | 中等质量 | 低质量 | 合计 |
| --- | ---: | ---: | ---: | ---: |
| `PLATFORM_POLICY` | 15 | 15 | 15 | 45 |
| `SHOP_REVIEW` | 15 | 15 | 15 | 45 |
| 合计 | 30 | 30 | 30 | 90 |

查询规模：

| 查询类别 | 数量 |
| --- | ---: |
| 平台政策查询 | 15 |
| 店铺评价查询 | 15 |
| 合计 | 30 |

评测使用真实的 `DocumentQualityAssessor` 进行文档质量评分，使用测试内的 `DeterministicEmbeddingModel` 进行稳定向量化，并用余弦相似度模拟 TopK 检索排序。

## 策略定义

| 策略 | 含义 |
| --- | --- |
| `ALL_INDEX` | 所有文档都参与索引和检索排序，作为基线。 |
| `SKIP_LOW_QUALITY` | 评分低于阈值的文档不参与检索。 |
| `DEGRADE_LOW_QUALITY` | 评分低于阈值的文档保留，但相似度分数降权。 |

当前低质量阈值：

```text
LOW_QUALITY_THRESHOLD = 0.45
```

## 质量分类指标

| 指标 | 结果 | 说明 |
| --- | ---: | --- |
| 低质量识别召回率 | `96.67%` | 30 篇人工标注低质量文档中识别出 29 篇。 |
| 误杀率 | `0%` | 60 篇可用文档中没有被误判为低质量。 |

这说明当前评分规则在该离线样本集上能够较稳定地识别低质量文档，同时没有误伤高质量和中等质量文档。

## 检索指标对比

| 指标 | `ALL_INDEX` | `SKIP_LOW_QUALITY` | `DEGRADE_LOW_QUALITY` |
| --- | ---: | ---: | ---: |
| `Precision@3` | `0.7000` | `0.9000` | `0.9000` |
| `Recall@3` | `0.9333` | `0.9333` | `0.9333` |
| `LowQuality@3` | `0.2556` | `0.0333` | `0.0333` |
| `BadPromotionRate` | `0.7667` | `0.1000` | `0.1000` |
| `IdealTop1Rate` | `0.1000` | `0.2000` | `0.2000` |

相对于基线 `ALL_INDEX`，`DEGRADE_LOW_QUALITY` 的主要提升为：

| 指标 | 变化 |
| --- | ---: |
| `Precision@3` 相对提升 | `28.57%` |
| `LowQuality@3` 降低 | `86.97%` |
| `BadPromotionRate` 降低 | `86.96%` |
| `Recall@3` 损失 | `0%` |
| `IdealTop1Rate` | 从 `0.1000` 提升到 `0.2000` |

## 结论

在当前 90 篇人工构造文档和 30 个标注查询的离线评测集中，质量感知策略能够显著降低低质量文档进入 Top3 的比例，并提升 Top3 结果的可用精度。`DEGRADE_LOW_QUALITY` 和 `SKIP_LOW_QUALITY` 在本轮数据上结果相同，但两者的产品含义不同：

- `SKIP_LOW_QUALITY` 更适合强质量门禁场景，收益直接，但对评分误杀更敏感。
- `DEGRADE_LOW_QUALITY` 更适合灰度阶段，既能降低低质量内容排序优先级，也保留低质量评分尚未完全验证时的容错空间。

因此当前更适合作为后续索引策略实验的推荐默认方向：先观察和降权，再根据线上和更大规模离线评测结果决定是否升级为跳过索引。

## 备注

- 本文档记录的是离线受控评测结果，不代表线上真实提升。
- 当前向量化使用确定性测试 embedding，不等同于生产环境的真实 embedding 模型。
- 当前评测重点是评分黄金样本和索引决策效果，尚未覆盖真实召回链路的端到端效果。
- 后续第二轮可以引入 `InMemoryEmbeddingStore` 或 fake retriever，构建可重复的召回效果实验。
- 如果后续调整评分权重、低质量阈值或索引策略，需要同步更新本文档中的指标。

## 验证命令

相关测试：

```text
mvn "-Dtest=DocumentQualityAssessorTest,DocumentQualityGoldenSampleTest,DocumentIndexDecisionServiceTest,PlatformPolicyRetrievalExperimentTest,ShopReviewRetrievalExperimentTest,OfflineQualityRetrievalEvaluationTest" test
```

全量测试：

```text
mvn test
```

最近一次验证结果：

```text
Tests run: 385, Failures: 0, Errors: 0, Skipped: 0
```
