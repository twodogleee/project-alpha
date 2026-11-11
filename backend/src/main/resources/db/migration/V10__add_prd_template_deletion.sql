-- 前置 V9；仅为当前 Alpha schema 的自定义 PRD 模板增加可恢复删除状态。
-- 可重复执行：只补缺失列，不改变既有删除状态、草稿、发布历史或 PRD 引用。@author Andersen
ALTER TABLE prd_templates ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;
