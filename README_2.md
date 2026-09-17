# Video Reader 个人知识库 RAG 改进方案与执行计划

> 文档状态：阶段 0～8 已实施，阶段 9 自动化验收已完成；真实视频黄金集标注与模型阈值校准待实测数据
> 适用范围：知识库全局搜索、视频内连续追问、Agent 自动分析
> 基线分支：`main`
> 基线提交：`84969b9`

## 0. 实施结果（2026-09-11）

| 阶段 | 状态 | 验证结果 |
|---|---|---|
| 0 基线与保护测试 | 完成 | 基线 58 个后端测试通过 |
| 1 检索正确性修复 | 完成 | 用户/内容范围、失败重试、降级与删除失效测试通过 |
| 2 Chunk 与索引版本 | 完成 | 90s/15s、确定性 ID、批量 Embedding、重建测试通过 |
| 3 BM25/OCR | 完成 | Lucene SmartChinese、字段召回、隔离和生命周期测试通过 |
| 4 RRF 统一内核 | 完成 | Dense 25 / BM25 25 / OCR 10、去重与降级测试通过 |
| 5 Reranker | 完成 | Top10→Top5、HTTP 契约和故障回退测试通过 |
| 6 全局知识库 | 完成 | 单次 USER_ALL 检索、Top10、每视频 Top3、无生成 LLM |
| 7 视频连续追问 | 完成 | 历史改写、原文 EvidencePack、直接回答和前端引用 |
| 8 Agent 增量检索 | 完成 | 跨 Task 微批、三轮缓存、时间戳直读、增量查询测试通过 |
| 9 评估与验收 | 自动化部分完成 | 新增 Recall@K/MRR/nDCG；后端、前端、Python、Compose 验收见本文末 |

本轮没有新增独立 Chunk 数据表。Chunk 仍使用内存缓存与 `retrieval-index` Checkpoint 恢复，符合本期约束。

模型质量校准不能用合成数据替代：`eval/rag-golden-tasks.example.json` 给出标注格式，待使用真实视频填写 `expectedChunkIds` 和时间范围后，再据 Recall@5、MRR、nDCG@5 调整 RRF 权重与 Reranker 阈值。当前不启用未经标注验证的硬阈值，避免误删有效证据。

## 1. 背景与改造目标

当前项目已经具备视频 RAG 的基本闭环：视频完成 ASR 与 OCR 后生成带时间戳的 `VideoContext`，按固定五分钟切成 `VideoChunk`，使用 BGE-M3 建立 Dense 向量并写入 Qdrant，再通过语义、关键词和 OCR 命中率加权完成检索。检索结果目前被以下功能使用：

1. 知识库跨视频搜索；
2. 视频内连续追问；
3. Agent 的 Planner → Executor → Critic 分析流程；
4. 单视频证据检索接口。

其中前三项是真实产品场景。单视频证据检索不再作为单独的产品能力设计，而是保留为底层检索接口、调试入口和质量评估入口。

本轮改造目标是将当前“简单向量 + contains 关键词”的实现升级为统一的两阶段混合检索：

```text
离线阶段
ASR/OCR → 重叠切块 → 摘要/关键词 → Dense 索引 + BM25 索引

在线阶段
查询处理 → Dense/BM25/OCR 三路召回 → 加权 RRF → Top 10
→ bge-reranker-v2-m3 精排 → Top 5 → 原始 Chunk 回读
→ EvidencePack → 搜索展示 / LLM 直接回答 / Agent 分析
```

同时解决现有实现中的以下问题：

- Qdrant 检索未按当前用户、视频或内容哈希过滤，存在跨视频排序污染；
- 固定五分钟 Chunk 粒度过粗；
- 没有真正的 BM25 倒排索引；
- 没有 Cross-Encoder 精排；
- 无相关性阈值，零分结果也可能被返回；
- 查询 Embedding 失败时不能完整降级到关键词检索；
- 连续追问只把摘要交给 LLM，没有使用原始转写证据；
- 全局知识库逐视频循环检索，视频数量增加后开销线性上升；
- Agent 每轮会对相同 Planner Task 重复检索；
- 索引失败状态、索引版本、删除清理和重建机制不完整。

## 2. 设计原则

### 2.1 一套检索内核，三种场景策略

三类场景共用同一个 `HybridRetrievalService`，场景之间只改变：

- 检索范围；
- 是否使用对话历史改写；
- 候选结果的多样性限制；
- EvidencePack 的大小；
- 最终是直接展示、直接调用 LLM，还是进入 Agent。

不为全局搜索、连续追问和 Agent 分别维护三套检索代码。

### 2.2 检索与生成分离

检索层只负责：

- 查询理解；
- 多路召回；
- 排名融合；
- 精排；
- 去重和原文证据装配。

检索层不负责生成答案。上层场景自行决定：

- 全局搜索：直接展示；
- 连续追问：调用一次回答模型；
- Agent：交给 Executor 和 Critic。

### 2.3 原文是回答真源

Qdrant、BM25 和 Reranker 用于定位候选 Chunk。最终传给 LLM 或 Agent 的内容必须重新从当前媒体的 `VideoChunk` 集合中读取，不能把 Qdrant payload 中的摘要当成最终证据。

### 2.4 可降级但不能静默错误

- Query Rewrite 失败：使用用户原问题；
- Dense Embedding 失败：保留 BM25/OCR；
- Qdrant 失败：可使用本地余弦，无法计算则跳过 Dense；
- BM25 失败：保留 Dense/OCR；
- Reranker 失败：使用 RRF Top 5；
- 所有召回分数低于阈值：返回证据不足，不用无关片段强行回答。

