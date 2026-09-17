Video Reader RAG 过程数据 Fixtures

这两套目录不是自定义文本协议，而是项目 CheckpointService 写入
agent_checkpoint.payload 的原始 JSON 结构。

目录：
- 20min：模拟 mediaId=900020，视频时长 1200000ms。
- 40min：模拟 mediaId=900040，视频时长 2400000ms。

每套文件：
1. ingest-asr.json
   checkpoint_name=ingest-asr
   stage=ASR
   Java 类型=List<VideoContextBuilder.AsrSeg>
   字段=startMs, endMs, text

2. ingest-ocr.json
   checkpoint_name=ingest-ocr
   stage=OCR
   Java 类型=List<VideoContextBuilder.OcrFrame>
   字段=timestampMs, texts, frameRef

3. video-context.json
   checkpoint_name=video-context
   stage=READY
   Java 类型=VideoContext
   字段=source, userGoal, segments
   VideoSegment 字段=startMs, endMs, transcript, ocrTexts, evidenceFrames

固定黄金集：
- golden-set.json：20min/40min 各 3 个连续问题，每题人工排序 Top7 Chunk Ground Truth。
- RAG_TUNING.md：指标口径、可调参数、候选网格和推荐调优顺序。
- RagGoldenSetTest 会重建 Chunk 并验证全部 ID、时间范围、索引版本和样本数量。

使用方式：
- 测试 ASR/OCR 断点恢复：把前两个 JSON 分别作为对应 Checkpoint 的 payload，
  媒体保持待处理状态，再触发原处理消息。VideoContextService 会跳过推理，
  加载两份 Checkpoint，执行 align 后建立检索索引。
- 只测试 RAG：把 video-context.json 作为 video-context/READY 的 payload，
  然后触发 RetrievalIndexService，或通过检索接口触发懒索引。
- source 和 frameRef 中的 900020/900040 是测试占位 mediaId。导入已有媒体记录时，
  应替换为真实 mediaId。
- frameRef 使用真实项目格式 frames/{mediaId}/{timestampMs}.jpg。Fixture 不附带图片，
  因此测试证据图片展示时需要准备同名 MinIO 对象；纯文本检索不受影响。

数据规模：
- 20min：50 个非等长 ASR 段、114 个 OCR 帧，ASR 约 5930 字（约 296 字/分钟）。
- 40min：100 个非等长 ASR 段、228 个 OCR 帧，ASR 约 11699 字（约 292 字/分钟）。
- 两套 ASR 时间轴连续，OCR 时间均位于视频时长内。
- CACHE-SHIELD-X7 与 NET-TRACE-Z9 只出现在 OCR，用于验证 OCR-only 召回。
- ASR 保留“嗯、啊、呃”等停顿词、重复和半句重说，并混入术语同音误识别、
  字母缩写误识别、背景声以及短暂跑题内容，用于模拟未清洗的真实原始转写。
- OCR 包含主题文字、字幕片段、页码/进度、固定页脚和少量识别错字，
  用于验证 OCR 去重、低信息页脚干扰和 OCR-only 召回。
- ASR 不再固定每分钟一个整段：每分钟拆成 2～3 段，单段约 14～33 秒，
  语气词、纠正和跑题内容穿插在正文内部，而不是统一追加在段尾。
- OCR 约每分钟 5～6 帧，并混入系统更新、天气、电量、Wi-Fi、播放器控件、
  浏览器标签、会议聊天和快递等无关画面文字。

自动化业务流测试：
- 测试类：server/src/test/java/com/videoagent/service/retrieval/
  RagTwentyMinuteBusinessFlowTest.java
- 从 server 目录运行：
  mvn -Dtest=RagTwentyMinuteBusinessFlowTest test
- 测试会从 ingest-asr.json 和 ingest-ocr.json 开始，依次复用生产代码的时间轴对齐、
  90 秒/15 秒重叠分块、摘要与关键词、Dense/BM25/OCR、加权 RRF、Top5、
  连续问答证据合并以及 Agent EvidencePack。
- Lucene 使用内存 Directory；Checkpoint、Qdrant、Embedding、Reranker 仅替换外部
  I/O 边界，不连接 MySQL、Redis、Kafka、MinIO 或任何开发/生产服务。
- FixtureLoader 和业务流测试均位于 server/src/test，过程文件位于 server 之外，
  不会进入生产 Spring Boot JAR。
- 业务流测试包含三轮连续视频追问：
  1. “缓存穿透怎么处理？”
  2. “那它和缓存击穿有什么区别？”
  3. “如果是大量键一起过期呢？”
  三轮会持久化模拟历史、消解“它”等指代、分别回读穿透/击穿/雪崩原文，
  并断言该直接问答流程没有进入 AgentLoop。
- 40 分钟三轮无 LLM 检索审计测试：
  server/src/test/java/com/videoagent/service/retrieval/RagFortyMinuteRetrievalTraceTest.java
  报告依次记录 Dense Top25、BM25 Top25、OCR Top10、加权 RRF Top10、
  Reranker Top5 和最终完整 EvidencePack，默认输出到：
  server/target/rag-test-reports/40min-three-round-retrieval-trace.txt

真实模型与真实 Qdrant 全流程测试：
- 测试类：server/src/test/java/com/videoagent/service/retrieval/
  RagRealModelsEndToEndTest.java
- 前置服务：BGE-M3 http://127.0.0.1:8000、Qdrant http://127.0.0.1:6333、
  bge-reranker-v2-m3 http://127.0.0.1:8003。
- 运行命令（PowerShell 中必须给 -D 参数加引号）：
  mvn '-Drag.real-models=true' '-Dtest=RagRealModelsEndToEndTest' test
- 真实测试复用生产 RetrievalIndexService、QdrantVectorStore、Bm25IndexService、
  WeightedRrfFusion、RerankerClient、VideoEvidenceRetrievalService 和
  ChatEvidenceService。Embedding、Qdrant、BM25/OCR、RRF、Reranker 均不使用 Mock。
- Checkpoint 与 LLM 保持测试隔离：Checkpoint 只缓存本轮已生成 Chunk；固定黄金集直接
  提供 standaloneQuery，不做生成式改写，也不生成最终自然语言答案。
- 完整报告输出：
  server/target/rag-test-reports/real-models-six-query-full-flow.txt
- 未显式设置 rag.real-models=true 时该测试跳过，避免普通单元测试在没有模型服务时
  伪装成真实模型测试。
