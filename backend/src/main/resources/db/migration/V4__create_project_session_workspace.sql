-- 单人桌面工作空间：独立项目、会话与交付版本；不导入旧评审历史。@author Andersen
CREATE TABLE workspace_projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    background TEXT NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    CHECK (revision >= 1)
);
CREATE TABLE work_sessions (
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
CREATE TABLE session_messages (
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
CREATE TABLE workspace_runs (
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
    CHECK (kind IN ('CHAT','DRAFT')),
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED'))
);
CREATE TABLE session_draft_revisions (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    source_run_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id) REFERENCES work_sessions(id),
    UNIQUE (session_id, revision)
);
CREATE TABLE requirement_versions (
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
    CHECK (stage = 'REVIEW'),
    CHECK (lifecycle IN ('ACTIVE','CANCELED'))
);
CREATE TABLE requirement_version_revisions (
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
ALTER TABLE work_sessions ADD CONSTRAINT fk_session_version_project FOREIGN KEY (version_id, project_id) REFERENCES requirement_versions(id, project_id);
CREATE INDEX idx_workspace_projects_updated ON workspace_projects (updated_at DESC, id);
CREATE INDEX idx_work_sessions_updated ON work_sessions (updated_at DESC, id);
CREATE INDEX idx_requirement_versions_updated ON requirement_versions (updated_at DESC, id);
CREATE INDEX idx_workspace_runs_session ON workspace_runs (session_id, created_at DESC);