### 2.5 多用户与媒体范围必须在检索层强制执行

不能只依赖 Controller 已验证用户权限。每个检索调用都必须显式携带 `RetrievalScope`，向量和 BM25 查询都必须使用相同范围过滤。

## 3. 总体架构

```text
                         ┌──────────────────────────────┐
                         │       QueryProcessor         │
                         │ 清洗/对话改写/关键词/OCR词   │
                         └──────────────┬───────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
             DenseRetriever       Bm25Retriever       OcrRetriever
             Qdrant Top25         BM25 Top25          OCR Top10
                    └───────────────────┼───────────────────┘
                                        ▼
                                WeightedRrfFusion
                              chunkId 去重，Top10
                                        ▼
                                  RerankerClient
                            bge-reranker-v2-m3 Top5
                                        ▼
                              EvidencePackBuilder
                     原文回读/时间重叠处理/相邻块合并
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
               全局搜索展示          视频直接问答          Agent
```

建议新增或重构以下核心组件：

```text
service/retrieval/
├── HybridRetrievalService.java       # 统一编排入口
├── RetrievalScope.java               # USER_ALL / SINGLE_MEDIA
├── RetrievalQuery.java               # 独立问题、语义查询、关键词、OCR词
├── RetrievalCandidate.java           # 各路召回的统一候选
├── RetrievalResult.java              # 精排后的结果
├── QueryRewriteService.java           # 仅连续追问使用对话改写
├── DenseRetriever.java               # Qdrant / 本地余弦
├── Bm25IndexService.java              # 建索引、删除、搜索
├── Bm25Retriever.java
├── OcrRetriever.java
├── WeightedRrfFusion.java
├── RerankerClient.java
├── EvidencePackBuilder.java
└── RetrievalSession.java              # Agent 会话级缓存与增量检索
```

现有 `VideoEvidenceRetrievalService` 可在迁移期间作为兼容 Facade，最终只负责把旧接口转发给 `HybridRetrievalService`。

## 4. 离线索引阶段

### 4.1 Chunk 切分

当前实现按 `0～300s、300～600s` 固定窗口切分。改造后采用带少量重叠的检索块：

- 默认 Chunk 时长：90 秒；
- 默认重叠：15 秒；
- 最小有效 Chunk：15 秒或达到最小文本长度；
- 边界优先落在 ASR 片段结束位置；
- 不截断单个 ASR 片段；
- OCR 帧按时间戳归入覆盖该时间点的 Chunk；
- 同一 OCR 内容在一个 Chunk 内去重；
- 保留 `rawSegments` 用于秒级证据回溯。

第一阶段暂不引入父子 Chunk，也不额外读取 Top 5 之外的相邻块。未来当长上下文效果不足时，再增加父块或邻居扩展。

### 4.2 Chunk 暂不新增正式数据表

本期不新增 `knowledge_chunk` 表。沿用：

- 进程内 `Map<Long, List<VideoChunk>>` 缓存；
- MySQL `retrieval-index` Checkpoint 作为重启恢复来源；
- `VideoContext` Checkpoint 作为原始重建来源。

这里的“暂不持久化”指不新增独立 Chunk 表，不删除现有 Checkpoint。否则服务重启后无法低成本恢复索引和原文证据。

`VideoChunk` 新增字段：

```java
String chunkId;
int chunkIndex;
String contentHash;
int indexVersion;
```

确定性 Chunk ID：

```text
SHA-256(userId + ":" + contentHash + ":" + indexVersion + ":" + startMs + ":" + endMs)
```

加入 `userId` 是为了防止不同用户使用同一内容哈希时在 Qdrant 中互相覆盖。当前项目的内容复用本身也只允许同一用户。

### 4.3 Dense 索引文本

Dense Embedding 的输入由以下内容组成：

```text
[摘要] segmentSummary
[关键词] keyword1 keyword2 ...
[转写] transcript
[画面] 去重后的高置信度 OCR 文字
```

要求：

- 保留核心转写，不能再只有摘要和关键词；
- 对过长文本按模型输入上限裁剪；
- 优先保留摘要、关键词和 Chunk 中部/首尾完整句；
- BGE-M3 输出继续使用归一化 1024 维稠密向量；
- Embedding 批量请求，避免逐 Chunk 单独 HTTP 调用。

Qdrant payload：

```json
{
  "userId": 1,
  "mediaId": 10,
  "contentHash": "...",
  "chunkId": "...",
  "chunkIndex": 3,
  "indexVersion": 2,
  "startMs": 225000,
  "endMs": 315000,
  "summary": "...",
  "keywords": ["..."]
}
```

Qdrant 点 ID 使用确定性 UUID，输入必须包含 `userId + contentHash + indexVersion + chunkIndex`。

### 4.4 BM25 与 OCR 索引

初期采用嵌入式 Lucene，使用 `BM25Similarity`，避免马上增加新的中间件。接口必须抽象，未来可替换为 OpenSearch/Elasticsearch。

每个 Lucene Document 包含：

```text
chunkId       StringField
userId        StringField
mediaId       StringField
contentHash   StringField
indexVersion  IntPoint/StringField
startMs       LongPoint/StoredField
endMs         LongPoint/StoredField
transcript    TextField
summary       TextField
keywords      TextField
ocrText       TextField
```

逻辑召回分为：

1. BM25 内容召回：
   - `transcript`：主字段；
   - `summary`：中等权重；
   - `keywords`：高权重；
