/**
 * agent —— Agent 核心循环（推理层）⭐。
 *
 * <p>职责：Planner → Executor → Critic 多角色协作（≤2 轮），四模式路由
 * （GENERAL / LEARNING / REVIEW / CREATION），执行预算与 Checkpoint 断点恢复（阶段四实现）。
 */
package com.videoagent.service.agent;
