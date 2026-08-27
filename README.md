# Video Agent（可信长视频理解 Agent）

把长视频转化为**可检索、可追溯、可验证、可继续追问**的结构化知识——以「证据可信」为差异化核心。

> 技术栈：Java 21 · Spring Boot 3 · LangChain4j · Kafka · Vue 3（见 `docs/` 与根目录方案文档 `../video-agent-rebuild-plan.md`）

## 目录结构

```text
video_reader/
├── server/               # Spring Boot 后端（五关注点分包）
│   └── src/main/java/com/videoagent/
│       ├── controller/   # REST + SSE
│       ├── service/
│       │   ├── ingest/   # 视频预处理 (FFmpeg/ASR/OCR)          [阶段二]
│       │   ├── retrieval/# 分块 + 混合检索                      [阶段三]
│       │   ├── agent/    # Planner/Executor/Critic + 模式路由   [阶段四]
│       │   ├── trust/    # 证据验证三层 ★                      [阶段五]
│       │   ├── eval/     # 评估与可观测                        [阶段六]
│       │   └── auth/     # 用户鉴权 + 配额限流                  [已完成]
│       ├── consumer/     # Kafka 消费者                        [阶段二/四]
│       ├── dto/          # 领域模型 (record)
│       ├── entity/       # 数据库实体
│       ├── repository/   # Checkpoint / Media / 失败任务
│       ├── config/       # 线程池 / MinIO / Kafka / Redis 配置
│       └── utils/        # LLM / Embedding / ASR / OCR 封装
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/ # Flyway 迁移脚本
├── client/               # Vue 3 工作台（占位，阶段六实现）
├── inference/            # 本地推理服务 faster-whisper / PaddleOCR（占位，阶段二实现）
├── eval/                 # 离线评估黄金任务集（占位，阶段六实现）
├── docker-compose.yml    # 中间件编排（Kafka/MySQL/MinIO/Qdrant/Redis）
└── docs/                 # 架构设计文档
```

## 当前进度：阶段一 ✅

- Spring Boot 3 工程骨架，五关注点分包 + Flyway 迁移（users / media_files / agent_checkpoint / failed_analysis_task）
- JWT 多用户鉴权：注册 / 登录 / BCrypt / AuthInterceptor / userId 数据隔离
- 中间件接入：Kafka（topic + AdminClient）/ Redisson（限流）/ MinIO（桶）/ Qdrant / MySQL
- `GET /health` 免鉴权健康检查（逐组件探测，优雅降级）
- docker-compose.yml 一键起中间件
- 单元测试：JwtService / AuthService 全部通过

## 当前进度：阶段一 ✅ 阶段二 ✅ 阶段三 ✅ 阶段四 ✅ 阶段五 ✅ 阶段六 ✅

**阶段二（视频预处理 + 本地推理服务）**：
- MinIO 分片上传 + 断点续传 + 内容级去重；FFmpeg 切片/关键帧/phash 去重
- 本地推理：ASR（Qwen3-ASR-0.6B GPU）+ OCR（RapidOCR PP-OCRv4）+ Embedding（BGE-M3）
- ASR/OCR 双分支并行容错 → 带时间戳 VideoContext；Kafka 异步 + 死信收敛 + 分支级 Checkpoint

**阶段三（长视频检索）**：
- 5 分钟知识块 + LLM 摘要 + BGE-M3 → Qdrant（contentHash 派生点 ID：内容级共享 + 幂等）
- 混合检索（语义×0.6+关键词×0.25+画面×0.15）+ 意图改写；Qdrant 关闭降级本地余弦（DoD 验证通过）

**阶段四（Agent 核心循环 ⭐）**：
- Planner → Executor → Critic（≤2 轮）+ 反馈驱动定向补检索；执行预算 + 超限保留警告
- 四模式路由（GENERAL/LEARNING/REVIEW/CREATION）+ ModeProfile 扩展点
- 自研 DeepSeek 客户端（thinking 关闭，completion token 降 ~85%）；目标级 Checkpoint 断点恢复