2. OCR 召回：
   - 单独查询 `ocrText`；
   - 得到独立排名列表，进入 RRF；
   - OCR 精确术语、英文缩写、数字型号应获得额外 boost。

中文分词需要单独验证。若标准分析器效果不足，第一阶段采用适合中文的 Lucene Analyzer；生产多实例阶段再迁移到 OpenSearch 中文分词方案。

### 4.5 索引版本与重建

新增统一版本常量，例如：

```text
RETRIEVAL_INDEX_VERSION=2
```

以下任一项变化必须提升版本：

- Chunk 时长或重叠策略；
- Dense 索引文本拼装；
- Embedding 模型或维度；
- BM25 Analyzer 或字段权重；
- Chunk ID 生成规则。

读取 Checkpoint 时必须同时验证：

- Checkpoint stage 为 `INDEXED`；
- `indexVersion` 与当前版本相同；
- Dense 和 BM25 索引均可用。

`FAILED` Checkpoint 不能被永久当成有效索引返回。需要允许自动重试或人工触发重建。

## 5. 在线混合检索

### 5.1 统一查询对象

```java
record RetrievalQuery(
    String originalQuestion,
    String standaloneQuestion,
    String semanticQuery,
    List<String> keywords,
    List<String> ocrKeywords
) {}
```

### 5.2 三路召回数量

固定初始参数：

| 召回通道 | 候选数 | 主要用途 |
|---|---:|---|
| Dense | 25 | 语义相近、同义表达 |
| BM25 | 25 | 精确术语、关键词、数字 |
| OCR | 10 | PPT、板书、画面文字 |

所有召回接口都必须使用 `RetrievalScope`：

```java
record RetrievalScope(Long userId, Long mediaId, ScopeType type) {}

enum ScopeType {
    USER_ALL,
    SINGLE_MEDIA
}
```

### 5.3 加权 RRF

不同召回通道的原始分数不在同一量纲，不直接归一化相加，统一按排名使用加权 RRF：

```text
score(chunk) =
    0.50 / (60 + denseRank)
  + 0.35 / (60 + bm25Rank)
  + 0.15 / (60 + ocrRank)
```

约束：

- 未进入某通道排名时，该通道贡献为 0；
- 先按 `chunkId` 合并三路候选；
- 保存每个候选的来源、各路排名和 RRF 分数，供 Trace 使用；
- RRF 后保留 Top 10；
- 不把 RRF 分数展示为“可信度”或“支撑率”。

权重后续通过黄金任务集调整，不在业务代码中散落硬编码。

### 5.4 Cross-Encoder 精排

新增本地推理服务：

```text
inference/reranker/app.py
默认端口：8003
模型：bge-reranker-v2-m3
```

接口建议：

```http
POST /rerank
```

请求：

```json
{
  "query": "独立问题",
  "documents": [
    {"id": "chunk-1", "text": "候选文本"},
    {"id": "chunk-2", "text": "候选文本"}
  ],
  "topN": 5
}
```

要求：

- 一次批量精排 10 个候选；
- Reranker 文本使用摘要、关键词、原始转写和 OCR 的受控拼接；
- 返回 `chunkId + score`；
- Reranker 超时或不可用时，降级为 RRF Top 5；
- Reranker 分数阈值必须通过黄金任务集校准；
- 最终不足 5 条时如实返回，不用低相关候选补满。

### 5.5 去重、重叠和相邻块合并

处理顺序：

1. 三路召回按 `chunkId` 去重；
2. RRF Top 10；
3. Reranker Top 5；
4. 读取这 5 个 Chunk 原文；
5. 处理时间重叠和相邻内容。

第一版合并规则：

- 仅合并同一 `mediaId`；
- 时间区间高度重叠时保留高分候选为主，原文去重合并；
- 两个入选 Chunk 时间间隔不超过 15 秒时，按时间顺序合并；
- 合并后的 Evidence Item 记录 `chunkIds`，不能丢失来源；
- 不额外读取 Top 5 之外的邻居；
- 最终物理读取最多 5 个 Chunk，Evidence Item 数量不超过 5。

## 6. 场景一：知识库全局搜索

### 6.1 场景定位

目标是帮助用户定位“哪个视频、哪个时间段出现了相关内容”，不负责生成综合回答。

### 6.2 查询流程

```text
原始问题
→ 基础清洗与确定性关键词提取
→ RetrievalScope(USER_ALL, userId)
→ Dense Top25 / BM25 Top25 / OCR Top10
→ 加权 RRF Top10
→ Reranker
→ 每个视频最多3条
→ 全局Top10
→ 前端搜索结果
```

这里不维护对话历史、不执行生成式 LLM Query Rewrite、不调用答案生成模型。BGE-M3 和本地 Reranker 仍会使用。

### 6.3 性能改造

当前实现逐个遍历用户视频，再逐视频进行检索。改造后：

- Qdrant 按 `userId` 过滤并一次全局搜索；
- Lucene 按 `userId` 过滤并一次全局搜索；
- OCR 同样一次全局搜索；
- 不再执行 `视频数量 × 查询` 的循环；
- 同一视频最多保留 3 条，避免一个视频占满结果。

### 6.4 返回数据

```json
{
  "mediaId": 10,
  "title": "视频标题",
  "filename": "原文件名.mp4",
  "chunkId": "...",
  "summary": "片段摘要",
  "keywords": ["关键词1", "关键词2"],
  "startMs": 192000,
  "endMs": 268000
}
```

前端：

