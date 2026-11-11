-- 前置 V10；会话及其独立文档可恢复删除，不改写正文、历史和定稿。@author Andersen
ALTER TABLE work_sessions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE requirement_versions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_work_sessions_deleted ON work_sessions (deleted_at, id);
