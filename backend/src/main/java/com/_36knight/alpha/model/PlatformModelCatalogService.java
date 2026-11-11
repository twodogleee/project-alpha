package com._36knight.alpha.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 显式读取上游模型目录；网络不占用数据库事务，不跟随重定向，不推理或保存模型。
 * 只接受 OpenAI 模型目录的 data 数组，限制响应大小、模型数和整体请求时间。
 * @author Andersen
 */
@Service
public class PlatformModelCatalogService {
    /** 脱敏诊断，不记录原始响应、请求头或异常堆栈。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlatformModelCatalogService.class);
    /** 成功目录最多读取 1 MiB，避免任意长度的外部响应耗尽内存。 */
    private static final int MAX_BODY = 1024 * 1024;
    /** 一次选择最多提供 1000 个模型，超限明确失败而不静默截断。 */
    private static final int MAX_MODELS = 1000;
    /** 整体超时包含读取正文，防止平台仅返回响应头后一直等待。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** 只读目录准备平台地址和此次请求的凭证。 */
    private final ModelCatalogService catalog;
    /** 使用项目 JSON 解析器，只读取模型 id。 */
    private final ObjectMapper json;
    /** 不跟随重定向，不维护 Cookie，不使用 SDK 的模型推理接口。 */
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 注入目录快照与 JSON 契约，不读取环境默认密钥。 */
    public PlatformModelCatalogService(ModelCatalogService catalog, ObjectMapper json) { this.catalog = catalog; this.json = json; }

    /** 获取临时目录；校验失败不发起网络，HTTP 失败只公开脱敏原因及诊断编号。 */
    public PlatformModelsView fetch(String platformId, FetchPlatformModelsRequest request) {
        var settings = catalog.catalogFetchSettings(platformId, request);
        var builder = HttpRequest.newBuilder(settings.url()).timeout(TIMEOUT).header("Accept", "application/json").GET();
        if (!settings.apiKey().isEmpty()) builder.header("Authorization", "Bearer " + settings.apiKey());
        var pending = client.sendAsync(builder.build(), response -> new LimitedBody(response.statusCode() >= 400 ? 20000 : MAX_BODY));
        try {
            var response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw rejected("REDIRECT", "模型列表接口发生重定向，请填写平台最终的同源地址；后端未跟随跳转");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                var failure = ModelConnectionFailure.from(new RestClientResponseException("", response.statusCode(), "", null,
                        response.body(), StandardCharsets.UTF_8), settings.apiKey());
                throw failed(failure);
            }
            return new PlatformModelsView(settings.revision(), parse(response.body(), settings.apiKey()));
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw failed(ModelConnectionFailure.from(new IllegalStateException(new HttpTimeoutException("目录请求超时")), settings.apiKey()));
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw rejected("INTERRUPTED", "模型列表获取已中断，请稍后重试");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof CatalogBodyTooLarge) throw rejected("RESPONSE_TOO_LARGE", "平台模型列表或错误响应过大，已停止读取");
            throw failed(ModelConnectionFailure.from(new IllegalStateException(exception.getCause()), settings.apiKey()));
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    /** 限定 data[].id 契约，忽略不能保存的标识和疑似凭证；不返回外部名称、描述或原始对象。 */
    private List<String> parse(byte[] body, String apiKey) {
        try {
            var root = json.readTree(body);
            var data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()) throw rejected("INVALID_RESPONSE", "模型列表响应需要包含 data 数组及模型 id，请检查列表地址是否正确");
            if (data.size() > MAX_MODELS) throw rejected("TOO_MANY_MODELS", "平台返回超过 1000 条模型，请配置返回较小目录的接口");
            var ids = new TreeSet<String>();
            for (var item : data) {
                var id = item.get("id");
                if (id == null || !id.isTextual()) continue;
                String value = id.asText();
                if (value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")
                        && ModelErrorDetail.parse(value, apiKey).reason().equals(value)) ids.add(value);
            }
            return List.copyOf(ids);
        } catch (java.io.IOException exception) {
            throw rejected("INVALID_RESPONSE", "平台未返回可解析的模型目录 JSON，请检查模型列表地址；未输出原文");
        }
    }

    /** 复用连接诊断的脱敏边界，但目录错误不误报推理接口或结构化需求失败。 */
    private ModelConfigurationException failed(ModelConnectionFailure failure) {
        String diagnosticId = UUID.randomUUID().toString();
        var detail = failure.upstreamDetail();
        LOG.warn("模型列表获取失败：diagnosticId={}, category={}, upstreamStatus={}, exceptionType={}, upstreamCode={}, upstreamReason={}",
                diagnosticId, failure.category(), failure.upstreamStatus(), failure.exceptionType(),
                detail == null ? "" : detail.code(), detail == null ? "" : detail.reason());
        String message = failure.upstreamStatus() == null ? switch (failure.category()) {
            case "TIMEOUT" -> "模型列表请求超时，请检查平台响应速度及后端网络";
            case "UNKNOWN" -> "平台请求失败，具体原因未能分类；请查看后端安全诊断日志";
            default -> failure.message();
        }
                : "平台模型列表请求失败（上游 HTTP " + failure.upstreamStatus() + "）；平台原因：" + detail.reason();
        return new ModelConfigurationException(HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_FETCH_FAILED", "模型列表获取失败：" + message + "；诊断编号：" + diagnosticId);
    }

    /** 本地协议限制使用固定提示，不从异常正文生成页面消息。 */
    private ModelConfigurationException rejected(String category, String message) {
        String diagnosticId = UUID.randomUUID().toString();
        LOG.warn("模型列表获取失败：diagnosticId={}, category={}", diagnosticId, category);
        return new ModelConfigurationException(HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_FETCH_FAILED", message + "；诊断编号：" + diagnosticId);
    }

    /** 响应超过限制时中止订阅，无异常正文或凭证。 */
    private static final class CatalogBodyTooLarge extends RuntimeException { }

    /** 按流有界读取，达到限制立即取消，而不是在完整下载后才检查大小。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        /** 本次 HTTP 状态对应的大小限制。 */
        private final int limit;
        /** 完成状态由 HTTP 客户端与整体超时共同管理。 */
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        /** 只存储限制以内的正文。 */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /** 超限时取消后续网络读取。 */
        private Flow.Subscription subscription;
        /** 指定此响应的最大字节数。 */
        private LimitedBody(int limit) { this.limit = limit; }
        /** 返回客户端等待的有界正文结果。 */
        @Override public CompletionStage<byte[]> getBody() { return result; }
        /** 每次只请求一批网络数据。 */
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        /** 写入前检查大小，超过限制取消并明确失败。 */
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new CatalogBodyTooLarge()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        /** 传递网络原因供安全分类，不自行记录原始错误。 */
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        /** 仅返回限制以内的完成正文。 */
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