- 不显示相关度百分比；
- 显示视频标题，文件名作为辅助信息；
- 显示摘要、关键词和时间范围；
- 点击后跳转工作台并定位到 `startMs`；
- 空结果明确显示“没有找到相关片段”。

## 7. 场景二：视频内连续追问

### 7.1 场景定位

连续追问使用 RAG 检索增强后直接调用回答模型，不进入 Planner、Executor、Critic。

### 7.2 轻量查询改写

输入：

- 当前用户问题；
- 最近 3～5 轮对话；
- 当前视频标题，可选。

输出固定 JSON：

```json
{
  "standaloneQuestion": "脱离对话历史也能理解的完整问题",
  "semanticQuery": "适合Dense召回的语义表达",
  "keywords": ["用于BM25的词"],
  "ocrKeywords": ["可能出现在PPT或板书中的词"]
}
```

改写模型职责仅限：

- 消解“它、这个、第二点、刚才说的”等指代；
- 保留原问题意图；
- 提取术语、数字和 OCR 候选词；
- 不回答问题；
- 不增加视频中未出现的事实。

改写失败或格式异常时：

```text
standaloneQuestion = originalQuestion
semanticQuery = originalQuestion
keywords = 确定性分词结果
ocrKeywords = keywords
```

### 7.3 检索与生成流程

```text
当前问题 + 最近对话
→ 轻量改写
→ RetrievalScope(SINGLE_MEDIA, userId, mediaId)
→ Dense/BM25/OCR
→ RRF Top10
→ Reranker Top5
→ 回读最多5个原始Chunk
→ EvidencePack
→ 回答模型生成答案
```

EvidencePack 示例：

```text
[证据1]
chunkIds: c1,c2
视频: TCP课程
时间: 03:12~04:31
转写原文: ...
OCR文字: ...

[证据2]
...
```

回答提示词约束：

- 只根据 EvidencePack 回答视频内容问题；
- 证据不足时明确说明；
- 关键结论标记证据编号；
- 不把模型外部常识冒充为视频内容；
- 回答保持自然，不展示 RRF/Reranker 内部得分。

接口返回：

```json
{
  "answer": "回答正文",
  "evidence": [
    {
      "chunkIds": ["c1", "c2"],
      "mediaId": 10,
      "startMs": 192000,
      "endMs": 271000,
      "quote": "用于支撑回答的原文"
    }
  ],
  "insufficientEvidence": false,
  "history": []
}
```

前端在回答下方显示可点击时间戳证据。

## 8. 场景三：Agent 自动分析

### 8.1 场景定位

Agent 继续使用 Planner → Executor → Critic。统一混合检索为 Agent 提供证据，但 Agent 不重复使用连续追问的对话改写流程。

Planner 生成的 Task 本身应当是独立、可检索的问题。必要时只做确定性关键词扩展，不额外调用一次生成式 Query Rewrite。

### 8.2 第一轮基础检索

```text
Planner Tasks
→ 批量构建 RetrievalQuery
→ 批量 Dense Embedding
→ 批量 Dense/BM25/OCR 召回
→ 每个 Task RRF Top10
→ 批量 Reranker Top5
→ 建立 EvidencePool
→ 按任务覆盖与时间多样性构建 EvidencePack
```

要求：

- Planner Task 数量设置上限；
- 每个 Task 至少保留一个有效证据，前提是存在相关结果；
- 单任务最多保留 3～5 个候选；
- 跨任务按 `chunkId` 去重；
- 同一时间段避免重复占用预算；
- 最终 EvidencePack 默认最多 8～12 个证据项；
- 原始检索结果存入当前 Agent 运行的 `RetrievalSession`。

### 8.3 避免三轮重复检索

当前最多执行三轮：

```text
round 0: EvidencePack → Executor → Critic
round 1: EvidencePack → Executor → Critic
round 2: EvidencePack → Executor → Critic
```

现有实现会在每轮对所有 Planner Task 重新检索。改造为“一次基础检索 + Critic 增量检索”。

`RetrievalSession` 保存：

```java
record RetrievalSession(
    Long mediaId,
    int indexVersion,
    Map<String, List<RetrievalResult>> queryCache,
    Map<String, VideoChunk> evidencePool
) {}
```

查询缓存 Key：

```text
mediaId + indexVersion + normalizedQuery
```

### 8.4 Critic 结构化动作

Critic 输出增加：

```json
{
  "passed": false,
  "retrievalAction": "REUSE",
  "searchQueries": [],
  "requiredTimestamps": [],
  "feedback": ["修改结论表述"]
}
```

动作定义：

| 动作 | 含义 | 是否执行完整 RAG |
|---|---|---|
| `REUSE` | 证据已够，只需要修改答案 | 否 |
| `ADD_TIMESTAMP` | 已知需要补充的时间位置 | 否，直接读取对应 Chunk |
| `SEARCH` | 当前 EvidencePool 中确实缺少证据 | 是，只检索新查询 |

规则：

- Critic 因格式、措辞、覆盖方式不通过时必须返回 `REUSE`；
- Critic 指定时间戳时直接从当前 Chunk 集合读取，不重新跑 Dense/BM25/Reranker；
- 只有给出新的、可执行的 `searchQueries` 时才执行增量 RAG；
- 原 Planner Tasks 的检索结果不得重复计算；
- 新增证据进入 EvidencePool 后重新构建下一轮 EvidencePack。

### 8.5 Agent 检索次数目标

优化后：

