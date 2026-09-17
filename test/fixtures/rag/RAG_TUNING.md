# RAG 固定评估集与调优口径

## 固定样本

`golden-set.json` 是当前 RAG 检索调优的唯一固定小型黄金集：

- `20min`：Redis 缓存课程，3 轮连续追问；
- `40min`：网络排障课程，3 轮连续追问；
- 每题包含原始追问、消解上下文后的 `standaloneQuery`，以及人工排序的 Top 7 Chunk；
- Top 7 同时保存 `chunkId`、起止时间、相关性等级和标注理由。

相关性等级：3=可直接回答，2=关键支撑/对比，1=有用上下文。Top 7 是评估标签，
不代表线上必须返回 7 块；当前 EvidencePack 仍可只取 Top 5。

`chunkId` 与 userId、contentHash、indexVersion、起止时间绑定。修改分块窗口、重叠、
内容哈希或索引版本后，`RagGoldenSetTest` 会失败；此时必须重新审阅原文并更新标注，
不能机械替换 ID。

## 分阶段指标

| 阶段 | 主要指标 | 推荐观察点 |
| --- | --- | --- |
| Dense/BM25/OCR 单路粗召回 | Recall@25、Hit@5/10/25 | 先保证至少一路找到直接证据 |
| RRF 融合 | Recall@10、Hit@10、MRR@10 | 直接证据是否进入精排候选且是否靠前 |
| Reranker | Hit@1/3/5、MRR@5、Recall@5、nDCG@5 | 精排是否把直接证据前推，并剔除低价值块 |
| EvidencePack | Evidence Precision、时间覆盖率、重复率、Token 数 | 合并后是否保留答案、减少重复和噪声 |

- **Hit@K**：Top K 中至少有一个黄金 Chunk 即为 1。适合回答“能否找到证据”，但不反映找全程度。
- **Recall@K**：Top K 命中的黄金 Chunk 数 / 7。适合调粗召回；因为每题标 7 个，当前 Top 5 的理论上限是 `5/7=0.7143`。
- **MRR@K**：第一个黄金 Chunk 排名的倒数。适合衡量第一条证据的位置；只有一个查询集合时应汇总取均值。
- **nDCG@K**：衡量整体排序。调优时应使用 `relevance=3/2/1` 的分级增益，比二值 Recall 更能区分直接答案和背景块。
- **Precision@K**：Top K 中相关 Chunk 占比。Reranker 阈值应重点看它，防止为了取满 Top 5 塞入噪声。

6 条查询太少，只适合做回归守卫和初始方向判断，不能据此认定线上质量。正式定参至少应增加
30～50 条真实视频问题，并保留独立验证集，避免把参数过拟合到这两段视频。

## 可调参数

### 1. 分块与索引

| 参数 | 当前值 | 首轮候选网格 | 主要影响指标 |
| --- | ---: | --- | --- |
| Chunk 窗口 | 90 秒 | 60/75/90/120 秒 | Recall、nDCG、Evidence Token 数 |
| Chunk 重叠 | 15 秒 | 10/15/20/30 秒 | 边界 Hit、重复率、Token 数 |
| Dense 索引文本组成 | 摘要+关键词+转写 | 调整字段是否加入及长度 | Dense Recall@25 |
| BM25 字段权重 | 当前 Lucene 实现 | transcript/summary/keywords 分别加权 | BM25 Recall@25、MRR |
| OCR 清洗/字段权重 | 独立 OCR 召回 | 去页脚、去控件、去重、主题词 boost | OCR Precision、OCR Recall |
| Reranker 文本上限 | 8000 字符 | 2000/4000/6000/8000 | nDCG、延迟、截断漏证据率 |

先固定分块再调检索参数。改变分块会改变 Ground Truth Chunk ID，属于重新标注级变更。

### 2. 三路粗召回

| 参数 | 当前值 | 建议网格 | 主要影响指标 |
| --- | ---: | --- | --- |
| Dense Top K | 25 | 10/15/25/40 | Dense Recall@K、延迟 |
| BM25 Top K | 25 | 10/15/25/40 | BM25 Recall@K、延迟 |
| OCR Top K | 10 | 5/10/15/20 | OCR Recall、噪声率 |
| Dense 最低相似度 | 仅 `>0` | 按真实分数分位数校准 | Precision、空结果率 |
| BM25 `k1` | Lucene 默认 | 0.8/1.2/1.6 | BM25 MRR、Recall |
| BM25 `b` | Lucene 默认 | 0.3/0.6/0.75/0.9 | 长短 Chunk 偏置 |

粗召回优先优化 Recall，不要先加过高阈值。某一路 Precision 低不一定有害，只要 RRF 和
Reranker 能处理；但它会增加精排成本。

### 3. RRF 融合

| 参数 | 当前值 | 建议网格 | 主要影响指标 |
| --- | ---: | --- | --- |
| Dense/BM25/OCR 权重 | 0.50/0.35/0.15 | 以 0.05～0.10 步长、权重和为 1 | RRF Recall@10、MRR@10 |
| RRF 常数 `k` | 60 | 20/40/60/80 | 头部排名敏感度、MRR |
| Fused Top K | 10 | 8/10/15/20 | Reranker 候选 Recall、延迟 |

权重应以 6 个问题的宏平均指标比较，不能只看单题。OCR-only 词要单独保留切片统计，避免总体均值掩盖 OCR 退化。

### 4. Reranker 与最终截断

`reranker.minScore` 值得增加，但不能直接拍一个固定数。当前推理服务输出模型分数，先在黄金集和
真实验证集上收集“相关/不相关”分数分布，再选择阈值。建议同时调：

- `reranker.topN`：3/5/7；
- `reranker.minScore`：按观测分布测试，例如 0.1～0.9 网格，而不是预设 0.5；
- `reranker.minKeep`：建议 1，避免阈值导致可回答问题完全空包；
- `reranker.maxKeep`：当前 5；
- `reranker.relativeDrop`：与第一名分差过大时截断，可测试 0.10/0.20/0.30；
- `fusedTopK`：决定 Cross-Encoder 能看到多少候选；
- 相邻 Chunk 合并间隔、时间重叠比例与 EvidencePack Token 上限。

阈值目标不是提高 Recall，而是在 **Hit@1/3 基本不下降** 的条件下，提高 Precision@5、
nDCG@5，并降低 EvidencePack 噪声和 Token 数。阈值后允许返回 1～5 个，不应强制补满 5 个。

## 推荐调优顺序

1. 固定分块与黄金集，记录当前六题分阶段基线。
2. 单独调 Dense/BM25/OCR，使每路和三路并集 Recall 达标。
3. 调 RRF 权重、`RRF_K` 和 Fused Top K，优化 Recall@10 与 MRR@10。
4. 固定候选后调 Reranker Top N、`minScore`、相对分差，优化 nDCG/Precision 和成本。
5. 最后调相邻合并及 Token 预算，验证完整 EvidencePack 不丢直接证据。
6. 在独立真实视频验证集复测后再写入生产默认值。

## 真实基线运行

参数调优只能使用 `RagRealModelsEndToEndTest` 生成的真实基线。该测试要求 BGE-M3、
Qdrant 和 bge-reranker-v2-m3 都处于可用状态，并显式传入：

```powershell
mvn '-Drag.real-models=true' '-Dtest=RagRealModelsEndToEndTest' test
```

普通业务流单元测试中的确定性模型替身只验证代码编排、降级和 EvidencePack 结构，
不得把它产生的分数用于模型质量结论或 `reranker.minScore` 校准。
