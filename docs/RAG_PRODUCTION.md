# 主业务 RAG 接入说明

本次只修复与接入主程序，不调整已有 ASR/OCR 样本、六轮原始问题、扩展集标签和
groundtruth。协议回归与真实检索入口验证是新增测试，不是新增业务测试场景。

## 三个入口

| 入口 | 检索与消费方式 | 输出 |
|---|---|---|
| 视频分析 | 任务查询不改写；多任务批量 embedding/reranker；同轮次链路共用 RetrievalSession；Critic 的时间戳直接定位原文 | 带任务诊断的紧凑 EvidencePack，供 Executor/Critic 使用 |
| 视频追问 | 最近对话轻量改写；Dense25、BM25 25、OCR10；chunkId 去重的加权 RRF10；reranker 最多5 | 按原文时间合并相邻/重叠片段，直接调用答案 LLM，不走 Agent |
| 跨视频检索 | 当前 userId 范围；不进行历史改写或答案生成；冷索引使用抽取式摘要/关键词 | 最多10条、每视频最多3条；标题、摘要、关键词、时间和定位ID，不显示分数 |

跨视频精排保留三路召回并集（最多60条），随后应用视频配额。只能从这批候选中
补足结果，不保证任意查询一定有10条。该入口候选更多，CPU服务器需要单独压测。

Agent 沿用每任务最多2块、任务块最多8块的紧凑证据预算，不等于聊天完整 Top5
原文包。任务结果缓存的是原始命中和诊断，再应用相关性策略，不缓存过滤后的空列表。

## 相关性与降级契约

公共诊断对象：`retrieval: {status, degraded, hint}`，不包含数值分数。

- `CANDIDATE_EVIDENCE`：有超过提示阈值的候选，不承诺原文足以回答。
- `LOW_RELEVANCE`：精排候选全部低于阈值，提示原文可能不足；不是“肯定无关”。
- `NO_EVIDENCE`：没有候选，且没有检测到检索链路故障。
- `DEGRADED`：检索通道或精排异常、旧分数未知等；不能把服务异常当成无关问题。

`degraded` 单独标记部分链路异常，可能与 `LOW_RELEVANCE` 同时出现。
内部 `EvidenceHit.scoreType` 区分 `RERANKER_SIGMOID`、`RRF`、`UNKNOWN`。
旧 JSON 未提供类型时读作 UNKNOWN，绝不拿 RRF 的数值套精排阈值。
精排返回数量、ID、重复项和有限的 [0,1] 数值都会校验，异常降级为 RRF 并提示。

默认只提示、不删除候选。若显式打开硬过滤，只保留超过阈值的精排项；
精排不可用时仍保留 RRF 候选并报告降级。相关性分数不是回答正确概率。

## 接口和数据兼容

- 现有聊天响应字段保留，新增 `retrieval`；assistant 聊天历史保存相同诊断。
  旧历史缺失此字段时可正常读取。
- 保留 `GET /analysis/global-search?query=...&topK=10` 的列表返回。
- 新增 `GET /analysis/global-search/details?query=...&topK=10`，返回
  `{ "hits": [...], "retrieval": { "status": "...", "degraded": false, "hint": "..." } }`。
  前端知识库已切换到新接口；认证和用户范围沿用原控制器逻辑。
- Agent 的 `AGENT_EXECUTE` SSE 增加 `retrieval` 任务映射；每轮诊断写入
  `<goalKey>-retrieval-<round>` 的 `RETRIEVED` checkpoint。
- EvidenceBounds 使用保留的原始片段起止时间，修正显示区间比实际原文窄的问题；
  无 rawSegments 的旧块退回块窗口。chunkId、分块窗口和 INDEX_VERSION=2 不变。
- 原文按已命中的 chunkId 回读。Agent 有明确 ID 却读不到时，不用同时间的另一个
  Chunk 冒充；仅旧的无 ID 记录和 Critic 时间戳定位使用时间查找。

本次不需要数据库表迁移或因时间显示修复而重建索引；不新增 Chunk 持久化方案。
已有 checkpoint/Lucene/Qdrant 生命周期保持原机制。

## 部署参数与就绪状态

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| RAG_MIN_RERANKER_SCORE | 0.5 | 精排低相关提示阈值，须校准，范围[0,1] |
| RAG_REJECT_LOW_RELEVANCE | false | 是否硬过滤低分项，默认关闭 |
| RERANKER_READ_TIMEOUT_MS | 60000 | 精排 HTTP 读取超时，正整数 |

