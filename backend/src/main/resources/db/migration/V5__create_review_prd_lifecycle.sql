-- 正式评审、不可变人工基线和 PRD；不改写 V1–V4。@author Andersen
ALTER TABLE requirement_versions DROP CONSTRAINT requirement_versions_stage_check;
ALTER TABLE requirement_versions ADD CONSTRAINT chk_version_stage CHECK (stage IN ('REVIEW','PRD','PROTOTYPE'));
CREATE TABLE version_lifecycle_state (
 version_id VARCHAR(36) PRIMARY KEY REFERENCES requirement_versions(id),
 revision BIGINT NOT NULL, review_revision BIGINT NOT NULL, prd_revision BIGINT NOT NULL,
 scope_fingerprint VARCHAR(64) NOT NULL, review_json TEXT NOT NULL, prd_json TEXT,
 review_baseline_id VARCHAR(36), prd_baseline_id VARCHAR(36), prd_source_baseline_id VARCHAR(36),
 prd_mode VARCHAR(30), prd_model VARCHAR(120), updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK (revision >= 1 AND review_revision >= 1 AND prd_revision >= 0)
);
CREATE TABLE version_artifact_baselines (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('REVIEW','PRD')), source_revision BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, content_json TEXT NOT NULL, source_baseline_id VARCHAR(36),
 scope_fingerprint VARCHAR(64) NOT NULL, label VARCHAR(120) NOT NULL, goal TEXT NOT NULL, scope TEXT NOT NULL,
 mode VARCHAR(30) NOT NULL, model VARCHAR(120), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (id,version_id), UNIQUE (version_id,request_id),
 FOREIGN KEY (source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
CREATE TABLE version_artifact_revisions (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('REVIEW','PRD','EVENT')), revision BIGINT NOT NULL,
 content_json TEXT NOT NULL, source_baseline_id VARCHAR(36), scope_fingerprint VARCHAR(64) NOT NULL,
 mode VARCHAR(30), model VARCHAR(120), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (version_id,kind,revision),
 FOREIGN KEY (source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
CREATE TABLE version_prd_runs (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES requirement_versions(id),
 baseline_id VARCHAR(36) NOT NULL, prd_revision BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
 mode VARCHAR(30), model VARCHAR(120), result_json TEXT, error_message TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (version_id,request_id),
 FOREIGN KEY (baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id)
);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT fk_current_review_baseline FOREIGN KEY (review_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT fk_current_prd_baseline FOREIGN KEY (prd_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
ALTER TABLE version_lifecycle_state ADD CONSTRAINT fk_prd_review_source FOREIGN KEY (prd_source_baseline_id,version_id) REFERENCES version_artifact_baselines(id,version_id);
CREATE INDEX idx_artifact_history ON version_artifact_revisions(version_id,kind,revision DESC);
CREATE INDEX idx_prd_runs_version ON version_prd_runs(version_id,created_at DESC);
