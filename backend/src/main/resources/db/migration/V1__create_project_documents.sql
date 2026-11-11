-- 当前业务文档与不可变历史独立于 Graph State。@author Andersen
CREATE TABLE project_documents (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document_json JSONB NOT NULL
);
CREATE TABLE project_versions (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES project_documents(id),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document_json JSONB NOT NULL,
    CONSTRAINT uk_project_versions_revision UNIQUE (project_id, revision)
);
CREATE INDEX idx_project_documents_updated ON project_documents (updated_at DESC, id);
COMMENT ON TABLE project_documents IS '项目当前已保存文档，不承担 Graph 恢复';
COMMENT ON TABLE project_versions IS '不可变评审快照，项目内 revision 唯一';