**阶段五（可信证据验证 ⭐⭐ 核心差异化）**：
- **L1 原文保真**：evidence 逐字命中 ASR/OCR 原文（引用非编造）——Executor 强制逐字引用，改写即判 UNSUPPORTED
- **L2 语义蕴含**：embedding 相似度闸门（省 LLM 调用）+ 独立 LLM 蕴含判定 + 降级矩阵（LLM 失败回退闸门 / 双失败 UNVERIFIABLE 安全放行）
- **L3 独立复核**：独立角色复核 Critic 判定与报告，阻断自评偏差
- 量化指标：semanticSupportRate / hallucinationRate（分母仅含可判定结论）；UNSUPPORTED 强制 Critic 判不通过并触发补证据
- 验证为增强能力绝不阻断主链路；`GET /analysis/verification` 查询报告
- 端到端验证：18 分钟视频产出 4 结论（2 SUPPORTED / 2 UNSUPPORTED 诚实标记），L1 捕获改写引用、L2 细粒度蕴含判定、L3 独立标记存疑
- **Agent 循环质量修复（v6.1）**：
  - v6 证据绑定改为**确定性抽取**（结论 ↔ 证据包原文最长公共子串锚点 + 窗口抽取，取代 LLM 自报证据）→ evidence 必为原文子串（L1 必过）、时间戳自动取证据块 startMs、无原文锚点的结论自动不带证据；Critic 反馈带上证据内容并放宽覆盖判定（允许任务合并，避免计划 5 任务 vs 结论上限导致的必然失败）；Executor 提示词强化反编造（如"802.11n对应WiFi4"这类原文没有的细节明确禁止，conclusions ≤5）
  - v6.1 **证据包完整性修复**（换新视频全 0 支撑的根因）：① 任务项转写上限 400→2000（取完整块而非头部——此前 5 分钟块的"百度网盘/服务器长期运行/10MB/s"等细节全被头部截断，Executor 只能看到泛泛开头 → 绑错证据/被迫编造）；② 定向补检索（Critic requiredTimestamps）改为**始终追加**进包（旧代码任务项占满 8 个时提前 return，被点名的时间戳 600000/900000 永远缺席 → 轮次空转）；③ AgentLoop 过滤「视频中不存在」的定向时间戳，防 Critic 每轮重复请求；④ Executor 规则 6：批评家要求补充的具体数值/实例必须确实在证据片段中才可写，禁止凭空补数
  - 新增 `ExecutorEvidenceBindTest`（4 用例）+ `EvidencePackServiceTest`（3 用例），全量 51/51 通过
  - v6.2 **内容级复用用户隔离修复**：复用源查找（上传 complete + ingest tryReuse 两处）改为**按 userId 限定**——不同用户的媒体记录与用户数据相互独立；`copyCheckpointsFrom` 改为**白名单复制**（仅 video-context / ingest-asr / ingest-ocr），聊天（media-chat）、分析结果（goal-*）、时间线（process-timeline）等用户级 checkpoint 绝不跨记录复制（同用户重传时分析也基于当前代码重新生成，不沿用旧结果）；新增 `MediaServiceReuseTest`（3 用例：同用户只复制内容级 / 跨用户不复用 / 查找限定调用用户），全量 54/54 通过
  - v6.3 **证据绑定质量修复**（两个视频 60% 幻觉率的根因）：① 修复 `lcsSpan` **b 侧区间错用**——此前把结论串的匹配区间套用到证据块串上，导致证据窗口定位错位（落在块开头而非真实论述处，即"C/S 地址知晓结论的证据只显示开场介绍"的根因）与锚点文本提取错误；② **多证据绑定**（≤3 条/结论，跨块结论如"去中心化@300000 + 单点容错@600000"逐块绑定）；③ **锚点有效性规则**（含汉字即有效；纯 ASCII 需 ≥5 字符且以字母/数字结尾，挡掉 "802."/"，802." 伪锚点）；④ 绑定**优先转写、回退画面文字**（防 OCR 开场白污染）；⑤ 证据窗口 ±80→±120；⑥ L2 蕴含闸门 0.55→0.50（闸门只拦明显无关，细粒度判定交给 LLM）；新增窗口定位回归测试，全量 56/56 通过
  - v6.4 **秒级时间戳 + 同块多锚点 + Critic 引导**：① `RetrievalIndexService` 重建分块时**保留 rawSegments**（此前被丢弃），证据绑定按锚点所在 **ASR 片段回精确 startMs**（块级 0/300000 → 秒级 192000/222000）；② ASCII 标识锚点阈值 6→5 且以字母/数字结尾（"802.5"/"802.8" 可作锚点，堵住 "，802." 伪锚点），**数字↔字母切换断词**（"802.8FDDI"→"802.8"+"FDDI"）；③ **同块多位置锚点**（结论含 "802.5" 与 "802.8" 两个相距较远的标识时各绑一条证据）；④ Critic 输出要求改为**可执行的下一步指令**（指明时间戳区域/检索关键词/改写动作，避免空泛"证据不足"）；新增 2 用例，全量 58/58 通过

