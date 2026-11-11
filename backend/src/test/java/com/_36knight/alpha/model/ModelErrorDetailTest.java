package com._36knight.alpha.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 诊断提取回归：保留平台原因，过滤凭证、额外字段、控制字符和过长响应。@author Andersen */
class ModelErrorDetailTest {
    /** 模拟私密凭证，不是真实平台密钥。 */
    private static final String KEY = "synthetic+private/key=123456";

    /** 只读取错误字段，嵌套请求或响应不能顺带输出。 */
    @Test
    void readsOnlyRecognizedErrorFields() {
        var result = ModelErrorDetail.parse("{\"error\":{\"type\":\"forbidden\",\"message\":\"Model not permitted\"},"
                + "\"request\":{\"api_key\":\"" + KEY + "\"},\"prompt\":\"private-prompt\"}", KEY);
        assertThat(result.code()).isEqualTo("forbidden");
        assertThat(result.reason()).isEqualTo("Model not permitted");
        assertThat(result.toString()).doesNotContain(KEY, "private-prompt");
        assertThat(ModelErrorDetail.parse("{\"error\":\"Access denied\"}", KEY).reason()).isEqualTo("Access denied");
        assertThat(ModelErrorDetail.parse("{\"code\":403,\"msg\":\"Forbidden request\"}", KEY).code()).isEqualTo("403");
    }

    /** 明文、URL 编码及 Base64 的实际凭证都要在截断前移除。 */
    @Test
    void removesKnownSecretAndEncodedVariants() {
        String encoded = URLEncoder.encode(KEY, StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(KEY.getBytes(StandardCharsets.UTF_8));
        var result = ModelErrorDetail.parse("Access denied " + KEY + " " + encoded + " " + base64, KEY);
        assertThat(result.reason()).startsWith("Access denied").contains("[REDACTED]");
        assertThat(result.toString()).doesNotContain(KEY, encoded, base64);
        var longResult = ModelErrorDetail.parse("x".repeat(490) + KEY, KEY);
        assertThat(longResult.reason()).doesNotContain(KEY.substring(0, 12));
    }

    /** 通用令牌与其他认证头仍需脱敏，不能只依赖当前配置密钥。 */
    @Test
    void removesOtherCredentialShapesAndHeaders() {
        String body = "Forbidden for sk-other-private-token\nAuthorization: Bearer unrelated-private-token\n"
                + "Cookie: session=private-cookie\n\"Authorization\":\"Basic private-other-header\"\napi_key=another-secret token=private-token\n"
                + "reason: model_not_allowed {\"password\":\"private-password\"}";
        var result = ModelErrorDetail.parse(body, KEY);
        assertThat(result.reason()).contains("Forbidden", "model_not_allowed", "[REDACTED]");
        assertThat(result.toString()).doesNotContain("sk-other-private-token", "unrelated-private-token", "private-cookie", "another-secret", "private-token", "private-password", "private-other-header");
    }

    /** 非法 JSON 与未知 JSON 不应退回打印完整原文。 */
    @Test
    void avoidsEchoingUnknownAndMalformedJson() {
        assertThat(ModelErrorDetail.parse("{\"request\":\"private-prompt\"}", KEY).reason()).contains("未提供").doesNotContain("private-prompt");
        assertThat(ModelErrorDetail.parse("{invalid " + KEY, KEY).reason()).contains("无法解析").doesNotContain(KEY);
        assertThat(ModelErrorDetail.parse("[\"private-prompt\"]", KEY).reason()).doesNotContain("private-prompt");
        assertThat(ModelErrorDetail.parse("", KEY).reason()).contains("未提供");
    }

    /** HTML 错误只保留标题，正文、脚本和认证信息不输出。 */
    @Test
    void extractsOnlyHtmlTitle() {
        var result = ModelErrorDetail.parse("<!doctype html><title>Access denied</title><script>" + KEY + "</script><p>private-body</p>", KEY);
        assertThat(result.reason()).isEqualTo("Access denied");
        assertThat(result.toString()).doesNotContain(KEY, "private-body", "script");
        assertThat(ModelErrorDetail.parse("<html><p>private-body</p></html>", KEY).reason()).contains("HTML").doesNotContain("private-body");
    }

    /** 原因按单行显示且有长度上限，超大响应只提供固定提示。 */
    @Test
    void boundsDiagnosticsAndRemovesLogControlCharacters() {
        var result = ModelErrorDetail.parse("Denied\r\n\u001b[31m model not permitted", KEY);
        assertThat(result.reason()).doesNotContain("\r", "\n", "\u001b").contains("model not permitted");
        assertThat(ModelErrorDetail.parse("x".repeat(600), KEY).reason()).hasSize(501).endsWith("…");
        assertThat(ModelErrorDetail.parse("x".repeat(20001), KEY).reason()).contains("过长");
    }
}
