package com._36knight.alpha.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 从上游错误中提取有限字段并脱敏；完整响应、认证头及密钥不会作为诊断返回。
 *
 * @param code   脱敏后的上游错误代码或类型，最长 120 字符
 * @param reason 脱敏后的单行错误原因，最长 500 字符
 * @author Andersen
 */
record ModelErrorDetail(String code, String reason) {
    /**
     * 独立只读解析器，不修改应用业务 JSON 配置。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * HTML 网关错误只读取页面标题，避免输出页面内容或脚本。
     */
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    /**
     * 常见认证头或凭证赋值；在空白规范化前删除整项。
     */
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)[\"']?(authorization|proxy-authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]*|"
            + "[\"']?(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|token|password|secret)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;&}]+)");
    /**
     * 不依赖当前配置密钥的常见凭证形态，防止平台回显另一枚令牌。
     */
    private static final Pattern TOKEN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\"'}]+|\\bsk-[A-Za-z0-9._-]+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /**
     * 仅保留错误代码和原因；未知 JSON 不回显，超长正文也不进入诊断。
     */
    static ModelErrorDetail parse(String body, String apiKey) {
        if (body == null || body.isBlank()) return new ModelErrorDetail("", "平台未提供错误原因");
        if (body.length() > 20000) return new ModelErrorDetail("", "平台错误正文过长，未输出；请查看平台侧请求记录");
        String text = body.strip();
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                JsonNode root = JSON.readTree(text);
                JsonNode error = root.path("error");
                JsonNode fields = error.isObject() ? error : root;
                String code = first(fields, "code", "error_code", "type");
                String reason = error.isTextual() ? error.asText() : first(fields, "message", "msg", "error_description", "detail");
                if (reason.isBlank() && fields != root) reason = first(root, "message", "msg");
                return new ModelErrorDetail(clean(code, apiKey, 120), reason.isBlank()
                    ? "平台未提供可识别的错误原因" : clean(reason, apiKey, 500));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                return new ModelErrorDetail("", "平台错误 JSON 无法解析，未输出原文");
            }
        }
        if (text.startsWith("<")) {
            var title = HTML_TITLE.matcher(text);
            return new ModelErrorDetail("", title.find() ? clean(title.group(1).replaceAll("<[^>]*>", ""), apiKey, 500)
                : "平台返回 HTML 错误页面，请检查网关或访问限制");
        }
        return new ModelErrorDetail("", clean(text, apiKey, 500));
    }

    /**
     * 只读取简单字符串或数值字段，忽略可能含请求正文的嵌套对象和数组。
     */
    private static String first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() || value.isNumber()) return value.asText();
        }
        return "";
    }

    /**
     * 先移除凭证及编码变体，再清理控制字符并截断，避免截断后泄漏密钥前缀。
     */
    private static String clean(String value, String apiKey, int limit) {
        String result = value;
        if (apiKey != null && !apiKey.isEmpty()) {
            result = result.replace(apiKey, "[REDACTED]")
                .replace(URLEncoder.encode(apiKey, StandardCharsets.UTF_8), "[REDACTED]")
                .replace(Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8)), "[REDACTED]");
            if (apiKey.length() >= 12) result = result.replace(apiKey.substring(0, 12), "[REDACTED]");
        }
        result = CREDENTIAL.matcher(result).replaceAll("[REDACTED]");
        result = TOKEN.matcher(result).replaceAll("[REDACTED]");
        result = result.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Z}\\s]+", " ").strip();
        return result.length() > limit ? result.substring(0, limit) + "…" : result;
    }
}