- 常见情况：一次基础混合检索，后续轮次复用；
- 需要指定时间戳：仍然只有一次完整混合检索；
- 真正缺少证据：基础检索 + 1～2 次增量检索；
- 最坏逻辑检索阶段：3 次；
- 不会因为 3 次 Executor/Critic 循环产生 5 次 RAG；
- 多个 Planner Task 应批量 Embedding、批量查询、批量 Reranker，减少 HTTP 往返。

Agent 的主要延迟仍可能来自：

- Planner 生成；
- 最多 3 次 Executor；
- 最多 3 次 Critic；
- 最终逐结论的语义蕴含判断；
- L3 独立复核。

因此需要分别统计“检索耗时”和“LLM/验证耗时”，不能只统计总 Agent 时间。

## 9. 原证据搜索接口定位

保留：

```http
GET /analysis/evidence-search
```

但其内部改为调用统一 `HybridRetrievalService`，用途调整为：

- 开发调试；
- 检索质量 Trace；
- 黄金任务评估；
- 必要时供前端证据面板调用。

它不再拥有独立检索算法。

## 10. API 调整

### 10.1 全局搜索

保留：

```http
GET /analysis/global-search?query=...&topK=10
```

内部改为单次用户范围混合检索。后续可迁移为：

```http
GET /knowledge/search
```

### 10.2 连续追问

保留：

```http
POST /analysis/chat
```

返回值增加 `evidence` 与 `insufficientEvidence`。

前端传入的 history 与服务端 Checkpoint 历史需要统一真源。建议以后端持久化历史为准，客户端不重复发送完整 history，只发送当前问题。

### 10.3 索引管理

增加内部或管理员接口：

```http
POST /analysis/index/rebuild?mediaId=...
GET  /analysis/index/status?mediaId=...
```

第一阶段可以只实现 Service 方法和自动重建，暂不开放前端按钮。

## 11. 索引删除与生命周期

删除媒体时必须同步处理：

- RetrievalIndexService 内存缓存；
- Lucene BM25 文档；
- Qdrant Dense 点；
- Retrieval Checkpoint；
- Agent RetrievalSession。

因为本期 Qdrant 点 ID 包含 `userId`，删除当前媒体的向量不会影响其他用户。

如果同一用户同一 `contentHash` 存在多条复用记录，需要先检查是否仍有其他媒体引用：

- 有引用：保留内容级索引；
- 无引用：删除 Qdrant/Lucene 内容索引；
- 无论是否有引用，都要清除被删除 `mediaId` 的内存缓存和会话缓存。

## 12. 相关性阈值与空结果

当前检索会强制返回至少一条结果。改造后：

- RRF 仅负责融合，不单独作为最终阈值；
- 使用 Reranker 分数判断最终相关性；
- 阈值必须通过黄金任务集校准；
- 若 Reranker 不可用，则使用“至少两路召回命中”或 BM25/Dense 的保守规则；
- 低于阈值的候选直接删除；
- 最终可以返回 0～5 条，而不是强制补满 5 条。

连续追问没有有效证据时：

```text
当前视频中没有检索到足以回答该问题的内容。
```

不调用回答模型，或者只使用固定文案，避免无证据生成。

## 13. 可观测与 Trace

每次检索记录：

```text
scene                  GLOBAL_SEARCH / VIDEO_CHAT / AGENT
scope                  USER_ALL / SINGLE_MEDIA
rewriteMs
denseMs
bm25Ms
ocrMs
rrfMs
rerankMs
evidenceBuildMs
denseCandidateCount
bm25CandidateCount
ocrCandidateCount
rrfCandidateCount
finalCandidateCount
fallbacks
```

Agent 额外记录：

```text
baseRetrievalBatches
incrementalRetrievalBatches
retrievalCacheHits
timestampDirectLoads
executorCalls
criticCalls
```

Trace 中允许显示内部排名和分数，普通知识库页面和对话页面不展示内部相关度百分比。

## 14. 测试计划

### 14.1 Chunk 测试

- 90 秒窗口与 15 秒重叠；
- ASR 片段不被截断；
- OCR 正确归属重叠窗口；
- Chunk ID 稳定且版本变化后改变；
- 空 ASR、纯 OCR、短视频和边界时间戳。

### 14.2 Dense/Qdrant 测试

- 单视频查询只命中当前 `mediaId`；
- 全局查询只命中当前 `userId`；
- 相同内容的不同用户不会覆盖；
- 查询 Embedding 失败时降级；
- Qdrant 失败时本地余弦或 BM25 可用；
- 删除和重建索引；
- 索引版本不匹配时自动重建。

### 14.3 BM25/OCR 测试

- 中文术语；
- 英文缩写；
- 数字型号，例如 `802.11n`；
- OCR-only 内容；
- userId/mediaId 过滤；
- 删除媒体后不可检索。

### 14.4 RRF 测试

- 三路分数尺度不同不影响排名融合；
- 相同 `chunkId` 正确去重；
- 权重和排名公式稳定；
- 单路失败时其他通道仍可排序。

### 14.5 Reranker 测试

- Top 10 输入、Top 5 输出；
- 返回顺序正确；
- 超时和服务失败回退 RRF；
- 低相关候选被阈值剔除；
- 批量请求不会混淆 ID。

### 14.6 连续追问测试

- “它、第二点、上面的方法”等指代消解；
- 改写失败回退原问题；
- 只检索当前视频；
- LLM 得到的是原始 EvidencePack，不是摘要列表；
- 回答携带时间戳证据；
- 无证据时不生成幻觉答案。

### 14.7 全局知识库测试

