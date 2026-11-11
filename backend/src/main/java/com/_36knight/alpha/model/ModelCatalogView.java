package com._36knight.alpha.model;

import java.time.Instant;
import java.util.List;

/**
 * 系统设置公开目录；没有明文密钥、密文或主密钥信息。
 *
 * @param revision  所有目录变更共用的并发版本
 * @param runtime   当前运行选择
 * @param platforms 数据库平台列表
 * @param models    数据库模型列表
 * @author Andersen
 */
public record ModelCatalogView(long revision, RuntimeSelection runtime, List<Platform> platforms, List<Model> models) {
    /**
     * 当前选择；mode 为 demo|ai，activeModelId 在 demo 下可为空。
     */
    public record RuntimeSelection(String mode, String activeModelId) {
    }

    /**
     * 平台公开元数据；modelCatalogUrl 可为空，不包含凭证，updatedAt 为 UTC 时间。
     */
    public record Platform(String id, String name, String protocol, String baseUrl, String modelCatalogUrl,
                           Instant updatedAt) {
    }

    /**
     * 模型公开元数据，apiKeyConfigured 只表示存在密文，不表示连通性已验证。
     */
    public record Model(String id, String platformId, String name, String model, boolean apiKeyConfigured,
                        Instant updatedAt) {
    }
}