**阶段六（评估与可观测 + 前端）**：
- 多维评估指标：structuredValid / claimEvidenceSupportRate / evidenceSupportRate(L1) / semanticSupportRate / hallucinationRate / contextPrecision / userAcceptanceRate（👍/👎，实时重算）
- Agent 可观测 trace：各阶段耗时 / LLM 调用次数 / Token 估算 / 成本估算（实测一轮分析 12 次 LLM、≈5400 tokens、≈0.011 元）
- 离线黄金任务集回归（`eval/golden-tasks.json` + `GoldenSetEvaluator`：关键词覆盖三重判定）
- Vue 3 工作台 `client/`：登录 → 分片上传 → 分析（目标+模式）→ SSE 阶段进度 → 结果/证据/可信度 → 证据检索 → 评估面板；`GET /analysis/evaluation` + `POST /analysis/feedback`；后端 CORS
- 前端重构（三层架构）：独立登录/注册页 → 全局框架（顶部导航：视频库/知识库/设置 + 主题切换 + 上传 + 用户名/退出；左侧边栏：标题搜索 + 状态/来源过滤统计计数）→ 内容视图（视频库列表页、解析工作台、知识库全局搜索、个人设置）
  - 解析工作台：左列播放器（fetch+blob 鉴权流式播放，时间戳标签点击跳转画面）+ 连续追问；右列顶部完整 ASR 转写记录（点击定位画面）+ 标题/核心结论/建议分层展示 + 逐结论时间戳证据；返回主页 + 删除该记录（二次确认）
  - 视频库：行记录带勾选框 + 右上角全选/批量删除（二次确认）；已完成记录展示完整处理时间与各步骤耗时（ASR/OCR/LLM 摘要/Agent 分析，来自「process-timeline」Checkpoint）；处理中记录显示进度条（按阶段估算）
  - **视频标题**：LLM 依据最新分析结果自动生成简短标题（≤15 汉字，分析完成后自动写入，用户改过则不覆盖）；视频库列表与解析工作台均展示同一标题（同一字段自动同步），支持点击修改（回车保存/Esc 取消）与「✨自动生成标题」按钮；接口 `PUT /media/{id}/title`、`POST /media/{id}/title/auto`
  - 视频库标题展示：标题与文件名同大小同颜色（加粗主标签），有标题时文件名默认收起、可「⏵ 展开文件名」查看完整原名；侧边栏标题搜索**同时匹配视频标题与文件名**
  - 新增 `GET /analysis/global-search`（跨项目检索，无 LLM 改写逐项目合并按分数排序）；`POST /media/batch-delete`（删除视频文件/帧/Checkpoint/反馈等全部关联数据）
- 端到端验证：评估指标/trace/反馈全链路 API 可用；10 个 SFC 全部通过编译校验；stream/global-search/batch-delete/标题自动生成+修改 实测通过（dev 服务需在普通终端运行）

### 性能优化（预处理管线）