- 单次用户级查询，不逐视频循环；
- 同一视频最多 3 条；
- 全局最多 10 条；
- 不调用生成式 LLM；
- 前端不显示相关度；
- 点击结果正确跳转时间戳。

### 14.8 Agent 测试

- 第一轮批量检索 Planner Tasks；
- 第二轮相同 Task 命中缓存；
- `REUSE` 不触发新检索；
- `ADD_TIMESTAMP` 只读取 Chunk；
- `SEARCH` 只执行新增查询；
- 三轮最多三个逻辑检索阶段；
- EvidencePool 跨轮保留；
- Executor/Critic 原有证据绑定和可信验证不回归。

## 15. 质量评估

当前固定小型 RAG 黄金集为 `test/fixtures/rag/golden-set.json`：20 分钟 Redis 视频和
40 分钟网络排障视频各 3 个连续问题，每题人工排序 Top 7 Chunk，并保留原始追问、
独立检索问题、时间范围、1～3 级相关性和标注理由。`RagGoldenSetTest` 会重建分块并
校验所有 Chunk ID，作为以后参数调优的回归守卫。完整口径见
`test/fixtures/rag/RAG_TUNING.md`。

正式线上校准仍需继续扩充真实视频问题；这 6 题只用于固定回归和小范围参数筛选。
每个任务结构示例：

```json
{
  "turn": 1,
  "userQuestion": "用户原始追问",
  "standaloneQuery": "结合历史消解后的独立问题",
  "expectedTop7": [
    {"rank": 1, "chunkId": "...", "startMs": 100000,
     "endMs": 180000, "relevance": 3, "reason": "直接回答"}
  ]
}
```

核心指标：

- Recall@25：各单路召回；
- Recall@10：RRF；
- Recall@5：Reranker；
- Hit@K 与 MRR；
- nDCG@5；
- Precision@5、EvidencePack 重复率与 Token 数；
- 跨用户错误召回数，必须为 0；
- 跨视频错误召回数，单视频场景必须为 0；
- 空问题/无答案问题误召回率；
- 连续追问独立问题改写准确率；
- Agent 平均和 P95 检索批次数。

## 16. 执行阶段

### 阶段 0：建立基线与保护测试

目标：先用测试稳定复现现有问题，避免在大规模重构中丢失行为。

任务：

- 补 `VideoEvidenceRetrievalServiceTest`；
- 补 `QdrantVectorStore` 请求体测试；
- 补跨视频排序污染测试；
- 补 Embedding 失败降级测试；
- 补零相关结果测试；
- 记录当前全局搜索、连续追问、Agent 的基线耗时。

建议提交：

```text
test(rag): add retrieval isolation and fallback regression coverage
```

### 阶段 1：检索正确性热修复

目标：在引入 BM25 和 Reranker 前，先修复现有 Dense 检索的正确性。

任务：

- Qdrant 查询加入 userId/mediaId/contentHash 过滤；
- 点 ID 加入用户维度；
- `semanticSource` 改为方法局部返回值；
- 捕获 Query Embedding 异常并降级；
- 不再强制返回零分候选；
- 删除媒体时 invalidate 内存缓存；
- 修复 FAILED Checkpoint 永久生效问题。

建议提交：

```text
fix(rag): enforce scoped dense retrieval and resilient fallback
```

### 阶段 2：重叠 Chunk 与索引版本

任务：

- 新增确定性 `chunkId`；
- 90 秒 Chunk + 15 秒重叠；
- Dense 索引文本加入核心转写和 OCR；
- Embedding 批处理；
- 增加 `indexVersion`；
- 旧 Checkpoint 自动重建；
- 更新 Chunk 和索引测试。

建议提交：

```text
feat(rag): add overlapping chunks and versioned dense index
```

### 阶段 3：BM25 与 OCR 召回

任务：

- 引入 Lucene；
- 实现 BM25 索引生命周期；
- 实现 BM25 内容召回；
- 实现 OCR 独立召回；
- 与视频删除、重建流程集成；
- 加入中文、术语、数字和 OCR 测试。

建议提交：

```text
feat(rag): add scoped BM25 and OCR retrieval
```

### 阶段 4：RRF 与统一检索内核

任务：

- 定义 RetrievalScope/Query/Candidate/Result；
- 实现加权 RRF；
- 实现 `HybridRetrievalService`；
- 旧 `VideoEvidenceRetrievalService` 转为兼容 Facade；
- 三个场景开始共用统一内核。

建议提交：

```text
refactor(rag): unify retrieval with weighted RRF fusion
```

### 阶段 5：Reranker

任务：

- 新增 `inference/reranker/app.py`；
- 增加 Docker、生产 Compose、启动/停止脚本和健康检查；
- 实现 Java `RerankerClient`；
- Top 10 → Top 5；
- 超时降级；
- 增加批量和阈值测试。

建议提交：

```text
feat(rag): add bge reranker second-stage ranking
```

### 阶段 6：知识库全局搜索

任务：

- 删除逐视频检索循环；
- 使用 USER_ALL 检索范围；
- 同视频最多 3 条、全局 Top 10；
- 返回标题、摘要、关键词和时间戳；
- 前端移除“支撑百分比”；
- 保持点击跳转。

建议提交：

```text
feat(knowledge): switch global search to scoped hybrid retrieval
```

### 阶段 7：视频连续追问

任务：

- 实现历史感知轻量改写；
- SINGLE_MEDIA 混合检索；
- EvidencePack 原文装配；
- 直接调用回答 LLM，不经过 Agent；
- 返回证据与 `insufficientEvidence`；
- 前端展示可点击引用。