后端对应 `app.retrieval.min-reranker-score`、`app.retrieval.reject-low-relevance`、
`app.ai.reranker.read-timeout-ms`。生产 Compose 已传递这些变量。

模型服务启动时加载真实权重并执行预热，成功后 `/health` 才返回200和 `ready:true`。
启动中可能尚未监听端口，不能依赖“进程存在”判断就绪。失败不会报告 ready。
reranker 显式使用 Sigmoid 输出，并在健康信息中标记 `RERANKER_SIGMOID`。
生产 Compose 等待 embedding/reranker 健康；模型库版本已固定。

部署需重新构建后端、前端和推理镜像。此文不代表已在服务器部署；本机 CUDA
验证不能替代服务器 CPU 并发、内存、批处理延迟和冷启动验证。尤其跨视频最多60
候选可能需要根据实测调整超时。实验0.95阈值未作为生产默认值。

## 自检与真实验证范围

常规后端：`mvn -f server/pom.xml package`；前端：在 `client` 执行 `npm run build`。
推理就绪单测：`python inference/test_retrieval_readiness.py`（该单测使用替身，不是模型质量验证）。

真实模型启动且 Qdrant 可用后，从仓库根目录执行：

```text
mvn -f server/pom.xml -Dtest=ProductionRagRealModelsTest -Drag.production-real=true test
```

该测试使用原20/40分钟样本及每视频3个原始问题，在生产检索服务上检查：
48个1024维向量、真实 Qdrant、真实 Lucene BM25/OCR、真实 bge-reranker-v2-m3、
6个聊天证据包、2个视频分析证据包及缓存复用、2个跨视频结果，另检查 Qdrant
跨用户隔离和跨视频配额。所有正常结果必须没有降级。

真实 HTTP 精排请求为8次单查询、2次多查询批量。报告包含健康信息、命中、完整
聊天 EvidencePack、分析包及实际提示文本：
`server/target/rag-test-reports/production-integration.json`。

数据库/Checkpoint边界使用替身；查询使用原测试集预置的独立表达；不调用
改写、Planner、Executor、Critic、答案生成 LLM，也不重跑 ASR/OCR 推理。因此这
是“三个主业务入口的真实检索集成验证”，不是完整上传到回答的端到端验证，
不测回答幻觉率。测试用户983000及独立 contentHash 与原基线和业务用户隔离，
没有删除业务 Qdrant 点。Spring 组件装配另由 `ProductionRagWiringTest` 验证。

评估工具同时修复 Precision@K 和 nDCG@K 的短列表分母，历史报告不会被自动
追溯改写。原样本和标注未调整，也没有据这次接入重新宣称阈值已校准。

### 本次验收结果（2026-09-14）

- 最终执行 `mvn -Drag.production-real=true package`：134项，132通过，2项未开启的
  历史真实模型套件跳过，0失败、0错误，Spring Boot JAR 打包成功。
- 其中生产三入口真实检索集成测试、Spring真实组件装配、精排非法响应校验均通过。
- 前端生产构建通过（51模块）；推理就绪单测2项通过；生产 Compose 配置解析通过。
- `git diff --check` 通过。扩展集 manifest SHA256 仍为
  `7A55ABF354E0563068909CE1A77E6258BCED1D7F566A032F64BEDE4CB84A92E1`。
- 磁盘 Lucene 装配测试首次受沙箱临时目录权限限制；获准在沙箱外重跑原命令后通过，
  没有改用内存索引绕过该装配验证。

### Windows 一键启动修复（2026-09-14）

后端超时日志显示实际根因是 Redisson 无法连接 `localhost:6379`。启动脚本原先只按
端口判断 Redis，会将短暂或错误的6379监听当作 Redis 已就绪；而真正需要启动 Redis
时，Windows PowerShell 5.1 又不接受传给 `Start-Process` 的空 `ArgumentList`。

现改为 Redis 协议级 `PING=PONG` 检查，并在后端启动前复核一次；无参数服务会省略
`ArgumentList`。HTTP服务若由本轮启动且进程提前退出，会立即输出其标准日志和错误
日志末尾。embedding/reranker 冷启动等待为180秒，以覆盖真实权重加载和预热。

