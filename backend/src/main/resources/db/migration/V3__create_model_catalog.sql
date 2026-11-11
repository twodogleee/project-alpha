-- 前置 V2；作用范围当前 Alpha schema。@author Andersen
-- 新建目录，初始数据只补缺失项；迁移旧配置保留原密文及旧表，不回写旧数据。
CREATE TABLE IF NOT EXISTS model_platforms (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    protocol VARCHAR(30) NOT NULL CHECK (protocol IN ('dashscope', 'openai-compatible')),
    base_url VARCHAR(500) NOT NULL,
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
INSERT INTO model_platforms (id,name,protocol,base_url,updated_at) VALUES
 ('platform-dashscope','阿里云百炼','dashscope','https://dashscope.aliyuncs.com',CURRENT_TIMESTAMP),
 ('platform-openai','OpenAI','openai-compatible','https://api.openai.com/v1',CURRENT_TIMESTAMP),
 ('platform-deepseek','DeepSeek','openai-compatible','https://api.deepseek.com/v1',CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO model_profiles (id,platform_id,name,model,updated_at) VALUES
 ('model-qwen-plus','platform-dashscope','通义千问 Plus','qwen-plus',CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
INSERT INTO model_platforms (id,name,protocol,base_url,updated_at)
 SELECT 'platform-migrated-v2','已迁移平台 · ' || platform,
 CASE WHEN platform='dashscope' THEN 'dashscope' ELSE 'openai-compatible' END,base_url,updated_at
 FROM model_configuration WHERE id=1 ON CONFLICT DO NOTHING;
INSERT INTO model_profiles (id,platform_id,name,model,api_key_ciphertext,updated_at)
 SELECT 'model-migrated-v2','platform-migrated-v2','原模型配置',model,api_key_ciphertext,updated_at
 FROM model_configuration WHERE id=1 ON CONFLICT DO NOTHING;
INSERT INTO model_runtime_selection (id,revision,mode,active_model_id,updated_at)
 SELECT 1,revision,CASE WHEN mode='ai' AND api_key_ciphertext IS NOT NULL THEN 'ai' ELSE 'demo' END,
 'model-migrated-v2',updated_at FROM model_configuration WHERE id=1 ON CONFLICT DO NOTHING;
INSERT INTO model_runtime_selection (id,revision,mode,active_model_id,updated_at)
 VALUES (1,1,'demo',NULL,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
COMMENT ON TABLE model_platforms IS '可维护接入平台，协议由已实现适配器限定';
COMMENT ON TABLE model_profiles IS '模型目录，API Key 仅 AES-GCM 密文';
COMMENT ON TABLE model_runtime_selection IS '单用户应用当前运行选择及目录并发版本';
