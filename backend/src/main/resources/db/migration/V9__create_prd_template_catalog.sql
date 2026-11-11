-- 前置 V1-V8；仅在当前 Alpha schema 增加自定义 PRD 模板草稿与不可变发布历史。
-- 可重复执行：仅创建缺失表，不覆盖既有模板、PRD 正文或历史。@author Andersen
CREATE TABLE IF NOT EXISTS prd_templates (
    id VARCHAR(80) PRIMARY KEY,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    latest_published_revision INTEGER NOT NULL CHECK (latest_published_revision >= 0),
    draft_json TEXT,
    copy_request_id VARCHAR(64) NOT NULL UNIQUE,
    copy_command_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS prd_template_versions (
    id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(80) NOT NULL REFERENCES prd_templates(id),
    revision INTEGER NOT NULL CHECK (revision >= 1),
    definition_json TEXT NOT NULL,
    publish_request_id VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision >= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (template_id, revision),
    UNIQUE (template_id, publish_request_id)
);
