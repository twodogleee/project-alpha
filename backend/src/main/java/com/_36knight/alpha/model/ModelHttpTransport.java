package com._36knight.alpha.model;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

/** 模型同步 HTTP 的有界传输，限制接入时长和响应体，不记录请求或响应正文。@author Andersen */
public final class ModelHttpTransport {
    /** JSON 包装及转义后的响应体最大 16 MiB，文本另由业务边界校验。 */
    private static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

    /** 工具类只提供受控客户端构造。 */
    private ModelHttpTransport() { }

    /** 连接最多十秒、单次响应最多三分钟，拒绝超大响应且不跟随凭证重定向。 */
    public static RestClient.Builder restClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(180));
        return RestClient.builder().requestFactory(requestFactory)
            .requestInterceptor((request, body, execution) -> {
                ClientHttpResponse response = execution.execute(request, body);
                if (response.getHeaders().getContentLength() > MAX_RESPONSE_BYTES) {
                    response.close();
                    throw new IOException("模型响应超过传输上限");
                }
                return new BoundedResponse(response);
            });
    }

    /** 在 JSON 转换之前统计实际读取的响应字节，分块传输同样受限。 */
    private static final class BoundedResponse implements ClientHttpResponse {
        /** 原始响应只用于读取协议状态和释放连接。 */
        private final ClientHttpResponse delegate;
        /** 同一响应复用一个计数流，防止重复获取流重置限额。 */
        private InputStream body;

        /** 包装已建立的响应，不缓存或打印正文。 */
        private BoundedResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        /** 状态码保持 SDK 既有错误分类。 */
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }

        /** 状态文本只转发给 SDK，不用于日志。 */
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }

        /** 关闭响应时释放底层连接。 */
        @Override public void close() { delegate.close(); }

        /** 保留解析正文所需的内容类型等头信息。 */
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }

        /** 每个字节经过上限检查，不能通过缺失 Content-Length 绕过限额。 */
        @Override
        public InputStream getBody() throws IOException {
            if (body == null) body = new LimitedInputStream(delegate.getBody());
            return body;
        }
    }

    /** 超限立即失败，绝不把截断响应当作有效模型内容。 */
    private static final class LimitedInputStream extends FilterInputStream {
        /** 当前响应已读取字节数。 */
        private long count;

        /** 绑定底层响应流并从零统计。 */
        private LimitedInputStream(InputStream input) { super(input); }

        /** 单字节读取遵循同一全量限额。 */
        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result >= 0) increase(1);
            return result;
        }

        /** 批量读取只申请剩余预算加一个探测字节。 */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int bounded = (int) Math.min(length, MAX_RESPONSE_BYTES - count + 1);
            int result = in.read(bytes, offset, bounded);
            if (result > 0) increase(result);
            return result;
        }

        /** 跳过也计入传输量，防止转换器跳读绕过限制。 */
        @Override
        public long skip(long amount) throws IOException {
            long skipped = in.skip(Math.min(amount, MAX_RESPONSE_BYTES - count + 1));
            increase(skipped);
            return skipped;
        }

        /** 超过限制只抛固定诊断，不含响应片段。 */
        private void increase(long amount) throws IOException {
            count += amount;
            if (count > MAX_RESPONSE_BYTES) throw new IOException("模型响应超过传输上限");
        }
    }
}
