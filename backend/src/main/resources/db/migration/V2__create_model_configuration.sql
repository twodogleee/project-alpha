-- 单用户工作台的模型配置；前置 V1，作用范围为当前 Alpha schema。@author Andersen
-- 可重复建表，不预置模式/模型/密钥，也不覆盖用户已有配置。
CREATE TABLE IF NOT EXISTS model_configuration (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('demo', 'ai')),
    platform VARCHAR(30) NOT NULL CHECK (platform IN ('dashscope', 'openai', 'deepseek', 'custom')),
    model VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key_ciphertext TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE model_configuration IS '单份模型配置，API Key 仅保存 AES-GCM 密文';
