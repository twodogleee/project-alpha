-- 统一工作空间复用持久化任务，新增定向编辑种类；保留旧任务、消息及全部历史。
-- Flyway 按 V6 后执行一次，仅扩展约束，不改写任何业务内容。@author Andersen
ALTER TABLE workspace_runs DROP CONSTRAINT workspace_runs_kind_check;
ALTER TABLE workspace_runs ADD CONSTRAINT workspace_runs_kind_check CHECK (kind IN ('CHAT','DRAFT','WORKBENCH'));
