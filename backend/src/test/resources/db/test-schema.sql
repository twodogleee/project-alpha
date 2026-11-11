-- 快速测试专用：JSON 用 TEXT，真实 JSONB 行为由 postgresTest 验证。
CREATE TABLE IF NOT EXISTS project_documents (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS project_versions (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES project_documents(id),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document_json TEXT NOT NULL,
    UNIQUE (project_id, revision)
);
CREATE TABLE IF NOT EXISTS model_configuration (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    mode VARCHAR(20) NOT NULL,
    platform VARCHAR(30) NOT NULL,
    model VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key_ciphertext TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 目录快速测试结构，正式 PostgreSQL 迁移单独验收。
CREATE TABLE IF NOT EXISTS model_platforms (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    protocol VARCHAR(30) NOT NULL CHECK (protocol IN ('dashscope', 'openai-compatible')),
    base_url VARCHAR(500) NOT NULL,
    model_catalog_url VARCHAR(500),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS model_profiles (
    id VARCHAR(36) PRIMARY KEY,
    platform_id VARCHAR(36) NOT NULL REFERENCES model_platforms(id),
    name VARCHAR(120) NOT NULL,
    model VARCHAR(120) NOT NULL,
    api_key_ciphertext TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (platform_id, name)
);
CREATE TABLE IF NOT EXISTS model_runtime_selection (
    id BIGINT PRIMARY KEY CHECK (id=1),
    revision BIGINT NOT NULL CHECK (revision>=1),
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('demo','ai')),
    active_model_id VARCHAR(36) REFERENCES model_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (mode='demo' OR active_model_id IS NOT NULL)
);
INSERT INTO model_platforms (id,name,protocol,base_url,updated_at)
SELECT 'platform-dashscope','阿里云百炼','dashscope','https://dashscope.aliyuncs.com',CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM model_platforms WHERE id='platform-dashscope');
INSERT INTO model_profiles (id,platform_id,name,model,updated_at)
SELECT 'model-qwen-plus','platform-dashscope','通义千问 Plus','qwen-plus',CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM model_profiles WHERE id='model-qwen-plus');
INSERT INTO model_runtime_selection (id,revision,mode,active_model_id,updated_at)
SELECT 1,1,'demo',NULL,CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM model_runtime_selection WHERE id=1);

