package com._36knight.alpha.model;

import java.net.URI;
import java.util.Set;

/**
 * 模型目录 URL 的凭证目标边界；只允许平台同源地址，且不接受 URL 中的凭证。@author Andersen
 */
final class ModelCatalogUrl {
    /**
     * 纯静态校验，不维护可变状态。
     */
    private ModelCatalogUrl() {
    }

    /**
     * 空值明确清除；非空地址只允许 HTTPS 或本机 HTTP，保留用户填写的 GET 路径。
     */
    static String normalize(String input, String baseUrl) {
        if (input == null || input.isBlank()) return null;
        try {
            String value = input.strip();
            URI target = URI.create(value);
            URI base = URI.create(baseUrl);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(target.getHost());
            if (value.length() > 500 || target.getHost() == null || target.getUserInfo() != null
                || target.getRawQuery() != null || target.getRawFragment() != null || target.getPath().isEmpty()
                || target.getPort() == 0 || target.getPort() > 65535
                || !("https".equals(target.getScheme()) || "http".equals(target.getScheme()) && loopback)
                || !target.getScheme().equals(base.getScheme()) || !target.getHost().equalsIgnoreCase(base.getHost())
                || port(target) != port(base)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ModelConfigurationException.invalid("模型列表地址需要与接入地址同源的有效 HTTPS URL，不包含账号、查询参数或片段；本机可用 HTTP");
        }
    }

    /**
     * 未显式填写的标准端口与显式填写同一端口视为同源。
     */
    private static int port(URI uri) {
        return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
}