建议提交：

```text
feat(chat): answer from reranked original evidence with citations
```

### 阶段 8：Agent 会话缓存与增量检索

任务：

- Planner Task 批量检索；
- 新增 RetrievalSession 和 EvidencePool；
- Critic 输出结构化 RetrievalAction；
- 支持 REUSE/ADD_TIMESTAMP/SEARCH；
- 相同查询跨轮复用；
- 增加检索批次数 Trace；
- 验证三轮循环不会重复执行基础检索。

建议提交：

```text
perf(agent): reuse evidence pool and retrieve incrementally
```

### 阶段 9：评估、文档与验收

任务：

- 扩展黄金任务集；
- 校准 RRF 权重和 Reranker 阈值；
- 运行后端、前端、推理服务测试；
- 更新主 README 和部署文档；
- 输出改造前后性能与质量报告。

建议提交：

```text
test(rag): add hybrid retrieval evaluation and acceptance report
```

实施记录：

- 已新增 `RagRetrievalMetrics`，覆盖 Recall@K、MRR、二值相关性 nDCG@K，并处理重复 Chunk；
- 已新增 `eval/rag-golden-tasks.example.json`，作为真实视频标注模板；
- 已完成跨 Planner Task 的批量 Embedding 与批量 Reranker，以及自动化构建与降级路径测试；
- RRF 权重暂采用 `0.50/0.35/0.15，k=60` 的设计基线；
- Reranker 硬阈值留待真实黄金集校准，不使用合成数据拍定生产阈值。

Agent 检索量对比（设 Planner 有 N 个不同任务，Critic 新增 M 个不同查询）：

```text
改造前：3 轮最坏约 3N 次查询管线
改造后：N + M 次唯一查询；REUSE 和 ADD_TIMESTAMP 不增加查询
```

例如 N=5 且三轮都只要求改写或指定时间戳时，查询管线从约 15 次降为 5 次；如后两轮各提出 1 个真正的新问题，则为 7 次。Trace 另外按轮记录逻辑检索批次，常见为 1，最坏为 3。

## 17. 实施顺序与依赖

```text
阶段0 基线测试
  ↓
阶段1 Dense正确性修复
  ↓
阶段2 Chunk与索引版本
  ↓
阶段3 BM25/OCR
  ↓
阶段4 RRF统一内核
  ↓
阶段5 Reranker
  ├──────────────┐
  ↓              ↓
阶段6 全局搜索   阶段7 连续追问
  └──────┬───────┘
         ↓
阶段8 Agent增量检索
         ↓
阶段9 评估验收
```

阶段 1 必须优先，因为当前 Qdrant 无范围过滤会影响后续所有评估结果。阶段 8 最后进行，以避免 Agent 优化期间底层检索接口持续变化。

## 18. 验收标准

### 18.1 正确性

- 单视频场景不读取其他视频候选；
- 所有场景不读取其他用户候选；
- 三路候选按 RRF 正确去重；
- Reranker 最多输出 5 个 Chunk；
- EvidencePack 使用原始转写/OCR；
- 无相关证据时返回空结果或证据不足；
- 视频删除后不可再从任何索引检索。

### 18.2 场景验收

- 全局搜索不调用生成式大模型，不逐视频循环，同视频最多 3 条；
- 连续追问能处理指代问题，并返回直接生成带时间戳证据的回答；
- Agent 第一轮建立 EvidencePool，后续相同查询命中缓存；
- Critic 的格式/措辞修改不触发 RAG；
- 三轮 Agent 最多只有 3 个逻辑检索阶段，常见情况只有 1 个。

### 18.3 工程验收

- 后端测试全部通过；
- 前端 SFC 检查与生产构建通过；
- Reranker、Embedding、Qdrant、BM25 任一单点故障均有明确降级；
- Git 每个阶段独立提交，可单独回滚；
- 主 README、部署配置和启动脚本与实际实现一致。

## 19. 本期不做

- PDF、Word、网页、Markdown 等非视频知识源；
- 独立知识库/集合/标签管理；
- 跨视频综合生成式问答；
- 独立 `knowledge_chunk` 数据表；
- 父子 Chunk 和 Top 5 之外的邻居扩展；
- OpenSearch/Elasticsearch 集群化；
- 分布式 RetrievalSession。

这些能力可以在本轮三类视频 RAG 场景稳定后继续演进。

## 20. 自动化验收记录（2026-09-11）

| 检查项 | 结果 |
|---|---|
| 后端 Java 21 编译与打包 | `mvn package` 成功，生成 Spring Boot JAR |
| 后端测试 | 106/106 通过，0 failure，0 error |
| 前端生产构建 | `npm run build` 成功，51 个模块完成转换 |
| Reranker Python 语法 | `py_compile` 通过 |
| Reranker 单查询/跨 Task 批量请求与排序契约 | 伪 Cross-Encoder 模型测试通过 |
| Compose | 开发与生产配置均通过 `docker compose config --quiet` |
| 启停脚本 | 3 个 PowerShell 脚本与 `setup-server.sh` 语法检查通过 |
| 配置与仓库检查 | 评估 JSON 可解析，`git diff --check` 通过，未发现明显密钥模式 |

本次自动化验收没有启动真实 BGE-M3、bge-reranker-v2-m3、Qdrant、MySQL、Kafka、MinIO 和外部 LLM 做整链路压测，因此不虚构端到端延迟与 Recall 数据。下一步使用真实视频完成黄金集标注后，运行模型质量校准和 P50/P95 延迟对比。

