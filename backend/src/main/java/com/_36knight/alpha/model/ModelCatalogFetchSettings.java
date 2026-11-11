package com._36knight.alpha.model;

import java.net.URI;

/**
 * 事务内准备、事务外使用的目录请求参数，绝不作为 API 响应。
 *
 * @param url      校验过的同源模型目录地址
 * @param apiKey   仅供此次后端 GET 请求使用的凭证，可为空
 * @param revision 请求准备时的数据库目录版本
 * @author Andersen
 */
record ModelCatalogFetchSettings(URI url, String apiKey, long revision) {
    /**
     * 调试表示仅包含版本，避免隐式字符串化泄漏凭证。
     */
    @Override
    public String toString() {
        return "ModelCatalogFetchSettings[revision=" + revision + "]";
    }
}
