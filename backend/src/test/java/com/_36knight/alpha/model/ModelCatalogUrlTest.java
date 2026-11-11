package com._36knight.alpha.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 目录地址同源与凭证目标的协议、主机、端口边界验证。@author Andersen */
class ModelCatalogUrlTest {
    /** 空值与空字符串都表示关闭获取功能。 */
    @Test void allowsClearingUrl() {
        assertThat(ModelCatalogUrl.normalize(null, "https://example.com/v1")).isNull();
        assertThat(ModelCatalogUrl.normalize(" ", "https://example.com/v1")).isNull();
    }
    /** 标准显式端口和大小写主机仍是同源，本机 HTTP 与现有平台规则一致。 */
    @Test void acceptsSameOriginAndLoopback() {
        assertThat(ModelCatalogUrl.normalize(" https://EXAMPLE.com:443/v1/models ", "https://example.com/v1")).isEqualTo("https://EXAMPLE.com:443/v1/models");
        assertThat(ModelCatalogUrl.normalize("http://127.0.0.1:1234/v1/models", "http://127.0.0.1:1234/v1")).endsWith("/v1/models");
    }
    /** 不允许改变协议、端口或认证目标，也不允许公共 HTTP。 */
    @Test void rejectsDifferentOriginAndUnsafeUrl() {
        for (String target : java.util.List.of("https://example.com:8443/v1/models", "http://example.com/v1/models",
                "https://elsewhere.example/v1/models", "https://user:password@example.com/v1/models", "https://example.com/v1/models?token=value")) {
            assertThatThrownBy(() -> ModelCatalogUrl.normalize(target, "https://example.com/v1")).isInstanceOf(ModelConfigurationException.class);
        }
        assertThatThrownBy(() -> ModelCatalogUrl.normalize("http://example.com/v1/models", "http://example.com/v1")).isInstanceOf(ModelConfigurationException.class);
    }
}