### 20.1 真实 RAG 检索基线补充（2026-09-13）

上述 2026-09-11 记录保留为历史状态。当前已新增显式启用的
`RagRealModelsEndToEndTest`，并使用 20/40 分钟固定过程样本完成 6 个查询的真实链路：

- BGE-M3 服务生成 1024 维 Dense Embedding；
- 48 个 Chunk 通过生产 `QdrantVectorStore` 写入真实 Qdrant collection；
- Lucene 执行真实 BM25 内容召回与 OCR 字段召回；
- 生产 `WeightedRrfFusion` 执行加权 RRF Top10；
- `RerankerClient` 通过 HTTP 调用真实 `bge-reranker-v2-m3`；
- 生产 `ChatEvidenceService` 回读并合并完整 EvidencePack；
- 不调用生成式 LLM，固定黄金集直接提供历史消解后的独立查询。

真实测试必须显式传入 `-Drag.real-models=true`；普通单元测试不会把模型替身结果标记为
真实基线。报告位于 `server/target/rag-test-reports/real-models-six-query-full-flow.txt`。

### 20.2 扩展 20 × 20 分钟回归集（2026-09-14）

新增 `test/fixtures/rag/expanded-v1/`，保留原 20/40 分钟样本。每视频三轮问题，
共 60 题：40 道可回答、10 道主题相近但缺失细节、10 道跨主题问题。每视频提供
ASR/OCR JSON 与时间戳纯文本，以及生产对齐、分块、抽取式摘要/关键词的派生过程文件。
这是人工知识点加确定性模板生成的合成集，不等同于真实视频分布。

相关性标签和源证据区间单独放在 `evaluator/`，不进入问题、索引或检索参数。
Groundtruth 根据源区间和真实 rawSegments 投影为 chunkId，不根据召回结果生成，
也不给无关题强凑 Top7。按视频分为10个校准视频和10个验证视频，禁止用验证集选阈值。

执行阶段：

1. `ExpandedRagDatasetTest` 验证时长、文本量、过程格式、ID和无标签输入，生成过程文件。
2. `ExpandedRagEvaluationTest` 验证标注覆盖、视频级划分和分数过滤/低相关提示行为。
3. 显式开启 `-Drag.expanded-real=true` 执行 `ExpandedRagRealModelsTest`：真实
   BGE-M3 → Qdrant + Lucene BM25/OCR → RRF Top10 → bge-reranker-v2-m3 Top5
   → 原文回读和 EvidencePack，逐阶段记录同一次业务调用的数据；发生 Dense/精排降级则失败。

本轮仍不调用生成式 LLM：使用预置独立查询、业务抽取式摘要/关键词；不验证历史改写和回答生成。
低相关拒绝/提示策略仅在 `server/src/test` 实验评估，**不改变主业务默认召回和提示词**。
真实点在现有 Qdrant collection 中以测试 userId=982000、独立 contentHash 隔离；不删除业务点。

指标使用相同 K 比较 RRF@5 与 reranker@5，并报告 Hit@1、Recall、MRR、nDCG、
负例拒绝率、正例误拒率、错误接受证据率与查询延迟。没有生成回答，回答幻觉率标为
NOT_MEASURED，不能用检索层错误率替代。16块视频的 Dense Top25 近乎全召回，
因此本集不足以验证大知识库的粗召回难度。

完整说明和运行方法见 `test/fixtures/rag/expanded-v1/README.md`。
真实报告目录：`server/target/rag-test-reports/expanded-v1/`，每题有 trace、evaluation
和过滤前后完整 evidencepack；读取前检查本轮 `status.txt` 为 COMPLETE。

### 20.3 三个主业务入口接入与关键修复（2026-09-14）

20.2 的“仅测试实验、不改变主业务”是当时的历史状态。本次将统一相关性提示和
降级诊断接入视频分析、视频追问、跨视频检索；没有改动视频样本、问题、标签或
groundtruth，也没有直接采用实验中的 0.95 硬拒绝阈值。

- 视频分析：复用任务批量检索和会话缓存，把每项任务的相关性/降级提示传入
  Executor/Critic 的 EvidencePack，并记录到 SSE 和 checkpoint。相同查询不重复跑 RAG。
- 视频追问：最近对话轻量改写 → 三路召回 → RRF Top10 → 精排最多 Top5 → 原文合并
  → 直接答案模型；不进入 Agent。响应和聊天历史新增 `retrieval` 诊断字段。
- 跨视频检索：只检索当前用户；即使冷索引也不请求生成式 LLM。对三路并集最多60条
  精排后，再限制每视频3条、总共10条，避免先截10条再限额导致漏掉后续视频。
  保留旧列表接口，新增 `/analysis/global-search/details` 返回 `{hits,retrieval}`。

修正了证据时间范围与原始 ASR 的对齐、RRF/精排分数类型区分、精排响应校验、
Spring 多构造器注入和配置绑定。模型服务预热完成才报告 ready，生产 Compose 等待
模型健康。默认 `RAG_MIN_RERANKER_SCORE=0.5`、`RAG_REJECT_LOW_RELEVANCE=false`：
只提示、不硬拒绝；0.5 是可配置启发式值，不是已校准的回答正确率。

接口、部署参数、验证范围见 [主业务 RAG 接入说明](docs/RAG_PRODUCTION.md)。
真实三入口证据报告由 `ProductionRagRealModelsTest` 生成于
`server/target/rag-test-reports/production-integration.json`；不调用生成式 LLM，
因此不能把这次检索验证称为回答质量或整站端到端验证。
