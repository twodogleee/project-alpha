-- 平台可选模型目录地址；依赖 V3，保留平台、模型、密文与当前选择。
-- 可重复执行，只补充缺失列；旧平台保持空值，需由用户显式配置。
ALTER TABLE model_platforms ADD COLUMN IF NOT EXISTS model_catalog_url VARCHAR(500);
COMMENT ON COLUMN model_platforms.model_catalog_url IS '可选同源模型列表 GET 地址，不含凭证';
