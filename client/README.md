# client — Vue 3 工作台

Video Agent 前端：登录 → 分片上传 → 提交分析（目标+模式）→ SSE 阶段进度 → 结果/证据/可信度展示 → 证据检索 → 评估面板 + 👍/👎 反馈。

## 启动（需在你自己的终端运行）

```bash
cd client   # 项目根目录下的 client/
npm install --registry https://registry.npmmirror.com
npm run dev        # http://localhost:5173
```

> 注意：Vite 依赖 esbuild（需启动子进程），在自动化沙箱环境中无法运行，请在普通终端执行。
> 开发服务器通过 `/api` 代理后端 `http://localhost:8081`（无需额外 CORS 配置）。

## 使用流程

1. 登录/注册（JWT 存 localStorage）
2. ① 上传：选择视频 → 分片上传（8MB/片，断点续传）→ 提交分析（目标 + 模式）
3. SSE 实时显示阶段进度（SUBMITTED→INGEST→AGENT_*→COMPLETED）
4. ② 结果与可信度：结论 + 逐字时间戳证据 + 模式段落 + **验证报告**（支持率/幻觉率/L1-L3 判定）+ 👍/👎
5. ③ 证据检索：混合检索（语义+关键词+画面文字），展示来源（QDRANT/LOCAL_COSINE）
6. ④ 评估面板：多维指标 + 可观测 trace（阶段耗时/LLM 调用/Token/成本）

## 构建

```bash
npm run build      # 产物在 client/dist/
```
