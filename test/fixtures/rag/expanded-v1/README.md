# 20 × 20 分钟合成 RAG 回归集 v1

本数据是人工编写知识点、脚本确定性展开的**合成教学视频过程文件**，不是来自真实视频的 ASR/OCR。保留旧的 20/40 分钟样本，不覆盖原基线。

首次真实运行结果与失败样例见 [RESULTS-2026-09-14.md](RESULTS-2026-09-14.md)。

## 数据与边界

- 20 个不同主题，每个 20 分钟、60 条 ASR、120 帧 OCR；共 400 分钟、60 道问题。
- ASR 约 14.5 万字符，每分钟约 363 字，含停顿、语气词、口误、自我修正和跑题插话。
- OCR 约 29.9 万字符（包含重复画面文字），每帧混合课程内容、时间/播放器、桌面和消息等干扰。
- 每视频连续三题：前两题可回答，第三题不可回答；共 40 道可回答题、10 道同主题缺失细节题、10 道跨主题题。
- 每个主题分四节，章节顺序轮换，正例不只集中在视频开头。知识点在章节内反复演示，模板和干扰话术也有重复；这会使任务偏简单，不能把本数据成绩外推为真实视频成绩。
- 各题使用预先提供的 `standaloneQuery`，不调用生成式 LLM。因此测试连续问题的独立检索，**不测试历史消解/自动改写质量，也不测试回答生成**。

每个 `video-XX/` 目录：

| 文件 | 用途 |
|---|---|
| ingest-asr.json | 业务 `AsrSeg(startMs,endMs,text)` 格式 |
| ingest-ocr.json | 业务 `OcrFrame(timestampMs,texts,frameRef)` 格式；frameRef 是模拟对象名，并没有图片 |
| asr.txt / ocr.txt | 同源的可读带时间戳文本 |
| video-context.json | 由真实 `VideoContextBuilder.align` 生成的过程文件 |
| chunks-keywords.json | 由业务切块和 `ChunkEnricher` 抽取式分支生成的原文、摘要、关键词；无向量 |

`manifest.json` 只有视频元数据和问题，不含相关性标签。题目不写入转写、OCR或索引。
`evaluator/annotations.json` 独立保存 related/category/split/supportIntervals/reason；真实测试在全部 60 次检索完成后才读取它。
`evaluator/groundtruth-chunks.json` 是标注源区间投影到真实 chunk 的结果，不根据模型排名产生，也不强凑 Top7。无关题的 groundtruth 为空。

chunkId 使用生产 SHA-256 规则；contentHash 来自两份实际 ingest 文件字节，内容改变会改变 ID。当前每视频 16 块，共 320 块。源区间内内容在章节重复出现，包含该源片段的边界块也算相关；原始片段覆盖 >=60 秒为等级3、>=30秒为2、>0为1。该初始标注仍建议人工复核。

## 校准、指标与拒召回实验

按视频分组：奇数编号视频校准、偶数编号视频独立验证，各 10 视频 / 30 题；各有 20 正例、5 同主题负例、5 跑题负例。

从预先固定的阈值网格中，仅按校准集的平衡准确率选择 minScore；同分选较小阈值。冻结后应用于验证集。这里的模型分数要求是当前真实服务的 [0,1] 输出，不是 RRF 分数，也不是答案正确概率。

实验策略：逐条过滤真实 reranker Top5 里低于 minScore 的候选，若一条不剩，则 `evidenceStatus=LOW_RELEVANCE`，返回空 EvidencePack 和证据不足提示；否则 `CANDIDATE_EVIDENCE`，仍提示只能回答原文明示内容。**这是 test 下的实验策略，没有接入生产默认行为。**

报告包含：

- 正例 Dense@25、RRF@10、RRF@5、Rerank@5 的 Hit、Precision、Recall、MRR、nDCG；RRF与精排用相同 K=5 比较。
- 正例 reranker Hit@1；过滤后的 Recall@5。
- TP/FP/TN/FN、负例拒绝率、无关问题错误接受证据率、正例误拒率、两类负例分别的拒绝率。
- 接受证据中无标注支持 chunk 的比例（chunk口径，不是合并区间/文本字符口径）。没有接受证据时比例为 N/A。
- 查询全链路 P50/P95，不称作 reranker 单独耗时。
- 回答幻觉率：**NOT_MEASURED**。没有生成回答就不能测这个指标；错误接受证据率只是检索层风险代理指标。

Hit/Recall/MRR/nDCG 只在有 groundtruth 的正例上平均，避免把负例空真值误计为检索失败。Precision 使用现有指标实现的实际返回数作分母；未过滤 Top5 固定5条。负例使用拒绝指标，不记 Recall=0 后混入均值。

注意：16 块视频的 Dense Top25 基本等于全量候选，因此 Dense Recall@25 很容易满分；这套集更适合验证重排和拒召回，不足以证明大库粗召回能力。只有10道验证负例，每错一道就改变10个百分点，不能作为上线保证。

## 执行与隔离

在项目根目录运行（按本机安装设置 JAVA_HOME 与 Maven 路径）：

```powershell
python server/src/test/scripts/generate_expanded_rag.py
mvn -f server/pom.xml '-Dtest=ExpandedRagDatasetTest,ExpandedRagEvaluationTest' test
mvn -f server/pom.xml '-Dtest=ExpandedRagRealModelsTest' '-Drag.expanded-real=true' test
```

第三条必须有真实本地 BGE-M3(8000)、Qdrant(6333)、bge-reranker-v2-m3(8003)。普通测试不会自动启动模型或运行真实评测。模型先执行真实预热，再通过生产客户端检索；trace 子类仅记录 `super` 的真实结果，任一 Dense/精排降级会让测试失败。Lucene 使用内存目录，Checkpoint/LLM 边界隔离，不连接生产数据库，不调用回答大模型。

Qdrant 使用现有 `video-chunks` collection，但所有测试点均在专用 userId=982000 和每视频 contentHash 下；**不是独立 collection**。重跑相同样本幂等覆盖这320点，不清空业务 collection。样本修改会留下旧hash点（被新hash过滤排除）；如果要清理，只能按明确的测试用户/内容hash过滤，不要删整个 collection。服务启动会占用显存和本地端口，服务器部署不要自动执行此显式真实测试。

输出 `server/target/rag-test-reports/expanded-v1/`：

- status.txt：RUNNING/COMPLETE，读取汇总前确认本轮 COMPLETE，避免误读旧报告。
- provenance.json：运行时间、模型服务身份/设备、manifest哈希与测试边界。
- summary.json / summary.txt：校准及验证的 baseline/gated 对比；calibration-sweep.json：所有候选阈值。
- video-XX/*-trace.json：同一次业务调用的 Dense/BM25/OCR、RRF Top10、真实精排输入/Top5、chunk完整内容和原始 EvidencePack。
- video-XX/*-evaluation.json：标签、gold、预测状态、指标、过滤后 EvidencePack/提示词。
- video-XX/*-evidencepack.txt：过滤前后完整可读 EvidencePack。

重新生成仅改动该数据集目录；不要在人工编辑该目录后无确认重跑生成脚本。报告在 target 下会被 Maven clean 删除，可按需要归档真实运行结果。