- **ASR 双引擎**：本地（Qwen3-ASR-0.6B，默认，GPU 离线）或**在线（科大讯飞极速录音转写 speedTranscription，HTTP 异步任务式）**——`inference/asr/app.py` 支持 `engine=local|xfyun`（表单字段，缺省取 `ASR_ENGINE`）；讯飞凭据**仅经环境变量 `XF_APPID`/`XF_APIKEY`/`XF_APISECRET` 或用户在个人设置提交**（AES-GCM 加密落库，仓库不存任何密钥）；个人设置页可切换引擎、维护在线剩余时长、提交自己的讯飞凭据（接口 `GET/PUT /settings/asr` + `GET/POST /user/asr-config`）
- **ASR**：音频切片 60s→300s（与检索分块粒度对齐，16 分钟视频调用次数 17→4）+ **4 路并行转写**；**单进程**（`ASR_WORKERS=1`，GPU 吞吐是瓶颈、多 worker 无收益且每实例常驻 ~2.5GB 显存）+ `max_inference_batch_size=2`；实测热态 60s 音频 ≈ 7.8s、300s ≈ 34-37s（0.11 倍实时）
- **OCR**：逐帧识别 **4 路并行 + 单帧容错**；装 `onnxruntime-gpu` 后自动启用 CUDA；`OCR_WORKERS=1`
- **Embedding**：默认 **CPU**（`EMBEDDING_DEVICE=cpu`）——把 ~1.3GB 显存让给 ASR；显存充足可改 `cuda`
- **资源自动释放（看门狗）**：三个推理服务内置后端健康探测（每 10s），后端失联超过 60s 自动退出释放 GPU/CPU（`WATCH_BACKEND=0` 关闭，`BACKEND_URL`/`WATCH_BACKEND_TIMEOUT` 可调）；配合 stop-all 的按端口+进程名清理，杜绝孤儿进程占显存（实测修复前 7.7GB → 修复后 ~2.5GB）
- **显存红线**：8GB 卡处理中显存应保持 ~6GB（2×ASR + 1×OCR + 桌面 ≈ 6GB）；超过会触发 WDDM 共享内存换页抖动，速度暴跌（实测 3+3 worker 组合 ~11GB → ASR 每片从 34s 恶化到 66s）
- **GPU 吞吐上限（实测）**：RTX 4060 Laptop（功耗墙 ~40-80W）跑 Qwen3-ASR-0.6B 约 **8-9 倍实时**（300s 音频 34-37s）。16m38s 视频 ASR ≈ **160-165s** 已是硬件极限，worker 并行只能省调用开销（~15-20s）；worker 数超过 GPU 可承受范围反而因显存抖动变慢
- **提速方向**：换 faster-whisper（CTranslate2 int8，GPU 吞吐 2-3 倍、原生分段时间戳）或服务器大 GPU
- **索引摘要**：分块摘要与 Embedding **4 路并行**（原串行）；推理服务懒加载加**线程锁**（修复并发首请求竞态导致的服务 500）
- **计时修正**：ASR/OCR 分支各自独立计时（原先 join 顺序导致 OCR 时长被污染显示成 ≈ASR 时长）；「总耗时」改为**真实墙钟**（并行步骤不重复计入）；Agent 步骤仅在真实执行时覆盖（Kafka 重投缓存命中不把耗时冲成 0）
- **进度展示**：`MediaDto` 新增 `stage` 字段，视频库进度条与解析工作台横幅实时显示当前步骤（语音转写/画面识别/Agent 分析等）；工作台处理中即显示播放器与各区域，已完成区域渐进式填充
- 实测：16m38s 视频墙钟 ≈ **3 分 22 秒**（ASR 163s 为 GPU 吞吐上限 + 索引 11s + Agent 28s），OCR 40.9s（CUDA）并行被 ASR 掩盖

## 本地启动

### 0. 一键启动（推荐）

```powershell
# 启动 中间件 + 推理服务 + 后端(8081) + 前端(5173)
scripts\start-all.cmd
# 或跳过前端（前端另开终端 cd client; npm run dev）：
scripts\start-all.cmd -SkipFrontend
```

**密钥配置**：本仓库**不含任何真实密钥**。请复制项目根目录 `.env.example` 为 `.env` 填入自己的密钥（`.env` 已被 gitignore 忽略）；`start-all.ps1` 会自动加载 `.env`，同名环境变量优先。LLM Key 也可由每个用户在**前端「个人设置」页提交自己的 Key**（AES-GCM 加密落库，仅显示脱敏值）。

脚本按顺序启动 MySQL(3307) → Redis(6379) → Kafka(9092) → MinIO(9000) → Qdrant(6333) → embedding(8000)/asr(8001)/ocr(8002) → 后端(8081) → Vite(5173)，每个服务等待就绪后才继续；**按 Ctrl+C 自动停止本次启动的全部服务**（按端口兜底清理，如 mysqld 换 PID 存活的情况）。日志目录见 `scripts/start-all.ps1` 顶部（默认项目同级 `.tools/logs/`）。