修复后实际执行 `scripts/start-all.ps1 -SkipFrontend`，MySQL、Redis、Kafka、MinIO、
Qdrant、embedding、ASR、OCR、reranker、backend 依次就绪；后端 `/health` 返回
overall=UP，MySQL/Redis/Kafka/MinIO/Qdrant 均为UP，Redis另行验证为PONG。后端日志显示
`Started VideoReaderApplication`，没有启动异常。验证启动的服务随后已清理，验证前已
运行的真实 BGE、reranker 和 Qdrant 保留。

### 视频追问实测修复（2026-09-14）

实测发现普通问答能正确使用低相关提示拒答，但有两项接口一致性问题：回答固定
`maxTokens=500` 会在长概述中截断；历史只保存 reranker 命中，而回答中的
`[证据N]` 对应合并后的 EvidencePack，页面重载后证据编号和时间可能错位。

现将答案预算调整为1200，并要求普通回答尽量在800个中文字符内完整收尾。
提示词明确：Top5候选不是视频全文，缺失内容只能表述为“当前证据不足”，不能断言
整段视频绝对未提及；总体概述也需声明依据当前召回片段。

`ChatEntry` 继续保留旧 `evidence` 精排命中，同时新增 `evidencePack` 保存回答实际使用的
合并原文区间。工作台和可信度 trace 优先展示 evidencePack，旧聊天记录缺少新字段时
回退到原 evidence，因而无需数据迁移。追问专项12项测试和前端51模块生产构建通过；
全量134项测试0失败、0错误（3项需显式开启的真实模型测试跳过）。释放运行中 JAR 的
Windows文件锁后，Spring Boot 可执行包重新生成并实际通过一键启动，后端健康组件全部UP。

### 追问窗口清空与撤销（2026-09-14）

工作台新增“撤销上一轮”和“清空”按钮，二者均通过确认弹窗后才执行。撤销以一轮
`user + assistant` 为单位；清空同时清除未发送的输入框文本。操作按当前 mediaId 和
当前登录用户隔离。

后台 `media-chat` 改为只追加的审计真源，仍由 `/analysis/chat-history` 提供给可信度
Trace；清空和撤销不会修改或删除它。工作台改读 `/analysis/chat-visible-history`，显示
状态单独保存在 `media-chat-view` checkpoint 中，仅记录隐藏的审计下标：

- `POST /analysis/chat-visible-history/undo` 隐藏最后一轮；
- `POST /analysis/chat-visible-history/clear` 隐藏当前全部轮次；
- 新问题仍追加到完整审计历史，但仅使用当前可见历史进行连续问题改写。因此已清空或
  已撤销的内容既不会重新出现在工作台，也不会继续影响上下文。

旧记录没有 `media-chat-view` 时默认全部可见，不需要数据库迁移。专项后端10项测试通过，
包含清空/撤销不删除审计历史、清空后新轮次可见和旧轮次不再进入可见上下文；前端51
模块生产构建和 Spring Boot 可执行 JAR 打包通过。

### 问答检索完整 Trace（2026-09-14）

可信度 Trace 的每轮 assistant 记录新增 `retrievalTrace` 审计快照。快照来自回答所用的
同一次真实检索，不会为了页面展示再次请求 BGE、Qdrant、Lucene 或 Reranker，包含：

- 连续追问的原始问题、独立问题、语义查询、BM25/OCR 查询和关键词；
- Dense Top25、BM25 Top25、OCR Top10 的全部实际命中、排名、原始通道分数和 Chunk 摘要；
- 加权 RRF 去重后的 Top10，包含三路排名、各路 `weight/(60+rank)` 贡献和总分；
- `bge-reranker-v2-m3` Top5 的 sigmoid 分数，或明确的 `FALLBACK_RRF/NOT_RUN` 状态；
- 当次 TopK、RRF 权重、`minRerankerScore`、低分过滤策略、耗时、降级状态和最终
  EvidencePack 的完整 ASR/OCR 原文。

Dense 相似度、Lucene BM25、RRF 和 Reranker sigmoid 是不同量纲，页面明确分列，禁止
横向比较。旧 `media-chat` JSON 没有该字段时仍可反序列化，页面只展示已有最终证据并提示
无法真实还原旧轮次的粗召回过程。
