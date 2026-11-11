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

-- 前置 V1–V12；新增原型聚合、不可变来源/修订、任务扩展与人工操作记录。
-- 仅作用于当前 Alpha schema；可重复执行以补缺表、索引和命名约束，不覆盖既有业务数据。
-- 已存在同名表的异常字段不会自动修复；活动任务唯一索引发现重复活动任务时会拒绝升级，不擅自终结任务。
-- 在 PostgreSQL 事务中执行；不读取环境凭证，不清理文件产物。@author Andersen
ALTER TABLE workspace_runs DROP CONSTRAINT IF EXISTS workspace_runs_kind_check;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='workspace_runs'::regclass AND conname='workspace_runs_kind_check') THEN
        ALTER TABLE workspace_runs ADD CONSTRAINT workspace_runs_kind_check CHECK (kind IN ('CHAT','DRAFT','WORKBENCH','PROTOTYPE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='workspace_runs'::regclass AND conname='uq_workspace_run_prototype_owner') THEN
        ALTER TABLE workspace_runs ADD CONSTRAINT uq_workspace_run_prototype_owner UNIQUE (id,version_id,project_id,kind);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='version_artifact_baselines'::regclass AND conname='uq_artifact_baseline_kind_owner') THEN
        ALTER TABLE version_artifact_baselines ADD CONSTRAINT uq_artifact_baseline_kind_owner UNIQUE (id,version_id,kind);
    END IF;
END $$;

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
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='prototypes'::regclass AND conname='fk_prototype_current_revision') THEN
        ALTER TABLE prototypes ADD CONSTRAINT fk_prototype_current_revision FOREIGN KEY (current_revision_id,id) REFERENCES prototype_revisions(id,prototype_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='prototypes'::regclass AND conname='fk_prototype_latest_confirmation') THEN
        ALTER TABLE prototypes ADD CONSTRAINT fk_prototype_latest_confirmation FOREIGN KEY (latest_confirmation_id,id) REFERENCES prototype_confirmations(id,prototype_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='prototype_revisions'::regclass AND conname='fk_prototype_revision_run') THEN
        ALTER TABLE prototype_revisions ADD CONSTRAINT fk_prototype_revision_run FOREIGN KEY (source_run_id,prototype_id) REFERENCES prototype_run_details(run_id,prototype_id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_prototypes_project ON prototypes(project_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_sources ON prototype_sources(prototype_id,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_prototype_revisions ON prototype_revisions(prototype_id,revision_number DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_run_details ON prototype_run_details(prototype_id,expected_prototype_revision DESC);
CREATE INDEX IF NOT EXISTS idx_prototype_confirmations ON prototype_confirmations(prototype_id,created_at DESC,id);
-- 旧 PRD 任务位于另一张表，跨种类互斥仍在同一项目根锁内核对。
CREATE UNIQUE INDEX IF NOT EXISTS uq_workspace_runs_active_project ON workspace_runs(project_id) WHERE status IN ('QUEUED','RUNNING');
