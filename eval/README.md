# eval — 离线评估（黄金任务集回归）

## 指标（方案 §6.5，RAGAS 风格）

| 指标 | 含义 | 层次 |
|:---|:---|:---|
| structuredValid | 产物结构完整性（有标题/结论/证据） | 结构 |
| evidenceSupportRate | 证据原文保真率（L1 逐字命中） | L1 |
| semanticSupportRate | 证据语义支撑率（L2） | L2 |
| hallucinationRate | 幻觉率（L2） | L2 |
| claimEvidenceSupportRate | 结论证据绑定率 | 绑定 |
| userAcceptanceRate | 用户 👍/👎 接受率 | 反馈 |
| contextPrecision | 检索片段与目标相关性（证据检索 TopK 平均混合分） | 检索 |

## 黄金任务集回归

`golden-tasks.json` 定义黄金任务（目标 + 期望关键词 + 模式），
`GoldenSetEvaluator` 对每条任务执行**关键词覆盖**判定（≥0.6 通过），
配合结构化产物与验证报告的 structuredValid + 幻觉率构成三重判定。

### 运行方式（离线脚本）

```bash
# 准备：目标视频已完成分析（mediaId 有 CONTEXT_READY + 检索索引）
# 通过后端 API 驱动：
#   GET /analysis/evidence-search?mediaId=7&query=<黄金目标>   → 取 TopK 摘要/关键词
#   GET /analysis/evaluation?mediaId=7                         → structuredValid + 幻觉率
# 关键词覆盖可在 Java 单测/脚本中调用 GoldenSetEvaluator.evaluateTexts 计算
```

后端单测：`GoldenSetEvaluatorTest`（覆盖通过/失败两档）。
