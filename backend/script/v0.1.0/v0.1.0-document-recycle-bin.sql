-- 前置 V11；区分单独删除文档与随会话回收，重复执行不重置删除状态。@author Andersen
ALTER TABLE requirement_versions ADD COLUMN IF NOT EXISTS independently_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_requirement_versions_deleted ON requirement_versions (deleted_at, id);