### 0.1 一键停止

```powershell
scripts\stop-all.cmd
# 或：powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
```

按端口停止全部后台服务（后端/MySQL/Redis/Kafka/MinIO/Qdrant/推理服务/前端），并兜底清理本项目环境进程（按可执行文件路径精确匹配 python，不误杀其他程序）。系统级 `redis-server` 若无权限会提示，需管理员执行 `taskkill /PID <pid> /F`（通常可忽略，不影响启动脚本——脚本会自动跳过已占用端口）。

### 1. 中间件

首选 Docker（需 WSL2）：

```bash
docker compose up -d
```

无 Docker 环境（Windows 本机演示）可原生启动，见 `docs/local-middleware.md`。

### 2. 后端

```bash
cd server
mvn spring-boot:run
# 环境变量按需覆盖：MYSQL_URL / JWT_SECRET / LLM_API_KEY / EMBEDDING_API_KEY ...
```

### 3. 前端

```bash
cd client
npm install --registry https://registry.npmmirror.com
npm run dev          # http://localhost:5173（需在普通终端运行；沙箱内 esbuild 无法 spawn）
```

### 4. 验收

```bash
curl http://localhost:8081/health
# 注册
curl -X POST http://localhost:8081/user/register -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123","nickname":"Alice"}'
# 登录拿 token → 访问鉴权接口 GET /user/me
```

## AI 服务接入（真实服务，环境变量注入）

| 能力 | 服务 | 配置 |
|:---|:---|:---|
| LLM | DeepSeek（OpenAI 兼容，`deepseek-v4-flash`） | `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` |
| Embedding | BGE-M3（OpenAI 兼容 /embeddings，1024 维） | `EMBEDDING_BASE_URL`（本地服务，无鉴权） |
| ASR | 本地 Qwen3-ASR-0.6B 推理服务 | `ASR_BASE_URL` / `ASR_MODEL_PATH` |
| OCR | 本地 RapidOCR（PP-OCRv4）推理服务 | `OCR_BASE_URL` |

### 密钥安全与按用户接入（阶段三.5 / 七）

- **仓库零密钥**：所有真实密钥（LLM / 讯飞 / 中间件口令）仅经环境变量或 `.env`（gitignore）注入，仓库与代码中不落明文；`start-all.ps1` 不内嵌任何账号，缺失时仅告警。
- **服务端默认 Key 仅经环境变量注入**（`LLM_API_KEY` / `XF_APPID`/`XF_APIKEY`/`XF_APISECRET`），不写入任何配置文件/仓库。
- **用户自带密钥（推荐）**：前端「个人设置」页提交 —— `POST /user/llm-config`（LLM Key）与 `POST /user/asr-config`（讯飞三件套），**AES-GCM 加密后落库**（密文，非明文），查询接口仅返回脱敏值；`LlmProvider` 按用户解析（用户 Key 优先，服务端兜底），ASR 转写链路同样按用户解密后透传（仅内存短暂持有），未配置回退服务端账号。
- **静态加密主密钥**：环境变量 `LLM_MASTER_KEY`（生产必设；未配置时开发回退派生自 JWT secret）。生成：`openssl rand -base64 32`；更换后旧密文无法解密（用户需重新提交）。
- **安全基线**：HTTPS 部署（生产必配 TLS，防密钥与对话明文被窃听）；密钥不进日志（代码统一不打印）；数据库/Redis 口令不写默认弱口令；`LLM_MASTER_KEY` 与 `JWT_SECRET` 服务器侧生成、仅环境变量注入。
- **成本护栏（省 token）**：`temperature=0` 确定输出 + `maxTokens=300` 上限 + 精简提示词；索引按内容级幂等，同一视频只生成一次摘要。
- **成本护栏（限流）**：Redisson 令牌桶两级限流（用户级默认 2 次/秒 + 全局级默认 3 次/秒，`RATE_LIMIT_USER_RPS`/`RATE_LIMIT_GLOBAL_RPS` 可覆盖），以「一次 LLM 消费动作」为单位——**提交分析 / 视频追问 / 自动标题共用同一额度**，入口超限返回 429，防止异常流量快速耗尽 LLM 调用额度。
