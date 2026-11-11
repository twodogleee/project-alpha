package com._36knight.alpha.model;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 模型测试错误的安全摘要；提取经过脱敏的上游错误字段，不保存完整异常正文或凭证。
 *
 * @param category       固定诊断类别，用于日志检索
 * @param message        可公开的分类提示与脱敏原因，不拼接完整响应
 * @param upstreamStatus 能识别的上游 HTTP 状态码，否则为空
 * @param exceptionType  本地异常类名，不含异常消息或堆栈
 * @param upstreamDetail 可公开的脱敏错误字段，非 HTTP 故障为空
 * @author Andersen
 */
record ModelConnectionFailure(String category, String message, Integer upstreamStatus, String exceptionType,
                              ModelErrorDetail upstreamDetail) {
    /**
     * 当前 Spring AI 错误处理器使用“状态码 - 正文”，数字前缀用于识别 HTTP 状态及正文边界。
     */
    private static final Pattern SDK_STATUS = Pattern.compile("^([45]\\d{2}) - ");

    /**
     * 有界遍历原因链，避免循环引用；优先识别上游状态再识别网络和解析故障。
     */
    static ModelConnectionFailure from(RuntimeException exception, String apiKey) {
        List<Throwable> causes = causes(exception);
        for (Throwable cause : causes) {
            Integer status = status(cause);
            if (status != null)
                return http(status, cause.getClass().getSimpleName(), ModelErrorDetail.parse(body(cause), apiKey));
        }
        for (Throwable cause : causes) {
            String type = cause.getClass().getSimpleName();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return new ModelConnectionFailure("TIMEOUT", "模型请求超时，请检查平台响应速度及后端网络", null, type, null);
            }
            if (cause instanceof UnknownHostException) {
                return new ModelConnectionFailure("DNS", "后端无法解析平台域名，请检查接入地址及 DNS 配置", null, type, null);
            }
            if (cause instanceof SSLException) {
                return new ModelConnectionFailure("TLS", "平台 TLS 连接失败，请检查证书及后端代理配置", null, type, null);
            }
            if (cause instanceof ConnectException) {
                return new ModelConnectionFailure("NETWORK", "后端无法连接平台，请检查网络、代理及接入地址", null, type, null);
            }
            if (cause instanceof JsonProcessingException) return structured(type);
        }
        return new ModelConnectionFailure("UNKNOWN", "模型测试失败，具体原因未能分类；请查看后端安全诊断日志", null,
            exception.getClass().getSimpleName(), null);
    }

    /**
     * 平台响应可解析，但业务字段未满足结构化输出契约。
     */
    static ModelConnectionFailure invalidOutput() {
        return structured("InvalidStructuredOutput");
    }

    /**
     * JSON 解析和业务结构校验共享明确的输出失败提示。
     */
    private static ModelConnectionFailure structured(String type) {
        return new ModelConnectionFailure("STRUCTURED_OUTPUT",
            "平台已返回响应，但结构化结果解析或校验失败；请检查模型是否支持按要求输出需求 JSON", null, type, null);
    }

    /**
     * 已识别状态码使用固定分类提示，并附加经过字段提取及脱敏的具体原因。
     */
    private static ModelConnectionFailure http(int status, String type, ModelErrorDetail detail) {
        String category = switch (status) {
            case 401 -> "AUTHENTICATION";
            case 403 -> "PERMISSION";
            case 404, 405 -> "ENDPOINT_OR_MODEL";
            case 429 -> "RATE_LIMIT_OR_QUOTA";
            default -> status >= 500 ? "UPSTREAM_SERVER" : "REQUEST_REJECTED";
        };
        String explanation = switch (status) {
            case 401 -> "平台鉴权失败，请检查 API Key 是否有效、是否属于当前平台";
            case 403 -> "平台拒绝访问，请检查密钥权限、模型授权或平台访问限制";
            case 404, 405 -> "平台接口或模型不可用，请核对模型标识以及是否支持 /chat/completions";
            case 429 -> "平台限流或额度不足，请检查账户额度、并发限制并稍后重试";
            default -> status >= 500 ? "平台服务或网关异常，请查看平台状态或稍后重试"
                : "平台拒绝请求，请核对接入协议、模型标识及平台支持的参数";
        };
        return new ModelConnectionFailure(category, explanation + "（上游 HTTP " + status + "）；平台原因：" + detail.reason(), status, type, detail);
    }

    /**
     * SDK 将 HTTP 正文包装到异常消息中；只对已识别的 HTTP 异常提取正文。
     */
    private static String body(Throwable cause) {
        if (cause instanceof RestClientResponseException response) return response.getResponseBodyAsString();
        if (cause instanceof NonTransientAiException || cause instanceof TransientAiException) {
            String message = cause.getMessage();
            if (message != null) {
                var match = SDK_STATUS.matcher(message);
                if (match.find()) return message.substring(match.end());
            }
        }
        return "";
    }

    /**
     * 优先读取类型化 HTTP 状态；SDK 丢失响应对象时只读取已知格式的数字前缀。
     */
    private static Integer status(Throwable cause) {
        if (cause instanceof RestClientResponseException response) return response.getStatusCode().value();
        if (cause instanceof NonTransientAiException || cause instanceof TransientAiException) {
            String message = cause.getMessage();
            if (message != null) {
                var match = SDK_STATUS.matcher(message);
                if (match.find()) return Integer.parseInt(match.group(1));
            }
        }
        return null;
    }

    /**
     * 最多读取二十层原因，不提取 suppressed 异常中的可能敏感内容。
     */
    private static List<Throwable> causes(Throwable exception) {
        List<Throwable> result = new ArrayList<>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && result.size() < 20 && seen.add(cause); cause = cause.getCause()) {
            result.add(cause);
        }
        return result;
    }
}