-- 单人桌面工作空间：独立项目、会话与交付版本；不导入旧评审历史。@author Andersen
CREATE TABLE IF NOT EXISTS workspace_projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    background TEXT NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    CHECK (revision >= 1)
);
CREATE TABLE IF NOT EXISTS work_sessions (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36),
    title VARCHAR(120),
    draft_text TEXT NOT NULL,
    draft_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    message_sequence BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (project_id) REFERENCES workspace_projects(id),
    UNIQUE (id, project_id),
    CHECK (revision >= 1 AND draft_revision >= 0 AND message_sequence >= 0)
);
CREATE TABLE IF NOT EXISTS session_messages (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    role VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    mode VARCHAR(30),
    model VARCHAR(120),
    run_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id) REFERENCES work_sessions(id),
    UNIQUE (session_id, sequence),
    CHECK (role IN ('USER','ASSISTANT'))
);
CREATE TABLE IF NOT EXISTS workspace_runs (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36),
    version_revision BIGINT,
    request_id VARCHAR(64) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    input_text TEXT NOT NULL,
    context_text TEXT NOT NULL,
    draft_revision BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    mode VARCHAR(30),
    model VARCHAR(120),
    result_text TEXT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id, project_id) REFERENCES work_sessions(id, project_id),
    UNIQUE (session_id, request_id),
    CHECK (kind IN ('CHAT','DRAFT','WORKBENCH','PROTOTYPE')),
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED'))
);
CREATE TABLE IF NOT EXISTS session_draft_revisions (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    source_run_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id) REFERENCES work_sessions(id),
    UNIQUE (session_id, revision)
);
CREATE TABLE IF NOT EXISTS requirement_versions (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    label VARCHAR(120) NOT NULL,
    goal TEXT NOT NULL,
    content TEXT NOT NULL,
    revision BIGINT NOT NULL,
    stage VARCHAR(30) NOT NULL,
    lifecycle VARCHAR(30) NOT NULL,
    source_session_id VARCHAR(36),
    source_draft_revision BIGINT,
    cancel_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (project_id) REFERENCES workspace_projects(id),
    FOREIGN KEY (source_session_id, project_id) REFERENCES work_sessions(id, project_id),
    UNIQUE (id, project_id),
    UNIQUE (project_id, label),
    UNIQUE (source_session_id, source_draft_revision),
    CHECK (stage IN ('REVIEW','PRD','PROTOTYPE')),
    CHECK (lifecycle IN ('ACTIVE','CANCELED'))
);
CREATE TABLE IF NOT EXISTS requirement_version_revisions (
    id VARCHAR(36) PRIMARY KEY,
    version_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    label VARCHAR(120) NOT NULL,
    goal TEXT NOT NULL,
    content TEXT NOT NULL,
    source_session_id VARCHAR(36),
    source_draft_revision BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (version_id) REFERENCES requirement_versions(id),
    UNIQUE (version_id, revision)
);
ALTER TABLE work_sessions ADD CONSTRAINT IF NOT EXISTS fk_session_version_project FOREIGN KEY (version_id, project_id) REFERENCES requirement_versions(id, project_id);
CREATE INDEX IF NOT EXISTS idx_workspace_projects_updated ON workspace_projects (updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_work_sessions_updated ON work_sessions (updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_requirement_versions_updated ON requirement_versions (updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_workspace_runs_session ON workspace_runs (session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS version_lifecycle_state (
 direct_prd BOOLEAN NOT NULL DEFAULT FALSE, proposals_json TEXT NOT NULL DEFAULT '[]',
 version_id VARCHAR(36) PRIMARY KEY REFERENCES requirement_versions(id),
 revision BIGINT NOT NULL, review_revision BIGINT NOT NULL, prd_revision BIGINT NOT NULL,
 scope_fingerprint VARCHAR(64) NOT NULL, review_json TEXT NOT NULL, prd_json TEXT,
 review_baseline_id VARCHAR(36), prd_baseline_id VARCHAR(36), prd_source_baseline_id VARCHAR(36),
 prd_mode VARCHAR(30), prd_model VARCHAR(120), updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK (revision >= 1 AND review_revision >= 1 AND prd_revision >= 0)
);
CREATE TABLE IF NOT EXISTS version_artifact_baselines (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('REVIEW','PRD')), source_revision BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, content_json TEXT NOT NULL, source_baseline_id VARCHAR(36),
 scope_fingerprint VARCHAR(64) NOT NULL, label VARCHAR(120) NOT NULL, goal TEXT NOT NULL, scope TEXT NOT NULL,
 mode VARCHAR(30) NOT NULL, model VARCHAR(120), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (id,version_id), UNIQUE (version_id,request_id),
 FOREIGN KEY (source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
CREATE TABLE IF NOT EXISTS version_artifact_revisions (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('REVIEW','PRD','EVENT')), revision BIGINT NOT NULL,
 content_json TEXT NOT NULL, source_baseline_id VARCHAR(36), scope_fingerprint VARCHAR(64) NOT NULL,
 mode VARCHAR(30), model VARCHAR(120), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (version_id,kind,revision),
 FOREIGN KEY (source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
CREATE TABLE IF NOT EXISTS version_prd_runs (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 baseline_id VARCHAR(36) NOT NULL, prd_revision BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
 mode VARCHAR(30), model VARCHAR(120), result_json TEXT, error_message TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (version_id,request_id),
 FOREIGN KEY (baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT IF NOT EXISTS fk_current_review_baseline FOREIGN KEY (review_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT IF NOT EXISTS fk_current_prd_baseline FOREIGN KEY (prd_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT IF NOT EXISTS fk_prd_review_source FOREIGN KEY (prd_source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
CREATE INDEX IF NOT EXISTS idx_artifact_history ON version_artifact_revisions(version_id,kind,revision DESC);
CREATE INDEX IF NOT EXISTS idx_prd_runs_version ON version_prd_runs(version_id,created_at DESC);
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
-- 前置 V9；仅为当前 Alpha schema 的自定义 PRD 模板增加可恢复删除状态。
-- 可重复执行：只补缺失列，不改变既有删除状态、草稿、发布历史或 PRD 引用。@author Andersen
ALTER TABLE prd_templates ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- 前置 V10；会话及其独立文档可恢复删除，不改写正文、历史和定稿。@author Andersen
ALTER TABLE work_sessions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE requirement_versions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_work_sessions_deleted ON work_sessions (deleted_at, id);

-- 前置 V11；区分单独删除文档与随会话回收，重复执行不重置删除状态。@author Andersen
ALTER TABLE requirement_versions ADD COLUMN IF NOT EXISTS independently_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_requirement_versions_deleted ON requirement_versions (deleted_at, id);

-- P3 快速测试等价结构；正式版本与重复执行由 PostgreSQL V13 验证。
ALTER TABLE workspace_runs ADD CONSTRAINT IF NOT EXISTS uq_workspace_run_prototype_owner UNIQUE (id,version_id,project_id,kind);
ALTER TABLE version_artifact_baselines ADD CONSTRAINT IF NOT EXISTS uq_artifact_baseline_kind_owner UNIQUE (id,version_id,kind);

CREATE TABLE IF NOT EXISTS prototypes (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL UNIQUE,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    next_revision_number BIGINT NOT NULL CHECK (next_revision_number >= 1),
    current_revision_id VARCHAR(36),
    latest_confirmation_id VARCHAR(36),
    template_id VARCHAR(80) NOT NULL,
    template_revision BIGINT NOT NULL CHECK (template_revision >= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,version_id),
    UNIQUE (id,version_id,project_id),
    FOREIGN KEY (version_id,project_id) REFERENCES requirement_versions(id,project_id)
);
CREATE TABLE IF NOT EXISTS prototype_sources (
    id VARCHAR(36) PRIMARY KEY,
    prototype_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    source_kind VARCHAR(20) NOT NULL CHECK (source_kind IN ('DRAFT','BASELINE')),
    prd_revision BIGINT NOT NULL CHECK (prd_revision >= 0),
    lifecycle_revision BIGINT NOT NULL CHECK (lifecycle_revision >= 1),
    version_revision BIGINT NOT NULL CHECK (version_revision >= 1),
    prd_baseline_id VARCHAR(36),
    baseline_kind VARCHAR(20) NOT NULL DEFAULT 'PRD' CHECK (baseline_kind='PRD'),
    prd_json TEXT NOT NULL,
    context_json TEXT NOT NULL,
    scope_json TEXT NOT NULL,
    fingerprints_json TEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,prototype_id),
    FOREIGN KEY (prototype_id,version_id) REFERENCES prototypes(id,version_id),
    FOREIGN KEY (prd_baseline_id,version_id,baseline_kind) REFERENCES version_artifact_baselines(id,version_id,kind),
    CHECK ((source_kind='DRAFT' AND prd_baseline_id IS NULL) OR (source_kind='BASELINE' AND prd_baseline_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS prototype_revisions (
    id VARCHAR(36) PRIMARY KEY,
    prototype_id VARCHAR(36) NOT NULL REFERENCES prototypes(id),
    revision_number BIGINT NOT NULL CHECK (revision_number >= 1),
    parent_revision_id VARCHAR(36),
    restored_from_revision_id VARCHAR(36),
    source_id VARCHAR(36) NOT NULL,
    source_run_id VARCHAR(36) UNIQUE,
    template_id VARCHAR(80) NOT NULL,
    template_revision BIGINT NOT NULL CHECK (template_revision >= 1),
    toolchain_id VARCHAR(160) NOT NULL,
    manifest_json TEXT NOT NULL,
    validation_json TEXT NOT NULL,
    artifact_json TEXT NOT NULL,
    mode VARCHAR(30) NOT NULL,
    model VARCHAR(120),
    change_summary TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,prototype_id),
    UNIQUE (prototype_id,revision_number),
    FOREIGN KEY (source_id,prototype_id) REFERENCES prototype_sources(id,prototype_id),
    FOREIGN KEY (parent_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id),
    FOREIGN KEY (restored_from_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id)
);
CREATE TABLE IF NOT EXISTS prototype_run_details (
    run_id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    run_kind VARCHAR(30) NOT NULL DEFAULT 'PROTOTYPE' CHECK (run_kind='PROTOTYPE'),
    prototype_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    operation VARCHAR(20) NOT NULL CHECK (operation IN ('GENERATE','EDIT','SYNC_PRD')),
    base_revision_id VARCHAR(36),
    expected_prototype_revision BIGINT NOT NULL CHECK (expected_prototype_revision >= 1),
    expected_prd_revision BIGINT NOT NULL CHECK (expected_prd_revision >= 0),
    expected_lifecycle_revision BIGINT NOT NULL CHECK (expected_lifecycle_revision >= 1),
    expected_version_revision BIGINT NOT NULL CHECK (expected_version_revision >= 1),
    command_hash VARCHAR(64) NOT NULL,
    target_json TEXT NOT NULL,
    plan_json TEXT,
    phase VARCHAR(30) NOT NULL CHECK (phase IN ('PREPARING','PLANNING','GENERATING','BUILDING','VERIFYING','PERSISTING','FINISHED')),
    repair_attempts INTEGER NOT NULL CHECK (repair_attempts BETWEEN 0 AND 2),
    execution_token VARCHAR(64),
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    result_revision_id VARCHAR(36),
    application_status VARCHAR(30) NOT NULL CHECK (application_status IN ('PENDING','APPLIED','CONFLICT','NOT_APPLICABLE')),
    diagnostic_json TEXT,
    retry_of_run_id VARCHAR(36),
    UNIQUE (run_id,prototype_id),
    FOREIGN KEY (run_id,version_id,project_id,run_kind) REFERENCES workspace_runs(id,version_id,project_id,kind),
    FOREIGN KEY (prototype_id,version_id,project_id) REFERENCES prototypes(id,version_id,project_id),
    FOREIGN KEY (source_id,prototype_id) REFERENCES prototype_sources(id,prototype_id),
    FOREIGN KEY (base_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id),
    FOREIGN KEY (result_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id),
    FOREIGN KEY (retry_of_run_id,prototype_id) REFERENCES prototype_run_details(run_id,prototype_id)
);
CREATE TABLE IF NOT EXISTS prototype_confirmations (
    id VARCHAR(36) PRIMARY KEY,
    prototype_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    prd_baseline_id VARCHAR(36) NOT NULL,
    baseline_kind VARCHAR(20) NOT NULL DEFAULT 'PRD' CHECK (baseline_kind='PRD'),
    scope_json TEXT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,prototype_id),
    UNIQUE (prototype_id,request_id),
    FOREIGN KEY (prototype_id,version_id) REFERENCES prototypes(id,version_id),
    FOREIGN KEY (revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id),
    FOREIGN KEY (prd_baseline_id,version_id,baseline_kind) REFERENCES version_artifact_baselines(id,version_id,kind)
);
CREATE TABLE IF NOT EXISTS prototype_commands (
    id VARCHAR(36) PRIMARY KEY,
    prototype_id VARCHAR(36) NOT NULL REFERENCES prototypes(id),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('APPLY','RESTORE')),
    request_id VARCHAR(64) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    target_revision_id VARCHAR(36) NOT NULL,
    result_revision_id VARCHAR(36) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (prototype_id,kind,request_id),
    FOREIGN KEY (target_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id),
    FOREIGN KEY (result_revision_id,prototype_id) REFERENCES prototype_revisions(id,prototype_id)
);
ALTER TABLE prototypes ADD CONSTRAINT IF NOT EXISTS fk_prototype_current_revision FOREIGN KEY (current_revision_id,id) REFERENCES prototype_revisions(id,prototype_id);
ALTER TABLE prototypes ADD CONSTRAINT IF NOT EXISTS fk_prototype_latest_confirmation FOREIGN KEY (latest_confirmation_id,id) REFERENCES prototype_confirmations(id,prototype_id);
ALTER TABLE prototype_revisions ADD CONSTRAINT IF NOT EXISTS fk_prototype_revision_run FOREIGN KEY (source_run_id,prototype_id) REFERENCES prototype_run_details(run_id,prototype_id);
CREATE INDEX IF NOT EXISTS idx_prototypes_project ON prototypes(project_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_sources ON prototype_sources(prototype_id,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_prototype_revisions ON prototype_revisions(prototype_id,revision_number DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_run_details ON prototype_run_details(prototype_id,expected_prototype_revision DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_confirmations ON prototype_confirmations(prototype_id,created_at DESC,id);
-- H2 不支持部分唯一索引，以活动项目生成列实现相同约束；正式表没有这个辅助列。
ALTER TABLE workspace_runs ADD COLUMN IF NOT EXISTS active_project_id VARCHAR(36) GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED','RUNNING') THEN project_id ELSE NULL END);
CREATE UNIQUE INDEX IF NOT EXISTS uq_workspace_runs_active_project ON workspace_runs(active_project_id);
