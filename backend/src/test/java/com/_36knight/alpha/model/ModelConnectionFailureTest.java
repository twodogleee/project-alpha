package com._36knight.alpha.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证诊断只读取类型与状态码，敏感异常正文不能进入公开摘要。@author Andersen */
class ModelConnectionFailureTest {
    /** 模拟上游回显的凭证和业务正文，只在隔离测试中使用。 */
    private static final String PRIVATE_BODY = "synthetic-private-key echoed-private-body";

    /** SDK 状态前缀必须准确分类，但状态后的错误原文必须丢弃。 */
    @ParameterizedTest
    @CsvSource({"400,REQUEST_REJECTED", "401,AUTHENTICATION", "403,PERMISSION", "404,ENDPOINT_OR_MODEL",
            "405,ENDPOINT_OR_MODEL", "429,RATE_LIMIT_OR_QUOTA", "500,UPSTREAM_SERVER", "502,UPSTREAM_SERVER"})
    void classifiesSdkHttpErrorsWithoutEchoingBodies(int status, String category) {
        RuntimeException upstream = status < 500 ? new NonTransientAiException(status + " - " + PRIVATE_BODY)
                : new TransientAiException(status + " - " + PRIVATE_BODY);
        var result = ModelConnectionFailure.from(new RuntimeException("wrapper " + PRIVATE_BODY, upstream), PRIVATE_BODY);
        assertThat(result.category()).isEqualTo(category);
        assertThat(result.upstreamStatus()).isEqualTo(status);
        assertThat(result.message()).contains("HTTP " + status);
        assertThat(result.toString()).doesNotContain(PRIVATE_BODY);
    }

    /** 类型化 HTTP 响应不依赖错误文本格式。 */
    @Test
    void readsTypedHttpStatus() {
        var result = ModelConnectionFailure.from(new HttpClientErrorException(HttpStatus.FORBIDDEN, PRIVATE_BODY), PRIVATE_BODY);
        assertThat(result.category()).isEqualTo("PERMISSION");
        assertThat(result.upstreamStatus()).isEqualTo(403);
        assertThat(result.toString()).doesNotContain(PRIVATE_BODY);
    }

    /** 普通异常中的数字不能冒充平台状态码。 */
    @Test
    void doesNotParseUntrustedGenericMessagesAsHttpStatus() {
        var result = ModelConnectionFailure.from(new IllegalStateException("401 - " + PRIVATE_BODY), PRIVATE_BODY);
        assertThat(result.category()).isEqualTo("UNKNOWN");
        assertThat(result.upstreamStatus()).isNull();
        assertThat(result.toString()).doesNotContain(PRIVATE_BODY);
    }

    /** 嵌套的网络原因决定诊断，包装异常不泄漏消息。 */
    @Test
    void distinguishesNetworkFailures() {
        assertThat(ModelConnectionFailure.from(new RuntimeException(new UnknownHostException(PRIVATE_BODY)), PRIVATE_BODY).category()).isEqualTo("DNS");
        assertThat(ModelConnectionFailure.from(new RuntimeException(new ConnectException(PRIVATE_BODY)), PRIVATE_BODY).category()).isEqualTo("NETWORK");
        assertThat(ModelConnectionFailure.from(new RuntimeException(new SocketTimeoutException(PRIVATE_BODY)), PRIVATE_BODY).category()).isEqualTo("TIMEOUT");
        assertThat(ModelConnectionFailure.from(new RuntimeException(new SSLHandshakeException(PRIVATE_BODY)), PRIVATE_BODY).category()).isEqualTo("TLS");
    }

    /** JSON 解析错误和字段校验失败不能被描述成鉴权或网络失败。 */
    @Test
    void identifiesStructuredOutputFailures() {
        try {
            new ObjectMapper().readTree("invalid " + PRIVATE_BODY);
            throw new AssertionError("测试正文必须解析失败");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            var result = ModelConnectionFailure.from(new RuntimeException(exception), PRIVATE_BODY);
            assertThat(result.category()).isEqualTo("STRUCTURED_OUTPUT");
            assertThat(result.toString()).doesNotContain(PRIVATE_BODY);
        }
        assertThat(ModelConnectionFailure.invalidOutput().category()).isEqualTo("STRUCTURED_OUTPUT");
    }

    /** 403 的具体上游原因要保留，SDK 包装和密钥回显都不能污染诊断。 */
    @Test
    void preservesUpstreamReasonWithRedactedCredentials() {
        var result = ModelConnectionFailure.from(new NonTransientAiException(
                "403 - {\"error\":{\"code\":\"model_not_allowed\",\"message\":\"Model access denied for " + PRIVATE_BODY + "\"}}"), PRIVATE_BODY);
        assertThat(result.upstreamDetail().code()).isEqualTo("model_not_allowed");
        assertThat(result.upstreamDetail().reason()).isEqualTo("Model access denied for [REDACTED]");
        assertThat(result.message()).contains("Model access denied");
        assertThat(result.toString()).doesNotContain(PRIVATE_BODY);
    }
}
