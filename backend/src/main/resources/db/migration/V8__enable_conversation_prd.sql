-- 同一 PRD 的会话编辑模式，旧内容在显式打开时迁移，不伪造需求确认。@author Andersen
ALTER TABLE version_lifecycle_state ADD COLUMN direct_prd BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE version_lifecycle_state ADD COLUMN proposals_json TEXT NOT NULL DEFAULT '[]';
